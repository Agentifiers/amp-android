package com.agentifiers.amp.settings

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.AmpWordmark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import com.agentifiers.amp.design.LocalPaneCreases
import com.agentifiers.amp.design.SettingsTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.components.AmpPaneHeader
import com.agentifiers.amp.design.components.AmpSecureTextField
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.ServerEnvironment
import com.agentifiers.amp.sync.api.AccountCandidate
import com.agentifiers.amp.sync.api.SignIn
import com.agentifiers.amp.sync.api.SignInFailure
import com.agentifiers.amp.sync.api.SignInStep
import kotlinx.coroutines.flow.Flow

private enum class Method { Device, Browser, Paste }

/** A plain class on purpose: its identity keys the collecting effect, and no toString can print the token its flow captured. */
private class Attempt(val method: Method, val steps: Flow<SignInStep>)

/** Where the screen is. Each method's steps move it through [after]. */
private sealed interface Stage {
    data object Welcome : Stage
    data class ServerEntry(val invalid: Boolean) : Stage
    data class Device(val code: SignInStep.ShowCode?, val expired: Boolean) : Stage
    data class Browser(val url: String?) : Stage
    data class Paste(val failure: SignInFailure?) : Stage
    data class Confirm(val candidate: AccountCandidate, val method: Method, val adding: Boolean) : Stage
    data class Failed(val reason: SignInFailure, val method: Method) : Stage
}

private fun Stage.after(method: Method, step: SignInStep): Stage = when (step) {
    is SignInStep.ShowCode -> Stage.Device(step, expired = false)
    SignInStep.Polling, is SignInStep.SignedIn -> this
    is SignInStep.OpenBrowser -> Stage.Browser(step.url)
    is SignInStep.Confirm -> Stage.Confirm(step.candidate, method, adding = false)
    is SignInStep.Failed -> when {
        method == Method.Device && step.reason == SignInFailure.Expired -> Stage.Device((this as? Stage.Device)?.code, expired = true)
        method == Method.Paste -> Stage.Paste(step.reason)
        else -> Stage.Failed(step.reason, method)
    }
}

/** Failures after which another method may still work (auth-and-security.md, "Device flow is the default"). */
private val otherMethodsMayWork = setOf(SignInFailure.NotAmpServer, SignInFailure.ServerRejected, SignInFailure.Network)

private fun SignInFailure.text(host: String): String = when (this) {
    SignInFailure.Expired -> "The sign-in expired. Try again."
    SignInFailure.Denied -> "The sign-in was denied in the browser."
    SignInFailure.Network -> "Couldn't reach $host. Check your connection."
    SignInFailure.ServerRejected -> "$host rejected the sign-in."
    SignInFailure.TooManyAccounts -> ACCOUNT_LIMIT_TEXT
    SignInFailure.NotAmpServer -> "This server did not return Amp sign-in settings"
    SignInFailure.AlreadySignedIn -> "This account is already signed in"
    SignInFailure.TokenRejected -> "Amp rejected this token."
}

internal const val ACCOUNT_LIMIT_TEXT = "You can keep up to 5 accounts signed in."

/** The sign-in page as people would type it: host and path, without the query that carries the code. */
internal fun verificationLinkText(url: String): String = url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')

/**
 * Sign-in: the device flow by default, loopback PKCE in the browser and a pasted access token under
 * **Other ways to sign in**, and the account confirmation every method ends with (auth-and-security.md).
 */
