# True Motion 0.19.0 — AbsentMotion 5.0 integration

Base: official `v5.0.0`, commit `3ada9bc0af5641fd4bdc92863cb567261701ae26`. Android package `app.truemotion`, version code 10023. Android/source integration completed on 2026-11-08; the user confirmed performing the interface check and build on 2026-11-10. Desktop sources are retained but True Motion desktop binaries were not built or verified.

The source tree now uses `android`, `shared/kotlin`, `shared/cpp`, `shared/assets` and `shared/res`. Upstream GPL-3.0 license and attribution are retained. Stock effect definitions match the v5 tag except the retained Classic Parent Helper. The v5 Parenting Helper has a separate runtime ID; the donor-port manifest preserves the old pinned baseline and records accepted upstream changes.

## Integration corrections

- Retained anchored, independent 2D parenting and exact affine rig/import matrices from 0.18.1.
- Kept the new depth/piece compositor separate from the old per-pixel `trueDepth3D` path. BSP cuts are skipped in that old path to avoid order-dependent double rasterization.
- Restored XYZ camera rotation in pre-5.0 projects independently of the depth-order switch.
- Manual and background viewport caches now both store checker-composited frames. Transparent PNG export remains separate; disk cache cap remains 2 GiB.
- Curved 2D reparenting samples the displayed trajectory and bakes auto-orientation once. Matte following supports both Parent Helper IDs.
- Fixed malformed legacy animation state JSON; preserved shader/script registrations and separate effect categories.
- Preserved the narrow Android 15+ Xiaomi `pond` GL preference for the reported Mali presentation crash. The physical phone has not been retested.

## Evidence

- Full Linux JVM run: 1954 tests, 277 classes, 0 failures; 27 environment assumptions. Focused cache tests passed after the final cache correction. Tool tests and native/source integrity checks passed.
- MuMu OpenGL and Graphite/Vulkan: native Parenting Helper weighting, legacy animation callback, v5 depth order, ambient model lighting and same-frame IK retargeting passed.
- Supplied `Новый проект 31 (1).alight`: 52 parent/rotation/stretch cases per backend. No RGBA channel differed from the independent reference by more than 2. 160 bones, 80 rebindings and 240 actual Surface presentations per backend passed.
- Both backends: constrained/pinned IK, nested Keep Size, pixel-identical ungroup, invalid mesh rejection, failed decoder cleanup and six compatible exports total passed. Large text/stroke and Scale Assist probes passed. Final legacy depth compositor passed order independence, transparency and scene camera checks on both backends.
- Project 122: 7 media, none missing; imported parent matrix error below 0.000028, imported group reference sizes 80/320/640 matched exactly. Standard stroke in offset groups and identity-effect targets matched exactly.
- Full Android port probe passed: native color grading, plugin commands/hooks, vector save/reopen, audio reactivity and exports, particles, nested group editing/undo/retiming, frame-to-photo and high-speed media playback.
- Viewport cache: 60 frames, cached frame mean channel error 0, edit invalidation and undo reuse, cancel/clear cleanup and 2147483648-byte cap checked. GIF color/alpha over two loops and save/reopen passed.
- Eight camera cases (X/Y/Z/XYZ, perspective/orthographic), neutral camera exactness, GL fallback and editable vector `.alight`/media round trip passed.

Reports are in the workspace `work/analysis/019-validation`. These results apply to MuMu and the stated fixtures, not all physical GPUs or every project. The complete historical feature-port roadmap remains unfinished.

The local APK uses the local debug certificate, which differs from the permanent GitHub preview certificate. This checkpoint was not published to GitHub.
