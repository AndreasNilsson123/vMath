# Thick lines (`vmath.lines`, experimental)

Thousands of styled polylines, drawn with as few calls as the context allows, from OpenGL 3.30 up: the data model, the five ways to draw them, a dynamic set that uploads only what changed, trails, simplification, clipping and culling.
The demos `line-lab`, `line-styles` and `line-stream` ([DEMOS.md](DEMOS.md)) draw every tier and show the set; their cards have the numbers of a scripted run. What it does not have yet: a coverage fringe for anti-aliasing, an alpha along every segment, the Vulkan form of the shader text (roadmap GPU-13), and a check on any vendor but one NVIDIA GPU.

The library makes no graphics API call. You describe the context ([`GraphicsCapabilities`](GPU.md)), the library chooses a strategy and gives you the buffers, the draws and the shader text.

## The pieces

| Class | What it is |
|---|---|
| `LineStyle` | colour, width (pixels or world units), caps, joins, miter limit, a dash pattern (world units, up to eight numbers), a layer; immutable |
| `LineBatch` | many polylines in `double` precision, a table of the distinct styles, a bounding box per polyline, the **origin** that positions are made relative to, and `relativeViewProjection` for the matrix of a frame |
| `LineGeometry` | the geometry of one segment in screen pixels: 54 vertices (a body, a piece at the start, a piece at the end), the definition that the shaders repeat |
| `LineExpander` | the reference expansion of a whole batch to triangles, straight from the data |
| `CoverageRaster` | a software rasteriser (4 by 4 samples per pixel) for comparing results |
| `LineStrategy`, `LineRenderPlan` | the strategies, the choice, and for the chosen one: the shaders, the buffer layouts, `write` and `submission` |
| `LineShaderModel` | what the vertex stage computes, run on the CPU from the written buffers and draws |
| `LineGpu` | the two records in the buffers: a 48-byte segment and a 64-byte style |
| `LineSet` | polylines that are added, edited and removed while running, with stable handles, and `update`, which writes only what changed |
| `TrailBuffer` | the last positions of many moving tracks, drawn as polylines that fade with age |
| `LineSimplifier` | Douglas-Peucker and Visvalingam-Whyatt simplification, for any zoom |
| `LineClipper` | Liang-Barsky clipping of segments and polylines to a rectangle, a box or a frustum |
| `LineCulling` | culls the polylines of a set by their boxes with the frustum kernels of `vmath.spatial` |

## Using it

```java
GraphicsCapabilities caps = GraphicsCapabilities.openGl(major, minor, extensionNames);
LineRenderPlan plan = LineRenderPlan.choose(caps);            // or LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, caps)

LineBatch batch = new LineBatch();
batch.setOrigin(cameraX, cameraY, cameraZ);                   // near what is on screen; move it when batch.originTooFar(...) says so
batch.addPolyline(xyz, 0, pointCount, false, LineStyle.pixels(3f).withColor(0xFFCC33FF));

MemorySegment data = allocate(plan.dataBytes(batch)), styles = allocate(plan.styleBytes(batch));
DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 64);
plan.write(batch, data, styles, draws);                       // upload data and styles; the draws say what to draw

// each frame
float[] vp = new float[16];
batch.relativeViewProjection(viewProjectionInWorldCoordinates, vp);   // double precision, rounded once
// uniforms: u_viewProjection = vp, u_viewport = (width, height), u_worldToPixel = 0.5 * height * projection[1][1]
DrawSubmission how = plan.submission(draws);                  // write the draws in that form: see docs/GPU.md
```

Draw with face culling and depth writes off: the triangles have no fixed winding, and a line drawn with transparency shows the overlap of the bodies and joins of its own segments.

## The strategies

The best one that the context allows is chosen; any can be asked for by name if the context has what it needs. The table is generated from the code and kept equal to it by `DecisionTablesDocTest`.

<!-- decision-table:line-strategy -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `INDIRECT_DRAW_ID` | `INSTANCED_ARRAYS`, `MULTI_DRAW_INDIRECT`, `BASE_INSTANCE`, `SHADER_DRAW_PARAMETERS` | one indirect multi-draw; instanced segments; the style table indexed by the draw index |
| 2 | `INDIRECT_INSTANCE_STYLE` | `INSTANCED_ARRAYS`, `MULTI_DRAW_INDIRECT`, `BASE_INSTANCE` | one indirect multi-draw; instanced segments; the style index in every segment |
| 3 | `EXPANDED_MULTIDRAW` | `MULTI_DRAW`, `TEXTURE_BUFFERS` | one glMultiDrawArrays, no instancing; segments in a texture buffer read from the vertex index |
| 4 | `INSTANCED_LOOP` | `INSTANCED_DRAWS`, `INSTANCED_ARRAYS` | an instanced draw per run of equal style; instanced attributes moved by hand without a base instance |
| 5 | `HAIRLINE` | nothing | one-pixel line strips, colour only |
<!-- /decision-table -->

