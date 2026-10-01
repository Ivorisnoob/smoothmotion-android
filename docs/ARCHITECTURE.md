# Architecture

How a decoded frame becomes several on screen, and where each decision lives. Read this before changing the engine, the clock or the governor.

```
 ExoPlayer ── MediaCodec ─▶ Media3 effect graph ─────────────────────────────▶ DefaultVideoSink ─▶ Surface
                              │                                                   │
                              ▼                                                   │ FixedFrameRateEstimator
              FrameInterpolationShaderProgram  (GL thread, per frame)             │ follows OUTPUT timestamps,
                │  OutputClock: ticks at 60/90/120 fps of real time               │ asks Surface.setFrameRate
                │  tick on a decoded frame ─▶ copy it out                         ▼
                │  tick between A and B    ─▶ MotionEngine.compose(A, B, t)     display goes to 120 Hz
                ▼
              ComputeMotionEngine  (GLES 3.1 compute + fragment, GLES 3.2 context)
                per pair: LUMA pyramid ▶ SEARCH both ways ▶ MEDIAN ▶ FIELD ▶ PREP + SCENE
                per tick: PHASE ▶ COVER (clear, splat, finish) ▶ RENDER

 SmoothMotion (player thread, every 500 ms) ─▶ FrameInterpolationGovernor ─▶ FrameInterpolationControl
   speed, live, HDR, dropped frames,              gate open?  target fps?        @Volatile fields the GL
   screen modes, thermal, battery saver           status                         thread reads per frame
```

## Modules

| Module | Package | Holds | Depends on |
|---|---|---|---|
| `smoothmotion-core` | `io.github.ivorisnoob.smoothmotion.core` | `ComputeMotionEngine` (shaders), `MotionEngine`, `GlFrame`, `MotionGl`; pure timing rules: `FrameInterpolationPolicy`, `OutputClock`, `SourceRateMeter`, `FrameDropWatch`, `ScreenRateWatch`; `FrameInterpolationSupport`; `SmoothMotionLog` | Android GLES only |
| `smoothmotion-media3` | `...smoothmotion.media3` | `SmoothMotion` (facade), `FrameInterpolationEffect`, `FrameInterpolationShaderProgram`, `FrameInterpolationControl`, `FrameInterpolationGovernor`, `FrameInterpolationStatus` | core, media3-exoplayer, media3-effect, coroutines |
| `smoothmotion-ui` | `...smoothmotion.ui` | `PlayerView.bindSmoothMotion`, `keepKnownAspectRatio` | media3, media3-ui |
| `sample` | `...smoothmotion.sample` | One-screen demo app | ui |

The core has no Media3 types. A non-Media3 GL pipeline can drive `ComputeMotionEngine` through `MotionEngine` with its own textures (`GlFrame`) and reuse the timing rules. It would rewrite only what `FrameInterpolationShaderProgram` does: the clock loop, the output pool and the reference copy.

## Five facts from Media3 that shape it

Each was verified against the Media3 1.11.0 bytecode in September 2026. [TRAPS.md](TRAPS.md) covers what they mean for users.

1. **The screen follows the output.** `DefaultVideoSink` runs its own `FixedFrameRateEstimator` on output timestamps; its synced rate is what `VideoFrameReleaseHelper` passes to `Surface.setFrameRate` and paces vsync by. A steady 120 fps output asks for 120 Hz by itself. An irregular output would never sync, which is why output is a **clock**, not "midpoints plus every real frame".
2. **The graph is chosen once per player**, on the video renderer's first enable. Hence install-before-prepare, and per-video decisions as per-frame fields (`FrameInterpolationControl.active`) rather than changes to the effect list.
3. **Any number of outputs per input is legal.** `PlaybackVideoGraphWrapper` hands each output timestamp to the sink on its own.
4. **The context is ES 3.2 on current phones.** Media3 asks for ES 3 and drivers return their highest. `FrameInterpolationSupport` reads the system's declared version before any context exists, and `buildPrograms` re-checks the real context.
5. **The final stage holds each output until its display time** and releases it only when the sink renders or drops it. So the output pool bounds how far decoding runs ahead: `poolCapacity` keeps about three decoded frames' worth, within 100 MB, and never fewer than one input's outputs plus two.

## The clock

`OutputClock` ticks at `step = speed / fps` of media time, computed from an origin so a fractional step never drifts. Then:

