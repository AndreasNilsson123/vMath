# Curves (`vmath.geo.Curves`, `ArcLengthTable`)

Experimental. Points are `dim` consecutive floats (1 to 4 dimensions), results are written into an array at an offset, evaluation is in double and allocates nothing.

- **Bézier** of any degree: `bezier`, `bezierTangent`, `bezierSplit` (de Casteljau). Degrees 1 to 3 use the closed Bernstein forms; higher degrees use the Bernstein weights by recurrence from the nearer
  end of the curve. Tested against a plain de Casteljau evaluation for degrees 0 to 11 in 1 to 4 dimensions, including parameters outside [0, 1].
- **Hermite**: `hermite`, `hermiteTangent`; equals the Bézier curve with control points `p0, p0 + m0 / 3, p1 - m1 / 3, p1` (a test).
- **Catmull-Rom**: `catmullRom` through every point of a list, open or closed, with `alpha` 0 (uniform), 0.5 (centripetal) or 1 (chordal), by the pyramidal formulation of Barry and Goldman. Repeated
  points (a zero chord) are handled by falling back to a unit knot interval.
- **Uniform cubic B-spline**: `bSpline`, `bSplineTangent`, open (4 or more points) or closed (3 or more); it does not pass through the control points but has a continuous second derivative.
- **`ArcLengthTable`**: samples any curve function at evenly spaced parameters and answers `length`, `lengthAt(t)` and the inverse `parameterAt(distance)` by binary search and linear interpolation, so
  a point can move along a curve at constant speed.

The table measures the curve with a polyline, which is slightly short on a curved arc. For a circle the relative shortfall is exactly `1 - n sin(pi / n) / pi` (checked by the test to 1e-9):

| Segments | Shortfall for a circle |
|---|---|
| 16 | 0.64% |
| 64 | 0.040% |
| 256 | 0.0025% |
| 1 024 | 0.00016% |

On a cubic Bézier test curve with a 2 000-segment table, 40 equal steps of arc length gave step distances within 5% of each other (the limit of the test; the table's own error is far below that).

Speed (JMH `UtilBench`, 3D points, JDK 25, single thread, 2026-10-03, time of one evaluation):

| Call | Time |
|---|---|
| `bezier`, cubic | 14.4 ns ± 2.7 ns |
| `bezier`, degree 7 | 69.1 ns ± 5.1 ns |
| `catmullRom`, centripetal, open | 107 ns ± 30 ns |
| `bSpline`, open | 23.2 ns ± 2.6 ns |

An earlier version computed `Math.pow` once per component and took 64 ns for the cubic; hoisting it and adding the closed forms for degrees 1 to 3 made it 4.5 times faster.

Not included: NURBS (rational curves), tangents of Catmull-Rom splines, curve fitting, curve-curve intersection and closest-point queries.
