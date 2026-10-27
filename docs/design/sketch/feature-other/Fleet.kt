// :feature:fleet  package amp.fleet
// Sidebar, rail, Activity, Running monitor, new-thread sheet. Reads Fleet / Catalog only.
// :app's PaneHost calls each pane from its exhaustive `when`.
package amp.fleet

import amp.model.*
import androidx.compose.runtime.Composable

/**
 * Sidebar (core.md:191-194). Each row: status dot, title, project, executor badge, unread/mention
 * badge, and a trailing [⋯] real button opening a menu of real buttons: Pin, Color, Labels, Rename,
 * Snooze ▸ (For 1 hour, Until next reply, Until 8am, Until Monday, Indefinitely; only one hour is
 * observed on the wire, S10), Mark unread, Archive, Delete,
 * Open in new window, Open in slot ▸. Swipe actions exist too but are never the only path.
 * "Inactive Last 72h" header has [Archive all] (Fleet.archiveAll) with a warning when any thread
 * owns automations or webhooks (collab-apps.md:389).
 * Rows are draggable (thread id as ClipData) onto slots and other windows.
 */
@Composable
fun SidebarPane(dest: PaneDestination.Sidebar, scope: amp.sync.api.AccountScope): Unit = TODO()

/** Activity feed with time chips (24h/72h/7d/all), status/user/project chips, and raw query field. */
@Composable
fun ActivityPane(dest: PaneDestination.Activity, scope: amp.sync.api.AccountScope): Unit = TODO()

/** Live monitor of working threads (amp top parity): state, elapsed, current tool, gate count. */
@Composable
fun RunningPane(dest: PaneDestination.Running, scope: amp.sync.api.AccountScope): Unit = TODO()

/**
 * New thread: composer + pickers. Executor picker: [Orb] project ▾ size ▾ provider ▾ (cost per hour
 * shown) | [Runner] runner ▾ (online/offline, shared, origin hostname) directory ▾ worktree ☐ |
 * [No machine]. Mode dial (low/medium/high/ultra + custom/plugin modes from listAgentModes✓);
 * locked after send. Visibility default by project ownership (core.md:282). Attachments as composer.
 * Submit → AccountScope.startThread(NewThreadSpec) → place(Thread(id)). SpawnFailed keeps the thread
 * and offers [Retry] and [Use another executor] (both AccountScope.respawn) and [Discard] (Archive).
 */
@Composable
fun NewThreadPane(dest: PaneDestination.NewThread, scope: amp.sync.api.AccountScope): Unit = TODO()
