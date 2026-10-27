plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.agentifiers.amp.fake.MainKt")
}

val copyCaptures by tasks.registering(Sync::class) {
    val captures = rootProject.file("spikes/protocol/captures")
    from(captures) { include("*.jsonl") }
    into(layout.buildDirectory.dir("generated/captures/fake-amp/captures"))
    doLast {
        val names = captures.listFiles { file -> file.name.endsWith(".jsonl") }!!.map { it.name.removeSuffix(".jsonl") }.sorted()
        destinationDir.resolve("index.txt").writeText(names.joinToString("\n", postfix = "\n"))
    }
}

sourceSets.main {
    resources.srcDir(copyCaptures.map { it.destinationDir.parentFile })
}

dependencies {
    implementation(libs.mockwebserver3)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
