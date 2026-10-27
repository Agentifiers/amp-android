// :feature:thread  package amp.thread
// The thread pane: title bar, transcript, gate dock, composer. Reads one StateFlow<ThreadState>.
// Leases are held by :app's VisibleThreadLeases for every visible pane; this file never attaches.
package amp.thread

import amp.design.AmpTag
import amp.model.*
import amp.sync.api.AccountScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Panes take (destination, scope). :app's PaneHost passes the window's AccountScope, so no feature
 * reads a CompositionLocal for engine access and :layout never learns about :sync.
 */
@Composable
fun ThreadPane(dest: PaneDestination.Thread, scope: AccountScope): Unit = TODO(
    """
    val thread = remember(dest.id) { scope.thread(dest.id) }
    val state by thread.state.collectAsStateWithLifecycle()
    Column(Modifier.testTag(AmpTag.Thread.pane(dest.id))) {
        ThreadTitleBar(state.header, state.link, state.viewer, thread::dispatch)
        LinkBanner(state.link, state.drift, onConnectLive = thread::connectLive)   // Link.OrbPaused → [Connect live] after the cost notice
        when (val t = state.transcript) {
            TranscriptView.Loading -> TranscriptSkeleton()
            is TranscriptView.Ready -> TranscriptList(t, state.activity, thread::dispatch, thread::loadEarlier)
        }
        QueueStrip(state.queue, thread::dispatch)
        GateDock(state.gates, thread::dispatch, onOpen = navigate)
        Composer(state.composer, thread::dispatch)
    }
    """
)

/**
 * LazyColumn keyed by TurnKey/StepKey. Collapsed StepGroups are one node each until expanded.
 * Node budget: a test renders a 2,000-message fixture and asserts the semantics tree < 5000 nodes
 * after scrolling to top, middle and bottom. Auto-scroll sticks to bottom only while at bottom;
 * "Jump to latest" is a real button.
 */
@Composable
fun TranscriptList(t: TranscriptView.Ready, activity: AgentActivity, onIntent: (ThreadIntent) -> Unit, onLoadEarlier: () -> Unit): Unit = TODO()

/** Shows the first Answerable gate expanded, others as chips; ReadOnly gates show the reason. */
@Composable
fun GateDock(gates: List<HumanGate>, onIntent: (ThreadIntent) -> Unit, onOpen: (PaneDestination) -> Unit): Unit = TODO(
    "ToolApprovalGate → full input, [Approve] [Deny…]; ChoiceGate → option buttons + Submit; PluginGate.* → native form " +
    "(SecretInput → ThreadIntent.AnswerSecret); ElicitationGate.OpenUrl → [Open in browser] then [Continue]; " +
    "UnsupportedGate → its kindLabel + [Answer on web] = Web(AccountPage(ThreadOnWeb, account, \"/<id>\")) in the account's WebView profile, no native responder"
)

/**
 * Composer. Actions come from ComposerState.actions (derived in :sync from AgentActivity):
 * Idle → [Send]; Busy → [Steer] (primary, default per core.md:224) [Queue] [Interrupt], and
 * [Interrupt and send] when the field holds text. Each action dispatches ComposerAction.intents in order.
 * Hardware keys follow docs/design/screens.md "Keyboard shortcuts". Long-press on Send also queues
 * (iOS parity) but Queue is always a real button.
 */
@Composable
fun Composer(c: ComposerState, onIntent: (ThreadIntent) -> Unit): Unit = TODO()

@Composable
fun QueueStrip(queue: List<QueuedMessage>, onIntent: (ThreadIntent) -> Unit): Unit = TODO(
    "each row: preview + [Steer now] [Up] [Down] [Remove] real buttons; reorder → ReorderQueue"
)

/**
 * Title, mode chip, project, executor badge, presence avatars, [Share], and a [⋯] menu of real
 * buttons. The arrangement control is in the window bar, not here. The menu's [Watch] toggles
 * Fleet.setWatched for this thread (notifications.md); the outbox's Unknown and Dropped entries for
 * this thread show here with [Retry] / [Discard].
 */
@Composable
fun ThreadTitleBar(header: ThreadHeader, link: Link, viewer: Viewer, onIntent: (ThreadIntent) -> Unit): Unit = TODO()
