Compose Multiplatform app for Android, made in Kotlin.

Released on Google Play as Inner Circle Squared.

## About

This is an Android application built with Jetpack Compose and Kotlin for the Inner Circle Squared community. The app provides access to live event streaming and payment portals.

## Features

- Live event streaming integration with MX Player
- Audio-only streaming option
- Password-protected access
- Direct links to teaching and event payment portals

## Building

This project uses Gradle for building. To build the APK:

```bash
./gradlew assembleDebug
```

For release builds:

```bash
./gradlew assembleRelease
```

## Requirements

- Android SDK 36
- Minimum SDK 21 (Android 5.0)
- Kotlin 1.9.22
- Jetpack Compose

## Dependencies

- Jetpack Compose for UI
- Ktor for HTTP client
- Kotlin Coroutines for async operations
- AndroidX Activity Compose
