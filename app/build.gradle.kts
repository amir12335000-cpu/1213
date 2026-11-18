plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val configuredServerUrl = (project.findProperty("serverBaseUrl") as String?) ?: System.getenv("SERVER_BASE_URL") ?: ""
val escapedServerUrl = configuredServerUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "ir.phonelink"
    compileSdk = 35

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "ir.phonelink"
        buildConfigField("String", "SERVER_BASE_URL", "\"$escapedServerUrl\"")
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core:1.15.0")
}