@Composable
fun SignInScreen(signIn: SignIn, host: SignInHost, modifier: Modifier = Modifier) {
    var server by remember(host.defaultServer) { mutableStateOf(host.defaultServer) }
    var stage by remember { mutableStateOf<Stage>(Stage.Welcome) }
    var attempt by remember { mutableStateOf<Attempt?>(null) }
    var privateTab by remember { mutableStateOf(false) }
    val serverInput = rememberTextFieldState()
    val token = rememberTextFieldState()

    LaunchedEffect(attempt) {
        val run = attempt ?: return@LaunchedEffect
        run.steps.collect { step ->
            when (step) {
                is SignInStep.OpenBrowser -> host.openBrowser(step.url, privateTab)
                is SignInStep.Confirm -> host.bringToFront()
                is SignInStep.SignedIn -> host.onSignedIn(step.account.id)
                else -> Unit
            }
            stage = stage.after(run.method, step)
            if (step is SignInStep.Confirm) token.clearText()
        }
        attempt = null
    }

    fun start(method: Method) {
        val steps = when (method) {
            Method.Device -> signIn.start(server)
            Method.Browser -> signIn.browser(server)
            Method.Paste -> signIn.paste(server, token.text.toString())
        }
        stage = when (method) {
            Method.Device -> Stage.Device(code = null, expired = false)
            Method.Browser -> Stage.Browser(url = null)
            Method.Paste -> Stage.Paste(failure = null)
        }
        attempt = Attempt(method, steps)
    }

    fun backToWelcome() {
        attempt = null
        token.clearText()
        stage = Stage.Welcome
    }

    val back = when (stage) {
        is Stage.Device, is Stage.Browser, is Stage.Paste, is Stage.Failed -> ::backToWelcome
        Stage.Welcome, is Stage.ServerEntry, is Stage.Confirm -> null
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag(SettingsTags.signinScreen)) {
        back?.let { onBack ->
            AmpPaneHeader(
                "Sign In",
                SettingsTags.signinHeader,
                navigation = { AmpIconButton(AmpIcons.ArrowLeft, "Back", onBack, SettingsTags.signinBack) },
            )
        }
        FullScreenColumn(Modifier.weight(1f).fillMaxWidth()) {
            AmpWordmark(height = AmpTheme.spacing.xxl)
            host.notice?.let { Text(it, color = AmpTheme.colors.status.danger, style = AmpTheme.type.body, modifier = Modifier.testTag(SettingsTags.signinNotice)) }
            when (val current = stage) {
                Stage.Welcome -> Welcome(
                    server = server,
                    isDefaultServer = server == host.defaultServer,
                    onSignIn = { start(Method.Device) },
                    onAdvanced = {
                        serverInput.setTextAndPlaceCursorAtEnd(server.ampBaseUrl)
                        stage = Stage.ServerEntry(invalid = false)
                    },
                    onBrowser = { start(Method.Browser) },
                    onPaste = { stage = Stage.Paste(failure = null) },
                    onCancel = host.onCancel,
                )
                is Stage.ServerEntry -> ServerEntry(
                    input = serverInput,
                    invalid = current.invalid,
                    allowLocalHttp = host.allowLocalHttp,
                    onSave = {
                        val parsed = ServerEnvironment.fromUserInput(serverInput.text.toString(), host.allowLocalHttp)
                        if (parsed == null) {
                            stage = Stage.ServerEntry(invalid = true)
                        } else {
                            server = parsed
                            stage = Stage.Welcome
                        }
                    },
                    onCancel = { stage = Stage.Welcome },
                )
                is Stage.Device -> DeviceCode(
                    stage = current,
                    host = server.host,
                    privateTab = privateTab,
                    onPrivateTab = { privateTab = it },
                    onOpen = { url -> host.openBrowser(url, privateTab) },
                    onNewCode = { start(Method.Device) },
                )
                is Stage.Browser -> BrowserWait(
                    stage = current,
                    host = server.host,
                    privateTab = privateTab,
                    onPrivateTab = { privateTab = it },
                    onOpen = { url -> host.openBrowser(url, privateTab) },
                )
                is Stage.Paste -> PasteToken(
                    token = token,
                    failure = current.failure?.text(server.host),
                    checking = attempt?.method == Method.Paste,
                    onSubmit = { start(Method.Paste) },
                )
                is Stage.Confirm -> Confirm(
                    stage = current,
                    onAdd = {
                        stage = current.copy(adding = true)
                        current.candidate.add()
                    },
                    onCancel = {
                        current.candidate.cancel()
                        stage = Stage.Welcome
                    },
                )
                is Stage.Failed -> Failed(
                    stage = current,
                    host = server.host,
                    onRetry = { start(current.method) },
                    onBrowser = { start(Method.Browser) },
                    onPaste = { stage = Stage.Paste(failure = null) },
                )
            }
        }
    }
}

/**
 * The widest the content gets: the 448dp column (14 × xxl) sign-in had, less its xl sides. That fits the device code and
 * a guidance line of about 60 characters.
 */
private val FullScreenContentMax = AmpTheme.spacing.xxl * 14 - AmpTheme.spacing.xl * 2

/**
 * Where sign-in and the lock screen put their content across a screen [width] wide with [creases] at these x offsets. The
 * content keeps to the panel that holds the screen's center, lg in from a crease and xl in from a screen edge, at most
 * [FullScreenContentMax] wide and centered in that room. With no crease, or one on the center, the panel is the screen.
 */
internal fun fullScreenColumn(width: Dp, creases: List<Dp>): ClosedRange<Dp> {
    val spacing = AmpTheme.spacing
    val center = width / 2
    val start = creases.lastOrNull { it < center }?.plus(spacing.lg) ?: spacing.xl
    val end = creases.firstOrNull { it > center }?.minus(spacing.lg) ?: (width - spacing.xl)
    val content = minOf(end - start, FullScreenContentMax)
    val left = start + (end - start - content) / 2
    return left..left + content
}

