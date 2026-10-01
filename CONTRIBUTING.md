# Contributing

Thanks for helping. Issues and pull requests are welcome, from people and from agents working for them.

**Reports.** The most useful bug report has:
- the output of `SmoothMotion.diagnose(context)`;
- a minute of `adb logcat -s SmoothMotion` while the problem shows;
- the video's resolution and frame rate.

Device reports are valuable even when everything works: they tell us which GPUs keep up at which sizes.

**Changes.** Read [AGENTS.md](AGENTS.md); its rules apply to everyone. In short:
- run `./gradlew check` (and `python3 tools/validate_shaders.py` for shader changes);
- keep the engine's class names (it is shared with Koda; see [docs/SYNC.md](docs/SYNC.md));
- never let an engine failure reach playback;
- say in the pull request what you checked on a real phone. The emulator cannot run the engine.

By contributing you agree your work is licensed under the [Apache License 2.0](LICENSE).
