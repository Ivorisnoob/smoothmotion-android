# smoothmotion-android

Real-time video frame interpolation for Android apps built on **Media3 / ExoPlayer**. A 24, 25 or 30 fps video reaches the screen at 60, 90 or 120 fps: the GPU draws the frames in between, timed to the display's refresh rate.

Built for [Koda](https://github.com/Ivorisnoob/Koda), where it ships as **Smooth motion**, and published here so any Media3 app can use it.

```kotlin
val player = ExoPlayer.Builder(context).build()
val smoothMotion = SmoothMotion.install(player, context)   // before prepare()
playerView.player = player
playerView.bindSmoothMotion(smoothMotion)
```

That is the whole integration. Everything below explains what those lines do for you and how to show the status to your users.

> **Using an AI coding agent?** Point it at [`docs/INTEGRATION.md`](docs/INTEGRATION.md): a step-by-step recipe with search patterns, exact edits and an acceptance checklist. [`llms.txt`](llms.txt) indexes everything for tools that read it.

---

## What it does

- **Draws real in-between frames.** It does not blend two frames together. Motion is estimated block by block on the GPU, and each new frame is warped along it. The renderer is a port of [open-svpflow](https://github.com/Z1xus/open-svpflow), an open reimplementation of SmoothVideo Project's method. Where motion is too hard to follow, the picture goes soft instead of tearing into blocks.
- **Times frames to the screen.** Output follows a steady clock at the display's rate, so Media3 asks the panel for 120 Hz by itself. When the timing lines up (24 into 120, 30 into 60), every original frame is shown untouched.
- **Backs off by itself.** It passes frames through, at almost no cost, for:
  - videos already near the screen's rate;
  - live streams and HDR video;
  - a phone that is warm (thermal status moderate or worse);
  - battery saver;
  - a phone that drops more than 10% of frames over about ten seconds. That video, at that quality, then plays normally.
- **Never breaks playback.** If anything in the engine fails on a given GPU, interpolation turns off for that player and video plays as usual.

**Measured on devices** (in Koda, September 2026):
- Mali-G615 MC6 (Xiaomi 2311DRK48I): 1080p at 23.98 fps into 120 fps for 12 minutes, with 0.44% of frames dropped. The display rose from 60 Hz to 120 Hz on its own.
- Adreno 613: 360p and 480p at 30 fps into 60 fps, at full rate.

## Requirements

| | |
|---|---|
| GPU | OpenGL ES **3.2**. Check with `SmoothMotion.isSupported(context)`; on other GPUs nothing is installed and video plays normally |
| Android | minSdk 24. Thermal pausing needs API 29 |
| Media3 | **1.11.0** (tested). Other versions log a warning; see [Media3 version](docs/TRAPS.md#media3-version) |
| Player | `ExoPlayer`, or anything built on it. Not libVLC, libmpv or `MediaPlayer` |
| Emulator | Does not run the engine: no emulator offers ES 3.2. Test on a phone |

## Install

The library is published through [JitPack](https://jitpack.io/#Ivorisnoob/smoothmotion-android). JitPack builds it from a GitHub tag on first request.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    // The facade and the effect. Brings in smoothmotion-core, media3-exoplayer and media3-effect.
    implementation("com.github.Ivorisnoob.smoothmotion-android:smoothmotion-media3:0.1.0")
    // Optional: PlayerView helpers (keeps the picture's shape). Brings in media3-ui.
    implementation("com.github.Ivorisnoob.smoothmotion-android:smoothmotion-ui:0.1.0")
}
```

| Module | For |
|---|---|
| `smoothmotion-media3` | Most apps. `SmoothMotion`, the effect, status and governor |
| `smoothmotion-ui` | Apps that draw video with Media3's `PlayerView` |
| `smoothmotion-core` | The GPU engine and timing rules alone, with no Media3. For your own GL pipeline |

## Use it

### 1. Install before the first `prepare()`

```kotlin
val player = ExoPlayer.Builder(context).build()
val smoothMotion = SmoothMotion.install(
    player,
    context,
    SmoothMotionConfig(enabled = userWantsIt, maxFps = 120),  // optional
)
```

Media3 decides on the first `prepare()` whether video goes through an effect pipeline, and never revisits it. `install()` throws if the player has already been prepared, and the message says how to fix it. To let users switch the feature on and off, install it once and flip `smoothMotion.enabled`. That takes effect within half a second, with no rebuild.

If your app already uses video effects, pass them in: `SmoothMotion.install(player, context, otherEffects = myEffects)`. Calling `setVideoEffects` yourself afterwards would replace interpolation.

### 2. Keep the picture's shape

With the effect installed, the player reports a video size of 0x0 (a Media3 behaviour; see [the trap](docs/TRAPS.md#the-player-reports-no-video-size)). Without a fix, zoom and aspect-ratio layouts stop working.

- **`PlayerView`:** call `playerView.bindSmoothMotion(smoothMotion)` after `playerView.player = player`, and `playerView.unbindSmoothMotion()` when the view goes away.
- **Compose or your own surface:** size it from `smoothMotion.videoAspectRatio` (a `StateFlow<Float?>`):

```kotlin
val ratio by smoothMotion.videoAspectRatio.collectAsState()
PlayerSurface(player, Modifier.aspectRatio(ratio ?: 16f / 9f))
```

### 3. Show what it is doing

```kotlin
smoothMotion.status.collect { status -> subtitle.text = status.describe() }
// "24 → 120 fps", "Not needed: already 60 fps", "Paused: battery saver", ...
```

`FrameInterpolationStatus` is a sealed type. Write your own (translated) text from its fields. Java and View apps can use `smoothMotion.addListener(...)`.

### 4. Release

```kotlin
playerView.unbindSmoothMotion()
smoothMotion.release()
player.release()
```

### Settings worth offering

Smooth motion uses more battery and makes the phone warmer. Koda keeps it **off by default**, behind a short warning, and offers:

- a switch, and an **Up to 60 / Up to 120** choice (`maxFps`);
- the switch only where `SmoothMotion.isSupported(context)` is true. Otherwise, say the phone's graphics do not support it;
- the status line under the switch, so people can see it working.

## How you know it works

On a phone with a 90 or 120 Hz screen, play a 24 or 30 fps video:

1. The status reads `Measuring the video`, then for example `24 → 120 fps`.
2. `adb logcat -s SmoothMotion` prints `Installed`, the GPU, `Motion engine: compute (GLES 3.2)`, `Interpolating up to 120 fps`, and a counters line every ten seconds:

   ```
   source=23.98fps out=120fps target=120 screen=120Hz drawn=... emitted=... dropped=... pairs=...
   ```

3. Panning shots look continuous instead of stepping. The developer option "Show refresh rate" reads 120.

The [sample app](sample/) does all of this in one screen. Open it, pick a video and watch the status line. **Diagnose** prints what the device offers.

## Things to know

The full list, with the reasons, is in [docs/TRAPS.md](docs/TRAPS.md):

- **Install before the first prepare.** Afterwards, Media3 cannot add the pipeline.
- **The player reports no video size.** Size views from `videoAspectRatio`.
- **HDR passes through untouched.** Koda also prefers SDR renditions while the feature is on, because HDR through a GL pipeline is tone-mapped on some devices and not others.
- **A paused frame needs redrawing after the surface changes size.** `SmoothMotion` does it (a 150 ms settle, then an exact seek to the same position).
- **One `SmoothMotion` per player**, called on the player's thread.

## Troubleshooting

| You see | Likely cause |
|---|---|
| Status `Not supported on this GPU` | No OpenGL ES 3.2, or the driver rejected a shader. Run `SmoothMotion.diagnose(context)` |
| Status `On after the player is rebuilt` | The feature was enabled for a player built without the effect. Install it when you build the player |
| Status stays `Measuring the video` | Playback has not produced frames yet, or the source is under ~17 fps |
| `Paused: this phone could not keep up` | Over 10% dropped for ten seconds. Try a lower quality or `maxFps = 60` |
| Output is 60 on a 120 Hz phone | The screen held at 60 (a refresh-rate lock, "smooth display" off). Status says "held below 120 by the screen" |
| Picture fills the view and zoom does nothing | Missing `bindSmoothMotion`, or it was called before `playerView.player = player` |
| Small paused picture in a corner after rotating | `redrawPausedFrameOnSurfaceChange` was turned off |

Logs go to logcat under `SmoothMotion`. To route them elsewhere, set `SmoothMotionLog.logger`.

## Documentation

- [docs/INTEGRATION.md](docs/INTEGRATION.md): integration recipe for people and AI agents
- [docs/TRAPS.md](docs/TRAPS.md): every non-obvious behaviour, with the reason
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how the clock, engine and governor work, and how to change a shader safely
- [docs/SYNC.md](docs/SYNC.md): how this repo tracks Koda's copy of the engine
- [AGENTS.md](AGENTS.md): working on this repository (people and agents)
- [CHANGELOG.md](CHANGELOG.md)

## Credits and licence

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).

The frame renderer, its masks, scene classes and re-timing are a port of [open-svpflow](https://github.com/Z1xus/open-svpflow) (Apache 2.0), itself an independent reimplementation of SmoothVideo Project's SVPFlow. No code from SVP is included. The motion search is Koda's own. Details are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
