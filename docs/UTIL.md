# Random numbers, noise, springs and easing (`vmath.util`)

Everything in this package is `@Experimental`, hand-written (not generated from the float templates), deterministic, and allocation-free per call (`AllocationContractTest`) except where
stated. Speeds are JMH `UtilBench`, 1 fork of 5 one-second iterations after 3 warm-up iterations, JDK 25, single thread, 2026-10-03; each benchmark makes 1 024 calls and the table is
the time of one call. The `±` is the 99.9% interval JMH reports.

## `Rng`: xoshiro256++

Seedable, with the reference output of xoshiro256++ (the first ten outputs from the state 1, 2, 3, 4 and the SplitMix64 outputs are tests, checked against an independent implementation).
It offers `nextLong/Int/Double/Float/Boolean/Gaussian`, an unbiased `nextInt(bound)` (the multiply-and-reject method of Lemire), `shuffle`, `split()` for a second stream, and sampling that writes into a
`float[]`: points on a circle and a sphere, in a disk and a ball, on a hemisphere, and cosine-weighted on a hemisphere. The sampling is tested statistically (moments, chi-square on the
buckets, the volume and area fractions, the mean cosine 1/2 and 2/3).

| Call | Time |
|---|---|
| `Rng.nextLong` | 1.18 ns ± 0.07 ns |
| `java.util.SplittableRandom.nextLong` (for comparison) | 1.18 ns ± 0.07 ns |
| `Rng.nextDouble` | 1.36 ns ± 0.06 ns |
| `Rng.nextGaussian` (polar method) | 13.4 ns ± 1.0 ns |
| `Rng.onUnitSphere` | 27.2 ns ± 5.0 ns |
| `Rng.cosineHemisphere` | 27.0 ns ± 1.4 ns |

The generator is not faster than `SplittableRandom`; the reason to use it is the reproducible, documented algorithm with a 256-bit state and the sampling routines on top.

## `Sequences`: low-discrepancy points and Poisson-disk sampling

`halton` (16 dimensions), `sobol2` (the first two Sobol dimensions), `r2`, `hammersley` and `radicalInverse`. The Sobol points start 0, (0.5, 0.5), (0.25, 0.75), (0.75, 0.25) and every aligned block of
2^k points has exactly one point in each cell of every 2^a x 2^b grid with a + b = k (tested for k up to 8). Only two Sobol dimensions exist; there are no scrambled variants and no blue-noise
tables.

`poissonDisk` is the algorithm of Bridson: random points at least `radius` apart that fill a rectangle. It allocates its grid, so it is set-up code. In a 100 x 100 rectangle it placed 2 847 points
for radius 1.5 and 1 592 for radius 2, which is 55% of the number a hexagonal lattice with that minimum distance would hold (the test accepts 45% to 100%).

| Call | Time |
|---|---|
| `Sequences.halton`, one dimension | 12.9 ns ± 0.3 ns |
| `Sequences.sobol2` | 23.3 ns ± 4.2 ns |
| `Sequences.poissonDisk`, 100 x 100 at radius 2.0 (1 592 points, 30 candidates) | 5.72 ms ± 0.07 ms |

## `Noise`

Value, gradient (Perlin 2D, 3D, 4D), simplex (2D, 3D), Worley (2D, 3D, nearest and second nearest distance), curl (2D, 3D), fractal sums, domain warping and a batch fill of a `float[]` grid. The
lattice values come from an integer hash (uniform: chi-square test on 64 buckets). Coordinates are in lattice units.

- **Perlin** uses the `+-1` diagonal gradients and is scaled by 2 / dimension, which makes `[-1, 1]` a proven bound (the largest possible value of the construction is dimension / 2). The price is a
  modest typical amplitude: the root mean square of the values over 2e7 random samples was 0.305 (2D), 0.221 (3D) and 0.169 (4D). Anisotropy of the diagonal gradient set was not measured.
- **Simplex** is scaled so that the largest value found in 3e7 samples was 0.998 (2D) and 0.975 (3D); this is a measurement, not a proof of the bound, and the root mean square was 0.538 and 0.383.
  The 3D kernel has the radius squared 0.5. The value 0.6 that many implementations use makes the function **jump** by about 0.003 at the faces between simplices (the continuity test
  found it: the measured slope grew in proportion to 1 / step, 8.5 at a step of 1e-3 and 3 442 at 1e-6); with 0.5 the slope is the same at every step (6.66). There is no 4D simplex.
