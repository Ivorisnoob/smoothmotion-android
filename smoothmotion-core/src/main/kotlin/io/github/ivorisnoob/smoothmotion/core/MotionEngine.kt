package io.github.ivorisnoob.smoothmotion.core

import android.opengl.GLES20
import android.opengl.GLU

/**
 * A GL texture the engine reads or draws into, with the framebuffer bound to
 * it. The engine never owns these: whoever made one releases it.
 *
 * Media3 users never build one by hand; the `smoothmotion-media3` module wraps
 * Media3's `GlTextureInfo` at the boundary. Any other GL pipeline (a custom
 * `SurfaceTexture` renderer, a transcoder) passes its own texture and FBO ids.
 */
public class GlFrame(
    /** A `GL_TEXTURE_2D` holding RGBA colour, sampled with normalised coordinates. */
    public val texId: Int,
    /** A framebuffer with [texId] as colour attachment 0; needed only for frames the engine draws into. */
    public val fboId: Int,
    public val width: Int,
    public val height: Int,
)

/**
 * The motion half of frame interpolation: everything between "here are two
 * decoded frames" and "here is the frame at phase t". The host (in Media3,
 * `FrameInterpolationShaderProgram`) owns the clock, the output pool and the
 * reference copy, and asks the engine ([ComputeMotionEngine], GLES 3.2) for
 * the drawn frames.
 *
 * **Call order.** [build] once, [configure] for each frame size, then per
 * decoded frame: [prepareReference] for the first frame of a run, and for each
 * later one [estimate], any number of [compose] calls, then [promoteCurrent].
 * [forgetHistory] after a seek or gap. [release] at the end.
 *
 * Every call runs on one thread with one GL context current. Any exception
 * means "this engine cannot run here": stop interpolating and pass frames
 * through. Never let it fail playback.
 */
public interface MotionEngine {
    /** For the log and a status read-out. */
    public val name: String

    /** Compiles everything; throws when this GPU cannot run the engine. */
    public fun build()

    /** Allocates for a [width] x [height] stream, releasing any previous size. */
    public fun configure(width: Int, height: Int)

    /** Prepares [input], about to become the kept frame A, from scratch. */
    public fun prepareReference(input: GlFrame)

    /** Prepares [current] as B and estimates the motion between A and B. */
    public fun estimate(current: GlFrame)

    /** After a pair: B's prepared data becomes the next pair's A. */
    public fun promoteCurrent()

    /**
     * Draws the frame [phase] of the way from [reference] (A) to [current] (B)
     * into [target]. [step] is the distance between output ticks in the same
     * units (a 24 fps pair drawn at 60 fps: 0.4), which the engine's re-timing
     * rules are written in.
     */
    public fun compose(reference: GlFrame, current: GlFrame, target: GlFrame, phase: Float, step: Float)

    /** The next pair does not follow this one (a seek, a gap): drop temporal hints. */
    public fun forgetHistory()

    /**
     * Starts reading how hard the pair just estimated was, without waiting for
     * the GPU; does nothing while an earlier reading is still in flight.
     */
    public fun requestHardness()

    /**
     * The reading [requestHardness] started, 0 to 1 (1 a scene cut), once the
     * GPU has got that far; -1 while it is still in flight or when none was
     * asked for. Never waits for the GPU, so the host may call it every pair.
     */
    public fun takeHardness(): Float

    public fun release()
}

/** GL helpers the engine and its hosts share. */
public object MotionGl {

    /** Thrown by [checkGlError]: a GL call failed. Treat it like any engine exception. */
    public class GlException(message: String) : RuntimeException(message)

    /**
     * Throws [GlException] naming every pending GL error, or returns. The
     * engine calls it once per call rather than per GL command.
     */
    @JvmStatic
    public fun checkGlError() {
        val errors = ArrayList<String>()
        while (true) {
            val error = GLES20.glGetError()
            if (error == GLES20.GL_NO_ERROR) break
            errors += "0x${Integer.toHexString(error)} ${GLU.gluErrorString(error) ?: ""}".trim()
            if (errors.size >= 8) break
        }
        if (errors.isNotEmpty()) throw GlException("GL error: ${errors.joinToString()}")
    }

    /** Binds [fboId] (0 is the default framebuffer) and sets the viewport to [width] x [height]. */
    @JvmStatic
    public fun focusFramebuffer(fboId: Int, width: Int, height: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)
    }

    /** The current context's GLES version as major * 10 + minor, 20 when unreadable. */
    @JvmStatic
    public fun glesVersion(): Int {
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: return 20
        val match = Regex("""OpenGL ES (\d+)\.(\d+)""").find(version) ?: return 20
        return match.groupValues[1].toInt() * 10 + match.groupValues[2].toInt()
    }
}
