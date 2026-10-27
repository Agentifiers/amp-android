package com.agentifiers.amp.store

import kotlinx.coroutines.flow.first

/** How many window workspaces `workspaces.pb` keeps; the least recently saved go first. */
const val SAVED_WORKSPACES = 24

/** [entry] becomes the most recent save for its window key and account, and the oldest saves beyond [keep] drop. */
fun WorkspacesProto.upsert(entry: WorkspaceEntry, keep: Int = SAVED_WORKSPACES): WorkspacesProto =
    WorkspacesProto((windows.filterNot { it.windowKey == entry.windowKey && it.accountId == entry.accountId } + entry).takeLast(keep))

fun WorkspacesProto.without(accountId: String): WorkspacesProto = WorkspacesProto(windows.filterNot { it.accountId == accountId })

suspend fun AppPreferences.saveWorkspace(entry: WorkspaceEntry) {
    workspaces.updateData { it.upsert(entry) }
}

/** Sign-out wipes what the account owns, and a saved workspace names its threads. */
suspend fun AppPreferences.forgetWorkspaces(accountId: String) {
    workspaces.updateData { it.without(accountId) }
}
