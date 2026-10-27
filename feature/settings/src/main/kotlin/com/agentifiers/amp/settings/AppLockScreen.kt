package com.agentifiers.amp.settings

import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.AmpWordmark
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.agentifiers.amp.design.SettingsTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank

/** Covers the whole window while app lock is engaged. [onUnlock] runs the system prompt; [error] is its last failure. */
@Composable
fun AppLockScreen(onUnlock: () -> Unit, error: String?, modifier: Modifier = Modifier) {
    FullScreenColumn(modifier.fillMaxSize().background(AmpTheme.colors.surfaces.canvas).windowInsetsPadding(WindowInsets.safeDrawing).testTag(SettingsTags.appLockScreen)) {
        AmpWordmark(height = AmpTheme.spacing.xxl)
        Text("Amp is locked", color = AmpTheme.colors.text.ink, style = AmpTheme.type.headline)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            error?.let { Text(it, color = AmpTheme.colors.status.danger, style = AmpTheme.type.body, modifier = Modifier.testTag(SettingsTags.appLockError)) }
            AmpButton("Unlock", onUnlock, SettingsTags.appLockUnlock, FullScreenButton, rank = AmpButtonRank.Primary)
        }
    }
}
