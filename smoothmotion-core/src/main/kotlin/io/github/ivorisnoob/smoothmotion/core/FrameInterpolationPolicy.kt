package io.github.ivorisnoob.smoothmotion.core

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Whether this device's GPU runs the engine: OpenGL ES 3.2 or newer, as the
 * system declares it (`reqGlEsVersion`), so it is known before any player or
 * GL context exists. Below that, do not offer the feature and do not install
 * the effect; there is no lesser engine to fall back to.
 */
public object FrameInterpolationSupport {
    /** The GLES version the engine needs, as major * 10 + minor ([MotionGl.glesVersion]). */
    const val MIN_GLES = 32

    private const val GLES_3_2 = 0x30002

    @JvmStatic
    public fun isSupported(context: Context): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        return activityManager.deviceConfigurationInfo.reqGlEsVersion >= GLES_3_2
    }
}

/** Pure decisions, kept apart from GL and Android so they can be unit tested. */
object FrameInterpolationPolicy {

    /**
     * Above this gap (below ~16.7 fps) the frames are too far apart to have
     * motion worth tracking, and a gap this large inside a normal video is
     * usually a discontinuity rather than a frame interval.
     */
    const val MAX_INTERVAL_US = 60_000L

    /**
     * A source within this factor of the output rate already looks smooth:
     * 60 fps on a 60 Hz screen passes through, 50 fps on it is lifted to 60.
     */
    const val PASS_THROUGH_MARGIN = 1.15

    /** The stored choices for the output cap. Frozen: they are persisted. */
    const val MIN_TARGET_FPS = 60
    const val MAX_TARGET_FPS = 120

    /**
     * Output pixels a second the full-size passes may cost: a little over
     * 1080p at 120 fps, so a decoder's 1088-line frame still qualifies.
     * Larger frames step down to 90, then 60, which is never withheld.
     */
    const val PIXEL_RATE_BUDGET = 2_200_000L * 120

    /**
     * The most outputs one decoded frame may produce. A slow playback speed
     * that would ask for more halves the output rate instead.
     */
    const val MAX_OUTPUTS_PER_INPUT = 8

    /**
     * The finest output step while the source is unmeasured: eight to the
     * widest gap still interpolated ([MAX_INTERVAL_US]).
     */
    const val MIN_STEP_US = MAX_INTERVAL_US.toDouble() / MAX_OUTPUTS_PER_INPUT

    /** A step within this fraction of a whole division of the source interval snaps to it. */
    const val SNAP_TOLERANCE = 0.015

    /**
     * How much faster than the screen's rate a snapped step may run: a
     * rounding hair, no more. [scar, September 2026] Snapping within
     * [SNAP_TOLERANCE] either way once put 122 fps on a 120 Hz screen, which
     * then discards about two frames a second without counting them as drops.
     */
    const val MAX_SNAP_SPEEDUP = 0.001

    /** Output textures may take this much memory before the lead shortens. */
    const val POOL_BUDGET_BYTES = 100L * 1024 * 1024

    /** Frames above this many pixels keep the smaller floor of four outputs. */
    const val LARGE_FRAME_PIXELS = 2_500_000L

