# Build Instructions

This project has been transformed from Flutter to Jetpack Compose Multiplatform with a single Android target.

## Prerequisites

1. **Android SDK**: Install Android SDK (API level 36 recommended, minimum 21)
2. **Java JDK**: Java 17 is required
3. **Android Studio**: Recommended for development (latest stable version)

## Setup

### 1. Configure Android SDK Path

Create a `local.properties` file in the root directory (you can copy from `local.properties.template`):

```properties
sdk.dir=/path/to/your/android/sdk
```

On Linux/Mac, the Android SDK is typically located at:
- `~/Android/Sdk`
- `/Users/[username]/Library/Android/sdk` (Mac)

On Windows:
- `C:\Users\[username]\AppData\Local\Android\Sdk`

### 2. Build the Project

From the command line:

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration)
./gradlew assembleRelease
```

### 3. Install on Device

```bash
# Install debug build
./gradlew installDebug

# Install and run
./gradlew installDebug
adb shell am start -n com.livingpresence.inner.circle.squared/.MainActivity
```

## Using Android Studio

1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to this directory and open it
4. Wait for Gradle sync to complete
5. Click "Run" (green triangle) to build and run on a connected device or emulator

## Release Build Configuration

For release builds, you'll need to configure signing. The original Flutter project had signing configuration in `android/app/build.gradle` referencing a `key.properties` file. You'll need to:

1. Create or obtain the signing keystore
2. Add signing configuration to `composeApp/build.gradle.kts`

Example signing configuration:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("path/to/your/keystore.jks")
            storePassword = "your-store-password"
            keyAlias = "your-key-alias"
            keyPassword = "your-key-password"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ... other config
        }
    }
}
```

## Troubleshooting

### Network Issues

If you encounter errors like "Could not resolve..." or "dl.google.com" connection issues:

1. Check your internet connection
2. Try using a VPN if Google services are blocked
3. Check firewall/proxy settings
4. Try running with `--refresh-dependencies`: `./gradlew build --refresh-dependencies`

### Gradle Issues

If Gradle sync fails:

1. Check that your Android SDK path is correctly set in `local.properties`
2. Try invalidating caches: In Android Studio, go to File → Invalidate Caches / Restart
3. Delete the `.gradle` folder and re-sync
4. Ensure you have the required SDK platforms installed via Android Studio's SDK Manager

## What Changed from Flutter

- **UI Framework**: Flutter widgets → Jetpack Compose composables
- **Language**: Dart → Kotlin
- **Build System**: Flutter build system → Gradle
- **Dependencies**: 
  - `url_launcher` → Android Intent system
  - `http` → Ktor client
- **Project Structure**: 
  - `lib/main.dart` → `composeApp/src/main/kotlin/.../MainActivity.kt`
  - `assets/` → `composeApp/src/main/res/drawable/`
  - `android/` (Flutter wrapper) → `composeApp/` (native Android app)

## Key Dependencies

- **Jetpack Compose**: v1.7.6 - Modern Android UI toolkit
- **Compose Material3**: v1.4.0 - Material Design 3 components
- **Ktor Client**: v3.0.3 - HTTP client for network requests
- **Kotlin Coroutines**: v1.10.1 - Asynchronous programming

All functionality from the original Flutter app has been preserved:
- Password-protected UI
- Audio-only toggle
- Live event video list fetching
- MX Player integration
- Payment portal links
