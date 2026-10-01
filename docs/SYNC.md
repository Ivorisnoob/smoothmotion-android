# Keeping in step with Koda

The engine is developed and tested on devices inside [Koda](https://github.com/Ivorisnoob/Koda), which keeps its own copy. Neither repository depends on the other. Fixes are ported **by hand** in whichever direction they were made, and this file records how far the two copies agree.

## Last sync

| | |
|---|---|
| Koda commit | `dcb5002` (1 October 2026) |
| Engine (`ComputeMotionEngine.kt`) | Koda `6e62131`, 28 September 2026: the 320 px motion search |
| Clock and governor (`FrameInterpolation.kt`, `FrameInterpolationShaderProgram.kt`) | Koda `4bbd6f3`, 30 September 2026: the cadence fix (gaps left out of the rate, snapping never faster than the screen, re-anchoring per pair) |
| `MotionEngine.kt` | Koda `8df2041`, 28 September 2026 |
| `PlayerViewAspect.kt` (`smoothmotion-ui`) | Koda `d2cf6ab`, 29 September 2026 |

The cadence fix (`4bbd6f3`) was in code but not yet confirmed on a device at the time of this sync. Koda's roadmap still lists "Smooth motion loses its cadence after a burst of dropped frames" until a tester confirms it.

When you sync, update this table in the same commit, and say which Koda commit you synced to in the commit message.

## Where each file went

Class names are kept identical to Koda's on purpose, so a diff between the two copies shows real changes and not renames.

| Koda (`app/src/main/java/com/ivor/ivormusic/`) | Here |
|---|---|
| `service/ComputeMotionEngine.kt` | `smoothmotion-core/.../core/ComputeMotionEngine.kt` |
| `service/MotionEngine.kt` (`MotionEngine`, `MotionGl`) | `smoothmotion-core/.../core/MotionEngine.kt` (plus `GlFrame`) |
| `service/FrameInterpolation.kt`: `FrameInterpolationSupport`, `FrameInterpolationPolicy`, `SourceRateMeter`, `OutputClock`, `FrameDropWatch`, `ScreenRateWatch` | `smoothmotion-core/.../core/FrameInterpolationPolicy.kt` |
| `service/FrameInterpolation.kt`: `FrameInterpolationEffect`, `FrameInterpolationControl`, `FrameInterpolationStatus`, `FrameInterpolationGovernor` | `smoothmotion-media3/.../media3/FrameInterpolation.kt` |
| `service/FrameInterpolationShaderProgram.kt` | `smoothmotion-media3/.../media3/FrameInterpolationShaderProgram.kt` |
| `ui/video/PlayerViewAspect.kt` | `smoothmotion-ui/.../ui/PlayerViewAspect.kt` |
| `test/.../ComputeMotionPlanTest.kt`, `FrameInterpolationPolicyTest.kt` | `smoothmotion-core/src/test/...` (unchanged apart from the package) |
| `ui/video/VideoPlayerViewModel.kt` (install, poll, HDR, redraw) | Rewritten as `smoothmotion-media3/.../media3/SmoothMotion.kt` |

## Deliberate differences

Leave these alone when porting; they are what makes this a library.

- **No Media3 types in the engine.** `MotionEngine` takes `GlFrame` instead of `GlTextureInfo`, and `MotionGl.checkGlError` / `focusFramebuffer` replace `GlUtil`'s. The Media3 module wraps textures with `GlTextureInfo.asFrame()`. Output and reference textures are made by `Media3Textures`, not `MotionGl.texture`.
- **The engine is injectable:** `FrameInterpolationEffect(control, engineFactory)`. Koda constructs `ComputeMotionEngine(control)` directly. The engine never used `control`, so it was dropped.
- **Logging** goes through `SmoothMotionLog` (tag `SmoothMotion`) instead of Koda's `KLog` (tag `FrameInterpolation`).
- **minSdk 24, not 30:** the governor reads the thermal status only on API 29+.
- **Koda-only wording** in KDoc (the ViewModel, Settings rows, `.probe/` harness paths) is replaced with library wording.
- **`SmoothMotion`** does what Koda's `VideoPlayerViewModel` does by hand: the fresh-read install, the 500 ms poll that feeds the governor, the paused-frame redraw, and the aspect ratio. Koda does not use it.

## Porting a fix

From Koda to here:
1. `git -C ../Koda log --oneline <last synced commit>..HEAD -- app/src/main/java/com/ivor/ivormusic/service app/src/main/java/com/ivor/ivormusic/ui/video/PlayerViewAspect.kt app/src/test/java/com/ivor/ivormusic/service`
2. Apply each change by hand to the matching file above, keeping the differences listed.
3. `./gradlew check` and `python3 tools/validate_shaders.py`.
4. Update the table above. Add a CHANGELOG entry that describes the user-visible change.

From here to Koda: the same in reverse. Koda's own rules apply there (its `CLAUDE.md`).
