package com.agentifiers.amp.sync.internal.push

import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster
import com.agentifiers.amp.model.AuthHealth
import com.agentifiers.amp.model.GateResponse
import com.agentifiers.amp.model.HumanGate
import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.BackgroundAccess
import com.agentifiers.amp.sync.api.BackgroundAccessProvider
import com.agentifiers.amp.sync.api.FleetSignal
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.PushChannel
import com.agentifiers.amp.sync.api.PushEvent
import com.agentifiers.amp.sync.api.RelayRoute
import com.agentifiers.amp.sync.api.SettledGate
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Base64
import kotlin.time.Instant

/** Routes background work to the signed-in account's [AccountBackground]; a signed-out account refuses everything. */
internal class PushBackgroundProvider(
    roster: StateFlow<Loadable<AccountRoster>>,
    scope: CoroutineScope,
    override val hasPushRelay: Boolean,
    private val signedIn: () -> Map<AccountId, AccountBackground>,
) : BackgroundAccessProvider {

    override val accounts: StateFlow<PersistentSet<AccountId>> = roster
        .map { loaded -> (loaded as? Loadable.Ready)?.value?.accounts.orEmpty().filter { it.auth != AuthHealth.NeedsSignIn }.map { it.id }.toPersistentSet() }
        .stateIn(scope, SharingStarted.Eagerly, persistentSetOf())

    override fun forAccount(account: AccountId): BackgroundAccess = signedIn()[account] ?: SignedOut

    override suspend fun onFcmToken(token: String): Outcome<Unit> {
        for (background in signedIn().values) {
            when (val registered = background.ensurePushRegistration(PushChannel.Relay(token))) {
                is Outcome.Ok -> background.reconcilePushSubscriptions()
                is Outcome.Failed -> return registered
                is Outcome.Refused -> return registered
                Outcome.Unknown -> return Outcome.Unknown
            }
        }
        return Outcome.Ok(Unit)
    }

    /** `{v: "1", rid, body | wake}` (relay/README.md). */
    override fun routeRelayMessage(data: Map<String, String>): RelayRoute {
        if (data["v"] != "1") return RelayRoute.Dropped("unknown version ${data["v"]}")
        val rid = data["rid"] ?: return RelayRoute.Dropped("no rid")
        val account = signedIn().entries.firstOrNull { it.value.relayId() == rid }?.key
            ?: return RelayRoute.Dropped("no signed-in account has rid ${rid.take(6)}")
        if (data["wake"] == "1") return RelayRoute.Deliver(account, null)
        val body = data["body"]?.let { runCatching { Base64.getUrlDecoder().decode(it) }.getOrNull() }
            ?: return RelayRoute.Dropped("unreadable body")
        return RelayRoute.Deliver(account, body)
    }

    private object SignedOut : BackgroundAccess {
        private const val WHY = "This account is signed out."
        override suspend fun recentStatuses(sinceSeen: Instant): Outcome<PersistentList<ThreadSummary>> = Outcome.Refused(WHY)
        override suspend fun peekGates(thread: ThreadId): Outcome<PersistentList<HumanGate>> = Outcome.Refused(WHY)
        override suspend fun answer(thread: ThreadId, response: GateResponse): Outcome<Unit> = Outcome.Refused(WHY)
        override suspend fun quickReply(thread: ThreadId, text: String): Outcome<MessageId> = Outcome.Refused(WHY)
        override suspend fun markRead(thread: ThreadId, upTo: MessageId): Outcome<Unit> = Outcome.Refused(WHY)
        override suspend fun ensurePushRegistration(channel: PushChannel): Outcome<String> = Outcome.Refused(WHY)
        override suspend fun reconcilePushSubscriptions(): Outcome<Int> = Outcome.Refused(WHY)
        override fun decodePush(body: ByteArray): PushEvent? = null
        override fun watchFleet(): Flow<FleetSignal> = emptyFlow()
        override val watched: StateFlow<PersistentSet<ThreadId>> = MutableStateFlow(persistentSetOf())
        override fun setWatched(thread: ThreadId, watching: Boolean) = Unit
        override val leasedThreads: StateFlow<PersistentSet<ThreadId>> = MutableStateFlow(persistentSetOf())
        override val settledGates: Flow<SettledGate> = emptyFlow()
    }
}
