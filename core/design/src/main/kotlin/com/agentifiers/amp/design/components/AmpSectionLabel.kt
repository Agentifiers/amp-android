package com.agentifiers.amp.design.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.AmpTheme

/**
 * A list's group heading in the section role: 32dp tall at the default font scale, the text on the bottom 6dp above the
 * first row, drawn uppercase with its [count]. TalkBack and flows read the text as written, "Needs you (3)", from the
 * node tagged [testTag]. [trailing] holds the group's own controls, such as a collapse toggle; their 48dp targets make
 * the row 48dp, and the text centers on them as a pane header's title centers on its icons.
 */
@Composable
fun AmpSectionLabel(
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val written = if (count == null) text else "$text ($count)"
    Row(
        modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.section).padding(horizontal = AmpTheme.spacing.paneX - AmpTheme.spacing.listInset),
        verticalAlignment = if (trailing == null) Alignment.Bottom else Alignment.CenterVertically,
    ) {
        Text(
            written.uppercase(),
            style = AmpTheme.type.section,
            color = AmpTheme.colors.text.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .then(if (trailing == null) Modifier.padding(bottom = AmpTheme.spacing.sectionBottom) else Modifier)
                .testTag(testTag)
                .clearAndSetSemantics {
                    this.text = AnnotatedString(written)
                    heading()
                },
        )
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}
