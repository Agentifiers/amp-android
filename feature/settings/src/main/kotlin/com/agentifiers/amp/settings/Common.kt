package com.agentifiers.amp.settings

import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.design.AmpTheme
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.SettingsTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpMenuItem
import com.agentifiers.amp.design.components.AmpRowMore
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.agentifiers.amp.model.AccountResource
import com.agentifiers.amp.sync.api.AccountScope
import com.agentifiers.amp.sync.api.EngineError
import com.agentifiers.amp.sync.api.Loadable
import com.agentifiers.amp.sync.api.Outcome
import java.util.WeakHashMap

internal fun EngineError.text(): String = when (this) {
    EngineError.Offline -> "You're offline."
    EngineError.Unauthorized -> "Amp rejected this account's sign-in."
    EngineError.StepUpRequired -> "Amp needs a passkey confirmation on the web first."
    EngineError.PaymentRequired -> "This needs more Amp credits."
    is EngineError.NotFound -> "Amp couldn't find $what."
    is EngineError.Server -> message.ifBlank { "Amp answered with $code." }
    is EngineError.Drift -> "Amp answered in a way this app doesn't understand ($where)."
    // The engine's build-unit sentinel, the one EngineError outside this list.
    else -> "This isn't available in this build yet."
}

/** Null when the write applied. */
internal fun Outcome<*>.failureText(): String? = when (this) {
    is Outcome.Ok -> null
    is Outcome.Failed -> error.text()
    Outcome.Unknown -> "The connection dropped before Amp answered, so this change may not have applied."
    is Outcome.Refused -> why
}

/**
 * Settings pages, the index, and the account list are list columns inset `listInset`, like A2's sidebar. Prose,
 * fields, and buttons in them take this inset too, so their text starts at paneX where a row's text starts.
 */
internal fun Modifier.rowInset(): Modifier = padding(horizontal = AmpTheme.spacing.rowPadX)

/** A settings page's group title, above the Note that describes the group. The index's group labels use AmpSectionLabel. */
@Composable
internal fun Heading(text: String) {
    Text(
        text,
        color = AmpTheme.colors.text.ink,
        style = AmpTheme.type.rowTitle,
        modifier = Modifier.rowInset().padding(top = AmpTheme.spacing.lg, bottom = AmpTheme.spacing.xs).semantics { heading() }.testTag(SettingsTags.settingsSection(text)),
    )
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary, modifier = modifier.rowInset())
}

/** A row's quiet ⋮, tagged [testTag], opening [items] in the overlay menu MachinePane's ControlsMenu uses. */
@Composable
internal fun RowMenu(testTag: String, contentDescription: String, items: List<AmpMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        AmpRowMore({ open = true }, testTag, contentDescription)
        AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, style = AmpTheme.type.body) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                    modifier = Modifier.testTag(item.testTag),
                    enabled = item.enabled,
                    leadingIcon = item.icon?.let { { Icon(it, contentDescription = null, Modifier.size(AmpTheme.metrics.icon)) } },
                    colors = MenuDefaults.itemColors(textColor = AmpTheme.colors.text.ink, leadingIconColor = AmpTheme.colors.text.inkMuted),
                )
            }
        }
    }
}

/**
 * One account setting read with its three states. [AccountScope.read] returns the same flow per key and
 * starts a new fetch when called for a key that failed, so Retry is another read.
 */
@Composable
internal fun <T> Loaded(scope: AccountScope, resource: AccountResource<T>, content: @Composable (T) -> Unit) {
    val flow = remember(scope, resource) { scope.read(resource) }
    val state by flow.collectAsStateWithLifecycle()
    when (val loaded = state) {
        Loadable.Loading -> Note("Loading…", Modifier.testTag(SettingsTags.settingsLoading))
        is Loadable.Failed -> Column(Modifier.rowInset()) {
            Text(loaded.error.text(), color = AmpTheme.colors.status.danger, style = AmpTheme.type.secondary, modifier = Modifier.testTag(SettingsTags.settingsLoadError))
            AmpButton("Retry", { scope.read(resource) }, SettingsTags.settingsRetry)
        }
        is Loadable.Ready -> content(loaded.value)
    }
}

/**
 * Sets FLAG_SECURE on the activity window while composed (auth-and-security.md, "Secrets stay out of
 * screenshots"). Holders are counted per window, so one secret leaving does not unhide another pane's.
 */
@Composable
internal fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.activity()?.window
        window?.let(SecureWindows::hold)
        onDispose { window?.let(SecureWindows::release) }
    }
}

private object SecureWindows {
    private val holders = WeakHashMap<Window, Int>()

    fun hold(window: Window) {
        val count = holders[window] ?: 0
        if (count == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        holders[window] = count + 1
    }

    fun release(window: Window) {
        val count = holders[window] ?: return
        if (count > 1) {
            holders[window] = count - 1
        } else {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

internal fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun Context.clearClipboard() {
    getSystemService(ClipboardManager::class.java).clearPrimaryClip()
}
