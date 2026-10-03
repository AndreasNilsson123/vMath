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

Not built: geodesic distances on the ellipsoid (Vincenty, Karney), map projections (UTM, Web Mercator), geoid heights, and datum transformations other than WGS-84.
