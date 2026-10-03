package vmath.core;

import java.nio.IntBuffer;
import vmath.annotations.ValueType;

/** Immutable integer 2-vector: pixel, texel and tile coordinates. See {@link Vec3i} for the conventions. */
@ValueType
public record Vec2i(int x, int y) {

    /** The zero vector. */
    public static final Vec2i ZERO = new Vec2i(0, 0);
    /** The vector with every component 1. */
    public static final Vec2i ONE = new Vec2i(1, 1);
    /** The unit vector along +X. */
    public static final Vec2i UNIT_X = new Vec2i(1, 0);
    /** The unit vector along +Y. */
    public static final Vec2i UNIT_Y = new Vec2i(0, 1);

    /** A vector with every component equal to {@code v}. */
    public static Vec2i splat(int v) {
        return new Vec2i(v, v);
    }

    /** The cell containing {@code p}: component-wise floor. */
    public static Vec2i floor(Vec2f p) {
        return new Vec2i((int) Math.floor(p.x()), (int) Math.floor(p.y()));
    }

    /** Round half up, component-wise. */
    public static Vec2i round(Vec2f p) {
        return new Vec2i(Math.round(p.x()), Math.round(p.y()));
    }

    /** The sum {@code this + o}. */
    public Vec2i add(Vec2i o) {
        return new Vec2i(x + o.x, y + o.y);
    }

    /** The sum of this and the vector with the given components. */
    public Vec2i add(int ox, int oy) {
        return new Vec2i(x + ox, y + oy);
    }

    /** The difference {@code this - o}. */
    public Vec2i sub(Vec2i o) {
        return new Vec2i(x - o.x, y - o.y);
    }

    /** Every component multiplied by {@code s}. */
    public Vec2i mul(int s) {
        return new Vec2i(x * s, y * s);
    }

    /** Component-wise product. */
    public Vec2i mul(Vec2i o) {
        return new Vec2i(x * o.x, y * o.y);
    }

    /** Every component negated. */
    public Vec2i negate() {
        return new Vec2i(-x, -y);
    }

    /** Component-wise floor division: rounds toward negative infinity. */
    public Vec2i floorDiv(int d) {
        return new Vec2i(Math.floorDiv(x, d), Math.floorDiv(y, d));
    }

    /** Component-wise floor modulus, in {@code [0, d)} for positive {@code d}. */
    public Vec2i floorMod(int d) {
        return new Vec2i(Math.floorMod(x, d), Math.floorMod(y, d));
    }

    /** The component-wise minimum. */
    public Vec2i min(Vec2i o) {
        return new Vec2i(Math.min(x, o.x), Math.min(y, o.y));
    }

    /** The component-wise maximum. */
    public Vec2i max(Vec2i o) {
        return new Vec2i(Math.max(x, o.x), Math.max(y, o.y));
    }

    /** The absolute value of every component. */
    public Vec2i abs() {
        return new Vec2i(Math.abs(x), Math.abs(y));
    }

    /** Every component clamped component-wise to {@code [lo, hi]}. */
    public Vec2i clamp(Vec2i lo, Vec2i hi) {
        return new Vec2i(Math.min(Math.max(x, lo.x), hi.x), Math.min(Math.max(y, lo.y), hi.y));
    }

    /** The dot product (as a {@code long}, so that it cannot overflow). */
    public long dot(Vec2i o) {
        return (long) x * o.x + (long) y * o.y;
    }

    /** The squared length (as a {@code long}, so that it cannot overflow). */
    public long lengthSquared() {
        return dot(this);
    }

    /** The squared distance to {@code o} (as a {@code long}, so that it cannot overflow). */
    public long distanceSquared(Vec2i o) {
        long dx = (long) x - o.x, dy = (long) y - o.y;
        return dx * dx + dy * dy;
    }

    /** The sum of the absolute component differences: the number of axis-aligned steps between two cells. */
    public long manhattan(Vec2i o) {
        return Math.abs((long) x - o.x) + Math.abs((long) y - o.y);
    }

    /** The largest absolute component difference: the number of steps when diagonal moves are allowed. */
    public long chebyshev(Vec2i o) {
        return Math.max(Math.abs((long) x - o.x), Math.abs((long) y - o.y));
    }

    /** Number of cells in an {@code x * y} grid; a negative extent counts as zero. */
    public long area() {
        return (long) Math.max(x, 0) * Math.max(y, 0);
    }

    /** The smallest component. */
    public int minComponent() {
        return Math.min(x, y);
    }

    /** The largest component. */
    public int maxComponent() {
        return Math.max(x, y);
    }

    /** Component {@code i} (0 is x, 1 is y, and so on); {@link IndexOutOfBoundsException} for any other index. */
    public int get(int i) {
        return switch (i) {
            case 0 -> x;
            case 1 -> y;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    /** Packs both coordinates into one {@code long}, 32 bits each with x in the high half. Lossless. */
    public long pack() {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /** Inverse of {@link #pack()}. */
    public static Vec2i unpack(long key) {
        return new Vec2i((int) (key >> 32), (int) key);
    }

    /** The same value with float components (rounded to the nearest float for double types). */
    public Vec2f toFloat() {
        return new Vec2f(x, y);
    }

    /** The same value with double components. */
    public Vec2d toDouble() {
        return new Vec2d(x, y);
    }

    /** Writes the components to {@code dst[off ..]} in order. */
    public void writeTo(int[] dst, int off) {
        dst[off] = x;
        dst[off + 1] = y;
    }

    /** Absolute write at {@code index}; does not change the buffer position. */
    public void writeTo(IntBuffer dst, int index) {
        dst.put(index, x).put(index + 1, y);
    }
}
