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
- **Thumbnail feed with scrub preview.** Poster tiles and a YouTube-style
  scrub-preview bubble are served by one shared frame engine backed by a
  memory + disk cache — no per-tile players.
- **Flexible presentation.** fit/fill/zoom resize matrix for both horizontal and
  vertical (9:16) video, rotate-to-fullscreen, auto-hiding controls,
  buffering/error UX, and a stats overlay + quality menu.
- **Background & Picture-in-Picture.** Playback is owned by a `MediaSession`
  service (surviving config changes), with PiP aspect-clamped for vertical video
  and background audio constrained to the low-bitrate audio-only tier.
- **Offline downloads.** Bounded (VOD) events download Wi-Fi-only via WorkManager
  into a cache shared with playback; truly-live events get no download affordance.
- **On-device captions.** Live transcription via a PCM audio tap → Vosk
  recognizer → Compose caption overlay; the model is fetched on demand, not
  bundled.
- **Cross-platform.** Android (Media3/ExoPlayer), iOS (AVPlayer via an Obj-C
  cinterop bridge), and a thin wasmJs web target, all sharing the `:mediakit`
  SDK and Compose UI.

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
                     PiP, background audio
  wasmJsMain       — thin web target (poster tiles + open-stream)
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

- **One shared frame engine, not N per-tile players** — `PreviewFrameEngine` is a
  single muted, video-only ExoPlayer extracting `_160p` frames (~65 KB each) into
  an LRU cache. This is the legitimate version of "frame recycling" — MediaCodec
  owns the decoded buffers; we own one extractor.

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
