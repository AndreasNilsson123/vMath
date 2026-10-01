# Colour (`vmath.color`, experimental)

Static, allocation-free colour maths: results go to a `float[3]` you pass in (with `Vec3f` overloads where convenient), and the array methods work in place. Everything
is in float; "linear" means linear light (the space lighting and blending belong in) and "encoded" means sRGB as stored in 8-bit textures.

## sRGB transfer function (`Srgb`)

`toLinear` and `fromLinear` are the exact piecewise functions of IEC 61966-2-1. Values below zero mirror (`f(-x) = -f(x)`), values above one continue along the curve (HDR), NaN stays
NaN. `byteToLinear(int)` is a 256-entry table and `linearToByte(float)` is the exact encoding rounded to nearest, so every byte survives decode then encode (tested for all 256).
The `Fast` variants are for inputs in `[0, 1]` (clamped): a cubic polynomial for decoding, and for encoding the exact linear segment below 0.0031308 plus a sum of three square roots above it
(the square-root fit alone is wrong by 0.037, nine 8-bit steps, near black, which is why the linear segment is kept).

Measured over 200 001 evenly spaced inputs in `[0, 1]` (`ColorTest`): decoding is off by at most 0.00167 (0.43 of an 8-bit step), encoding by at most 0.00097 (0.25 of a step); the fast
encoder gives a different byte than the exact one for 1.63% of inputs and never more than one off.

The array methods take the components per pixel and convert the first three channels; **alpha is linear and is never touched**. `Srgb.toLinear(float[], offset, pixels, components, fast)`.

## HSV, HSL, Oklab, Oklch (`ColorSpaces`)

- **HSV and HSL**: hue in degrees `[0, 360)`, the other two in `[0, 1]`. These are conveniences of the *encoded* cube, what a colour picker shows; they are not perceptual. Tested against known colours
  and against the textbook alternative formulas for conversion in both directions (50 000 random colours, to 2e-6), plus round trips.
- **Oklab** (Ottosson 2020) takes and returns *linear* sRGB: `L` lightness, `a` green to red, `b` blue to yellow. The primaries match the published values to 1e-3, greys have no chroma
  and `L` is the cube root of the linear value, round trips are accurate to 3e-5. `mixOklab` blends two colours perceptually: black to white at the midpoint has `L = 0.5`, which is linear 0.125.
  **Oklch** is its polar form. Colours outside the sRGB gamut give components outside `[0, 1]` when converted back; no gamut mapping is provided, clamp or reduce the chroma yourself.
- `luminance(r, g, b)` is the Rec. 709 relative luminance of a linear colour.

## Tone mapping (`ToneMap`)

Scene-referred linear light to `[0, 1]` before `Srgb.fromLinear`: `reinhard`, `reinhardExtended` (white point), `aces` (the Narkowicz fit, clamped), `hable` (the Uncharted 2 curve, normalized so 11.2 maps to 1)
and `exposure` (`1 - exp(-x)`). Each is applied per channel, so bright colours desaturate towards white; `byLuminance` scales all channels by `curve(Y) / Y` to keep the hue instead. Tested: every curve starts
at 0, is monotonic and stays within `[0, 1]` up to 100, hits its analytic points (Reinhard(1) = 0.5, the extended white point maps to 1, ACES(1) = 2.54 / 3.16, `exposure` has no precision loss near zero), and `byLuminance`
keeps channel ratios. These are the published formulas, not colour-managed film emulations: no AgX, no gamut handling, and no exposure control (multiply by `2^EV` first).

## Premultiplied alpha (`PremultipliedAlpha`)

`premultiply` and `unpremultiply` (alpha 0 gives all zeros), the `over` operator for premultiplied colours (tested associative on 5 000 random layer triples and with the expected end points), and the packed 8-bit forms
`premultiplyRgba8` / `unpremultiplyRgba8` (red in the lowest byte). The 8-bit product is `(c * a + 127) / 255`, which is exactly round-to-nearest because 255 is odd; checked against `Math.round` for all 65 536
colour and alpha combinations, and re-premultiplying an unpremultiplied valid pixel returns the same pixel for every one. Do the arithmetic in linear space.

## Measured

`ColorBench`, JDK 25, one machine, 100 000 RGBA pixels (300 000 colour channels) per call, the array copy that resets the data included (55 us):

| Operation | Time | Per channel or colour |
|---|---|---|
| `Srgb.toLinear`, exact | 6 676 us | 22 ns per channel |
| `Srgb.toLinear`, fast | 362 us | 1.2 ns per channel (18 times faster) |
| `Srgb.fromLinear`, exact | 6 059 us | 20 ns per channel |
| `Srgb.fromLinear`, fast | 1 491 us | 5.0 ns per channel (4 times faster) |
| `linearToByte`, exact / fast (100 000 values) | 2 225 us (+-570) / 518 us (+-326) | 22 ns / 5 ns |
| `byteToLinear` table (100 000 values) | 91 us | 0.9 ns |
| `ToneMap.apply` ACES / Hable | 545 us / 641 us | about 2 ns per channel |
| Oklab, linear sRGB to Oklab and back (100 000 colours) | 3 448 us | 34 ns per colour |
| HSV round trip (100 000 colours) | 3 233 us | 32 ns per colour |
| premultiply then unpremultiply | 282 us | 2.8 ns per pixel |

## Not covered

Wide-gamut spaces (Display P3, Rec. 2020, ACEScg) and chromatic adaptation, gamut mapping, CIE Lab/LCh, colour temperature, LUT-based grading, and AgX or other image-formation transforms.
