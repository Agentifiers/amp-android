package com.agentifiers.amp.model

/** What the app has done about the notification permission, remembered across launches. Later entries supersede earlier. */
enum class PromptHistory { Never, Declined, Requested }

/** Whether Android lets Amp post notifications, and the one way to turn them on from here. */
sealed interface NotificationAccess {
    /** Notifications show. [channelsOff] names the kinds the user turned off in Android settings. */
    data class Allowed(val channelsOff: List<String>) : NotificationAccess

    /** Never asked: the app explains once, and Turn On shows Android's prompt. */
    data object FirstAsk : NotificationAccess

    /** Android still shows its prompt, but only when the user taps Turn On. */
    data object AskAgain : NotificationAccess

    /** Only Android's notification settings can turn them on. */
    data object SettingsOnly : NotificationAccess

    companion object {
        /**
         * [runtimePermission] is false before Android 13, where notifications need no permission. [rationale] is
         * `shouldShowRequestPermissionRationale`, true only after one denial, so a denied permission with no rationale
         * is still askable when the app never requested it and final when it did.
         */
        fun of(
            runtimePermission: Boolean,
            granted: Boolean,
            rationale: Boolean,
            appEnabled: Boolean,
            history: PromptHistory,
            channelsOff: List<String>,
        ): NotificationAccess = when {
            !runtimePermission || granted -> if (appEnabled) Allowed(channelsOff) else SettingsOnly
            rationale -> AskAgain
            history == PromptHistory.Requested -> SettingsOnly
            history == PromptHistory.Declined -> AskAgain
            else -> FirstAsk
        }
    }
}