    /**
     * The rate to aim for: the user's cap and the display's fastest mode,
     * whichever is lower, and never under 60.
     */
    fun targetFps(userMaxFps: Int, displayMaxHz: Float): Int =
        minOf(userMaxFps, displayMaxHz.roundToInt()).coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS)

    /**
     * [targetFps] for a [width] x [height] frame, stepping down to 90 and then
     * 60 when every output frame at that size would cost more than
     * [PIXEL_RATE_BUDGET]. 60 is never withheld: that is where the feature
     * started, including 4K.
     */
    fun affordableFps(targetFps: Int, width: Int, height: Int): Int {
        val pixels = width.toLong() * height
        for (fps in intArrayOf(targetFps, 90, MIN_TARGET_FPS)) {
            if (fps <= targetFps && pixels * fps <= PIXEL_RATE_BUDGET) return fps
        }
        return minOf(targetFps, MIN_TARGET_FPS)
    }

    /**
     * An output step, whether it [snapped] to a whole division of the source
     * interval, and the unsnapped step at this rate and speed ([paceStepUs]),
     * which a snapped cadence must not run faster than.
     */
    data class OutputStep(val stepUs: Double, val snapped: Boolean, val paceStepUs: Double)

    /** [outputStep]'s step alone. */
    fun outputStepUs(fps: Int, speed: Float, sourceIntervalUs: Double): Double =
        outputStep(fps, speed, sourceIntervalUs).stepUs

    /**
     * The media-time gap between output frames for [fps] of real time at
     * [speed]. When the source interval divides into whole steps within
     * [SNAP_TOLERANCE] (30 into 120, 24 into 120, 30 into 60), and doing so
     * runs no faster than the screen's rate ([MAX_SNAP_SPEEDUP]), the step
     * snaps to it so every Nth output is a decoded frame shown as it is,
     * rather than one drawn next to it.
     */
    fun outputStep(fps: Int, speed: Float, sourceIntervalUs: Double): OutputStep {
        val pace = speed.coerceIn(0.1f, 8f).toDouble()
        val minStep = if (sourceIntervalUs > 0.0) {
            sourceIntervalUs / MAX_OUTPUTS_PER_INPUT * (1 - SNAP_TOLERANCE)
        } else {
            MIN_STEP_US
        }
        var rate = fps.coerceAtLeast(1)
        var step = pace * 1_000_000.0 / rate
        while (step < minStep && rate > 30) {
            rate /= 2
            step = pace * 1_000_000.0 / rate
        }
        if (sourceIntervalUs > 0.0) {
            val divisions = (sourceIntervalUs / step).roundToInt()
            if (divisions >= 2) {
                val snapped = sourceIntervalUs / divisions
                if (abs(snapped - step) / step <= SNAP_TOLERANCE &&
                    snapped >= step * (1 - MAX_SNAP_SPEEDUP)
                ) {
                    return OutputStep(snapped, snapped = true, paceStepUs = step)
                }
            }
        }
        return OutputStep(step, snapped = false, paceStepUs = step)
    }

    /**
     * The step for one pair [pairUs] apart on a snapped cadence: the pair cut
     * into whole steps near [stepUs], so its last tick is its decoded frame
     * exactly, with fewer, longer steps rather than any shorter than
     * [paceStepUs] allows (a long pair in a variable-rate video).
     */
    fun lockedStepUs(pairUs: Long, stepUs: Double, paceStepUs: Double): Double {
        var divisions = (pairUs / stepUs).roundToInt().coerceAtLeast(1)
        while (divisions > 1 && pairUs.toDouble() / divisions < paceStepUs * (1 - MAX_SNAP_SPEEDUP)) {
            divisions--
        }
        return pairUs.toDouble() / divisions
    }

    /** Whether a pair [intervalUs] apart is worth drawing frames between at [stepUs]. */
    fun shouldInterpolate(intervalUs: Long, stepUs: Double): Boolean =
        intervalUs in 1..MAX_INTERVAL_US && intervalUs > stepUs * PASS_THROUGH_MARGIN

    /** Output frames one input can produce at [stepUs]: its drawn frames plus itself. */
    fun outputsPerInput(intervalUs: Double, stepUs: Double): Int =
        if (intervalUs <= 0.0 || stepUs <= 0.0) 1 else ceil(intervalUs / stepUs - 1e-6).toInt().coerceAtLeast(1)

    /**
     * How many output textures to keep for [outputsPerInput] a frame. The
     * final stage holds each output until its display time, so this bounds
     * how far ahead of the screen the pipeline runs: three inputs' worth,
     * within [POOL_BUDGET_BYTES], and never so few that one input cannot fit
     * with room to spare.
     */
    fun poolCapacity(outputsPerInput: Int, width: Int, height: Int, bytesPerPixel: Int): Int {
        val pixels = width.toLong() * height
        val floor = if (pixels > LARGE_FRAME_PIXELS) 4 else 6
        val frameBytes = (pixels * bytesPerPixel).coerceAtLeast(1L)
        val affordable = (POOL_BUDGET_BYTES / frameBytes).toInt()
        return maxOf(floor, outputsPerInput + 2, minOf(outputsPerInput * 3, affordable))
    }

    /**
     * Whether this moment of playback should be interpolated at all.
     * [thermalStatus] is a `PowerManager.THERMAL_STATUS_*` value; moderate and
     * above means the system is already throttling.
     */
    // The THERMAL_STATUS_* values are plain ints; only reading the status needs API 29.
    @SuppressLint("InlinedApi")
    fun isGateOpen(
        enabledByUser: Boolean,
        isLive: Boolean,
        isHdr: Boolean,
        thermalStatus: Int,
        powerSave: Boolean,
        cannotKeepUp: Boolean
    ): Boolean = enabledByUser &&
        !isLive &&
        !isHdr &&
        thermalStatus < PowerManager.THERMAL_STATUS_MODERATE &&
        !powerSave &&
        !cannotKeepUp
}

