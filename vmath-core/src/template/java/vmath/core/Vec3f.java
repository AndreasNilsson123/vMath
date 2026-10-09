package vmath.core;

import vmath.annotations.Bulk;
import vmath.annotations.DoubleOnly;
import vmath.annotations.FloatOnly;
import vmath.annotations.GenerateDouble;
import vmath.annotations.ValueType;
import java.nio.FloatBuffer;

/**
 * Immutable 3-component float vector.
 *
 * <p>Valhalla: {@code -Pvalhalla} builds turn {@code @ValueType} into a real {@code value record}.
 * Never use {@code ==}, {@code synchronized} or identity-based APIs on it.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b> creating and combining vectors
 *
 * <pre>{@code
 * Vec3f a = new Vec3f(1f, 2f, 3f);
 * Vec3f b = Vec3f.UNIT_X.mul(2f);
 * Vec3f sum = a.add(b);                      // (3, 2, 3)
 * float dot = a.dot(b);                      // 2
 * Vec3f normal = a.cross(b).normalize();
 * Vec3f middle = a.lerp(b, 0.5f);
 * float[] array = new float[3];
 * middle.writeTo(array, 0);                  // for an upload buffer
 * }</pre>
 *
 * <p><b>Example:</b> comparing with a tolerance (never with {@code ==}, see the class comment of
 * the library)
 *
 * <pre>{@code
 * Vec3f a = new Vec3f(1f, 2f, 3f);
 * Vec3f b = a.add(1e-7f, 0f, 0f);
 * boolean same = a.approxEquals(b, 1e-5f);   // true
 * boolean equal = a.equals(b);               // false: exact comparison of the components
 * }</pre>
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 */
@GenerateDouble
@ValueType
public record Vec3f(float x, float y, float z) {

