buildscript {
    // The Android Gradle Plugin drags vulnerable transitive libraries (netty, bouncycastle,
    // jose4j, jdom2, protobuf) onto the plugin classpath. They surface in GitHub's dependency
    // graph and trip Dependabot high/critical alerts. Force patched versions on the buildscript
    // classpath so only fixed versions are ever resolved.
    // NOTE: jackson is deliberately NOT forced here. Dokka (every version through 2.1.0) depends
    // on jackson-databind 2.12.7.1 and calls TypeFactory(LRUMap), a constructor removed in 2.16;
    // the jackson-databind CVE is only patched in 2.18.8, so there is no version that satisfies
    // both Dokka and the advisory. Forcing jackson breaks `dokkaHtml`. See the allprojects block.
    // (Inlined rather than shared with the allprojects block below: the buildscript block is
    // evaluated before top-level script declarations exist.)
    configurations.all {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "io.netty" -> useVersion("4.1.135.Final")
                "org.bouncycastle" -> useVersion("1.81.1")
                "com.google.protobuf" -> if (requested.name != "protobuf-bom") useVersion("3.25.5")
                "org.bitbucket.b_c" -> if (requested.name == "jose4j") useVersion("0.9.6")
                "org.jdom" -> if (requested.name == "jdom2") useVersion("2.0.6.1")
            }
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    // SDK discipline (plan.md Phase 1/6): Dokka API docs + Kover coverage gate.
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kover) apply true
}

// Dummy task to satisfy GitHub CodeQL Default Setup Autobuilder.
// CodeQL executes `testClasses` by default. We map this to all Kotlin compilation tasks
// so CodeQL can trace the compiler invocations without triggering native linking (which causes OOMs).
val isMac = System.getProperty("os.name").startsWith("Mac OS X")

// Force patched versions of vulnerable transitive dependencies (Dependabot high/critical
// alerts). The same libraries the AGP plugin classpath pulls in (see the buildscript block)
// also appear in AGP-injected project configurations — e.g. the Unified Test Platform, which
// brings in protobuf-java/protobuf-kotlin. Apply the override to every project configuration.
// jackson is intentionally omitted (see the buildscript block): forcing it to the patched
// 2.18.8 breaks Dokka's runtime classpath, which requires jackson 2.12.x.
fun DependencyResolveDetails.forceSecurityPatchedVersions() {
    when (requested.group) {
        "io.netty" -> useVersion("4.1.135.Final")
        "org.bouncycastle" -> useVersion("1.81.1")
        "com.google.protobuf" -> if (requested.name != "protobuf-bom") useVersion("3.25.5")
        "org.bitbucket.b_c" -> if (requested.name == "jose4j") useVersion("0.9.6")
        "org.jdom" -> if (requested.name == "jdom2") useVersion("2.0.6.1")
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency { forceSecurityPatchedVersions() }
    }
    tasks.configureEach {
        if (name.contains("Ios", ignoreCase = true) && !isMac) {
            enabled = false
        }
    }
}

// Kotlin/Wasm's browser toolchain pins a vulnerable `ws` (< 8.21.0) in
// kotlin-js-store/wasm/yarn.lock. Force the patched version via a Yarn resolution so the
// regenerated lockfile records 8.21.0 (Dependabot npm alert).
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>().resolution("ws", "8.21.0")
}

tasks.register("testClasses") {
    // This task is an empty placeholder because CodeQL autobuilder hardcodes `testClasses`.
    // It's already defined for subprojects, so creating it in root project satisfies CodeQL if it runs it at root.
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "testClasses" } })
    subprojects {
        val subproject = this
        this@register.dependsOn(subproject.tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") })
    }
}
