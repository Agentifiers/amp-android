package com.agentifiers.amp.files

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.agentifiers.amp.design.FilesTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpEmptyState
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.model.Children
import com.agentifiers.amp.model.FileNode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One visible line of the tree. A directory's listing state shows as a row under it. */
internal sealed interface TreeRow {
    val key: String
    val depth: Int

    data class Entry(val node: FileNode, override val depth: Int, val expanded: Boolean) : TreeRow {
        override val key get() = "entry:${node.path}"
    }

    data class Pending(val dir: String, override val depth: Int) : TreeRow {
        override val key get() = "pending:$dir"
    }

    data class Empty(val dir: String, override val depth: Int) : TreeRow {
        override val key get() = "empty:$dir"
    }

    data class Failed(val dir: String, override val depth: Int, val reason: String) : TreeRow {
        override val key get() = "failed:$dir"
    }
}

/** Walks [root] through every expanded directory whose listing is in [listings]. */
internal fun flatten(root: String, listings: Map<String, FileNode>, expanded: Collection<String>): List<TreeRow> = buildList {
    fun addChildren(dir: String, depth: Int) {
        when (val children = (listings[dir] as? FileNode.Dir)?.children) {
            is Children.Loaded -> {
                if (children.nodes.isEmpty()) add(TreeRow.Empty(dir, depth))
                children.nodes.forEach { node ->
                    val open = node is FileNode.Dir && node.path in expanded
                    add(TreeRow.Entry(node, depth, open))
                    if (open) addChildren(node.path, depth + 1)
                }
            }
            is Children.Failed -> add(TreeRow.Failed(dir, depth, children.reason))
            Children.Loading, Children.NotLoaded, null -> add(TreeRow.Pending(dir, depth))
        }
    }
    addChildren(root, 0)
}

/**
 * The tree header's title: the opened folder's name. The executor's own root is "." and the client has no name for
 * it (the Changes repository name would cost a git load), so it gets no title.
 */
internal fun treeTitle(root: String): String? = fileName(root).takeIf { it.isNotEmpty() && it != "." }

private val modifiedFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
internal fun FileTree(
    rows: List<TreeRow>,
    selectedDir: String?,
    openFile: String?,
    onDir: (String) -> Unit,
    onFile: (String) -> Unit,
    openFilesOnWeb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val only = rows.singleOrNull()) {
        is TreeRow.Empty -> {
            Box(modifier, contentAlignment = Alignment.TopCenter) {
                AmpEmptyState(AmpIcons.Folder, "No files", "This folder is empty. Upload a file to add one.", FilesTags.empty)
            }
            return
        }
        is TreeRow.Failed -> {
            Box(modifier, contentAlignment = Alignment.TopCenter) {
                AmpEmptyState(AmpIcons.CircleAlert, "Files unavailable", only.reason, FilesTags.unavailable) {
                    AmpButton("Open Files on web", openFilesOnWeb, FilesTags.openWeb, rank = AmpButtonRank.Link)
                }
            }
            return
        }
        else -> Unit
    }
    val spacing = AmpTheme.spacing
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = spacing.listInset)) {
        items(rows, key = { it.key }) { row ->
            val indent = Modifier.padding(start = spacing.rowPadX + spacing.childIndent * row.depth, end = spacing.rowPadX)
            when (row) {
                is TreeRow.Entry -> EntryRow(
                    row = row,
                    selected = row.node.path == selectedDir || row.node.path == openFile,
                    onClick = { if (row.node is FileNode.Dir) onDir(row.node.path) else onFile(row.node.path) },
                    indent = indent,
                )
                is TreeRow.Pending -> InfoRow("Loading…", AmpTheme.colors.text.inkMuted, indent)
                is TreeRow.Empty -> InfoRow("Empty folder", AmpTheme.colors.text.inkMuted, indent)
                is TreeRow.Failed -> Row(Modifier.fillMaxWidth().then(indent), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.reason, Modifier.weight(1f), color = AmpTheme.colors.status.danger, style = AmpTheme.type.meta)
                    AmpButton("Open Files on web", openFilesOnWeb, FilesTags.openWeb, rank = AmpButtonRank.Link)
                }
            }
        }
    }
}

/** A listing-state line under a directory: not tappable, so it keeps the A2 file row height. */
@Composable
private fun InfoRow(text: String, color: Color, indent: Modifier) {
    Row(Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.fileRow).then(indent), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(AmpTheme.metrics.iconSmall))
        Text(text, Modifier.padding(start = AmpTheme.spacing.sm), color = color, style = AmpTheme.type.meta)
    }
}

@Composable
private fun EntryRow(row: TreeRow.Entry, selected: Boolean, onClick: () -> Unit, indent: Modifier) {
    val node = row.node
    val colors = AmpTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = AmpTheme.metrics.touch)
            .clip(AmpTheme.shapes.control)
            .testTag(FilesTags.node(node.path))
            .background(if (selected) AmpTheme.surfaceRoles.selected else Color.Transparent)
            .clickable(onClick = onClick)
            .then(indent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm),
    ) {
        val chevron = Modifier.size(AmpTheme.metrics.iconSmall)
        if (node is FileNode.Dir) {
            Icon(if (row.expanded) AmpIcons.ChevronDown else AmpIcons.ChevronRight, contentDescription = null, tint = colors.text.inkFaint, modifier = chevron)
        } else {
            Spacer(chevron)
        }
        Text(fileName(node.path), Modifier.weight(1f), color = colors.text.ink, style = AmpTheme.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val modified = (node as? FileNode.File)?.modifiedAt
        val meta = if (modified == null) "folder" else modifiedFormat.format(Instant.ofEpochMilli(modified.toEpochMilliseconds()))
        Text(meta, color = colors.text.inkMuted, style = AmpTheme.type.meta, maxLines = 1)
    }
}
