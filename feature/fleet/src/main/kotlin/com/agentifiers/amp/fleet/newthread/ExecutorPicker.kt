package com.agentifiers.amp.fleet.newthread

import com.agentifiers.amp.design.AmpTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentifiers.amp.design.Mono
import com.agentifiers.amp.design.FleetTags
import com.agentifiers.amp.design.ThreadTags
import com.agentifiers.amp.design.components.AmpButton
import com.agentifiers.amp.design.components.AmpIconButton
import com.agentifiers.amp.design.icons.AmpIcons
import com.agentifiers.amp.design.components.AmpSheet
import com.agentifiers.amp.design.components.AmpButtonRank
import com.agentifiers.amp.design.components.AmpListRow
import com.agentifiers.amp.design.components.AmpSectionLabel
import com.agentifiers.amp.design.components.AmpTextField
import com.agentifiers.amp.model.ExecutorChoice
import com.agentifiers.amp.model.OrbProvider
import com.agentifiers.amp.model.OrbSize
import com.agentifiers.amp.model.ProjectId
import com.agentifiers.amp.model.ProjectInfo
import com.agentifiers.amp.model.RunnerId
import com.agentifiers.amp.model.RunnerInfo
import com.agentifiers.amp.model.WorktreeChoice
import com.agentifiers.amp.model.AccountWebPage
import com.agentifiers.amp.model.OrbProviderListing
import com.agentifiers.amp.model.OrbProviderOwner
import com.agentifiers.amp.model.OrbProviderSource
import com.agentifiers.amp.model.PaneDestination
import com.agentifiers.amp.model.WebSurface
import com.agentifiers.amp.sync.api.AccountScope

/** Amp's live size list is a web-only function, so prices come from this table and the server validates on spawn. */
internal val ORB_SIZE_DETAILS: Map<String, String> = linkedMapOf(
    "a1.tiny" to "1 CPU, 2 GB, \$0.08/h",
    "a1.small" to "2 CPU, 4 GB, \$0.17/h",
    "a1.medium" to "4 CPU, 8 GB, \$0.33/h",
    "a1.large" to "8 CPU, 16 GB, \$0.66/h",
    "a1.xxlarge" to "16 CPU, 32 GB, \$1.32/h",
    "a1.3xlarge" to "16 CPU, 44 GB, \$2.13/h, Gigawatt or Enterprise only",
)

private val DEFAULT_ORB_SIZE = OrbSize("a1.medium")

/** After the web sign-in, the page that lists the user's own providers (`/(app)/settings/orbs`, web node 237). */
private const val SIGN_IN_RETURN = "?returnTo=%2Fsettings%2Forbs"
private val WORKTREE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$")

fun isValidWorktreeName(name: String): Boolean = WORKTREE_NAME.matches(name)

/** The first few words of the prompt as a branch-safe slug, or empty when the prompt has none. */
fun worktreeNameSuggestion(prompt: String): String =
    prompt.lowercase()
        .split(Regex("\\s+"))
        .map { it.replace(Regex("[^a-z0-9]+"), "-").trim('-') }
        .filter { it.isNotEmpty() }
        .take(4)
        .joinToString("-")
        .take(40)
        .trimEnd('-')

fun runnerSummary(runner: RunnerInfo): String {
    val count = runner.directories.size
    val directories = if (count == 1) "1 directory" else "$count directories"
    return if (runner.hostname.endsWith("-amp-app")) "$directories · Mac app" else directories
}

/** One expanded row at a time, so per-row tags such as runnerDirectory(index) stay unique on screen. */
private sealed interface Expanded {
    data object Orb : Expanded
    data class Runner(val id: RunnerId) : Expanded
}

