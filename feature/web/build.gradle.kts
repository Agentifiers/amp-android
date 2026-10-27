plugins {
    id("amp.android.library")
    id("amp.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":sync"))
    implementation(libs.webkit)
    implementation(libs.browser)
    implementation(libs.activity.compose)
}
