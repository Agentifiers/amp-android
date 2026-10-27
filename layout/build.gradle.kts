plugins {
    id("amp.android.library")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.window)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