What the best strategy is for some contexts:

<!-- decision-table:line-strategy-by-context -->
| Context | Best strategy |
|---|---|
| OpenGL 3.3 | `EXPANDED_MULTIDRAW` |
| OpenGL 4.2 | `EXPANDED_MULTIDRAW` |
| OpenGL 4.3 | `INDIRECT_INSTANCE_STYLE` |
| OpenGL 4.5, with ARB_shader_draw_parameters | `INDIRECT_DRAW_ID` |
| OpenGL 4.6 | `INDIRECT_DRAW_ID` |
| Vulkan, no optional features | `INSTANCED_LOOP` |
| Vulkan, multiDrawIndirect and drawIndirectFirstInstance | `INDIRECT_INSTANCE_STYLE` |
| Vulkan, all three | `INDIRECT_DRAW_ID` |
<!-- /decision-table -->

How each is issued, in OpenGL terms (Vulkan has the same calls under other names):

| Strategy | Bind | Issue |
|---|---|---|
| `INDIRECT_DRAW_ID` | the segments as instanced attributes (`segmentAccess().vertexLayout()`, divisor 1); the style table, one entry per draw | `glMultiDrawArraysIndirect(GL_TRIANGLES, ...)` over `draws.writeIndirect(...)` |
| `INDIRECT_INSTANCE_STYLE` | the segments as instanced attributes; the style table, one entry per distinct style | the same call |
| `EXPANDED_MULTIDRAW` | the segments as a texture buffer (`RGBA32UI`) on the unit `SEGMENT_SLOT`, the styles by `styleAccess()`; no vertex attributes | `glMultiDrawArrays(GL_TRIANGLES, firsts, counts, n)` from `copyFirsts` and `copyCounts`, or the indirect form if the context has it |
| `INSTANCED_LOOP` | the segments as instanced attributes | per draw `glDrawArraysInstanced(GL_TRIANGLES, 0, 54, instances)`; without a base instance rebind the attributes at `baseInstance * 48` bytes first |
| `HAIRLINE` | the vertices of `hairlineLayout()` | `glMultiDrawArrays(GL_LINE_STRIP, ...)`; width, caps, joins and dashes are ignored |

## A set that changes

A `LineBatch` is built once. For lines that appear, move and disappear every frame use `LineSet`, which keeps the same buffers and rewrites only the records that changed:

```java
LineSet set = new LineSet(plan, 200_000);                       // room for 200000 segments (or vertices, for hairlines)
MemorySegment data = allocate(set.dataBytes()), styles = allocate(set.styleBytes());
DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 1024);

long h = set.add(xyz, 0, n, false, style);                      // a handle that stays valid until the polyline is removed
set.set(h, newXyz, 0, n, false);                                // edit: rewritten in place if the number of points is the same
set.remove(h);                                                  // frees its records for the next polyline

set.update(data, styles, draws);                                // writes what changed, rebuilds the draws and the style table
for (int i = 0; i < set.dirtyRangeCount(); i++) {
    upload(data, set.dirtyOffset(i), set.dirtyLength(i));       // only these bytes go to the GPU
}
```

- The records of a polyline live in a slot that a free-list allocator hands out. When the free space is in pieces too small for a new polyline, `compact()` moves the polylines together in drawing order (it is done by itself when that lets an addition succeed); when the set is full `add` throws and `grow(units)` makes room. Both rewrite only what moved.
- Polylines of equal style that sit next to each other in the buffer are one draw, so a compacted set has the fewest draws. The draw list and the style table are small and are written completely at every update.
- The data buffer is a mirror of what is on the GPU: use the same buffer for every update, or call `invalidate()` when it is a new one. Moving the origin (`setOrigin`) rewrites everything.
- The same pixels in every strategy: the tests compare each strategy, after random additions, edits, removals, compactions and growth, with the reference expansion of the same polylines, with the GPU copy fed through the dirty ranges alone.
- Edits that keep the number of points, updates and removals allocate nothing (`DrawPathAllocationTest`); a change of style, an addition that needs a bigger array, and a growth do.

## Trails

```java
TrailBuffer trails = new TrailBuffer(1000, 64, 8);              // tracks, positions per track, slices of age
int track = trails.addTrack();
trails.push(track, x, y, z, timeSeconds);                       // at every update of the track; overwrites the oldest
trails.updateLines(set, track, nowSeconds, 60.0, LineStyle.pixels(2f).withColor(0x00FF00FF));
```

A trail is drawn as one polyline per slice of age: the newest has the colour of the style, each older one is more transparent, and positions older than the maximum age are left out. It is a fade in steps and not a gradient along every segment: a gradient needs an alpha at each end of a segment, which the 48-byte record does not have. Use solid styles, because a dash pattern restarts in every slice.

