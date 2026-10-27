package com.agentifiers.amp.debug

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import com.agentifiers.amp.surfaces.FleetWidgetReceiver
import kotlinx.coroutines.delay
import org.json.JSONObject

/** `pin-widget {}`: asks the launcher to pin the fleet widget, so a flow can put it on the home screen with one confirm tap. */
object PinWidget : AppEventHandler {
    override val name = "pin-widget"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        val manager = app.context.getSystemService(AppWidgetManager::class.java)
        if (!manager.isRequestPinAppWidgetSupported) return "refused: the launcher cannot pin widgets"
        // The event activity brings the app's window forward as it finishes; asking first would bury the launcher's confirm.
        delay(1_500)
        val asked = manager.requestPinAppWidget(ComponentName(app.context, FleetWidgetReceiver::class.java), null, null)
        return if (asked) "asked the launcher to pin the fleet widget" else "refused: the launcher declined the request"
    }
}