/**
 * The source's frame interval in media time, measured from decoded
 * timestamps.
 *
 * [scar, September 2026] A plain running average took in every gap a
 * dropped frame leaves, and the gap is exactly the moment the clock
 * restarts, so it restarted on a step the video did not have and kept it: in
 * a Mali-G615 bug report a 23.98 fps video went from 24 untouched decoded
 * frames a second at 120 fps to about 5, and later ran at 122 fps on a
 * 120 Hz screen. An interval far from the estimate is left out now, unless
 * [RATE_CHANGE_FRAMES] arrive in a row, which is the video changing rate.
 */
class SourceRateMeter {
    /** The smoothed interval, 0 until two frames have been seen. */
    var intervalUs: Double = 0.0
        private set

    private var lastUs = NONE
    private var outliers = 0

    fun onFrame(presentationTimeUs: Long) {
        val last = lastUs
        lastUs = presentationTimeUs
        if (last == NONE) return
        val interval = presentationTimeUs - last
        // Longer is a pause or a cut, not a frame rate; zero or negative is reordering.
        if (interval !in 1..MAX_MEASURED_INTERVAL_US) return
        if (intervalUs <= 0.0) {
            intervalUs = interval.toDouble()
            return
        }
        val ratio = interval / intervalUs
        if (ratio > OUTLIER_RATIO || ratio < 1 / OUTLIER_RATIO) {
            if (++outliers < RATE_CHANGE_FRAMES) return
            intervalUs = interval.toDouble()
        } else {
            intervalUs = intervalUs * 0.9 + interval * 0.1
        }
        outliers = 0
    }

    /** A seek: the next frame does not follow the last one, but the rate stands. */
    fun forgetLastFrame() {
        lastUs = NONE
        outliers = 0
    }

    /** A new stream, which may be another video: measure afresh. */
    fun reset() {
        forgetLastFrame()
        intervalUs = 0.0
    }

    companion object {
        /** Gaps longer than this (under 5 fps) are pauses or cuts, not a frame rate. */
        const val MAX_MEASURED_INTERVAL_US = 200_000L

        /**
         * An interval this many times longer or shorter than the estimate is
         * a gap or a glitch: one dropped frame doubles it.
         */
        const val OUTLIER_RATIO = 1.5

        /** This many outliers in a row are the new rate. */
        const val RATE_CHANGE_FRAMES = 3

        private const val NONE = Long.MIN_VALUE
    }
}

/**
 * The steady clock output frames leave on while interpolating. Ticks are
 * computed from the origin rather than accumulated, so a fractional step
 * (8333.3 us at 120 fps) never drifts.
 */
class OutputClock {
    private var originUs = 0L
    private var index = 0L

    var stepUs: Double = 0.0
        private set

    var isRunning: Boolean = false
        private set

    /** Starts ticking one step after [atUs], a frame already shown. */
    fun start(atUs: Long, stepUs: Double) {
        originUs = atUs
        this.stepUs = stepUs
        index = 1
        isRunning = true
    }

    fun stop() {
        isRunning = false
    }

    /**
     * Whether [stepUs] differs enough from the running step to restart on it:
     * a new rate or speed does, while a variable-rate source wobbling in and
     * out of [FrameInterpolationPolicy.SNAP_TOLERANCE] does not, since every
     * restart shows one frame off the clock.
     */
    fun needsRestart(stepUs: Double): Boolean =
        !isRunning || abs(stepUs - this.stepUs) > this.stepUs * RESTART_TOLERANCE

    private companion object {
        const val RESTART_TOLERANCE = 0.03
    }

    fun nextTickUs(): Long = originUs + (index * stepUs).roundToLong()

    fun advance() {
        index++
    }
}

/**
 * Decides whether the phone is keeping up, from the video renderer's drop
 * counter and the effect's own output count, sampled on each poll.
 *
 * **Why counters and a window rather than the renderer's drop reports.** The
 * first version acted on `onDroppedVideoFrames`, which arrives in batches -
 * at fifty drops, or whenever the renderer stops - covering whatever happened
 * since the last one. A quality change stops the renderer, so twelve seconds
 * of 1080p60 (which Smooth motion then left alone) arrived as one batch the
 * moment the viewer picked a 30 fps quality, and switched the feature off for
 * exactly the part it was about to work on. Here every poll is attributed
 * separately, and only polls that actually drew frames count.
 *
 * **What a drop is measured against.** With effects on, the renderer's
 * `droppedBufferCount` counts both decoder buffers too late to hand over and
 * output frames the sink dropped late, drawn ones included [verified
 * September 2026 against the Media3 1.11.0 bytecode: the sink listener's
 * `onFrameDropped` calls `updateDroppedBufferCounters(0, 1)`], while
 * `renderedOutputBufferCount` counts only decoder buffers. Measured against
 * the decoder count, 5% of a 120 fps output looked like 20% at 30 fps, so
 * the ratio is against the frames the effect emitted.
 *
 * **What it tolerates.** A grace period after anything that restarts the
 * pipeline (the first frames of a video, a quality change, a seek, a resume
 * after pause or buffering), where late frames are the decoder warming up;
 * and up to [maxDropRatio] of frames dropped over a sustained window.
 */
