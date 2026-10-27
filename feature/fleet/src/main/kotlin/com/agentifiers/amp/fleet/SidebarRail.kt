package com.agentifiers.amp.fleet

import com.agentifiers.amp.design.AmpTheme
import android.content.ClipData
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.ShellTags
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.organize.swatch
import com.agentifiers.amp.design.LocalPaneSurface
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.ThreadColor
import com.agentifiers.amp.model.ThreadId
import com.agentifiers.amp.model.ThreadSummary
import com.agentifiers.amp.sync.api.AccountScope

/**
 * The collapsed Sidebar: New Thread, Puck, one avatar per thread with its two-letter initials, color stripe, and status
 * dot, Needs you first, and a fixed footer with the account avatar that opens the switcher. Each thread avatar's label
 * is "<title>, <status>" so status stays readable as text.
 */
@Composable
fun SidebarRail(
    scope: AccountScope,
    selected: ThreadId?,
    navigate: (PaneDestination) -> Unit,
    onAccountSwitcher: (anchor: Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidebar by scope.fleet.sidebar.collectAsStateWithLifecycle()
    val account by scope.account.collectAsStateWithLifecycle()
    val server = account.server.ampBaseUrl
    val threads = remember(sidebar) { sidebar.needsYou + sidebar.pinned + sidebar.active.flatMap { it.threads } }
    Column(modifier.fillMaxHeight().background(LocalPaneSurface.current).testTag(FleetTags.rail), horizontalAlignment = Alignment.CenterHorizontally) {
        AmpIconButton(AmpIcons.SquarePen, "New Thread", onClick = { navigate(PaneDestination.NewThread(null)) }, testTag = FleetTags.newThread)
        AmpIconButton(AmpIcons.Sparkles, "Puck", onClick = { navigate(PaneDestination.Puck(null)) }, testTag = ShellTags.nav("puck"))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
            items(threads, key = { it.id.raw }) { summary ->
                RailItem(summary, "$server/threads/${summary.id.raw}", summary.id == selected) { navigate(PaneDestination.Thread(summary.id)) }
            }
        }
        HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = AmpTheme.spacing.md), color = AmpTheme.colors.surfaces.line)
        Box(Modifier.padding(vertical = AmpTheme.spacing.xs)) { AccountAvatar(account.email, onAccountSwitcher) }
    }
}

/** Two letters, so threads that share a first letter stay apart: the first two words' initials, else the first two letters. */
internal fun railInitials(title: String?): String {
    val words = title.orEmpty().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).replaceFirstChar(Char::uppercaseChar)
        else -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
    }
}

@Composable
private fun RailItem(summary: ThreadSummary, link: String, selected: Boolean, onClick: () -> Unit) {
    val title = summary.title ?: "Untitled"
    val label = "$title, ${summary.status.word}"
    Box(
        Modifier.size(AmpTheme.metrics.touch).testTag(FleetTags.railThread(summary.id)).clickable(onClick = onClick)
            // A long press drags the thread's link: a column in any app window opens it, and other apps get the URL.
            .dragAndDropSource { _: Offset -> DragAndDropTransferData(ClipData.newPlainText(title, link), flags = View.DRAG_FLAG_GLOBAL) }
            .clearAndSetSemantics {
                contentDescription = label
                text = AnnotatedString(label)
                role = Role.Button
                this.selected = selected
            },
    ) {
        val metrics = AmpTheme.metrics
        if (summary.color != ThreadColor.None) {
            Box(Modifier.align(Alignment.CenterStart).width(STRIPE).height(metrics.iconButton).clip(CircleShape).background(summary.color.swatch()))
        }
        Box(
            Modifier.size(metrics.iconButton).align(Alignment.Center).clip(CircleShape).background(AmpTheme.colors.surfaces.raised)
                .then(if (selected) Modifier.border(metrics.tabIndicator, AmpTheme.colors.accents.accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(railInitials(summary.title), color = AmpTheme.colors.text.ink, style = AmpTheme.type.label, fontWeight = FontWeight.SemiBold)
        }
        StatusDot(summary.status, Modifier.align(Alignment.BottomEnd).padding(AmpTheme.spacing.xs).size(metrics.dot))
    }
}
