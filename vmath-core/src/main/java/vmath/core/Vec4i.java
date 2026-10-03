package vmath.core;

import java.nio.IntBuffer;
import vmath.annotations.ValueType;

/**
 * Immutable integer 4-vector: a grid cell with a layer or time index, texture-array and cube
 * coordinates, rectangles as {@code (x, y, width, height)}.
 *
 * <p>See {@link Vec3i} for the conventions; like the other integer vectors it has no double twin,
 * because a precision pair makes no sense for integers.
 *
 * <p>Arithmetic wraps on overflow like {@code int}. The squared-length and distance methods return
 * {@code long} so that coordinates up to about 2^30 do not overflow them.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec4i a = new Vec4i(1, 2, 3, 4);
 * Vec4i b = a.add(1, 1, 1, 1).clamp(Vec4i.splat(0), Vec4i.splat(4));
 * long dot = a.dot(b);
 * long key = a.pack();                                         // 16 bits per coordinate
 * }</pre>
 *
 * @param x the x component
 * @param y the y component
 * @param z the z component
 * @param w the w component
 */
@ValueType
public record Vec4i(int x, int y, int z, int w) {

    /**
     * The zero vector.
     */
    public static final Vec4i ZERO = new Vec4i(0, 0, 0, 0);
    /**
     * The vector with every component 1.
     */
    public static final Vec4i ONE = new Vec4i(1, 1, 1, 1);
    /**
     * The unit vector along +X.
     */
    public static final Vec4i UNIT_X = new Vec4i(1, 0, 0, 0);
    /**
     * The unit vector along +Y.
     */
    public static final Vec4i UNIT_Y = new Vec4i(0, 1, 0, 0);
    /**
     * The unit vector along +Z.
     */
    public static final Vec4i UNIT_Z = new Vec4i(0, 0, 1, 0);
    /**
     * The unit vector along +W.
     */
    public static final Vec4i UNIT_W = new Vec4i(0, 0, 0, 1);

    /**
     * Bits per axis of {@link #pack()}.
     */
    private static final int PACK_BITS = 16;
    private static final long PACK_MASK = (1L << PACK_BITS) - 1L;
    /**
     * Smallest coordinate {@link #pack()} can hold: {@code -2^15}.
     */
    public static final int PACK_MIN = -(1 << (PACK_BITS - 1));
    /**
     * Largest coordinate {@link #pack()} can hold: {@code 2^15 - 1}.
     */
    public static final int PACK_MAX = (1 << (PACK_BITS - 1)) - 1;

    /**
     * Broadcasts a scalar into every component.
     *
     * @param v the value of every component
     * @return a vector with every component equal to {@code v}
     */
    public static Vec4i splat(int v) {
        return new Vec4i(v, v, v, v);
    }

    /**
     * Quantizes a position to the integer cell that contains it by flooring each component;
     * coordinates beyond the {@code int} range saturate.
     *
     * @param p the vector; must not be {@code null}
     * @return the cell containing {@code p}: component-wise floor
     */
    public static Vec4i floor(Vec4f p) {
        return new Vec4i((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()), (int) Math.floor(p.w()));
    }

    /**
     * Quantizes a position to the integer cell that contains it by flooring each component;
     * coordinates beyond the {@code int} range saturate.
     *
     * @param p the vector; must not be {@code null}
     * @return the cell containing the point, rounding each component down
     */
    public static Vec4i floor(Vec4d p) {
        return new Vec4i((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()), (int) Math.floor(p.w()));
    }

    /**
     * Rounds each component up to the next integer, giving the upper corner of the cells a box
     * touches; coordinates beyond the {@code int} range saturate.
     *
     * @param p the vector; must not be {@code null}
     * @return the cell containing the point, rounding each component up
     */
    public static Vec4i ceil(Vec4f p) {
        return new Vec4i((int) Math.ceil(p.x()), (int) Math.ceil(p.y()), (int) Math.ceil(p.z()), (int) Math.ceil(p.w()));
    }

    /**
     * Rounds half up, component-wise.
     *
     * @param p the vector; must not be {@code null}
     * @return the rounded vector, never {@code null}
     */
    public static Vec4i round(Vec4f p) {
        return new Vec4i(Math.round(p.x()), Math.round(p.y()), Math.round(p.z()), Math.round(p.w()));
    }

