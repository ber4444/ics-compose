# Inner Circle Squared

A Compose Multiplatform (Android + iOS + wasmJs) app for streaming live and
recorded HLS event streams, built on **Media3/ExoPlayer** (Android) and
**AVPlayer** (iOS) with a standalone KMP playback SDK (`:mediakit`).

**Live on Google Play:**
[com.livingpresence.inner.circle.squared](https://play.google.com/store/apps/details?id=com.livingpresence.inner.circle.squared)

It plays live/recorded HLS event streams from a Wowza nDVR server and turns four
*unadvertised* sibling renditions into a genuine client-side ABR ladder.

> 📐 The full design — ground-truth server measurements, scrutiny of every
> proposed idea, and a phase-by-phase plan — lives in [`plan.md`](./plan.md).
> Deferred follow-up PRs are tracked at the bottom of that file.

## Status

| Phase | What landed |
|-------|-------------|
| 0–1 | Audio-only plumbing removed; `:mediakit` KMP SDK extracted (`explicitApi`), pure-Kotlin HLS parsing + ladder synthesis, 27 common tests |
| 2 | VideosDialog → thumbnail feed (`PreviewFrameEngine`: one shared decoder, `_160p` frame extraction, LRU cache) |
| 3 | Player upgrades: real ABR via ladder synthesis, fit/fill/zoom resize matrix for horizontal **& vertical** video, auto-hide/buffering/error UX, stats overlay + quality menu |
| 4 | `PlaybackService` (MediaSession), PiP (aspect-clamped for vertical video), background-audio-to-audio-only-tier, `MemoryGovernor` |
| 5 | Offline downloads (`DownloadService` + `WorkManagerScheduler` + `SimpleCache` shared with playback), wifi-only, bounded events only |
| 6 | Test harness (Robolectric + unit tests), Dokka + binary-compatibility-validator + Kover, docs |
| 7 | iOS target (`iosArm64` + simulator): AVPlayer playback (`UIKitView` + `AVPlayerLayer` via an Obj-C cinterop bridge), PiP, background audio |
| 8 | On-device transcription: `TeeAudioProcessor` PCM tap → Vosk recognizer → Compose caption overlay (model fetched on demand, not bundled) |
| FU 1–5 | Scrub-preview thumbnails, poster-frame disk cache, `EventCatalog` TTL cache + retry, demo-sources debug menu + rotate-to-fullscreen, viewport-aware ABR |
| Live QA | Live path verified against an on-air stream (DVR window grows, stale chunklist URLs stay valid); DVR slider-drift fix + real-data playlist fixtures ([#14](https://github.com/ber4444/ics-compose/pull/14)) |

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
./gradlew :composeApp:compileKotlinIosSimulatorArm64   # Compose framework incl. the AVPlayer cinterop
```
(No Xcode app project is checked in yet; the target builds as a Compose framework.)

### Web (Wasm)
```bash
./gradlew wasmJsBrowserDevelopmentRun
```

To serve the production build from a static server:
```bash
./gradlew wasmJsBrowserDistribution
# serve composeApp/build/dist/wasmJs/productionExecutable/wasmJsBrowserDistribution
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
