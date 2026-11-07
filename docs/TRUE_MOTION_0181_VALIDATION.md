# True Motion 0.18.1 validation

Checkpoint before the AbsentMotion 5.0 integration. Base: 4.4.0; Android package: `app.truemotion`; version code: 10022.

## Anchored parenting regression

Reproduced the supplied `Новый проект 31 (1).alight` in the real Android renderer. It contains a rounded rectangle stretched to scale (1, 1.965694), an edge anchor, and a parented copy with animated rotation. Before the fix the copy became a diamond while rotating.

Ordinary 2D parenting now maps the child's anchor through the complete parent transform, then composes angle and width/height independently. Imported Alight origins, explicit skew/3D transforms, bone chains and inverse-bind matrices keep exact affine composition. Editor drawing matrices follow the same 2D rules. Rebinding artwork computes its displayed world transform before changing its coordinate frame.

`Parent018Probe` passed on OpenGL and Graphite/Vulkan in MuMu, using the original supplied archive. Per backend:

- 52 cases: original animation, parent and child rotations, width/height changes and mirrored scale.
- Maximum matrix error against an independently built unparented reference: 0.000140; normalized axis dot product below 0.000000066.
- No RGBA channel differed from the reference by more than 2 at 360 × 360.
- Editor handle matrices agreed within 0.000123.
- Moving the parent's anchor preserved the child's matrix exactly. Moving an animated child's anchor stayed within the editor's existing 1 px adaptive compensation tolerance (maximum 0.856263).
- 160 bones created, 80 rebindings, 240 preview frames presented to an actual Android Surface. Bind matrix error below 0.000111.

The test includes no external media and does not change user projects.

## Reported rig crash

The supplied Android 15 Xiaomi 2409BRN2CG / `pond` report identifies SIGSEGV in the vendor Mali worker `mali-cmar-backe`. The application render thread was presenting a Vulkan image (`vkQueueSignalReleaseImageANDROID`, `QueuePresentKHR`), rather than executing the bone solver. The user reported creating or binding bones before the crash.

This device on Android 15+ now prefers the checked OpenGL backend. Other Mali devices and earlier Android versions retain their existing selection. This is a driver workaround; the physical reported phone has not been retested. The emulator presentation/rebinding stress passed on both backends.

## Other checks

- 81 focused JVM tests passed: parenting, detach, anchor compensation, rig models/tools, ungroup, tracking/layout and GPU backend selection.
- Existing `Next018Probe` passed on both backends: constrained/pinned IK, independent bone angle, Parent Helper (including nested Keep Size), pixel-identical ungroup, invalid mesh rejection, failed decoder cleanup, six compatible exports total.
- `Depth017Probe` passed on both backends.
- Native Clang analysis: `styles.cpp` and `scene.cpp`, no warnings or errors.
- Native/source hash verification and source audit passed.
- Compat debug APK and instrumentation APK built successfully with the locally rebuilt renderer. Previous APK and native binary retained for rollback.