- **Snapping.** When the source interval divides into whole steps within 1.5% (24 into 120, 30 into 60), the step snaps to it, so every Nth tick lands exactly on a decoded frame, shown untouched. A snapped step may never run faster than the screen (`MAX_SNAP_SPEEDUP`); 122 fps on a 120 Hz screen silently discards frames.
- **Anchoring.** On a snapped cadence the clock is re-anchored on every pair (`lockedStepUs`), so B is always the pair's last tick.
- **Restarts.** Only a step change beyond 3% (a new rate or speed) restarts it; a variable-rate source wobbling inside the tolerance does not.
- **Bounds.** At slow speeds the rate halves rather than draw more than eight frames per decoded one. Frames above ~2.2 MP step down to 90 then 60 fps (`affordableFps`).
- **The source rate** (`SourceRateMeter`) leaves out intervals more than 1.5x off the estimate unless three come in a row. A plain average once took in the gap a dropped frame leaves, restarted the clock on it and kept the wrong step.

## The engine

`ComputeMotionEngine` draws SVP's frame over its own motion search.

- **Search.** A normalised-luma pyramid whose finest level is at most 320 px on its long side, whatever the video size (`ComputeMotionPlan.LEVEL0_LONG_SIDE`), down to about 40 px.
  - 8x8 blocks, both directions, with a 3x3 vector median at every level. The median picks a neighbour's vector; a per-component median would invent vectors and break thin objects.
  - The coarsest level searches +-8 px, the next +-2, the rest refine +-1 around the best of up to seven predictions (zero, the parent and its neighbours, the previous pair).
  - A smoothness penalty pulls vectors toward the prediction (SVP's `penalty.lambda`).
  - The 320 px cap took an Adreno 613 from ~310 ms to ~23 ms per pair.
- **FIELD** restates the vectors in SVP's terms: per block, the vector, an 8-bit luma SAD at SVP's scaling, and the mean luma. A search working like svpflow1's `Analyse` could replace this one without touching the rest.
- **PREP** clamps vectors to the frame and builds the bad-area mask. **SCENE** sorts the pair into SVP's classes: 0 fine, 1-2 hard, 3 a cut.
- **PHASE** applies the timing rules per tick. Every tick of an easy pair is drawn at its own phase; a cut shows the nearer frame; hard pairs are re-timed toward real frames.
- **COVER** splats each field's blocks to the output moment into a coverage mask, using buffer `atomicAdd`.
- **RENDER** computes every pixel as one formula over two samples. It reads A along the backward field at t and B along the forward field at 1 - t, both bilinear between block centres, and blends them by time:
  - algorithm 21 swaps in the other sample where coverage says content is uncovered;
  - algorithm 13, on hard pairs, clamps the plain blend between the two samples;
  - the bad-area mask fades poorly matched blocks toward a plain blend.

  Nothing is decided per pixel, which is why hard motion goes soft instead of tearing. An earlier engine chose a vector per pixel, and its picture broke into blocks.

**Scheduling.** Stages that do not read each other are queued together. Each ends in one memory barrier carrying only the bits the next stage reads (`STAGE_BARRIER_BITS`). GL errors are checked once per call. The scene class is read back behind a fence for the log and is never waited for.

**Failure.** Any exception from the engine (building, configuring, drawing) turns interpolation off for that player and passes frames through. `GlProgram`-style uniform lookups on an optimised-away uniform can throw `NullPointerException` rather than a GL error, which is why engine calls are caught as `Exception`.

## The governor

`FrameInterpolationGovernor.update` runs on the player thread every 500 ms. `SmoothMotion` calls it.

1. **Target rate:** the lower of the user's cap and the display's fastest mode at its current resolution. Not the current refresh rate: phones idle at 60 Hz until asked for more.
2. **Gate:** user switch, not live, not HDR, thermal below moderate, no battery saver, not judged unable to keep up.
3. **`FrameDropWatch`:** counts drops against frames emitted, only over polls that drew frames. It gives a 6 s grace after a start, quality change, seek or resume, then judges more than 10% dropped over ten to twelve seconds. Its window is counted in polls, so the poll interval is fixed at 500 ms. An earlier version acted on Media3's batched drop reports: a quality change delivered twelve seconds of unrelated drops at once and switched the feature off for the very stream it was about to work on.
4. **`ScreenRateWatch`:** if the screen stays below the output for 5 s, the output drops to the screen's rate for that video and quality.
5. **Status:** published as `FrameInterpolationStatus`, plus a log line every ten seconds of playback.

## Changing a shader

1. Edit the GLSL in `ComputeMotionEngine.kt`. Keep it GLSL ES 3.10.
2. Run `python3 tools/validate_shaders.py`. CI runs it too. Rules it has caught before:
   - `imageAtomic*` needs ES 3.2 or an extension, so atomics go to shader storage buffers;
   - `packed` is reserved;
   - images are write-only and on immutable (`glTexStorage2D`) textures;
   - 32-bit float textures are `NEAREST` and read with `texelFetch`.
3. Quality is judged offline, not on the emulator. Koda keeps a harness (moderngl, open-svpflow references, PSNR against held-out frames) outside its repository; it is not published here. Describe how you judged a change in the pull request.
4. Then on a device: the counters line, `dropped=` over a few minutes, and your eyes on panning shots and scene cuts.