## Simplifying, clipping and culling

- **`LineSimplifier`**: `douglasPeuckerImportance` (or `visvalingamImportance`) gives every point an importance once, when a polyline is made; `copySelected` then keeps the points above a threshold in one pass without allocation, exactly what the algorithm gives for that tolerance. The threshold for a zoom is `worldTolerance(pixels, pixelsPerWorldUnit)`. Douglas-Peucker keeps the outline within the tolerance in three dimensions (tested for every removed point); Visvalingam removes the point that changes the area least and looks smoother.
- **`LineClipper`**: `clipRectangle` (Liang-Barsky), `clipBox` (its three-dimensional form) and `clipPlanes` (Cyrus-Beck) clip one segment in double precision without allocating. `frustumPlanes` makes the six planes of a view-projection matrix for any depth range, and a segment that reaches behind the camera, which the strategies drop, is clipped to the near plane and can then be drawn. `clipPolyline` cuts a polyline into pieces and tells how far along the original each starts, so that the caller can continue a dash pattern.
- **`LineCulling`** culls the polylines of a `LineSet` by their boxes with the frustum kernels of `vmath.spatial` (the vector kernel where the machine has one); `set.update(data, styles, draws, visible)` then leaves the others out of the draws (their records stay written, so one that becomes visible needs no upload). The margin must cover half the width of the line in world units. This is the CPU form: the compute culling of `vmath.gpucull` decides per instance of a draw and does not apply to a polyline whose segments are the instances; `LineSet.fillBounds` gives the boxes for a culling pass of your own.

## What to know

- **Dashes are in world units**, measured along the polyline in double precision and reduced modulo the pattern before they are rounded, so the phase stays exact on long lines. For a constant length on the screen use `dashedInPixels` with the size of a pixel on the ground, and rewrite the style table when it changes (it is one buffer).
- **Width in world units** is pixels `width * u_worldToPixel / w` at the start of the segment, which is the size on the ground in an orthographic view.
- **A segment with an end at or behind the camera is dropped.** Clip the lines first with `LineClipper.clipPlanes` and the planes of `frustumPlanes`, or keep them in front of the camera.
- **A uniform block holds 256 styles** (`LineRenderPlan.STYLE_TABLE_UNIFORM_LENGTH`): a context with neither storage buffers nor texture buffers cannot draw more distinct styles, and `write` and `update` throw instead of truncating. Bind the whole block even when fewer styles are used.
- **There is no anti-aliasing** in the geometry; the edge of a line is as sharp as the pixels. A coverage fringe in the shader is possible and not built.
- **Joins fill the outside of a corner** and the bodies overlap on the inside, like Java 2D's stroke does; the tests compare the covered area with Java 2D's `BasicStroke` for every cap, every join, the miter limit, closed polylines, dashes and random polylines.
- **The shader text is checked on a driver.** `LineGpuCheck` (the samples module, `./gradlew -Psamples :vmath-samples:lineCheck`) compiles, links and runs the text of every strategy at every GLSL version from 3.30 to 4.60, as the capabilities of that version choose it, on four scenes (styles in a storage block, a texture buffer or a uniform block as the context allows; a grid of cells with a colour each, overlapping lines, a perspective view with widths in world units, and lines four million units from the origin), and compares the pixels with the coverage that the reference expansion computes. All 177 cases agree on an NVIDIA GeForce RTX 3060 (driver 546.30, Windows). That is one vendor. `LineShaderModel` runs the same arithmetic on the CPU from the same buffers, and `ShaderCompileTest` also compiles the text with glslang where that is installed (it was not on the machine this was written on). The text is the OpenGL form: loose uniforms and varyings without locations. The Vulkan form is roadmap item GPU-13.
- **No geometry-shader strategy exists**: every OpenGL from 3.30 has instancing, and geometry shaders are slower on most drivers.

## What each tier costs

Measured by `LineGpuCheck --bench` (the line lab measures the same thing in the window, with the scripted view: 2.0 to 3.1 ms of GPU time for the five thick tiers and the same ordering, `docs/DEMOS.md`) (`./gradlew -Psamples :vmath-samples:lineCheck --args="--bench"`) on an NVIDIA GeForce RTX 3060 Laptop GPU, driver 546.30, Windows, at 1920 by 1080: 5000 polylines of 64 points (315000 segments) in 8 styles, widths of 1 to 6 pixels, runs of 7 polylines of one style. "GPU time" is the median of 60 frames of a `GL_TIME_ELAPSED` query, "write" the mean of 20 calls of `LineRenderPlan.write` on one thread. The older contexts are the same driver asked for the calls and the shader text of that tier.

