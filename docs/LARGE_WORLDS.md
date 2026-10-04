# Large worlds: camera-relative data, floating origins, frames and WGS-84

A `float` has 24 bits of precision. At 6.4 million units from the origin (the radius of the Earth in metres) its spacing is 0.5, so a position cannot be held to better than half a metre.
Everything here is about keeping the world in `double` and giving the GPU `float` data that is small again. The single-value form, `relativeTo(origin)`, is described in `docs/CAMERA.md`
and `docs/COOKBOOK.md`; this document is what sits around it.

## `relativeTo` for transforms

The double types of the transforms have `relativeTo(Vec3d origin)`, which moves the translation by the origin **in double** and then narrows: `Mat4d`, `Mat4x3d`, `Transformd`,
`RigidTransformd` and `FrameTransformd` (and, as before, `Vec3d`, `Aabbd` and `Sphered`). The result is the model matrix of the object in a frame whose origin is `origin`; only the rotation
and scale are rounded.

## Whole arrays: `Rebase`

`Rebase` does the same for the arrays of a scene, from `double[]` world data to the `float[]` the GPU gets:

- `positions(src, srcOffset, origin, dst, dstOffset, count)`: three doubles to three floats each;
- `matrices(...)`: 16 doubles (column-major) to 16 floats, the translation (elements 12 to 14) moved by the origin;
- `bounds(...)`: `minX, minY, minZ, maxX, maxY, maxZ`, each corner subtracted and rounded to the nearest float (not outwards: add a margin to a culling test that must never miss);
- `shiftPositions(float[] ...)` and `shiftMatrices(...)`: data that is already relative, when the origin moves.

Measured (`LargeWorldBench`, 100 000 elements, 2 forks of 8 iterations): positions 103 us (+-5), about 1 ns each; matrices 1 241 us (+-68), about 12 ns each; shifting positions 101 us (+-7).
`RebaseTest` checks that the subtraction really happens in double: at 6.4 million units the result is exact to the float spacing of the *result*, while narrowing first loses the fraction of most values.

## A floating origin: `FloatingOrigin`

Converting the whole scene every frame touches everything. A `FloatingOrigin(cellSize, threshold)` follows the camera in whole steps of a grid instead: `update(cameraPosition)` moves the
origin to the nearest grid point only when the camera is farther from it than `threshold` on some axis, and returns whether it did. Then `lastShift()` is how far the origin moved, and float data
that is kept relative to it follows with `Rebase.shiftPositions` / `shiftMatrices`; `generation()` counts the moves so that a consumer can notice a missed one; `toLocal` and `toWorld` convert
single positions. The threshold is at least the cell, so a camera that has just caused a shift sits close to the new origin and one hovering at the border does not cause a shift every frame
(tested with a hundred updates around a border). With a power-of-two cell the shift is exact in `float`, and subtracting it is exact for data within a factor of two of the shift (the objects near
the camera); data far from the camera rounds to the precision of its result, which is as fine as a `float` of that size can be.

## Frame-tagged transforms: `Frame`, `FrameTransformf`

A product of the wrong two matrices is still a matrix. `FrameTransformf(source, target, transform)` (and `FrameTransformd`) carries the frames it connects: it maps coordinates in `source` to
coordinates in `target`. `mul(inner)` accepts only an inner transform whose target is this one's source (otherwise `IllegalArgumentException` naming the frames), `inverse()` swaps the frames,
and `transformPosition(Frame from, Vec3f p)` refuses a point that is not in the source frame. A `Frame` is a name; two frames are the same when their names are equal. The arithmetic is that of
`Transformf`, so its limits for non-uniform scale apply (`docs/API.md`).

## WGS-84: `Geodetic`, `Wgs84`

All in `double`.

- `Geodetic(latitude, longitude, height)` in radians and metres above the ellipsoid (`ofDegrees`, `latitudeDegrees`, `toEcef`). The latitude is the geodetic one. Nothing is normalised.
- `Wgs84`: the defining constants (`A`, `INVERSE_FLATTENING`) and the derived ones (`B`, `E2`, `EP2`); `toEcef` and `toGeodetic` (iterative, Bowring's method, converging to the precision of `double`; the
  height comes from the form that is accurate near the equator or the poles); `primeVerticalRadius` and `meridionalRadius`; the local axes `east`, `north` and `up` in ECEF; and the local
  East-North-Up frame: `enuToEcefRotation`, `enuFrame(origin)` (a `RigidTransformd` from local metres to ECEF), `ecefToEnu`, `enuToEcef` and `ecefToEnuDirection`.

Tested: `toEcef` against the closed form in terms of the semi-axes, `toGeodetic(toEcef(x)) == x` for 5 000 random positions from 10 km below the surface to 40 000 km up (latitude and longitude to 1e-12
radians, height to a micrometre), the poles, the polar axis and the centre of the Earth, the radii of curvature at the equator and the pole, an orthonormal right-handed ENU frame whose up raises the
height by exactly the distance, and a step north changing the latitude by `distance / M`. Measured: `toEcef` 62 ns, `toGeodetic` 378 ns per call.

To draw something on the Earth: hold positions in ECEF (or in the local ENU frame of the scene), subtract the camera (or a `FloatingOrigin`) in double, and give the GPU the floats.

