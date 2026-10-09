package vmath.core;

import vmath.annotations.Bulk;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 2-component float vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 * Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec2f a = new Vec2f(3f, 4f);
 * float length = a.length();                 // 5
 * Vec2f unit = a.normalize();
 * Vec2f turned = a.rotate((float) Math.PI / 2);   // counter-clockwise, in radians
 * float side = Vec2f.orient(Vec2f.ZERO, a, new Vec2f(0f, 10f));  // positive: left of the line
 * }</pre>
 *
 * @param x the x component
 * @param y the y component
 */
@GenerateDouble
@ValueType
public record Vec2f(float x, float y) {

    /**
     * The zero vector.
     */
    public static final Vec2f ZERO = new Vec2f(0f, 0f);
    /**
     * The vector with every component 1.
     */
    public static final Vec2f ONE = new Vec2f(1f, 1f);
    /**
     * The unit vector along +X.
     */
    public static final Vec2f UNIT_X = new Vec2f(1f, 0f);
    /**
     * The unit vector along +Y.
     */
    public static final Vec2f UNIT_Y = new Vec2f(0f, 1f);

    /**
     * Broadcasts a scalar into every component.
     *
     * @param s the value of every component
     * @return a vector with every component equal to {@code s}
     */
    public static Vec2f splat(float s) {
        return new Vec2f(s, s);
    }

    /**
     * Adds the vectors component-wise.
     *
     * @param o the other vector; must not be {@code null}
     * @return the sum {@code this + o}
     */
    @Bulk
    public Vec2f add(Vec2f o) {
        return new Vec2f(x + o.x, y + o.y);
    }

    /**
     * Subtracts the vectors component-wise.
     *
     * @param o the other vector; must not be {@code null}
     * @return the difference {@code this - o}
     */
    @Bulk
    public Vec2f sub(Vec2f o) {
        return new Vec2f(x - o.x, y - o.y);
    }

    /**
     * Scales the vector by a scalar.
     *
     * @param s the factor
     * @return every component multiplied by {@code s}
     */
    @Bulk
    public Vec2f mul(float s) {
        return new Vec2f(x * s, y * s);
    }

    /**
     * Multiplies the vectors component-wise (the Hadamard product, not a dot product).
     *
     * @param o the other vector; must not be {@code null}
     * @return component-wise product
     */
    @Bulk
    public Vec2f mul(Vec2f o) {
        return new Vec2f(x * o.x, y * o.y);
    }

    /**
     * Divides the vector by a scalar; division by zero follows the IEEE rules and gives infinities
     * or NaN instead of throwing.
     *
     * @param s the divisor
     * @return every component divided by {@code s}; a zero divisor gives infinities or NaN, as in
     *     IEEE arithmetic
     */
    @Bulk
    public Vec2f div(float s) {
        float inv = 1f / s;
        return new Vec2f(x * inv, y * inv);
    }

    /**
     * Negates every component.
     *
     * @return every component negated
     */
    @Bulk
    public Vec2f negate() {
        return new Vec2f(-x, -y);
    }

    /**
     * Scales a vector and adds it to this one in one expression, without an intermediate vector.
     *
     * @param a the first vector; must not be {@code null}
     * @param s the factor for {@code a}
     * @return {@code this + a * s}
     */
    @Bulk
    public Vec2f fma(Vec2f a, float s) {
        return new Vec2f(x + a.x * s, y + a.y * s);
    }

    /**
     * Computes the dot product, which is the product of the lengths and the cosine of the angle
     * between the vectors.
     *
     * @param o the other vector; must not be {@code null}
     * @return the dot product
     */
    @Bulk
    public float dot(Vec2f o) {
        return x * o.x + y * o.y;
    }

    /**
     * Computes the two-dimensional cross product, the signed area of the parallelogram the vectors
     * span; its sign tells on which side of this vector the other lies.
     *
     * @param o the other vector; must not be {@code null}
     * @return z component of the 3D cross product (signed parallelogram area)
     */
    public float cross(Vec2f o) {
        return x * o.y - y * o.x;
    }

