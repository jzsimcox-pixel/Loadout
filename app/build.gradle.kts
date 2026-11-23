plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.loadout"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.loadout"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // When the build provides a shared signing key, every build is signed the
    // same way, so a new build installs over the old one. Without it, Android's
    // default debug key is used.
    val sharedKeystore = System.getenv("LOADOUT_KEYSTORE")
    val sharedPassword = System.getenv("LOADOUT_KEYSTORE_PASSWORD")
    val useSharedKey = !sharedKeystore.isNullOrBlank() && !sharedPassword.isNullOrBlank()

    signingConfigs {
        if (useSharedKey) {
            create("shared") {
                storeFile = file(sharedKeystore!!)
                storePassword = sharedPassword
                keyAlias = "loadout"
                keyPassword = sharedPassword
            }
        }
    }

    buildTypes {
        debug {
            if (useSharedKey) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    testImplementation("junit:junit:4.13.2")
}
