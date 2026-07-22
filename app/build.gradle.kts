import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore + credentials live OUTSIDE git (*.jks and keystore.properties are
// gitignored; backup copy in the Dropbox planning folder, see keystore-backup/). On a machine
// without the file — fresh clone — release still builds, just unsigned, so nothing else breaks.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.appblock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.appblock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Real caps everywhere by default; only the debugFast variant flips this on.
        buildConfigField("boolean", "FAST_CAPS", "false")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 on: smaller APK, and stripped metadata makes on-device bypass tinkering harder.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Throwaway QA build: 1-minute caps so the block can be verified in ~90s instead of 15 min.
        // Its own applicationId so `adb install -r` can NEVER swap it in over the strict install
        // while inheriting the Accessibility/overlay grants — it installs alongside instead.
        // Non-debuggable so `run-as` can't edit its prefs either.
        create("debugFast") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".fast"
            versionNameSuffix = "-fast"
            isDebuggable = false
            buildConfigField("boolean", "FAST_CAPS", "true")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")

    // Pure-Java QR encoder (no camera, no Google Play Services, offline) — renders the durable-change
    // "stash" QR at key setup. Scanning it back is a later on-device convenience; unlocking today is
    // by entering the code the QR contains. Trust-minimal by design.
    implementation("com.google.zxing:core:3.5.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
