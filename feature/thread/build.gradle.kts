plugins {
    id("amp.android.library")
    id("amp.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":sync"))
    implementation(libs.coil.compose)
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
}