/**
 * The column sign-in and the lock screen share, so the two cannot drift: [fullScreenColumn] across, centered on the
 * height of [modifier]'s box, content left-aligned with xl between groups, scrolling when it runs taller than the box.
 */
@Composable
internal fun FullScreenColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val column = fullScreenColumn(maxWidth, LocalPaneCreases.current)
        Column(
            Modifier.padding(start = column.start).width(column.endInclusive - column.start).verticalScroll(rememberScrollState()).padding(vertical = AmpTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xl),
            content = content,
        )
    }
}

/** Every button on sign-in and the lock screen: one full-width stack, primary first, each as tall as a field. */
internal val FullScreenButton = Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.field)

/** One group of a full-screen step. The column puts xl between groups; a group puts sm between its parts. */
@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm), content = content)
}

@Composable
private fun Title(text: String) {
    Text(text, color = AmpTheme.colors.text.ink, style = AmpTheme.type.headline)
}

@Composable
private fun Guidance(text: String) {
    Text(text, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary)
}

@Composable
private fun Welcome(
    server: ServerEnvironment,
    isDefaultServer: Boolean,
    onSignIn: () -> Unit,
    onAdvanced: () -> Unit,
    onBrowser: () -> Unit,
    onPaste: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Title("Sign in to Amp")
    Group {
        AmpButton("Sign In", onSignIn, SettingsTags.signinStart, FullScreenButton, rank = AmpButtonRank.Primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Server: ${server.host}", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary, modifier = Modifier.weight(1f))
            AmpButton(if (isDefaultServer) "Advanced" else "Change", onAdvanced, SettingsTags.signinAdvanced, rank = AmpButtonRank.Link)
        }
    }
    OtherWays(onBrowser, onPaste)
    onCancel?.let { AmpButton("Cancel", it, SettingsTags.signinCancel, FullScreenButton) }
}

@Composable
private fun OtherWays(onBrowser: (() -> Unit)?, onPaste: () -> Unit) {
    Group {
        Text("Other ways to sign in", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.label, modifier = Modifier.semantics { heading() })
        onBrowser?.let {
            AmpButton("Sign In with Browser", it, SettingsTags.signinBrowser, FullScreenButton)
        }
        AmpButton("Paste an Access Token", onPaste, SettingsTags.signinPaste, FullScreenButton)
    }
}

@Composable
private fun ServerEntry(input: TextFieldState, invalid: Boolean, allowLocalHttp: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    Group {
        Title("Server URL")
        Guidance("Use this when your workspace requires its own domain. Only the address is kept; any path is dropped.")
    }
    Group {
        AmpTextField(
            state = input,
            testTag = SettingsTags.signinServerUrl,
            modifier = Modifier.fillMaxWidth(),
            label = "Server URL",
            errorText = if (invalid) {
                val rule = if (allowLocalHttp) "an https address, or http for localhost, 127.0.0.1 or 10.0.2.2" else "an https address"
                "Enter $rule, such as https://amp.example.com."
            } else {
                null
            },
            errorTestTag = SettingsTags.signinError,
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            onKeyboardAction = { onSave() },
        )
        AmpButton("Use This Server", onSave, SettingsTags.signinServerSave, FullScreenButton, rank = AmpButtonRank.Primary)
        AmpButton("Cancel", onCancel, SettingsTags.signinServerCancel, FullScreenButton)
    }
}

@Composable
private fun PrivateTabSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = AmpTheme.metrics.row)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .testTag(SettingsTags.signinPrivateTab),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.rowTitleMetaGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Use a private browser tab", color = AmpTheme.colors.text.ink, style = AmpTheme.type.rowTitle)
            Guidance("The browser keeps no ampcode.com session afterward.")
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun DeviceCode(
    stage: Stage.Device,
    host: String,
    privateTab: Boolean,
    onPrivateTab: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onNewCode: () -> Unit,
) {
    val context = LocalContext.current
    val code = stage.code
    Group {
        Title("Approve this code on $host")
        Text(
            code?.userCode ?: "········",
            color = if (stage.expired) AmpTheme.colors.text.inkMuted else AmpTheme.colors.text.ink,
            style = AmpTheme.type.deviceCode,
            modifier = Modifier.testTag(SettingsTags.signinCode),
        )
        val status = when {
            stage.expired -> "Code expired"
            code == null -> "Requesting a code from $host…"
            else -> "Waiting for approval"
        }
        Text(status, color = if (stage.expired) AmpTheme.colors.status.danger else AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body, modifier = Modifier.testTag(SettingsTags.signinStatus))
    }
    if (stage.expired) {
        AmpButton("Get New Code", onNewCode, SettingsTags.signinNewCode, FullScreenButton, rank = AmpButtonRank.Primary)
        return
    }
    val live = code != null
    Group {
        AmpButton(
            "Open Sign-in Page",
            { code?.let { onOpen(it.verificationUrl) } },
            SettingsTags.signinOpenBrowser,
            FullScreenButton,
            rank = AmpButtonRank.Primary,
            enabled = live,
        )
        AmpButton(
            "Copy Code",
            { code?.let { context.copyText("Amp sign-in code", it.userCode) } },
            SettingsTags.signinCopyCode,
            FullScreenButton,
            leadingIcon = AmpIcons.Copy,
            enabled = live,
        )
    }
    PrivateTabSwitch(privateTab, onPrivateTab)
    code?.let {
        Group {
            Guidance("You can also approve on another device at ${verificationLinkText(it.verificationUrl)}")
            AmpButton(
                "Copy Link",
                { context.copyText("Amp sign-in link", it.verificationUrl) },
                SettingsTags.signinCopyLink,
                FullScreenButton,
                leadingIcon = AmpIcons.Link,
            )
        }
    }
}

