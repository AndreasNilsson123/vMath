# Thick lines (`vmath.lines`, experimental)

Thousands of styled polylines, drawn with as few calls as the context allows, from OpenGL 3.30 up. This page is the first part of roadmap item LINE-9: it says what exists (LINE-1 to LINE-4) and how to use it.
What it does not have yet is the cost of each strategy measured in a demo (`docs/DEMOS.md`, L1 to L3), the dynamic line set (LINE-5), simplification and clipping (LINE-6) and trails (LINE-7).

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

## What to know

- **Dashes are in world units**, measured along the polyline in double precision and reduced modulo the pattern before they are rounded, so the phase stays exact on long lines. For a constant length on the screen use `dashedInPixels` with the size of a pixel on the ground, and rewrite the style table when it changes (it is one buffer).
- **Width in world units** is pixels `width * u_worldToPixel / w` at the start of the segment, which is the size on the ground in an orthographic view.
- **A segment with an end at or behind the camera is dropped.** Clip the lines first, or keep them in front of the camera (roadmap LINE-6).
- **There is no anti-aliasing** in the geometry; the edge of a line is as sharp as the pixels. A coverage fringe in the shader is possible and not built.
- **Joins fill the outside of a corner** and the bodies overlap on the inside, like Java 2D's stroke does; the tests compare the covered area with Java 2D's `BasicStroke` for every cap, every join, the miter limit, closed polylines, dashes and random polylines.
- **The shader text has not been compiled or run.** It is a transliteration of `LineGeometry.vertex`, checked by reading and by `LineShaderModel`, which runs the same arithmetic from the same buffers. `ShaderCompileTest` compiles it with glslang at every version where a compiler is installed, and no compiler was installed on the machine the code was written on. It is the OpenGL form of the text: loose uniforms and varyings without locations. The Vulkan form is roadmap item GPU-13.
- **No geometry-shader strategy exists**: every OpenGL from 3.30 has instancing, and geometry shaders are slower on most drivers.