    /**
     * Rotates the vector by a quarter turn counter-clockwise; the length is preserved and no
     * trigonometry is needed.
     *
     * @return counter-clockwise perpendicular
     */
    public Vec2f perpendicular() {
        return new Vec2f(-y, x);
    }

    /**
     * Sums the squares of the components; avoids the square root, so prefer it for comparisons.
     *
     * @return the squared length
     */
    @Bulk
    public float lengthSquared() {
        return x * x + y * y;
    }

    /**
     * Computes the Euclidean length with a square root.
     *
     * @return the length
     */
    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    /**
     * Measures the Euclidean distance with a square root; prefer {@link #distanceSquared} when only
     * comparing.
     *
     * @param o the other vector; must not be {@code null}
     * @return the distance to {@code o}
     */
    public float distance(Vec2f o) {
        return sub(o).length();
    }

    /**
     * Scales the vector to unit length; the zero vector gives non-finite components.
     *
     * <p>A zero, NaN or infinite input yields NaN components. A very large or very small vector,
     * whose squared length would overflow or underflow, is scaled first and still gives the right
     * direction (the plain formula would return zeros or infinities for it).
     *
     * @return unit vector in the same direction
     */
    public Vec2f normalize() {
        float len2 = x * x + y * y;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Vec2f(x * inv, y * inv);
        }
        return normalizeScaled();
    }

    /**
     * The slow path of {@link #normalize()}: divide by the largest component first so that the
     * squares neither overflow nor underflow.
     */
    private Vec2f normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.abs(y));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Vec2f(Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b);
        return new Vec2f(a * inv, b * inv);
    }

    /**
     * Interpolates linearly between the vectors; the parameter is not clamped, so values outside
     * zero to one extrapolate.
     *
     * @param o the other vector; must not be {@code null}
     * @param t the interpolation parameter, 0 for this vector and 1 for {@code o}; not clamped
     * @return the linear interpolation {@code this + (o - this) * t}; {@code t} is not clamped
     */
    @Bulk
    public Vec2f lerp(Vec2f o, float t) {
        return new Vec2f(x + (o.x - x) * t, y + (o.y - y) * t);
    }

    /**
     * Takes the smaller of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise minimum
     */
    @Bulk
    public Vec2f min(Vec2f o) {
        return new Vec2f(Math.min(x, o.x), Math.min(y, o.y));
    }

    /**
     * Takes the larger of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise maximum
     */
    @Bulk
    public Vec2f max(Vec2f o) {
        return new Vec2f(Math.max(x, o.x), Math.max(y, o.y));
    }

    /**
     * Adds a vector given as separate components, component-wise.
     *
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @return the sum of this and the vector with the given components
     */
    public Vec2f add(float ox, float oy) {
        return new Vec2f(x + ox, y + oy);
    }

    /**
     * Measures the squared Euclidean distance, which avoids the square root and is the right choice
     * for comparing distances.
     *
     * @param o the other vector; must not be {@code null}
     * @return the squared distance to {@code o}; cheaper than {@link #distance}
     */
    @Bulk
    public float distanceSquared(Vec2f o) {
        float dx = x - o.x, dy = y - o.y;
        return dx * dx + dy * dy;
    }

    /**
     * Returns like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input.
     *
     * @return {@link #ZERO} for (near-)zero input
     */
    public Vec2f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        if (!(len2 <= Float.MAX_VALUE)) { // overflowed (a huge vector keeps its direction) or NaN (stays NaN)
            return normalizeScaled();
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec2f(x * inv, y * inv);
    }

    /**
     * Computes the unsigned angle between the vectors from the dot and perp-dot products, which
     * stays accurate for small angles; undefined for a zero vector.
     *
     * @param o the other vector; must not be {@code null}
     * @return unsigned angle between this and {@code o} in radians, in [0, PI]
     */
    public float angle(Vec2f o) {
        return (float) Math.atan2(Math.abs(cross(o)), dot(o));
    }

    /**
     * Takes the absolute value per component.
     *
     * @return the absolute value of every component
     */
    @Bulk
    public Vec2f abs() {
        return new Vec2f(Math.abs(x), Math.abs(y));
    }

    /**
     * Builds a unit vector from a polar angle, using sine and cosine.
     *
     * @param angle the angle in radians
     * @return the unit vector at {@code angle} radians counter-clockwise from +X:
     *     {@code (cos angle, sin angle)}
     */
    public static Vec2f fromAngle(float angle) {
        return new Vec2f((float) Math.cos(angle), (float) Math.sin(angle));
    }

    /**
     * Computes the polar angle of the vector with {@code atan2}.
     *
     * @return the polar angle of this vector in radians, counter-clockwise from +X, in
     *     {@code (-PI, PI]}; 0 for the zero vector
     */
    public float polarAngle() {
        return (float) Math.atan2(y, x);
    }

    /**
     * Computes the signed angle that turns this vector onto another, with {@code atan2} of the
     * perp-dot and dot products.
     *
     * @param o the other vector; must not be {@code null}
     * @return the signed angle in radians that rotates this vector onto {@code o},
     *     counter-clockwise positive, in {@code [-PI, PI]}: {@code atan2(perpDot, dot)}
     */
    public float signedAngle(Vec2f o) {
        return (float) Math.atan2(cross(o), dot(o));
    }

    /**
     * Computes the perp-dot product, the two-dimensional analogue of the cross product, whose sign
     * tells on which side the other vector lies.
     *
     * <p>The same as {@link #cross}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the perp-dot product {@code x * o.y - y * o.x}, the 2D cross product: positive when
     *     {@code o} is counter-clockwise from this vector
     */
    public float perpDot(Vec2f o) {
        return x * o.y - y * o.x;
    }

    /**
     * Rotates counter-clockwise by {@code angle} radians about the point {@code pivot}.
     *
     * @param pivot the pivot; must not be {@code null}
     * @param angle the angle in radians
     * @return the rotated vector, never {@code null}
     */
    public Vec2f rotateAround(Vec2f pivot, float angle) {
        return sub(pivot).rotate(angle).add(pivot);
    }

    /**
     * Evaluates the orientation of three points in floating point, which is not exact for nearly
     * collinear points; use {@link vmath.core.Predicates} when the sign must be right.
     *
     * <p>The orientation test of 2D geometry ({@code vmath.core.Predicates.orient2d} is the exact
     * version for the decision alone).
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @return twice the signed area of the triangle {@code a, b, c}: positive when the three points
     *     run counter-clockwise, negative when clockwise, zero when collinear
     */
    public static float orient(Vec2f a, Vec2f b, Vec2f c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /**
     * Rotates counter-clockwise by {@code angle} radians.
     *
     * @param angle the angle in radians
     * @return the rotated vector, never {@code null}
     */
    public Vec2f rotate(float angle) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        return new Vec2f(c * x - s * y, s * x + c * y);
    }

    /**
     * Selects the smallest component.
     *
     * @return the smallest component
     */
    public float minComponent() {
        return Math.min(x, y);
    }

    /**
     * Selects the largest component.
     *
     * @return the largest component
     */
    public float maxComponent() {
        return Math.max(x, y);
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when no component is NaN or infinite
     */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y);
    }

    /**
     * Restricts each component to a per-component range; the lower bound is expected not to exceed
     * the upper bound.
     *
     * @param lo the lower bound; must not be {@code null}
     * @param hi the upper bound; must not be {@code null}
     * @return component-wise clamp to {@code [lo, hi]}
     */
    public Vec2f clamp(Vec2f lo, Vec2f hi) {
        return new Vec2f(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y));
    }

    /**
     * Restricts each component to the same scalar range.
     *
     * @param lo the lower bound
     * @param hi the upper bound
     * @return every component clamped to {@code [lo, hi]}
     */
    public Vec2f clamp(float lo, float hi) {
        return new Vec2f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi));
    }

    /**
     * Clamps every component to [0, 1].
     *
     * @return the clamped vector, never {@code null}
     */
    public Vec2f saturate() {
        return clamp(0f, 1f);
    }

    /**
     * Rounds each component down to a whole number; the result stays a floating-point vector.
     *
     * @return every component rounded down to a whole number
     */
    public Vec2f floor() {
        return new Vec2f((float) Math.floor(x), (float) Math.floor(y));
    }

    /**
     * Rounds each component up to a whole number; the result stays a floating-point vector.
     *
     * @return every component rounded up to a whole number
     */
    public Vec2f ceil() {
        return new Vec2f((float) Math.ceil(x), (float) Math.ceil(y));
    }

    /**
     * Keeps only the fractional part, as the GLSL function of the same name does; the result is
     * always in {@code [0, 1)}, also for negative components.
     *
     * @return fractional part as in GLSL: {@code v - floor(v)}, always in [0, 1)
     */
    public Vec2f fract() {
        return new Vec2f(x - (float) Math.floor(x), y - (float) Math.floor(y));
    }

    /**
     * Reduces each component to its sign, as the GLSL function of the same name does; NaN stays
     * NaN.
     *
     * @return -1, 0 or +1 per component (NaN stays NaN)
     */
    public Vec2f sign() {
        return new Vec2f(Math.signum(x), Math.signum(y));
    }

    /**
     * Applies a hard threshold per component, as the GLSL function of the same name does.
     *
     * @param edge the edge
     * @return 0 where the component is below {@code edge}, else 1
     */
    public Vec2f step(float edge) {
        return new Vec2f(x < edge ? 0f : 1f, y < edge ? 0f : 1f);
    }

    /**
     * Applies the smooth cubic Hermite blend per component, as the GLSL function of the same name
     * does; the edges must differ.
     *
     * @param e0 the lower edge
     * @param e1 the upper edge
     * @return hermite interpolation of each component between {@code e0} and {@code e1}, as in GLSL
     */
    public Vec2f smoothstep(float e0, float e1) {
        return new Vec2f(smooth(x, e0, e1), smooth(y, e0, e1));
    }

    private static float smooth(float v, float e0, float e1) {
        float t = Math.min(Math.max((v - e0) / (e1 - e0), 0f), 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * Mirrors the vector about a plane given by its normal, as the GLSL function of the same name
     * does; the normal must have unit length.
     *
     * @param n the vector; must not be {@code null}
     * @return mirror around the unit normal {@code n}: {@code this - 2 (this . n) n}
     */
    public Vec2f reflect(Vec2f n) {
        float d = 2f * dot(n);
        return new Vec2f(x - d * n.x, y - d * n.y);
    }

    /**
     * Bends the vector as light bends at a surface, as the GLSL function of the same name does; the
     * vector and the normal must have unit length, and total internal reflection gives the zero
     * vector.
     *
     * <p>This vector must be a unit incident direction. Returns {@link #ZERO} on total internal
     * reflection.
     *
     * @param n the vector; must not be {@code null}
     * @param eta the eta
     * @return refraction through a surface with unit normal {@code n} and index ratio {@code eta}
     *     (GLSL {@code refract})
     */
    public Vec2f refract(Vec2f n, float eta) {
        float d = dot(n);
        float k = 1f - eta * eta * (1f - d * d);
        if (k < 0f) {
            return ZERO;
        }
        float s = eta * d + (float) Math.sqrt(k);
        return new Vec2f(eta * x - s * n.x, eta * y - s * n.y);
    }

    /**
     * Projects onto another vector, giving the parallel component; the other vector must not be
     * zero.
     *
     * <p>Returns {@link #ZERO} when {@code onto} is zero.
     *
     * @param onto the onto; must not be {@code null}
     * @return component of this vector along {@code onto}
     */
    public Vec2f project(Vec2f onto) {
        float d = onto.lengthSquared();
        if (d == 0f) {
            return ZERO;
        }
        float s = dot(onto) / d;
        return new Vec2f(onto.x * s, onto.y * s);
    }

    /**
     * Removes the parallel component along another vector, giving the perpendicular component; the
     * other vector must not be zero.
     *
     * @param onto the onto; must not be {@code null}
     * @return component perpendicular to {@code onto}: {@code this - project(onto)}
     */
    public Vec2f reject(Vec2f onto) {
        return sub(project(onto));
    }

    /**
     * Flips the vector to face against an incident direction, as the GLSL function of the same name
     * does.
     *
     * @param incident the incident; must not be {@code null}
     * @param ref the ref; must not be {@code null}
     * @return GLSL {@code faceforward}: this vector, flipped when {@code ref . incident >= 0}
     */
    public Vec2f faceForward(Vec2f incident, Vec2f ref) {
        return ref.dot(incident) < 0f ? this : negate();
    }

    /**
     * Reads a component by index, for loops that treat the vector as an array.
     *
     * @param i the index
     * @return component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException}
     *     for any other index
     * @throws IndexOutOfBoundsException if {@code i} is not a component index
     */
    public float get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /**
     * Compares two vectors component by component with an absolute tolerance, for tests and for
     * detecting changes; not a scale-relative comparison.
     *
     * @param o the other vector; must not be {@code null}
     * @param eps the tolerance
     * @return {@code true} when every component differs from that of {@code o} by at most
     *     {@code eps}
     */
    public boolean approxEquals(Vec2f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps;
    }

    /**
     * Writes the components to {@code dst[off ..]} in order.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(float[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
    }

    /**
     * Writes the vector at the absolute position {@code index}; does not change the buffer
     * position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Vec2d toDouble() {
        return new Vec2d(x, y);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Vec2f toFloat() {
        return new Vec2f((float) x, (float) y);
    }

    /**
     * Reads a vector from text in any of the forms the types print or people write: the {@code toString} of the record
     * ({@code Vec2f[x=1.0, y=1.0]}), the compact form of {@link #toCompactString()}, or a bare list such as {@code 1 2 3}.
     * Brackets and bars are ignored and the numbers are separated by commas, semicolons or white space. Labelled numbers may come in any
     * order; without labels they are taken in the order of the components. {@code NaN} and {@code Infinity} are numbers.
     *
     * @param text the text; must not be {@code null}
     * @return the vector with the 2 numbers of the text
     * @throws IllegalArgumentException if the text does not hold exactly 2 numbers, mixes labelled and unlabelled ones, names a component
     *     twice or not at all, starts with the name of another type, or holds a malformed number
     */
    public static Vec2f parse(CharSequence text) {
        String[] t = Text.tokens(text, "Vec2f", Text.VEC2, null);
        return new Vec2f(Float.parseFloat(t[0]), Float.parseFloat(t[1]));
    }

    /**
     * Gives the compact text of the vector, such as {@code (1.0, 2.0, 3.0)}, with every component in the shortest form that reads back exactly.
     *
     * @return the components in order between parentheses; {@link #parse(CharSequence)} gives back an equal vector
     */
    public String toCompactString() {
        return "(" + x + ", " + y + ")";
    }

    /**
     * Gives the text of the vector with a fixed number of decimals, for a HUD or a log.
     *
     * @param decimals the digits after the point, 0 to 17
     * @return the components in order between parentheses, each rounded to {@code decimals} digits (not read back exactly)
     * @throws IllegalArgumentException if {@code decimals} is outside 0 to 17
     */
    public String format(int decimals) {
        return "(" + Text.fixed(x, decimals) + ", " + Text.fixed(y, decimals) + ")";
    }
}
