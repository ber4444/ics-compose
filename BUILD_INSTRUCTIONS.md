# Build Instructions

This project uses Compose Multiplatform with Android and Web targets.

## Prerequisites

1. Java 17
2. Android SDK for Android builds
3. A modern browser for Web/Wasm development

## Android build

Create `local.properties` with your Android SDK path, then run:

```bash
./gradlew assembleDebug
```

## Web build

Run the development server with:

```bash
./gradlew wasmJsBrowserDevelopmentRun
```

## Source layout

- `composeApp/src/commonMain` contains shared Compose UI, ViewModel logic, navigation, and shared resources
- `composeApp/src/androidMain` contains Android entry points and Media3 playback integration
- `composeApp/src/wasmJsMain` contains the browser entry point and Web-specific playback handoff
