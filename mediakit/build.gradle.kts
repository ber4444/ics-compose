import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.dokka)
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

    android {
        namespace = "com.livingpresence.mediakit"
        compileSdk = 37
        minSdk = 23
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTestBuilder {}
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // iOS targets (Phase 7): the SDK is pure commonMain Kotlin, so the targets
    // exist purely to publish the API into the iosMain consumer of :composeApp.
    val iosTargets = if (System.getProperty("os.name").startsWith("Mac OS X")) {
        listOf(
            iosArm64(),
            iosSimulatorArm64()
        )
    } else emptyList()

    iosTargets.forEach { iosTarget -> }

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