@Composable
private fun BrowserWait(
    stage: Stage.Browser,
    host: String,
    privateTab: Boolean,
    onPrivateTab: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    Group {
        Title("Finish signing in to $host in your browser")
        val status = if (stage.url == null) "Opening the sign-in page…" else "Waiting for the browser"
        Text(status, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body, modifier = Modifier.testTag(SettingsTags.signinStatus))
        Guidance("Keep Amp open until the browser sends you back. If the app closes first, start again.")
    }
    AmpButton(
        "Open Sign-in Page",
        { stage.url?.let(onOpen) },
        SettingsTags.signinOpenBrowser,
        FullScreenButton,
        rank = AmpButtonRank.Primary,
        enabled = stage.url != null,
    )
    PrivateTabSwitch(privateTab, onPrivateTab)
}

@Composable
private fun PasteToken(token: TextFieldState, failure: String?, checking: Boolean, onSubmit: () -> Unit) {
    SecureWindow()
    Group {
        Title("Paste an access token")
        Guidance("Create one on ampcode.com in Settings, Security, Access Token, then paste it here. It stays valid until you revoke it there, even after you sign out.")
    }
    Group {
        AmpSecureTextField(
            state = token,
            testTag = SettingsTags.signinTokenInput,
            modifier = Modifier.fillMaxWidth(),
            label = "Access token",
            errorText = failure,
            errorTestTag = SettingsTags.signinError,
            enabled = !checking,
            onKeyboardAction = { if (token.text.isNotBlank()) onSubmit() },
        )
        AmpButton(
            "Sign In",
            onSubmit,
            SettingsTags.signinSubmit,
            FullScreenButton,
            rank = AmpButtonRank.Primary,
            enabled = token.text.isNotBlank(),
            loading = checking,
        )
    }
}

@Composable
private fun Confirm(stage: Stage.Confirm, onAdd: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var clipboardCleared by remember(stage.candidate) { mutableStateOf(false) }
    val candidate = stage.candidate
    Group {
        Title("Add ${candidate.email} on ${candidate.server.host}?")
        Guidance("Check that this is the account you meant to sign in with.")
    }
    Group {
        AmpButton("Add Account", onAdd, SettingsTags.signinConfirmAccount, FullScreenButton, rank = AmpButtonRank.Primary, loading = stage.adding)
        if (stage.method == Method.Paste) {
            AmpButton(
                if (clipboardCleared) "Clipboard Cleared" else "Clear Clipboard",
                {
                    context.clearClipboard()
                    clipboardCleared = true
                },
                SettingsTags.signinClearClipboard,
                FullScreenButton,
                enabled = !clipboardCleared,
            )
        }
        AmpButton("Cancel", onCancel, SettingsTags.signinCancelAccount, FullScreenButton, enabled = !stage.adding)
    }
}

@Composable
private fun Failed(stage: Stage.Failed, host: String, onRetry: () -> Unit, onBrowser: () -> Unit, onPaste: () -> Unit) {
    Text(stage.reason.text(host), color = AmpTheme.colors.status.danger, style = AmpTheme.type.body, modifier = Modifier.testTag(SettingsTags.signinError))
    val final = stage.reason == SignInFailure.TooManyAccounts || stage.reason == SignInFailure.AlreadySignedIn
    if (!final) {
        AmpButton("Try Again", onRetry, SettingsTags.signinRetry, FullScreenButton, rank = AmpButtonRank.Primary)
    }
    if (stage.reason in otherMethodsMayWork) OtherWays(onBrowser.takeIf { stage.method != Method.Browser }, onPaste)
}
