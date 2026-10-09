package vmath.core;

import vmath.annotations.Bulk;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 4-component float vector (homogeneous coordinates, colors, plane equations).
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
 * Vec4f position = Vec4f.point(new Vec3f(1f, 2f, 3f));    // w = 1
 * Vec4f direction = Vec4f.direction(Vec3f.UNIT_Y);        // w = 0
 * Vec4f clip = Mat4f.perspective(1f, 1.5f, 0.1f, 100f, ClipSpace.OPENGL).transform(position);
 * Vec3f ndc = clip.divideByW();
 * }</pre>
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 * @param w the w component
 */
@GenerateDouble
@ValueType
public record Vec4f(float x, float y, float z, float w) {

    /**
     * The zero vector.
     */
    public static final Vec4f ZERO = new Vec4f(0f, 0f, 0f, 0f);
    /**
     * The vector with every component 1.
     */
    public static final Vec4f ONE = new Vec4f(1f, 1f, 1f, 1f);

    /**
     * Broadcasts a scalar into every component.
     *
     * @param s the value of every component
     * @return a vector with every component equal to {@code s}
     */
    public static Vec4f splat(float s) {
        return new Vec4f(s, s, s, s);
    }

    /**
     * Lifts a three-dimensional position to homogeneous coordinates with {@code w = 1}, so that
     * translations apply to it.
     *
     * @param v the vector; must not be {@code null}
     * @return point: {@code (v, 1)}
     */
    public static Vec4f point(Vec3f v) {
        return new Vec4f(v.x(), v.y(), v.z(), 1f);
    }

    /**
     * Lifts a three-dimensional direction to homogeneous coordinates with {@code w = 0}, so that
     * translations do not apply to it.
     *
     * @param v the vector; must not be {@code null}
     * @return direction: {@code (v, 0)}
     */
    public static Vec4f direction(Vec3f v) {
        return new Vec4f(v.x(), v.y(), v.z(), 0f);
    }