    /**
     * The zero vector.
     */
    public static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);
    /**
     * The vector with every component 1.
     */
    public static final Vec3f ONE = new Vec3f(1f, 1f, 1f);
    /**
     * The unit vector along +X.
     */
    public static final Vec3f UNIT_X = new Vec3f(1f, 0f, 0f);
    /**
     * The unit vector along +Y.
     */
    public static final Vec3f UNIT_Y = new Vec3f(0f, 1f, 0f);
    /**
     * The unit vector along +Z.
     */
    public static final Vec3f UNIT_Z = new Vec3f(0f, 0f, 1f);

    /**
     * Broadcasts a scalar into every component.
     *
     * @param s the value of every component
     * @return a vector with every component equal to {@code s}
     */
    public static Vec3f splat(float s) {
        return new Vec3f(s, s, s);
    }

    /**
     * Adds the vectors component-wise.
     *
     * @param o the other vector; must not be {@code null}
     * @return the sum {@code this + o}
     */
    @Bulk
    public Vec3f add(Vec3f o) {
        return new Vec3f(x + o.x, y + o.y, z + o.z);
    }

    /**
     * Adds a vector given as separate components, component-wise.
     *
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @return the sum of this and the vector with the given components
     */
    public Vec3f add(float ox, float oy, float oz) {
        return new Vec3f(x + ox, y + oy, z + oz);
    }

    /**
     * Subtracts the vectors component-wise.
     *
     * @param o the other vector; must not be {@code null}
     * @return the difference {@code this - o}
     */
    @Bulk
    public Vec3f sub(Vec3f o) {
        return new Vec3f(x - o.x, y - o.y, z - o.z);
    }

    /**
     * Scales the vector by a scalar.
     *
     * @param s the factor
     * @return every component multiplied by {@code s}
     */
    @Bulk
    public Vec3f mul(float s) {
        return new Vec3f(x * s, y * s, z * s);
    }

    /**
     * Multiplies the vectors component-wise (the Hadamard product, not a dot product).
     *
     * @param o the other vector; must not be {@code null}
     * @return component-wise product
     */
    @Bulk
    public Vec3f mul(Vec3f o) {
        return new Vec3f(x * o.x, y * o.y, z * o.z);
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
    public Vec3f div(float s) {
        float inv = 1f / s;
        return new Vec3f(x * inv, y * inv, z * inv);
    }

    /**
     * Negates every component.
     *
     * @return every component negated
     */
    @Bulk
    public Vec3f negate() {
        return new Vec3f(-x, -y, -z);
    }

    /**
     * Scales a vector and adds it to this one in one expression, without an intermediate vector.
     *
     * @param a the first vector; must not be {@code null}
     * @param s the factor for {@code a}
     * @return {@code this + a * s}
     */
    @Bulk
    public Vec3f fma(Vec3f a, float s) {
        return new Vec3f(x + a.x * s, y + a.y * s, z + a.z * s);
    }

    /**
     * Computes the dot product, which is the product of the lengths and the cosine of the angle
     * between the vectors.
     *
     * @param o the other vector; must not be {@code null}
     * @return the dot product
     */
    @Bulk
    public float dot(Vec3f o) {
        return x * o.x + y * o.y + z * o.z;
    }

    /**
     * Computes the cross product with the right-hand rule: a vector perpendicular to both, whose
     * length is the area of the parallelogram they span; the order of the operands matters.
     *
     * @param o the other vector; must not be {@code null}
     * @return right-handed cross product
     */
    @Bulk
    public Vec3f cross(Vec3f o) {
        return new Vec3f(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    /**
     * Sums the squares of the components; avoids the square root, so prefer it for comparisons.
     *
     * @return the squared length
     */
    @Bulk
    public float lengthSquared() {
        return x * x + y * y + z * z;
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
     * Measures the squared Euclidean distance, which avoids the square root and is the right choice
     * for comparing distances.
     *
     * @param o the other vector; must not be {@code null}
     * @return the squared distance to {@code o}; cheaper than {@link #distance}
     */
    @Bulk
    public float distanceSquared(Vec3f o) {
        float dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Measures the Euclidean distance with a square root; prefer {@link #distanceSquared} when only
     * comparing.
     *
     * @param o the other vector; must not be {@code null}
     * @return the distance to {@code o}
     */
    public float distance(Vec3f o) {
        return (float) Math.sqrt(distanceSquared(o));
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
    public Vec3f normalize() {
        float len2 = x * x + y * y + z * z;
        if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
            float inv = 1f / (float) Math.sqrt(len2);
            return new Vec3f(x * inv, y * inv, z * inv);
        }
        return normalizeScaled();
    }

    /**
     * The slow path of {@link #normalize()}: divide by the largest component first so that the
     * squares neither overflow nor underflow.
     */
    private Vec3f normalizeScaled() {
        float m = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (!(m > 0f) || m == Float.POSITIVE_INFINITY) {
            return new Vec3f(Float.NaN, Float.NaN, Float.NaN);
        }
        float a = x / m, b = y / m, c = z / m;
        float inv = 1f / (float) Math.sqrt(a * a + b * b + c * c);
        return new Vec3f(a * inv, b * inv, c * inv);
    }

    /**
     * Returns like {@link #normalize()} but returns {@link #ZERO} for (near-)zero input.
     *
     * @return {@link #ZERO} for (near-)zero input
     */
    public Vec3f normalizeOrZero() {
        float len2 = lengthSquared();
        if (len2 <= 1e-30f) {
            return ZERO;
        }
        if (!(len2 <= Float.MAX_VALUE)) { // overflowed (a huge vector keeps its direction) or NaN (stays NaN)
            return normalizeScaled();
        }
        float inv = 1f / (float) Math.sqrt(len2);
        return new Vec3f(x * inv, y * inv, z * inv);
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
    public Vec3f lerp(Vec3f o, float t) {
        return new Vec3f(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /**
     * Computes the unsigned angle between the vectors from the dot and cross products, which stays
     * accurate for small angles; undefined for a zero vector.
     *
     * @param o the other vector; must not be {@code null}
     * @return angle between this and {@code o} in radians, in [0, PI]
     */
    public float angle(Vec3f o) {
        return (float) Math.atan2(cross(o).length(), dot(o));
    }

    /**
     * Takes the smaller of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise minimum
     */
    @Bulk
    public Vec3f min(Vec3f o) {
        return new Vec3f(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    /**
     * Takes the larger of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise maximum
     */
    @Bulk
    public Vec3f max(Vec3f o) {
        return new Vec3f(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    /**
     * Takes the absolute value per component.
     *
     * @return the absolute value of every component
     */
    @Bulk
    public Vec3f abs() {
        return new Vec3f(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    /**
     * Constructs a perpendicular direction without branching on a component, useful for building a
     * basis around an axis; the result is not unique, and the vector must be non-zero.
     *
     * <p>Zero input yields NaN components.
     *
     * @return a unit vector perpendicular to this one
     */
    public Vec3f anyPerpendicular() {
        float ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        Vec3f axis = ax <= ay && ax <= az ? UNIT_X : ay <= az ? UNIT_Y : UNIT_Z;
        return cross(axis).normalize();
    }

    /**
     * Selects the smallest component.
     *
     * @return the smallest component
     */
    public float minComponent() {
        return Math.min(Math.min(x, y), z);
    }

    /**
     * Selects the largest component.
     *
     * @return the largest component
     */
    public float maxComponent() {
        return Math.max(Math.max(x, y), z);
    }

    /**
     * Checks all components for NaN and infinity.
     *
     * @return {@code true} when no component is NaN or infinite
     */
    public boolean isFinite() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z);
    }

    /**
     * Restricts each component to a per-component range; the lower bound is expected not to exceed
     * the upper bound.
     *
     * @param lo the lower bound; must not be {@code null}
     * @param hi the upper bound; must not be {@code null}
     * @return component-wise clamp to {@code [lo, hi]}
     */
    public Vec3f clamp(Vec3f lo, Vec3f hi) {
        return new Vec3f(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y), Math.min(Math.max(z, lo.z), hi.z));
    }

    /**
     * Restricts each component to the same scalar range.
     *
     * @param lo the lower bound
     * @param hi the upper bound
     * @return every component clamped to {@code [lo, hi]}
     */
    public Vec3f clamp(float lo, float hi) {
        return new Vec3f(Math.min(Math.max(x, lo), hi), Math.min(Math.max(y, lo), hi), Math.min(Math.max(z, lo), hi));
    }

    /**
     * Clamps every component to [0, 1].
     *
     * @return the clamped vector, never {@code null}
     */
    public Vec3f saturate() {
        return clamp(0f, 1f);
    }

    /**
     * Rounds each component down to a whole number; the result stays a floating-point vector.
     *
     * @return every component rounded down to a whole number
     */
    public Vec3f floor() {
        return new Vec3f((float) Math.floor(x), (float) Math.floor(y), (float) Math.floor(z));
    }

    /**
     * Rounds each component up to a whole number; the result stays a floating-point vector.
     *
     * @return every component rounded up to a whole number
     */
    public Vec3f ceil() {
        return new Vec3f((float) Math.ceil(x), (float) Math.ceil(y), (float) Math.ceil(z));
    }

    /**
     * Keeps only the fractional part, as the GLSL function of the same name does; the result is
     * always in {@code [0, 1)}, also for negative components.
     *
     * @return fractional part as in GLSL: {@code v - floor(v)}, always in [0, 1)
     */
    public Vec3f fract() {
        return new Vec3f(x - (float) Math.floor(x), y - (float) Math.floor(y), z - (float) Math.floor(z));
    }

    /**
     * Reduces each component to its sign, as the GLSL function of the same name does; NaN stays
     * NaN.
     *
     * @return -1, 0 or +1 per component (NaN stays NaN)
     */
    public Vec3f sign() {
        return new Vec3f(Math.signum(x), Math.signum(y), Math.signum(z));
    }

    /**
     * Applies a hard threshold per component, as the GLSL function of the same name does.
     *
     * @param edge the edge
     * @return 0 where the component is below {@code edge}, else 1
     */
    public Vec3f step(float edge) {
        return new Vec3f(x < edge ? 0f : 1f, y < edge ? 0f : 1f, z < edge ? 0f : 1f);
    }

    /**
     * Applies the smooth cubic Hermite blend per component, as the GLSL function of the same name
     * does; the edges must differ.
     *
     * @param e0 the lower edge
     * @param e1 the upper edge
     * @return hermite interpolation of each component between {@code e0} and {@code e1}, as in GLSL
     */
    public Vec3f smoothstep(float e0, float e1) {
        return new Vec3f(smooth(x, e0, e1), smooth(y, e0, e1), smooth(z, e0, e1));
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
    public Vec3f reflect(Vec3f n) {
        float d = 2f * dot(n);
        return new Vec3f(x - d * n.x, y - d * n.y, z - d * n.z);
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
    public Vec3f refract(Vec3f n, float eta) {
        float d = dot(n);
        float k = 1f - eta * eta * (1f - d * d);
        if (k < 0f) {
            return ZERO;
        }
        float s = eta * d + (float) Math.sqrt(k);
        return new Vec3f(eta * x - s * n.x, eta * y - s * n.y, eta * z - s * n.z);
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
    public Vec3f project(Vec3f onto) {
        float d = onto.lengthSquared();
        if (d == 0f) {
            return ZERO;
        }
        float s = dot(onto) / d;
        return new Vec3f(onto.x * s, onto.y * s, onto.z * s);
    }

    /**
     * Removes the parallel component along another vector, giving the perpendicular component; the
     * other vector must not be zero.
     *
     * @param onto the onto; must not be {@code null}
     * @return component perpendicular to {@code onto}: {@code this - project(onto)}
     */
    public Vec3f reject(Vec3f onto) {
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
    public Vec3f faceForward(Vec3f incident, Vec3f ref) {
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
    public boolean approxEquals(Vec3f o, float eps) {
        return Math.abs(x - o.x) <= eps && Math.abs(y - o.y) <= eps && Math.abs(z - o.z) <= eps;
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
    }

    /**
     * Writes the vector at the absolute position {@code index}; does not change the buffer
     * position.
     *
     * @param dst receives the result; must not be {@code null}
     * @param index the index
     */
    public void writeTo(FloatBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    @FloatOnly
    public Vec3d toDouble() {
        return new Vec3d(x, y, z);
    }

    /**
     * Converts the components to {@code float}, which rounds values that need more precision.
     *
     * @return the same value with float components (rounded to the nearest float for double types)
     */
    @DoubleOnly
    public Vec3f toFloat() {
        return new Vec3f((float) x, (float) y, (float) z);
    }

    /**
     * Subtracts a double-precision origin before narrowing to {@code float}, which keeps precision
     * for positions far from the origin (camera-relative rendering).
     *
     * <p>This is the core of camera-relative rendering: pass the camera's world position as
     * {@code origin} so vertex data stays small enough for float precision on the GPU.
     *
     * @param origin the origin; must not be {@code null}
     * @return {@code this - origin}, subtracted in double and then narrowed to float
     */
    @DoubleOnly
    public Vec3f relativeTo(Vec3d origin) {
        return new Vec3f((float) (x - origin.x), (float) (y - origin.y), (float) (z - origin.z));
    }

    /**
     * Reads a vector from text in any of the forms the types print or people write: the {@code toString} of the record
     * ({@code Vec3f[x=1.0, y=1.0, z=1.0]}), the compact form of {@link #toCompactString()}, or a bare list such as {@code 1 2 3}.
     * Brackets and bars are ignored and the numbers are separated by commas, semicolons or white space. Labelled numbers may come in any
     * order; without labels they are taken in the order of the components. {@code NaN} and {@code Infinity} are numbers.
     *
     * @param text the text; must not be {@code null}
     * @return the vector with the 3 numbers of the text
     * @throws IllegalArgumentException if the text does not hold exactly 3 numbers, mixes labelled and unlabelled ones, names a component
     *     twice or not at all, starts with the name of another type, or holds a malformed number
     */
    public static Vec3f parse(CharSequence text) {
        String[] t = Text.tokens(text, "Vec3f", Text.VEC3, null);
        return new Vec3f(Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2]));
    }

    /**
     * Gives the compact text of the vector, such as {@code (1.0, 2.0, 3.0)}, with every component in the shortest form that reads back exactly.
     *
     * @return the components in order between parentheses; {@link #parse(CharSequence)} gives back an equal vector
     */
    public String toCompactString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    /**
     * Gives the text of the vector with a fixed number of decimals, for a HUD or a log.
     *
     * @param decimals the digits after the point, 0 to 17
     * @return the components in order between parentheses, each rounded to {@code decimals} digits (not read back exactly)
     * @throws IllegalArgumentException if {@code decimals} is outside 0 to 17
     */
    public String format(int decimals) {
        return "(" + Text.fixed(x, decimals) + ", " + Text.fixed(y, decimals) + ", " + Text.fixed(z, decimals) + ")";
    }
}
