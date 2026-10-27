package com.agentifiers.amp.fleet

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpIconButton

/** Android is not letting Amp post notifications; present only while the Sidebar should say so. */
class NotificationsOffNotice(val turnOn: () -> Unit, val dismiss: () -> Unit)

@Composable
internal fun NotificationsOffRow(notice: NotificationsOffNotice) {
    Row(
        Modifier.padding(start = AmpTheme.spacing.paneX - AmpTheme.spacing.listInset).testTag(FleetTags.notificationsOff),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Notifications are off", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        AmpButton("Turn On", notice.turnOn, FleetTags.notificationsTurnOn, rank = AmpButtonRank.Tertiary)
        AmpIconButton(AmpIcons.X, "Dismiss", onClick = notice.dismiss, testTag = FleetTags.notificationsDismiss)
    }
}
