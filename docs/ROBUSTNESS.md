# Behaviour on degenerate input

What the core types do with zero, negative zero, NaN, infinity, subnormal and huge values, singular matrices and invalid arguments. Every row is
checked by `DegenerateContractfTest` (float, and its generated double twin); `DegenerateInputSweepTest` calls every public method of the 14 core types
about 400 times each with such values (257 200 calls, 629 methods) and fails on anything not listed here.

## Principles

1. **Garbage in, loud garbage out.** An invalid input (zero vector to normalize, singular matrix, parallel up vector) gives NaN or infinity, not a plausible
   finite number. Detect it with `isFinite()` (vectors, quaternions, matrices) or by testing the determinant.
2. **Nothing throws** except an index outside a value (`get(i)`, `column(i)`, `row(i)` throw `IndexOutOfBoundsException`). No call in the sweep ran long or hung.
3. **NaN is not hidden.** The only operations that return a finite value for input containing NaN are those that do not read the NaN (a component accessor, the
   bottom row of an affine matrix) or are defined by a comparison; they are listed in the sweep test with the reason, and a new one fails the build.
4. **Quaternion, rotation and basis operations assume unit length** and do not validate it. Non-unit input gives scaled or degenerate (but finite) results.
5. **Setup-time builders do not validate.** Projection and look-at builders are not hot and could throw, but they follow the same rule as the rest: bad
   arguments show up as infinity or NaN in the matrix.

## Normalizing

| Input | `normalize()` (Vec2/3/4, Quat) | `normalizeOrZero()` (vectors) |
|---|---|---|
| zero, -0 | all NaN | zero vector |
| NaN in any component | all NaN | all NaN |
| infinity in any component | all NaN | all NaN |
| squared length 1e-30 or less (but not zero) | the right unit vector | zero vector |
| subnormal (smallest float 1.4e-45) | the right unit vector | zero vector |
| huge (0.9 of the largest float) | the right unit vector | the right unit vector |

The last two rows of `normalize` were wrong until this was measured: the squared length underflowed to 0 (result `(Infinity, NaN, NaN)`) or overflowed to infinity
(result `(0, 0, 0)`, silently), and `normalizeOrZero` turned a huge vector into the zero vector. Now the common case is the same two multiplications and a square root
as before, and only a squared length outside the normal range takes a slower path that divides by the largest component first. A sweep over every binary exponent
from the smallest subnormal to the overflow limit checks that the result has length 1. Measured with `CoreBench` (JMH, JDK 25, 0 B/op both ways): `chainVec3` 6.29 ± 0.26 ns
before and 6.49 ± 0.25 after, `chainQuat` 18.7 ± 13.3 before (a noisy run) and 15.4 ± 0.4 after: no measurable cost.

## Lengths

`length()`, `lengthSquared()` and `distance()` use the direct formula, so a vector whose components exceed about 1.8e19 (float) or 1.3e154 (double) has an infinite
`lengthSquared` and an infinite `length` although the length could be represented, and a subnormal vector has length 0. Scale first, or use `normalize()`, which handles both.

## Vectors

| Operation | Behaviour |
|---|---|
| `a.cross(b)` parallel | zero vector; NaN input gives NaN |
| `a.angle(zero)` | 0; `angle(-a)` is pi |
| `a.project(zero)` | zero vector (a guard, so NaN in `a` is not reported) |
| `a.reject(zero)`, `a.reflect(zero)` | `a` unchanged |
| `a.refract(zero normal, eta)` | zero vector (the total-internal-reflection convention) |
| `lerp(b, NaN)` | all NaN |
| `div(0)` | IEEE: infinity for a nonzero component, NaN for 0 / 0 |
| `zero.anyPerpendicular()` | all NaN |
| `faceForward`, `step` | defined by a comparison, so a NaN that reaches the comparison selects a branch (GLSL semantics) |

## Quaternions

| Operation | Behaviour |
|---|---|
| `fromTo(a, -a)` | a valid half turn about a vector perpendicular to `a` |
| `fromTo(zero, b)`, `lookRotation(zero, up)`, `lookRotation(f, f)` | all NaN |
| `fromAxisAngle(angle, zero axis)` | NaN vector part (the angle still shows in `w`) |
| `zero.invert()` | all NaN |
| `slerp(q, -q)` / `nlerp(q, -q)` | the short arc: a unit quaternion, here no rotation at all |
| `slerp` with NaN | NaN |
| `zero.transform(v)`, `zero.toMat3()` | `v` unchanged, the zero matrix (not a rotation: unit length is assumed) |
| `zero.pow(t)`, `zero.exp()` | identity, for any `t` including NaN |
| `integrate(zero omega, dt)` | unchanged, for any `dt` including NaN |
| `angle()`, `axis()` | read `w` and the vector part of a unit quaternion; `axis()` of a (near) zero rotation is +x |

## Matrices

