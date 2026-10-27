plugins {
    id("amp.android.library")
    id("amp.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":sync"))
    implementation(libs.activity.compose)
    implementation(libs.markdown.m3)
    testImplementation(libs.junit)
}
