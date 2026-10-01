package io.github.ivorisnoob.smoothmotion.media3

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import io.github.ivorisnoob.smoothmotion.core.ComputeMotionEngine
import io.github.ivorisnoob.smoothmotion.core.FrameDropWatch
import io.github.ivorisnoob.smoothmotion.core.FrameInterpolationPolicy
import io.github.ivorisnoob.smoothmotion.core.MotionEngine
import io.github.ivorisnoob.smoothmotion.core.ScreenRateWatch
import io.github.ivorisnoob.smoothmotion.core.SmoothMotionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Video frame interpolation (Smooth motion): a GPU effect in Media3's
 * video pipeline that puts frames on a steady output clock at the screen's
 * rate (60, 90 or 120 fps) and draws each one that falls between two decoded
 * frames by motion compensation, so a 24-60 fps video reaches the screen at
 * up to 120 fps.
 *
 * **The pipeline is fixed per player.** [verified September 2026 against the
 * Media3 1.11.0 bytecode] `MediaCodecVideoRenderer.onEnabled` decides once, on
 * the renderer's first enable, whether it renders through the effect graph
 * (`hasSetVideoSink`), so `setVideoEffects` has to be called before the first
 * prepare and cannot add the graph to a player that started without it. That
 * is why [SmoothMotion.install] refuses a player that is not idle, why an
 * app that lets the user turn the feature on mid-session rebuilds its player
 * next time it closes, and why every per-video decision is a field the
 * effect reads per frame ([FrameInterpolationControl]) rather than a change
 * to the effect list.
 *
 * **Any number of output frames per input is legal in playback, and the
 * screen follows the output rate.** [verified September 2026, same bytecode]
 * `PlaybackVideoGraphWrapper.onOutputFrameAvailableForRendering` hands each
 * output timestamp to the sink on its own; `DefaultVideoSink` feeds those
 * output timestamps to its own `FixedFrameRateEstimator`, whose synced rate
 * is what `VideoFrameReleaseHelper` passes to `Surface.setFrameRate` and what
 * it paces vsync alignment by. So a steady 120 fps output asks the display
 * for 120 Hz by itself, and an irregular one (a real frame dropped in between
 * synthesized ones off the clock) would lose that sync - which is why the
 * output is a clock and not "midpoints plus the real frames".
 */
@UnstableApi
public class FrameInterpolationEffect @JvmOverloads constructor(
    private val control: FrameInterpolationControl,
    /** Builds the engine on Media3's GL thread; swap it to try another [MotionEngine]. */
    private val engineFactory: () -> MotionEngine = { ComputeMotionEngine() },
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        FrameInterpolationShaderProgram(control, useHdr, engineFactory)
}

/**
 * What the main thread and the GL thread tell each other, one writer per
 * field. A stale read costs one frame or one poll either way.
 */
public class FrameInterpolationControl {
    /** Main thread writes: interpolate now, or pass frames through. */
    @Volatile
    var active: Boolean = false

    /** Main thread writes: the output rate to aim for, in frames per second of real time. */
    @Volatile
    var targetFps: Int = FrameInterpolationPolicy.MIN_TARGET_FPS

    /** Main thread writes: the playback speed, so the output clock runs in real time. */
    @Volatile
    var speed: Float = 1f

    /** GL thread writes: the source's measured frame rate in media time, 0 until known. */
    @Volatile
    var sourceFps: Float = 0f

    /** GL thread writes: the real-time rate frames leave at while interpolating, 0 while passing through. */
    @Volatile
    var outputFps: Int = 0

    /** GL thread writes: [outputFps] is below [targetFps] because of the frame size. */
    @Volatile
    var limitedByResolution: Boolean = false

    /** GL thread writes: frames drawn between decoded ones so far, so the main thread can see it working. */
    @Volatile
    var synthesized: Long = 0L

    /** GL thread writes: every frame handed downstream, decoded or drawn. */
    @Volatile
    var emitted: Long = 0L

    /** GL thread writes: frame pairs whose motion was estimated. */
    @Volatile
    var pairs: Long = 0L

    /**
     * GL thread writes: the share of the frame, in percent, that found no good
     * match in the last sampled pair (about every two seconds), -1 until
     * sampled. High on a scene cut; persistently high means the video moves
     * in ways the search cannot follow.
     */
    @Volatile
    var unmatchedPercent: Int = -1