    /**
     * Adds the vectors component-wise.
     *
     * @param o the other vector; must not be {@code null}
     * @return the sum {@code this + o}
     */
    @Bulk
    public Vec4f add(Vec4f o) {
        return new Vec4f(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    /**
     * Subtracts the vectors component-wise.
     *
     * @param o the other vector; must not be {@code null}
     * @return the difference {@code this - o}
     */
    @Bulk
    public Vec4f sub(Vec4f o) {
        return new Vec4f(x - o.x, y - o.y, z - o.z, w - o.w);
    }

    /**
     * Scales the vector by a scalar.
     *
     * @param s the factor
     * @return every component multiplied by {@code s}
     */
    @Bulk
    public Vec4f mul(float s) {
        return new Vec4f(x * s, y * s, z * s, w * s);
    }

    /**
     * Multiplies the vectors component-wise (the Hadamard product, not a dot product).
     *
     * @param o the other vector; must not be {@code null}
     * @return component-wise product
     */
    @Bulk
    public Vec4f mul(Vec4f o) {
        return new Vec4f(x * o.x, y * o.y, z * o.z, w * o.w);
    }

    /**
     * Negates every component.
     *
     * @return every component negated
     */
    @Bulk
    public Vec4f negate() {
        return new Vec4f(-x, -y, -z, -w);
    }

    /**
     * Computes the four-dimensional dot product.
     *
     * @param o the other vector; must not be {@code null}
     * @return the dot product
     */
    @Bulk
    public float dot(Vec4f o) {
        return x * o.x + y * o.y + z * o.z + w * o.w;
    }

    /**
     * Sums the squares of the components; avoids the square root, so prefer it for comparisons.
     *
     * @return the squared length
     */
    @Bulk
    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
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
     * Scales the vector to unit length; the zero vector gives non-finite components.
     *
     * <p>A zero, NaN or infinite input yields NaN components. A very large or very small vector,
     * whose squared length would overflow or underflow, is scaled first and still gives the right
     * direction (the plain formula would return zeros or infinities for it).
     *
     * @return unit vector in the same direction
     */
    public Vec4f normalize() {
        float len2 = x * x + y * y + z * z + w * w;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Vec4f(x * inv, y * inv, z * inv, w * inv);
        }
        return normalizeScaled();
    }

    /**
     * The slow path of {@link #normalize()}: divide by the largest component first so that the
     * squares neither overflow nor underflow.
     */
    private Vec4f normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.max(Math.abs(z), Math.abs(w))));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Vec4f(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m, c = z / m, d = w / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c + d * d);
        return new Vec4f(a * inv, b * inv, c * inv, d * inv);
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
    public Vec4f lerp(Vec4f o, float t) {
        return new Vec4f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t, w + (o.w - w) * t);
    }

    /**
     * Drops the fourth component without dividing by it; use {@link #divideByW()} for a perspective
     * divide.
     *
     * @return the first three components
     */
    public Vec3f xyz() {
        return new Vec3f(x, y, z);
    }

    /**
     * Performs the perspective divide that turns clip coordinates into normalised device
     * coordinates; a {@code w} of zero gives non-finite components.
     *
     * @return perspective divide: {@code xyz / w}
     */
    public Vec3f divideByW() {
        float inv = 1f / w;
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /**
     * Adds a vector given as separate components, component-wise.
     *
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param ow the w component to add
     * @return the sum of this and the vector with the given components
     */
    public Vec4f add(float ox, float oy, float oz, float ow) {
        return new Vec4f(x + ox, y + oy, z + oz, w + ow);
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
    public Vec4f div(float s) {
        float inv = 1f / s;
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    /**
     * Scales a vector and adds it to this one in one expression, without an intermediate vector.
     *
     * @param a the first vector; must not be {@code null}
     * @param s the factor for {@code a}
     * @return {@code this + a * s}
     */
    @Bulk
    public Vec4f fma(Vec4f a, float s) {
        return new Vec4f(x + a.x * s, y + a.y * s, z + a.z * s, w + a.w * s);
    }

    /**
     * Measures the squared Euclidean distance, which avoids the square root and is the right choice
     * for comparing distances.
     *
     * @param o the other vector; must not be {@code null}
     * @return the squared distance to {@code o}; cheaper than {@link #distance}
     */
    @Bulk
    public float distanceSquared(Vec4f o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z, dw = w - o.w;
        return dx * dx + dy * dy + dz * dz + dw * dw;
    }

    /**
     * Measures the Euclidean distance with a square root; prefer {@link #distanceSquared} when only
     * comparing.
     *
     * @param o the other vector; must not be {@code null}
     * @return the distance to {@code o}
     */
    public float distance(Vec4f o) {
        return (float) Math.sqrt(distanceSquared(o));
    }

    /**
     * Returns like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input.
     *
     * @return {@link #ZERO} for (near-)zero input
     */
    public Vec4f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        if (!(len2 <= Float.MAX_VALUE)) { // overflowed (a huge vector keeps its direction) or NaN (stays NaN)
            return normalizeScaled();
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec4f(x * inv, y * inv, z * inv, w * inv);
    }

    /**
     * Takes the smaller of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise minimum
     */
    @Bulk
    public Vec4f min(Vec4f o) {
        return new Vec4f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z), Math.min(w, o.w));
    }

    /**
     * Takes the larger of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise maximum
     */
    @Bulk
    public Vec4f max(Vec4f o) {
        return new Vec4f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z), Math.max(w, o.w));
    }

    /**
     * Takes the absolute value per component.
     *
     * @return the absolute value of every component
     */
    @Bulk
    public Vec4f abs() {
        return new Vec4f(Math.abs(x), Math.abs(y), Math.abs(z), Math.abs(w));
    }

    /**
     * Selects the smallest component.
     *
     * @return the smallest component
     */
    public float minComponent() {
        return Math.min(Math.min(Math.min(x, y), z), w);
    }

    /**
     * Selects the largest component.
     *
     * @return the largest component
     */
    public float maxComponent() {
        return Math.max(Math.max(Math.max(x, y), z), w);
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when no component is NaN or infinite
     */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z) && Float.isFinite(w);
    }

    /**
     * Restricts each component to a per-component range; the lower bound is expected not to exceed
     * the upper bound.
     *
     * @param lo the lower bound; must not be {@code null}
     * @param hi the upper bound; must not be {@code null}
     * @return component-wise clamp to {@code [lo, hi]}
     */
    public Vec4f clamp(Vec4f lo, Vec4f hi) {
        return new Vec4f(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y), Math.min(Math.max(z, lo.z), hi.z), Math.min(Math.max(w, lo.w), hi.w));
    }

    /**
     * Restricts each component to the same scalar range.
     *
     * @param lo the lower bound
     * @param hi the upper bound
     * @return every component clamped to {@code [lo, hi]}
     */
    public Vec4f clamp(float lo, float hi) {
        return new Vec4f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi), Math.min(Math.max(z, lo), hi), Math.min(Math.max(w, lo), hi));
    }

    /**
     * Clamps every component to [0, 1].
     *
     * @return the clamped vector, never {@code null}
     */
    public Vec4f saturate() {
        return clamp(0f, 1f);
    }

    /**
     * Rounds each component down to a whole number; the result stays a floating-point vector.
     *
     * @return every component rounded down to a whole number
     */
    public Vec4f floor() {
        return new Vec4f((float) Math.floor(x), (float) Math.floor(y), (float) Math.floor(z), (float) Math.floor(w));
    }

    /**
     * Rounds each component up to a whole number; the result stays a floating-point vector.
     *
     * @return every component rounded up to a whole number
     */
    public Vec4f ceil() {
        return new Vec4f((float) Math.ceil(x), (float) Math.ceil(y), (float) Math.ceil(z), (float) Math.ceil(w));
    }

    /**
     * Keeps only the fractional part, as the GLSL function of the same name does; the result is
     * always in {@code [0, 1)}, also for negative components.
     *
     * @return fractional part as in GLSL: {@code v - floor(v)}, always in [0, 1)
     */
    public Vec4f fract() {
        return new Vec4f(x - (float) Math.floor(x), y - (float) Math.floor(y), z - (float) Math.floor(z), w - (float) Math.floor(w));
    }

    /**
     * Reduces each component to its sign, as the GLSL function of the same name does; NaN stays
     * NaN.
     *
     * @return -1, 0 or +1 per component (NaN stays NaN)
     */
    public Vec4f sign() {
        return new Vec4f(Math.signum(x), Math.signum(y), Math.signum(z), Math.signum(w));
    }

    /**
     * Applies a hard threshold per component, as the GLSL function of the same name does.
     *
     * @param edge the edge
     * @return 0 where the component is below {@code edge}, else 1
     */
    public Vec4f step(float edge) {
        return new Vec4f(x < edge ? 0f : 1f, y < edge ? 0f : 1f, z < edge ? 0f : 1f, w < edge ? 0f : 1f);
    }

    /**
     * Applies the smooth cubic Hermite blend per component, as the GLSL function of the same name
     * does; the edges must differ.
     *
     * @param e0 the lower edge
     * @param e1 the upper edge
     * @return hermite interpolation of each component between {@code e0} and {@code e1}, as in GLSL
     */
    public Vec4f smoothstep(float e0, float e1) {
        return new Vec4f(smooth(x, e0, e1), smooth(y, e0, e1), smooth(z, e0, e1), smooth(w, e0, e1));
    }

    private static float smooth(float v, float e0, float e1) {
        float t = Math.min(Math.max((v - e0) / (e1 - e0), 0f), 1f);
        return t * t * (3f - 2f * t);
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
    public Vec4f project(Vec4f onto) {
        float d = onto.lengthSquared();
        if (d == 0f) {
            return ZERO;
        }
        float s = dot(onto) / d;
        return new Vec4f(onto.x * s, onto.y * s, onto.z * s, onto.w * s);
    }

    /**
     * Removes the parallel component along another vector, giving the perpendicular component; the
     * other vector must not be zero.
     *
     * @param onto the onto; must not be {@code null}
     * @return component perpendicular to {@code onto}: {@code this - project(onto)}
     */
    public Vec4f reject(Vec4f onto) {
        return sub(project(onto));
    }

    /**
     * Computes the unsigned angle between the vectors from the dot product and the lengths; zero
     * vectors are handled by returning zero.
     *
     * <p>There is no cross product in 4D, so this uses the numerically stable form
     * {@code 2 atan2(|u - v|, |u + v|)} of the unit vectors, which stays accurate for nearly
     * parallel and nearly opposite inputs where {@code acos(dot)} loses all precision.
     *
     * @param o the other vector; must not be {@code null}
     * @return angle between this and {@code o} in radians, in [0, PI]; 0 if either is zero
     */
    public float angle(Vec4f o) {
        float la = length(), lb = o.length();
        if (la == 0f || lb == 0f) {
            return 0f;
        }
        Vec4f u = mul(1f / la), v = o.mul(1f / lb);
        return 2f * (float) Math.atan2(u.sub(v).length(), u.add(v).length());
    }

    /**
     * Mirrors the vector about a hyperplane given by its normal, as the GLSL function of the same
     * name does; the normal must have unit length.
     *
     * @param n the vector; must not be {@code null}
     * @return mirror around the unit normal {@code n}: {@code this - 2 (this . n) n}
     */
    public Vec4f reflect(Vec4f n) {
        float d = 2f * dot(n);
        return new Vec4f(x - d * n.x, y - d * n.y, z - d * n.z, w - d * n.w);
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
    public Vec4f refract(Vec4f n, float eta) {
        float d = dot(n);
        float k = 1f - eta * eta * (1f - d * d);
        if (k < 0f) {
            return ZERO;
        }
        float s = eta * d + (float) Math.sqrt(k);
        return new Vec4f(eta * x - s * n.x, eta * y - s * n.y, eta * z - s * n.z, eta * w - s * n.w);
    }

    /**
     * Flips the vector to face against an incident direction, as the GLSL function of the same name
     * does.
     *
     * @param incident the incident; must not be {@code null}
     * @param ref the ref; must not be {@code null}
     * @return GLSL {@code faceforward}: this vector, flipped when {@code ref . incident >= 0}
     */
    public Vec4f faceForward(Vec4f incident, Vec4f ref) {
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
            case 2 -> z;
            case 3 -> w;
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
    public boolean approxEquals(Vec4f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps
                && Math.abs(z - o.z) <= eps && Math.abs(w - o.w) <= eps;
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
        dst[off + 2] = z;
        dst[off + 3] = w;
    }

    /**
     * Writes the vector at the absolute position {@code index}; does not change the buffer
     * position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z).put(index + 3, w);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Vec4d toDouble() {
        return new Vec4d(x, y, z, w);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Vec4f toFloat() {
        return new Vec4f((float) x, (float) y, (float) z, (float) w);
    }

    /**
     * Reads a vector from text in any of the forms the types print or people write: the {@code toString} of the record
     * ({@code Vec4f[x=1.0, y=1.0, z=1.0, w=1.0]}), the compact form of {@link #toCompactString()}, or a bare list such as {@code 1 2 3}.
     * Brackets and bars are ignored and the numbers are separated by commas, semicolons or white space. Labelled numbers may come in any
     * order; without labels they are taken in the order of the components. {@code NaN} and {@code Infinity} are numbers.
     *
     * @param text the text; must not be {@code null}
     * @return the vector with the 4 numbers of the text
     * @throws IllegalArgumentException if the text does not hold exactly 4 numbers, mixes labelled and unlabelled ones, names a component
     *     twice or not at all, starts with the name of another type, or holds a malformed number
     */
    public static Vec4f parse(CharSequence text) {
        String[] t = Text.tokens(text, "Vec4f", Text.VEC4, null);
        return new Vec4f(Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3]));
    }

    /**
     * Gives the compact text of the vector, such as {@code (1.0, 2.0, 3.0)}, with every component in the shortest form that reads back exactly.
     *
     * @return the components in order between parentheses; {@link #parse(CharSequence)} gives back an equal vector
     */
    public String toCompactString() {
        return "(" + x + ", " + y + ", " + z + ", " + w + ")";
    }

    /**
     * Gives the text of the vector with a fixed number of decimals, for a HUD or a log.
     *
     * @param decimals the digits after the point, 0 to 17
     * @return the components in order between parentheses, each rounded to {@code decimals} digits (not read back exactly)
     * @throws IllegalArgumentException if {@code decimals} is outside 0 to 17
     */
    public String format(int decimals) {
        return "(" + Text.fixed(x, decimals) + ", " + Text.fixed(y, decimals) + ", " + Text.fixed(z, decimals) + ", " + Text.fixed(w, decimals) + ")";
    }
}
