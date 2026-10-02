package vmath.core;

import vmath.annotations.Experimental;

/**
 * Hashing of positions for hash grids, vertex welding and "have I seen this point before" lookups, where the record {@code equals} and {@code hashCode} are the wrong
 * tool: they are exact (see {@code docs/EQUALITY.md}), so two points that differ by rounding noise land in different buckets.
 *
 * <p>The approach is the standard one. Space is cut into cubes of {@code cellSize}. {@link #cell} gives a coordinate's cell index, {@link #hash(int, int, int)} mixes
 * three cell indices into a well-distributed {@code int}, and {@link #pack3} packs them into an exact {@code long} key when they are small enough. A point is
 * <b>inserted</b> under the hash of its own cell. A lookup that must also find points within {@code epsilon} of the query cannot just hash the query's cell (the match may
 * sit just across a cell border), so it visits every cell that the box {@code [p - epsilon, p + epsilon]} overlaps: {@link #cellsOverlapping} returns their hashes, at most 8
 * (at most 2 per axis because {@code epsilon <= cellSize / 2} is required). Guarantee, tested: if two points differ by at most {@code epsilon} on every axis, the
 * stored point's cell hash is among the hashes returned for the query. Candidates found this way still need an exact distance test, since the cells also contain farther
 * points and two different cells can share a hash.
 *
 * <p>Cell indices come from {@code floor(v / cellSize)} computed in double precision, so the same value always lands in the same cell regardless of how the box bounds were
 * rounded. The index must fit an {@code int}: positions further than {@code 2^31} cells from the origin, and NaN or infinite coordinates, are rejected.
 *
 * <p>{@link #floatKey} and {@link #doubleKey} are the other half of exact hashing: they map every NaN to one bit pattern and negative zero to positive zero, for code that wants
 * {@code 0.0} and {@code -0.0} to be the same key.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the helper set may grow")
public final class SpatialHash {

    private SpatialHash() {
    }

    /** The cell index of a coordinate: {@code floor(v / cellSize)}, in double precision. */
    public static int cell(float v, float cellSize) {
        if (!(cellSize > 0f) || Float.isInfinite(cellSize)) {
            throw new IllegalArgumentException("cellSize must be positive and finite: " + cellSize);
        }
        return cellIndex(v, cellSize);
    }

    private static int cellIndex(double v, double cellSize) {
        double q = Math.floor(v / cellSize);
        if (!(q >= Integer.MIN_VALUE && q <= Integer.MAX_VALUE)) {
            throw new IllegalArgumentException("the coordinate " + v + " is NaN, infinite or too many cells (" + q + ") from the origin for cells of " + cellSize);
        }
        return (int) q;
    }

    /** Murmur3's 32-bit finalizer: every input bit affects every output bit. */
    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /** A well-distributed hash of a 3D cell index. Neighbouring cells get unrelated hashes, so a table indexed by {@code hash & (size - 1)} fills evenly. */
    public static int hash(int x, int y, int z) {
        int h = 0x9E3779B9;
        h = mix(h ^ x);
        h = mix(h * 31 ^ y);
        h = mix(h * 31 ^ z);
        return h;
    }

    /** A well-distributed hash of a 2D cell index. */
    public static int hash(int x, int y) {
        int h = 0x9E3779B9;
        h = mix(h ^ x);
        h = mix(h * 31 ^ y);
        return h;
    }

    /** The hash of the cell that contains the point. */
    public static int hash(float x, float y, float z, float cellSize) {
        return hash(cell(x, cellSize), cell(y, cellSize), cell(z, cellSize));
    }

    /** The hash of the cell that contains the point. */
    public static int hash(Vec3f p, float cellSize) {
        return hash(p.x(), p.y(), p.z(), cellSize);
    }

    /** The largest absolute cell index {@link #pack3} accepts: {@code 2^20 - 1} (indices run from its negative to it). */
    public static final int MAX_PACKED = (1 << 20) - 1;

    /** An exact 63-bit key of a cell index with each component in {@code [-MAX_PACKED, MAX_PACKED]}; use it as a {@code long} map key without collisions. */
    public static long pack3(int x, int y, int z) {
        if (Math.abs((long) x) > MAX_PACKED || Math.abs((long) y) > MAX_PACKED || Math.abs((long) z) > MAX_PACKED) {
            throw new IllegalArgumentException("a cell index is outside +-" + MAX_PACKED + ": " + x + ", " + y + ", " + z);
        }
        return ((long) (x + (1 << 20)) << 42) | ((long) (y + (1 << 20)) << 21) | (long) (z + (1 << 20));
    }

    /** The cell index from the key made by {@link #pack3}: its x. */
    public static int unpackX(long key) {
        return (int) ((key >>> 42) & 0x1FFFFF) - (1 << 20);
    }

    /** The cell index from the key made by {@link #pack3}: its y. */
    public static int unpackY(long key) {
        return (int) ((key >>> 21) & 0x1FFFFF) - (1 << 20);
    }

    /** The cell index from the key made by {@link #pack3}: its z. */
    public static int unpackZ(long key) {
        return (int) (key & 0x1FFFFF) - (1 << 20);
    }

    /**
     * Writes to {@code out} the hashes of every cell that the box {@code [p - epsilon, p + epsilon]} overlaps (distinct, at least 1 and at most 8) and returns how many.
     * {@code out} must have room for 8. See the class comment for the guarantee.
     *
     * @throws IllegalArgumentException if {@code epsilon} is negative or above {@code cellSize / 2}, {@code cellSize} is not positive, or a coordinate is not finite
     */
    public static int cellsOverlapping(float x, float y, float z, float epsilon, float cellSize, int[] out) {
        if (!(cellSize > 0f) || Float.isInfinite(cellSize)) {
            throw new IllegalArgumentException("cellSize must be positive and finite: " + cellSize);
        }
        if (!(epsilon >= 0f) || epsilon > 0.5f * cellSize) {
            throw new IllegalArgumentException("epsilon must be in [0, cellSize / 2]: " + epsilon + " for cells of " + cellSize);
        }
        if (out.length < 8) {
            throw new IllegalArgumentException("out must have room for 8 hashes");
        }
        int x0 = cellIndex((double) x - epsilon, cellSize), x1 = cellIndex((double) x + epsilon, cellSize);
        int y0 = cellIndex((double) y - epsilon, cellSize), y1 = cellIndex((double) y + epsilon, cellSize);
        int z0 = cellIndex((double) z - epsilon, cellSize), z1 = cellIndex((double) z + epsilon, cellSize);
        int n = 0;
        for (int cz = z0; cz <= z1; cz++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cx = x0; cx <= x1; cx++) {
                    out[n++] = hash(cx, cy, cz);
                }
            }
        }
        return n;
    }

    /** {@link #cellsOverlapping(float, float, float, float, float, int[])} for a {@link Vec3f}. */
    public static int cellsOverlapping(Vec3f p, float epsilon, float cellSize, int[] out) {
        return cellsOverlapping(p.x(), p.y(), p.z(), epsilon, cellSize, out);
    }

    /** The bits of {@code f} with every NaN mapped to one pattern ({@code 0x7FC00000}) and {@code -0.0f} mapped to {@code 0.0f}: equal numbers get equal keys. */
    public static int floatKey(float f) {
        if (f != f) {
            return 0x7FC00000;
        }
        return f == 0f ? 0 : Float.floatToRawIntBits(f);
    }

    /** The double version of {@link #floatKey}. */
    public static long doubleKey(double d) {
        if (d != d) {
            return 0x7FF8000000000000L;
        }
        return d == 0d ? 0L : Double.doubleToRawLongBits(d);
    }
}
