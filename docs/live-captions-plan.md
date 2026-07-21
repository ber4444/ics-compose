# Live Captions — Streaming ASR (Deepgram + Soniox) Implementation Plan

> **Living document.** Each step is checked off with a note when complete so another
> agent can resume. Read "Current status" first, then the step whose box is unchecked.

## Context / why

On-device Whisper (whisper.cpp via WhisperJNI) was tried for real-time captions and
**abandoned for real-time use**: on a Samsung Fold3 (SD888) base.en runs ~2× slower
than real time (~7 s per 4 s window), so ~half the audio is dropped → fragmented,
low-quality captions; multi-thread hit a native GGML barrier deadlock; the debug
`-O0` native build was ~25× too slow. Full diagnosis is in git history of
`TranscriptionEngine.kt`. Conclusion: **on-device can't do good real-time captions on
this hardware.**

Decision (with the maintainer):
- **LIVE events → cloud streaming ASR.** Two providers wired with a **UI switch**:
  **Deepgram** (default; recognizable for a portfolio, mature SDK/docs, $200 free
  credit) and **Soniox** (cheapest at $0.12/hr, bundles translation/diarization).
  Behind a provider-agnostic interface so swapping is a config change.
- **VOD events → batch subtitles** (out of scope for this doc; see "VOD path" note at
  bottom). Not needed for the live streaming feature.
- **Translation**: optional. Soniox includes real-time translation in-band; DeepL can
  be added later as a post-process. Not mandatory for MVP.
- **Platforms**: Android + iOS **mandatory**, web **optional**.
- **API keys**: gitignored config file → per-platform build wiring → runtime holder.
  Keys must never be committed and never hard-coded in shared code.

## Architecture

Most code is shared; only three things are per-platform.

**Shared (`composeApp/src/commonMain`)**
- `CaptionCue` (moved from androidMain)
- `TranscriptionProvider` enum (`DEEPGRAM`, `SONIOX`) + persisted selection
- `StreamingTranscriber` interface
- `DeepgramClient`, `SonioxClient` — Ktor websocket clients (Ktor is multiplatform)
- `TranscriptionSecrets` — runtime holder for API keys, set at startup per platform
- Caption overlay Composable (Compose Multiplatform)

**Per-platform**
| Piece | Android | iOS | Web |
|---|---|---|---|
| PCM tap | `TeeAudioProcessor` (exists) | `MTAudioProcessingTap` on AVPlayer | WebAudio `AudioWorklet` |
| Key provisioning | `androidApp` BuildConfig | xcconfig / Info.plist | build-time env |
| Ktor engine | `ktor-client-android` | `ktor-client-darwin` | `ktor-client-js` |

PCM contract fed to a `StreamingTranscriber`: **16 kHz, mono, signed 16-bit little-endian**.
Resampling from the source format (usually 48 kHz stereo) happens on the platform side
before `feedPcm` (reuse the anti-aliased resampler already in `TranscriptionEngine`).

### Provider protocol notes (for implementers)
- **Deepgram** (`wss://api.deepgram.com/v1/listen`): query params
  `model=nova-3&encoding=linear16&sample_rate=16000&channels=1&interim_results=true&punctuate=true&smart_format=true`.
  Auth header `Authorization: Token <KEY>`. Send raw PCM16 as binary frames. Receive
  JSON: `channel.alternatives[0].transcript` + top-level `is_final`. Finish by sending
  text frame `{"type":"CloseStream"}`.
- **Soniox** (`wss://stt-rt.soniox.com/transcribe-websocket`, verify current host in
  docs): first send a JSON **config** frame `{api_key, model:"stt-rt-v5",
  audio_format:"pcm_s16le", sample_rate:16000, num_channels:1, language_hints, ...}`,
  then stream PCM16 binary frames. Receive JSON `{tokens:[{text,is_final,...}]}`.
  Finish by sending `{"type":"finalize"}` then an empty frame to close.
  **Both endpoints/fields must be re-verified against current vendor docs before trusting.**

## Current status

**Phases 0, 1, 2 DONE** — shared core + full Android wiring compile and install on device;
the router receives tapped audio. **End-to-end captions unverified pending a real API key.**
**Next: Phase 3 (iOS).** Nothing committed yet — all uncommitted on `feature/platform-parity`
alongside the on-device Whisper pipeline (kept as an optional offline tier / "why cloud" story).

Build commands used (set `ANDROID_HOME=~/Library/Android/sdk`):
`./gradlew :composeApp:compileCommonMainKotlinMetadata` (shared), `:androidApp:installDebug` (device).

