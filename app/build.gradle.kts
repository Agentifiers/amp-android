import java.util.Properties

plugins {
    id("amp.android.application")
    id("amp.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}
val rivetPublishableToken: String = localProperties.getProperty("amp.rivetPublishableToken").orEmpty()

// Unset, the app never registers with a push relay and gets Web Push through UnifiedPush or the poll worker.
val pushRelayUrl: String? = localProperties.getProperty("amp.pushRelayUrl")?.trim()?.takeIf { it.isNotEmpty() }?.also {
    require(it.matches(Regex("https?://[^/]+/(.*/)?"))) { "amp.pushRelayUrl must be an http(s) URL ending in /, got $it" }
}

// The release key lives outside the repo. local.properties names the file that holds its location and passwords.
val releaseSigning = localProperties.getProperty("amp.releaseSigning")?.let(::file)?.takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

// A release for a device that already runs this app under another package name keeps that name, so the update
// keeps the app's data and push setup. Debug builds always use the default, which the flows and scripts open.
val defaultApplicationId = "com.agentifiers.amp"
val releaseApplicationId: String? = localProperties.getProperty("amp.releaseApplicationId")?.trim()?.takeIf { it.isNotEmpty() }?.also {
    require(it.matches(Regex("[A-Za-z]\\w*(\\.[A-Za-z]\\w*)+"))) { "amp.releaseApplicationId must be a package name, got $it" }
}

android {
    defaultConfig {
        applicationId = defaultApplicationId
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "RIVET_PUBLISHABLE_TOKEN", "\"$rivetPublishableToken\"")
        buildConfigField("String", "PUSH_RELAY_URL", pushRelayUrl?.let { "\"$it\"" } ?: "null")
    }
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets.getByName("main").res.srcDir("src/surfaces/res")
}

/** Writes res/xml/shortcuts.xml from src/surfaces/shortcuts.xml with the variant's package. */
abstract class ShortcutsTask : DefaultTask() {
    @get:InputFile abstract val template: RegularFileProperty
    @get:Input abstract val applicationId: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val xml = template.get().asFile.readText().replace("\${applicationId}", applicationId.get())
        outputDir.get().dir("xml").asFile.apply { mkdirs() }.resolve("shortcuts.xml").writeText(xml)
    }
}

androidComponents {
    onVariants { variant ->
        variant.applicationId.set(if (variant.buildType == "release") releaseApplicationId ?: defaultApplicationId else defaultApplicationId)
        val shortcuts = tasks.register<ShortcutsTask>("generate${variant.name.replaceFirstChar(Char::uppercase)}Shortcuts") {
            template.set(file("src/surfaces/shortcuts.xml"))
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(shortcuts, ShortcutsTask::outputDir)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":store"))
    implementation(project(":sync"))
    implementation(project(":layout"))
    implementation(project(":vt"))
    implementation(project(":waymote"))
    implementation(project(":notify"))
    implementation(project(":feature:fleet"))
    implementation(project(":feature:thread"))
    implementation(project(":feature:changes"))
    implementation(project(":feature:files"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:desktop"))
    implementation(project(":feature:space"))
    implementation(project(":feature:puck"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:web"))
    implementation(libs.activity.compose)
    implementation(libs.window)
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    implementation(libs.glance.appwidget)
    debugImplementation(project(":fake-amp"))
    testImplementation(libs.junit)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
