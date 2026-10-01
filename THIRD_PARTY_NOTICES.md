# Third-party notices

smoothmotion-android is licensed under the [Apache License 2.0](LICENSE). This file credits work that shaped its code.

## open-svpflow: SVP's frame rendering, masks and scene handling

<https://github.com/Z1xus/open-svpflow>, ported from commit `a334f02` (September 2026), licensed under the Apache License 2.0 (the same text as [LICENSE](LICENSE)). open-svpflow is an independent open-source reimplementation of SVPFlow, the VapourSynth plugins of SmoothVideo Project (SVP). This library contains no code from SVP itself; SVP is named only to identify the method.

What `ComputeMotionEngine.kt` ports from it:

- the `SmoothFps` renderer (`crates/svpflow-core/src/renderer.rs`): each output pixel blends frame A warped along the backward vector field and frame B warped along the forward field, both read bilinearly between block centres, with algorithm 21's coverage swap and algorithm 13's median for hard pairs;
- the coverage mask (covered and uncovered areas) and the bad-area mask that fades poorly matched blocks toward a plain blend (`renderer.rs`);
- the scene classes (fine, hard, and a scene cut) from brightness-weighted block SAD (`crates/svpflow-core/src/metadata.rs`, `crates/svpflow2/src/metadata.rs`);
- phase re-timing for hard pairs and nearer-frame display at cuts (`crates/svpflow-core/src/frame_math.rs`), and how these combine per frame (`crates/svpflow2/src/core.rs`).

Changes made: the Rust CPU and WebGPU code is rewritten as GLSL ES 3.10 for Media3's OpenGL ES pipeline; vectors come from the library's own motion search, restated as SVP's per-block vector, score and mean luma rather than from svpflow1's `Analyse`; every tick of an easy pair is drawn at its own phase; and the scene class is read back behind a GPU fence for logging only.

## The motion search

The luma pyramid, 8x8 block matching coarse to fine in both directions and the 3x3 vector median are Koda's own, written after studying AMD's FidelityFX SDK. No FidelityFX code is included.

## Earlier engines

Two earlier engines, removed from Koda in September 2026 and never part of this library, were designed after studying the FidelityFX SDK and FFmpeg's `minterpolate` filter. No source code was copied from either.
