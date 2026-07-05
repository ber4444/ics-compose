import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets (Phase 7): AVPlayer-backed playback in iosMain. Both ARM64
    // device and simulator share one iosMain intermediate source set.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        // AVPlayer Obj-C bridge. The Xcode 26.5 SDK + Kotlin/Native cinterop
        // combo fails to merge AVPlayer's Obj-C category methods
        // (play/pause/rate/seek/...) onto the generated AVPlayer class, so
        // those calls are unresolvable from Kotlin. This small cinterop wraps
        // them in a plain NSObject whose methods cinterop merges correctly.
        // See native/avplayer/cinterop/AVPlayerBridge.h.
        iosTarget.compilations.getByName("main").cinterops {
            create("avplayer") {
                defFile(project.file("native/avplayer/cinterop/avplayer.def"))
                // The def's `headers =` resolves relative to this dir, but cinterop's
                // clang invocation doesn't add it to the search path by default — pass
                // it explicitly so AVPlayerBridge.h is found regardless of CWD.
                compilerOpts("-I${project.file("native/avplayer/cinterop").absolutePath}")
            }
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("composeApp")
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(project(":mediakit"))
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.android)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.exoplayer.workmanager)
            implementation(libs.media3.datasource)
            implementation(libs.media3.session)
            implementation(libs.media3.ui.compose)
            implementation(libs.media3.ui.compose.material3)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.vosk.android)
            implementation(libs.splash.screen.support)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        // Robolectric unit tests for Android player/resize logic.
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.media3.test.utils.robolectric)
                implementation(libs.kotlinx.coroutines.test)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.uiTooling)
            }
        }
    }
}

android {
    namespace = "com.livingpresence.inner.circle.squared"
    compileSdk = 36

    sourceSets["main"].manifest.srcFile("src/main/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/main/res")

    defaultConfig {
        applicationId = "com.livingpresence.inner.circle.squared"
        minSdk = 23
        targetSdk = 36
        versionCode = 7009
        versionName = "8.0.7"

        // Login-gate password, sourced from a gradle/local property so it's not
        // in source. Falls back to "SECRET" so dev + CI builds without the
        // property still work.
        val eventsPassword = (project.findProperty("icsEventsPassword") as String?)
            ?: "SECRET"
        buildConfigField("String", "EVENTS_PASSWORD", "\"$eventsPassword\"")

        // Optional portrait demo clip URL (debug demo menu). No durable public
        // vertical test stream exists, so this defaults empty → the menu entry
        // is hidden until you point it at a portrait clip you host.
        //   -PicsVerticalDemoUrl=https://host/portrait.mp4
        val verticalDemoUrl = (project.findProperty("icsVerticalDemoUrl") as String?) ?: ""
        buildConfigField("String", "VERTICAL_DEMO_URL", "\"$verticalDemoUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.livingpresence.inner.circle.squared.generated.resources"
}

dependencies {
    debugImplementation(compose.uiTooling)
}
