package com.agentifiers.amp.layout

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Every vertical FoldingFeature WindowManager reports for [activity], as x positions in window dp. */
fun reportedVerticalFoldsDp(activity: Activity): Flow<List<Float>> =
    WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
        val density = activity.resources.displayMetrics.density
        info.displayFeatures
            .filterIsInstance<FoldingFeature>()
            .filter { it.orientation == FoldingFeature.Orientation.VERTICAL }
            .map { it.bounds.exactCenterX() / density }
    }

/**
 * The rotation of the display [activity] is on. A half turn keeps the same size and orientation, so it changes no
 * configuration and recomposes nothing; only a display listener sees it.
 */
fun displayRotation(activity: Activity): Flow<DisplayRotation> =
    callbackFlow {
        val displays = activity.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                activity.display?.takeIf { it.displayId == displayId }?.let { trySend(rotationOf(it.rotation)) }
            }

            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        activity.display?.let { trySend(rotationOf(it.rotation)) }
        displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        awaitClose { displays.unregisterDisplayListener(listener) }
    }.distinctUntilChanged()

private fun rotationOf(surfaceRotation: Int): DisplayRotation =
    when (surfaceRotation) {
        Surface.ROTATION_90 -> DisplayRotation.Quarter
        Surface.ROTATION_180 -> DisplayRotation.Half
        Surface.ROTATION_270 -> DisplayRotation.ThreeQuarter
        else -> DisplayRotation.Natural
    }
