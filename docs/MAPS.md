# Maps (`vmath.geo` and `vmath.map`, experimental)

The pieces of a 2D moving map, for any use: a navigation display, a ship plot, a survey, a game's strategic view. Geodesy and projections on the ellipsoid, a view that follows a position, shapes sampled for the screen, tiles, symbols, label placement, filled areas, terrain shading and a viewshed, units and text, and display palettes. The library makes no graphics API call and ships no symbols, fonts, tiles or colour schemes: it gives you data, buffers, draws and shader text, and the checks that the shaders do what the CPU models say.

Where to start: [COOKBOOK.md](COOKBOOK.md) section 6 has the view, the shapes, the tracks and the terrain as short runnable recipes. The lines are in [LINES.md](LINES.md), the capability machinery in [GPU.md](GPU.md), the orthographic camera in [CAMERA.md](CAMERA.md).

## The pieces

| Class | Package | What it is |
|---|---|---|
| `Ellipsoid`, `Geodesy`, `GeodesicLine` | `vmath.geo` | geodesics on the ellipsoid (direct, inverse, a line you can walk), spherical forms with a stated error, rhumb lines, cross-track distance, clipping of a geodesic and a circle to a box |
| `MapProjection` and `WebMercatorProjection`, `TransverseMercator`, `Utm`, `Mgrs`, `LambertConformalConic`, `PolarStereographic`, `AzimuthalEquidistant` | `vmath.geo` | forward, inverse, scale and convergence at a point, in double precision |
| `Units`, `GeoFormat` | `vmath.geo` | exact unit constants and conversions; latitude, longitude, bearing, range and speed text, and parsing of positions |
| `TerrainRgb` | `vmath.geo` | the codecs of elevation tiles stored as colours (older than this layer) |
| `MapView2d` | `vmath.map` | centre, scale, orientation, offset of the centre, viewport; geographic to window and back; bounds; the camera-relative matrix for any `ClipSpace`; the scale bar |
| `MapShapes` | `vmath.map` | circles, arcs, sectors, legs, routes, corridors and range rings sampled to a tolerance in pixels; feeds `LineBatch`, `LineSet` and `AreaBatch` |
| `TileGrid`, `FlatTileSelector` | `vmath.map` | tile pyramids (XYZ, TMS, any rectangle of any projection) and the selection of the tiles that cover a view, with the hysteresis and the budget |
| `SymbolAtlas`, `SymbolBatch`, `SymbolStrategy`, `SymbolRenderPlan`, `SymbolShaderModel`, `SymbolGpu` | `vmath.map` | billboard symbols: an atlas packed with `RectPacker`, the data, the strategies, the shaders, buffers and draws, the CPU model |
| `Declutter` | `vmath.map` | label and symbol placement as data: greedy by priority, candidate offsets, stable between frames |
| `AreaStyle`, `AreaBatch`, `AreaStrategy`, `AreaRenderPlan`, `AreaShaderModel` | `vmath.map` | filled polygons with holes, hatch, cross-hatch and dot patterns evaluated by the shader |
| `TerrainGrid`, `Hillshade`, `ColorRamp`, `TerrainShading`, `TerrainShader`, `Viewshed` | `vmath.map` | heights, hillshade, slope and aspect, colour ramps (also relative to an altitude), the CPU and GPU colouring of a tile, a viewshed |
| `DisplayPalette`, `ViewportInset` | `vmath.map` | day, night and night-vision colour maps; the viewport and scissor rectangles of an inset |

## Geodesy and projections

`Geodesy.inverse` and `direct` solve the two geodesic problems on any `Ellipsoid` (WGS 84, GRS 80, Airy 1830, Clarke 1866 are given) by the method of the auxiliary sphere with the integrals evaluated by Gauss-Legendre quadrature; near-antipodal pairs and the poles are handled. Angles are radians and lengths metres.

What has been checked, and against what, so that the claim is not a memory of the formulas:

- the **direct** problem against a numerical integration of the geodesic equations (an ODE solver that shares nothing with the series): within 5 micrometres on random, short, polar and equatorial cases;
- the **inverse** problem by solving it and then the direct problem from the answer: within 10 micrometres, including near-antipodal pairs; and a published reference vector (Flinders Peak to Buninyong) within 2 mm;
- the **spherical** forms against the ellipsoidal ones on random points all over the globe: distance within 0.6 percent (measured 0.55) and initial bearing within 0.4 degrees (measured 0.19); the constants `Geodesy.SPHERICAL_DISTANCE_ERROR` and `SPHERICAL_BEARING_ERROR` are those bounds;
- **Transverse Mercator** (a Krueger series of order 6) against the EPSG guidance example, against Redfearn's series, against the meridian arc computed by quadrature, and for conformality; **Lambert** against the EPSG 9802 example; **polar stereographic** against its published numbers; round trips of every projection;
- **UTM** zones with the exceptions of Norway and Svalbard and the band letters; **MGRS** for the UTM area only. The polar (UPS) letters are **not implemented** and `Mgrs` refuses them, because no reference was available to check them against.