    /**
     * Adds the vectors component-wise; overflow wraps around as for {@code int}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the sum {@code this + o}
     */
    public Vec4i add(Vec4i o) {
        return new Vec4i(x + o.x, y + o.y, z + o.z, w + o.w);
    }

    /**
     * Adds a vector given as separate components, component-wise; overflow wraps around as for
     * {@code int}.
     *
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param ow the w component to add
     * @return the sum of this and the vector with the given components
     */
    public Vec4i add(int ox, int oy, int oz, int ow) {
        return new Vec4i(x + ox, y + oy, z + oz, w + ow);
    }

    /**
     * Subtracts the vectors component-wise; overflow wraps around as for {@code int}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the difference {@code this - o}
     */
    public Vec4i sub(Vec4i o) {
        return new Vec4i(x - o.x, y - o.y, z - o.z, w - o.w);
    }

    /**
     * Scales the vector by an integer factor; overflow wraps around as for {@code int}.
     *
     * @param s the factor for every component
     * @return every component multiplied by {@code s}
     */
    public Vec4i mul(int s) {
        return new Vec4i(x * s, y * s, z * s, w * s);
    }

    /**
     * Multiplies the vectors component-wise (the Hadamard product, not a dot product); overflow
     * wraps around as for {@code int}.
     *
     * @param o the other vector; must not be {@code null}
     * @return component-wise product
     */
    public Vec4i mul(Vec4i o) {
        return new Vec4i(x * o.x, y * o.y, z * o.z, w * o.w);
    }

    /**
     * Negates every component; {@code Integer.MIN_VALUE} stays unchanged because it has no positive
     * counterpart.
     *
     * @return every component negated
     */
    public Vec4i negate() {
        return new Vec4i(-x, -y, -z, -w);
    }

    /**
     * Divides component-wise, rounding towards negative infinity instead of towards zero, which is
     * what cell addressing of negative coordinates needs; a zero divisor throws.
     *
     * @param d the divisor; must not be zero
     * @return component-wise floor division: rounds toward negative infinity
     */
    public Vec4i floorDiv(int d) {
        return new Vec4i(Math.floorDiv(x, d), Math.floorDiv(y, d), Math.floorDiv(z, d), Math.floorDiv(w, d));
    }

    /**
     * Takes the component-wise modulus with the sign of the divisor, so that cell coordinates wrap
     * into a non-negative range; a zero divisor throws.
     *
     * @param d the divisor; must not be zero
     * @return component-wise floor modulus, in {@code [0, d)} for positive {@code d}
     */
    public Vec4i floorMod(int d) {
        return new Vec4i(Math.floorMod(x, d), Math.floorMod(y, d), Math.floorMod(z, d), Math.floorMod(w, d));
    }

