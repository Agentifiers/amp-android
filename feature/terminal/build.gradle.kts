plugins {
    id("amp.android.library")
    id("amp.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":sync"))
    implementation(project(":vt"))
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
}