    /** GL thread writes: sampled pairs judged a scene cut. */
    @Volatile
    var sampledCuts: Long = 0L

    /** GL thread writes: the motion engine in use ([MotionEngine.name]), empty before one runs. */
    @Volatile
    var engine: String = ""

    /** GL thread writes: the interpolation shaders failed on this GPU; frames only pass through. */
    @Volatile
    var unsupported: Boolean = false
}

/** What Smooth motion is doing for the video on screen, for the player to show. */
public sealed interface FrameInterpolationStatus {
    /** The setting is off; nothing to show. */
    data object Disabled : FrameInterpolationStatus
    /** Enabled, but this player was built without the effect (it must be installed before the first prepare). */
    data object NeedsRestart : FrameInterpolationStatus
    /** No frames measured yet for this video. */
    data object Measuring : FrameInterpolationStatus
    data class Active(
        val sourceFps: Int,
        val outputFps: Int,
        /** Why [outputFps] is below the screen's rate, if it is. */
        val limit: RateLimit,
        /** The rate the user and the screen asked for. */
        val targetFps: Int,
    ) : FrameInterpolationStatus
    /** The source already plays at about the output rate, so frames pass through. */
    data class NotNeeded(val playingFps: Int) : FrameInterpolationStatus
    data object Live : FrameInterpolationStatus
    data object Hdr : FrameInterpolationStatus
    data object Hot : FrameInterpolationStatus
    data object BatterySaver : FrameInterpolationStatus
    /** Dropped too many frames while interpolating; off until the video or quality changes. */
    data object CannotKeepUp : FrameInterpolationStatus
    /** The interpolation shaders failed on this GPU. */
    data object Unsupported : FrameInterpolationStatus

    enum class RateLimit { NONE, RESOLUTION, SCREEN }
}

/**
 * Decides, from the main thread, whether the effect should be interpolating
 * right now and at what rate, and says what it is doing: the user's switch
 * and rate cap, the screen, the current video, the playback speed, the
 * phone's thermal state and battery saver, and whether this video at this
 * quality has already shown the phone cannot keep up.
 */
public class FrameInterpolationGovernor(context: Context) {

    public val control: FrameInterpolationControl = FrameInterpolationControl()

    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val dropWatch = FrameDropWatch()
    private val screenWatch = ScreenRateWatch()
    private var cannotKeepUp = false
    private var playbackKey: String? = null
    private var lastEmitted = 0L
    private var lastReportAtMs = 0L

    private val _status = MutableStateFlow<FrameInterpolationStatus>(FrameInterpolationStatus.Disabled)
    public val status: StateFlow<FrameInterpolationStatus> = _status.asStateFlow()

