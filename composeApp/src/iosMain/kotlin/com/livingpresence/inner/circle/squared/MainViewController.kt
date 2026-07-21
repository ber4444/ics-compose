package com.livingpresence.inner.circle.squared

import androidx.compose.ui.window.ComposeUIViewController
import com.livingpresence.inner.circle.squared.transcription.TranscriptionSecrets
import platform.Foundation.NSBundle

/**
 * iOS app entry point. The host Xcode project (SwiftUI `App`) calls
 * [MainViewControllerKt].mainViewController to obtain the `UIViewController`
 * hosting the shared [App] composable.
 *
 * Mirrors how the wasmJs target's `Main.kt` mounts `App()` into the DOM; here
 * the framework is embedded in an `iosApp` Xcode project (not in this repo).
 */
fun mainViewController() = ComposeUIViewController {
    val info = NSBundle.mainBundle.infoDictionary
    TranscriptionSecrets.deepgramApiKey = info?.get("DEEPGRAM_API_KEY") as? String ?: ""
    TranscriptionSecrets.sonioxApiKey = info?.get("SONIOX_API_KEY") as? String ?: ""
    App()
}
