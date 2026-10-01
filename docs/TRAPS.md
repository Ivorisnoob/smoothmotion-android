# Traps

Every behaviour here compiles cleanly and fails at run time, or only on some phones. `SmoothMotion` handles most of them for you; this page is the reason why, and what to do when you go around it. The library's error messages link to these headings.

Facts marked **[verified September 2026]** were read from the Media3 1.11.0 bytecode or seen on a device. Re-check them when Media3 changes (see [Media3 version](#media3-version)).

---

## Install before the first prepare

**Symptom:** interpolation never starts; status reads `On after the player is rebuilt`, or `install()` throws `IllegalStateException`.

**Why** [verified September 2026]: `MediaCodecVideoRenderer.onEnabled` decides once, on the renderer's first enable, whether video goes through the effect graph (`hasSetVideoSink`). `setVideoEffects` after that does nothing for this player. A player that was prepared and then stopped is idle again but has already decided, so prepare is the line, not the playback state.

**Do:**
- call `SmoothMotion.install(player, context)` immediately after `ExoPlayer.Builder(...).build()`;
- to let users switch it on and off, install once and set `smoothMotion.enabled`. Off costs one texture copy per frame;
- if the user enables it while a player built without it is open, rebuild the player at the next natural moment (Koda does it when the player closes). Status says `NeedsRestart` until then.

## The player reports no video size

**Symptom:** with the effect installed, `Player.videoSize` stays 0x0 and `onVideoSizeChanged` never fires; `PlayerView` fills the whole view; "zoom to fill" (`RESIZE_MODE_ZOOM`) changes nothing; layouts sized from the video size collapse.

**Why** [verified September 2026]: the renderer hands the graph's size to its video-sink listener, whose `onVideoSizeChanged` is an empty method in 1.11.0 (`MediaCodecVideoRenderer$1`). The graph's last stage letterboxes the picture inside the surface, so plain fit still *looks* right, which hides the problem until something zooms.

**Do:**
- `PlayerView`: `playerView.bindSmoothMotion(smoothMotion)` **after** `playerView.player = player` (setting a player resets the frame's ratio);
- anything else: size the surface from `smoothMotion.videoAspectRatio`, which is worked out from the decoder's input format and the container's rotation;
- a `SurfaceView` that zooms: hold its buffer at the fitted size (`keepKnownAspectRatio` does this). Otherwise every zoom resizes the surface, the graph re-configures, and frames drawn meanwhile come out at the old size anchored bottom-left: a flicker on each zoom.

## A paused frame after a surface change

**Symptom:** paused video, then the view changes (mini player to full screen, rotation): the picture is small in the bottom-left corner until playback resumes.

**Why:** the graph draws a paused frame once, when a surface arrives, and the surface's size arrives after it. Media3's `VideoFrameProcessor.REDRAW` is not the fix: it needs a replayable frame cache only the composition player builds, and throws without one [verified September 2026].

**Do:** nothing, by default. `SmoothMotion` listens for `onSurfaceSizeChanged`, waits 150 ms for the burst of sizes to settle, and seeks `EXACT` to the current position, which draws the frame afresh. Set `redrawPausedFrameOnSurfaceChange = false` only if you redraw yourself.

## HDR

**Symptom:** HDR video looks washed out or too dark with the effect installed, and differently on different phones.

**Why:** an HDR stream through Media3's GL pipeline is tone-mapped or not depending on the device's GL extensions. The engine never interpolates HDR (it copies it through at high precision), but the pipeline itself is the variable.

**Do:** while the feature is installed, prefer SDR renditions when you offer a choice. Koda withholds HDR from its quality list while Smooth motion is on, and while the player still carries the effect after it was turned off. Status reads `Paused: HDR video` when HDR plays anyway.

## Threading

**Symptom:** `IllegalStateException: SmoothMotion must be called on the player's application thread`.

**Do:** call `install`, `attach`, `enabled`, `maxFps` and `release` on the thread the player was built on (its `applicationLooper`, the main thread unless you chose another). Listeners are called on that thread. The engine itself runs on Media3's GL thread; you never touch it.

## One instance per player

**Symptom:** `IllegalStateException: SmoothMotion is already attached to a player`.

**Do:** one `SmoothMotion` per `ExoPlayer`, released before the player. A new player needs a new `SmoothMotion`. The effect object is tied to one governor; sharing it between players mixes their counters and decisions.

## Media3 version

**Symptom:** the log warns `Media3 X is not the tested 1.11.0`.

**Why:** the effect relies on behaviour that is not public API: how the renderer chooses the graph, that any number of outputs per input is legal in playback, that the sink's frame-rate estimator follows output timestamps (which is how the display is asked for 120 Hz), and the two bugs above. A Media3 update can change any of them.

**Do:** stay on 1.11.0 where you can. On another version, check on a device that video plays, the status reaches `Active`, and the counters line shows `out=` near the screen's rate. Open an issue with the result either way.

## The emulator

**Symptom:** status `Not supported on this GPU`, or frames pass through, on every emulator.

**Why** [verified September 2026, emulator 37.1.11]: the default host renderer reports ES 3.0; the ANGLE/Vulkan options reach ES 3.1 at most, and one of them fails Media3's own input stage. The engine needs 3.2.

**Do:** judge on a phone. For logic, the unit tests cover the timing rules; for shaders, `python3 tools/validate_shaders.py` compiles every one without a GPU.

## Dropped frames are counted against output, not decoded frames

Only matters if you read the counters yourself. With effects on, the renderer's `droppedBufferCount` also counts output frames the sink dropped late, drawn ones included [verified September 2026: the sink listener's `onFrameDropped` calls `updateDroppedBufferCounters(0, 1)`], while `renderedOutputBufferCount` counts only decoder buffers. Measured against the decoder count, a 5% drop at 120 fps output reads as 20%. The governor divides by frames the effect emitted (`control.emitted`).

## Things outside the library's control

- **The screen may refuse 120 Hz** (a battery-saving refresh lock, "smooth display" off, a mode switch the system calls non-seamless). After five seconds below the output, the output drops to what the screen shows, for that video and quality; status says "held below 120 by the screen".
- **Large frames cost more.** Above roughly 2.2 megapixels per output frame at 120 fps (beyond 1080p), the output steps down to 90, then 60. 60 is never withheld.
- **Variable-rate video** (phone recordings) is measured with outliers left out, so one dropped frame does not change the rate. A real rate change is taken after three frames in a row.
