import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
    // SDK API-surface tracking: `apiCheck` validates the public API against
    // mediakit/api/mediakit.api, so accidental binary-breaking changes fail the build.
    alias(libs.plugins.binary.compatibility.validator)
}

kotlin {
    // The playback SDK: pure-Kotlin core (HLS parsing, ladder synthesis, event
    // probing) lives in commonMain so it is unit-testable on the JVM without
    // Android. Platform actuals (ExoPlayer, hls.js) arrive in later phases.
    explicitApi()

    jvm()

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.livingpresence.mediakit"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
