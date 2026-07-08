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
tasks.register("testClasses") {
    subprojects {
        val subproject = this
        this@register.dependsOn(subproject.tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") })
    }
}
