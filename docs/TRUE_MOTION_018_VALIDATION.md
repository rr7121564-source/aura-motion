# True Motion 0.18.0 Preview

## Editing

- Rigging now has rest-length editing, joint angle limits, independent angles,
  pose locks, an IK chain root, pose reset and artwork unbinding.
- Pinned bone tips have an animated target. Native rendering solves chains up to
  16 joints at the requested time, including random seeks and export.
- Bone rotation, position, scale and pinned IK targets use the existing transform
  keyframe curve editor. Select the channel in Bone settings, add keys, then open
  Easing in the rig toolbar. Curve presets, handles and curve copy/apply actions
  share the regular transform implementation.
- Add → Object / resource → Import PSD creates ordinary editable media layers
  and groups in the main editor. The importer retains offsets, layer order,
  folders, visibility, opacity and supported blend modes. It accepts PSD v1 RGB,
  8-bit channels. Text and smart objects use their saved raster representation.
- Ungroup is in the selected group's tool panel. A null transform controller
  preserves animated group transforms and anchors. Groups with compositing
  effects, non-normal timing or speed retain their group and show an explanation.
- Parent Helper is in Move / Transform and imports the corresponding Alight ID.
  It controls inherited rotation and scale separately and has keep-angle,
  keep-size and weighted modes. Its numerical scale-weight behavior has not been
  established as identical to every Alight version.
- Speed is available on shapes, text, drawings, groups, cameras, nulls, models
  and audio, using the existing timing/keyframe retiming implementation.
- Project creation, project settings and export have numeric FPS/resolution
  fields alongside presets. FPS values are positive integers. The previous
  4096-pixel composition-side limit is removed; actual export codec/memory
  support is still checked. Explicit custom export values survive preferences.
- Numeric volume accepts values above 150%. The drag slider retains its useful
  normal range. Mixing guards against nonfinite samples at extreme gain values.
- Scale Assist has a native transform handler with the donor's X, Y, X/Y,
  X/−Y and damping behavior, retaining its effect ID and project parameters.

## Stability audit

The pass covers Android Lint across the Java/Kotlin sources, Clang static analysis
of all 32 first-party native translation units in the engine build, JVM regression
tests and Android integration probes. It cannot establish that all possible
crashes are absent.

| Finding | Change |
| --- | --- |
| Java List `removeLast()` could resolve to an API unavailable below Android 15 | Use `removeAt(lastIndex)` |
| Failed ImageReader construction could leave a decoder thread running | Construct the reader first and clean up initialization failures |
| Image callbacks could enqueue frames during close | Synchronize close/enqueue and make close idempotent |
| Cancellation or failed frame presentation could release the held codec output twice | Transfer ownership before presentation |
| One failing decoder cleanup could skip other resources | Release each resource independently and make close idempotent |
| Native model uploads accepted malformed array sizes or nonfinite coordinates | Validate Kotlin parsing and JNI input before copying |
| Vector editor retained a destroyed Activity | Clear final Activity references and stop playback; bound configuration restoration |
| Vector dialog used raw Back-key interception | Use ComponentDialog's Back dispatcher and preserve the unsaved-work prompt |
| Export preview could retain its Surface | Release through the composable lifetime |
| Effect option thumbnails saved an index instead of the declared option value | Save `optionValues[i]` |
| Pending export replacement failed when a destination already existed on Windows | Atomic NIO replacement with a supported fallback |
| Oversized caption downloads were deleted while their stream was open | Delete after closing the stream |
| UI text resources were captured in callbacks without configuration observation | Use current Compose resources |

Clang reports one remaining warning in Skia's intrusive reference-count helper,
reached through `EngineImpl::interpolateVideo`. Review found strong `sk_sp`
references in the frame cache and local values throughout that path. This is
classified as a likely analyzer false positive, rather than a confirmed use after
free. No refcount suppression or ownership rewrite was introduced.

Large local font sizes previously shrank raster glyphs independently of their
vector stroke geometry. Large plain glyph runs now use canonical font outlines;
large color glyphs use normalized font units. Static and animated text share the
draw helper. This also keeps offscreen effect passes on the same plain glyph
geometry. Native image checks include 8 font/composition-size combinations,
identity effects and standard text strokes.

## Export mitigation

The Video export panel offers Compatible encoder mode. It transfers rendered
pixels through EncoderCopy and prefers a software video encoder. The affected
Android 14 `redwood` family uses the safe transfer path automatically. Recording a
new native video-export crash also selects compatible mode in saved preferences.

Codec configuration retries use fresh instances. Cleanup explicitly stops and
releases owned codecs. Native tombstone protobuf logs are decoded with the
crashing thread first, including signal, abort message, PCs and build IDs.

The Xiaomi/POCO phone from the supplied `HIDL DEAD_OBJECT` screenshots was not
available. Successful emulator exports validate the mitigation path, but do not
prove that the physical-device crash is fixed.

## Android checks

MuMu, Android 15, arm64 translation, OpenGL ES and Graphite Vulkan:

- Failed ImageReader creation leaked no image threads.
- JNI rejected nonfinite mesh vertices.
- Parent Helper preserved orbit position with locked angle/scale and weighted
  rotation/scale.
- The keep-size preset was also checked with rotated, nonuniformly scaled
  ancestors. Before the fix, a child scale of 1.2 became 0.79875; the fixed path
  preserves 1.2 and 0.8 on the child's axes.
- Three-joint pinned IK worst measured endpoint error was 0.0478 pixels.
- Joint limits and independent angle passed native checks.
- Group ungrouping produced identical pixels, maximum channel difference 0.
- Three 30-frame compatible MP4 exports succeeded on each backend, six total.

Manual UI checks on a 390-dp-wide portrait layout confirmed bone curve saving,
unchanged artwork keys, saved limits and pin targets, layered PSD import, ungroup
and undo, and the vector editor's Back confirmation/return flow. Tests used QA
projects rather than user projects.

Additional UI checks saved 137 FPS, a 2196×1235 composition, shape speed 2.5×,
group speed 1.75× and volume 1200% (gain 12). The supplied 608,992-byte user PSD
was inspected without modifying it; its layer-info count is one, so import cannot
recover separate source layers. The importer now reports single-layer files.

The full JVM pass ran 1,765 tests, with zero failures and 26 environment-dependent
assumptions. A subsequent focused pass includes the added Scale Assist import
regression. Android Lint passes with the existing baseline unchanged; it retains
general style/translation warnings and is not a zero-warning claim.

## Package

The local Preview uses the existing local debug certificate. It updates previous
local Preview builds. The GitHub release certificate has a different fingerprint,
so this APK does not install as an update over a GitHub-signed build.
`TRUE_MOTION_PROVENANCE.json` records the native binary and source hashes.