| Operation | Behaviour |
|---|---|
| `invert()` of a singular matrix | non-finite entries (infinity and NaN, from dividing by the zero determinant); `determinant()` is 0 |
| `invertAffine()` of a singular affine matrix | non-finite entries |
| `invert()` of a NaN matrix | all NaN |
| `invert()` of a matrix with scale 1e-20 | finite, entries 1e20 |
| `determinant()` / `invert()` with scale near the overflow limit | infinity / NaN (the product overflows) |
| `decompose()` with a zero scale axis | translation and scale are right, the rotation is all NaN |
| `decompose()` with scale above about 1e19 (float) | infinite scale (the lengths overflow) |
| `perspective(aspect 0)`, `ortho(left == right)`, `perspective(near == far)` | infinity in the matrix |
| `perspective(near 0)` | finite, but `m32` is 0: no depth resolution (not detected) |
| `lookAt(eye == target)`, `lookAt` with up parallel to the view | NaN entries |
| `rotationAxis(angle, zero axis)` | NaN |
| `Quat.toMat3` / `rotation(q)` for a non-unit quaternion | a scaled or zero matrix |
| `Mat3.basisFromNormal(zero)` | a finite basis whose third column is zero (the normal must be unit) |
| affine methods (`transformPosition`, `transformDirection`, `normalMatrix`, `invertAffine`, `decompose`, `Mat4x3.fromMat4`) | never read the bottom row, so a NaN there is ignored; `isFinite()` looks at every entry |

## Equality and hashing (CORE-11)

The value types are records, so `equals` and `hashCode` are exact and bitwise-like: **NaN equals itself** (and has a consistent hash), **0 and -0 are different
keys**, and infinities compare by sign. `approxEquals(other, eps)` is the opposite: -0 equals 0, and NaN, and even infinity against the same infinity, are never
approximately equal (the difference is NaN). `SpatialHash` has the epsilon-neighbourhood helpers for hashing positions (`docs/EQUALITY.md`).

## Exact predicates, double-double and stable formulas (CORE-10)

**`Predicates`** (experimental) answers the four questions a convex hull, a Delaunay triangulation or a mesh boolean keeps asking with a sign that is **always exact**:
`orient2d` (counter-clockwise, clockwise or collinear), `orient3d` (above, below or on the plane of a triangle), `incircle` and `insphere` (inside, outside or on the circle or sphere
through the others). Plain floating point answers them wrongly for nearly degenerate input: in `PredicatesTest` the ordinary double determinant had the wrong sign in 6.3% of the
`orient2d` cases (3 781 of 60 000), 6.4% of the `orient3d` cases (2 571 of 40 000), 14% of the `incircle` cases (5 535 of 40 000) and 12% of the `insphere` cases (2 488 of 20 000),
because the cases are built to be hard: points exactly on a line, plane, circle or sphere of an integer lattice at several scales, and points that are on one up to the last bits.

How: each predicate evaluates its determinant in double and also a bound on the rounding error of that evaluation (Shewchuk's bounds, doubled); when the result is larger than the
bound its sign cannot be wrong and it is returned, which is almost every call. Otherwise the determinant is computed exactly with expansion arithmetic (`Expansions`: sums and products
of unevaluated sums of doubles, with `Math.fma` for the exact product of two doubles) and the sign of the largest component is returned. Every result is verified against `BigDecimal`
arithmetic, which is exact for doubles, on the hard cases above (the expansion operations are also checked on their own, on random doubles over 80 binary orders of magnitude); the tests
also check that exactly degenerate input returns exactly zero and that swapping two points flips the sign. They were run with seeds 1 to 3 at 40 000 trials as well as the default.

Cost (JMH `PredicatesBench`, one fork, ns per call, 2026-10-02): on random points `orient2d` 2.6 against 1.9 for the plain formula, `orient3d` 7.2, `incircle` 6.2, `insphere` 22; on
exactly degenerate points, which always take the exact stage, `orient2d` 86, `orient3d` 184, `incircle` about 370 (a noisy figure) and `insphere` about 1 330. So the exact stage costs
30 to 60 times the filter, and a mesh in which most triples are collinear or coplanar will pay it often. There is no middle stage (Shewchuk's adaptive predicates have a cheaper
second step with a tighter bound); it was not built because the exact stage is already microseconds.

Range: coordinates must be finite, and zero or between `1e-20` and `1e20` in magnitude, so that no intermediate product overflows or underflows (floats are always fine). The conventions
(which side is positive) are in the class comment and are tested against the triple product and the unit circle and sphere.

**`DoubleDouble`** (experimental) is a number held as `hi + lo`, two doubles: 106 bits, 32 digits. Addition, subtraction, multiplication and division have a relative error under `2^-102`
(`DoubleDouble.EPSILON`, 2.0e-31); measured against 80-digit `BigDecimal`: add 2.4e-32, multiply 4.0e-32, divide 2.9e-32, square root 3.5e-32, and addition under cancellation 0 relative
to the size of the operands. `twoSum` and `twoProduct` are exact. A sum of one million times 0.1 is off by 1.3e-6 in double and exactly right in `DoubleDouble`.

**Stable formulas.** The angle between two vectors was already `atan2(|a x b|, a . b)`, and `normalize` already survives overflow and underflow (see Normalizing above); this pass added
`StableFormulasTest` for both and fixed `Quat.angle()`, which used `2 acos(w)`: for a rotation of 1e-5 radians a float `w` rounds to exactly 1 and the angle came out as 0. It is now
`2 atan2(|xyz|, |w|)`, accurate for tiny rotations and rotations near pi, and independent of the quaternion's length.

## What this does not cover

Shapes (`geo`), culling, meshes and the bulk arrays have their own NaN policies, documented with them (overlap tests are written as "not separated", so NaN
overlaps). The sweep covers only the 14 core types; it skips methods that take arrays, buffers or segments, which have their own tests. The magnitude sweep of
`normalize` and the contract tests use one data point per case, not random values.
