package com.agentifiers.amp.fleet.newthread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.agentifiers.amp.design.AmpTheme
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.components.AmpChip
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.components.AmpDropdownMenu
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.ProjectInfo

/** What the Project picker can do for an executor choice: pick among projects, or say why it cannot. */
sealed interface ProjectPick {
    /** [project] null is **No Project**. */
    data class Open(val project: ProjectId?) : ProjectPick
    data class Closed(val reason: String) : ProjectPick
}

fun projectPick(choice: ExecutorChoice?): ProjectPick = when (choice) {
    null -> ProjectPick.Closed("pick an executor first")
    ExecutorChoice.NoMachine -> ProjectPick.Closed("needs a machine")
    is ExecutorChoice.Orb -> ProjectPick.Open(choice.project)
    is ExecutorChoice.Runner -> ProjectPick.Open(choice.project)
}

fun projectLabel(pick: ProjectPick, projects: List<ProjectInfo>): String = "Project: " + when (pick) {
    is ProjectPick.Closed -> pick.reason
    is ProjectPick.Open -> pick.project?.let { id -> projects.firstOrNull { it.id == id }?.name ?: id.raw } ?: "No Project"
}

/** No machine has no project to set, so it comes back as it was. */
fun ExecutorChoice.withProject(project: ProjectId?): ExecutorChoice = when (this) {
    ExecutorChoice.NoMachine -> this
    is ExecutorChoice.Orb -> copy(project = project)
    is ExecutorChoice.Runner -> copy(project = project)
}

@Composable
internal fun ProjectPicker(pick: ProjectPick, projects: List<ProjectInfo>, enabled: Boolean, onPick: (ProjectId?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AmpChip(
            label = projectLabel(pick, projects),
            selected = false,
            onClick = { open = true },
            testTag = FleetTags.newThreadProject,
            trailingIcon = AmpIcons.ChevronDown,
            enabled = enabled && pick is ProjectPick.Open,
        )
        if (pick is ProjectPick.Open) {
            AmpDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                ProjectItem("No Project", pick.project == null, FleetTags.newThreadProjectOption(null)) { onPick(null); open = false }
                projects.forEach { info ->
                    ProjectItem(info.name, pick.project == info.id, FleetTags.newThreadProjectOption(info.id)) { onPick(info.id); open = false }
                }
            }
        }
    }
}

/** Every row keeps the check's slot, so a chosen name starts where the others do. */
@Composable
private fun ProjectItem(name: String, chosen: Boolean, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(name) },
        onClick = onClick,
        modifier = Modifier.testTag(tag).semantics { selected = chosen },
        leadingIcon = {
            Box(Modifier.size(AmpTheme.metrics.icon)) {
                if (chosen) Icon(AmpIcons.Check, contentDescription = null, Modifier.size(AmpTheme.metrics.icon))
            }
        },
    )
}
