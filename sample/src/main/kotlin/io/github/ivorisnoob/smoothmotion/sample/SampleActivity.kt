package io.github.ivorisnoob.smoothmotion.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.ivorisnoob.smoothmotion.media3.FrameInterpolationStatus
import io.github.ivorisnoob.smoothmotion.media3.SmoothMotion
import io.github.ivorisnoob.smoothmotion.media3.describe
import io.github.ivorisnoob.smoothmotion.ui.bindSmoothMotion
import io.github.ivorisnoob.smoothmotion.ui.unbindSmoothMotion

/**
 * The whole integration, in the order an app does it. Pick a video on the
 * device, or open one from adb:
 *
 * ```
 * adb shell am start -a android.intent.action.VIEW -t video/mp4 \
 *     -d https://example.com/clip.mp4 io.github.ivorisnoob.smoothmotion.sample
 * ```
 *
 * The status line shows what interpolation is doing ("24 → 120 fps"), and
 * `adb logcat -s SmoothMotion` prints the engine's counters every ten seconds.
 */
@OptIn(UnstableApi::class)
class SampleActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var smoothMotion: SmoothMotion
    private lateinit var playerView: PlayerView
    private lateinit var statusLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Build the player, then install Smooth motion before the first prepare().
        player = ExoPlayer.Builder(this).build()
        smoothMotion = SmoothMotion.install(player, this)

        // 2. Bind the view after setting its player, so it keeps the video's shape.
        playerView = PlayerView(this)
        playerView.player = player
        playerView.bindSmoothMotion(smoothMotion)

        // 3. Show the status where your user can see it.
        statusLine = TextView(this).apply { setTextColor(Color.WHITE) }
        smoothMotion.addListener(object : SmoothMotion.Listener {
            override fun onStatusChanged(status: FrameInterpolationStatus) {
                statusLine.text = "Smooth motion: ${status.describe()}"
            }
        })

        setContentView(layout())

        // 4. Play as usual.
        intent?.data?.let(::play)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(::play)
    }

    @Deprecated("Activity result API kept simple for the sample")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK) data?.data?.let(::play)
    }

    override fun onDestroy() {
        // 5. Release in reverse: the view, Smooth motion, then the player.
        playerView.unbindSmoothMotion()
        playerView.player = null
        smoothMotion.release()
        player.release()
        super.onDestroy()
    }

    private fun play(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.play()
    }

    private fun layout(): LinearLayout {
        val toggle = Switch(this).apply {
            text = "Smooth motion"
            setTextColor(Color.WHITE)
            isChecked = smoothMotion.enabled
            isEnabled = smoothMotion.isSupported
            setOnCheckedChangeListener { _, on -> smoothMotion.enabled = on }
        }
        val pick = Button(this).apply {
            text = "Pick a video"
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"),
                    PICK_VIDEO
                )
            }
        }
        val diagnose = Button(this).apply {
            text = "Diagnose"
            setOnClickListener { statusLine.text = SmoothMotion.diagnose(this@SampleActivity) }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(toggle)
            addView(pick)
            addView(diagnose)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 48, 24, 24)
            addView(playerView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(statusLine, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private companion object {
        const val PICK_VIDEO = 1
    }
}
