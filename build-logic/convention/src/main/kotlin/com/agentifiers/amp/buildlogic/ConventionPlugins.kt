package com.agentifiers.amp.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

const val COMPILE_SDK = 36
const val MIN_SDK = 31
const val TARGET_SDK = 36

/** Namespace from the project path: `:feature:fleet` -> `com.agentifiers.amp.fleet`, `:core:design` -> `...amp.design`. */
fun namespaceOf(path: String): String = "com.agentifiers.amp." + path.split(':').last().replace('-', '.')

private fun Project.checkSourcesBefore(taskName: String) {
    val bans = registerSourceBans(this)
    tasks.matching { it.name == taskName }.configureEach { dependsOn(bans) }
}

/** Pure Kotlin module: :core:model, :vt. */
class JvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        extensions.configure(KotlinJvmProjectExtension::class.java) {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        }
        checkSourcesBefore("compileKotlin")
    }
}

/** Android library with the app's SDK levels and Java 17. Namespace follows the project path. */
class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure(LibraryExtension::class.java) {
            namespace = namespaceOf(path)
            compileSdk = COMPILE_SDK
            defaultConfig.minSdk = MIN_SDK
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        checkSourcesBefore("preBuild")
    }
}

/** Adds Compose to an Android library or application: compiler plugin, build feature, and the UI libraries. */
class AndroidComposePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("com.android.library") {
            extensions.configure(LibraryExtension::class.java) { buildFeatures.compose = true }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure(ApplicationExtension::class.java) { buildFeatures.compose = true }
        }
        val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        listOf("compose-ui", "compose-foundation", "compose-material3", "lifecycle-runtime-compose").forEach {
            dependencies.add("implementation", libs.findLibrary(it).get())
        }
    }
}

/** The app module: SDK levels, Java 17, and the source bans. */
class AndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure(ApplicationExtension::class.java) {
            namespace = "com.agentifiers.amp"
            compileSdk = COMPILE_SDK
            defaultConfig {
                minSdk = MIN_SDK
                targetSdk = TARGET_SDK
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        checkSourcesBefore("preBuild")
    }
}
