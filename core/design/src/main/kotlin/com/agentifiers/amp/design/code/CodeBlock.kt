package com.agentifiers.amp.design.code

import com.agentifiers.amp.design.AmpTheme
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import kotlinx.coroutines.launch

internal val CodeShape = RoundedCornerShape(6.dp)

/** Monospace code with a language label, Copy, and an optional collapse to the first [collapseAfterLines] lines. */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    collapseAfterLines: Int? = null,
) {
    val text = remember(code) { AnnotatedString(code) }
    CodeFrame(text, language, highlight, collapseAfterLines, modifier)
}

/** Terminal output with ANSI colors, Copy (plain text), and a collapse after [collapseAfterLines] lines. */
@Composable
fun TerminalOutput(
    output: String,
    modifier: Modifier = Modifier,
    highlight: String? = null,
    collapseAfterLines: Int = 40,
) {
    val ansi = AmpTheme.colors.code.ansi
    val text = remember(output, ansi) { ansiToAnnotatedString(output, ansi).let { it.subSequence(0, it.text.trimEnd().length) } }
    CodeFrame(text, null, highlight, collapseAfterLines, modifier)
}

@Composable
private fun CodeFrame(
    text: AnnotatedString,
    label: String?,
    highlight: String?,
    collapseAfterLines: Int?,
    modifier: Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val cut = remember(text, collapseAfterLines) { collapseAfterLines?.let { collapseOffset(text.text, it) } }
    val hiddenMatch = cut != null && !highlight.isNullOrEmpty() &&
        text.text.indexOf(highlight, (cut - highlight.length + 1).coerceAtLeast(0), ignoreCase = true) >= 0
    val collapsed = cut != null && !expanded && !hiddenMatch
    val shown = if (collapsed) text.subSequence(0, cut) else text
    val display = rememberHighlighted(shown, highlight)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val colors = AmpTheme.colors
    Column(modifier.fillMaxWidth().clip(CodeShape).background(colors.code.editorBackground).border(1.dp, colors.surfaces.line, CodeShape)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label.orEmpty(), style = AmpTheme.type.meta, color = colors.text.inkMuted, modifier = Modifier.weight(1f))
            AmpIconButton(
                AmpIcons.Copy,
                "Copy",
                { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label ?: "text", text.text))) } },
                ThreadTags.codeCopy,
            )
        }
        Text(
            display,
            style = Mono.copy(color = colors.code.editorText),
            softWrap = false,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        )
        if (collapsed) {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(ThreadTags.codeShowAll)) { Text("Show All") }
        }
    }
}

/** End of the first [lines] lines when [text] is longer than that, else null. */
internal fun collapseOffset(text: String, lines: Int): Int? {
    if (lines < 1) return null
    var end = -1
    repeat(lines) {
        end = text.indexOf('\n', end + 1)
        if (end < 0) return null
    }
    return if (end < text.length - 1) end else null
}
