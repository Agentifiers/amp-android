package com.agentifiers.amp.surfaces

import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.text.TextStyle as Role
import androidx.compose.ui.text.font.FontWeight as RoleWeight

/** The app's type roles as Glance styles. Glance has its own TextStyle and only three weights, so 600 and up draw bold. */
internal object WidgetType {
    fun of(role: Role, color: ColorProvider): TextStyle =
        TextStyle(color = color, fontSize = role.fontSize, fontWeight = weight(role.fontWeight))

    private fun weight(weight: RoleWeight?): FontWeight = when {
        weight == null -> FontWeight.Normal
        weight >= RoleWeight.SemiBold -> FontWeight.Bold
        weight >= RoleWeight.Medium -> FontWeight.Medium
        else -> FontWeight.Normal
    }
}
