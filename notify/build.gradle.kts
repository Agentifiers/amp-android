import groovy.json.JsonSlurper

plugins {
    id("amp.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Firebase initializes from `app/google-services.json` (gitignored) when the file exists. The Google Services
 * plugin is not applied, so a missing file never fails the build; the app then uses UnifiedPush or polling.
 */
data class FirebaseConfig(val projectId: String, val senderId: String, val appId: String, val apiKey: String)

fun firebaseConfig(): FirebaseConfig? {
    val file = rootProject.file("app/google-services.json")
    if (!file.exists()) return null
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(file) as Map<String, Any?>
    val project = root["project_info"] as? Map<String, Any?> ?: return null
    val clients = root["client"] as? List<Map<String, Any?>> ?: return null
    val client = clients.firstOrNull {
        val info = it["client_info"] as? Map<String, Any?>
        (info?.get("android_client_info") as? Map<String, Any?>)?.get("package_name") == "com.agentifiers.amp"
    } ?: return null
    val appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as? String ?: return null
    val apiKey = (client["api_key"] as? List<Map<String, Any?>>)?.firstOrNull()?.get("current_key") as? String ?: return null
    return FirebaseConfig(project["project_id"] as String, project["project_number"] as String, appId, apiKey)
}

val firebase = firebaseConfig()

android {
    defaultConfig {
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase?.projectId.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebase?.senderId.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase?.appId.orEmpty()}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase?.apiKey.orEmpty()}\"")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":sync"))
    implementation(project(":store"))
    implementation(libs.hilt.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    // The connector's JVM Tink duplicates :sync's tink-android classes; the app never uses the connector's own keys.
    implementation(libs.unifiedpush.connector) { exclude(group = "com.google.crypto.tink", module = "tink") }
    implementation(libs.work.runtime)
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
}
