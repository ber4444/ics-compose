import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "com.livingpresence.inner.circle.squared.shared"
        compileSdk = 36
        minSdk = 23
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTestBuilder {
            // isIncludeAndroidResources = true  (moved to testOptions if needed or omitted for now)
        }
    }

    // testOptions are typically not available directly in the simplified KMP DSL,
    // so we'll start with just withHostTestBuilder.

    val iosTargets = if (System.getProperty("os.name").startsWith("Mac OS X")) {
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
        )
    } else emptyList()
    
    iosTargets.forEach { iosTarget ->
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
        //
        // CAVEAT: Kotlin 2.3.x cinterop parses the def's `sources =` but does
        // NOT compile the Obj-C implementation into the klib/framework — so
        // _OBJC_CLASS_$_AVPlayerBridge stays undefined at link time. We work
        // around this by compiling AVPlayerBridge.m into a static archive with
        // the Konan clang + Xcode SDK ourselves (see compileAvplayerBridge*
        // below), and pointing the def's staticLibraries/libraryPaths at it.
        val cinteropDir = project.file("native/avplayer/cinterop")
        // Compile AVPlayerBridge.m to LLVM bitcode (.bc) so it can be injected
        // into the cinterop klib's natives/ directory, where K/N picks it up
        // during the framework link and embeds it alongside cstubs.bc. This is
        // the only mechanism that works for *static* frameworks: -force_load
        // and -ObjC linker opts are silently dropped by K/N's lld invocation
        // when producing a static framework archive.
        val cinteropTaskName = "cinteropAvplayer${iosTarget.targetName.replaceFirstChar { it.uppercase() }}"
        val bitcodeFile = layout.buildDirectory.file("tmp/avplayer/${iosTarget.targetName}/AVPlayerBridge.bc")
        val compileTask = tasks.register("compileAvplayerBridge${iosTarget.targetName.replaceFirstChar { it.uppercase() }}") {
            val source = cinteropDir.resolve("AVPlayerBridge.m")
            val sdk = when {
                iosTarget.targetName.contains("Simulator") || iosTarget.targetName.contains("X64") ->
                    providers.exec { commandLine("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path") }.standardOutput.asText.get().trim()
                else ->
                    providers.exec { commandLine("xcrun", "--sdk", "iphoneos", "--show-sdk-path") }.standardOutput.asText.get().trim()
            }
            val target = when {
                iosTarget.targetName.contains("Simulator") -> "arm64-apple-ios-simulator"
                iosTarget.targetName.contains("X64") -> "x86_64-apple-ios-simulator"
                else -> "arm64-apple-ios"
            }
            inputs.file(source)
            inputs.property("sdk", sdk)
            inputs.property("target", target)
            outputs.file(bitcodeFile)
            doFirst {
                val clang = file("${System.getProperty("user.home")}/.konan/dependencies/llvm-19-aarch64-macos-essentials-81/bin/clang")
                check(clang.exists()) { "Konan clang not found at $clang — install Kotlin/Native dependencies first" }
                val outFile = bitcodeFile.get().asFile
                outFile.parentFile.mkdirs()
                ProcessBuilder(
                    clang.absolutePath,
                    "-c", "-emit-llvm",
                    source.absolutePath,
                    "-o", outFile.absolutePath,
                    "-isysroot", sdk,
                    "-target", target,
                    "-fobjc-arc",
                    "-I${cinteropDir.absolutePath}"
                ).redirectErrorStream(true).start().apply {
                    val output = inputStream.bufferedReader().readText()
                    if (waitFor() != 0) error("Clang failed: $output")
                }
            }
        }

        iosTarget.compilations.getByName("main").cinterops {
            create("avplayer") {
                defFile(project.file("native/avplayer/cinterop/avplayer.def"))
                // The def's `headers =` resolves relative to this dir, but cinterop's
                // clang invocation doesn't add it to the search path by default — pass
                // it explicitly so AVPlayerBridge.h is found regardless of CWD.
                compilerOpts("-I${cinteropDir.absolutePath}")
            }
        }
        // After cinterop generates the klib (which contains cstubs.bc but NOT
        // our Obj-C implementation), inject AVPlayerBridge.bc into the klib's
        // natives directory. K/N links every .bc there into the framework, so
        // the AVPlayerBridge class ends up defined (not undefined) in the
        // final binary.
        val injectTask = tasks.register("injectAvplayerBridge${iosTarget.targetName.replaceFirstChar { it.uppercase() }}") {
            dependsOn(cinteropTaskName, compileTask)
            val bc = bitcodeFile
            // The klib native target dir name follows K/N's convention:
            // iosSimulatorArm64 -> ios_simulator_arm64, iosArm64 -> ios_arm64.
            val nativeTargetName = iosTarget.targetName.split("(?=\\p{Upper})".toRegex())
                .filter { it.isNotEmpty() }
                .joinToString("_") { it.lowercase() }
            val nativesDir = layout.projectDirectory.dir(
                "build/classes/kotlin/${iosTarget.targetName}/main/cinterop/composeApp-cinterop-avplayer/default/targets/$nativeTargetName/native"
            )
            inputs.file(bc)
            outputs.dir(nativesDir)
            doLast {
                val target = nativesDir.dir("AVPlayerBridge.bc").asFile
                target.parentFile.mkdirs()
                bc.get().asFile.copyTo(target, overwrite = true)
            }
        }
        // The compile + link tasks must run after injection.
        tasks.named("compileKotlin${iosTarget.targetName.replaceFirstChar { it.uppercase() }}").configure {
            dependsOn(injectTask)
        }
        tasks.matching { it.name == "commonizeCInterop" || it.name.endsWith("Cinterop-avplayerKlib") }.configureEach {
            dependsOn(injectTask)
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
        val androidHostTest by getting {
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

compose.resources {
    publicResClass = true
    packageOfResClass = "com.livingpresence.inner.circle.squared.generated.resources"
}

dependencies {
    // Debug dependencies moved to androidApp or runtime classpath
}
