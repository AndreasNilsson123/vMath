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
- **Simplex** is scaled so that the largest value found in 2e7 samples was 0.998 (2D) and 0.978 (3D); this is a measurement, not a proof of the bound, and the root mean square was 0.538 and 0.425.
  There is no 4D simplex.
- **Value noise** reached 0.99994 in 2e7 samples (the bound 1 is exact: it interpolates values in [-1, 1]).
- **Continuity**: with a step of 1e-4 no noise changed faster than 14 units per unit of coordinate (simplex 2D is the steepest); a jump would show as thousands.
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
