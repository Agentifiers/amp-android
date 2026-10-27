plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("ampModuleGraph") {
            id = "amp.module-graph"
            implementationClass = "com.agentifiers.amp.buildlogic.ModuleGraphPlugin"
        }
        register("ampJvmLibrary") {
            id = "amp.jvm.library"
            implementationClass = "com.agentifiers.amp.buildlogic.JvmLibraryPlugin"
        }
        register("ampAndroidLibrary") {
            id = "amp.android.library"
            implementationClass = "com.agentifiers.amp.buildlogic.AndroidLibraryPlugin"
        }
        register("ampAndroidCompose") {
            id = "amp.android.compose"
            implementationClass = "com.agentifiers.amp.buildlogic.AndroidComposePlugin"
        }
        register("ampAndroidApplication") {
            id = "amp.android.application"
            implementationClass = "com.agentifiers.amp.buildlogic.AndroidApplicationPlugin"
        }
    }
}
