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

## Cube map faces

`CubeFaces` describes the six faces of a cube map in the GL/Vulkan orientation (layer order +X, -X, +Y, -Y, +Z, -Z): `direction`,
`up`, `view(face, position)`, the shared 90 degree aspect-1 `projection(near, far, depth)`, `viewProjection`, `frustum` and
`faceOf(direction)` (the face a cube lookup reads). Use the frusta to cull per face, or `LightCull.cubeFaces` (see
`docs/CULLING.md`) to get a per-object face mask in one pass. With `REVERSED_ZERO_TO_ONE` the far plane is infinite.

## Planar reflections, portals, stereo and dual paraboloids

`PlanarViews` (experimental) covers planar reflections and portals. `reflection(plane)` is the mirror matrix (its own inverse, determinant -1, so draw with the
triangle winding flipped), and `reflectedView(view, plane)` the mirrored camera. `obliqueNearPlane(projection, clipPlane, depth)` replaces the near plane of a
projection by a plane in view space (Lengyel's oblique frustum clipping, for all three depth conventions and with a Y flip), so geometry behind the mirror is
clipped by the hardware: the side planes are untouched, the far plane keeps its corner on the kept side, and depth precision degrades the more the plane is tilted
against the view direction. It throws if the camera is not on the clipped side or if the plane clips the whole frustum. `portalTransform(source, destination)` and
`portalView(view, source, destination)` move the camera through a portal: portal frames are portal-to-world matrices (+Z is the side the portal is seen from) and the
destination is entered with a half turn about the up axis. Measured in `PlanarViewsTest`: in every depth convention, with and without a Y flip, 60 random planes
and about 15 000 random points per case pass the new near plane exactly when they are on the positive side of the plane, and the far-plane corner stays at its depth.

`Stereo` (experimental) builds the pieces of a head-mounted display: `eyeView(headView, ipd, eye)`, an asymmetric `projection` from the four half angles an
HMD runtime reports (and `projectionReversedZ`, infinite far), and `offAxis(halfWidth, halfHeight, screenDistance, eyeOffset, ...)` for a physical screen. The tests
check that symmetric angles give the ordinary perspective, that the eye positions are `+-ipd/2` along the head's X axis, and that for the off-axis projection a point on
the screen plane lands at the same place in both eyes (no parallax at the screen). There is no combined frustum for culling both eyes once; cull per eye or with a
union you build yourself.

`DualParaboloid` (experimental) maps a direction to the unit disc of a hemisphere image, `(x, y) / (1 - z)`, with the distance from the centre as depth
(`project`, `direction`, `view`, `hemisphereOf`, `halfSpace` for culling, and the GLSL of the vertex transform). The mapping is not projective, so geometry must be
tessellated finely and the seam needs a small overlap; against a cube map it costs two renders instead of six. Tested: projection and inverse agree to 2e-5 over
15 000 random points, the rim of the disc is the equator, and every direction belongs to the half-space of exactly the hemisphere `hemisphereOf` names. The GLSL is
text that is not compiled here.

## Not covered yet

Orthographic and off-center cameras (use `Mat4f.ortho`/`frustum` directly), the physical camera model (exposure, focal length), a single culling frustum for both
eyes, and the quality of a dual-paraboloid shadow map compared with a cube map (not measured). All are in `docs/ROADMAP.md`.

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

## Clustered and tiled lighting (`ClusterGrid`, `ClusterLights`)

`ClusterGrid` cuts the view frustum into tiles in x and y and exponential slices in depth: `boundary(k) = near * (far / near)^(k / slices)`, so a fragment finds its slice
with one logarithm, `floor(ln(depth) * sliceScale + sliceBias)`. `clusterOf(pixelX, pixelY, depth)`, `clusterOfViewPosition`, `sliceOfNdcDepth(camera, ndcDepth)` (all three
depth conventions, finite or infinite far plane, through `Cameraf.linearizeDepth`) and `bounds(cluster)` (the view-space box around a cluster, for a compute shader or the CPU
assignment) are the arithmetic; `glslLookup()` returns the matching GLSL (`clusterIndex(fragCoord, viewDepth)` with the grid constants filled in). Tile rows count in the
direction pixel coordinates run (`yDown` for Vulkan and D3D window coordinates). The index is `(slice * tilesY + row) * tilesX + column`. Perspective only.

`ClusterLights` is the CPU reference of the light assignment: point and spot lights in view space, assigned to the clusters they can reach as a compact index list
(`offset(cluster)`, `count(cluster)`, `lightAt(cluster, i)`, ascending light order), with `writeRanges` (`uvec2(offset, count)` per cluster) and `writeIndices` for the two storage
buffers a fragment shader reads, and `gl.ClusterLight` (three `vec4`s, 48 bytes) as the light record. For every light the columns, rows and slices it can touch are found first (screen extent of the
bounding sphere by the tangent-angle method, depth extent directly) and only those clusters are tested: a sphere against the cluster box for a point light, the bounding sphere of
the cone and then the cone against the sphere around the box for a spot light. `assignTiled` is the tiled variant: a single-slice grid whose depth range per tile (from a depth
pre-pass) replaces the slice, which drops lights in front of or behind everything in the tile. A light at a NaN position is listed for every cluster (NaN is not a separation) and never
dropped.

**Tested.** The grid: boundaries and the shader formula for the slice, the depth conventions, that a view-space point lies inside the box of the cluster it maps to (both pixel
directions, partial edge tiles, four viewport sizes, three tile sizes), that the pixel route and the view route agree, and that tiles cover the screen. The assignment against an
**exact oracle** for point lights (the sphere against the true frustum slice: inside, or the distance to its twelve face triangles): 648 418 pairs touch exactly, **0 are missing**, and
651 939 are listed, **0.5% extra**. For sampling oracles (random points inside a light and inside the frustum must be in a cluster that lists the light) on point and spot lights, over seeds
1 to 4. For spot lights, sampling the slices (96 points each) found 13 111 pairs where the assignment lists 24 764, none missing, so **at most 47% of the spot pairs are unnecessary**
(sampling only finds a lower bound, so the true figure is lower; the cone test against the sphere around the box is the loose part). The six-plane test of the sphere against the cluster's own
side and depth planes was tried as a tighter second test: it removed 320 of 650 000 pairs and was dropped.

**Measured** (`ClusterLightBench`, 1920 x 1080, 64-pixel tiles, 24 slices = 12 240 clusters, lights scattered through the frustum, JDK 25, one machine; the thread-level allocation contract test
shows 0 bytes per call, the 0.2 to 2 KB/op that `-prof gc` prints is the JVM's background allocation divided by long operations):

| Lights | range scale 0.25 (typical), points | range scale 0.25, 25% spots | range scale 1 (stress), points | range scale 1, 25% spots |
|---|---|---|---|---|
| 256 | 3.5 ms | 3.2 ms | 14.6 ms | 12.2 ms |
| 1 024 | 11.5 ms | 11.6 ms | 61.7 ms | 55.4 ms |
| 4 096 | 57.9 ms | 56.0 ms | 238.8 ms | 215.7 ms |

In the stress scene every light touches about 2 500 clusters (10.1 million pairs for 4 096 point lights: about 22 ns per listed pair, all passes). A single visiting pass that records
(cluster, light) pairs and counting-sorts them replaced a count pass and a fill pass and made it 2.2 times faster (4 096 lights, range 1: 488 ms before, 223 to 239 ms after), as did
precomputing the per-tile slopes. This is a reference and an oracle, not a production path: the assignment belongs in a compute shader, one thread per light or cluster.

GLSL sketch of the assignment pass for one cluster (the cluster boxes from `fillBounds` uploaded as `clusterBounds[]`; **not compiled or run here**, the Java above is what it was
written from):

```glsl
// one invocation per cluster: test every light against the box, append survivors
vec3 lo = clusterBounds[c].min, hi = clusterBounds[c].max;
for (uint l = 0u; l < lightCount; ++l) {
    vec4 pr = lights[l].positionRange;                  // view space
    vec3 d = max(max(lo - pr.xyz, pr.xyz - hi), 0.0);
    if (dot(d, d) <= pr.w * pr.w) { clusterLightIndices[base + n++] = l; }   // plus the cone test for spots
}
```
