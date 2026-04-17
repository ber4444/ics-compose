## About

This project is a Compose Multiplatform application for the Inner Circle Squared community project.

## Building

### Android

```bash
./gradlew assembleDebug
```

### Web (Wasm)

```bash
./gradlew wasmJsBrowserDevelopmentRun
```

## Notes

- Shared UI and app state live in `composeApp/src/commonMain`
- Shared image resources live in `composeApp/src/commonMain/composeResources`
- Android entry points and playback integration live in `composeApp/src/androidMain`
- Web entry points live in `composeApp/src/wasmJsMain`
