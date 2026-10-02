# Equality, hashing and spatial keys

Every value type (`Vec2` to `Vec4`, `Quat`, `Mat3`, `Mat4`, `Mat4x3`, `Transform`, `Aabb`, `Sphere`, `Plane`, `Ray`, `Triangle`, `Obb`, `Capsule`, `Segment`, in `f` and `d` form) is a record, and its
`equals` and `hashCode` are the ones Java gives records: component by component, **exact**, using `Float.compare` and `Double.compare` rules. That has three consequences that are easy to trip over and that
`EqualityContractTest` checks for every component of every type (both precisions):

| Case | Result |
|---|---|
| `0.0` against `-0.0` | **not equal**, and the hash codes differ |
| NaN against NaN (any payload, either sign) | **equal**, with the same hash code; a value containing NaN equals itself |
| infinity against itself | equal; `+Infinity` and `-Infinity` differ |
| two numbers that differ in the last bit | not equal |

The `-Pvalhalla` build, where these records are real value records on the JDK 28 early-access build, passes the same contract test. So `equals` answers "is this the same value, bit for bit (with all NaNs identified)", not "do these compare equal under `==`". The record `hashCode` is consistent with that, and is stable within a run only: do not
store it or depend on its value.

## Tolerant comparison is a separate method

`approxEquals(other, eps)` compares each component with `|a - b| <= eps` (absolute, inclusive). It sees `0.0` and `-0.0` as the same number, it is **false whenever a component is NaN** (even
for a value compared with itself, since the comparison is false), and for `Quat` and `Transform` the rotations `q` and `-q` count as the same (`sameRotation`). It is not transitive and not a hash-compatible
equality: two values that are `approxEquals` can have different hash codes, so never use it for `HashMap` keys.

## As map and set keys

Records as keys work and are exact: `0`, `-0` and NaN are three different keys (the contract test builds a `HashMap` to show it), and any NaN finds the NaN entry. If `0.0` and `-0.0` must be the same key,
canonicalize the bits first: `SpatialHash.floatKey(f)` and `doubleKey(d)` map `-0.0` to `0.0` and every NaN to one pattern, and the result is a plain `int` or `long` to hash and compare.

## Positions that are "the same point" up to noise (`SpatialHash`)

Welding vertices, finding an earlier sample, hashing particles into a grid: exact keys are wrong for these (two points that differ by rounding land in different buckets), and rounding to a grid has the
opposite flaw (two points a hair apart on either side of a cell border land in different cells). `SpatialHash` is the standard answer.

- Space is cut into cubes of `cellSize`; `cell(v, cellSize)` is `floor(v / cellSize)`, computed in double precision, as an `int`. NaN, infinity and an index beyond an `int` are rejected.
- `hash(x, y, z)` mixes three cell indices into a well-distributed `int` (a Murmur3-style finalizer on each; neighbouring cells get unrelated hashes), and `pack3` gives an exact `long` key for indices within
  +-1,048,575 when you want a collision-free map.
- **Insert** a point under the hash of its own cell. **Query** with `cellsOverlapping(x, y, z, epsilon, cellSize, out)`, which writes the hashes of every cell that the box `[p - epsilon, p + epsilon]` overlaps
  (1, 2, 4 or 8 of them; it requires `epsilon <= cellSize / 2`). Guarantee, tested on 200,000 random cases at several scales, including points exactly `epsilon` away: if a stored point is within `epsilon` of the query on
  every axis, its cell hash is among the returned hashes. The candidates then need an exact distance test, because cells hold farther points too and two cells can share a hash.

```java
float cell = 0.01f, eps = 0.004f;                       // eps <= cell / 2
int[] probe = new int[8];
Map<Integer, IntList> grid = new HashMap<>();           // cell hash -> points stored in that cell (collisions possible)
// insert point i
grid.computeIfAbsent(SpatialHash.hash(x[i], y[i], z[i], cell), k -> new IntList()).add(i);
// find stored points within eps of (qx, qy, qz)
int n = SpatialHash.cellsOverlapping(qx, qy, qz, eps, cell, probe);
for (int c = 0; c < n; c++) {
    IntList bucket = grid.get(probe[c]);                // visit each bucket once if two cells share a hash
    // test every candidate in `bucket` exactly: |x - qx| <= eps on every axis, or the Euclidean distance
}
```

**Measured** (`EqualityContractTest`): 262,144 neighbouring cells (a 64 x 64 x 64 block) gave 262,144 distinct hash values and, taken modulo 1,024, buckets of between 205 and 304 against a mean of 256, so a
power-of-two table fills evenly. The cells visited per query were 1, 2, 4 or 8 (never another count, since each axis spans one or two cells): in the test's mix of random queries 43,235 touched one cell, 43,324 two,
43,460 four and 59,174 eight, which is why `epsilon` should be small against `cellSize` (at `epsilon = cellSize / 2` a point near a corner always costs 8 lookups).

## Not covered

Hash grids themselves (the `UniformGrid` and `LooseOctree` in `vmath.spatial` are the bounded-world structures), tolerance that scales with magnitude (`approxEquals` is absolute: scale `eps` yourself), and
exact predicates for geometric robustness (CORE-10 in the roadmap).
