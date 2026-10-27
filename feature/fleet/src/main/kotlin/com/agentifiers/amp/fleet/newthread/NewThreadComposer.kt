package com.agentifiers.amp.fleet.newthread

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpInputBar
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.AgentMode
import com.agentifiers.amp.model.Draft
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.NewThreadSpec
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbProviderSource
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.RunnerInfo
import com.agentifiers.amp.model.SendMode
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadIntent
import com.agentifiers.amp.model.Visibility
import com.agentifiers.amp.model.WorktreeChoice
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Outcome
import com.agentifiers.amp.sync.api.StartedThread
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

sealed interface NewThreadStage {
    data object Composing : NewThreadStage
    data object Starting : NewThreadStage

    /** The thread exists without an executor. Retry and Choose Another Executor respawn on it, never create another. */
    data class SpawnFailed(val thread: ThreadId, val reason: String) : NewThreadStage
}

private enum class Sheet { Executor, Mode }

fun defaultMode(modes: List<AgentMode>): AgentMode? = modes.firstOrNull { it.key == "medium" } ?: modes.firstOrNull()

fun executorLabel(choice: ExecutorChoice?, runners: List<RunnerInfo>): String = when (choice) {
    null -> "Choose"
    ExecutorChoice.NoMachine -> "No machine"
    is ExecutorChoice.Orb -> when (val provider = choice.provider) {
        OrbProvider.Amp -> "Orb · ${choice.size.key}"
        is OrbProvider.SelfHosted -> "Orb · ${provider.name} · ${choice.size.key}"
    }
    is ExecutorChoice.Runner -> {
        val host = runners.firstOrNull { it.id == choice.runner }?.hostname ?: choice.runner.raw
        val worktree = when (val tree = choice.worktree) {
            WorktreeChoice.InPlace -> ""
            is WorktreeChoice.NewWorktree -> " · new worktree ${tree.branch}"
        }
        "$host · ${lastSegment(choice.directory)}$worktree"
    }
}

internal fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/')

private const val OFFLINE_NOTICE = "Starting a thread needs a connection."

/** Null for Ok. Used for both a failed start and a failed respawn. */
internal fun startFailure(outcome: Outcome<*>): String? = when (outcome) {
    is Outcome.Ok -> null
    is Outcome.Failed -> if (outcome.error == EngineError.Offline) OFFLINE_NOTICE else "Couldn't start the thread: ${shortReason(outcome.error)}"
    Outcome.Unknown -> "Couldn't start the thread: Amp did not answer"
    is Outcome.Refused -> "Couldn't start the thread: ${outcome.why}"
}

private fun shortReason(error: EngineError): String = when (error) {
    EngineError.Offline -> "no connection"
    EngineError.Unauthorized -> "signed out"
    EngineError.StepUpRequired -> "a passkey check is required"
    EngineError.PaymentRequired -> "out of credits"
    is EngineError.NotFound -> "${error.what} not found"
    is EngineError.Server -> error.message.ifBlank { error.code }
    is EngineError.Drift -> "Amp answered in an unexpected shape"
    else -> "this build cannot do that yet"
}

