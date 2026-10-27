package com.agentifiers.amp.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens [url] in the browser's Custom Tab, which uses the browser's own session. [ephemeral] asks for a tab that
 * keeps no cookies, when the default browser supports it (androidx.browser 1.9+).
 */
fun openCustomTab(context: Context, url: String, ephemeral: Boolean) {
    val builder = CustomTabsIntent.Builder()
    val browser = if (ephemeral) CustomTabsClient.getPackageName(context, emptyList()) else null
    val ephemeralBrowser = browser?.takeIf { CustomTabsClient.isEphemeralBrowsingSupported(context, it) }
    if (ephemeralBrowser != null) builder.setEphemeralBrowsingEnabled(true)
    val tab = builder.build()
    if (ephemeralBrowser != null) tab.intent.setPackage(ephemeralBrowser)
    if (context.activity() == null) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        tab.launchUrl(context, Uri.parse(url))
    } catch (noBrowser: ActivityNotFoundException) {
        Toast.makeText(context, "No browser on this device can open this page.", Toast.LENGTH_LONG).show()
    }
}

internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