- **Value noise** reached 0.99994 in 2e7 samples (the bound 1 is exact: it interpolates values in [-1, 1]).
- **Continuity**: the slope does not grow as the step shrinks (steps from 1e-3 to 1e-7, 3e7 samples): the steepest slopes are 2.7 (Perlin 2D and 3D; 0.5 in 4D), 7.03 (simplex 2D), 6.66 (simplex 3D) and 3.7 (value noise).
- **Worley**: the nearest distance is exact. The second nearest distance is searched among the 9 (27 in 3D) cells around the sample, which is not always enough: measured against a search of 81 (343)
  cells, with fully random feature positions it was wrong in 272 of 2e6 samples in 2D (0.014%) and 11 of 3e5 in 3D (0.004%), and never wrong in the same samples when the feature positions were
  limited to half the cell (`jitter` 0.5).
- **Curl** noise is the curl of Perlin noise by central differences (step 1e-4); the divergence of the result was measured below 1e-3 over 2 000 random points (the exact field has none).

| Call | Time |
|---|---|
| `value3` | 17.8 ns ± 1.1 ns |
| `perlin2` | 12.6 ns ± 0.8 ns |
| `perlin3` | 35.6 ns ± 2.7 ns |
| `perlin4` | 81.0 ns ± 7.1 ns |
| `simplex2` | 13.6 ns ± 0.6 ns |
| `simplex3` | 33.2 ns ± 2.6 ns |
| `worley2` | 41.1 ns ± 2.8 ns |
| `worley3` | 174 ns ± 13 ns |
| `fbm3`, Perlin, 4 octaves | 148 ns ± 12 ns |
| `curl3` (potential evaluated 12 times) | 430 ns ± 57 ns |

Branch-free signs in the gradient dot products mattered: with a ternary per sign, `perlin4` took 265 ns because of branch mispredictions (the gradient bits are random); the arithmetic form brought it
to 81 ns, and `fbm3` from 471 ns to 148 ns.

## `Spring`, `Smoothing`, `Easing`

`Spring` solves the damped oscillator exactly for any step size (underdamped, critical and overdamped), so it is frame-rate independent and cannot blow up. It is tested against a classical
Runge-Kutta integration with 4 000 small steps (position within 1e-6 and velocity within 1e-5 relative), against splitting a step in two (equal to 1e-12), and for a step of a million seconds.
`Spring.halfLife(seconds)` gives the angular frequency of a critically damped spring that covers half the distance in that time.

`Smoothing.towards` is the frame-rate independent exponential form (`1 - exp(-rate dt)` of the distance); `towardsHalfLife`, `moveTowards`, `angleDelta`, `smoothstep` and `smootherstep` are
the small helpers around it.

`Easing` has the Penner families (quad, cubic, quart, quint, sine, expo, circ, back, elastic, bounce, each in, out and in-out) as an enum with `apply(t)`; `t` is clamped to [0, 1]; the monotonic
families are tested to be monotonic and the in-out forms symmetric.

| Call | Time |
|---|---|
| `Spring.update`, critically damped | 26.9 ns ± 1.1 ns |
| `Spring.update`, underdamped or overdamped (varying damping) | 62.7 ns ± 2.4 ns |

Not included: springs on quaternions and angles (use `Smoothing.angleDelta` for angles), blue-noise tables, scrambled Sobol and more than two Sobol dimensions, 4D simplex noise.

## `SphericalHarmonics`, `Ibl`, `DebugLines`

**`SphericalHarmonics`** holds an environment in the first two bands (nine real coefficients per colour channel, 27 floats, the layout a shader uniform array of `vec3` wants): `project` integrates a
function on the sphere with a Gauss-Legendre product quadrature, `evaluate` reconstructs it, `irradiance` gives the diffuse irradiance at a normal with the clamped-cosine factors (`pi`,
`2 pi / 3`, `pi / 4`), `convolveWithCosine` precomputes them, `rotate` turns the environment without going back to the function, and `addConstant` and `addDirectionalLight` build one from lights.
Tests: the basis is orthonormal; the clamped cosine has the published coefficients (`sqrt(pi) / 2`, `sqrt(pi / 3)`, `sqrt(5 pi) / 8`); the irradiance of a band-limited environment equals a direct
numerical integration (within 3e-3); the rotation equals projecting the rotated function (within 3e-4), composes, and keeps the norm. Two bands represent only smooth environments: a directional light
facing the surface gives 1.0625 (the exact value is 1) and a surface facing away still sees a small negative value of at most 0.1 (a measured artefact of the truncation); do not use these coefficients for specular reflection.

