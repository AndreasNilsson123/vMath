package vmath.core;

/**
 * Morton (Z-order) codes: interleave the bits of grid coordinates so that numerically close codes are spatially close.
 * Sorting objects by Morton code gives a cache-friendly memory order and is the basis of LBVH construction.
 *
 * <p>3D codes take 21 bits per axis (63 bits, so always non-negative as a {@code long}); 2D codes take 32 bits per
 * axis and use all 64 bits. Coordinates are treated as unsigned; use {@link #quantize} to map a float in a known range
 * onto the grid.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class Morton {

    private Morton() {
    }

    /** Largest 3D coordinate: {@code 2^21 - 1}. */
    public static final int MAX_3D = (1 << 21) - 1;

    /** Spreads the low 21 bits of {@code v} so that two zero bits sit between each pair of bits. */
    static long spread3(long v) {
        long x = v & 0x1FFFFFL;
        x = (x | (x << 32)) & 0x1F00000000FFFFL;
        x = (x | (x << 16)) & 0x1F0000FF0000FFL;
        x = (x | (x << 8)) & 0x100F00F00F00F00FL;
        x = (x | (x << 4)) & 0x10C30C30C30C30C3L;
        x = (x | (x << 2)) & 0x1249249249249249L;
        return x;
    }

    static long compact3(long v) {
        long x = v & 0x1249249249249249L;
        x = (x ^ (x >>> 2)) & 0x10C30C30C30C30C3L;
        x = (x ^ (x >>> 4)) & 0x100F00F00F00F00FL;
        x = (x ^ (x >>> 8)) & 0x1F0000FF0000FFL;
        x = (x ^ (x >>> 16)) & 0x1F00000000FFFFL;
        x = (x ^ (x >>> 32)) & 0x1FFFFFL;
        return x;
    }

    /** 63-bit code: bit {@code 3i} is bit {@code i} of x, bit {@code 3i+1} of y, bit {@code 3i+2} of z. */
    public static long encode3(int x, int y, int z) {
        return spread3(x) | (spread3(y) << 1) | (spread3(z) << 2);
    }

    public static Vec3i decode3(long code) {
        return new Vec3i((int) compact3(code), (int) compact3(code >>> 1), (int) compact3(code >>> 2));
    }

    static long spread2(long v) {
        long x = v & 0xFFFFFFFFL;
        x = (x | (x << 16)) & 0x0000FFFF0000FFFFL;
        x = (x | (x << 8)) & 0x00FF00FF00FF00FFL;
        x = (x | (x << 4)) & 0x0F0F0F0F0F0F0F0FL;
        x = (x | (x << 2)) & 0x3333333333333333L;
        x = (x | (x << 1)) & 0x5555555555555555L;
        return x;
    }

    static long compact2(long v) {
        long x = v & 0x5555555555555555L;
        x = (x ^ (x >>> 1)) & 0x3333333333333333L;
        x = (x ^ (x >>> 2)) & 0x0F0F0F0F0F0F0F0FL;
        x = (x ^ (x >>> 4)) & 0x00FF00FF00FF00FFL;
        x = (x ^ (x >>> 8)) & 0x0000FFFF0000FFFFL;
        x = (x ^ (x >>> 16)) & 0x00000000FFFFFFFFL;
        return x;
    }

    /** 64-bit code: bit {@code 2i} is bit {@code i} of x, bit {@code 2i+1} of y. */
    public static long encode2(int x, int y) {
        return spread2(x) | (spread2(y) << 1);
    }

    public static Vec2i decode2(long code) {
        return new Vec2i((int) compact2(code), (int) compact2(code >>> 1));
    }

    /** 30-bit code of three 10-bit coordinates (0 to 1023), as an {@code int} that is non-negative and sorts as a signed or an unsigned integer alike. */
    public static int encode3Int(int x, int y, int z) {
        return (int) encode3(x & 0x3FF, y & 0x3FF, z & 0x3FF);
    }

    public static Vec3i decode3Int(int code) {
        return decode3(code & 0x3FFFFFFFL);
    }

    /** 32-bit code of two 16-bit coordinates (0 to 65535); the code is unsigned, so compare with {@link Integer#compareUnsigned} or sort with an unsigned sort. */
    public static int encode2Int(int x, int y) {
        return (int) encode2(x & 0xFFFF, y & 0xFFFF);
    }

    public static Vec2i decode2Int(int code) {
        return decode2(code & 0xFFFFFFFFL);
    }

    /**
     * Maps {@code v} from {@code [min, max]} onto {@code [0, cells - 1]}, clamping values outside the range. Use it to
     * turn positions into Morton input, with {@code cells = MAX_3D + 1} for full 3D precision.
     */
    public static int quantize(float v, float min, float max, int cells) {
        float t = (v - min) / (max - min);
        int q = (int) (t * cells);
        return Math.min(Math.max(q, 0), cells - 1);
    }

    /** Morton code of {@code p} inside the given bounds at full 21-bit precision per axis. */
    public static long encode3(Vec3f p, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        int cells = MAX_3D + 1;
        return encode3(quantize(p.x(), minX, maxX, cells), quantize(p.y(), minY, maxY, cells),
                quantize(p.z(), minZ, maxZ, cells));
    }
}
