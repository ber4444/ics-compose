package np.com.sarbagyastha.youtube_player_flutter_example

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugins.GeneratedPluginRegistrant
import java.util.*
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        GeneratedPluginRegistrant.registerWith(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "openFullVideo" -> openMxplayer(call.argument("url"), call.argument("audioOnly"))
                else -> result.notImplemented()
            }
        }
    }

    private fun openMxplayer(url: String?, audioOnly: Boolean?) {
        if (url==null) return

        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage("com.mxtech.videoplayer.pro")

        intent.setDataAndTypeAndNormalize(uri, "video/*")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("decode_mode", 2.toByte())
        intent.putExtra("orientation", 0)
        if (audioOnly == true) intent.putExtra("video", false)
        intent.putExtra("sticky", true)
        intent.putExtra("secure_uri", true)
        intent.putExtra("video_zoom", 2)
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            this.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                val packageName = "com.mxtech.videoplayer.ad"
                intent.setPackage(packageName)
                this.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=MX+player")))
            }
        }
    }

    companion object {
        private const val CHANNEL = "samples.flutter.dev/gabor"
    }
}