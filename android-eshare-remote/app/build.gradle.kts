import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionProperties = Properties().apply {
    file("../version.properties").inputStream().use { load(it) }
}
val appVersionName = versionProperties.getProperty("VERSION_NAME")
    ?: error("VERSION_NAME is missing from version.properties")
val appVersionCode = versionProperties.getProperty("VERSION_CODE")?.toIntOrNull()
    ?: error("VERSION_CODE must be an integer in version.properties")

android {
    namespace = "com.wasla.eshareremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wasla.eshareremote"
        minSdk = 23
        targetSdk = 28
        versionCode = appVersionCode
        versionName = appVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Native WebRTC engine for receiving the TV MediaProjection video track.
    implementation("io.github.webrtc-sdk:android:150.7871.04")
}
