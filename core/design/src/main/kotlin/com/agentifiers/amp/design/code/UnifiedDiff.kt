package com.agentifiers.amp.design.code

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.theme.DiffTokens

internal enum class DiffLine { Added, Removed, Hunk, Header, Context }

private fun DiffLine.textColor(diff: DiffTokens): Color = when (this) {
    DiffLine.Added -> diff.addedText
    DiffLine.Removed -> diff.removedText
    DiffLine.Hunk, DiffLine.Header -> diff.meta
    DiffLine.Context -> Color.Unspecified
}

/** A full-width band behind the line, or Unspecified for none. */
private fun DiffLine.band(diff: DiffTokens): Color = when (this) {
    DiffLine.Added -> diff.addedBackground
    DiffLine.Removed -> diff.removedBackground
    DiffLine.Hunk, DiffLine.Header, DiffLine.Context -> Color.Unspecified
}

internal data class ParsedDiff(val text: AnnotatedString, val lines: List<DiffLine>)

private val headerPrefixes = listOf("diff ", "index ", "--- ", "+++ ", "Index: ", "===")

/** Lines before the first `@@` of a file are headers; inside a hunk, `---`/`+++` are ordinary removed/added lines. */
internal fun parseDiff(diff: String, colors: DiffTokens): ParsedDiff {
    var lines = diff.trimEnd().lines()
    if (lines.firstOrNull()?.trimStart()?.startsWith("```") == true) lines = lines.drop(1)
    if (lines.lastOrNull()?.trim() == "```") lines = lines.dropLast(1)
    var inHunk = false
    val kinds = lines.map { line ->
        when {
            line.startsWith("@@") -> DiffLine.Hunk.also { inHunk = true }
            line.startsWith("diff ") -> DiffLine.Header.also { inHunk = false }
            !inHunk && headerPrefixes.any(line::startsWith) -> DiffLine.Header
            line.startsWith("\\") -> DiffLine.Hunk
            line.startsWith("+") -> DiffLine.Added
            line.startsWith("-") -> DiffLine.Removed
            else -> DiffLine.Context
        }
    }
    val text = AnnotatedString.Builder().apply {
        lines.forEachIndexed { i, line ->
            if (i > 0) append('\n')
            val color = kinds[i].textColor(colors)
            if (color == Color.Unspecified) append(line) else withStyle(SpanStyle(color = color)) { append(line) }
        }
    }.toAnnotatedString()
    return ParsedDiff(text, kinds)
}

/** A unified diff, optionally fenced as ```diff, with added and removed lines banded across the full width. */
@Composable
fun UnifiedDiff(diff: String, modifier: Modifier = Modifier, highlight: String? = null) {
    val colors = AmpTheme.colors.code
    val parsed = remember(diff, colors.diff) { parseDiff(diff, colors.diff) }
    val display = rememberHighlighted(parsed.text, highlight)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        display,
        style = Mono.copy(color = colors.editorText),
        softWrap = false,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .clip(CodeShape)
            .background(colors.editorBackground)
            .border(1.dp, AmpTheme.colors.surfaces.line, CodeShape)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
            .drawBehind {
                val result = layout ?: return@drawBehind
                parsed.lines.forEachIndexed { i, kind ->
                    val band = kind.band(colors.diff)
                    if (band != Color.Unspecified && i < result.lineCount) {
                        val top = result.getLineTop(i)
                        drawRect(band, Offset(0f, top), Size(size.width, result.getLineBottom(i) - top))
                    }
                }
            }
            .padding(horizontal = 10.dp),
    )
}