## Steps

### Phase 0 — Secrets / config (gitignored keys)
- [x] 0.1 `secrets.properties.example` created (repo root).
- [x] 0.2 `secrets.properties` created (gitignored, empty keys).
- [x] 0.3 `secrets.properties` added to root `.gitignore` (verified via `git check-ignore`).
- [x] 0.4 `androidApp/build.gradle.kts` reads `secrets.properties` → BuildConfig fields
  `DEEPGRAM_API_KEY` / `SONIOX_API_KEY`. NOTE: needed `import java.util.Properties` at top
  (bare `java.util.X` collides with Gradle's `java` extension accessor in `.kts`).
- [ ] 0.5 Push keys into `TranscriptionSecrets` at Android startup — **do in Phase 2**
  (MainActivity/Application: `TranscriptionSecrets.deepgramApiKey = BuildConfig.DEEPGRAM_API_KEY`, etc.).

### Phase 1 — Shared core (`commonMain`) — DONE
- [x] 1.1 Catalog: added `ktor-client-websockets` + `kotlinx-serialization-json` libs and
  `kotlinx-serialization` plugin (reused existing `ktor`/`kotlinx-serialization` versions).
  Did NOT need content-negotiation (WS frames parsed with `Json` directly).
- [x] 1.2 Added to `composeApp` commonMain deps; applied `libs.plugins.kotlinx.serialization`.
- [x] 1.3 `CaptionCue` moved to `commonMain` (same package, so androidMain refs unchanged);
  deleted `androidMain/.../CaptionCue.kt`.
- [x] 1.4 `TranscriptionProvider` enum + `TranscriptionSettings` (in-memory `StateFlow`).
  Persistence still TODO (DataStore/NSUserDefaults/localStorage).
- [x] 1.5 `TranscriptionSecrets` holder.
- [x] 1.6 `StreamingTranscriber` interface + `TranscriberStatus` + shared `CaptionAccumulator`
  (uses `MutableStateFlow.update`, NOT `synchronized` — the latter is JVM-only, breaks common).
- [x] 1.7 `DeepgramClient` — Ktor WS, `nova-3`, `Authorization: Token` header, binary PCM,
  parses `channel.alternatives[0].transcript` + `is_final`.
- [x] 1.8 `SonioxClient` — Ktor WS, JSON config handshake then binary PCM, token stream with
  `is_final`; commits a cue at sentence end / >80 chars. **Endpoint + fields UNVERIFIED against
  live service — re-check vendor docs.**
- [ ] 1.9 Shared caption overlay — DEFERRED. Android `CaptionOverlay.kt` (androidMain) still
  works for Android; move to commonMain when doing iOS (Phase 3) so both share it.

All of `commonMain` compiles (`:composeApp:compileCommonMainKotlinMetadata`). `HttpClient { install(WebSockets) }`
resolves the per-platform engine (android/darwin/js already in deps) with no engine arg.

### Phase 2 — Android wiring — DONE (compiles + installs; needs a key to verify captions)
- [x] 2.1 `CaptionAudioRouter` (androidMain singleton, implements new `PcmTapSink`) owns the
  active `StreamingTranscriber`, mirrors its captions/status/error, and switches provider.
  `TranscriptionRenderersFactory` + `CaptionAudioBufferSink` now feed `PcmTapSink` (not the
  Whisper engine); `PlaybackService` passes `CaptionAudioRouter.get()`. On-device Whisper is
  intentionally OUT of this path (kept as dead code / offline-tier story).
- [x] 2.2 Resampler in `CaptionAudioRouter.resampleTo16kMonoS16` (anti-aliased averaging
  decimation) → 16 kHz mono s16le `ByteArray` → `client.feedPcm`.
- [x] 2.3 UI: rewrote `CaptionController` to drive router + `TranscriptionSettings`.
  `CaptionToggleButton` now uses `status`/`error` (CC / CC… / CC● / CC!); added
  `CaptionProviderButton` (shown when enabled) that cycles Deepgram ↔ Soniox.
- [x] 0.5 Keys pushed in `MainActivity.onCreate`:
  `TranscriptionSecrets.deepgramApiKey = BuildConfig.DEEPGRAM_API_KEY` (+ Soniox).
- [~] 2.4 Verified: `:androidApp:installDebug` builds+installs; `CaptionAudioRouter` receives
  the PCM tap (logcat `AudioSink flush` under its tag). NOT yet verified end-to-end with a
  real key + live audio (empty keys → clients emit "Missing … API key" and CC shows "CC!").
  **TO FINISH: put a real key in `secrets.properties`, rebuild, enable CC on a LIVE source,
  confirm captions + the Deepgram/Soniox switch. Also re-verify the Soniox endpoint/fields
  against current docs — SonioxClient is written from research, not run against the service.**

### Phase 3 — iOS wiring (structural; verify in Xcode)
- Shared refactor done (helps iOS/web): provider selection + caption mirroring hoisted into
  `commonMain` `LiveTranscriber`; `CaptionAudioRouter` is now just Android capture+resample
  delegating to it. iOS/web instantiate `LiveTranscriber`, do their own tap, call `feedPcm`.
- **NOTE:** `:composeApp:compileKotlinIosSimulatorArm64` currently FAILS, but on
  **pre-existing** errors in `iosMain/Platform.ios.kt` (`DisposableEffect`/`onDispose`/
  `AVURLAsset`/`AVAssetImageGenerator` unresolved) that are UNRELATED to this feature (I never
  touched that file). The shared `transcription/` code compiled past those (no errors reference
  it). Fix the iOS player build first, then this feature's shared code is iOS-ready.
- [ ] 3.1 `MTAudioProcessingTap` on the AVPlayerItem audio mix → resample to 16k mono s16 →
  `LiveTranscriber.feedPcm`.
- [ ] 3.2 Key provisioning via xcconfig/Info.plist → `TranscriptionSecrets` (set at app launch).
- [ ] 3.3 Caption overlay over the AVPlayer view (move `CaptionOverlay` to commonMain, step 1.9).

### Phase 4 — Web (optional)
- [ ] 4.1 WebAudio `AudioWorklet` PCM tap → `feedPcm` via `ktor-client-js`.

### Phase 5 — Docs / cleanup
- [ ] 5.1 Update README: captions are cloud-streamed for live (audio leaves device);
  correct the "on-device / no network round-trip" claim.
- [ ] 5.2 Short ADR capturing the on-device→cloud decision (the portfolio story).

## Handoff notes (append as you go)
- **New files (commonMain `transcription/`):** `TranscriptionProvider`, `TranscriptionSettings`,
  `TranscriptionSecrets`, `StreamingTranscriber` (+`TranscriberStatus`), `CaptionAccumulator`,
  `DeepgramClient`, `SonioxClient`. `CaptionCue` moved to `commonMain` root package.
- **New files (androidMain):** `CaptionAudioRouter` (+ `PcmTapSink` interface).
- **Changed (androidMain):** `TranscriptionRenderersFactory`, `PlaybackService`,
  `CaptionController` (full rewrite), `Platform.android.kt` (toggle + provider button).
- **Changed (build):** `gradle/libs.versions.toml`, `composeApp/build.gradle.kts` (serialization
  plugin + ws/json deps), `androidApp/build.gradle.kts` (secrets→BuildConfig), `MainActivity.kt`.
- **Gotchas learned:**
  - `.kts`: `java.util.X` collides with Gradle's `java` accessor → `import java.util.Properties`.
  - `synchronized` is JVM-only — don't use in commonMain (`MutableStateFlow.update` instead).
  - `HttpClient { install(WebSockets) }` with NO engine arg resolves the per-platform Ktor engine.
- **Known limitations to address:** provider selection not persisted (in-memory only);
  switching providers creates a new client without closing the old `HttpClient` (minor leak,
  rare); cloud cues use `startMs/endMs = 0` (no content-position stamping — fine for live).
- **Phase 3 (iOS) starting point:** look at `composeApp/src/iosMain` for the AVPlayer path;
  need an `MTAudioProcessingTap` on the AVPlayerItem's `audioMix` → resample to 16k mono s16
  → `StreamingTranscriber.feedPcm`. Reuse the shared clients + `CaptionAudioRouter`-equivalent
  logic (consider hoisting the router's provider-mirroring into a shared `commonMain` class so
  iOS/web reuse it — only the PCM tap stays platform-specific). Keys via xcconfig/Info.plist →
  `TranscriptionSecrets`. Caption overlay: move `CaptionOverlay` to commonMain (step 1.9).

## VOD path (separate, not in this doc)
Recorded events should get **batch** transcription (once per asset) → WebVTT served as
an HLS subtitle rendition; Media3's built-in text renderer (already enabled in
`TranscriptionRenderersFactory`) renders it with ~zero client work. Use Soniox async
($0.10/hr) or self-hosted whisper large-v3. Track separately.
