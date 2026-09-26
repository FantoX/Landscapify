plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.landscapify.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.landscapify.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("LANDSCAPIFY_KEYSTORE_FILE")?.let(::file)
            storePassword = System.getenv("LANDSCAPIFY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LANDSCAPIFY_KEY_ALIAS")
            keyPassword = System.getenv("LANDSCAPIFY_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
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
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
