package vmath.core;

import java.nio.IntBuffer;
import vmath.annotations.ValueType;

/**
 * Immutable integer 3-vector: grid, voxel and chunk coordinates. There is no double twin, because a precision
 * pair makes no sense for integers.
 *
 * <p>Arithmetic wraps on overflow like {@code int}. The squared-length and distance methods return {@code long}
 * so that coordinates up to about 2^30 do not overflow them.
 */
@ValueType
public record Vec3i(int x, int y, int z) {

    /** The zero vector. */
    public static final Vec3i ZERO = new Vec3i(0, 0, 0);
    /** The vector with every component 1. */
    public static final Vec3i ONE = new Vec3i(1, 1, 1);
    /** The unit vector along +X. */
    public static final Vec3i UNIT_X = new Vec3i(1, 0, 0);
    /** The unit vector along +Y. */
    public static final Vec3i UNIT_Y = new Vec3i(0, 1, 0);
    /** The unit vector along +Z. */
    public static final Vec3i UNIT_Z = new Vec3i(0, 0, 1);

    /** Bits per axis of {@link #pack()}. */
    private static final int PACK_BITS = 21;
    private static final long PACK_MASK = (1L << PACK_BITS) - 1L;
    /** Smallest coordinate {@link #pack()} can hold: {@code -2^20}. */
    public static final int PACK_MIN = -(1 << (PACK_BITS - 1));
    /** Largest coordinate {@link #pack()} can hold: {@code 2^20 - 1}. */
    public static final int PACK_MAX = (1 << (PACK_BITS - 1)) - 1;

    /** A vector with every component equal to {@code v}. */
    public static Vec3i splat(int v) {
        return new Vec3i(v, v, v);
    }

    /** The cell containing {@code p}: component-wise floor. */
    public static Vec3i floor(Vec3f p) {
        return new Vec3i((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
    }

    /** The cell containing the point, rounding each component down. */
    public static Vec3i floor(Vec3d p) {
        return new Vec3i((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
    }

    /** The cell containing the point, rounding each component up. */
    public static Vec3i ceil(Vec3f p) {
        return new Vec3i((int) Math.ceil(p.x()), (int) Math.ceil(p.y()), (int) Math.ceil(p.z()));
    }

    /** Round half up, component-wise. */
    public static Vec3i round(Vec3f p) {
        return new Vec3i(Math.round(p.x()), Math.round(p.y()), Math.round(p.z()));
    }

    /** The sum {@code this + o}. */
    public Vec3i add(Vec3i o) {
        return new Vec3i(x + o.x, y + o.y, z + o.z);
    }

    /** The sum of this and the vector with the given components. */
    public Vec3i add(int ox, int oy, int oz) {
        return new Vec3i(x + ox, y + oy, z + oz);
    }

    /** The difference {@code this - o}. */
    public Vec3i sub(Vec3i o) {
        return new Vec3i(x - o.x, y - o.y, z - o.z);
    }

    /** Every component multiplied by {@code s}. */
    public Vec3i mul(int s) {
        return new Vec3i(x * s, y * s, z * s);
    }

    /** Component-wise product. */
    public Vec3i mul(Vec3i o) {
        return new Vec3i(x * o.x, y * o.y, z * o.z);
    }

    /** Every component negated. */
    public Vec3i negate() {
        return new Vec3i(-x, -y, -z);
    }

    /** Component-wise floor division: rounds toward negative infinity, so negative coordinates land in the right cell. */
    public Vec3i floorDiv(int d) {
        return new Vec3i(Math.floorDiv(x, d), Math.floorDiv(y, d), Math.floorDiv(z, d));
    }

    /** Component-wise floor modulus, in {@code [0, d)} for positive {@code d}. */
    public Vec3i floorMod(int d) {
        return new Vec3i(Math.floorMod(x, d), Math.floorMod(y, d), Math.floorMod(z, d));
    }

    /** The component-wise minimum. */
    public Vec3i min(Vec3i o) {
        return new Vec3i(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    /** The component-wise maximum. */
    public Vec3i max(Vec3i o) {
        return new Vec3i(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    /** The absolute value of every component. */
    public Vec3i abs() {
        return new Vec3i(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    /** Every component clamped component-wise to {@code [lo, hi]}. */
    public Vec3i clamp(Vec3i lo, Vec3i hi) {
        return new Vec3i(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y),
                Math.min(Math.max(z, lo.z), hi.z));
    }

    /** The dot product (as a {@code long}, so that it cannot overflow). */
    public long dot(Vec3i o) {
        return (long) x * o.x + (long) y * o.y + (long) z * o.z;
    }

    /** The squared length (as a {@code long}, so that it cannot overflow). */
    public long lengthSquared() {
        return dot(this);
    }

    /** The squared distance to {@code o} (as a {@code long}, so that it cannot overflow). */
    public long distanceSquared(Vec3i o) {
        long dx = (long) x - o.x, dy = (long) y - o.y, dz = (long) z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Sum of absolute component differences: the number of axis-aligned steps between two cells. */
    public long manhattan(Vec3i o) {
        return Math.abs((long) x - o.x) + Math.abs((long) y - o.y) + Math.abs((long) z - o.z);
    }

    /** Largest absolute component difference: the number of steps when diagonal moves are allowed. */
    public long chebyshev(Vec3i o) {
        return Math.max(Math.max(Math.abs((long) x - o.x), Math.abs((long) y - o.y)), Math.abs((long) z - o.z));
    }

    /** The smallest component. */
    public int minComponent() {
        return Math.min(x, Math.min(y, z));
    }

    /** The largest component. */
    public int maxComponent() {
        return Math.max(x, Math.max(y, z));
    }

    /** Component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException} for any other index. */
    public int get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            case 2 -> z;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /**
     * Packs the three coordinates into one {@code long} (21 bits each, two's complement), a compact key for hash maps of
     * chunk or voxel coordinates. Every coordinate must lie in {@code [PACK_MIN, PACK_MAX]}.
     */
    public long pack() {
        if (x < PACK_MIN || x > PACK_MAX || y < PACK_MIN || y > PACK_MAX || z < PACK_MIN || z > PACK_MAX) {
            throw new IllegalStateException(this + " is outside the packable range [" + PACK_MIN + ", " + PACK_MAX + "]");
        }
        return ((x & PACK_MASK) << (2 * PACK_BITS)) | ((y & PACK_MASK) << PACK_BITS) | (z & PACK_MASK);
    }

    /** Inverse of {@link #pack()}. */
    public static Vec3i unpack(long key) {
        return new Vec3i(signExtend(key >>> (2 * PACK_BITS)), signExtend(key >>> PACK_BITS), signExtend(key));
    }

    private static int signExtend(long bits) {
        return (int) ((bits & PACK_MASK) << (64 - PACK_BITS) >> (64 - PACK_BITS));
    }

    /** The same value with float components (rounded to the nearest float for double types). */
    public Vec3f toFloat() {
        return new Vec3f(x, y, z);
    }

    /** The same value with double components. */
    public Vec3d toDouble() {
        return new Vec3d(x, y, z);
    }

    /** Writes the components to {@code dst[off ..]} in order. */
    public void writeTo(int[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
        dst[off + 2] = z;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(IntBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y).put(index + 2, z);
    }
}
