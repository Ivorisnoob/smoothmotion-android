# Changelog

All notable changes to this library. Versions follow [Semantic Versioning](https://semver.org); while the version is 0.x, a minor release may change public API, and says so here.

## Unreleased

## 0.1.0 - 2026-10-01

First release, a copy of the engine in Koda at commit `dcb5002` ([docs/SYNC.md](docs/SYNC.md)).

- `SmoothMotion.install(player, context)`: frame interpolation for an `ExoPlayer` in one call, with a status flow, an on/off switch and a 60/120 fps cap that apply without rebuilding the player.
- Backs off by itself for live and HDR video, thermal throttling, battery saver, sources already at the screen's rate, and phones that drop more than 10% of frames.
- `videoAspectRatio`, and `PlayerView.bindSmoothMotion` in `smoothmotion-ui`, for the video size the player stops reporting.
- Redraws a paused frame after the video surface changes size.
- `SmoothMotion.diagnose(context)` for bug reports; `SmoothMotionLog.logger` to route logs.
- `smoothmotion-core`: the GPU engine and timing rules with no Media3 dependency.
- Includes Koda's cadence fix (rate measured without gaps, snapping never faster than the screen). It has not yet been confirmed on a device.
