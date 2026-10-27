package com.agentifiers.amp.buildlogic

/**
 * The module rules of settings.gradle.kts as data. `checkModuleGraph` fails on any project dependency
 * not in [allowed] for main configurations, or not in [allowed] + [allowedTestAndDebug] for test and
 * debug ones. `ampSourceBans` fails on a banned literal or import in a module outside the ban's allowedIn.
 */
object ModuleGraphRules {
    /** module -> modules it may depend on in main configurations. */
    val allowed: Map<String, Set<String>> = mapOf(
        ":core:model" to emptySet(),
        ":core:design" to setOf(":core:model"),
        ":store" to setOf(":core:model"),
        ":sync" to setOf(":core:model", ":store", ":vt"),
        ":layout" to setOf(":core:model"),
        ":vt" to emptySet(),
        ":waymote" to setOf(":sync"),
        ":feature:fleet" to FEATURE,
        ":feature:thread" to FEATURE,
        ":feature:changes" to FEATURE,
        ":feature:files" to FEATURE,
        ":feature:terminal" to FEATURE + ":vt",
        ":feature:desktop" to FEATURE + ":waymote",
        ":feature:space" to FEATURE,
        ":feature:puck" to FEATURE,
        ":feature:settings" to FEATURE,
        ":feature:web" to FEATURE,
        ":notify" to setOf(":core:model", ":sync", ":store"),
        ":fake-amp" to emptySet(),
        ":app" to setOf(
            ":core:model", ":core:design", ":store", ":sync", ":layout", ":vt", ":waymote", ":notify",
            ":feature:fleet", ":feature:thread", ":feature:changes", ":feature:files", ":feature:terminal",
            ":feature:desktop", ":feature:space", ":feature:puck", ":feature:settings", ":feature:web",
        ),
    )

    /** Extra edges allowed only in test configurations (:sync) and debug configurations (:app). :fake-amp never ships. */
    val allowedTestAndDebug: Map<String, Set<String>> = mapOf(
        ":sync" to setOf(":fake-amp"),
        ":app" to setOf(":fake-amp"),
    )

    enum class BanKind { Literal, ImportPrefix, Pattern }

    /** [allowedPaths] also exempts files whose path matches, wherever their module is. */
    data class SourceBan(val kind: BanKind, val text: String, val allowedIn: Set<String>, val why: String, val allowedPaths: Regex? = null)

    /** The token table, tests (which assert literal colors), and non-Kotlin files. */
    private val colorTokenFiles = Regex("""/core/design/src/main/kotlin/.*/theme/|/src/(test|androidTest)/|\.(xml|json|js|html|kts)$""")
    private const val COLOR_WHY = "every color is a token in :core:design theme/ (docs/design/brand.md)"

    /** The token table, tests, non-Kotlin files, and the widget's Glance adapter, which converts roles and names no size. */
    private val typeTokenFiles = Regex("""/core/design/src/main/kotlin/.*/theme/|/src/(test|androidTest)/|\.(xml|json|js|html|kts)$|/app/src/main/kotlin/.*/surfaces/WidgetType\.kt$""")
    private const val TYPE_WHY = "every text style is an AmpTheme.type role from :core:design theme/ (docs/design/brand.md \"Type and sizes\")"

    /** A named argument at the start of a line or after `(` or `,`, so `val lineHeight = ...` stays legal. */
    private fun namedArgument(name: String) = """(^|[(,])\s*$name\s*=(?!=)"""

    val bans: List<SourceBan> = listOf(
        SourceBan(
            BanKind.Literal, "/_app/remote/", setOf(":feature:web"),
            "web-remote SvelteKit functions change ids every deploy; only a web surface may name the path, to block it",
        ),
        SourceBan(BanKind.ImportPrefix, "android.webkit.", setOf(":feature:web"), "web content is a quarantined leaf"),
        SourceBan(BanKind.ImportPrefix, "androidx.webkit.", setOf(":feature:web"), "web content is a quarantined leaf"),
        SourceBan(BanKind.ImportPrefix, "androidx.browser.", setOf(":feature:web"), "Custom Tabs are a web surface"),
        SourceBan(BanKind.ImportPrefix, "okhttp3.", setOf(":sync", ":fake-amp"), "one network stack, owned by :sync, image loading included"),
        SourceBan(
            BanKind.ImportPrefix, "kotlinx.serialization.", setOf(":sync", ":store", ":waymote", ":fake-amp"),
            "domain types are not wire types; :waymote speaks amp-desktop-v1 control JSON by design",
        ),
        SourceBan(BanKind.Pattern, """\bColor\(\s*(0x|\d|\.\d)""", emptySet(), COLOR_WHY, colorTokenFiles),
        SourceBan(BanKind.Pattern, """\bColor\.(Red|Green|Blue|White|Black|Gray|DarkGray|LightGray|Yellow|Cyan|Magenta)\b""", emptySet(), COLOR_WHY, colorTokenFiles),
        SourceBan(BanKind.Pattern, """\b0x[Ff]{2}[0-9A-Fa-f]{6}\b""", emptySet(), COLOR_WHY, colorTokenFiles),
        SourceBan(BanKind.Pattern, """\b\d+(\.\d+)?\.sp\b""", emptySet(), TYPE_WHY, typeTokenFiles),
        SourceBan(BanKind.Pattern, namedArgument("fontSize"), emptySet(), TYPE_WHY, typeTokenFiles),
        SourceBan(BanKind.Pattern, namedArgument("lineHeight"), emptySet(), TYPE_WHY, typeTokenFiles),
        SourceBan(BanKind.Pattern, namedArgument("letterSpacing"), emptySet(), TYPE_WHY, typeTokenFiles),
        SourceBan(BanKind.Pattern, """\bTextStyle\(""", emptySet(), TYPE_WHY, typeTokenFiles),
        SourceBan(BanKind.Pattern, """\bMaterialTheme\.typography\b""", emptySet(), TYPE_WHY, typeTokenFiles),
        SourceBan(
            BanKind.Pattern,
            """\bandroidx\.compose\.material3\.(\*|(Button|ElevatedButton|FilledTonalButton|OutlinedButton|TextButton|IconButton|FilledIconButton|FilledTonalIconButton|OutlinedIconButton|IconToggleButton|FilledIconToggleButton|FilledTonalIconToggleButton|OutlinedIconToggleButton|ButtonDefaults|IconButtonDefaults)\b)""",
            setOf(":core:design"),
            "buttons are AmpButton ranks or AmpIconButton from :core:design",
        ),
        SourceBan(
            BanKind.Pattern,
            """\bandroidx\.compose\.material3\.(PrimaryTabRow|SecondaryTabRow|TabRow|ScrollableTabRow|PrimaryScrollableTabRow|SecondaryScrollableTabRow|Tab|LeadingIconTab|ListItem)\b""",
            setOf(":core:design"),
            "Material's tabs are 48dp and its list items 56dp; use AmpTabRow and AmpListRow at A2 sizes (docs/design/brand.md \"Type and sizes\")",
        ),
        SourceBan(
            BanKind.Pattern,
            """\b(TextField|OutlinedTextField|BasicTextField|SecureTextField|OutlinedSecureTextField|BasicSecureTextField)\(""",
            setOf(":core:design"),
            "fields are AmpTextField, AmpSearchField, or AmpInputBar from :core:design; the desktop keyboard sink is an invisible IME target, not a field",
            Regex("""/feature/desktop/src/main/kotlin/.*/DesktopToolbar\.kt$"""),
        ),
    )
}

private val FEATURE = setOf(":core:model", ":core:design", ":sync")