    /**
     * Takes the smaller of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise minimum
     */
    public Vec4i min(Vec4i o) {
        return new Vec4i(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z), Math.min(w, o.w));
    }

    /**
     * Takes the larger of the two vectors per component.
     *
     * @param o the other vector; must not be {@code null}
     * @return the component-wise maximum
     */
    public Vec4i max(Vec4i o) {
        return new Vec4i(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z), Math.max(w, o.w));
    }

    /**
     * Takes the absolute value per component; {@code Integer.MIN_VALUE} stays negative.
     *
     * @return the component-wise absolute value
     */
    public Vec4i abs() {
        return new Vec4i(Math.abs(x), Math.abs(y), Math.abs(z), Math.abs(w));
    }

    /**
     * Returns each component clamped to the matching range of {@code lo} and {@code hi}.
     *
     * @param lo the vector; must not be {@code null}
     * @param hi the vector; must not be {@code null}
     * @return the clamped vector, never {@code null}
     */
    public Vec4i clamp(Vec4i lo, Vec4i hi) {
        return new Vec4i(Math.max(lo.x, Math.min(hi.x, x)), Math.max(lo.y, Math.min(hi.y, y)), Math.max(lo.z, Math.min(hi.z, z)), Math.max(lo.w, Math.min(hi.w, w)));
    }

    /**
     * Computes the dot product in {@code long} arithmetic, so that no {@code int} overflow can
     * occur.
     *
     * @param o the other vector; must not be {@code null}
     * @return the dot product, in {@code long}
     */
    public long dot(Vec4i o) {
        return (long) x * o.x + (long) y * o.y + (long) z * o.z + (long) w * o.w;
    }

    /**
     * Computes the squared length in {@code long} arithmetic, which avoids both the square root and
     * any {@code int} overflow; the right choice for comparing lengths.
     *
     * @return the squared length, in {@code long}
     */
    public long lengthSquared() {
        return dot(this);
    }

    /**
     * Computes the squared distance in {@code long} arithmetic, which avoids both the square root
     * and any {@code int} overflow; the right choice for comparing distances.
     *
     * @param o the other vector; must not be {@code null}
     * @return the squared Euclidean distance to {@code o}, in {@code long}
     */
    public long distanceSquared(Vec4i o) {
        long dx = (long) x - o.x, dy = (long) y - o.y, dz = (long) z - o.z, dw = (long) w - o.w;
        return dx * dx + dy * dy + dz * dz + dw * dw;
    }

    /**
     * Measures the distance on the Manhattan (L1) metric: the sum of the per-axis steps, computed
     * in {@code long}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the Manhattan (L1) distance to {@code o}
     */
    public long manhattan(Vec4i o) {
        return Math.abs((long) x - o.x) + Math.abs((long) y - o.y) + Math.abs((long) z - o.z) + Math.abs((long) w - o.w);
    }

    /**
     * Measures the distance on the Chebyshev (L-infinity) metric: the largest per-axis step,
     * computed in {@code long}.
     *
     * @param o the other vector; must not be {@code null}
     * @return the Chebyshev (L-infinity) distance to {@code o}: the largest component difference
     */
    public long chebyshev(Vec4i o) {
        return Math.max(Math.max(Math.abs((long) x - o.x), Math.abs((long) y - o.y)), Math.max(Math.abs((long) z - o.z), Math.abs((long) w - o.w)));
    }

    /**
     * Selects the smallest component.
     *
     * @return the smallest component
     */
    public int minComponent() {
        return Math.min(Math.min(x, y), Math.min(z, w));
    }

    /**
     * Selects the largest component.
     *
     * @return the largest component
     */
    public int maxComponent() {
        return Math.max(Math.max(x, y), Math.max(z, w));
    }

    /**
     * Reads a component by index, for loops that treat the vector as an array.
     *
     * @param i the index
     * @return component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException}
     *     for any other index
     * @throws IndexOutOfBoundsException if {@code i} is not a component index
     */
    public int get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            case 3 -> w;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /**
     * Packs the four coordinates into one {@code long} (16 bits each, two's complement), a compact
     * key for hash maps.
     *
     * <p>Every coordinate must lie in {@code [PACK_MIN, PACK_MAX]}.
     *
     * @return the packed key, with 16 bits per coordinate
     * @throws IllegalStateException if a coordinate is outside the packable range
     */
    public long pack() {
        if (x < PACK_MIN || x > PACK_MAX || y < PACK_MIN || y > PACK_MAX || z < PACK_MIN || z > PACK_MAX || w < PACK_MIN || w > PACK_MAX) {
            throw new IllegalStateException(this + " is outside the packable range [" + PACK_MIN + ", " + PACK_MAX + "]");
        }
        return ((x & PACK_MASK) << (3 * PACK_BITS)) | ((y & PACK_MASK) << (2 * PACK_BITS)) | ((z & PACK_MASK) << PACK_BITS) | (w & PACK_MASK);
    }

    /**
     * Decodes a key made by {@link #pack()} back into a vector, restoring the sign of every
     * component.
     *
     * @param key the key
     * @return inverse of {@link #pack()}
     */
    public static Vec4i unpack(long key) {
        return new Vec4i(signExtend(key >>> (3 * PACK_BITS)), signExtend(key >>> (2 * PACK_BITS)), signExtend(key >>> PACK_BITS), signExtend(key));
    }

    private static int signExtend(long bits) {
        return (int) ((bits & PACK_MASK) << (64 - PACK_BITS) >> (64 - PACK_BITS));
    }

    /**
     * Converts the components to {@code float}, which rounds large values.
     *
     * @return the same value with float components (rounded to the nearest float for large
     *     magnitudes)
     */
    public Vec4f toFloat() {
        return new Vec4f(x, y, z, w);
    }

    /**
     * Converts the components to {@code double}, which is exact.
     *
     * @return the same value with double components
     */
    public Vec4d toDouble() {
        return new Vec4d(x, y, z, w);
    }

    /**
     * Writes the components to {@code dst[off ..]} in order.
     *
     * @param dst receives the result
     * @param off the index of the first element to read or write
     */
    public void writeTo(int[] dst, int off) {
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
    public void writeTo(IntBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z).put(index + 3, w);
    }
}
