pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LiveKit Android pulls audioswitch from JitPack only.
        maven("https://jitpack.io") { content { includeGroup("com.github.davidliu") } }
    }
}

rootProject.name = "amp-android"

// Module map, owners, and forbidden edges: docs/design/sketch/settings.gradle.kts.
// build-logic ModuleGraphRules is the same table as data; `./gradlew checkModuleGraph` enforces it.
include(":core:model")
include(":core:design")
include(":sync")
include(":store")
include(":layout")
include(":vt")
include(":waymote")
include(":feature:fleet")
include(":feature:thread")
include(":feature:changes")
include(":feature:files")
include(":feature:terminal")
include(":feature:desktop")
include(":feature:space")
include(":feature:puck")
include(":feature:settings")
include(":feature:web")
include(":notify")
include(":app")
include(":fake-amp")