    /**
     * Called from the player's progress poll, about twice a second, on the
     * player's application thread. [SmoothMotion] does this for you.
     */
    @SuppressLint("InlinedApi") // THERMAL_STATUS_NONE is a plain int; the read is version-guarded.
    public fun update(
        enabledByUser: Boolean,
        userMaxFps: Int,
        pipelineInstalled: Boolean,
        currentVideoId: String?,
        qualityKey: String?,
        isLive: Boolean,
        isHdr: Boolean,
        speed: Float,
        isPlaying: Boolean,
        positionMs: Long,
        droppedFrames: Int?,
    ) {
        if (!enabledByUser || !pipelineInstalled) {
            setActive(false)
            // Switched off in the player: switching it back on is a deliberate
            // retry, so the video gets a fresh chance and a fresh grace period.
            if (!enabledByUser) playbackKey = null
            _status.value = if (!enabledByUser) {
                FrameInterpolationStatus.Disabled
            } else {
                FrameInterpolationStatus.NeedsRestart
            }
            return
        }

        val now = SystemClock.elapsedRealtime()
        // A new video, or a new quality of the same one, gets a fresh chance:
        // the stream that overloaded the GPU may have been 1080p, the next 480p.
        val key = "$currentVideoId|$qualityKey"
        if (key != playbackKey) {
            playbackKey = key
            cannotKeepUp = false
            dropWatch.restart(now)
            screenWatch.restart()
        }

        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        val screenTarget = FrameInterpolationPolicy.targetFps(userMaxFps, displayMaxHz(display))
        val target = screenWatch.limitFps.takeIf { it > 0 }?.let { minOf(it, screenTarget) } ?: screenTarget
        control.targetFps = target
        control.speed = speed

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        val powerSave = powerManager?.isPowerSaveMode == true
        val open = FrameInterpolationPolicy.isGateOpen(
            enabledByUser = true,
            isLive = isLive,
            isHdr = isHdr,
            thermalStatus = thermal,
            powerSave = powerSave,
            cannotKeepUp = cannotKeepUp,
        )
        setActive(open)

        val emitted = control.emitted
        val emitting = isPlaying && emitted > lastEmitted
        lastEmitted = emitted
        if (open && display != null) {
            screenWatch.onPoll(now, emitting, control.outputFps, display.refreshRate)
        }

        if (open && droppedFrames != null &&
            dropWatch.onPoll(
                nowMs = now,
                isPlaying = isPlaying,
                positionMs = positionMs,
                speed = speed,
                droppedFrames = droppedFrames,
                emittedFrames = emitted,
                synthesizedFrames = control.synthesized,
            )
        ) {
            SmoothMotionLog.w(TAG, "Dropping frames while interpolating; off for this video and quality")
            cannotKeepUp = true
            setActive(false)
        }

        // One line every ten seconds of playback: enough to read a phone's
        // behaviour off `adb logcat -s SmoothMotion` without flooding it.
        if (isPlaying && now - lastReportAtMs >= REPORT_EVERY_MS) {
            lastReportAtMs = now
            SmoothMotionLog.d(
                TAG,
                "source=${"%.2f".format(control.sourceFps)}fps out=${control.outputFps}fps " +
                    "target=$target screen=${display?.refreshRate?.roundToInt()}Hz " +
                    "drawn=${control.synthesized} emitted=$emitted dropped=$droppedFrames " +
                    "pairs=${control.pairs} unmatched=${control.unmatchedPercent}% " +
                    "sampledCuts=${control.sampledCuts} active=${control.active} engine=${control.engine}"
            )
        }

        val sourceFps = control.sourceFps
        val outputFps = control.outputFps
        _status.value = when {
            control.unsupported -> FrameInterpolationStatus.Unsupported
            cannotKeepUp -> FrameInterpolationStatus.CannotKeepUp
            isLive -> FrameInterpolationStatus.Live
            isHdr -> FrameInterpolationStatus.Hdr
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> FrameInterpolationStatus.Hot
            powerSave -> FrameInterpolationStatus.BatterySaver
            sourceFps <= 0f -> FrameInterpolationStatus.Measuring
            outputFps > 0 -> FrameInterpolationStatus.Active(
                sourceFps = sourceFps.roundToInt(),
                outputFps = outputFps,
                limit = when {
                    control.limitedByResolution -> FrameInterpolationStatus.RateLimit.RESOLUTION
                    target < screenTarget -> FrameInterpolationStatus.RateLimit.SCREEN
                    else -> FrameInterpolationStatus.RateLimit.NONE
                },
                targetFps = screenTarget,
            )
            sourceFps * speed * FrameInterpolationPolicy.PASS_THROUGH_MARGIN >= target ->
                FrameInterpolationStatus.NotNeeded((sourceFps * speed).roundToInt())
            else -> FrameInterpolationStatus.Measuring
        }
    }

    /** The player closed: whatever opens next, even the same video, starts fresh. */
    public fun reset() {
        playbackKey = null
        cannotKeepUp = false
        screenWatch.restart()
        setActive(false)
    }

    private fun setActive(active: Boolean) {
        if (active != control.active) {
            SmoothMotionLog.i(TAG, if (active) "Interpolating up to ${control.targetFps} fps" else "Passing frames through")
            control.active = active
        }
    }

    /**
     * The fastest refresh rate the display offers at its current resolution.
     * Not the current rate: a phone idles at 60 Hz until something asks for
     * more, and asking is exactly what a 120 fps output does.
     */
    private fun displayMaxHz(display: Display?): Float {
        display ?: return FrameInterpolationPolicy.MIN_TARGET_FPS.toFloat()
        val mode = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate }
            ?: display.refreshRate
    }

    private companion object {
        const val TAG = SmoothMotionLog.TAG
        const val REPORT_EVERY_MS = 10_000L
    }
}
