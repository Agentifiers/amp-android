package com.agentifiers.amp.window

import com.agentifiers.amp.layout.CreaseTableEntry

/**
 * The TriFold_Inner_API36 AVD (scripts/create-trifold-avd.sh) stands in for the TriFold inner display but reports no
 * folding features, so debug builds give it the measured creases and flows see the panels the device shows.
 */
internal val StandInDevices: List<CreaseTableEntry> = listOf(
    CreaseTableEntry(modelPrefix = "sdk_gphone", windowWidthDp = 1080f, creasesDp = listOf(335f, 717f)),
)
