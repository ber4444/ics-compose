import java.util.Properties

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

// Transcription provider API keys are read from the gitignored `secrets.properties`
// at the repo root (copy secrets.properties.example) and exposed via BuildConfig.
// NOTE: BuildConfig strings are embedded in the APK and are extractable — this is a
// dev/portfolio convenience, not production key handling. For production, proxy the
// websocket through a backend that holds the key. Empty when the file/keys are absent.
val transcriptionSecrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

androidComponents {
    onVariants { variant ->
        val eventsPassword = (project.findProperty("icsEventsPassword") as String?) ?: "SECRET"
        variant.buildConfigFields?.put("EVENTS_PASSWORD", com.android.build.api.variant.BuildConfigField("String", "\"$eventsPassword\"", "Login-gate password"))

        val verticalDemoUrl = (project.findProperty("icsVerticalDemoUrl") as String?) ?: ""
        variant.buildConfigFields?.put("VERTICAL_DEMO_URL", com.android.build.api.variant.BuildConfigField("String", "\"$verticalDemoUrl\"", "Vertical demo URL"))

        val deepgramKey = transcriptionSecrets.getProperty("DEEPGRAM_API_KEY", "")
        variant.buildConfigFields?.put("DEEPGRAM_API_KEY", com.android.build.api.variant.BuildConfigField("String", "\"$deepgramKey\"", "Deepgram API key (local, gitignored)"))

        val sonioxKey = transcriptionSecrets.getProperty("SONIOX_API_KEY", "")
        variant.buildConfigFields?.put("SONIOX_API_KEY", com.android.build.api.variant.BuildConfigField("String", "\"$sonioxKey\"", "Soniox API key (local, gitignored)"))
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