/** Executor, Project, and Mode pickers over the first message and Send, at the pane's bottom. The thread is created only when Send succeeds. */
@Composable
fun NewThreadComposer(dest: PaneDestination.NewThread, scope: AccountScope, navigate: (PaneDestination) -> Unit, orbProviders: OrbProviderSource, modifier: Modifier = Modifier) {
    val runners by scope.catalog.runners.collectAsStateWithLifecycle()
    val projects by scope.catalog.projects.collectAsStateWithLifecycle()
    val modes by scope.catalog.modes.collectAsStateWithLifecycle()
    val coroutines = rememberCoroutineScope()

    var text by rememberSaveable { mutableStateOf(dest.prefill?.text.orEmpty()) }
    var stage by remember { mutableStateOf<NewThreadStage>(NewThreadStage.Composing) }
    var notice by remember { mutableStateOf<String?>(null) }
    var executor by remember { mutableStateOf<ExecutorChoice?>(null) }
    var chosenMode by remember { mutableStateOf<AgentMode?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val mode = chosenMode ?: defaultMode(modes)
    val starting = stage == NewThreadStage.Starting

    fun opened(thread: ThreadId) {
        stage = NewThreadStage.Composing
        text = ""
        navigate(PaneDestination.Thread(thread))
    }

    fun start(chosen: ExecutorChoice, agentMode: AgentMode) {
        stage = NewThreadStage.Starting
        notice = null
        coroutines.launch {
            val outcome = scope.startThread(NewThreadSpec(Draft.text(text.trim()), agentMode, chosen, persistentListOf(), Visibility.Private))
            when (val started = (outcome as? Outcome.Ok)?.value) {
                is StartedThread.Running -> opened(started.thread)
                is StartedThread.SpawnFailed -> stage = NewThreadStage.SpawnFailed(started.thread, started.reason)
                null -> {
                    notice = startFailure(outcome)
                    stage = NewThreadStage.Composing
                }
            }
        }
    }

    fun respawn(thread: ThreadId, chosen: ExecutorChoice) {
        stage = NewThreadStage.Starting
        coroutines.launch {
            val failure = startFailure(scope.respawn(thread, chosen))
            if (failure == null) {
                scope.thread(thread).dispatch(ThreadIntent.Send(Draft.text(text.trim()), SendMode.Auto))
                opened(thread)
            } else {
                stage = NewThreadStage.SpawnFailed(thread, failure)
            }
        }
    }

    val spacing = AmpTheme.spacing
    val chosen = executor
    Column(modifier.fillMaxSize().background(LocalPaneSurface.current).testTag(ThreadTags.newThread)) {
        AmpPaneHeader(title = "New thread", testTag = FleetTags.newThreadHeader, kind = "newthread")
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.padding(start = spacing.composerMargin, end = spacing.composerMargin, bottom = spacing.composerMargin),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.actionGap), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                AmpChip(
                    label = "Executor: ${executorLabel(executor, runners)}",
                    selected = false,
                    onClick = { sheet = Sheet.Executor },
                    testTag = ThreadTags.composerExecutor,
                    trailingIcon = AmpIcons.ChevronDown,
                    enabled = !starting,
                )
                ProjectPicker(projectPick(executor), projects, enabled = !starting) { project -> executor = executor?.withProject(project) }
                AmpChip(
                    label = "Mode: ${mode?.display ?: "Choose"}",
                    selected = false,
                    onClick = { sheet = Sheet.Mode },
                    testTag = ThreadTags.composerMode,
                    trailingIcon = AmpIcons.ChevronDown,
                    enabled = !starting,
                )
            }
            if (starting) Text("Starting…", style = AmpTheme.type.secondary, color = AmpTheme.colors.text.inkMuted)
            ((stage as? NewThreadStage.SpawnFailed)?.reason ?: notice)?.let {
                Text(it, style = AmpTheme.type.secondary, color = AmpTheme.colors.status.danger, modifier = Modifier.testTag(ThreadTags.newThreadNotice))
            }
            (stage as? NewThreadStage.SpawnFailed)?.let { current ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.actionGap)) {
                    AmpButton("Retry", onClick = { executor?.let { respawn(current.thread, it) } }, testTag = ThreadTags.composerSpawnRetry, rank = AmpButtonRank.Primary)
                    AmpButton("Choose Another Executor", onClick = { sheet = Sheet.Executor }, testTag = ThreadTags.composerSpawnOther)
                    AmpButton(
                        "Discard",
                        onClick = {
                            scope.thread(current.thread).dispatch(ThreadIntent.Archive(true))
                            stage = NewThreadStage.Composing
                        },
                        testTag = ThreadTags.composerSpawnDiscard,
                    )
                }
            }
            AmpInputBar(
                value = text,
                onValueChange = { text = it },
                placeholder = "Message the agent",
                testTag = ThreadTags.composerInput,
                enabled = !starting,
                maxLines = 12,
                trailing = {
                    AmpIconButton(
                        AmpIcons.Send,
                        "Send",
                        onClick = { if (chosen != null && mode != null) start(chosen, mode) },
                        testTag = ThreadTags.composerSend,
                        enabled = stage == NewThreadStage.Composing && chosen != null && mode != null && text.isNotBlank(),
                        primary = true,
                    )
                },
            )
        }
    }

    when (sheet) {
        Sheet.Executor -> ExecutorPicker(
            scope = scope,
            prompt = text,
            orbProviders = orbProviders,
            navigate = { destination ->
                sheet = null
                navigate(destination)
            },
            onPick = { choice ->
                sheet = null
                executor = choice
                (stage as? NewThreadStage.SpawnFailed)?.let { respawn(it.thread, choice) }
            },
            onDismiss = { sheet = null },
        )
        Sheet.Mode -> ModePicker(
            scope = scope,
            executor = executor,
            selected = mode,
            onPick = {
                sheet = null
                chosenMode = it
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}
