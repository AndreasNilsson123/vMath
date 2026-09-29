# Meshes

`vmath.mesh` is the content layer under everything else: an indexed triangle mesh stored as plain arrays, procedural shapes,
normal and tangent generation, load-time optimisation, and export into GPU buffers. It follows the library's rules: no per-vertex
objects, one array per attribute, allocation only where a tool works on a whole mesh at load time.

```
Primitives ─┐
your loader ─┴─► Mesh ─► MeshTools (normals, tangents) ─► MeshOptimizer (weld, cache, fetch) ─► MeshExport ─► GPU buffers
                  │                                                                              (VertexLayout)
                  └─► bounds, area, volume ─► BoundsArray / StaticBvh / culling
```

## `Mesh`

- Always: `positions` (3 floats per vertex) and a triangle index list. Optional streams, switched on with `enable...` and always
  one entry per vertex: `normals` (3), `tangents` (4: direction and a handedness sign, so `bitangent = cross(normal, tangent) * w`)
  and up to four UV sets (2 each).
- Counter-clockwise triangles seen from outside, like the rest of the library. `positions()`, `indices()` etc. return the live
  arrays, which are longer than the used part and may be replaced when the mesh grows: fetch them again after adding.
- Queries: `bounds()`, `surfaceArea()` and `signedVolume()` (positive for a closed, outward-wound mesh: the cheapest check that a
  mesh is not inside out). `copyVertex` appends a copy of a vertex across every stream, which is what vertex splitting needs.

## `Primitives`

Plane, box, UV sphere, icosphere, capsule, cylinder, cone and torus, each with normals, UV set 0 and tangents. Where UVs or normals
must differ across an edge (a sphere's seam, a cylinder's cap rim, a box's corners) the vertices are duplicated with
**bit-identical positions**, so every closed shape is watertight by position: the tests check that each directed edge occurs exactly
once together with its opposite, that the signed volume is positive, that normals are unit length and agree with their faces, and that
volume and area match the analytic shape (for example a 64 x 32 sphere reaches at least 98.5% of the true volume from below, a
64-segment cylinder 99.5%). Zero-area triangles are left out. The icosphere's UVs are spherical and stretch at the seam; use the UV
sphere when texturing matters.

## `MeshTools`

- `computeSmoothNormals`: angle-weighted, so a triangle contributes to each corner in proportion to the angle it has there and the
  result does not depend on how a surface is split into triangles (tested: cutting one wedge in two leaves the centre normal unchanged).
- `computeNormalsWithCrease(mesh, angle)`: splits vertices where the faces around them meet at more than the crease angle (a cube
  keeps 24 vertices with flat faces, a fine sphere keeps its vertex count), copies all other streams to the new vertices, and returns
  a `remap` (new vertex to original vertex) so your own per-vertex data can follow.
- `computeTangents(mesh, uvSet)`: per triangle the direction in which `u` grows and in which `v` grows, accumulated at the corners with
  angle weights, made perpendicular to the normal, handedness in `w`. It is the standard accumulation, **not a bit-exact MikkTSpace
  port**. Tested against random rotated, sheared and mirrored UV mappings of a plane (200 trials): the tangent points along `dP/du`
  and `w` is right in every case.
- **Degenerate input never gives NaN.** Zero-area triangles and UV-degenerate triangles are skipped; a vertex left with nothing gets
  normal `(0, 1, 0)` or a tangent perpendicular to its normal. A corpus of random meshes with coincident vertices, collapsed triangles,
  huge and tiny coordinates and identical UVs checks this.

## `MeshOptimizer`

Run in this order: weld, cache, fetch. None of them changes the surface (tested by comparing the set of position triples, orientation
included, before and after the whole pipeline on random meshes).

- `weld(mesh, eps, positionsOnly)`: merges duplicate vertices through a grid of cell size `eps`. Comparing every stream keeps UV seams
  and hard edges; `positionsOnly` closes them (use it to rebuild connectivity). Returns the old-to-new remap; triangles that collapse
  are dropped. A triangle soup of a cube welds back to 8 vertices.
- `optimizeVertexCache(mesh, cacheSize)`: Tom Forsyth's scoring algorithm. Only the order of the triangles changes.
- `optimizeVertexFetch(mesh)`: reorders vertices by first use, so the vertex buffer is read almost sequentially; returns the remap
  (a permutation), unused vertices go last.
- `acmr(mesh, cacheSize)`: average cache miss ratio for a FIFO cache: vertex shader runs per triangle (3 is worst, about 0.5 is the best
  a regular grid can do).

**Measured** (FIFO cache of 32, triangle order shuffled, then optimised; a single run on this machine, JDK 25):

| Mesh | triangles | as generated | shuffled | optimised | time |
|---|---|---|---|---|---|
| grid 100 x 100 | 20 000 | 1.010 | 2.993 | **0.664** | 22 ms |
| UV sphere 128 x 64 | 16 128 | 1.016 | 2.991 | **0.667** | 19 ms |
| icosphere, 6 subdivisions | 81 920 | 0.781 | 2.998 | **0.683** | 128 ms |
| torus 128 x 64 | 16 384 | 1.016 | 2.992 | **0.672** | 21 ms |

The optimiser recovers about 77% of the vertex shader work lost to a random order and beats the natural generation order of grid-like
meshes (1.01). It costs about 1.3 to 2 microseconds per triangle, fine for load time and not for every frame. **Overdraw
optimisation is not built** (it needs a rasterisation model); neither are meshlets or simplification.

## `MeshExport` and `VertexLayout`

`VertexLayout.builder().position().normalOct16().tangent().uvHalf(0).build()` describes an interleaved vertex: position as 3 floats,
normal as float3 or 2 x 16-bit octahedral (about 4e-5 radians of error), tangent as 4 floats, UVs as 2 floats or 2 halves. Offsets and
stride are computed as you add attributes. `MeshExport.writeVertices` writes straight from the mesh arrays into a `MemorySegment`
(`GpuWriter.of(byteBuffer)` wraps a `ByteBuffer`), `writeIndices32` and `writeIndices16` write the indices (the 16-bit one refuses a mesh
with more than 65 536 vertices). A layout that asks for a stream the mesh does not have is an error, not silently zero.
