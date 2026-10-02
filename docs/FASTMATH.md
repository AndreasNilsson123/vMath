# FastMath: polynomial approximations (CORE-9)

`vmath.core.FastMath` (experimental) has `float` versions of `sin`, `cos`, `atan`, `atan2`, `acos`, `asin`, `exp` and `log`. It is opt-in: no other class of the library calls it, and
`Math` is what everything else uses. Each method documents its error, the tests measure the largest error against `Math` in double precision over sweeps of millions of
arguments and fail if it exceeds the documented bound, and the arguments outside the stated range (huge angles, zero, NaN, infinities, subnormal results) are handed to `Math`, so
their results are exactly `Math`'s.

## Accuracy

Measured on 2026-10-02 (`FastMathTest`, `-Dvmath.verbose=true`; the sweeps walk the float bit patterns with a stride, so every magnitude is covered). The errors are mostly the
rounding of the result to a float (half a unit in the last place), not the polynomial: a polynomial error of a few `1e-8` rides on top.

| Function | Domain | Largest error measured | Documented bound |
|---|---|---|---|
| `sin`, `cos` | `abs(x) <= 1e6`, any larger value goes to `Math` | 3.0e-8 absolute | 5e-8 absolute |
| `atan` | all finite | 9.7e-8 absolute | 1.2e-7 absolute |
| `atan2` | finite, not both zero | 1.57e-7 absolute (results near pi) | 2e-7 absolute |
| `acos` | `[-1, 1]` | 1.35e-7 absolute (results near 2) | 1.5e-7 absolute |
| `asin` | `[-1, 1]` | 6.8e-8 absolute | 1.5e-7 absolute |
| `exp` | normal results (about -87 to 88) | 6.0e-8 relative | 1e-7 relative |
| `log` | positive normal | 6.0e-8 relative | 1e-7 relative |

`asin` is `pi/2 - acos`, so its error is absolute: for an argument near zero the relative error of the result can be large.

## Speed

JMH `FastMathBench`, 2 forks of 5 one-second iterations, 4 096 arguments per call (angles within +-10 radians; unit range for `acos`; +-10 for `exp`; 1e-3 to 1e3 for `log`), JDK 25,
the cost of one call in ns (the score divided by 4 096):

| Function | `(float) Math.x` | `FastMath.x` | Speed-up |
|---|---|---|---|
| `sin` | 10.7 | 4.6 | 2.3 times |
| `cos` | 10.4 | 4.1 | 2.5 times |
| `atan2` | 15.7 | 6.3 | 2.5 times |
| `acos` | 6.6 | 4.3 | 1.5 times |
| `log` | 8.3 | 5.7 | 1.5 times |
| `exp` | 6.9 | 5.5 | 1.25 times |

The gains are real but of a small constant: `Math.sin` is already an intrinsic. The biggest win is where a loop calls `sin`, `cos` or `atan2` on every element; for one call per
object per frame it will not show.

## What was tried and removed

`invSqrt`, the bit-pattern estimate (`0x5F375A86`) plus two Newton steps, reaches a relative error of 4.7e-6 and costs **1.8 ns per call against 0.92 ns for
`1f / (float) Math.sqrt(x)`**: twice as slow. A modern JIT turns the square root and the division into the hardware instructions, which beat a trick that was designed for CPUs
without them. It was removed from `FastMath`; the loop is kept in `FastMathBench.invSqrtFast` so that the measurement can be repeated. The library's own `normalize` and
`length` keep using `Math.sqrt`.

## How they work

- `sin`, `cos`: reduce by `pi` in double (`q = rint(x / pi)`, `r = x - q pi`, so `abs(r) <= pi/2`), a Taylor polynomial of degree 13 (`sin`) or 12 (`cos`) in `r`, and the sign `(-1)^q`.
- `atan`: for `abs(x) > 1` use `pi/2 - atan(1/x)`; on `[0, 1]` an odd minimax polynomial of degree 15 (eight terms, fitted by Lawson's iteration on 20 001 points; the fit's own
  error is 3.7e-8). `atan2` reduces to that and fixes the quadrant, with the signs of zeros as `Math.atan2`.
- `acos`: Abramowitz and Stegun 4.4.46, `sqrt(1 - x)` times a degree-7 polynomial on `[0, 1]`, and `acos(-x) = pi - acos(x)`.
- `exp`: `x = k ln 2 + r` with `abs(r) <= 0.347`, a degree-8 polynomial for `e^r`, and `2^k` built from the exponent bits.
- `log`: split the float into exponent and mantissa in `[sqrt(1/2), sqrt(2))`, `log(m) = 2 atanh(s)` with `s = (m - 1)/(m + 1)` and a six-term series.

All the arithmetic is in double and only the result is rounded to float: on current CPUs a double multiply-add costs the same as a float one, and the intermediate rounding of a float
evaluation would cost digits.
