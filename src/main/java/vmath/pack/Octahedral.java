package vmath.pack;

import vmath.core.Vec2f;
import vmath.core.Vec3f;

/**
 * Octahedral encoding of unit vectors (Meyer et al. 2010; Cigolle et al., "A Survey of Efficient Representations for Independent Unit
 * Vectors", 2014): a direction becomes two numbers in [-1, 1] by projecting the unit sphere onto an octahedron and unfolding it into
 * a square. Two 16-bit values (4 bytes) or two 8-bit values (2 bytes) replace a 12-byte {@code Vec3f}, with worst-case angular errors
 * of about 0.00004 radians (0.0025 degrees) and 0.011 radians (0.63 degrees) respectively, measured over millions of random directions
 * (see {@code docs/FORMATS.md}). It is the usual compact format
 * for vertex normals, tangents and G-buffer normals.
 *
 * <p>The packing functions quantize with <b>best-of-four rounding</b>: they try the four neighbouring quantized values around the exact
 * result and keep the one whose decoded direction is closest, which cuts the worst error at 8 bits by about a third compared
 * with plain rounding (0.011 against 0.017 radians).
 */
public final class Octahedral {

    private Octahedral() {
    }

    private static float signNotZero(float v) {
        return v >= 0f ? 1f : -1f;
    }

    /** The unfolded square position of a unit vector, each component in [-1, 1]. The input must be non-zero. */
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

    /** The unit vector at a square position (components outside [-1, 1] are clamped). */
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

    public static Vec3f decode(Vec2f p) {
        return decode(p.x(), p.y());
    }

    // ---------------------------------------------------------------- 16-bit per component (32 bits total)

    /** Two snorm16 in one int ({@code x} in the low half). */
    public static int pack16(Vec3f n) {
        Vec2f e = encode(n);
        return bestOfFour(n, e, 32767f, true);
    }

    public static Vec3f unpack16(int packed) {
        return decode(Norm.unpackSnorm16(packed), Norm.unpackSnorm16(packed >>> 16));
    }

    // ---------------------------------------------------------------- 8-bit per component (16 bits total)

    /** Two snorm8 in the low 16 bits of an int ({@code x} in the low byte). Store the result as a {@code short}. */
    public static int pack8(Vec3f n) {
        Vec2f e = encode(n);
        return bestOfFour(n, e, 127f, false);
    }

    public static Vec3f unpack8(int packed) {
        return decode(Norm.unpackSnorm8(packed), Norm.unpackSnorm8(packed >>> 8));
    }

    /** Tries floor/ceil of each scaled component and keeps the combination that decodes closest to {@code n}. */
    private static int bestOfFour(Vec3f n, Vec2f e, float scale, boolean sixteenBit) {
        float sx = e.x() * scale, sy = e.y() * scale;
        int fx = (int) Math.floor(sx), fy = (int) Math.floor(sy);
        // choose by the length of the cross product, which grows with the angle and, unlike a dot product, stays precise for
        // the small angles that matter here (a float dot near 1 cannot tell 0.0001 rad from 0.0002 rad)
        float bestError = Float.POSITIVE_INFINITY;
        int bestX = 0, bestY = 0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                int qx = clampQuantized(fx + dx, scale), qy = clampQuantized(fy + dy, scale);
                float error = decode(qx / scale, qy / scale).cross(n).lengthSquared();
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

    /** Angle in radians between two unit vectors, accurate for small angles. */
    public static float angleBetween(Vec3f a, Vec3f b) {
        return (float) Math.atan2(a.cross(b).length(), a.dot(b));
    }
}
