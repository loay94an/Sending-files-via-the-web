plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.wasla.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wasla.tv"
        minSdk = 21
        targetSdk = 28
        versionCode = 7
        versionName = "0.1.7"
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
    implementation("androidx.documentfile:documentfile:1.0.1")
}
