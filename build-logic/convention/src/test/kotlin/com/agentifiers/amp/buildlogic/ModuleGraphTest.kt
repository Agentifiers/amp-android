package com.agentifiers.amp.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ModuleGraphTest {
    private val modules = ModuleGraphRules.allowed.keys

    @Test
    fun featureNamingAnotherFeatureFails() {
        val edges = listOf(ModuleEdge(":feature:changes", "implementation", ":feature:thread"))
        assertEquals(
            listOf(":feature:changes -> :feature:thread (implementation) is not an allowed edge"),
            violations(modules, edges),
        )
    }

    @Test
    fun fakeAmpIsAllowedOnlyInDebugAndTest() {
        val edges = listOf(
            ModuleEdge(":app", "debugImplementation", ":fake-amp"),
            ModuleEdge(":sync", "testImplementation", ":fake-amp"),
            ModuleEdge(":app", "implementation", ":fake-amp"),
        )
        assertEquals(listOf(":app -> :fake-amp (implementation) is not an allowed edge"), violations(modules, edges))
    }

    @Test
    fun moduleMissingFromTableFails() {
        assertEquals(listOf(":feature:extra is not in ModuleGraphRules.allowed"), violations(modules + ":feature:extra", emptyList()))
    }

    @Test
    fun webkitImportOutsideFeatureWebIsBanned() {
        val text = "package x\nimport android.webkit.WebView\nval u = \"https://ampcode.com/_app/remote/abc\"\n"
        val hits = banHits(":feature:thread", File("X.kt"), text).map { it.line to it.ban.text }
        assertEquals(listOf(2 to "android.webkit.", 3 to "/_app/remote/"), hits)
        assertEquals(emptyList<Pair<Int, String>>(), banHits(":feature:web", File("X.kt"), text).map { it.line to it.ban.text })
    }

    @Test
    fun customTabsQualifiedNameIsBanned() {
        val text = "val i = androidx.browser.customtabs.CustomTabsIntent.Builder()"
        assertEquals(listOf(1 to "androidx.browser."), banHits(":feature:settings", File("X.kt"), text).map { it.line to it.ban.text })
    }

    @Test
    fun colorLiteralsAreBannedOutsideTheTokenTable() {
        val text = "val a = Color(0xFF123456)\nval b = Color.White\nval c = 0xFF3B4A6B.toInt()\nval d = Color(255, 0, 0)\n" +
            "val ok = AmpTheme.colors.text.ink\nval mask = bg.toLong() and 0xFFFFFFFFL\nval clear = Color.Transparent\n"
        val feature = File("/w/feature/thread/src/main/kotlin/com/agentifiers/amp/thread/X.kt")
        assertEquals(listOf(1, 2, 3, 4), banHits(":feature:thread", feature, text).map { it.line }.distinct())

        val theme = File("/w/core/design/src/main/kotlin/com/agentifiers/amp/design/theme/Forest.kt")
        val designCode = File("/w/core/design/src/main/kotlin/com/agentifiers/amp/design/code/Ansi.kt")
        val test = File("/w/feature/fleet/src/test/kotlin/com/agentifiers/amp/fleet/StatusDotTest.kt")
        assertEquals(emptyList<Int>(), banHits(":core:design", theme, text).map { it.line })
        assertEquals(listOf(1, 2, 3, 4), banHits(":core:design", designCode, text).map { it.line }.distinct())
        assertEquals(emptyList<Int>(), banHits(":feature:fleet", test, text).map { it.line })
    }

    @Test
    fun materialButtonsAreBannedOutsideCoreDesign() {
        val why = "buttons are AmpButton ranks or AmpIconButton from :core:design"
        fun hits(module: String, text: String) = banHits(module, File("X.kt"), text).map { it.line to it.ban.why }

        assertEquals(listOf(1 to why), hits(":feature:thread", "import androidx.compose.material3.TextButton"))
        assertEquals(emptyList<Pair<Int, String>>(), hits(":core:design", "import androidx.compose.material3.TextButton"))
        assertEquals(listOf(1 to why), hits(":feature:fleet", "import androidx.compose.material3.ButtonDefaults"))
        assertEquals(listOf(2 to why), hits(":feature:puck", "val x = 1\nandroidx.compose.material3.IconButton(onClick = {}) {}"))
        assertEquals(listOf(1 to why), hits(":app", "import androidx.compose.material3.*"))
        val allowed = "import androidx.compose.material3.RadioButton\nimport com.agentifiers.amp.design.components.AmpButton\n" +
            "AmpButton(\"Save\", onSave, Tags.save)\nimport androidx.compose.material3.TextField"
        assertEquals(emptyList<Pair<Int, String>>(), hits(":feature:thread", allowed))
    }

    @Test
    fun textSizesOutsideTheThemeFolderAreBanned() {
        val text = listOf(
            "Text(\"a\", fontSize = 13.sp)",
            "val s = Mono.copy(lineHeight = 18.sp)",
            "    letterSpacing = 4.sp,",
            "val t = TextStyle(color = ink)",
            "val u = MaterialTheme.typography.bodyMedium",
            "val half = 1.5.sp",
            "Text(\"ok\", style = AmpTheme.type.body)",
            "val lineHeight = with(density) { font.lineHeightPx.toDp() }",
            "val px = state.textSize.value.sp.toPx()",
            "Notification.BigTextStyle().bigText(text)",
            "if (style.fontSize == other) Unit",
        ).joinToString("\n")
        val feature = File("/w/feature/thread/src/main/kotlin/com/agentifiers/amp/thread/X.kt")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), banHits(":feature:thread", feature, text).map { it.line }.distinct())

        val theme = File("/w/core/design/src/main/kotlin/com/agentifiers/amp/design/theme/AmpType.kt")
        val designMarkdown = File("/w/core/design/src/main/kotlin/com/agentifiers/amp/design/markdown/Markdown.kt")
        val widget = File("/w/app/src/main/kotlin/com/agentifiers/amp/surfaces/WidgetType.kt")
        val test = File("/w/core/design/src/test/kotlin/com/agentifiers/amp/design/theme/DensityTokensTest.kt")
        assertEquals(emptyList<Int>(), banHits(":core:design", theme, text).map { it.line })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), banHits(":core:design", designMarkdown, text).map { it.line }.distinct())
        assertEquals(emptyList<Int>(), banHits(":app", widget, text).map { it.line })
        assertEquals(emptyList<Int>(), banHits(":core:design", test, text).map { it.line })
    }

    @Test
    fun materialTabsAndListItemsAreBannedOutsideCoreDesign() {
        val text = "import androidx.compose.material3.PrimaryTabRow\nimport androidx.compose.material3.Tab\nimport androidx.compose.material3.ListItem\n" +
            "import androidx.compose.material3.TabRowDefaults\nimport com.agentifiers.amp.design.components.AmpTabRow\n"
        assertEquals(listOf(1, 2, 3), banHits(":app", File("X.kt"), text).map { it.line })
        assertEquals(emptyList<Int>(), banHits(":core:design", File("X.kt"), text).map { it.line })
    }

    @Test
    fun rawTextFieldsAreBannedOutsideCoreDesignExceptTheDesktopKeyboardSink() {
        val text = listOf(
            "OutlinedTextField(value = v, onValueChange = {})",
            "    TextField(state)",
            "BasicTextField(value, onChange)",
            "OutlinedSecureTextField(state = s)",
            "BasicSecureTextField(state = s)",
            "AmpTextField(value = v, onValueChange = {}, testTag = t)",
            "AmpSearchField(q, {}, \"Search\", t)",
            "import androidx.compose.foundation.text.BasicTextField",
        ).joinToString("\n")
        val feature = File("/w/feature/fleet/src/main/kotlin/com/agentifiers/amp/fleet/ActivityPane.kt")
        val sink = File("/w/feature/desktop/src/main/kotlin/com/agentifiers/amp/desktop/DesktopToolbar.kt")
        assertEquals(listOf(1, 2, 3, 4, 5), banHits(":feature:fleet", feature, text).map { it.line })
        assertEquals(emptyList<Int>(), banHits(":feature:desktop", sink, text).map { it.line })
        assertEquals(emptyList<Int>(), banHits(":core:design", File("/w/core/design/src/main/kotlin/X.kt"), text).map { it.line })
    }
}