@Composable
internal fun ExecutorPicker(
    scope: AccountScope,
    prompt: String,
    orbProviders: OrbProviderSource,
    navigate: (PaneDestination) -> Unit,
    onPick: (ExecutorChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val runners by scope.catalog.runners.collectAsStateWithLifecycle()
    val projects by scope.catalog.projects.collectAsStateWithLifecycle()
    val sizes by scope.catalog.orbSizes.collectAsStateWithLifecycle()
    val account by scope.account.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val memory = remember(account.id) { OrbProviderMemory(context.applicationContext, account.id) }
    var book by remember(account.id) { mutableStateOf(memory.load()) }
    var listing by remember(account.id) { mutableStateOf<OrbProviderListing?>(null) }
    var expanded by remember { mutableStateOf<Expanded?>(null) }
    LaunchedEffect(account.id) {
        val read = orbProviders.list(account)
        if (read is OrbProviderListing.Listed) book = book.listed(read.options).also(memory::save)
        listing = read
    }
    val online = runners.filter { it.online }
    val own = online.filter { !it.shared }
    val shared = online.filter { it.shared }

    fun toggle(target: Expanded) {
        expanded = if (expanded == target) null else target
    }

    AmpSheet(onDismissRequest = onDismiss) {
        val spacing = AmpTheme.spacing
        val textX = Modifier.padding(horizontal = spacing.paneX - spacing.listInset)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = spacing.listInset).padding(bottom = spacing.sm)
                .semantics { testTagsAsResourceId = true }.testTag(ThreadTags.executorPicker),
        ) {
            Column(Modifier.testTag(ThreadTags.executorSectionOrb)) {
                AmpSectionLabel("New Orb", FleetTags.sectionHeading(ThreadTags.executorSectionOrb))
                PickerRow(title = "Orb", detail = "Project, size, and provider", tag = ThreadTags.executorOrb) { toggle(Expanded.Orb) }
                if (expanded == Expanded.Orb) {
                    OrbOptions(
                        projects, sizes.ifEmpty { ORB_SIZE_DETAILS.keys.map(::OrbSize) }, book, listing,
                        onBook = { next -> book = next.also(memory::save) },
                        onSignIn = { navigate(PaneDestination.Web(WebSurface.AccountPage(AccountWebPage.SignIn, account.id, SIGN_IN_RETURN))) },
                        onPick = onPick,
                    )
                }
            }
            Column(Modifier.testTag(ThreadTags.executorSectionRunners)) {
                AmpSectionLabel("Runners", FleetTags.sectionHeading(ThreadTags.executorSectionRunners))
                if (own.isEmpty()) Text("No runners online.", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.body, modifier = textX)
                own.forEach { runner ->
                    RunnerRow(runner, expanded == Expanded.Runner(runner.id), allowWorktree = runner.canCreateWorktree, prompt, onPick) { toggle(Expanded.Runner(runner.id)) }
                }
            }
            Column(Modifier.testTag(ThreadTags.executorSectionShared)) {
                AmpSectionLabel("Shared Runners", FleetTags.sectionHeading(ThreadTags.executorSectionShared))
                Text("Runs as the owner with their files and credentials. Threads here are visible to the workspace.", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary, modifier = textX)
                shared.forEach { runner ->
                    RunnerRow(runner, expanded == Expanded.Runner(runner.id), allowWorktree = false, prompt, onPick) { toggle(Expanded.Runner(runner.id)) }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, detail: String, tag: String, onClick: () -> Unit) {
    AmpListRow(title = title, onClick = onClick, testTag = tag, secondLine = { Text(detail, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta) })
}

@Composable
private fun Choice(label: String, detail: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AmpTheme.metrics.touch).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = AmpTheme.spacing.sm)) {
            Text(label, color = AmpTheme.colors.text.ink, style = AmpTheme.type.rowTitle)
            detail?.let { Text(it, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.meta) }
        }
    }
}

@Composable
private fun OrbOptions(
    projects: List<ProjectInfo>,
    sizes: List<OrbSize>,
    book: OrbProviderBook,
    listing: OrbProviderListing?,
    onBook: (OrbProviderBook) -> Unit,
    onSignIn: () -> Unit,
    onPick: (ExecutorChoice) -> Unit,
) {
    var project by remember { mutableStateOf<ProjectId?>(null) }
    var size by remember { mutableStateOf(DEFAULT_ORB_SIZE) }
    var picked by remember { mutableStateOf<OrbProvider?>(null) }
    var adding by remember { mutableStateOf(false) }
    val provider = picked?.let(book::merged) ?: book.initial
    val quiet = Modifier.testTag(ThreadTags.orbProviderStatus)
    Column(Modifier.padding(start = AmpTheme.spacing.rowPadX), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
        Text("Project", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.label)
        Choice("No Project", "A clean orb with no repo", project == null, ThreadTags.orbProjectNone) { project = null }
        projects.forEach { info -> Choice(info.name, null, project == info.id, ThreadTags.orbProject(info.id)) { project = info.id } }
        Text("Size", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.label)
        sizes.forEach { option -> Choice(option.key, ORB_SIZE_DETAILS[option.key], size == option, ThreadTags.orbSize(option)) { size = option } }
        Text("Provider", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.label)
        Choice("Built-in Orbs", providerDetail(book, OrbProvider.Amp), provider == OrbProvider.Amp, ThreadTags.orbProviderBuiltIn) { picked = OrbProvider.Amp }
        Text("Self-Hosted", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.label)
        when (listing) {
            null -> if (book.listed.isEmpty()) Text("Listing your self-hosted orbs…", quiet, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary)
            OrbProviderListing.SignedOut -> AmpListRow(title = "Sign in to ampcode.com to list your self-hosted orbs", onClick = onSignIn, testTag = ThreadTags.orbProviderSignIn)
            is OrbProviderListing.Failed -> Text(listing.reason, quiet, color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary)
            is OrbProviderListing.Listed -> Unit
        }
        book.offered.forEach { option ->
            when (option) {
                is OrbProvider.SelfHosted.Listed ->
                    Choice(option.name, providerDetail(book, option), provider == option, ThreadTags.orbProviderSelfHosted(option)) { picked = option }
                is OrbProvider.SelfHosted.Named -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Choice(option.name, providerDetail(book, option), provider == option, ThreadTags.orbProviderSelfHosted(option)) { picked = option }
                    }
                    AmpIconButton(AmpIcons.X, "Forget ${option.name}", {
                        if (picked == option) picked = null
                        onBook(book.forget(option))
                    }, ThreadTags.orbProviderForget(option))
                }
            }
        }
        if (adding) {
            ProviderNameForm { added ->
                if (book.merged(added) == added) onBook(book.add(added))
                picked = added
                adding = false
            }
        } else {
            AmpButton("Add Self-Hosted Provider", { adding = true }, ThreadTags.orbProviderAdd)
        }
        if (listing !is OrbProviderListing.Listed) {
            Text("Or add one by the name ampcode.com shows in Settings → Orbs.", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary)
        }
        AmpButton(
            "Use Orb", {
                onBook(book.used(provider))
                onPick(ExecutorChoice.Orb(project, size, provider))
            }, ThreadTags.orbUse, Modifier.align(Alignment.End),
            rank = AmpButtonRank.Primary,
        )
    }
}

