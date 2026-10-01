package io.github.ivorisnoob.smoothmotion.media3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.ColorInfo
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import io.github.ivorisnoob.smoothmotion.core.FrameInterpolationPolicy
import io.github.ivorisnoob.smoothmotion.core.FrameInterpolationSupport
import io.github.ivorisnoob.smoothmotion.core.SmoothMotionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Video frame interpolation for one [ExoPlayer], in one call.
 *
 * ```kotlin
 * val player = ExoPlayer.Builder(context).build()
 * val smoothMotion = SmoothMotion.install(player, context)   // before prepare()
 * playerView.player = player
 * playerView.bindSmoothMotion(smoothMotion)                  // smoothmotion-ui
 * player.setMediaItem(MediaItem.fromUri(uri))
 * player.prepare()
 * // ...
 * smoothMotion.release()                                     // before player.release()
 * ```
 *
 * What this does for you, so you do not have to know it:
 * - installs the GPU effect ([FrameInterpolationEffect]) only where it can
 *   run (OpenGL ES 3.2, [isSupported]); elsewhere the player is left exactly
 *   as it was and [status] reads [FrameInterpolationStatus.Unsupported];
 * - refuses a player that has already been prepared, because Media3 fixes its
 *   video pipeline on the first prepare (docs/TRAPS.md, "Install before the
 *   first prepare");
 * - polls the player twice a second on its own thread and feeds the
 *   governor: playback speed, live, HDR, dropped frames, the screen's refresh
 *   rate, thermal state and battery saver, pausing interpolation whenever any
 *   of them argues against it;
 * - works out the video's shape ([videoAspectRatio]), which the player stops
 *   reporting once the effect is installed;
 * - redraws a paused frame after the surface changes size, which the effect
 *   graph otherwise leaves drawn at the old size in a corner.
 *
 * **Threading.** Call everything on the player's application thread (the main
 * thread unless you built the player with another looper).
 *
 * **Lifetime.** One [SmoothMotion] per player. Call [release] before the
 * player's own `release()`. The effect stays in the player's pipeline after
 * [release] (Media3 cannot remove it) but passes frames through.
 */