**`Ibl`** has the GGX terms (the distribution, the height-correlated Smith visibility, the Schlick-GGX geometry term of image-based lighting), importance sampling of the lobe, the scale and bias
of the split-sum specular term (`dfg`) and its lookup table (`brdfLut`), and the GGX-prefiltered environment (`prefilterGgx`). Checks that do not rely on the code under test: the distribution
integrates to 1 over the projected hemisphere (within 2e-3); for a nearly perfect mirror the table is exactly `1 - (1 - n.v)^5` and `(1 - n.v)^5`; at roughness 1 and normal incidence the
table equals `1 - ln 2` (the closed form of the integral); `A + B` never exceeds 1; the sampled values agree with a 40 000-sample reference to 0.01; the prefiltered environment converges to a
direct integration of the lobe (within 0.02 at 8 192 samples). An analytic fit of the table (the "mobile" approximation of Karis) was tried from memory and **not included**: it differed from the
sampled table by up to 0.18, and the constants could not be verified against a source.

**`DebugLines`** generates line lists for drawing: boxes, oriented boxes, circles, spheres (three great circles), capsules, cones (spot-light gizmos), axes, grids, arrows, frusta (from a
view-projection matrix in any depth convention, also reversed and infinite), skeletons (from world matrices) and meshes' wireframes, with a packed colour per line. The shape generators allocate
nothing once the buffer has reached its size (`AllocationContractTest`); `frustum` creates the inverse matrix. The tests check the geometry itself: the box edges and corners, the circle radius and
plane, every capsule point at exactly the radius from the axis, the frustum corners at the corners of the NDC cube in every clip space.

| Call | Time |
|---|---|
| `SphericalHarmonics.evaluate` | 25.0 ns ± 2.3 ns |
| `SphericalHarmonics.irradiance` | 27.1 ns ± 3.2 ns |
| `SphericalHarmonics.rotate` | 819 ns ± 91 ns |
| `SphericalHarmonics.project`, 16 x 32 quadrature points | 32.2 µs ± 14.3 µs |
| `Ibl.dfg`, 256 samples | 21.8 µs ± 1.1 µs |
| `Ibl.brdfLut`, 32 x 32 texels of 128 samples | 11.2 ms ± 1.6 ms |
| `Ibl.prefilterGgx`, 1 024 samples | 60.4 µs ± 24.1 µs |
| `DebugLines`: a box, a sphere and a capsule (12 + 72 + 52 lines) | 4.87 µs ± 0.29 µs |

JMH `RoadmapBench`, JDK 25, single thread, 2026-10-03. The `project` and `prefilterGgx` figures include the cost of the environment lambda and have wide intervals.

## `RollingStats` and `FrameTimer` (UTIL-4)

`RollingStats(window)` keeps the last `window` samples of a stream in a ring and answers `min`, `max`, `mean` (Kahan summation), `stdDev` (population), `countAbove` and exact percentiles
(`percentile(p)`, `percentiles(ps, out)`). A percentile is the linear interpolation between the closest ranks (NumPy's `linear`, Excel's `PERCENTILE.INC`): the median of 1, 2, 3, 4 is 2.5. One
percentile is a quickselect on a copy of the window in a scratch array the object owns; several are one sort of that copy. Nothing is cached, so a query is right after any sequence of additions.
An empty window gives `NaN`; a NaN or infinite sample is refused with an exception, since it would spoil every later figure. Tested against a sorted copy on 300 random windows with many repeated
values (the case that breaks a careless partition), around the wrap of the ring, and for the refused inputs.

`FrameTimer(window)` is a `RollingStats` of frame times in milliseconds: `tick()` once per frame (`tick(nowNanos)` with your own clock, `record(nanos)` for a duration you measured), then `fps()`,
`averageMillis()`, `medianMillis()`, `percentileMillis(p)`, `minMillis()`, `maxMillis()`, `lowFps(percent)` (`lowFps(1)` is the "1% low": the frame rate at the 99th percentile of the frame time) and
`hitches(factor)` (the frames that took more than `factor` times the median). The test builds a smooth run and a run with four 59 ms stalls among 9 ms frames that have the same mean frame time
(10 ms, 100 fps): `fps()` cannot tell them apart, `lowFps(1)` gives 100 against 17, and `hitches(2)` counts the four.

Neither allocates after construction (`AllocationContractTest`). JMH was not used; a plain loop on one machine (JDK 25, after warm-up): `add` 1.9 ns, `percentile` 0.41 us for a window of 240 and
0.84 us for 1 024, four percentiles with one sort 2.3 us and 7.1 us, `mean` plus `stdDev` 1.9 us and 8.7 us. A frame loop that asks for the figures every frame spends a few microseconds; ask
every 10th frame if that matters.