| Context | Strategy | Submission | Draws | Calls | Data (MB) | Styles (KB) | Write on the CPU (ms) | GPU time per frame (ms) |
|---|---|---|---|---|---|---|---|---|
| OpenGL 4.6 | `INDIRECT_DRAW_ID` | MULTI_DRAW_INDIRECT | 715 | 1 | 14.42 | 44.69 | 8.52 | 3.19 |
| OpenGL 4.6 | `INDIRECT_INSTANCE_STYLE` | MULTI_DRAW_INDIRECT | 715 | 1 | 14.42 | 0.50 | 6.89 | 2.63 |
| OpenGL 4.6 | `EXPANDED_MULTIDRAW` | MULTI_DRAW_INDIRECT | 715 | 1 | 14.42 | 0.50 | 6.93 | 2.14 |
| OpenGL 4.6 | `INSTANCED_LOOP` | MULTI_DRAW_INDIRECT | 715 | 1 | 14.42 | 0.50 | 6.33 | 2.68 |
| OpenGL 4.6 | `HAIRLINE` | MULTI_DRAW_INDIRECT | 5000 | 1 | 4.88 | 0.00 | 3.49 | 0.16 |
| OpenGL 4.2 | `INSTANCED_LOOP` | DRAW_LOOP | 715 | 715 | 14.42 | 0.50 | 6.36 | 2.64 |
| OpenGL 3.3 | `EXPANDED_MULTIDRAW` | MULTI_DRAW_CLIENT | 715 | 1 | 14.42 | 0.50 | 6.14 | 2.13 |
| OpenGL 3.3 | `INSTANCED_LOOP` | DRAW_LOOP | 715 | 715 | 14.42 | 0.50 | 5.93 | 2.60 |
| OpenGL 3.3 | `HAIRLINE` | MULTI_DRAW_CLIENT | 5000 | 1 | 4.88 | 0.00 | 1.28 | 0.16 |

What the numbers say, for this GPU and this scene only:

- **Memory** is 48 bytes per segment in every thick strategy (14.4 MB here) and 16 bytes per point for hairlines (4.9 MB). The style table is 64 bytes per style, except for `INDIRECT_DRAW_ID`, where it has one entry per draw (45 KB here) and is rewritten with every set of draws.
- **Calls**: one for every strategy that has a multi-draw (indirect, or `glMultiDrawArrays` from 3.30); 715 for the loop of the instanced strategy on a context without one. On the GPU the loop costs about what the multi-draw does; its CPU cost, 715 calls instead of one, is not in this table.
- **GPU time**: vertex pulling (`EXPANDED_MULTIDRAW`) was the fastest and the draw-index strategy (`INDIRECT_DRAW_ID`) the slowest here, because it reads the style of every vertex from a storage buffer by the draw index while the others take the style index from the instance. The order of the decision table is "the best the features allow" and does not come from these numbers; on this GPU the 3.30 tier is as fast as the 4.60 one. Measure on your target before forcing a strategy.
- **CPU time to write** is 6 to 9 ms for 315000 segments on one thread (about 20 to 27 ns a segment), and 1 to 3.5 ms for hairlines.
- **The dynamic set**: editing 100 of the 5000 polylines each frame uploads about 292 KB as dirty ranges instead of 14.4 MB (2%), and one `update` takes about 1.5 ms on the CPU.

## Issuing the draws in Vulkan terms

Vulkan has the same calls under other names; the Vulkan form of the shader text is roadmap item GPU-13. The data and the draws are the same ones.

| Strategy | Vulkan |
|---|---|
| `INDIRECT_DRAW_ID` | the segments as a vertex buffer with an input rate of instance; the style table as a storage buffer indexed by the draw index (feature `shaderDrawParameters`); `vkCmdDrawIndirect` with the number of draws as `drawCount` (features `multiDrawIndirect` and `drawIndirectFirstInstance`) over the commands of `draws.writeIndirect(...)` |
| `INDIRECT_INSTANCE_STYLE` | the same without the draw index: the style index is in each segment; `multiDrawIndirect` and `drawIndirectFirstInstance` |
| `EXPANDED_MULTIDRAW` | the segments as a uniform texel buffer (format `R32G32B32A32_UINT`) or a storage buffer, read with the vertex index divided by 54; one `vkCmdDraw` per run (Vulkan has no `glMultiDrawArrays`), or `vkCmdDrawIndirect` with `multiDrawIndirect` |
| `INSTANCED_LOOP` | the segments as a vertex buffer with an input rate of instance; one `vkCmdDraw` of 54 vertices with the instance count of the run per run: Vulkan has the first instance in every draw, so nothing is moved by hand |
| `HAIRLINE` | the line strip topology, a `vkCmdDraw` per polyline |

The thick strategies need no face culling and no depth writes in the pipeline (see "Using it"), a blend state if the colours have alpha, and the three uniforms as a push constant block.
