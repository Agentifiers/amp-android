package com.agentifiers.amp.window

import android.app.Activity
import android.content.Intent
import com.agentifiers.amp.MainActivity
import com.agentifiers.amp.layout.parseRoute
import com.agentifiers.amp.model.PaneDestination

private const val EXTRA_ROUTE = "com.agentifiers.amp.window.ROUTE"
private const val EXTRA_ALONE = "com.agentifiers.amp.window.ALONE"

/**
 * Opens another instance of the app window (layout.md "Multiple windows"). On a large screen the system places it
 * beside this one; elsewhere it opens as its own task. [opening] is what the new window shows first.
 */
fun Activity.openNewWindow(opening: WindowOpening?) {
    val intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    if (opening != null) intent.putExtra(EXTRA_ROUTE, opening.dest.route).putExtra(EXTRA_ALONE, opening.alone)
    startActivity(intent)
}

/** The opening a New Window intent carries, parsed at the boundary like any other saved route. */
fun Intent.windowOpening(): WindowOpening? {
    val dest: PaneDestination = getStringExtra(EXTRA_ROUTE)?.let(::parseRoute) ?: return null
    return WindowOpening(dest, getBooleanExtra(EXTRA_ALONE, false))
}
