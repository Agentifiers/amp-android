package com.agentifiers.amp.design

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagRegistryTest {
    private val areas: List<Any> = listOf(
        ShellTags, FleetTags, ThreadTags, ChangesTags, FilesTags, TerminalTags, GateTags,
        LayoutTags, SettingsTags, WebTags, NotifyTags, PuckTags, DesktopTags, SpaceTags,
    )

    private val fixedTags: List<Pair<String, String>> = areas.flatMap { area ->
        area::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && it.type == String::class.java }
            .map { "${area::class.java.simpleName}.${it.name}" to it.get(null) as String }
    }

    @Test
    fun noTwoAreasDeclareTheSameTag() {
        assertTrue("composer-send" in fixedTags.map { it.second })
        val clashes = fixedTags.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<String>>(), clashes)
    }

    @Test
    fun everyFixedTagIsKebabCase() {
        val kebab = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        assertEquals(emptyList<Pair<String, String>>(), fixedTags.filterNot { kebab.matches(it.second) })
    }
}
