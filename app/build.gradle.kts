plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.farmbotingen"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.farmbotingen.bosshunter"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.1"
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
