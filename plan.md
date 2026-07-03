# Plan: Media playback showcase — thumbnail feed, full-screen player, playback SDK

## Goal
Turn this KMP app into a portfolio-grade demonstration of the job requirement: *"Experience developing SDKs & video playback solutions with modern Android media frameworks (for example Media3/ExoPlayer), including player-based UIs, handling horizontal and vertical video."*

Concretely:
1. Replace the **Videos List dialog** with an **inline feed of video thumbnails** (poster frames, LIVE badges, durations). Tap a tile → **in-app full-screen player** with pause/seek/scrub-preview controls.
2. Extract the playback engine into a **`:mediakit` KMP library module** — a small playback SDK with a documented public API, consumed by the app. This is the "SDK experience" half of the job requirement.
3. Android = **ExoPlayer/Media3** (session service, PiP, downloads, memory governance). iOS = **AVPlayer** (later phase). Web (wasmJs) = hls.js (kept minimal).
4. Full **GitHub Actions CI** with unit + UI tests and coverage.

## Requirement → where it's demonstrated
| Job requirement | Deliverable |
|---|---|
| SDK development | `:mediakit` KMP module: explicit API mode, KDoc + Dokka docs, binary-compatibility validator, versioned |
| Media3/ExoPlayer | ExoPlayer HLS playback, client-side ABR ladder synthesis, `MediaSessionService`, `DownloadService`, custom `LoadControl`, track selection, frame extraction |
| Player-based UIs | Compose feed + full-screen player (auto-hiding controls, scrub preview, quality menu, buffering/error states) |
| Horizontal & vertical video | Aspect-ratio-driven layout matrix, portrait-video path, PiP aspect ratio, resize modes; verified with synthetic vertical sources in tests + demo menu |

## Ground truth (measured against the live server, 2026-07-03)
These facts were verified with `curl` against `65e54f30ec73c.streamlock.net` and drive several design decisions:

- **Events are genuinely live at certain times of day** (next window: ~5 PM today) on the *same* URLs; outside those windows the URL serves the completed Wowza nDVR recording of the event.
- **The advertised master playlist carries exactly one 720p variant** — no ladder, no `EXT-X-I-FRAME-STREAM-INF`, no storyboard tracks. **But unadvertised renditions exist** at `live/event{i}{suffix}` (all measured):

  | suffix | resolution | bandwidth | codecs |
  |---|---|---|---|
  | *(none)* | 1280×720 | ~1.0 Mbps | avc1.42c01f + mp4a.40.2 |
  | `_360p` | 640×360 | ~507 kbps | avc1.4d401e + mp4a.40.2 |
  | `_160p` | 284×160 | ~262 kbps | avc1.42c015 + mp4a.40.2 |
  | `_aac` | audio-only | ~51 kbps | mp4a.40.2 |

- **Renditions are segment-aligned** (event14: 1675/1674/1674 × 2.0 s segments, total durations within one segment) — consistent with Wowza transcoding one source with aligned GOPs, so cross-rendition switching is viable.
- **Chunklist URLs rotate**: every `playlist.m3u8` request mints a fresh `chunklist_w<token>_DVR.m3u8` name (observed across consecutive fetches; older names kept resolving). Any client-built ladder must resolve chunklists **just-in-time** at playback start.
- **Chunklists are bounded after the event ends**: they end with `#EXT-X-ENDLIST` (event14 ≈ 56 min) → effectively VOD → downloadable. During a live window `ENDLIST` is absent → that *is* the "is live" signal.
- **Segment duration is 2 s** → keyframes at ≤ 2 s intervals → keyframe-accurate seeking has ~2 s granularity.
- **CORS is open** (`Access-Control-Allow-Origin: *`) → hls.js on wasmJs works; the old plan's CORS risk is retired.
- **Missing events return a real 404** → the existing repository filter (skip 404s) is the correct "no placeholder tile" mechanism.

## Scrutiny of the proposed ideas (what changes and why)
Corrections a media reviewer would expect us to get right:

