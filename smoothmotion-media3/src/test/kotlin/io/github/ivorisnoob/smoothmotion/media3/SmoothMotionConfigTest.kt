package io.github.ivorisnoob.smoothmotion.media3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmoothMotionConfigTest {

    @Test fun `defaults interpolate up to 120 and redraw paused frames`() {
        val config = SmoothMotionConfig()
        assertEquals(true, config.enabled)
        assertEquals(120, config.maxFps)
        assertEquals(true, config.redrawPausedFrameOnSurfaceChange)
    }

    @Test fun `a cap outside 60 to 120 is refused with the range in the message`() {
        val error = assertThrows(IllegalArgumentException::class.java) { SmoothMotionConfig(maxFps = 30) }
        assertEquals("maxFps must be between 60 and 120, was 30", error.message)
        assertThrows(IllegalArgumentException::class.java) { SmoothMotionConfig(maxFps = 144) }
    }

    @Test fun `describe reads as a status line`() {
        assertEquals("24 → 120 fps", FrameInterpolationStatus.Active(24, 120, FrameInterpolationStatus.RateLimit.NONE, 120).describe())
        assertEquals(
            "30 → 90 fps (held below 120 by the frame size)",
            FrameInterpolationStatus.Active(30, 90, FrameInterpolationStatus.RateLimit.RESOLUTION, 120).describe()
        )
        assertEquals("Not needed: already 60 fps", FrameInterpolationStatus.NotNeeded(60).describe())
        assertEquals("Not supported on this GPU", FrameInterpolationStatus.Unsupported.describe())
    }
}
