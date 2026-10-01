# AGENTS.md

Instructions for AI coding agents (and people) working **on this repository**. To add the library to an app, follow [docs/INTEGRATION.md](docs/INTEGRATION.md) instead.

## What this is

Real-time video frame interpolation for Android Media3. Three published modules and a sample app:

```
smoothmotion-core/     GPU engine (GLSL ES 3.10 in Kotlin strings) + pure timing rules. No Media3.
smoothmotion-media3/   SmoothMotion facade, Media3 GlEffect/GlShaderProgram, governor, status.
smoothmotion-ui/       PlayerView helpers (aspect ratio, surface buffer).
sample/                One-screen demo app.
tools/                 validate_shaders.py (compiles every shader with glslangValidator).
docs/                  INTEGRATION, TRAPS, ARCHITECTURE, SYNC.
```

Start with [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Every non-obvious behaviour has its reason in [docs/TRAPS.md](docs/TRAPS.md).

## Commands

```bash
./gradlew check                     # unit tests + lint (warnings are errors) for every module
./gradlew :smoothmotion-core:testDebugUnitTest
./gradlew publishToMavenLocal       # what JitPack runs
python3 tools/validate_shaders.py   # after any shader edit; needs glslang-tools
```

Needs JDK 17+ and an Android SDK with platform 36 (`local.properties`: `sdk.dir=...`).

## Rules

1. **The engine is shared with Koda.** Class names match Koda's on purpose. Do not rename `FrameInterpolation*`, `ComputeMotionEngine`, `MotionEngine` or the policy classes, and do not reformat the ported files. Either would turn every future port into a merge. Record any port in [docs/SYNC.md](docs/SYNC.md).
2. **Never let the engine fail playback.** Every engine call is inside a `catch (e: Exception)` that turns interpolation off and passes frames through. Keep it that way, including for new code paths.
3. **No new Media3 types in `smoothmotion-core`.** It must stay usable from any GL pipeline.
4. **Media3 stays pinned to 1.11.0** until someone checks a new version on a device (see TRAPS.md, "Media3 version"). Bump `TESTED_MEDIA3_VERSION` in `SmoothMotion.kt` with it.
5. **Shaders:** GLSL ES 3.10 only. Run `tools/validate_shaders.py` before committing. The emulator cannot run the engine (no ES 3.2), so do not claim a shader change works because the sample ran on one.
6. **Keep the timing rules pure and tested.** Logic goes in `FrameInterpolationPolicy.kt` with a unit test, and the GL code only follows it. Do not add a coroutine timeout or a wall-clock read inside the GL thread's per-frame path.
7. **Public API changes** are listed in `CHANGELOG.md` and, if they change how apps integrate, in `docs/INTEGRATION.md` and the README in the same change.
8. **Error messages point to a fix.** A new `check`/`require` in public API says what to do and links a `docs/TRAPS.md` heading (`SmoothMotion.DOCS`). Add the heading if it is new.
9. **No hardcoded thread assumptions:** public calls run on the player's application looper; the effect runs on Media3's GL thread; they meet only through `FrameInterpolationControl`'s `@Volatile` fields, one writer per field.
10. **Releasing:** bump `smoothmotion.version` in `gradle.properties`, move the CHANGELOG's Unreleased section under the version, and tag `vX.Y.Z`. JitPack builds from the tag.

## Before you hand work back

- `./gradlew check` passes, and so does `tools/validate_shaders.py` if a shader changed.
- Say what was only compiled and what was seen on a device. Behaviour on screen (smoothness, refresh rate, drops) can only be judged on a phone with ES 3.2.
