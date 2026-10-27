package com.agentifiers.amp.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** SvelteKit remote functions: a web surface may name the path only to refuse it (RATIONALE.md, rule 7). */
private const val REMOTE_FUNCTIONS = "/_app/remote/"

/** What a surface does with a main-frame http(s) navigation the shared rules let through. */
internal sealed interface Navigation {
    data object Load : Navigation
    data object Cancel : Navigation
    data class Browser(val url: String) : Navigation
}

/** A hardened WebView and what the native chrome shows about it. */
@Stable
internal class WebFrame(val view: WebView) {
    var url by mutableStateOf<String?>(null)
    var title by mutableStateOf<String?>(null)
    var failure by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)
}

/**
 * The only way this module makes a WebView. The rules are auth-and-security.md's list for every WebView the app
 * hosts. [profile] is the WebView profile; [networked] false answers every request but `data:` and `blob:` with
 * an empty 403. [navigate] decides main-frame http(s) navigations the shared rules let through, and may act on one
 * itself (a portal re-mints its login URL). A renderer crash replaces the WebView with a fresh one, so callers
 * load from a `LaunchedEffect(frame)`.
 */
@Composable
internal fun rememberWebFrame(profile: String, networked: Boolean, prompts: WebPrompts, navigate: (Uri) -> Navigation): WebFrame {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentNavigate by rememberUpdatedState(navigate)
    var generation by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<SaveRequest?>(null) }
    val save = rememberLauncherForActivityResult(SaveDocument) { target ->
        val request = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (target != null) scope.launch { prompts.notice = saveDownload(context, request, target) }
    }
    val frame = remember(profile, networked, generation) {
        WebFrame(hardenedWebView(context, profile)).also { frame ->
            frame.view.webViewClient = FrameClient(frame, networked, { currentNavigate(it) }, context, prompts, scope, onGone = { generation++ })
            frame.view.webChromeClient = FrameChrome(frame)
            frame.view.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
                val request = saveRequestOf(url, userAgent, disposition, mimeType, profile)
                if (request == null) {
                    prompts.notice = "This download can be saved only from a browser."
                } else {
                    scope.launch {
                        val host = Uri.parse(url).host.orEmpty()
                        if (prompts.confirm("Download ${request.fileName}?", "From $host. Choose where to save it.", "Choose Location")) {
                            pending = request
                            save.launch(request)
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(frame) {
        onDispose {
            (frame.view.parent as? ViewGroup)?.removeView(frame.view)
            frame.view.destroy()
        }
    }
    return frame
}

@Composable
internal fun WebFrameView(frame: WebFrame, modifier: Modifier = Modifier) {
    key(frame) { AndroidView(factory = { frame.view }, modifier = modifier) }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun hardenedWebView(context: Context, profile: String): WebView {
    val view = WebView(context)
    WebProfiles.attach(view, profile)
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        safeBrowsingEnabled = true
        setGeolocationEnabled(false)
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
        WebSettingsCompat.setWebAuthenticationSupport(view.settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE)
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
    }
    return view
}

private class FrameClient(
    private val frame: WebFrame,
    private val networked: Boolean,
    private val navigate: (Uri) -> Navigation,
    private val context: Context,
    private val prompts: WebPrompts,
    private val scope: CoroutineScope,
    private val onGone: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        when (uri.scheme?.lowercase()) {
            "http", "https" -> Unit
            "data", "blob", "about" -> return false
            "javascript" -> return true
            else -> {
                if (request.isForMainFrame) askToLeave(uri)
                return true
            }
        }
        if (uri.path.orEmpty().contains(REMOTE_FUNCTIONS)) return true
        if (!request.isForMainFrame) return false
        return when (val next = navigate(uri)) {
            Navigation.Load -> false
            Navigation.Cancel -> true
            is Navigation.Browser -> {
                openCustomTab(context, next.url, ephemeral = false)
                true
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (networked) return null
        return when (request.url.scheme?.lowercase()) {
            "data", "blob" -> null
            else -> WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        frame.url = url
        frame.failure = null
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        frame.url = url
        frame.canGoBack = view.canGoBack()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        frame.canGoBack = view.canGoBack()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) frame.failure = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "The page did not load."
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame && response.statusCode >= 500) frame.failure = "The page answered ${response.statusCode}."
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone()
        return true
    }

    private fun askToLeave(uri: Uri) {
        val intent = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (malformed: java.net.URISyntaxException) {
            prompts.notice = "This page tried to open a link that isn't valid."
            return
        }
        // A page must not reach a specific component or a non-browsable activity through an intent: URI.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        if (context.activity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = intent.`package` ?: uri.scheme.orEmpty()
        scope.launch {
            if (!prompts.confirm("Leave Amp?", "This page wants to open $target outside the app.", "Open")) return@launch
            try {
                context.startActivity(intent)
            } catch (none: ActivityNotFoundException) {
                prompts.notice = "No app on this device opens $target links."
            }
        }
    }
}

private class FrameChrome(private val frame: WebFrame) : WebChromeClient() {
    override fun onReceivedTitle(view: WebView, title: String?) {
        frame.title = title
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
}

private class SaveRequest(val url: String, val fileName: String, val mimeType: String, val userAgent: String, val cookies: CookieManager)

private fun saveRequestOf(url: String, userAgent: String, disposition: String?, mimeType: String?, profile: String): SaveRequest? {
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val type = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
    return SaveRequest(url, URLUtil.guessFileName(url, disposition, type), type, userAgent, WebProfiles.cookiesOf(profile))
}

/** ACTION_CREATE_DOCUMENT with the download's own MIME type, which the stock CreateDocument fixes at registration. */
private object SaveDocument : ActivityResultContract<SaveRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

private const val MAX_REDIRECTS = 5

/**
 * Streams the download into the chosen document with the page profile's cookies. Redirects are followed by hand so
 * each hop gets only its own host's cookies. No Authorization header is ever sent.
 */
private suspend fun saveDownload(context: Context, request: SaveRequest, target: Uri): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    try {
        download(request, resolver, target)
        "Saved ${request.fileName}."
    } catch (failed: IOException) {
        runCatching { DocumentsContract.deleteDocument(resolver, target) }
        "Couldn't save ${request.fileName}. ${failed.message.orEmpty()}".trim()
    }
}

private fun download(request: SaveRequest, resolver: ContentResolver, target: Uri) {
    var url = URL(request.url)
    repeat(MAX_REDIRECTS + 1) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", request.userAgent)
            request.cookies.getCookie(url.toString())?.let { setRequestProperty("Cookie", it) }
        }
        try {
            val status = connection.responseCode
            if (status !in 300..399) {
                if (status !in 200..299) throw IOException("The server answered $status.")
                val output = resolver.openOutputStream(target) ?: throw IOException("The chosen location can't be written.")
                connection.inputStream.use { input -> output.use { input.copyTo(it) } }
                return
            }
            val location = connection.getHeaderField("Location") ?: throw IOException("The server redirected nowhere.")
            url = URL(url, location)
            if (url.protocol != "https" && url.protocol != "http") throw IOException("The server redirected outside the web.")
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("The server redirected too many times.")
}