1. **"YouTube requests the scrubbed timestamp from the server at low resolution"** — not literally how YouTube works: YouTube ships **pre-generated storyboard sprite sheets** (tiled JPEGs at several tiers) downloaded ahead of scrubbing. Our server has no storyboard and no I-frame playlist, so the equivalent is **client-side keyframe extraction from the `_160p` rendition**: a second, muted, video-only ExoPlayer on `event{i}_160p` with `SeekParameters.CLOSEST_SYNC`, debounced scrub requests (~200 ms), rendering into a small preview surface, with an LRU bitmap cache. Cost per unique scrub stop ≈ one 2 s segment at ~262 kbps ≈ **65 KB**, decode cost trivial at 284×160. Precision ≈ keyframe interval (~2 s) — visually equivalent to YouTube's storyboard granularity. Net: the *mechanism* differs from the idea as stated, but the outcome (low-res frame fetched from the server for the scrubbed timestamp) is exactly what ships.
2. **"Fetch 144p/240p frames for thumbnails"** — confirmed feasible: the server exposes unadvertised `_160p` (284×160) and `_360p` renditions (measured). Tile posters and scrub previews fetch from `event{i}_160p` — the 144p-class source the idea asked for, already tile-sized. The preview player also sets `forceLowestBitrate(true)` so behavior is identical on any stream with an advertised ladder.
3. **ABR** — ExoPlayer does ABR automatically (`AdaptiveTrackSelection` + bandwidth meter) *when the master playlist advertises a ladder* — this server's masters advertise only one variant, so out of the box ABR is a no-op here. But the renditions exist, so we **synthesize the multivariant playlist client-side**: probe the four rendition playlists, resolve their chunklist URLs just-in-time (they rotate — see Ground truth), emit a spec-correct master with measured `BANDWIDTH`/`RESOLUTION`/`CODECS` attributes and absolute chunklist URIs, include `_aac` as the audio-only bottom tier (per Apple's ladder guidance), and hand it to `HlsMediaSource` via a `data:` URI (`DataSchemeDataSource`). Result: **genuine ABR on the production streams** plus a quality menu backed by real renditions. Fallback if chunklist rotation or GOP alignment bites: manual quality switching across rendition URLs with position-preserving reload.
4. **"Frame recycling / reuse video frame buffers"** — decoded frame buffers are owned and recycled by **MediaCodec** internally; this is not an app-level knob and claiming it would read as a red flag. The real, equivalent engineering: **one shared extractor/preview player** instead of N per-tile players (this supersedes the old plan's 3-player cap design), surface reuse, and a pooled/LRU bitmap cache for thumbnails.
5. **Live Caption** — a system feature the user toggles; apps cannot invoke it programmatically. Our obligations: correct `AudioAttributes` (`USAGE_MEDIA` — already set) and not blocking capture (`setAllowedCapturePolicy`), plus a README note. Actual in-app transcription = PCM tap via `TeeAudioProcessor` feeding an on-device recognizer (Vosk) — kept as a stretch phase because it's large (model asset, resampling, threading).
6. **"Pre-download with WorkManager"** — WorkManager alone cannot download HLS (hundreds of segments, resume, cache indexing). The correct Media3 architecture: **`DownloadService` + `HlsDownloader`**, with **`WorkManagerScheduler`** (`media3-exoplayer-workmanager`) so unmet-requirement restarts go through WorkManager, and `Requirements(NETWORK_UNMETERED)` for wifi-only. Backgrounding doesn't interrupt because `DownloadService` is a foreground service; WorkManager resumes it after process death. Measured reality: today's events are bounded (`ENDLIST`) → downloadable. Unbounded (truly live) events get a **LIVE badge** and no download affordance.
7. **404 handling** — already correct in `VideoRepository` (404s never enter the list). We extend it: probe in parallel, and if a stream 404s *after* listing (e.g. removed between probe and render), the tile's thumbnail load failure removes the tile rather than showing a placeholder.
8. **iOS/AVPlayer** — sound idea, own phase. Expert caveat: `AVAssetImageGenerator` does **not** work on HTTP Live Streams; iOS scrub previews use `AVPlayerItemVideoOutput`. iOS offline uses `AVAssetDownloadURLSession` (WorkManager is Android-only).
9. **"Pause video, continue audio in background"** — subtle trap: with muxed HLS (video+audio in the same TS segments), disabling the video renderer stops *decode* but **not download** — the muxed segments keep streaming at full bitrate. With the synthesized ladder including the `_aac` audio-only tier, backgrounding constrains track selection to that tier → real savings (~51 kbps vs ~1 Mbps) while the `MediaSession` keeps audio going. Verify ExoPlayer drops to the audio-only variant when video is disabled; fallback: swap the `MediaItem` to the `_aac` URL with a position handoff. Re-enable video on foreground. PiP is preferred when the user is actively watching and the device supports it.

## Architecture
```
:composeApp        — app UI (feed, login, screens), navigation, DI wiring
:mediakit          — KMP playback SDK (the showcase artifact)
  commonMain       — PlayerController (expect/actual facade), PlayerState (StateFlow),
                     EventCatalog (probe/parse), PlaylistInspector (pure-Kotlin HLS tag
                     parser: variants, ENDLIST/live, duration), LadderSynthesizer
                     (multivariant playlist builder over the probed renditions),
                     ThumbnailProvider API, DownloadState model, MediaKitConfig
  androidMain      — ExoPlayerController, PreviewFrameEngine (shared extractor player),
                     PlaybackService (MediaSessionService), DownloadCenter
                     (DownloadService + WorkManagerScheduler + CacheDataSource),
                     MemoryGovernor (LoadControl + onTrimMemory tiers)
  iosMain (P7)     — AVPlayerController, AVPlayerItemVideoOutput previews, PiP
  wasmJsMain       — thin hls.js binding
```
SDK discipline: `explicitApi()`, Dokka, `binary-compatibility-validator`, semantic version in the module, README with API examples. `PlaylistInspector` is deliberately pure Kotlin (no I/O) so it's unit-testable in `commonTest`.

---

## Phase 0 — Cleanup: remove audio-only, simplify navigation *(carried over from the previous plan; still valid)*
- **`commonMain/App.kt`**: delete the Audio-only `Row`/`Switch`, the Live Events `Button`, `onShowVideosDialog`/`onDismissVideosDialog`, the `VideosDialog` composable and its `if (uiState.isVideosDialogVisible)` block. Relabel the password `TextField` → **"Login to events"** (keep styling). Route becomes `player/{eventNumber}` — drop `PlayerAudioOnlyArg`, its `navArgument`, and the `audioOnly` decode.
- **`commonMain/MainViewModel.kt`**: remove dialog state + methods; remove `audioOnly` from `MainUiState`/`PlaybackRequest` (fold `PlaybackRequest` into the nav call); expose `ensureVideosLoaded()`.
- **`commonMain/Platform.kt`**: `getUrl(eventNumber)` single-arg (no `_aac` branch); `PlatformPlayerScreen(url, onClose)`.
- **`androidMain/Platform.android.kt`**: delete the `audioOnly` branch (the `Column` control-panel layout), `buildPlaybackMediaSource`'s merge path — `FilteringMediaSource`, `MergingMediaSource`, `deriveAudioOnlyUrl` all go. `audioAttributes` fixed to `AUDIO_CONTENT_TYPE_MOVIE`.
- **`wasmJsMain/Platform.wasmJs.kt`**: drop the `audioOnly` param + copy branch.
- Rationale: the stream already muxes AAC (`CODECS="avc1.42c01f,mp4a.40.2"`, verified), so the login-screen toggle and the dual-stream `MergingMediaSource` plumbing are dead weight. The `_aac` stream itself is *not* dead: it returns in Phase 3 as the synthesized ladder's audio-only tier — reached via track selection, never via URL merging.

## Phase 1 — `:mediakit` module + data layer + CI skeleton
- Create `:mediakit` KMP module; move player/URL/probe code behind its API. `getUrl` becomes `MediaKitConfig`-driven with rendition support — `renditionUrl(eventNumber, tier)` where tier ∈ {P720, P360, P160, AUDIO} maps to the `""`/`_360p`/`_160p`/`_aac` suffixes (base URL injectable → testable, and the hardcoded server stops being sprinkled through app code).
- **`PlaylistInspector`** (commonMain, pure functions): parse master → variant list (resolution/bandwidth/codecs, chunklist URI); parse chunklist → `isLive` (no `ENDLIST`), `durationMs` (Σ `EXTINF`), target duration.
- **`LadderSynthesizer`** (commonMain): pure function `(List<ProbedRendition>) -> String` emitting the multivariant playlist text (unit-testable with golden files), plus a suspend resolver that probes the rendition playlists in parallel and extracts fresh chunklist URLs (JIT, because they rotate). Renditions that 404 are simply omitted from the ladder — per-event rendition availability is not assumed.
- **`EventCatalog`** replaces `VideoRepository`'s sequential loop: probe events **in parallel** (bounded concurrency ~5, Ktor), returning `EventInfo(eventNumber, isLive, durationMs)` — 404s excluded (unchanged behavior, satisfies "no placeholder for 404"). Only the base rendition is probed for existence; the full ladder is resolved lazily at playback/preview time. Cache with a TTL; keep retry.
- **CI skeleton** (`.github/workflows/ci.yml`): `compileDebugKotlinAndroid` + `compileKotlinWasmJs`, `:mediakit:allTests` (commonTest: inspector, catalog with Ktor `MockEngine`, ViewModel with `kotlinx-coroutines-test`), ktlint or detekt, Kover coverage upload. CI grows with each later phase.

## Phase 2 — Thumbnail feed (replaces the dialog)
- **`LiveEventsGallery`** (commonMain): logged-in state of `LoginScreen` swaps the field for a feed — `LazyRow` (or 2-row grid if >8 events) of 16:9 tiles: poster thumbnail, title, **LIVE badge** (`isLive`) or duration label, loading/error/empty states. `expect LiveEventTile(...)` stays the per-platform seam.
- **Android `ThumbnailProvider`**: one shared **`PreviewFrameEngine`** — a single muted, video-only ExoPlayer (or `ExperimentalFrameExtractor` from `media3-transformer`; evaluate first, fall back to the DIY player+`ImageReader` if its HLS support or API stability disappoints) **pointed at the `_160p` rendition** — 284×160 is already tile-sized and a frame costs ~65 KB of network. Sequentially: load `event{i}_160p` → seek 10% in (`CLOSEST_SYNC`) → capture frame → **memory LRU + disk cache** keyed by event+position. Tiles render cached bitmaps; failures (late 404) remove the tile; if `_160p` is missing for an event, fall back to `_360p`, then base.
- Optional polish: the tile nearest the viewport center autoplays a muted preview using the same single engine (YouTube-feed pattern, still exactly one decoder). **This supersedes the old plan's N-mini-players-with-cap-3 design** — one decoder instead of three, and tiles are cheap bitmaps.
- **wasmJs tile**: keep the old plan's hover-to-play `<video>` overlay (create on hover-enter, destroy on exit, ≤1 active stream; bounds via `onGloballyPositioned`, hls.js from `index.html` with feature-detect). CORS verified open, so this now works in Chrome/Firefox, not just Safari.
- ViewModel: `MainUiState.availableVideos: List<EventInfo>`; `ensureVideosLoaded()` on login.

## Phase 3 — Full-screen player upgrades (Android)
The existing player (aspect-ratio-aware `PlayerSurface`, scrub slider, jump-to-live) is the base. Add:
- **Controls UX**: auto-hide after 3 s of inactivity (currently tap-toggle only), buffering spinner (`Player.STATE_BUFFERING`), error surface with retry (currently log-only `onPlayerError`), immersive mode (hide system bars).
- **Horizontal & vertical video** (explicit job requirement):
  - Layout matrix already compares content AR vs container AR — extend with **resize modes** (fit default; fill/zoom toggle) and a **portrait-content path**: 9:16 content in portrait fills height with pillarboxing rules; landscape content offers rotate-to-fullscreen (sensor-based, plus manual button).
  - Verified via `SimpleBasePlayer` fakes reporting portrait `videoSize` in UI tests + vertical demo source.
- **Scrub preview thumbnails** (the YouTube-style feature, per Scrutiny #1): while dragging, a floating preview above the seekbar; `PreviewFrameEngine` seeks the debounced target on the `_160p` stream (`CLOSEST_SYNC`), LRU-cached frames make repeat scrubs instant. Falls back gracefully (time-only bubble) when frames aren't ready.
- **Real ABR via ladder synthesis** (Scrutiny #3): the player's `MediaItem` is built from `LadderSynthesizer`'s multivariant playlist (`data:` URI → `HlsMediaSource`), so `AdaptiveTrackSelection` + `DefaultBandwidthMeter` adapt across 720p/360p/160p/audio-only on the production streams. `DefaultTrackSelector` gets `setViewportSizeToPhysicalDisplaySize`; **quality menu** = Auto + the real renditions via `TrackSelectionParameters`; debug stats overlay shows current rendition/bitrate/buffer (the adaptation is *visible* — strong interview artifact).
- **Demo-sources debug menu** (debug builds only): Apple's multivariant bipbop stream (server-advertised ladder + I-frame playlists), a vertical HLS/MP4 sample, and the production events — proves the player is not hardwired to one server's conventions, and covers vertical-video and trick-play cases this server lacks.

## Phase 4 — Lifecycle, background, PiP, memory
- **`PlaybackService`**: media3 `MediaSessionService` + `MediaSession` (artifact `media3-session`); notification with artwork (event thumbnail); `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission (API 34+); audio focus + becoming-noisy already handled, keep.
- **Backgrounding policy** (per Scrutiny #9): app to background while playing → if PiP available and user opted in → PiP; else continue audio constrained to the ladder's audio-only tier (~51 kbps — disabling the video renderer alone would keep downloading muxed segments at full rate); on return, restore video selection. Player ownership moves from the composable into the service/controller so process/config changes don't kill playback.
- **PiP**: `supportsPictureInPicture` + `configChanges` in the manifest; `PictureInPictureParams` with aspect ratio from `videoSize` (clamped to platform 1:2.39–2.39:1 — matters for vertical video), `setAutoEnterEnabled` (API 31+) + `setSourceRectHint`; controls collapse in PiP.
- **MemoryGovernor**:
  - **Adaptive `LoadControl`**: `DefaultLoadControl.Builder` tuned by `ActivityManager.isLowRamDevice()`/`getMemoryInfo` — e.g. low-RAM: `setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)` + `setTargetBufferBytes` cap; defaults otherwise. Numbers are tunables, documented as such.
  - **`onTrimMemory` tiers**: MODERATE → shrink thumbnail LRU; LOW → also release `PreviewFrameEngine` + cap `setMaxVideoSize` (no-op on this server, correct on ABR streams); CRITICAL → keep only the foreground player.
  - This trio (adaptive buffers, trim tiers, shared extractor) is the honest implementation of ideas #6–8 from the brief.

## Phase 5 — Offline downloads
- **`DownloadCenter`**: `DownloadService` + `DownloadManager` + `HlsDownloader` (via `DownloadHelper`), `SimpleCache` + `CacheDataSource` shared between download and playback; `WorkManagerScheduler` (`media3-exoplayer-workmanager`) for requirement-based restarts; `Requirements(NETWORK_UNMETERED)` = wifi-only.
- **Eligibility**: `EventInfo.isLive == false` (bounded playlist) → downloadable; live → LIVE badge, no download UI (per Scrutiny #6; outside live windows all events are bounded, so the feature is fully demoable).
- **Download quality**: downloads target a concrete rendition URL (default `_360p` ≈ 220 MB/h vs ~450 MB/h at 720p; user-selectable) rather than the synthesized `data:`-URI ladder — keeps `DownloadManager` cache keys stable and sidesteps chunklist rotation for stored content.
- Feed tiles get download state (progress ring / downloaded check / remove); player prefers cache automatically via `CacheDataSource`; airplane-mode playback is the acceptance test.
- Auto pre-download of new events on wifi as an opt-in setting (`DownloadManager.addDownload` on catalog refresh).

## Phase 6 — Tests, CI hardening, showcase polish
Tests are written *per phase*; this phase completes the harness:
- **commonTest**: `PlaylistInspector` (variant parsing, ENDLIST/live detection, duration math), `LadderSynthesizer` (golden-file playlist output, missing-rendition omission, attribute correctness), `EventCatalog` (MockEngine: 404 exclusion, parallel probe, error paths), `MainViewModel` state transitions.
- **Android unit (Robolectric)**: `ExoPlayerController` with `media3-test-utils(-robolectric)` (`TestPlayerRunHelper`, `FakeClock`); MemoryGovernor tier logic; DownloadCenter state mapping.
- **Compose UI tests**: feed states (loading/error/empty/populated, LIVE badge), player controls (auto-hide, seek callbacks, quality menu) against **`SimpleBasePlayer`** fakes — including a portrait `videoSize` fake for the vertical-video layout assertions; screenshot tests via Roborazzi (optional but cheap once fakes exist).
- **CI**: jobs for build (android debug + wasm), unit tests + Kover gate, instrumented tests via `reactivecircus/android-emulator-runner` (KVM on ubuntu runners), Dokka docs published to GH Pages, APK artifact upload. Badges in README.
- **README as showcase front door**: architecture diagram, GIFs (feed → player → scrub preview → PiP → offline), the Scrutiny section's engineering-judgment notes distilled (this is interview material), API docs link.
- Minor hardening: move the login gate password out of source (gradle property / `local.properties`).

## Phase 7 — iOS target (AVPlayer)
- Add `iosArm64`/`iosSimulatorArm64` + CMP iOS app entry; `AVPlayerController: PlayerController` in `iosMain`.
- Playback: `AVPlayer` + `AVPlayerLayer` in `UIKitView` (HLS + ABR are native); controls reuse the shared Compose UI driven by `PlayerState`.
- Scrub preview: `AVPlayerItemVideoOutput` frame grabs (**not** `AVAssetImageGenerator` — doesn't support HLS).
- Background audio: `AVAudioSession(.playback)` + `UIBackgroundModes: audio`; PiP: `AVPictureInPictureController`.
- Scope: playback + PiP + background audio = parity core. iOS offline (`AVAssetDownloadURLSession`) and transcription are explicitly out of scope for v1 of this phase. CI: macos runner compiles `iosSimulatorArm64` + runs common tests.

## Phase 8 — Stretch: transcription
- **Live Caption**: no code — verify `USAGE_MEDIA` (done) and default capture policy; README note on enabling it (per Scrutiny #5).
- **In-app on-device transcription**: custom `RenderersFactory` → `DefaultAudioSink` with `TeeAudioProcessor` tapping PCM → resample → **Vosk** small-model recognizer on a background thread → `StateFlow<List<CaptionCue>>` → Compose caption overlay with a CC toggle. Ship the model as a downloadable asset, not in the APK (~50 MB). Also enable Media3's built-in text renderer so CEA-608/708 would render if a stream ever carries them.

## Key technical decisions
- **One shared frame engine** for tiles + scrub previews (supersedes per-tile players): bounded decode/network cost, and it *is* the legitimate version of "frame recycling".
- **Client-side keyframe previews from the `_160p` rendition** instead of pretending the server has storyboards; debounce + LRU keeps cost ≈ 65 KB per unique scrub stop.
- **Client-side ladder synthesis** turns four unadvertised sibling renditions into a real ABR ladder (with an audio-only bottom tier that doubles as the background-audio path) — the single highest-leverage media decision in the plan.
- **Playback engine lives in `:mediakit`, owned by a service**, not by a composable — survives config changes, enables background audio/PiP, and is the SDK artifact the job asks about.
- **Live vs VOD decided by playlist inspection** (`ENDLIST`), driving both the LIVE badge and download eligibility.
- **wasmJs stays thin** (hover preview + in-page player); the showcase weight is Android-first, iOS second, per the job description.

## Risks & open questions
- `ExperimentalFrameExtractor` is `@UnstableApi` and may not love live-edge HLS → fallback DIY extractor is specced above; decide in Phase 2 with a spike.
- **Ladder synthesis fragilities**: chunklist `w`-token rotation (mitigated by JIT resolution at playback start; verify old chunklist URLs survive a full long session — if Wowza expires them mid-playback, fall back to manual rendition switching) and assumed GOP alignment across renditions (segment counts/durations match, but verify switches are glitch-free on device; misalignment → same fallback).
- The live path (no `ENDLIST`, sliding DVR window, `isCurrentMediaItemLive`, jump-to-live) must be re-verified during a real live window — one airs ~5 PM today, use it.
- Rendition availability per event is assumed uniform but unverified across all events — `LadderSynthesizer` omits 404ing renditions, so worst case an event degrades to fewer tiers.
- CMP-on-iOS `UIKitView` z-ordering with overlaid Compose controls needs a spike (known CMP interop sharp edge).
- Vosk model size/licensing and PCM resampling effort make Phase 8 genuinely optional.

## Verification
- Per phase: `./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinWasmJs :mediakit:allTests` green locally and in CI.
- Phase 2: login → feed shows only existing events (kill one URL → no tile, no placeholder); thumbnails appear without jank; scroll is 60 fps.
- Phase 3: scrub → preview bubble tracks the thumb with ~2 s granularity; stats overlay shows rendition switching on the production ladder under network throttling (`adb shell cmd netpolicy` / emulator network profiles); quality menu switches renditions on both production and bipbop streams; vertical sample fills portrait correctly.
- Phase 4: background during playback → audio continues (notification) at the audio-only tier (verify bandwidth drop in the stats overlay) or PiP; return → video resumes; `adb shell am send-trim-memory` exercises governor tiers.
- **Live window (e.g. today ~5 PM)**: feed shows the LIVE badge on the airing event, player follows the live edge, jump-to-live works, seek stays within the DVR window, download UI is absent for it.
- Phase 5: download on wifi, enable airplane mode → playback works; LIVE-badged items expose no download UI.
- Phase 6: CI green end-to-end, coverage badge live, Dokka published.

## Follow-up PRs (deferred items, tracked so they aren't forgotten)

These were deferred during Phases 0–6 (each called out in its PR's "Scope notes")
and form their own coherent follow-up PRs after Phase 6 lands. They are *not*
required for the showcase to be coherent — they deepen it.

- **FU-1: Scrub preview thumbnails.** While dragging the seekbar, a floating
  preview bubble; `PreviewFrameEngine` seeks the debounced target (~200 ms) on
  the `_160p` rendition (`CLOSEST_SYNC`), LRU-cached frames make repeat scrubs
  instant. Falls back to a time-only bubble when frames aren't ready. Needs the
  `PreviewFrameEngine` hoisted so the player screen and the feed share one
  instance (it currently lives in `MainActivity`'s composition; the player is
  service-owned, so this requires a lookup/`expect` seam to reach the engine
  from the service-driven controller path). See Scrutiny #1.

- **FU-2: `PreviewFrameEngine` disk cache.** Today the engine has an in-memory
  LRU only; the plan called for "memory LRU + disk cache" keyed by event +
  position. A `DiskLruCache` / media3 `SimpleCache`-backed frame store survives
  process death and avoids re-decoding on relaunch. Modest subsystem.

- **FU-3: `EventCatalog` TTL cache + retry.** Today the catalog re-probes every
  entry on each `loadEvents()` call. Add a bounded TTL cache (the plan's
  Phase 1) so repeated gallery opens don't re-hit the server, plus retry with
  backoff on probe failure. Resilience/efficiency polish.

- **FU-4: Demo-sources debug menu + rotate-to-fullscreen.** Debug-build menu
  with Apple's multivariant bipbop stream (server-advertised ladder + I-frame
  playlists — proves the player isn't hardwired to one server), a vertical
  HLS/MP4 sample, and the production events; plus sensor-based
  rotate-to-fullscreen for landscape content and a manual button. Demo polish
  that covers vertical-video and trick-play cases this server lacks.

- **FU-5: Viewport-aware ABR.** Enable
  `DefaultTrackSelector` viewport-size-from-physical-display so the player picks
  the rendition that fits the screen rather than decoding a higher one it can't
  show. A no-op on the single-720p advertised master but correct on the
  synthesized ladder and demo streams; needs the exact media3 1.10 extension
  resolved (the convenience extension's call site moved across versions).

