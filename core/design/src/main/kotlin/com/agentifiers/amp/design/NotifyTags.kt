package com.agentifiers.amp.design

/**
 * In-app alerts. System notifications carry no resource ids, so flows assert on their text in the
 * shade instead (docs/design/notifications.md, "Testing notifications").
 */
object NotifyTags {
    const val needsYouBanner = "needs-you-banner"
}
