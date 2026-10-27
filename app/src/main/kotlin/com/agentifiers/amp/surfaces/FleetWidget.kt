package com.agentifiers.amp.surfaces

import com.agentifiers.amp.design.AmpTheme
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.agentifiers.amp.AmpApplication

/** Home-screen list of the threads that need you and the threads that are working, from the window's account. */
class FleetWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val surfaces = context.surfaces()
        provideContent {
            val glance by surfaces.glance.collectAsState()
            FleetWidgetContent(glance)
        }
    }
}

class FleetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FleetWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        context.surfaces().refresh()
    }
}

class RefreshFleet : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.surfaces().refresh()
    }
}

private fun Context.surfaces(): FleetSurfaces = (applicationContext as AmpApplication).surfaces

@Composable
private fun SurfaceAction.intentOf(): Intent = intent(LocalContext.current)

private val fg: ColorProvider @Composable get() = ColorProvider(AmpTheme.colors.text.ink)
private val dim: ColorProvider @Composable get() = ColorProvider(AmpTheme.colors.text.inkMuted)
private val accent: ColorProvider @Composable get() = ColorProvider(AmpTheme.colors.accents.accent)

@Composable
private fun FleetWidgetContent(glance: FleetGlance) {
    Column(GlanceModifier.fillMaxSize().background(AmpTheme.colors.surfaces.canvas).cornerRadius(16.dp).padding(AmpTheme.spacing.md)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Amp",
                style = WidgetType.of(AmpTheme.type.paneTitle, fg),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(SurfaceAction.OpenFleet.intentOf())),
            )
            if (glance is FleetGlance.Ready) {
                Text(
                    "New", style = WidgetType.of(AmpTheme.type.button, accent),
                    modifier = GlanceModifier.padding(horizontal = AmpTheme.spacing.sm).clickable(actionStartActivity(SurfaceAction.NewThread.intentOf())),
                )
                Text("Refresh", style = WidgetType.of(AmpTheme.type.button, accent), modifier = GlanceModifier.clickable(actionRunCallback<RefreshFleet>()))
            }
        }
        Spacer(GlanceModifier.height(AmpTheme.spacing.sm))
        when (glance) {
            FleetGlance.Starting -> Note("Loading threads…")
            FleetGlance.SignedOut -> Text(
                "Sign in to Amp to see your threads.",
                style = WidgetType.of(AmpTheme.type.body, dim),
                modifier = GlanceModifier.clickable(actionStartActivity(SurfaceAction.OpenFleet.intentOf())),
            )
            is FleetGlance.Ready -> ReadyContent(glance)
        }
    }
}

@Composable
private fun ReadyContent(glance: FleetGlance.Ready) {
    glance.problem?.let { Note("Last refresh failed: $it") }
    if (glance.needsYou.isEmpty() && glance.working.isEmpty()) {
        Note("No thread is working or waiting on you.")
        return
    }
    val rows = buildList {
        if (glance.needsYou.isNotEmpty()) add(WidgetLine.Heading("Needs you")).also { glance.needsYou.forEach { add(WidgetLine.Thread(it)) } }
        if (glance.working.isNotEmpty()) add(WidgetLine.Heading("Working")).also { glance.working.forEach { add(WidgetLine.Thread(it)) } }
    }
    LazyColumn {
        items(rows, itemId = { it.id }) { line ->
            when (line) {
                is WidgetLine.Heading -> Text(
                    line.text.uppercase(), style = WidgetType.of(AmpTheme.type.section, dim),
                    modifier = GlanceModifier.padding(top = 6.dp, bottom = 2.dp),
                )
                is WidgetLine.Thread -> ThreadLine(line.row)
            }
        }
    }
}

@Composable
private fun ThreadLine(row: GlanceRow) {
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).background(AmpTheme.colors.surfaces.raised).cornerRadius(8.dp).padding(AmpTheme.spacing.sm)
            .clickable(actionStartActivity(SurfaceAction.OpenThread(row.thread).intentOf())),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.title, maxLines = 1, style = WidgetType.of(AmpTheme.type.rowTitle, fg), modifier = GlanceModifier.defaultWeight())
        Text(row.status, style = WidgetType.of(AmpTheme.type.meta, dim), modifier = GlanceModifier.padding(start = AmpTheme.spacing.rowTitleMetaGap))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = WidgetType.of(AmpTheme.type.body, dim))
}

private sealed interface WidgetLine {
    val id: Long

    data class Heading(val text: String) : WidgetLine {
        override val id = text.hashCode().toLong()
    }

    data class Thread(val row: GlanceRow) : WidgetLine {
        override val id = row.thread.raw.hashCode().toLong() shl 1
    }
}