Cost (random pairs on the whole globe, one thread, the development machine, after warm-up, `Timing` scratch program run once): `inverse` 9 microseconds, `direct` 2 microseconds, `sphericalDistance` 0.08 microseconds. They are not tuned; a map that needs thousands of geodesics a frame should use the spherical forms (error above) or `GeodesicLine` to walk one line.

`MapProjection.scale` and `convergence` have a numerical default (a Richardson-extrapolated Jacobian) that every projection of the library overrides where a closed form is known; the convention is *true bearing minus grid bearing*, positive east of the central meridian in the northern hemisphere.

## The view

`MapView2d` is immutable; each `with...` gives a new one, so animating a zoom is making a view per frame. The scale is ground metres per pixel (`withMetersPerPixel`) or a range to the top edge (`withRange`). Orientations: north up, course up, heading up, any bearing up. `withCenterOffset` moves the centre inside the window (an own position near the bottom). `toScreen` and `toGeographic` convert in double; `bounds` gives the geographic box of the window including its corners and edges (the window of a polar view contains the pole and has all longitudes, and says so).

`viewProjection(ClipSpace, originX, originY, out)` writes the matrix that maps projected metres *relative to an origin you choose* (usually the centre) to clip space, in the clip space of your API (y down and depth 0 to 1 for Vulkan). Buffers written relative to the same origin keep single precision exact to the pixel for a map of a country: a UTM easting of 500 000 does not fit a float to better than 0.03 m, and this is why every `write` takes the origin.

## Tiles

`TileGrid` describes a pyramid: zoom 0 is one tile, every zoom has twice as many along a side, over the rectangle of any projection, numbered XYZ (row 0 north) or TMS. `FlatTileSelector` selects the tiles that cover a view at the pixel density it needs, with a slack and a hysteresis so the zoom does not flap, a budget that lowers the zoom if there are too many tiles, nearest-first order, and tile copies across the antimeridian for a wrapping world. Fetching, decoding and caching are the engine's.

## Symbols

A symbol is a textured square of a size in pixels at a position, rotated by an angle, tinted by a colour, optionally rotated with the map or sized in map units, and moved on the screen by an offset (a declutter leader). The 48-byte record is `SymbolGpu`. `SymbolStrategy` picks how it reaches the shader:

<!-- decision-table:symbol-strategy -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `INSTANCED` | `INSTANCED_DRAWS`, `INSTANCED_ARRAYS` | one instanced draw; the symbol records as per-instance attributes |
| 2 | `TEXTURE_FETCH` | `TEXTURE_BUFFERS` | one plain draw, 6 vertices per symbol; the records in a texture buffer read from the vertex index |
| 3 | `EXPANDED` | nothing | one plain draw; the records repeated for each of the 6 vertices by the CPU |
<!-- /decision-table -->

All three draw 6 vertices per symbol and no geometry shader; `EXPANDED` is the floor that needs nothing. `SymbolAtlas` packs RGBA sprites with a one-texel gutter that repeats the edge, so filtering never picks up a neighbour. **No symbol set ships.** The standards for military and civil symbology have their own licences, and the demos draw generic shapes.

`angle` is counter-clockwise radians in the map frame: a sprite whose top points north drawn for a heading of `b` radians clockwise has the angle `-b`, and `SymbolRenderPlan.mapRotation(view)` is what `u_mapRotation` takes so that `ROTATE_WITH_MAP` symbols follow a rotated map. The tests check that the sprite's top points where `toScreen` says the bearing points, for rotated views.

## Declutter

`Declutter` places boxes in window pixels. Each item has an anchor, a size, a priority and a key; the candidates are offsets from the anchor tried in order (`useRingCandidates` makes the usual ones). Items are visited by priority, mandatory first; each takes the first candidate that overlaps nothing placed (a `UniformGrid` of the placed boxes keeps this near linear), or is hidden. An item shown the last time tries its previous candidate first and counts `stickiness` more in the order, so a pan of half a pixel changes under 1 percent of the decisions in the test, not half of them. It is greedy: a high priority item can push two lesser ones away where moving it would have saved both. Text and its measurement stay in the engine; `apply` writes the result to the symbols of a `SymbolBatch`.

## Filled areas

`AreaBatch` takes polygons with holes in projected double coordinates (triangulated in a frame local to each polygon, so a polygon in the middle of a UTM zone is not damaged by single precision) and a style table of `AreaStyle`: a fill colour and a pattern of lines or dots that the fragment shader evaluates in window pixels. `AreaRenderPlan` writes indexed triangles from one vertex buffer and one index buffer, one draw per run of polygons of equal style, submitted through `DrawSubmission` in whichever of its three forms the capabilities allow.

