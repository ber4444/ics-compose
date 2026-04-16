## About

This is an Android application built with Jetpack Compose and Kotlin for a small community project. 
The app provides access to live event streaming and payment portals.
Released on Google Play as Inner Circle Squared.

## Building

This project uses Gradle for building. To build the APK:

```bash
./gradlew assembleDebug
```

For release builds:

```bash
./gradlew assembleRelease
```

## Dependencies

- Jetpack Compose for UI
- Navigation 3 for navigation
- Metro for dependency injection
- Media3 / ExoPlayer for playback
- Ktor for HTTP client
- Kotlin Coroutines for async operations
- AndroidX Activity Compose
