plugins {
    id("amp.android.library")
    id("amp.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:design"))
    implementation(project(":sync"))
    implementation(libs.core.telecom)
    implementation(libs.activity.compose)
    testImplementation(libs.junit)
}