@UnstableApi
public class SmoothMotion private constructor(
    context: Context,
    /** The configuration this instance was created with; [enabled] and [maxFps] can change later. */
    public val config: SmoothMotionConfig,
) {
    /**
     * Status and shape changes, delivered on the player's application thread.
     * Both methods have empty defaults; override what you need.
     */
    public interface Listener {
        public fun onStatusChanged(status: FrameInterpolationStatus) {}

        /** The displayed video's width over height, or null before the first frame is known. */
        public fun onVideoAspectRatioChanged(aspectRatio: Float?) {}
    }

    private val appContext = context.applicationContext
    private val governor = FrameInterpolationGovernor(appContext)

    /** Whether this device's GPU runs the engine (OpenGL ES 3.2). Fixed for the device. */
    public val isSupported: Boolean = FrameInterpolationSupport.isSupported(appContext)

    /**
     * The Media3 effect. [install] puts it in the player for you; use it
     * directly only with [create] and [attach], when you build the effect
     * list yourself. Put it last, after any effects of your own.
     */
    public val effect: Effect = FrameInterpolationEffect(governor.control)

    private val _status = MutableStateFlow<FrameInterpolationStatus>(
        if (isSupported) FrameInterpolationStatus.Measuring else FrameInterpolationStatus.Unsupported
    )

    /** What interpolation is doing right now; see [FrameInterpolationStatus] and [describe]. */
    public val status: StateFlow<FrameInterpolationStatus> = _status.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow<Float?>(null)

    /**
     * The video's displayed width over height, null until known. Use it to
     * size your video view: with the effect installed `Player.videoSize`
     * stays 0x0 (docs/TRAPS.md, "The player reports no video size").
     */
    public val videoAspectRatio: StateFlow<Float?> = _videoAspectRatio.asStateFlow()

    /**
     * The user's switch. Off passes frames through within one poll, without
     * rebuilding the player; turning it back on gives the current video a
     * fresh chance even if it had been judged too heavy.
     */
    public var enabled: Boolean = config.enabled
        set(value) {
            field = value
            pollNow()
        }

    /** The output cap, [FrameInterpolationPolicy.MIN_TARGET_FPS] (60) to [FrameInterpolationPolicy.MAX_TARGET_FPS] (120). */
    public var maxFps: Int = SmoothMotionConfig.checkedMaxFps(config.maxFps)
        set(value) {
            field = SmoothMotionConfig.checkedMaxFps(value)
            pollNow()
        }

    private var player: ExoPlayer? = null
    private var handler: Handler? = null
    private var effectInstalled = false
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val poll = object : Runnable {
        override fun run() {
            tick()
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val redraw = Runnable { redrawPausedFrame() }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = pollNow()
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) = pollNow()
        override fun onTracksChanged(tracks: Tracks) = pollNow()
        override fun onVideoSizeChanged(videoSize: VideoSize) = pollNow()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publishAspectRatio(null)
            pollNow()
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || !effectInstalled || !config.redrawPausedFrameOnSurfaceChange) return
            // A hand-off reports sizes in a burst; draw once it settles.
            handler?.removeCallbacks(redraw)
            handler?.postDelayed(redraw, REDRAW_SETTLE_MS)
        }
    }

    /**
     * Starts following [player], whose effect list must already contain
     * [effect] (or not, if [isSupported] is false). [install] calls this for
     * you. Returns this, for chaining.
     */
    public fun attach(player: ExoPlayer): SmoothMotion {
        checkThread(player)
        check(this.player == null) {
            "SmoothMotion is already attached to a player. Create one SmoothMotion per player. $DOCS#one-instance-per-player"
        }
        this.player = player
        effectInstalled = isSupported
        handler = Handler(player.applicationLooper)
        player.addListener(playerListener)
        handler?.post(poll)
        return this
    }

    /**
     * Stops following the player: no more polls, listeners or redraws, and
     * frames pass through. Call before the player's own `release()`. Safe to
     * call twice.
     */
    public fun release() {
        val p = player ?: return
        p.removeListener(playerListener)
        handler?.removeCallbacksAndMessages(null)
        handler = null
        governor.reset()
        player = null
        listeners.clear()
    }

    public fun addListener(listener: Listener) {
        listeners += listener
        listener.onStatusChanged(_status.value)
        listener.onVideoAspectRatioChanged(_videoAspectRatio.value)
    }

    public fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Counters from the GL thread, for a debug overlay. Read-only. */
    public val control: FrameInterpolationControl get() = governor.control

    // ------------------------------------------------------------------ poll

    private fun pollNow() {
        val h = handler ?: return
        if (Looper.myLooper() == h.looper) tick() else h.post { tick() }
    }

    private fun tick() {
        val p = player ?: return
        updateAspectRatio(p)
        if (!isSupported) {
            publishStatus(FrameInterpolationStatus.Unsupported)
            return
        }
        val format = p.videoFormat
        val item = p.currentMediaItem
        governor.update(
            enabledByUser = enabled,
            userMaxFps = maxFps,
            pipelineInstalled = effectInstalled,
            currentVideoId = "${p.currentMediaItemIndex}|${item?.mediaId}|${item?.localConfiguration?.uri}",
            qualityKey = format?.let { "${it.id}|${it.width}x${it.height}|${it.frameRate}|${it.bitrate}" },
            isLive = p.isCurrentMediaItemLive,
            isHdr = ColorInfo.isTransferHdr(format?.colorInfo),
            speed = p.playbackParameters.speed,
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition,
            droppedFrames = p.videoDecoderCounters?.let { counters ->
                counters.ensureUpdated()
                counters.droppedBufferCount
            },
        )
        publishStatus(governor.status.value)
    }

    private fun publishStatus(next: FrameInterpolationStatus) {
        if (next == _status.value) return
        _status.value = next
        listeners.forEach { it.onStatusChanged(next) }
    }

    private fun updateAspectRatio(p: ExoPlayer) {
        val size = p.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            size.width * size.pixelWidthHeightRatio / size.height
        } else {
            p.videoFormat?.let { f ->
                if (f.width <= 0 || f.height <= 0) return@let null
                val r = f.width * f.pixelWidthHeightRatio / f.height
                // The effect pipeline applies the container's rotation.
                if (f.rotationDegrees == 90 || f.rotationDegrees == 270) 1f / r else r
            }
        }
        if (ratio != null && ratio.isFinite() && ratio > 0f) publishAspectRatio(ratio)
    }

    private fun publishAspectRatio(ratio: Float?) {
        if (ratio == _videoAspectRatio.value) return
        _videoAspectRatio.value = ratio
        listeners.forEach { it.onVideoAspectRatioChanged(ratio) }
    }

    /**
     * The effect graph draws a paused frame once, when a surface arrives and
     * before its size does, so after a hand-off (a mini player to full screen,
     * a rotation) the paused picture sits small in the bottom-left corner.
     * Media3's `VideoFrameProcessor.REDRAW` needs a frame cache only the
     * composition player builds, so an exact seek to where playback already is
     * draws the frame afresh. Playing needs nothing: the next frame is drawn
     * at the new size.
     */
    private fun redrawPausedFrame() {
        val p = player ?: return
        if (p.isPlaying || p.playbackState != Player.STATE_READY || p.isCurrentMediaItemLive) return
        val previous = p.seekParameters
        p.setSeekParameters(SeekParameters.EXACT)
        p.seekTo(p.currentPosition)
        // Commands reach the playback thread in order, so the seek runs EXACT.
        p.setSeekParameters(previous)
    }

    public companion object {
        /** The Media3 release this library was built and tested against. */
        public const val TESTED_MEDIA3_VERSION: String = "1.11.0"

        internal const val DOCS = "https://github.com/Ivorisnoob/smoothmotion-android/blob/main/docs/TRAPS.md"
        private const val POLL_INTERVAL_MS = 500L
        private const val REDRAW_SETTLE_MS = 150L

        /** Whether this device's GPU runs the engine. Check it before offering the feature. */
        @JvmStatic
        public fun isSupported(context: Context): Boolean = FrameInterpolationSupport.isSupported(context)

        /**
         * Installs interpolation in [player] and starts following it. Call it
         * right after building the player, **before the first `prepare()`**.
         *
         * [otherEffects] are your own video effects, run before interpolation;
         * this replaces the player's effect list, so pass them here rather
         * than calling `setVideoEffects` yourself.
         *
         * On a device without OpenGL ES 3.2 this changes nothing in the player
         * and returns an instance whose [status] is
         * [FrameInterpolationStatus.Unsupported].
         *
         * @throws IllegalStateException if the player is not idle, or if
         *   called off the player's application thread. The message says how
         *   to fix it.
         */
        @JvmStatic
        @JvmOverloads
        public fun install(
            player: ExoPlayer,
            context: Context,
            config: SmoothMotionConfig = SmoothMotionConfig(),
            otherEffects: List<Effect> = emptyList(),
        ): SmoothMotion {
            checkThread(player)
            check(player.playbackState == Player.STATE_IDLE) {
                "SmoothMotion.install() was called after player.prepare(). Media3 decides on the first " +
                    "prepare whether video goes through an effect pipeline, and never revisits it. Call " +
                    "install() right after ExoPlayer.Builder(...).build(), before prepare(). To turn the " +
                    "feature on or off later, keep it installed and set smoothMotion.enabled instead. " +
                    "$DOCS#install-before-the-first-prepare"
            }
            val instance = create(context, config)
            warnOnUntestedMedia3()
            if (instance.isSupported) {
                player.setVideoEffects(otherEffects + instance.effect)
                SmoothMotionLog.i(SmoothMotionLog.TAG, "Installed; up to ${instance.maxFps} fps, enabled=${instance.enabled}")
            } else {
                if (otherEffects.isNotEmpty()) player.setVideoEffects(otherEffects)
                SmoothMotionLog.i(
                    SmoothMotionLog.TAG,
                    "Not installed: this GPU does not offer OpenGL ES 3.2. Video plays normally."
                )
            }
            return instance.attach(player)
        }

        /**
         * An instance not yet attached, for apps that build the effect list
         * themselves: `player.setVideoEffects(myEffects + sm.effect)` (only
         * when [isSupported]) before the first prepare, then [attach].
         */
        @JvmStatic
        @JvmOverloads
        public fun create(context: Context, config: SmoothMotionConfig = SmoothMotionConfig()): SmoothMotion =
            SmoothMotion(context, config)

        /**
         * A plain-text report of what this device offers, for a bug report or
         * a support screen: GPU support, the display's refresh rates, thermal
         * state, battery saver and the Media3 version.
         */
        @SuppressLint("InlinedApi") // THERMAL_STATUS_MODERATE is a plain int; the read is version-guarded.
        @JvmStatic
        public fun diagnose(context: Context): String {
            val app = context.applicationContext
            val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val power = app.getSystemService(PowerManager::class.java)
            val glEs = app.getSystemService(android.app.ActivityManager::class.java)
                ?.deviceConfigurationInfo?.glEsVersion
            val modes = display?.supportedModes
                ?.map { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" }
                ?.distinct()
                ?.joinToString()
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus else null
            return buildString {
                appendLine("smoothmotion-android diagnosis")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                appendLine("OpenGL ES declared: $glEs (needs 3.2)")
                appendLine("supported: ${isSupported(app)}")
                appendLine("display now: ${display?.refreshRate?.let { "%.1f".format(it) }} Hz")
                appendLine("display modes: $modes")
                appendLine("thermal status: ${thermal ?: "unknown below API 29"} (pauses at ${PowerManager.THERMAL_STATUS_MODERATE}+)")
                appendLine("battery saver: ${power?.isPowerSaveMode}")
                appendLine("media3: ${MediaLibraryInfo.VERSION} (tested: $TESTED_MEDIA3_VERSION)")
            }
        }

        private fun checkThread(player: ExoPlayer) {
            check(Looper.myLooper() == player.applicationLooper) {
                "SmoothMotion must be called on the player's application thread (usually the main " +
                    "thread). $DOCS#threading"
            }
        }

        private var warnedMedia3 = false

        private fun warnOnUntestedMedia3() {
            if (warnedMedia3 || MediaLibraryInfo.VERSION == TESTED_MEDIA3_VERSION) return
            warnedMedia3 = true
            SmoothMotionLog.w(
                SmoothMotionLog.TAG,
                "Media3 ${MediaLibraryInfo.VERSION} is not the tested $TESTED_MEDIA3_VERSION. The effect " +
                    "relies on Media3 internals; check video plays and the status reaches Active. " +
                    "$DOCS#media3-version"
            )
        }
    }
}

