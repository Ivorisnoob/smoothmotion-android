package io.github.ivorisnoob.smoothmotion.ui

import android.view.SurfaceView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.ivorisnoob.smoothmotion.media3.SmoothMotion
import kotlin.math.roundToInt

/**
 * Keeps this view's picture at [smoothMotion]'s video shape, which the player
 * stops reporting once the effect is installed. Call it **after**
 * `playerView.player = player` (setting a player resets the frame), and call
 * [unbindSmoothMotion] when the view goes away.
 *
 * ```kotlin
 * playerView.player = player
 * playerView.bindSmoothMotion(smoothMotion)
 * ```
 */
@UnstableApi
public fun PlayerView.bindSmoothMotion(smoothMotion: SmoothMotion) {
    unbindSmoothMotion()
    val binding = SmoothMotionBinding(this, smoothMotion)
    setTag(R.id.smoothmotion_binding, binding)
    smoothMotion.addListener(binding)
}

/** Undoes [bindSmoothMotion]. Safe to call when nothing is bound. */
@UnstableApi
public fun PlayerView.unbindSmoothMotion() {
    (getTag(R.id.smoothmotion_binding) as? SmoothMotionBinding)?.let {
        it.smoothMotion.removeListener(it)
    }
    setTag(R.id.smoothmotion_binding, null)
    releaseKnownAspectRatio()
}

@UnstableApi
private class SmoothMotionBinding(
    private val view: PlayerView,
    val smoothMotion: SmoothMotion,
) : SmoothMotion.Listener {
    override fun onVideoAspectRatioChanged(aspectRatio: Float?) {
        view.keepKnownAspectRatio(aspectRatio)
    }
}

/**
 * Gives this view's content frame the video's shape, [aspectRatio], whenever
 * the player itself reports no video size, and holds a SurfaceView's buffer
 * at the video's fitted size. [bindSmoothMotion] calls this for you; use it
 * directly when you know the shape from elsewhere (a stream manifest, a file's
 * metadata).
 *
 * **Why.** With the effect installed the player never reports a size. The
 * renderer hands the graph's size to its video-sink listener, and in Media3
 * 1.11.0 that listener's `onVideoSizeChanged` is an empty method
 * (`MediaCodecVideoRenderer$1`) [verified September 2026 against the 1.11.0
 * bytecode]. `Player.videoSize` stays 0x0, so `PlayerView` gives its
 * [AspectRatioFrameLayout] no ratio, the frame fills the whole view and the
 * graph's last stage letterboxes the picture inside the surface. Fit still
 * looks right; zoom (`RESIZE_MODE_ZOOM`) changes nothing.
 *
 * **The buffer.** The graph draws each frame at the output surface's size, so
 * a surface that resizes - which is all a zoom is - makes it re-configure, and
 * frames drawn meanwhile come out at the old size, anchored bottom-left (GL's
 * origin): a flicker on every zoom. Fixed at the fitted size, the buffer does
 * not change on a zoom; the compositor scales it instead.
 *
 * A size the player does report always wins. Call after binding the player;
 * pair with [releaseKnownAspectRatio], or the listener keeps the view
 * reachable from the player.
 */
@UnstableApi
public fun PlayerView.keepKnownAspectRatio(aspectRatio: Float?) {
    val keeper = getTag(R.id.smoothmotion_known_aspect_ratio) as? KnownAspectRatio
        ?: KnownAspectRatio(this).also { setTag(R.id.smoothmotion_known_aspect_ratio, it) }
    keeper.ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f }
    keeper.follow(player)
    keeper.apply()
    keeper.fixSurfaceBuffer()
}

/** Stop keeping the shape; the listener leaves the player. */
@UnstableApi
public fun PlayerView.releaseKnownAspectRatio() {
    (getTag(R.id.smoothmotion_known_aspect_ratio) as? KnownAspectRatio)?.detach()
    setTag(R.id.smoothmotion_known_aspect_ratio, null)
}

@UnstableApi
private class KnownAspectRatio(private val view: PlayerView) : Player.Listener {
    var ratio: Float? = null
    private var player: Player? = null
    private var fixedBuffer: Pair<Int, Int>? = null

    // The fitted size follows the view (rotation, a side panel), not the
    // content frame, which is the part a zoom resizes.
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) fixSurfaceBuffer()
    }

    init {
        view.addOnLayoutChangeListener(layoutListener)
    }

    fun follow(next: Player?) {
        if (player === next) return
        player?.removeListener(this)
        next?.addListener(this)
        player = next
    }

    fun detach() {
        follow(null)
        view.removeOnLayoutChangeListener(layoutListener)
    }

    fun apply() {
        val reported = view.player?.videoSize
        if (reported != null && reported.width > 0 && reported.height > 0) return
        val known = ratio ?: return
        view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.setAspectRatio(known)
    }

    fun fixSurfaceBuffer() {
        val surface = view.videoSurfaceView as? SurfaceView ?: return
        val known = ratio
        val width = view.width
        val height = view.height
        if (known == null || width <= 0 || height <= 0) {
            if (fixedBuffer != null) {
                fixedBuffer = null
                surface.holder.setSizeFromLayout()
            }
            return
        }
        val fitted = if (width.toFloat() / height > known) {
            (height * known).roundToInt() to height
        } else {
            width to (width / known).roundToInt()
        }
        if (fitted == fixedBuffer) return
        fixedBuffer = fitted
        surface.holder.setFixedSize(fitted.first.coerceAtLeast(1), fitted.second.coerceAtLeast(1))
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        // PlayerView's own listener resets the frame for this same event, and
        // the order the two run in is not ours to choose - so after it.
        view.post { apply() }
    }
}