<!-- decision-table:area-strategy -->
| # | Strategy | Needs | What it does |
|---|---|---|---|
| 1 | `STYLE_TABLE` | `UNIFORM_BLOCKS` | a style index per vertex and a table of styles (storage block, uniform block or texture buffer); patterns in the fragment shader |
| 2 | `VERTEX_COLOR` | nothing | a colour per vertex; solid fills only |
<!-- /decision-table -->

Patterns are fixed to the window unless `patternOffset` moves them with the map, and they do not rotate with it. The outline of an area is a line: draw it with the line layer (`MapShapes` produces both from one shape).

## Terrain

`TerrainGrid` is a node-registered grid of heights over a rectangle of a projection (as `TerrainRgb` documents), with a `groundScale` for projections that do not keep distances. `Hillshade` is Horn's method (as GDAL), `slope` and `aspect` likewise. `ColorRamp` maps a value to a colour with stops that blend or steps; `relativeSteps` and `relativeLinear` place the stops at offsets from a reference altitude, which is how a terrain-awareness style of display colours terrain as clear, near and above the aircraft. The ramp is baked into a row of texels over a range (`bake`), looked up as a texture fetch, and rebuilt and uploaded when the reference moves. **The library does not know what any colour means**, and a safety-relevant colour scheme is the integrator's to define and verify.

`TerrainShading` colours a tile on the CPU into an RGBA image; `TerrainShader` is the GLSL that does the same per pixel from a height texture and the ramp texture. `Viewshed.compute` sweeps rays over the grid with optional curvature and refraction; `Viewshed.isVisible` is the exact test for one point. Measured: **146 nanoseconds per cell** once warmed up (88 runs in the `map-terrain` demo: 1.9 ms for about 12 900 cells in range on a 385 by 385 tile), and **3.3 microseconds per cell** for the first call in a fresh JVM (the unit test, 129 by 129 nodes of 30 m, 10 088 cells), so a figure taken from one cold run overstates the steady cost by twenty times. Agreement with the exact test is **98 percent** of the cells (the sweep is an approximation: a cell on the edge of a ridge can differ); the test asserts at least 97 percent.

## Units and text

`Units` has the nautical mile (1852 m), the international foot (0.3048 m), the statute mile, the knot and the foot per minute as exact constants, conversions, and `Length` and `Speed` enums. `GeoFormat` writes latitudes and longitudes as decimal degrees, degrees and minutes, or degrees minutes and seconds with rounding that carries (`59.9996'` is the next degree), bearings with three digits (`359.6` rounds to `000°`), ranges in a chosen or an automatic unit, and parses positions in the common variants, refusing what it cannot read instead of guessing. Output never depends on the default locale.

## Display palettes and insets

`DisplayPalette` maps colours (a 3 by 3 matrix and a gain in linear light, alpha kept): `DAY`, `NIGHT`, `NIGHT_VISION` and `RED_NIGHT` are generic looks, `of` makes any. It is an `IntUnaryOperator`, accepted by `LineRenderPlan.write`, `LineSet.setColorMap`, `AreaRenderPlan.write`, `SymbolRenderPlan.write`, `ColorRamp.mapped` and `SymbolAtlas.mapped`. A style table is one small buffer, so a switch is `rewriteStyles` and one upload; the strategies that keep a colour in the vertices (the hairline lines, the vertex-colour areas, the symbol records) write their data again. **Whether a palette is compatible with a goggle or a cockpit lighting standard depends on the display hardware and cannot be established by values in a table**: the presets make no such claim.

`ViewportInset` is a rectangle of a larger window: the OpenGL viewport and scissor (origin bottom left), the Vulkan and Direct3D form (origin top left), the conversion of mouse positions, and the scaling to a high-density framebuffer. Draw `view.withViewport(inset.width(), inset.height())` into it.

## What has been verified, and what has not

- Everything above has unit tests against references or independent computations, listed with the classes.
- The shaders (`SymbolRenderPlan`, `AreaRenderPlan`, `TerrainShader`) were compiled, linked and run on **one driver: an NVIDIA GPU with an OpenGL 4.6 context**, for the capabilities of every version from 3.30 to 4.6, with the style table as a storage block, a uniform block and a texture buffer where those are allowed, and every draw submission, by `MapGpuCheck` (`./gradlew -Psamples :vmath-samples:mapCheck`; 275 cases, each compared with the CPU model pixel by pixel where the model is sure, and with a check that the comparison fails on a wrong scene). Other drivers, mobile and Vulkan are **not** verified; the Vulkan form of the text is roadmap item GPU-13.
- The area patterns and the terrain shading are compared to within 3 of 255 per channel; a few pixels in a thousand may differ where a height is on the edge of a ramp texel.
- Not implemented: MGRS in the polar caps, text rendering, tile fetching and caching, symbol sets, a geometry or compute path for symbols.
