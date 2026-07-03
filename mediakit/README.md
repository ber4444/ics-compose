# :mediakit

A small Kotlin Multiplatform **playback SDK** — the showcase artifact for the
"SDK development" half of the job requirement. It owns everything stream-related
(URL construction, HLS playlist inspection, ABR-ladder synthesis, event probing)
so the app consumes a documented API rather than sprinkling a hardcoded server
through its UI code.

This is Phase 1 of the plan: the pure-Kotlin, unit-tested core. Platform
actuals (ExoPlayer on Android, hls.js on wasm) arrive in later phases.

## Targets

- **JVM** — runs `commonTest` (the testable core is pure Kotlin, no Android).
- **Android** — ExoPlayer/Media3 actuals (Phase 2+).
- **wasmJs** — hls.js binding (later phase).
- *iOS (AVPlayer) — Phase 7.*

`explicitApi()` is enabled: every public declaration is an intentional,
documented part of the API surface.

## Public API

### `MediaKitConfig`

Injectable stream configuration — the production host lives here, not in app
code.

```kotlin
val config = MediaKitConfig.Default   // production Wowza server, events 1..20

// Build playlist URLs for any rendition tier:
config.eventUrl(14)                                // 720p base
config.renditionUrl(14, RenditionTier.P160)        // 284×160 thumbnail source
config.renditionUrl(14, RenditionTier.AUDIO)       // audio-only `_aac` tier
```

### `PlaylistInspector`

Pure-Kotlin HLS parser — no I/O, unit-testable. This is how live-vs-VOD and
duration are determined.

```kotlin
val media = PlaylistInspector.parseMediaPlaylist(chunklistText)
media.isLive          // false when #EXT-X-ENDLIST is present (bounded → downloadable)
media.durationSeconds // Σ of all #EXTINF durations
media.targetDurationSeconds

val variants = PlaylistInspector.parseMaster(masterText) // List<Variant>
```

### `LadderSynthesizer`

The server advertises only one 720p variant, but four segment-aligned sibling
renditions exist (`_360p`, `_160p`, `_aac`). `LadderSynthesizer` turns probed
renditions into a spec-correct multivariant playlist so ExoPlayer's
`AdaptiveTrackSelection` does real ABR on the production streams.

```kotlin
val masterText = LadderSynthesizer.synthesize(probedRenditions)
// → feed to HlsMediaSource via a data: URI (Phase 3)
```

### `EventCatalog`

Replaces the app's sequential probe loop with bounded parallelism. 404s are
excluded (no placeholder tiles); live/duration come from playlist inspection.

```kotlin
val catalog = EventCatalog(httpClient, MediaKitConfig.Default)
val events: List<EventInfo> = catalog.loadEvents() // probed in parallel, ~5 at a time
events.first().let { it.isLive; it.durationMs; it.eventNumber }
```

## Testing

```sh
./gradlew :mediakit:jvmTest        # the pure-Kotlin core, fastest
./gradlew :mediakit:allTests        # JVM + Android + wasmJs
```

Coverage: `PlaylistInspector` (variant parsing, ENDLIST/live detection, duration
math), `LadderSynthesizer` (golden-file output, missing-rendition omission,
attribute correctness, round-trip), `EventCatalog` (MockEngine: 404 exclusion,
parallel probe, transport-error paths, live detection).