```java
Geodetic here = Geodetic.ofDegrees(59.33, 18.07, 28.0);
RigidTransformd localToEcef = Wgs84.enuFrame(here);               // metres east, north and up of `here` to ECEF
Vec3d building = localToEcef.transformPosition(new Vec3d(120.0, -35.0, 12.0));
Vec3f onScreen = building.relativeTo(cameraEcef);                  // subtract in double, then narrow
```

Not built: geodesic distances on the ellipsoid (Vincenty, Karney), map projections other than Web Mercator (UTM and the like), geoid heights, and datum transformations other than WGS-84.

## Map tiles: `WebMercator`, `TileId`, `TileBounds`, `HorizonCuller`, `Ellipsoids`, `TerrainRgb`, `TileSelector`

The pieces for a globe that is drawn from a pyramid of map tiles (package `vmath.geo`, all in `double`, none `@Experimental`). The `globe` demo (`docs/DEMOS.md`) uses every one of them.

- `WebMercator`: the spherical EPSG:3857 projection that tile servers use (radius `Wgs84.A`, fed with the geodetic latitude): longitude and latitude to the tile square `(u, v)` and to projected
  metres and back, `MAX_LATITUDE` (85.0511 degrees), `metersPerPixel`. It is a tile addressing scheme, not a measuring projection; positions in 3D come from `Wgs84`.
- `TileId(zoom, x, y)`: XYZ numbering (rows count south from the northern limit; `fromTms` and `tmsY` convert), `containing(zoom, lon, lat)`, `parent`, `child`, `neighbour` (wraps east-west, ends
  at the poles of the map), the edges in radians, a `long` key that is unique over all zooms (a marker bit above the Morton code) and the quadkey string.
- `TileBounds`: the oriented box of the patch of the ellipsoid that a tile covers between two heights, in ECEF, oriented like the local East-North-Up frame at the middle of the tile; also its sphere
  and axis-aligned box, and a compact form (`compute` into a `double[16]`) for code that tests thousands of tiles. It is built from a 5 by 5 grid of samples at both heights grown by the
  largest distance the surface can leave a chord (`s^2 / 8r`), so it never excludes a point of the volume; it is not the smallest box.
- `HorizonCuller`: whether a bounding sphere is certainly hidden behind the ellipsoid from a camera, in the scaled space where the ellipsoid is the unit sphere. Conservative: a sphere just behind the
  limb can be reported as visible. The occluder is the ellipsoid at height 0.
- `Ellipsoids.raySpheroid` and `rayWgs84`: the first hit of a ray with a spheroid of revolution, in the units of the ray's direction like `Intersectiond.raySphere`.
- `TerrainRgb`: the Terrarium and Mapbox terrain-RGB codecs (`encode`, `decode`, whole tiles), the lowest and highest height of a grid, bilinear sampling of a node-registered grid and normals from
  central differences. A node-registered grid has the first and last samples on the tile's edges, so neighbouring tiles share their border heights exactly.
- `TileSelector`: the quadtree walk. A tile is split while the distance between the vertices of its mesh, projected at the distance to its box, is more than an allowed number of pixels; tiles outside
  the frustum or behind the horizon are skipped; after the walk, neighbouring tiles are brought within one zoom level of each other (so that a skirt of half a cell hides every crack). The boxes are
  cached; the result is in arrays (`count`, `zoom(i)`, `x(i)`, `y(i)`) and the same arguments always give the same result. Limits (`capacity` tiles, `6 * capacity` nodes) stop the refinement and
  say so (`truncated`, `overflowed`).

Tested against brute force: `TileBounds` contains every point of a dense grid of the volume (about 750,000 points over 400 tiles from zoom 0 to 15, including the polar rows and the antimeridian
columns); `HorizonCuller` never hides a sphere that has a point in view (4,000 random cameras from 30 m to 30,000 km up, 60 random points in each hidden sphere, each one behind the ellipsoid by a ray
test); `Ellipsoids` against the analytic hits along the axes and a hit height of zero; `TileId` round trips (keys, quadkeys, TMS) at every zoom; the codecs to half a step; `TileSelector` selects tiles
that do not overlap, neighbouring tiles differ by at most one level (and the test checks that it does see tiles one level apart), the result is the same for the same input, a finer allowance selects more tiles, the tile under
a low camera is at the finest level and a small capacity stops the refinement.

Measured (a loop on the build machine, JDK 25, after warm-up, not JMH): `TileBounds.compute` 3.5 us, `HorizonCuller.isHidden` 40 ns, `WebMercator.v` 29 ns; `TileSelector.select` with the boxes cached
over five views from 20,000 km to 5 m, with the zoom range 2 to 17, 32 cells and 6 pixels: 20 to 637 tiles, 56 to 1,248 nodes tested, 23 to 284 us. A call allocates the horizon culler and, for a tile
whose box is not cached, the temporaries of `TileBounds.compute`; nothing else.

Not built: geodesic distances on the ellipsoid (Vincenty, Karney), the ellipsoidal variant of Web Mercator, geoid heights and datum transformations other than WGS-84 (geodetic heights are heights
above the ellipsoid, not above sea level), an elevation-dependent horizon (terrain that rises above the ellipsoid hides more than the culler assumes, which is conservative), tile data itself, and a
decoder for the image formats of tile servers (the JDK's `ImageIO` reads PNG and JPEG).
