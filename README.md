# Inner Circle Squared

A Compose Multiplatform (Android + iOS + wasmJs) app for streaming live and
recorded HLS event streams, built on **Media3/ExoPlayer** (Android) and
**AVPlayer** (iOS) with a standalone KMP playback SDK (`:mediakit`).

**Live on Google Play:**
[com.livingpresence.inner.circle.squared](https://play.google.com/store/apps/details?id=com.livingpresence.inner.circle.squared)

It plays live/recorded HLS event streams from a Wowza nDVR server and turns four
*unadvertised* sibling renditions into a genuine client-side ABR ladder.

> 📐 The full design — ground-truth server measurements and the rationale
> behind each decision — lives in [`plan.md`](./plan.md).

## Features

- **Adaptive streaming.** Genuine client-side ABR synthesized from four
  unadvertised sibling renditions, with viewport-aware track selection so the
  chosen quality matches the surface size.
- **Live & recorded playback.** Live-vs-VOD is inferred from playlist inspection;
  live events expose a LIVE badge and jump-to-live, and the seek bar tracks a
  growing Wowza nDVR window without drift.
- **Thumbnail feed with scrub preview.** Poster tiles and a scrub-preview bubble 
  are powered by an ExoPlayer frame engine on Android, and native `AVAssetImageGenerator` 
  on iOS, avoiding the overhead of N full per-tile players.
- **Flexible presentation.** fit/fill/zoom resize matrix for both horizontal and
  vertical (9:16) video, rotate-to-fullscreen, auto-hiding controls,
  buffering/error UX, and a stats overlay + quality menu.
- **Background & Picture-in-Picture.** Playback is owned by a `MediaSession`
  service (surviving config changes), with PiP aspect-clamped for vertical video
  and background audio constrained to the low-bitrate audio-only tier.
- **Offline downloads.** Bounded (VOD) events download via WorkManager (Android) 
  and `AVAssetDownloadURLSession` (iOS) into a cache shared with playback; 
  truly-live events get no download affordance.
- **On-device captions.** Live transcription via a PCM audio tap → Vosk
  recognizer → Compose caption overlay; the model is fetched on demand, not
  bundled.
- **Cross-platform Parity.** A seamless unified experience across Android, iOS, and Wasm. 
  The app features native in-app Web navigation, unified UI aesthetics across all targets, 
  hardware-accelerated thumbnail extraction on iOS via `AVAssetImageGenerator`, and robust 
  native iOS background HLS downloading using `AVAssetDownloadURLSession`.

## Architecture

```
:composeApp        — app UI (feed, login, player), navigation, DI wiring
:mediakit          — KMP playback SDK
  commonMain       — PlaylistInspector (pure-Kotlin HLS parser), LadderSynthesizer
                     (multivariant playlist builder), LadderResolver (JIT rendition
                     probing), EventCatalog (parallel event probing), MediaKitConfig,
                     EventInfo/ProbedRendition/RenditionTier models
  androidMain      — ExoPlayer (in PlaybackService), PreviewFrameEngine,
                     DownloadCenter, MemoryGovernor, LadderMediaSourceBuilder
  iosMain          — AVPlayer playback (UIKitView + AVPlayerLayer cinterop),
                     PiP, background audio, native offline downloads
  wasmJsMain       — thin web target (poster tiles + in-app player screen)
```

The playback engine lives in `:mediakit`, **owned by a `MediaSessionService`**,
not by a composable — it survives config changes and enables background
audio/PiP.

- Shared UI and app state live in `composeApp/src/commonMain`
- Shared image resources live in `composeApp/src/commonMain/composeResources`
- Android entry points and playback integration live in `composeApp/src/androidMain`
- iOS entry points and AVPlayer integration live in `composeApp/src/iosMain`
- Web entry points live in `composeApp/src/wasmJsMain`

## Engineering decisions

The load-bearing design calls and the reasoning behind them. Full detail in
[`plan.md`](./plan.md) §"Scrutiny".

- **Client-side ladder synthesis** — the server's master advertises only one
  720p variant, but four segment-aligned siblings exist (`_360p`/`_160p`/`_aac`).
  `LadderSynthesizer` probes them just-in-time (chunklist `w`-tokens rotate) and
  emits a spec-correct multivariant playlist fed via a `data:` URI. The result is
  **genuine ABR on the production streams** — the single highest-leverage decision.

- **Efficient Thumbnail Extraction, not N per-tile players** — On Android, 
  `PreviewFrameEngine` is a single muted, video-only ExoPlayer extracting `_160p` 
  frames (~65 KB each) into an LRU cache. This avoids decoder exhaustion. On iOS, 
  we leverage `AVAssetImageGenerator` natively within Compose `UIKitView`s to 
  achieve performant frame extraction without full player instantiation.

- **Live vs VOD by playlist inspection** — `#EXT-X-ENDLIST` presence drives both
  the LIVE badge and download eligibility. Truly-live events (no `ENDLIST`) get no
  download affordance; bounded events download to disk.

- **Background audio at the audio-only tier** — with muxed HLS, disabling the
  video renderer stops decode but *not* download. Constraining track selection to
  the ladder's `_aac` tier (~51 kbps vs ~1 Mbps) is real savings while the
  `MediaSession` keeps audio going.

- **PiP aspect ratio clamped for vertical video** — `PictureInPictureParams` uses
  `videoSize`, clamped to the platform's 1:2.39–2.39:1 range so 9:16 content
  isn't rejected.

- **Downloads target a concrete rendition** — not the `data:` ladder — so cache
  keys stay stable and stored content sidesteps chunklist rotation.

## TODO: deferred from the AGP 9 migration

The supported Gradle 9/AGP 9 migration is planned in
[`plan.md`](./plan.md) under “Standalone PR plan — Gradle 9 and AGP 9
migration.” The migration PR intentionally leaves these follow-ups out:

- [ ] Rename `:composeApp` to `:shared` and extract a separate `:webApp`
  (JetBrains' recommended full platform restructure). The minimum supported
  split keeps iOS and Wasm in `:composeApp`.
- [ ] Revisit the ordinary dependency upgrades grouped into Dependabot PR #33.
  AndroidX, Media3, Ktor, coroutines, Metro, Vosk, and other runtime updates
  should be reviewed in smaller dependency-only PRs.
- [ ] Extract repeated build configuration into convention plugins after the
  migrated module boundaries have stabilized.
- [ ] Refresh checked-in or developer-local Android Studio run configurations
  to target `:androidApp`; this is IDE metadata, not part of the Gradle
  migration itself.
- [ ] Consider a later full AGP-defaults/R8 audit with release smoke testing.
  The migration adopts defaults needed for AGP 9, but does not redesign keep
  rules, shrinking policy, packaging, or release delivery.

## Building

### Android
```bash
./gradlew assembleDebug            # debug APK → composeApp/build/outputs/apk/debug/
./gradlew installDebug             # install on a connected device
```

### iOS
```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64   # Compose framework incl. the AVPlayer cinterop
```
A runnable SwiftUI host app lives in `iosApp/` (generated from
`project.yml` via `xcodegen`):
```bash
cd iosApp && xcodegen generate
xcodebuild -project ICSApp.xcodeproj -scheme ICSApp \
    -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build
```

> **cinterop note.** Kotlin 2.3.x's cinterop parses the def's `sources =
> AVPlayerBridge.m` but does not compile the Obj-C implementation into the
> framework, so `_OBJC_CLASS_$_AVPlayerBridge` stays undefined at link time.
> The Gradle build works around this by compiling `AVPlayerBridge.m` to LLVM
> bitcode (`.bc`) and injecting it into the cinterop klib's `natives/`
> directory, where the Kotlin/Native linker picks it up. No manual steps
> needed — `linkDebugFrameworkIosSimulatorArm64` handles it end-to-end.

### Web (Wasm)
```bash
./gradlew wasmJsBrowserDevelopmentRun
```

To serve the production build from a static server:
```bash
./gradlew wasmJsBrowserDistribution
# serve composeApp/build/dist/wasmJs/productionExecutable/
```

## Tests & SDK discipline
```bash
./gradlew :mediakit:test                # the pure-Kotlin SDK core (JVM)
./gradlew :composeApp:testDebugUnitTest # Robolectric unit tests (resize matrix,
                                        # MemoryGovernor tiers, MainViewModel)
./gradlew :mediakit:apiCheck            # binary-compatibility validation
./gradlew :mediakit:dokkaHtml           # API docs → mediakit/build/dokka/html
./gradlew koverXmlReport                # coverage report
```

The `:mediakit` public API surface is tracked under `mediakit/api/` — accidental
binary-breaking changes fail CI. Dokka API docs are published to GitHub Pages on
`main`.

## Configuration

The login-gate password is injected via a gradle property (`icsEventPassword`),
falling back to `SECRET` for dev/CI — it is not in source:
```
# gradle.properties or ~/.gradle/gradle.properties
icsEventPassword=your-password
```

The debug demo menu can offer a portrait/vertical clip for exercising the
vertical-video path (resize matrix, PiP clamping, orientation). No durable
public vertical test stream exists, so the URL comes from an optional gradle
property and the menu entry is hidden until it's set:
```
# gradle.properties, or -P at build time
icsVerticalDemoUrl=https://host/portrait.mp4
```
