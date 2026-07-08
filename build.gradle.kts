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
allprojects {
    tasks.configureEach {
        if (name.contains("Ios", ignoreCase = true) && !isMac) {
            enabled = false
        }
    }
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
