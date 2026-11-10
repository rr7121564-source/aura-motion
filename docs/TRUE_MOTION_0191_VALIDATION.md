# True Motion 0.19.1 — AbsentMotion 5.0.3

Date: 2026-11-10. Complete source merge of the official `v5.0.3` tag, commit `b9e07a5a17d3f4d6f176dbd05e0e1a7a80abd6df`, into the validated True Motion 0.19.0 checkpoint. Android package `app.truemotion`, version code 10024.

## Preserved fork behavior

Retained True Motion color grading/audio/spectrum/particles, Classic and AfterMotionZ+ effects, shader/script plugins, vector editor markers, PSD import, media library/replacement, free numeric fields, anchored 2D parenting, exact affine rig/import matrices, camera XYZ support in old projects and the legacy per-pixel depth path. The original Classic Parent Helper remains separate from native Parenting Helper. Group navigation/restoration, bone-aware detach and motion-path baking are retained alongside upstream fixes.

Compatible GPU startup now includes the official OpenGL-first/proven-Vulkan path plus the existing narrow device safeguards. The updater still targets Mushroom65/TrueMotion and checks the permanent True Motion APK signing certificate. It does not use the upstream APK signer. License/credits and the separate future roadmap are retained.

The new native renderer was built from the merged C++ sources with pinned Skia. Other native libraries come from the official compatible 5.0.3 APK, verified against release SHA-256 `2f6347643487b12c8dda308d7a2e09f22387ad4fc1ea3ee55a9d802d28fd6c12`. Exact library/source hashes are in TRUE_MOTION_PROVENANCE.json.

## Validation

- Full Linux JVM suite: 2025 tests, 296 classes, zero failures; 50 environment assumptions. Main Kotlin/Java, APK and instrumentation builds passed.
- MuMu OpenGL and Graphite/Vulkan: Motion Blur and Shutter Blur extend beyond the current decoded-media quad in both fast and full modes (256–1984 visible pixels outside the quad, depending on mode). The test supplies a decoded video-frame texture and exercises actual native spatial rendering.
- Both GPU backends: native Parenting Helper weighting, Classic script callback, depth order, ambient light and same-frame IK retargeting passed.
- Original supplied parenting archive: 52 rotation/stretch cases per backend, no changed channels above 2/255; 160 bones, 80 rebindings and 240 actual Surface presentations per backend passed.
- Legacy depth intersections, transparency/camera and rig handles passed on both backends. Large text/standard stroke and all four Scale Assist axes passed. Compatible export, pinned IK/limits and ungroup regressions passed.
- Project 122: all seven media found. Imported parent/group alignment, stroke alignment, 3D particles and PNG/MP4 passed.
- Full port workflow passed: native grading, plugin commands/hooks, vector persistence, audio reactivity/BPM, particles, nested group editing/undo/retiming, frame-to-photo, GIF and camera/vector package round trip. The 60-frame viewport cache matched direct rendering exactly; invalidation, undo reuse, cleanup and the 2 GiB cap passed.
- Source audit, native/source hash verification, standalone depth tests and tool tests passed.

Reports are in workspace work/analysis/0191-validation, work/analysis/0191 and `503-*` reports in work/analysis/019-validation. The reported physical Mali/Xiaomi phone has not been retested. Desktop sources were updated by the merge but True Motion desktop binaries were not built. The user independently checked the 0.19.0 interface/build; this patch was checked through builds and native regression workflows.

The local Preview uses the local debug certificate and differs from the permanent GitHub release signer. No release was published. The tasks for 0.20.0, 0.21.0 and 1.00.0 remain in ROADMAP_RU.md.
