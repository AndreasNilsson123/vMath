package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * Culling against the volume a local light reaches: which objects a point light or spot light can touch, and which faces of a
 * point light's shadow cube map they land in. All functions work on {@link BoundsArray} and clear bits of a
 * {@link VisibilitySet} (only visible objects are examined), and none allocates.
 *
 * <p>Every test is conservative: an object the light reaches is never removed.
 */
public final class LightCull {

    private LightCull() {
    }

    /** Clears every visible object whose box is farther than {@code range} from the light. Returns how many were cleared. */
    public static int pointLight(BoundsArray bounds, VisibilitySet visible, float x, float y, float z, float range) {
        float r2 = range * range;
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = visible.words();
        int cleared = 0;
        for (int i = visible.nextSetBit(0); i >= 0 && i < bounds.size(); i = visible.nextSetBit(i + 1)) {
            if (distanceSquared(x0[i], y0[i], z0[i], x1[i], y1[i], z1[i], x, y, z) > r2) {
                words[i >>> 6] &= ~(1L << i);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Clears every visible object that a spot light cannot reach: the light sits at {@code (ax, ay, az)}, shines along
     * {@code (dx, dy, dz)} (any length) with the given half-angle (radians, below pi/2) out to {@code range}. Each box is
     * represented by its bounding sphere, so the test may keep objects near the cone's edge that miss it by a hair.
     * Returns how many were cleared.
     */
    public static int spotLight(BoundsArray bounds, VisibilitySet visible, float ax, float ay, float az,
                                float dx, float dy, float dz, float halfAngle, float range) {
        if (!(halfAngle > 0f && halfAngle < (float) (Math.PI / 2.0))) {
            throw new IllegalArgumentException("halfAngle must be in (0, pi/2): " + halfAngle);
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 0f)) {
            throw new IllegalArgumentException("direction must not be zero");
        }
        dx /= len;
        dy /= len;
        dz /= len;
        float sin = (float) Math.sin(halfAngle), cos = (float) Math.cos(halfAngle);
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = visible.words();
        int cleared = 0;
        for (int i = visible.nextSetBit(0); i >= 0 && i < bounds.size(); i = visible.nextSetBit(i + 1)) {
            float hx = (x1[i] - x0[i]) * 0.5f, hy = (y1[i] - y0[i]) * 0.5f, hz = (z1[i] - z0[i]) * 0.5f;
            float r = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) * 1.0001f + 1e-6f;
            float vx = (x0[i] + x1[i]) * 0.5f - ax, vy = (y0[i] + y1[i]) * 0.5f - ay, vz = (z0[i] + z1[i]) * 0.5f - az;
            float along = vx * dx + vy * dy + vz * dz;
            float perpSq = Math.max(0f, vx * vx + vy * vy + vz * vz - along * along);
            // distance from the sphere centre to the infinite cone surface (an underestimate near the apex, so conservative)
            float toCone = cos * (float) Math.sqrt(perpSq) - along * sin;
            boolean outside = toCone > r || along > range + r || along < -r;
            if (outside && vx == vx && vy == vy && vz == vz) {
                words[i >>> 6] &= ~(1L << i);
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * For a point light at {@code (x, y, z)} reaching {@code range}: clears visible objects that touch none of its six cube
     * faces, and stores for the others a bit mask of the faces they touch in {@code masks[i]} (bit {@code f} for face
     * {@code f} of {@link vmath.camera.CubeFaces}: +X, -X, +Y, -Y, +Z, -Z). An object that straddles a face boundary has several
     * bits set and must be drawn into each of those faces. Objects outside {@code range} are cleared too. Returns how many
     * were cleared.
     *
     * <p>A face is set when the box is not entirely outside one of the four side planes of that face's 90 degree pyramid
     * (an ordinary conservative box-plane test), so the mask may contain a face the box only just misses, never the reverse.
     * Entries of {@code masks} for objects that are not visible are left alone.
     */
    public static int cubeFaces(BoundsArray bounds, VisibilitySet visible, float x, float y, float z, float range, byte[] masks) {
        int n = bounds.size();
        if (masks.length < n) {
            throw new IllegalArgumentException("masks needs " + n + " entries");
        }
        float r2 = range * range;
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = visible.words();
        int cleared = 0;
        for (int i = visible.nextSetBit(0); i >= 0 && i < n; i = visible.nextSetBit(i + 1)) {
            int mask = 0;
            if (!(distanceSquared(x0[i], y0[i], z0[i], x1[i], y1[i], z1[i], x, y, z) > r2)) {
                mask = faceMask(x0[i] - x, y0[i] - y, z0[i] - z, x1[i] - x, y1[i] - y, z1[i] - z);
            }
            float sum = x0[i] + y0[i] + z0[i] + x1[i] + y1[i] + z1[i];
            if (sum != sum) {
                mask = 63; // NaN bounds: keep what cannot be judged, in every face
            }
            if (mask == 0) {
                words[i >>> 6] &= ~(1L << i);
                cleared++;
            } else {
                masks[i] = (byte) mask;
            }
        }
        return cleared;
    }

    /**
     * The cube faces a box touches, given the box relative to the light (light at the origin). Bit {@code f} is set unless the
     * box lies entirely outside a side plane of face {@code f}'s pyramid.
     */
    public static int faceMask(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        // the largest value of (main +- side) over the box, for each axis as the main axis
        int mask = 0;
        // +X: x >= |y|, x >= |z|
        if (maxX + maxY >= 0f && maxX - minY >= 0f && maxX + maxZ >= 0f && maxX - minZ >= 0f) {
            mask |= 1;
        }
        // -X: -x >= |y|, -x >= |z|
        if (-minX + maxY >= 0f && -minX - minY >= 0f && -minX + maxZ >= 0f && -minX - minZ >= 0f) {
            mask |= 2;
        }
        // +Y: y >= |x|, y >= |z|
        if (maxY + maxX >= 0f && maxY - minX >= 0f && maxY + maxZ >= 0f && maxY - minZ >= 0f) {
            mask |= 4;
        }
        // -Y
        if (-minY + maxX >= 0f && -minY - minX >= 0f && -minY + maxZ >= 0f && -minY - minZ >= 0f) {
            mask |= 8;
        }
        // +Z: z >= |x|, z >= |y|
        if (maxZ + maxX >= 0f && maxZ - minX >= 0f && maxZ + maxY >= 0f && maxZ - minY >= 0f) {
            mask |= 16;
        }
        // -Z
        if (-minZ + maxX >= 0f && -minZ - minX >= 0f && -minZ + maxY >= 0f && -minZ - minY >= 0f) {
            mask |= 32;
        }
        return mask;
    }

    private static float distanceSquared(float x0, float y0, float z0, float x1, float y1, float z1,
                                         float px, float py, float pz) {
        float dx = Math.max(Math.max(x0 - px, 0f), px - x1);
        float dy = Math.max(Math.max(y0 - py, 0f), py - y1);
        float dz = Math.max(Math.max(z0 - pz, 0f), pz - z1);
        return dx * dx + dy * dy + dz * dz;
    }
}
