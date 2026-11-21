plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.spasm0dic.thereabouts"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.spasm0dic.thereabouts"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.10.1"
    }

    // Release signing comes from environment variables so no secrets live in the repo
    // (see docs/RELEASING.md). Without them, release builds are signed with the local
    // debug key: fine for trying things out, but such APKs can't update a real install.
    val keystorePath = System.getenv("THEREABOUTS_KEYSTORE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                val password = System.getenv("THEREABOUTS_KEYSTORE_PASSWORD")
                    ?: error("THEREABOUTS_KEYSTORE is set but THEREABOUTS_KEYSTORE_PASSWORD is not")
                storeFile = file(keystorePath)
                storePassword = password
                keyAlias = System.getenv("THEREABOUTS_KEY_ALIAS")?.ifBlank { null } ?: "thereabouts"
                // PKCS12 keystores use the same password for the key; allow a separate one anyway.
                keyPassword = System.getenv("THEREABOUTS_KEY_PASSWORD")?.ifBlank { null } ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        // Keeps the APK free of Google-encrypted blobs (needed for F-Droid).
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OpenStreetMap map view (Apache 2.0).
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
