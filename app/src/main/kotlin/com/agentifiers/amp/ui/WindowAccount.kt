package com.agentifiers.amp.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.agentifiers.amp.model.Account
import com.agentifiers.amp.model.AccountId
import com.agentifiers.amp.model.AccountRoster

/** What covers the window's workspace: nothing, the account switcher, or sign-in for another account. */
internal enum class WindowOverlay { None, Switcher, AddAccount }

/**
 * The account this window shows and its overlay. Each window holds its own, so two windows can show two accounts.
 * [switcherAnchor] is the avatar's window bounds from the tap that opened the switcher. It is not saved: after a
 * configuration change the avatar has moved, so a restored switcher has none.
 */
@Stable
internal class WindowAccount(chosen: AccountId?, overlay: WindowOverlay) {
    var chosen by mutableStateOf(chosen)
        private set
    var overlay by mutableStateOf(overlay)
        private set
    var switcherAnchor by mutableStateOf<Rect?>(null)
        private set

    fun switchTo(account: AccountId) {
        chosen = account
        overlay = WindowOverlay.None
    }

    fun openSwitcher(anchor: Rect) {
        switcherAnchor = anchor
        overlay = WindowOverlay.Switcher
    }

    fun addAccount() {
        overlay = WindowOverlay.AddAccount
    }

    fun dismiss() {
        overlay = WindowOverlay.None
    }

    companion object {
        val Saver: Saver<WindowAccount, Any> = listSaver(
            save = { listOf(it.chosen?.raw, it.overlay.name) },
            restore = { WindowAccount((it[0] as String?)?.let(::AccountId), WindowOverlay.valueOf(it[1] as String)) },
        )
    }
}

/**
 * The account a window with [chosen] shows: that account while it is signed in, else the last one used,
 * else the first. Null only when no account is signed in.
 */
internal fun AccountRoster.accountFor(chosen: AccountId?): Account? =
    accounts.firstOrNull { it.id == chosen } ?: accounts.firstOrNull { it.id == lastUsed } ?: accounts.firstOrNull()