/** The web composer's badges (`cf27bdbc.js`): Workspace Default and Personal; then Last used, once there is a choice. */
private fun providerDetail(book: OrbProviderBook, provider: OrbProvider): String? {
    val option = (provider as? OrbProvider.SelfHosted)?.let(book::option)
    return listOfNotNull(
        "Workspace Default".takeIf { option?.isDefault == true },
        "Personal".takeIf { option?.owner == OrbProviderOwner.Personal },
        "Last used".takeIf { book.offered.isNotEmpty() && provider == book.last?.let(book::merged) },
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun ProviderNameForm(onAdd: (OrbProvider.SelfHosted.Named) -> Unit) {
    var name by remember { mutableStateOf("") }
    val provider = OrbProvider.SelfHosted.named(name)
    Column(verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
        AmpTextField(
            value = name,
            onValueChange = { name = it },
            testTag = ThreadTags.orbProviderName,
            modifier = Modifier.fillMaxWidth(),
            label = "Provider name",
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { provider?.let(onAdd) }),
        )
        AmpButton("Add", { provider?.let(onAdd) }, ThreadTags.orbProviderSave, Modifier.align(Alignment.End), enabled = provider != null)
    }
}

@Composable
private fun RunnerRow(runner: RunnerInfo, expanded: Boolean, allowWorktree: Boolean, prompt: String, onPick: (ExecutorChoice) -> Unit, onToggle: () -> Unit) {
    PickerRow(title = runner.hostname, detail = runnerSummary(runner), tag = ThreadTags.executorRunner(runner.id), onClick = onToggle)
    if (expanded) RunnerDirectories(runner, allowWorktree, prompt, onPick)
}

@Composable
private fun RunnerDirectories(runner: RunnerInfo, allowWorktree: Boolean, prompt: String, onPick: (ExecutorChoice) -> Unit) {
    val several = runner.directories.size > 1
    var open by remember { mutableStateOf(if (several) null else 0) }
    var worktreeFor by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(start = AmpTheme.spacing.rowPadX), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
        runner.directories.forEachIndexed { index, directory ->
            val tap = if (several) Modifier.clickable(role = Role.Button) {
                open = index
                worktreeFor = null
            } else Modifier
            Column(Modifier.fillMaxWidth().then(tap).padding(vertical = AmpTheme.spacing.xs).testTag(ThreadTags.runnerDirectory(index))) {
                Text(directory, style = Mono)
                if (open == index) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AmpTheme.spacing.sm)) {
                        AmpButton("Current", { onPick(ExecutorChoice.Runner(runner.id, directory, WorktreeChoice.InPlace, null)) }, ThreadTags.runnerCurrent)
                        if (allowWorktree) AmpButton("New Worktree", { worktreeFor = index }, ThreadTags.runnerNewWorktree)
                    }
                    if (worktreeFor == index) {
                        WorktreeForm(directory, prompt) { name -> onPick(ExecutorChoice.Runner(runner.id, directory, WorktreeChoice.NewWorktree(name), null)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorktreeForm(directory: String, prompt: String, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf(worktreeNameSuggestion(prompt)) }
    Column(Modifier.padding(top = AmpTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(AmpTheme.spacing.xs)) {
        AmpTextField(
            value = name,
            onValueChange = { name = it },
            testTag = ThreadTags.worktreeName,
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            singleLine = true,
        )
        Text("Directory ${lastSegment(directory)}-$name", color = AmpTheme.colors.text.ink, style = AmpTheme.type.secondary)
        Text("Branch $name", color = AmpTheme.colors.text.ink, style = AmpTheme.type.secondary)
        Text("From current HEAD after fetching upstream", color = AmpTheme.colors.text.inkMuted, style = AmpTheme.type.secondary)
        AmpButton(
            "Create Worktree", { onCreate(name) }, ThreadTags.worktreeCreate, Modifier.align(Alignment.End),
            rank = AmpButtonRank.Primary, enabled = isValidWorktreeName(name),
        )
    }
}
