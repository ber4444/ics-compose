pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        gradlePluginPortal()
    }

    plugins {
        id("com.android.application") version "9.2.1"
        id("org.jetbrains.kotlin.android") version "2.4.0"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
        id("org.jetbrains.compose") version "1.11.1"
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "InnerCircleSquared"
include(":composeApp")
include(":mediakit")