class FrameDropWatch(
    private val graceMs: Long = 6_000L,
    private val windowPolls: Int = 24,
    private val minimumPolls: Int = 20,
    private val maxDropRatio: Float = 0.10f,
    /** Position movement off the expected pace beyond this is a seek. */
    private val seekToleranceMs: Long = 1_500L,
) {
    private var lastDropped = -1
    private var lastEmitted = -1L
    private var lastSynthesized = -1L
    private var lastPositionMs = 0L
    private var lastPollAtMs = 0L
    private var wasPlaying = false
    private var graceUntilMs = 0L
    private val window = ArrayDeque<Pair<Int, Long>>()

    /** A new video or quality: start over, grace included. */
    fun restart(nowMs: Long) {
        lastDropped = -1
        lastEmitted = -1L
        lastSynthesized = -1L
        wasPlaying = false
        window.clear()
        graceUntilMs = nowMs + graceMs
    }

    /** @return true when sustained interpolation dropped more than the budget. */
    fun onPoll(
        nowMs: Long,
        isPlaying: Boolean,
        positionMs: Long,
        speed: Float,
        droppedFrames: Int,
        emittedFrames: Long,
        synthesizedFrames: Long,
    ): Boolean {
        val first = lastDropped < 0
        val countersReset = !first && (droppedFrames < lastDropped || emittedFrames < lastEmitted)
        val expectedAdvance = ((nowMs - lastPollAtMs) * speed).toLong()
        val seeked = !first && wasPlaying && isPlaying &&
            abs((positionMs - lastPositionMs) - expectedAdvance) > seekToleranceMs
        val resumed = !first && isPlaying && !wasPlaying
        if (first || countersReset || seeked || resumed) {
            graceUntilMs = max(graceUntilMs, nowMs + graceMs)
            window.clear()
        }
        val dropped = if (first || countersReset) 0 else droppedFrames - lastDropped
        val emitted = if (first || countersReset) 0L else emittedFrames - lastEmitted
        val interpolated = !first && !countersReset && synthesizedFrames > lastSynthesized

        lastDropped = droppedFrames
        lastEmitted = emittedFrames
        lastSynthesized = synthesizedFrames
        lastPositionMs = positionMs
        lastPollAtMs = nowMs
        wasPlaying = isPlaying

        // Only sustained, uninterrupted interpolation is judged.
        if (!isPlaying || !interpolated) {
            window.clear()
            return false
        }
        if (nowMs < graceUntilMs) return false
        window.addLast(dropped to emitted)
        while (window.size > windowPolls) window.removeFirst()
        if (window.size < minimumPolls) return false
        val droppedTotal = window.sumOf { it.first }
        val emittedTotal = window.sumOf { it.second }
        return emittedTotal > 0 && droppedTotal.toFloat() / emittedTotal > maxDropRatio
    }
}

/**
 * Notices a screen that stays below the output rate: a phone locked to 60 Hz,
 * "smooth display" off, or a mode switch the system refuses as not seamless.
 * Frames beyond the refresh rate are drawn and never seen, so once the
 * display has held below the output for [patienceMs] of steady output, the
 * output drops to what the display shows for the rest of this video and
 * quality. Media3 only asks for the new rate once its estimator has synced
 * on the output, which takes a moment, hence the patience.
 */
class ScreenRateWatch(private val patienceMs: Long = 5_000L) {
    private var belowSinceMs = 0L

    /** The rate the display was seen to stay at, or 0 while nothing argues against the target. */
    var limitFps: Int = 0
        private set

    fun restart() {
        belowSinceMs = 0L
        limitFps = 0
    }

    fun onPoll(nowMs: Long, emitting: Boolean, outputFps: Int, displayHz: Float) {
        val shown = displayHz.roundToInt()
        if (!emitting || outputFps <= 0 || shown <= 0 || shown + 5 >= outputFps) {
            belowSinceMs = 0L
            return
        }
        if (belowSinceMs == 0L) {
            belowSinceMs = nowMs
        } else if (nowMs - belowSinceMs >= patienceMs) {
            limitFps = shown.coerceAtLeast(FrameInterpolationPolicy.MIN_TARGET_FPS)
            belowSinceMs = 0L
        }
    }
}

