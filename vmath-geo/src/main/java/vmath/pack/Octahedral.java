package vmath.pack;

import vmath.core.Vec2f;
import vmath.core.Vec3f;

/**
 * Octahedral encoding of unit vectors (Meyer et al. 2010; Cigolle et al., "A Survey of Efficient
 * Representations for Independent Unit Vectors", 2014): a direction becomes two numbers in [-1, 1]
 * by projecting the unit sphere onto an octahedron and unfolding it into a square.
 *
 * <p>Two 16-bit values (4 bytes) or two 8-bit values (2 bytes) replace a 12-byte {@code Vec3f},
 * with worst-case angular errors of about 0.00004 radians (0.0025 degrees) and 0.011 radians (0.63
 * degrees) respectively, measured over millions of random directions (see {@code docs/FORMATS.md}).
 * It is the usual compact format for vertex normals, tangents and G-buffer normals.
 *
 * <p>The packing functions quantize with <b>best-of-four rounding</b>: they try the four
 * neighbouring quantized values around the exact result and keep the one whose decoded direction is
 * closest, which cuts the worst error at 8 bits by about a third compared with plain rounding
 * (0.011 against 0.017 radians).
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Vec3f n = new Vec3f(0f, 1f, 0f);
 * int packed = Octahedral.pack16(n);                                     // two snorm8 in 16 bits
 * Vec3f back = Octahedral.unpack16(packed);                              // a unit vector close to n
 * float error = Octahedral.angleBetween(n, back);
 * }</pre>
 */
public final class Octahedral {

    private Octahedral() {
    }

    private static float signNotZero(float v) {
        return v >= 0f ? 1f : -1f;
    }

    /**
     * Maps a unit vector to a point on a square by folding the octahedron onto it, which encodes a
     * direction in two numbers with a small error; the input must have unit length.
     *
     * <p>The input must be non-zero.
     *
     * @param n the vector; must not be {@code null}
     * @return the unfolded square position of a unit vector, each component in [-1, 1]
     */
    public static Vec2f encode(Vec3f n) {
        float invL1 = 1f / (Math.abs(n.x()) + Math.abs(n.y()) + Math.abs(n.z()));
        float px = n.x() * invL1, py = n.y() * invL1;
        if (n.z() < 0f) {
            float fx = (1f - Math.abs(py)) * signNotZero(px);
            float fy = (1f - Math.abs(px)) * signNotZero(py);
            px = fx;
            py = fy;
        }
        return new Vec2f(px, py);
    }

    /**
     * Maps a point of the square back to a unit direction; components outside the square are
     * clamped.
     *
     * @param x the x component
     * @param y the y component
     * @return the unit vector at a square position (components outside [-1, 1] are clamped)
     */
    public static Vec3f decode(float x, float y) {
        float ex = Math.max(-1f, Math.min(1f, x));
        float ey = Math.max(-1f, Math.min(1f, y));
        float vz = 1f - Math.abs(ex) - Math.abs(ey);
        float vx = ex, vy = ey;
        if (vz < 0f) {
            vx = (1f - Math.abs(ey)) * signNotZero(ex);
            vy = (1f - Math.abs(ex)) * signNotZero(ey);
        }
        return new Vec3f(vx, vy, vz).normalize();
    }

    /**
     * Maps a point of the square, given as a vector, back to a unit direction.
     *
     * @param p the vector; must not be {@code null}
     * @return {@link #decode(float, float)} for a vector
     */
    public static Vec3f decode(Vec2f p) {
        return decode(p.x(), p.y());
    }

    // ---------------------------------------------------------------- 16-bit per component (32 bits total)

    /**
     * Encodes a unit direction in sixteen bits per component, packed into one int; the error is far
     * below what is visible in shading.
     *
     * @param n the vector; must not be {@code null}
     * @return two snorm16 in one int ({@code x} in the low half)
     */
    public static int pack16(Vec3f n) {
        return pack16(n.x(), n.y(), n.z());
    }

    /**
     * Encodes a unit direction in sixteen bits per component from separate components; allocates
     * nothing, so it can run over every vertex of a mesh.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return {@link #pack16(Vec3f)} for a vector given by its components: allocates nothing, so it
     *     can run over every vertex of a mesh
     */
    public static int pack16(float x, float y, float z) {
        return bestOfFour(x, y, z, 32767f, true);
    }

    /**
     * Decodes a unit direction packed by {@link #pack16}.
     *
     * @param packed the packed value
     * @return the unit vector for a value made by {@link #pack16}: two snorm16, {@code x} in the
     *     low half
     */
    public static Vec3f unpack16(int packed) {
        return decode(Norm.unpackSnorm16(packed), Norm.unpackSnorm16(packed >>> 16));
    }

