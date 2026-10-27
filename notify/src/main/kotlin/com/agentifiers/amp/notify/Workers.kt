package com.agentifiers.amp.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.Answerability
import com.agentifiers.amp.model.GateId
import com.agentifiers.amp.model.GateResponses
import com.agentifiers.amp.model.JsonValue
import com.agentifiers.amp.model.PluginOutcome
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.store.LedgerState
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.GateTokens
import com.agentifiers.amp.sync.api.Outcome
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** WorkManager tag for one account's work, cancelled at sign-out by [AccountTeardown]. */
internal fun accountTag(account: AccountId) = "account:${account.raw}"

private fun NoticeKey.toData(vararg extra: Pair<String, String>): Data = Data.Builder()
    .putString("account", account.raw).putString("thread", thread.raw).putString("tag", tag).putString("title", title)
    .apply { extra.forEach { (k, v) -> putString(k, v) } }
    .build()

private fun Data.noticeKey(): NoticeKey? {
    val account = getString("account")?.let(::AccountId) ?: return null
    val thread = getString("thread")?.let(ThreadId::parse) ?: return null
    return NoticeKey(account, thread, getString("tag") ?: return null, getString("title").orEmpty())
}

private inline fun <reified W : CoroutineWorker> enqueueExpedited(context: Context, key: NoticeKey, data: Data) {
    val request = OneTimeWorkRequestBuilder<W>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInputData(data)
        .addTag(accountTag(key.account))
        .build()
    WorkManager.getInstance(context).enqueue(request)
}

/**
 * After `approval_required` or a needs-you status: a short socket reads the thread's pending gates, and the
 * notification gains the tool and its input with the buttons that fit (`notifications.md`).
 */
internal class GateFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.noticeKey() ?: return Result.failure()
        val notifier = NotifyRuntime.notifier
        when (val gates = NotifyRuntime.background(key.account).peekGates(key.thread)) {
            is Outcome.Ok -> {
                val gate = gates.value.firstOrNull { it.answerability == Answerability.Answerable } ?: gates.value.firstOrNull()
                if (gate == null) notifier.postOpenOnly(key, "Open Amp to see what needs you.") else notifier.postGate(key, gate)
            }
            is Outcome.Failed, is Outcome.Refused, Outcome.Unknown -> notifier.postOpenOnly(key, "Open Amp to see what needs you.")
        }
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context, key: NoticeKey) = enqueueExpedited<GateFetchWorker>(context, key, key.toData())
    }
}

/**
 * A notification button's work: a Reply through the outbox, or a gate answer over a short socket. The receiver only
 * enqueues this, so an answer that waits for its settle event is not cut off by the broadcast time limit.
 */
internal class ActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val key = inputData.noticeKey() ?: return Result.failure()
        val background = NotifyRuntime.background(key.account)
        val notifier = NotifyRuntime.notifier
        val text = inputData.getString(TEXT)
        when (inputData.getString(KIND)) {
            REPLY -> {
                val reply = text ?: return Result.failure()
                val outcome = background.quickReply(key.thread, reply)
                val shown = if (outcome is Outcome.Ok) "You: $reply" else "Reply not sent yet: ${explain(outcome)}"
                notifier.resolve(key, AmpChannel.Finished, shown, LedgerState.Answered)
            }
            ANSWER, ANSWER_TEXT -> {
                val response = inputData.getString(TOKEN)?.let(GateTokens::decode)
                    ?: inputData.getString(GATE)?.let { gate -> text?.let { GateResponses.pluginAnswer(GateId(gate), PluginOutcome.Result(JsonValue.Str(it))) } }
                    ?: return Result.failure().also { notifier.postOpenOnly(key, "This notification is from an older version. Open Amp to answer.") }
                val outcome = background.answer(key.thread, response)
                val resolved = inputData.getString(RESOLVED) ?: "Answered"
                val shown = when {
                    outcome is Outcome.Ok -> resolved
                    outcome is Outcome.Refused -> outcome.why
                    outcome is Outcome.Failed && outcome.error == EngineError.StepUpRequired -> "Open Amp to confirm with your passkey."
                    else -> "Not sent: ${explain(outcome)}"
                }
                notifier.resolve(key, AmpChannel.NeedsYou, "$shown · ${inputData.getString(SUMMARY).orEmpty()}".trimEnd(' ', '·'), LedgerState.Answered)
            }
        }
        return Result.success()
    }

    private fun explain(outcome: Outcome<*>): String = when (outcome) {
        is Outcome.Ok -> "sent"
        is Outcome.Refused -> outcome.why
        Outcome.Unknown -> "no answer from Amp"
        is Outcome.Failed -> when (val error = outcome.error) {
            EngineError.Offline -> "offline"
            EngineError.Unauthorized -> "signed out"
            is EngineError.Server -> error.message
            else -> error.toString()
        }
    }

    companion object {
        const val KIND = "kind"
        const val REPLY = "reply"
        const val ANSWER = "answer"
        const val ANSWER_TEXT = "answer-text"
        const val TEXT = "text"
        const val TOKEN = "token"
        const val GATE = "gate"
        const val RESOLVED = "resolved"
        const val SUMMARY = "summary"

        fun enqueue(context: Context, key: NoticeKey, vararg extra: Pair<String, String>) =
            enqueueExpedited<ActionWorker>(context, key, key.toData(*extra))
    }
}

