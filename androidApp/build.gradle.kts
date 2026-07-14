plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.livingpresence.inner.circle.squared"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.livingpresence.inner.circle.squared"
        minSdk = 23
        targetSdk = 36
        versionCode = 7009
        versionName = "8.0.7"
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
}

androidComponents {
    onVariants { variant ->
        val eventsPassword = (project.findProperty("icsEventsPassword") as String?) ?: "SECRET"
        variant.buildConfigFields?.put("EVENTS_PASSWORD", com.android.build.api.variant.BuildConfigField("String", "\"$eventsPassword\"", "Login-gate password"))

        val verticalDemoUrl = (project.findProperty("icsVerticalDemoUrl") as String?) ?: ""
        variant.buildConfigFields?.put("VERTICAL_DEMO_URL", com.android.build.api.variant.BuildConfigField("String", "\"$verticalDemoUrl\"", "Vertical demo URL"))
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation("androidx.core:core-ktx:1.19.0")
    debugImplementation(libs.compose.ui.tooling)
}
