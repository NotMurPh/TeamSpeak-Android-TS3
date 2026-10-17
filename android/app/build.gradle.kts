plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciVersionCode = providers.gradleProperty("ciVersionCode").orNull?.let {
    requireNotNull(it.toIntOrNull()?.takeIf { code -> code in 1..2100000000 }) {
        "ciVersionCode must be an integer between 1 and 2100000000"
    }
} ?: 1
val ciVersionName = providers.gradleProperty("ciVersionName").orNull?.also {
    require(it.isNotBlank()) { "ciVersionName must not be blank" }
} ?: "0.1.0"
val releaseSigning = listOf(
    "ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull?.takeIf { value -> value.isNotBlank() } }
val releaseKeystore = releaseSigning["ANDROID_KEYSTORE_PATH"]?.let { file(it) }

// Check the resolved graph, including aggregate and abbreviated release task requests.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project == project && it.name.contains("release", ignoreCase = true) }) {
        val missing = releaseSigning.filterValues { it == null }.keys
        check(missing.isEmpty()) { "Release signing requires environment variables: ${missing.joinToString()}" }
        check(releaseKeystore?.isFile == true) { "ANDROID_KEYSTORE_PATH must point to an existing keystore" }
    }
}

android {
    namespace = "com.tsrewrite.client"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.tsrewrite.client"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = releaseSigning["ANDROID_KEYSTORE_PASSWORD"]
            keyAlias = releaseSigning["ANDROID_KEY_ALIAS"]
            keyPassword = releaseSigning["ANDROID_KEY_PASSWORD"]
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    sourceSets.getByName("release").assets.srcDir(rootProject.file("../target/release-assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
}