    // ---------------------------------------------------------------- 8-bit per component (16 bits total)

    /**
     * Encodes a unit direction in eight bits per component, packed into 16 bits of an int; compact,
     * but the error is visible on smooth highlights.
     *
     * <p>Store the result as a {@code short}.
     *
     * @param n the vector; must not be {@code null}
     * @return two snorm8 in the low 16 bits of an int ({@code x} in the low byte)
     */
    public static int pack8(Vec3f n) {
        return pack8(n.x(), n.y(), n.z());
    }

    /**
     * Encodes a unit direction in eight bits per component from separate components; allocates
     * nothing.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return {@link #pack8(Vec3f)} for a vector given by its components; allocates nothing
     */
    public static int pack8(float x, float y, float z) {
        return bestOfFour(x, y, z, 127f, false);
    }

    /**
     * Decodes a unit direction packed by {@link #pack8}.
     *
     * @param packed the packed value
     * @return the unit vector for a value made by {@link #pack8}: two snorm8 in the low 16 bits,
     *     {@code x} in the low byte
     */
    public static Vec3f unpack8(int packed) {
        return decode(Norm.unpackSnorm8(packed), Norm.unpackSnorm8(packed >>> 8));
    }

    /**
     * Tries floor/ceil of each scaled component and keeps the combination that decodes closest to
     * {@code (x, y, z)}.
     *
     * <p>The arithmetic is that of {@link #encode}, {@link #decode(float, float)} and
     * {@code Vec3f.cross}, written on primitives so that nothing is allocated; the result is the
     * same bits.
     */
    private static int bestOfFour(float x, float y, float z, float scale, boolean sixteenBit) {
        float invL1 = 1f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float px = x * invL1, py = y * invL1;
        if (z < 0f) {
            float fx = (1f - Math.abs(py)) * signNotZero(px);
            float fy = (1f - Math.abs(px)) * signNotZero(py);
            px = fx;
            py = fy;
        }
        float sx = px * scale, sy = py * scale;
        int flx = (int) Math.floor(sx), fly = (int) Math.floor(sy);
        // choose by the length of the cross product, which grows with the angle and, unlike a dot product, stays precise for
        // the small angles that matter here (a float dot near 1 cannot tell 0.0001 rad from 0.0002 rad)
        float bestError = Float.POSITIVE_INFINITY;
        int bestX = 0, bestY = 0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                int qx = clampQuantized(flx + dx, scale), qy = clampQuantized(fly + dy, scale);
                float ex = Math.max(-1f, Math.min(1f, qx / scale));
                float ey = Math.max(-1f, Math.min(1f, qy / scale));
                float vz = 1f - Math.abs(ex) - Math.abs(ey);
                float vx = ex, vy = ey;
                if (vz < 0f) {
                    vx = (1f - Math.abs(ey)) * signNotZero(ex);
                    vy = (1f - Math.abs(ex)) * signNotZero(ey);
                }
                float len2 = vx * vx + vy * vy + vz * vz;
                float dxn, dyn, dzn;
                if (len2 >= Float.MIN_NORMAL && len2 <= Float.MAX_VALUE) {
                    float inv = 1f / (float) Math.sqrt(len2);
                    dxn = vx * inv;
                    dyn = vy * inv;
                    dzn = vz * inv;
                } else {
                    Vec3f d = new Vec3f(vx, vy, vz).normalize(); // the scaled slow path of Vec3f.normalize: not reachable for a decoded octahedral vector, kept for exactness
                    dxn = d.x();
                    dyn = d.y();
                    dzn = d.z();
                }
                float cx = dyn * z - dzn * y, cy = dzn * x - dxn * z, cz = dxn * y - dyn * x;
                float error = cx * cx + cy * cy + cz * cz;
                if (error < bestError) {
                    bestError = error;
                    bestX = qx;
                    bestY = qy;
                }
            }
        }
        return sixteenBit ? (bestX & 0xFFFF) | ((bestY & 0xFFFF) << 16) : (bestX & 0xFF) | ((bestY & 0xFF) << 8);
    }

    private static int clampQuantized(int q, float scale) {
        int max = (int) scale;
        return Math.max(-max, Math.min(max, q));
    }

    /**
     * Computes the angle between two unit vectors from the lengths of their sum and difference,
     * which stays accurate for small angles where the arc cosine of the dot product does not.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @return angle in radians between two unit vectors, accurate for small angles
     */
    public static float angleBetween(Vec3f a, Vec3f b) {
        return (float) Math.atan2(a.cross(b).length(), a.dot(b));
    }
}