/**
 * The backstop that runs in every keep-alive mode: subscribes recently active threads that have no subscription from
 * this device, turns a needs-you indicator into a notification even when no push came (S15c), and, with the setting
 * on, posts turns that ended without a push. While a turn runs it checks again every 3 minutes, for at most 2 hours.
 */
internal class PollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val provider = NotifyRuntime.provider
        val only = inputData.getString("account")?.let(::AccountId)
        val chainStartedMs = inputData.getLong(CHAIN_STARTED, 0L).takeIf { it > 0L }
        val finishes = NotifyWhenOpenElsewhere.current()
        for (account in provider.accounts.value.filter { only == null || it == only }) {
            val background = provider.forAccount(account)
            when (val added = background.reconcilePushSubscriptions()) {
                is Outcome.Ok -> Log.i(PushInbox.TAG, "poll: ${added.value} subscription(s) added")
                else -> Log.w(PushInbox.TAG, "poll: subscribing failed: $added")
            }
            val recent = background.recentStatuses(Clock.System.now() - 24.hours) as? Outcome.Ok ?: continue
            for (summary in recent.value) {
                LocalTurns.needsYou(applicationContext, account, summary, background.leasedThreads.value)
                if (finishes) LocalTurns.finished(account, summary, "poll")
            }
            if (finishes && recent.value.any(TurnEnds::working)) checkTurnsLater(applicationContext, account, chainStartedMs)
        }
        Log.i(PushInbox.TAG, "poll: ${provider.accounts.value.size} account(s)")
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "amp-poll"
        private const val CHAIN_STARTED = "chainStartedMs"
        const val TURN_CHECKS = "amp-turn-checks"
        private const val CHAIN_GAP_MINUTES = 3L
        private val CHAIN_LIMIT_MS = TimeUnit.HOURS.toMillis(2)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE, not KEEP: an update that renamed this worker's class replaces the old spec at the next start.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** A relay wake message: the account's sync, once and expedited. */
        fun runNow(context: Context, account: AccountId) {
            val request = OneTimeWorkRequestBuilder<PollWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString("account", account.raw).build())
                .addTag(accountTag(account))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("amp-wake-${account.raw}", ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * The next link of the account's 3-minute chain. [chainStartedMs] is null outside the chain, which starts one
         * unless a link is already queued; a link appends its successor, so a running link never cancels itself. Not
         * expedited, because WorkManager refuses an initial delay on expedited work. Doze and app standby defer it.
         */
        fun checkTurnsLater(context: Context, account: AccountId, chainStartedMs: Long?) {
            val now = System.currentTimeMillis()
            val started = chainStartedMs ?: now
            if (now - started >= CHAIN_LIMIT_MS) {
                Log.i(PushInbox.TAG, "poll: turn checks stop, 2 h since they started")
                return
            }
            val request = OneTimeWorkRequestBuilder<PollWorker>()
                .setInitialDelay(CHAIN_GAP_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(Data.Builder().putString("account", account.raw).putLong(CHAIN_STARTED, started).build())
                .addTag(accountTag(account))
                .addTag(TURN_CHECKS)
                .build()
            val policy = if (chainStartedMs == null) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
            WorkManager.getInstance(context).enqueueUniqueWork("$TURN_CHECKS-${account.raw}", policy, request)
        }
    }
}
