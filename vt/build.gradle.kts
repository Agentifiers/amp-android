plugins {
    id("amp.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}
