plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.bletether.control"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bletether.control"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Stock ROM backend: tethering calls run as the shell user through Shizuku.
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    // Lifts hidden API checks for the reflection on TetheringManager / WifiManager.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
