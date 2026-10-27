plugins {
    id("amp.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    testOptions {
        unitTests.all { it.systemProperty("amp.captures", rootProject.file("spikes/protocol/captures").absolutePath) }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":store"))
    implementation(project(":vt"))
    api(libs.collections.immutable)
    api(libs.coroutines.core)
    api(libs.coil.core)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
    testImplementation(project(":fake-amp"))
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.coroutines.test)
}
