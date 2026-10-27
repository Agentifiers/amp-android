// build-logic (included build)  package amp.buildlogic
// The module rules of settings.gradle.kts as data. One table; the prose in settings.gradle.kts explains
// it, this enforces it:
//   - `./gradlew checkModuleGraph` (wired into `check`) fails on any project dependency not in [allowed]
//     for main configurations, or not in [allowed] + [allowedTestAndDebug] for test and debug ones.
//   - Lint rule `AmpSourceBans` (one detector, reads [bans]) fails on a banned literal or import
//     in a module outside the ban's allowedIn set.
package amp.buildlogic

object ModuleGraphRules {
    /** module -> modules it may depend on in main configurations. `implementation` only, so nothing leaks transitively. */
    val allowed: Map<String, Set<String>> = mapOf(
        ":core:model" to emptySet(),
        ":core:design" to setOf(":core:model"),
        ":store" to setOf(":core:model"),
        ":sync" to setOf(":core:model", ":store", ":vt"),         // :vt: TerminalSession owns the VtEngine
        ":layout" to setOf(":core:model"),
        ":vt" to emptySet(),
        ":waymote" to setOf(":sync"),                      // RelayChannel only; wire types are internal to :sync
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
        ":app" to setOf(":core:model", ":core:design", ":store", ":sync", ":layout", ":vt", ":waymote", ":notify",
            ":feature:fleet", ":feature:thread", ":feature:changes", ":feature:files", ":feature:terminal",
            ":feature:desktop", ":feature:space", ":feature:puck", ":feature:settings", ":feature:web"),
    )

    /** Extra edges allowed only in testImplementation (:sync) and debugImplementation (:app). :fake-amp never ships. */
    val allowedTestAndDebug: Map<String, Set<String>> = mapOf(
        ":sync" to setOf(":fake-amp"),
        ":app" to setOf(":fake-amp"),
    )

    enum class BanKind { Literal, ImportPrefix }
    data class SourceBan(val kind: BanKind, val text: String, val allowedIn: Set<String>, val why: String)

    val bans: List<SourceBan> = listOf(
        SourceBan(BanKind.Literal, "/_app/remote/", setOf(":feature:web"),
            "web-remote SvelteKit functions change ids every deploy (private-protocol.md:329); only a web surface may name the path, to block it"),
        SourceBan(BanKind.ImportPrefix, "android.webkit.", setOf(":feature:web"), "web content is a quarantined leaf"),
        SourceBan(BanKind.ImportPrefix, "androidx.webkit.", setOf(":feature:web"), "web content is a quarantined leaf"),
        SourceBan(BanKind.ImportPrefix, "androidx.browser.", setOf(":feature:web"), "Custom Tabs are a web surface"),
        SourceBan(BanKind.ImportPrefix, "okhttp3.", setOf(":sync", ":fake-amp"), "one network stack, owned by :sync, image loading included"),
        SourceBan(BanKind.ImportPrefix, "kotlinx.serialization.", setOf(":sync", ":store", ":waymote", ":fake-amp"),
            "domain types are not wire types; :waymote speaks amp-desktop-v1 control JSON by design"),
    )
}

private val FEATURE = setOf(":core:model", ":core:design", ":sync")
