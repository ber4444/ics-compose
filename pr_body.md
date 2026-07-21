## High-Level Summary of Features

This PR implements extensive cross-platform parity improvements across Android, iOS, and Wasm targets, and fundamentally upgrades our on-device AI transcription engine to use Whisper C++.

### Cross-Platform Parity
- **Web Navigation:** Wasm target now sports a fully functional browser-based back navigation stack.
- **Unified Login UX:** A beautiful, responsive glassmorphism aesthetic is now applied uniformly across the login screens of all platforms (Android, iOS, Web).
- **iOS Thumbnail Engine:** iOS now extracts scrubbing preview thumbnails natively via `AVAssetImageGenerator` utilizing hardware acceleration.
- **iOS Offline HLS:** iOS now robustly downloads background HLS streams using native `AVAssetDownloadURLSession`, matching Android's capability.
- **Robust MediaKit Probing:** Redesigned `EventCatalog` to handle Master and Media playlist parallel probing sequentially and safely for all targets.

### AI Captions (Whisper Integration)
- **Engine Migration:** The live caption generation has been fully migrated from Vosk to **Whisper C++** natively integrated via Android NDK for blazing fast, on-device transcription!
- **Mobile-Optimized Models:** Uses the `ggml-base.en` Whisper model to guarantee fluid, real-time transcription latency on mobile CPUs (avoiding the silent hangs associated with massive models like large-v3-turbo).
- **Dynamic UX:** The Caption toggle button now provides real-time, percentage-based model download progress directly in the player UI!

