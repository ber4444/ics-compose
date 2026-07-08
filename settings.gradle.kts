pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        gradlePluginPortal()
    }

    plugins {
        id("com.android.application") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "2.3.20"
        id("org.jetbrains.kotlin.multiplatform") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
        id("org.jetbrains.compose") version "1.10.3"
    }
}
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.81.1")
            classpath("com.fasterxml.jackson.core:jackson-databind:2.18.8")
            classpath("io.netty:netty-handler:4.1.135.Final")
            classpath("io.netty:netty-codec-http2:4.1.135.Final")
            classpath("io.netty:netty-codec-http:4.1.135.Final")
            classpath("io.netty:netty-codec:4.1.135.Final")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
        }
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
