# Integrating smoothmotion-android

A recipe a person or an AI coding agent can follow from top to bottom in an existing Android app. Every step says what to search for, what to change, and how to check it. The finished app is described in [Acceptance criteria](#acceptance-criteria). Work towards that, not towards "it compiles".

**Prompt you can hand to an agent:**

> Add smoothmotion-android to this app by following https://github.com/Ivorisnoob/smoothmotion-android/blob/main/docs/INTEGRATION.md exactly. Do every step in order, report what you found at step 0, and finish by checking each acceptance criterion.

---

## 0. Survey the app (read only)

Run these from the app's repository root and keep the results. Every later step refers to them.

| Search | Pattern | What it tells you |
|---|---|---|
| Players | `ExoPlayer.Builder\(` | Where players are built. Each one is an install site |
| Prepare calls | `\.prepare\(\)` | Install must come before the first one on each player |
| Existing effects | `setVideoEffects\(` | Effects to pass through `otherEffects` |
| Player views | `PlayerView\b`, `StyledPlayerView\b`, `PlayerSurface\(` | Where to keep the picture's shape |
| Surface sizing | `videoSize`, `onVideoSizeChanged`, `VideoSize\(` | Code that will stop getting sizes and must use `videoAspectRatio` instead |
| Media3 version | `androidx.media3` in `gradle/libs.versions.toml` or `build.gradle*` | Compare with 1.11.0 |
| Repositories | `dependencyResolutionManagement` in `settings.gradle*` | Where to add JitPack |
| minSdk | `minSdk` | Must be 24 or higher |
| Player in a service | `MediaSessionService`, `MediaLibraryService` | The install site may be in the service |

Stop and report instead of guessing if:
- there is no `ExoPlayer` (the app uses `MediaPlayer`, libVLC or libmpv). This library only works inside Media3's pipeline;
- `minSdk` is under 24;
- the app's video is audio-only (music apps with no video surface gain nothing).

Decide per player: interpolate video that a person watches (the main video player, full screen, picture-in-picture). Skip previews, thumbnails, muted autoplay rows, and ambient backgrounds. They gain little and would cost battery.

## 1. Add the dependency

In `settings.gradle.kts`, inside `dependencyResolutionManagement { repositories { ... } }`, add JitPack if it is not there:

```kotlin
maven("https://jitpack.io")
```

With a version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
smoothmotion = "0.1.0"

[libraries]
smoothmotion-media3 = { group = "com.github.Ivorisnoob.smoothmotion-android", name = "smoothmotion-media3", version.ref = "smoothmotion" }
smoothmotion-ui = { group = "com.github.Ivorisnoob.smoothmotion-android", name = "smoothmotion-ui", version.ref = "smoothmotion" }
```

In the app module:

```kotlin
implementation(libs.smoothmotion.media3)
implementation(libs.smoothmotion.ui)      // only if the app uses PlayerView
```

Without a catalog, use the coordinates directly: `com.github.Ivorisnoob.smoothmotion-android:smoothmotion-media3:0.1.0`.

If the app pins Media3 to a version other than 1.11.0, do not change it on your own. Note it in your report: the library will log a warning, and the device check below matters more.

**Check:** the app compiles (`./gradlew :app:compileDebugKotlin`, or the module's equivalent).

## 2. Install at each player you chose

Find the `ExoPlayer.Builder(...).build()` from step 0. Add the install **immediately after it**, before any `prepare()`, `setMediaItem` or `setVideoEffects` that follows:

```kotlin
// before
player = ExoPlayer.Builder(context)
    .setRenderersFactory(factory)
    .build()
player.setMediaItem(item)
player.prepare()

// after
player = ExoPlayer.Builder(context)
    .setRenderersFactory(factory)
    .build()
smoothMotion = SmoothMotion.install(player, context, SmoothMotionConfig(enabled = settings.smoothMotionEnabled))
player.setMediaItem(item)
player.prepare()
```

Store `smoothMotion` next to the player: same owner (ViewModel, service, controller), same lifetime.

Imports: `io.github.ivorisnoob.smoothmotion.media3.SmoothMotion`, `io.github.ivorisnoob.smoothmotion.media3.SmoothMotionConfig`. Both are `@UnstableApi`, like most of Media3. Opt in the way the file already does (`@OptIn(UnstableApi::class)` on the class or function, or the module's `-opt-in` flag).

**Cases:**
- **The app already calls `setVideoEffects(list)`:** remove that call and pass the list instead: `SmoothMotion.install(player, context, config, otherEffects = list)`. A later `setVideoEffects` would drop interpolation.
- **The player is built in a `MediaSessionService`:** install there. Expose `smoothMotion.status`/`enabled` to the UI the way the app already exposes player state, or keep the feature always on with no UI.
- **The player is built lazily, or rebuilt:** install on every build, and release the old `SmoothMotion` with the old player.
- **The app needs the effect list itself** (it changes effects per item): use `SmoothMotion.create(context)`, include `smoothMotion.effect` **last** in the list it passes to `setVideoEffects` before the first prepare, and only when `smoothMotion.isSupported`. Then call `smoothMotion.attach(player)`.

**Never:**
- install after `prepare()`. It throws, and the fix is to move it, not to catch it;
- call `install` twice on one player, or share one `SmoothMotion` between players;
- call it off the player's thread.

## 3. Release with the player

Wherever the player is released (`player.release()`), release `SmoothMotion` first:

```kotlin
smoothMotion.release()
player.release()
```

## 4. Keep the picture's shape

With the effect installed, `player.videoSize` stays 0x0. Every place step 0 found that reads it, or relies on `PlayerView` sizing itself, needs one of these.

**`PlayerView`** (needs `smoothmotion-ui`): after the line that sets `playerView.player = player`, add the bind. Where the view is torn down, unbind:

```kotlin
playerView.player = player
playerView.bindSmoothMotion(smoothMotion)        // import io.github.ivorisnoob.smoothmotion.ui.bindSmoothMotion
// teardown:
playerView.unbindSmoothMotion()
```

In Compose with `AndroidView { PlayerView(it) }`, bind in `update` (after setting the player) and unbind in `onRelease`.

**Compose `PlayerSurface` or a custom `SurfaceView`/`TextureView`:** size it from the aspect-ratio flow:

```kotlin
val ratio by smoothMotion.videoAspectRatio.collectAsState()
PlayerSurface(player, Modifier.aspectRatio(ratio ?: 16f / 9f))
```

**Code that read `videoSize` for other reasons** (the picture-in-picture shape, portrait detection): use `smoothMotion.videoAspectRatio.value`, falling back to `player.videoSize` when it is null.

## 5. Let the user control it (recommended)

Interpolation costs battery and warmth, so give it a setting. Default to off if the app's audience is battery-sensitive (Koda's choice); on is fine for a dedicated video app.

- Show the setting only when `SmoothMotion.isSupported(context)` is true. Otherwise disable it, with a line such as "Not supported by this phone's graphics".
- Toggle: `smoothMotion.enabled = checked`. It takes effect within half a second. Do not rebuild the player.
- Optional cap: "Up to 60" / "Up to 120" maps to `smoothMotion.maxFps = 60 / 120`. Persist the number.
- Status line under the toggle, from `smoothMotion.status` (a `StateFlow`) or `addListener`: `status.describe()` gives English text; build translated strings from the `FrameInterpolationStatus` cases if the app is localised.
- If the app offers HDR quality choices, hide HDR while the setting is on. See [HDR](TRAPS.md#hdr).

If the app has a settings search index, add the new setting to it.

## 6. Verify

**Without a device** (an agent can do all of these):
1. The app compiles.
2. `grep` again: every player you chose has `SmoothMotion.install` between its `build()` and its first `prepare()`, and a `release()` before the player's.
3. No `setVideoEffects(` call remains after an install on the same player.
4. Every `PlayerView` bound to such a player calls `bindSmoothMotion` after setting its player, and unbinds on teardown.
5. Nothing that sizes a video surface reads only `player.videoSize`.
6. Existing unit tests still pass.

**On a device**, which a person must do (the emulator cannot run the engine; tell your user this):

```
adb logcat -s SmoothMotion
```

## Acceptance criteria

The integration is done when all of these hold on a phone with OpenGL ES 3.2 and a 90 or 120 Hz screen:

1. **Playback behaves as before when off.** With the setting off, seeking, quality switching, picture-in-picture and rotation work as they did. The effect stays in the pipeline and only copies each frame, which costs very little.
2. **It works when on.** A 24 or 30 fps video shows the status `24 → 120 fps` (or `→ 90`, `→ 60` per the screen and cap) within a couple of seconds of playing.
3. **The log agrees.** It shows `Installed; up to N fps`, `Motion engine: compute (GLES 3.2)` and `Interpolating up to N fps`, then every ten seconds a counters line with `out=` near the screen's rate and `drawn=` rising.
4. **The shape is right.** Portrait, landscape and full screen show the picture at its true aspect ratio. Zoom-to-fill, if the app has it, still zooms.
5. **Pause survives hand-offs.** Pause, then rotate or go full screen: the paused frame fills its space and is not a small picture in a corner.
6. **It backs off.** A 60 fps video on a 60 Hz screen reads `Not needed: already 60 fps`. A live stream reads `Paused: live stream`. Battery saver on reads `Paused: battery saver`.
7. **Unsupported phones are untouched.** On a phone without ES 3.2 (or the emulator) the setting is disabled or reads `Not supported on this GPU`, and playback is exactly as before.
8. **Toggling is instant.** Switching the setting during playback changes the status within a second, with no rebuffer.

Report each criterion as met, not met, or "needs a device check", with the reason.

## Removing it

Delete the install, release, bind and unbind calls and the dependency. If you replaced a `setVideoEffects(list)` call in step 2, put it back.
