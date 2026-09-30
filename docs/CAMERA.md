# Camera and rendering maths (`vmath.camera`)

## `Cameraf` / `Camerad`

A perspective camera is seven values: position, orientation, `fovy`, `aspect`, `near`, `far` and a `DepthRange`. Everything else is
derived on demand, so a camera can never be internally inconsistent. It is a template, so `Camerad` exists for world-scale positions.

```java
Cameraf cam = Cameraf.lookingAt(eye, target, Vec3f.UNIT_Y, 1.0f, 16f / 9f, 0.1f, 500f, DepthRange.ZERO_TO_ONE);
Mat4f vp = cam.viewProjection();
Frustumf frustum = cam.frustum();                       // ready for CullContext
Vec3f ndc = cam.project(worldPoint);                    // x, y in [-1, 1] inside the view
Vec3f px = cam.toScreen(worldPoint, 1920, 1080);        // pixels, origin top-left, y down
Rayf ray = cam.pickRay(mouseX + 0.5f, mouseY + 0.5f, 1920, 1080);
Vec3f world = cam.worldPositionFromDepth(ndcX, ndcY, depthValue);
```

Conventions: the camera looks down its local **-Z**, +Y up, +X right; screen space has its origin at the **top-left**; NDC has +y up.
`pickRay` is built analytically from the field of view, so it also works with infinite projections.

`DepthRange` selects the clip-space depth convention: `NEGATIVE_ONE_TO_ONE` (GL default), `ZERO_TO_ONE` (Vulkan, D3D) and
`REVERSED_ZERO_TO_ONE` (near maps to 1, always infinite far). A `far` of `+Infinity` gives an infinite projection in the first two too.
`linearizeDepth` and `viewPositionFromDepth` are the exact inverses for whichever convention the camera uses.

### Camera-relative rendering (double precision worlds)

At 6.4 million units from the origin a float can only represent multiples of 0.5, so projecting float world coordinates is
visibly wrong. Keep the world in double and render relative to the camera:

```java
Camerad cam = ...;                                   // world position in double
Cameraf relative = cam.cameraRelative();             // same orientation, position at the origin
Vec3f local = objectPosition.relativeTo(cam.position());   // subtract in double, then narrow
Vec3f ndc = relative.project(local);
```

`CameraPrecisionTest` measures this: camera-relative error stays around 1e-5 NDC units while narrowing first is off by orders of magnitude.

### Temporal anti-aliasing

`Jitter.offset(frame, 8)` gives a Halton (2, 3) sub-pixel offset in [-0.5, 0.5) pixels, repeating every 8 frames. Pass it to
`cam.jitteredProjection(jx, jy, width, height)`: every projected point shifts by exactly `(2 jx / width, 2 jy / height)` in NDC,
whatever its depth. For motion vectors keep last frame's view-projection and use `cam.reprojection(previousViewProjection)`, which
maps this frame's clip space to the previous frame's.

## Cascaded shadow maps (`Cascades`)

```java
List<Cascades.Cascade> cascades = Cascades.fitAll(cam, 4, 0.7f, 200f, lightDirection, 2048,
        /* stabilize */ true, /* casterDistance */ 50f, DepthRange.ZERO_TO_ONE);
for (Cascade c : cascades) {
    // render casters visible in c.frustum() with c.viewProjection(); sample later with c.textureMatrix()
}
```

- **Splits.** `splitDistances(near, far, count, lambda)` blends uniform (0) and logarithmic (1) spacing, the practical split scheme.
- **Stable fit.** The cascade is built around the slice's bounding **sphere** (unchanged when the camera turns) and its origin is snapped to
  whole texels (so a static shadow edge never slides between texels as the camera creeps). It costs a slightly larger volume than a tight
  fit (`stabilize = false`), which fits the slice's corners exactly.
- **Casters.** `casterDistance` extends the volume toward the light so tall geometry outside the view slice still lands in the map.
- **Culling.** `Cascade.frustum()` feeds the culling pipeline, one `CullContext` per cascade.
- **Sampling.** `textureMatrix()` maps world space to `u, v` in [0, 1] and depth in the convention's own range (no y flip).

Property tests check that every cascade contains its slice in all three depth conventions, that a stabilized cascade keeps its size when the
camera moves or turns, and that moving the camera advances the map origin in whole texels only.

## Not covered yet

Orthographic and off-center cameras (use `Mat4f.ortho`/`frustum` directly), oblique near-plane clipping, stereo/VR projections, cubemap
face matrices, clustered-light froxel math, and the physical camera model (exposure, focal length). All are in `docs/ROADMAP.md`.

## Cube map faces

`CubeFaces` describes the six faces of a cube map in the GL/Vulkan orientation (layer order +X, -X, +Y, -Y, +Z, -Z): `direction`,
`up`, `view(face, position)`, the shared 90 degree aspect-1 `projection(near, far, depth)`, `viewProjection`, `frustum` and
`faceOf(direction)` (the face a cube lookup reads). Use the frusta to cull per face, or `LightCull.cubeFaces` (see
`docs/CULLING.md`) to get a per-object face mask in one pass. With `REVERSED_ZERO_TO_ONE` the far plane is infinite.

## Clip-space conventions (`ClipSpace`)

The graphics APIs disagree about clip space in two ways, and a projection matrix has to match the one you render with:

| `ClipSpace` | NDC depth, near to far | NDC `y` |
|---|---|---|
| `OPENGL` (default GL) | -1 to 1 | up |
| `VULKAN` | 0 to 1 | **down** |
| `D3D` (Direct3D, Metal; also GL with `glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)`) | 0 to 1 | up |

`Mat4f.perspective`, `perspectiveInfinite`, `perspectiveReversedZ`, `ortho` and `frustum` each have an overload taking a `ClipSpace`, which is what
to use instead of the boolean `zZeroToOne` overloads (those cannot express the Vulkan Y flip and remain for compatibility). The Vulkan variants are the
depth-0-to-1 matrix with its output `y` mirrored (`Mat4f.flipY()`). Reversed-Z needs a [0, 1] depth range, so `ClipSpace.OPENGL` is rejected there.
`DepthRange.of(space)` gives the depth convention to pass to `Frustumf.fromViewProjection`; a Y flip only swaps the top and bottom planes, so culling is unchanged.

**What is not converted:** `Cameraf` and the screen-space helpers built on it (`toScreen`, `pickRay`, `project`/`unproject`) still assume a y-up NDC. With
a Vulkan matrix from `Mat4f.perspective(..., ClipSpace.VULKAN)` use the matrix directly for rendering and flip `y` yourself when converting NDC to pixels.
Teaching `Cameraf` about `ClipSpace` would change its record components, which is an API break, so it is a recorded follow-up.
