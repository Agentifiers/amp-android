package com.agentifiers.amp.design

import com.agentifiers.amp.model.MessageId
import com.agentifiers.amp.model.Portal

/** Every WebView surface: the account web pane, portals, and amp-view prototypes. */
object WebTags {
    const val pane = "web-pane"
    const val openInBrowser = "web-open-in-browser"
    const val stepupOpenWeb = "stepup-open-web"
    const val pageHost = "web-page-host"
    const val confirmAllow = "web-confirm-allow"
    const val confirmDeny = "web-confirm-deny"

    /** By port when the link has one, else by a kebab slug of its host (custom portal hostnames). */
    fun portalItem(portal: Portal) = "portal-item-" + (portal.port?.toString() ?: kebab(hostOf(portal.url)))
    const val portalPortInput = "portal-port-input"
    const val portalOpen = "portal-open"
    const val portalAddress = "portal-address"
    const val portalTitle = "portal-title"
    const val portalError = "portal-error"
    const val portalBack = "portal-back"
    const val portalReload = "portal-reload"
    const val portalPage = "portal-page"
    const val portalOptions = "portal-options"
    const val portalOptionsClose = "portal-options-close"
    const val portalFocus = "portal-focus"
    const val portalEmpty = "portal-empty"
    fun portalFolder(name: String) = "portal-folder-" + kebab(name)

    fun ampView(id: MessageId, index: Int) = "amp-view-${id.raw}-$index"
    const val ampViewSource = "amp-view-source"

    private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

    private fun kebab(text: String): String = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