/**
 * How [SmoothMotion] starts. Everything here has a sensible default; most
 * apps pass nothing.
 */
public data class SmoothMotionConfig @JvmOverloads constructor(
    /** Interpolate from the start. Change it later with [SmoothMotion.enabled]. */
    val enabled: Boolean = true,
    /**
     * The output cap: 60 for "Up to 60", 120 for "Up to 120". The output is
     * the lower of this and the display's fastest mode, so 120 on a 60 Hz
     * phone gives 60. Values outside 60..120 throw.
     */
    val maxFps: Int = FrameInterpolationPolicy.MAX_TARGET_FPS,
    /** Redraw a paused frame after the video surface changes size. Leave on unless you redraw yourself. */
    val redrawPausedFrameOnSurfaceChange: Boolean = true,
) {
    init {
        checkedMaxFps(maxFps)
    }

    internal companion object {
        fun checkedMaxFps(value: Int): Int {
            require(value in FrameInterpolationPolicy.MIN_TARGET_FPS..FrameInterpolationPolicy.MAX_TARGET_FPS) {
                "maxFps must be between ${FrameInterpolationPolicy.MIN_TARGET_FPS} and " +
                    "${FrameInterpolationPolicy.MAX_TARGET_FPS}, was $value"
            }
            return value
        }
    }
}

