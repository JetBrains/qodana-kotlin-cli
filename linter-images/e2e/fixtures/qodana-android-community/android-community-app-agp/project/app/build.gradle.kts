// Minimal single-module Android application. compileSdk matches the platform the qodana.yaml
// bootstrap installs via sdkmanager (android-34). No buildToolsVersion: a Qodana scan is a model
// import + inspections, not a build, so it never runs the SDK's resource compiler — pinning
// build-tools would force an arch-specific binary the scan never uses. (QD-15022, QD-15247)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.androidcommunityappagp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.androidcommunityappagp"
        minSdk = 24
        targetSdk = 34
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
