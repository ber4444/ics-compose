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
