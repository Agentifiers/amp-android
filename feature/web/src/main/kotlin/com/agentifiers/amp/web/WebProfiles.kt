package com.agentifiers.amp.web

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.agentifiers.amp.model.AccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebView profiles: one cookie jar, storage and cache per name (auth-and-security.md, "Web pages run in a
 * per-account WebView profile"). Account pages and portals use the account's profile; MCP Apps and amp-view
 * prototypes get their own, so an account's ampcode.com cookies never reach them.
 */
object WebProfiles {
    internal const val MCP_APPS = "mcp-apps"
    internal const val AMP_VIEW = "amp-view"
    private const val TAG = "AmpWeb"
    private const val PREFS = "amp_web_profiles"
    private const val QUEUED = "delete_at_start"

    private lateinit var prefs: SharedPreferences

    /** Main thread only. */
    private var swept = false

    fun profileFor(account: AccountId): String = "amp-${account.raw}"

    /** App start, on the main thread: deletes the profiles sign-out queued, once the main thread is first idle. */
    fun start(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Looper.myQueue().addIdleHandler {
            sweep()
            false
        }
    }

    /**
     * Sign-out: deletes the account's profile. ProfileStore refuses to delete a profile this process has loaded,
     * so that one has its cookies and storage cleared now and its files deleted at the next start.
     */
    suspend fun delete(account: AccountId) {
        withContext(Dispatchers.Main) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return@withContext
            val store = ProfileStore.getInstance()
            val name = profileFor(account)
            if (name !in store.allProfileNames) return@withContext
            if (deleted(store, name)) return@withContext
            store.getProfile(name)?.run {
                cookieManager.removeAllCookies(null)
                webStorage.deleteAllData()
            }
            prefs.edit().putStringSet(QUEUED, queued() + name).commit()
            Log.i(TAG, "Profile $name is loaded in this process: cleared its cookies and storage, deleting it at the next start")
        }
    }

    private fun sweep() {
        if (swept) return
        swept = true
        val queued = queued()
        if (queued.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        val store = ProfileStore.getInstance()
        val left = queued.filter { it in store.allProfileNames && !deleted(store, it) }.toSet()
        prefs.edit().putStringSet(QUEUED, left).apply()
    }

    private fun queued(): Set<String> = prefs.getStringSet(QUEUED, null).orEmpty()

    private fun deleted(store: ProfileStore, name: String): Boolean = try {
        store.deleteProfile(name)
        Log.i(TAG, "Deleted profile $name")
        true
    } catch (_: IllegalStateException) {
        false
    }

    /** Must run before [view] loads anything. A queued deletion of [name] runs first, so a new sign-in starts clean. */
    internal fun attach(view: WebView, name: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            sweep()
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(view, name)
            Log.i(TAG, "WebView on profile $name")
        } else {
            Log.i(TAG, "WebView on profile default; this WebView has no multi-profile support, so $name shares cookies")
        }
    }

    /** The cookie jar a download from a page in profile [name] must send. Main thread only. */
    internal fun cookiesOf(name: String): CookieManager =
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(name).cookieManager
        } else {
            CookieManager.getInstance()
        }
}
