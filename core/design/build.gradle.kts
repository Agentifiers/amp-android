plugins {
    id("amp.android.library")
    id("amp.android.compose")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.markdown.m3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.metadata.jvm)
}