/**
 * A short English line for a settings row or a debug overlay, such as
 * "24 → 120 fps" or "Paused: battery saver". Write your own from the status's
 * fields to translate it.
 */
public fun FrameInterpolationStatus.describe(): String = when (this) {
    FrameInterpolationStatus.Disabled -> "Off"
    FrameInterpolationStatus.NeedsRestart -> "On after the player is rebuilt"
    FrameInterpolationStatus.Measuring -> "Measuring the video"
    is FrameInterpolationStatus.Active -> buildString {
        append("$sourceFps → $outputFps fps")
        when (limit) {
            FrameInterpolationStatus.RateLimit.RESOLUTION -> append(" (held below $targetFps by the frame size)")
            FrameInterpolationStatus.RateLimit.SCREEN -> append(" (held below $targetFps by the screen)")
            FrameInterpolationStatus.RateLimit.NONE -> Unit
        }
    }
    is FrameInterpolationStatus.NotNeeded -> "Not needed: already $playingFps fps"
    FrameInterpolationStatus.Live -> "Paused: live stream"
    FrameInterpolationStatus.Hdr -> "Paused: HDR video"
    FrameInterpolationStatus.Hot -> "Paused: the phone is hot"
    FrameInterpolationStatus.BatterySaver -> "Paused: battery saver"
    FrameInterpolationStatus.CannotKeepUp -> "Paused: this phone could not keep up with this video"
    FrameInterpolationStatus.Unsupported -> "Not supported on this GPU"
}
