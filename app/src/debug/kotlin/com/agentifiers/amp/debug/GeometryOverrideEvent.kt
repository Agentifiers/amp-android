package com.agentifiers.amp.debug

import com.agentifiers.amp.GeometryOverride
import org.json.JSONObject

/** `geometry-override {widthDp, creasesDp}` lays the window out at that width and those creases; `{}` clears it. */
object GeometryOverrideEvent : AppEventHandler {
    override val name = "geometry-override"

    override suspend fun handle(payload: JSONObject, app: AppEventContext): String {
        if (!payload.has("widthDp")) {
            app.overrides.geometry.value = null
            return "cleared"
        }
        val width = payload.optDouble("widthDp").takeIf { it.isFinite() && it > 0 } ?: throw BadPayload("widthDp must be a positive number")
        val array = payload.optJSONArray("creasesDp") ?: throw BadPayload("missing \"creasesDp\" array")
        val creases = List(array.length()) { index ->
            array.optDouble(index).takeIf { it.isFinite() && it > 0 && it < width } ?: throw BadPayload("crease $index must lie inside the width")
        }
        app.overrides.geometry.value = GeometryOverride(width.toFloat(), creases.map(Double::toFloat))
        return "width ${width}dp creases $creases"
    }
}
