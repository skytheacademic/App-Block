import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore + credentials live OUTSIDE git (*.jks and keystore.properties are
// gitignored). There is deliberately **no second copy** — a synced backup was the finding that
// closed A-1, because the same signature installs as an *update* and inherits the accessibility
// grant, which makes the key a bypass tool rather than just a build input.
// On a machine without the file — fresh clone — release still builds, just unsigned, so nothing
// else breaks. It simply can't update an installed App-Block in place.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.appblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appblock"
        minSdk = 26
        // Bumped to 36 ahead of One UI 9 / Android 17 reaching the S25 FE (stable 2026-07-22, this
        // phone in beta). The point isn't compatibility — targetSdk 35 runs fine on a newer OS — it's
        // having a freshly verified build in hand *before* the update lands, rather than debugging a
        // toolchain on a phone whose blocker has already stopped working.
        //
        // Every expensive Android 16 migration item was checked and none applies: edge-to-edge is
        // already handled (safeDrawingPadding), there are no onBackPressed overrides so the
        // predictive-back default costs nothing, no foreground services, and no runtime
        // registerReceiver calls.
        targetSdk = 36
        // 5 / 0.5.0: the two P0s from the third cable session — the accessibility-button picker (rule 3,
        // a control that names us) and repair mode disarming the Settings tier on one bad
        // canDrawOverlays reading. 4 / 0.4.0 was the N-2 second channel. 3 / 0.3.0 was audit Batch B
        // (N-4 day model + observer latch, the robustness items). 2 / 0.2.0 was Batch A (N-1, N-2, N-3,
        // G-1). Bumped per batch so App info on the phone says which build is installed; installs go in
        // ascending order because a release build can't be downgraded.
        versionCode = 5
        versionName = "0.5.0"

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
        // Its own applicationId, for the opposite reason to debugFast's below: not to stop this
        // build swapping itself in, but so it can get onto the phone at all. This variant is what
        // CI publishes, and CI is the only build route that doesn't need the laptop and a cable.
        // Unsuffixed it was package `com.appblock` signed with the debug key — the same package as
        // the release daily driver but a different signature, so it could neither update it
        // (INSTALL_FAILED_UPDATE_INCOMPATIBLE) nor install beside it. A green CI run produced an
        // APK that could not reach the device by any route. As com.appblock.debug it installs
        // alongside: download the artifact on the phone, install, no cable involved.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric: real resources + manifest in local unit tests (still JVM-only, no device).
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * Compose screen tests run in the **debug** variant only.
 *
 * `createComposeRule()` launches the bare `ComponentActivity` that `ui-test-manifest` declares, and
 * that artifact is `debugImplementation` — deliberately, since it adds an exported activity and the
 * release/`debugFast` builds are the hardened ones that go on the phone. Without this filter every
 * Compose test failed in those two variants with "Unable to resolve activity for …ComponentActivity",
 * so `./gradlew test` was red on a clean tree even though `testDebugUnitTest` was green. The engine
 * tests still run in every variant — only the screen tests are scoped.
 */
// `kotlinOptions` is deprecated in KGP 2.x; `compilerOptions` on the Kotlin extension is its
// replacement. Same value as compileOptions above — the two must agree or AGP fails the build.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    if (name != "testDebugUnitTest") {
        filter {
            excludeTestsMatching("com.appblock.*ScreenTest")
            isFailOnNoMatchingTests = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")

    // Pure-Java QR encoder (no camera, no Google Play Services, offline) — renders the durable-change
    // "stash" QR at key setup. Trust-minimal by design.
    implementation("com.google.zxing:core:3.5.3")
    // Embedded zxing camera scanner (still offline, no Play Services): scans the stashed QR back at
    // unlock time. Typing the code stays as the fallback path.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Provides the ComponentActivity that createComposeRule() launches; merged into the debug
    // manifest, which is what Robolectric unit tests run against.
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Robolectric UI/worker tests: real Android framework on the JVM — screens and workers get
    // coverage without a device. SDK pinned in each test via @Config for JDK-17 compatibility.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.1")
}
