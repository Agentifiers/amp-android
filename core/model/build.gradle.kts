plugins {
    id("amp.jvm.library")
}

dependencies {
    api(libs.collections.immutable)
    testImplementation(libs.junit)
}
