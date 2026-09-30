# API parity

Sibling types offer the same operations under the same names, so code written against one carries over to the next. This is **enforced**: `ApiParityTest`
reads the public methods of the built classes and fails, naming the type and the operation, when one is missing. The tables below are that test's
required lists; add an operation to a type and the list it belongs to in the same change.

An operation counts by name, so overloads (for example `add(Vec3f)` and `add(float, float, float)`) count once.

## Vectors: `Vec2f`, `Vec3f`, `Vec4f`

`abs`, `add`, `angle`, `approxEquals`, `ceil`, `clamp`, `distance`, `distanceSquared`, `div`, `dot`, `equals`, `faceForward`, `floor`, `fma`, `fract`, `get`,
`hashCode`, `isFinite`, `length`, `lengthSquared`, `lerp`, `max`, `maxComponent`, `min`, `minComponent`, `mul`, `negate`, `normalize`, `normalizeOrZero`,
`project`, `reflect`, `refract`, `reject`, `saturate`, `sign`, `smoothstep`, `splat`, `step`, `sub`, `toDouble`, `toString`, `writeTo`.

On purpose only on some: `Vec3f.cross` and `Vec2f.cross` (in 2D the scalar perp-dot product; none in 4D), `Vec3f.anyPerpendicular`, `Vec2f.perpendicular`, `Vec2f.rotate`,
and the homogeneous-coordinate helpers of `Vec4f` (`w`, `xyz`, `point`, `direction`, `divideByW`).

## Matrices: `Mat3f`, `Mat4f`, `Mat4x3f`

All three: `approxEquals`, `column`, `determinant`, `equals`, `fromArray`, `fromColumns`, `get`, `hashCode`, `invert`, `isFinite`, `mul`, `rotation`, `rotationAxis`,
`rotationX`, `rotationY`, `rotationZ`, `scaling`, `toDouble`, `toString`, `transform`, `writeTo`.

Square matrices (`Mat3f`, `Mat4f`): `row`, `transpose`. Affine matrices (`Mat4f`, `Mat4x3f`): `decompose`, `getTranslation`, `normalMatrix`, `transformDirection`,
`transformPosition`, `translation`, `upperLeft3x3`, `withTranslation`.

On purpose only on some: `Mat4f.isAffine`, `lookAt`, `lookTo`, `perspective*`, `ortho`, `frustum` (4x4 builders), `Mat3f.skew`, `basisFromNormal`, `normal`,
`Mat3f.decompose` (a 3x3 has no translation; use `Quatf.fromMat3`), and no `transpose` or `row` on `Mat4x3f` (not square).

## Rotations: `Quatf`

`angle`, `approxEquals`, `axis`, `conjugate`, `dot`, `equals`, `exp`, `fromAxisAngle`, `fromEuler`, `fromMat3`, `fromTo`, `hashCode`, `integrate`, `invert`, `isFinite`,
`length`, `lengthSquared`, `log`, `lookRotation`, `mul`, `nlerp`, `normalize`, `pow`, `slerp`, `squad`, `swing`, `toDouble`, `toEuler`, `toMat3`, `toMat4`, `toString`,
`transform`, `twist`.

## Shapes: `Aabbf`, `Spheref`, `Planef`, `Rayf`, `Trianglef`, `Obbf`, `Frustumf`, `Segmentf`, `Capsulef`

All: `equals`, `hashCode`, `toDouble`, `toString`. With a nearest point (`closestPoint`): every shape except `Frustumf`. That can be transformed by a matrix (`transform`):
`Aabbf`, `Spheref`, `Planef`, `Rayf`, `Trianglef`, `Segmentf`, `Capsulef`. On purpose not: `Obbf.transform` (an oriented box under a general affine map is not a box) and `Frustumf.transform`
(a frustum is rebuilt from its view-projection).

## Float and double twins

Every `...d` type is generated from its `...f` template, and the test checks the result: each public method of a float type exists on the double twin with the
same name, parameter count and staticness, and the double type converts back with `toFloat`. The intended differences:

| Only on the float type | Why |
|---|---|
| `toDouble` | the double type has `toFloat` instead |
| `writeTo(FloatBuffer, ...)` | buffers are float-only; the double types write arrays |

| Only on the double type | Why |
|---|---|
| `toFloat` | narrowing conversion |
| `relativeTo(origin)` (`Vec3d`, `Aabbd`, `Sphered`) | camera-relative rendering: subtract in double, then narrow to float |

## Adding an operation

1. Write it in the float template (`src/template/java/...`); mark float-only or double-only members with `@FloatOnly` / `@DoubleOnly`.
2. If siblings could have it, add it to all of them and to the list above (`ApiParityTest.FAMILIES`); if it makes sense for some only, add it to `ApiParityTest.EXCEPTIONS` with the reason.
3. Add a case to the matching test template (`src/testTemplate/java/...`): it is generated for both precisions.
