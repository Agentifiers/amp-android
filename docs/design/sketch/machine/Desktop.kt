// :waymote  package amp.waymote   +  :feature:desktop  package amp.desktop
// Native Waymote client (private-protocol.md:244-253). Not VNC, not WebRTC: three byte channels
// (video, audio, control) opened through Machine.desktop(target) → RelayChannelFactory, which hides
// the relay service names and the direct-portal-WebSocket fallback inside :sync.
//
// NOT VERIFIED: the desktop relay service names, and pointer messages were not decoded from
// e88f9517.js (private-protocol.md:252, :325). Shipping Desktop natively requires decoding them
// first. Until then DesktopTab offers [Open on web] (AccountWebPage.ThreadOnWeb in the account's
// WebView profile), a visible degraded mode, never a silent failure.
package amp.waymote

import amp.sync.api.RelayChannel

/** Video: binary frames, header ≥ 40 bytes, byte0=2 byte1=1, byte2 bit0 = keyframe; codec string from JSON config. */
interface VideoPipeline {
    /** MediaCodec configured from the config message's codec string (e.g. avc1/hvc1/av01 → MIME). */
    fun configure(codec: String, width: Int, height: Int, surface: android.view.Surface)
    fun submit(frame: ByteArray, keyframe: Boolean, ptsUs: Long)
    /** Feedback {queue, dropped, fps, rtt, active} sent on control every second. */
    fun stats(): FeedbackStats
    fun release()
}
data class FeedbackStats(val queue: Int, val dropped: Int, val fps: Float, val rttMs: Int, val active: Boolean)

/** Audio: 24-byte header, byte0=2 byte1=2, u64 LE ts at 12, u32 config generation at 20; Opus via MediaCodec → AudioTrack. */
interface AudioPipeline { fun submit(frame: ByteArray); fun mute(muted: Boolean); fun release() }

/** Control JSON: key, text, clipboard-write, display-select{id}, reset, samples, ping; pointer [to decode]. */
sealed interface ControlOut {
    data class Key(val code: String, val down: Boolean, val mods: Int) : ControlOut
    data class Text(val text: String) : ControlOut
    data class ClipboardWrite(val text: String) : ControlOut
    data class SelectDisplay(val id: String) : ControlOut
    data class Pointer(val x: Float, val y: Float, val buttons: Int, val wheelDx: Float, val wheelDy: Float) : ControlOut  // schema TBD
    data object Reset : ControlOut
    data class Ping(val id: Int) : ControlOut
}
sealed interface ControlIn {
    data class Config(val codec: String, val width: Int, val height: Int) : ControlIn
    data class Displays(val displays: List<amp.model.DesktopDisplay>, val selected: String, val switching: Boolean, val error: String?) : ControlIn
    data class Presented(val displayId: String) : ControlIn
    data class Pong(val id: Int) : ControlIn
}

interface WaymoteSession {
    suspend fun start(video: RelayChannel, audio: RelayChannel, control: RelayChannel, surface: android.view.Surface)
    fun send(msg: ControlOut)
    val incoming: kotlinx.coroutines.flow.Flow<ControlIn>
    /** Resolution request snaps to multiples of 16 within 320×180..6000×6000, ≤ 8,294,400 px. */
    fun requestResolution(widthPx: Int, heightPx: Int)
    fun stop()
}

// ── :feature:desktop ──────────────────────────────────────────────────────────
// DesktopTab(dest, tab: MachineTab.Desktop, scope) in :feature:desktop, called from :app's MachinePane.
// The tab appears only when the executor reports a desktop (MachineCaps.desktop is Yes).
// AndroidExternalSurface (SurfaceView) tagged AmpTag.Machine.desktopSurface, a
// toolbar of real buttons (Keyboard, Trackpad/Direct, Display ▾, Clipboard, Take over / Hand back,
// Fullscreen, PiP), client_desktop_viewing{display} sent while visible (via :sync, internal).
// Input modes: Direct (tap = click at point), Trackpad (relative cursor, two-finger scroll,
// long-press-drag), hardware mouse/keyboard pass-through (DeX). Resolution follows the slot size,
// so moving Desktop from a one-panel slot to Focus re-requests resolution. Product.md defers Desktop
// to a later release; until pointer messages are decoded the tab renders the [Open on web] state.
