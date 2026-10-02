package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.VisibilitySet;

/**
 * Backface culling of whole clusters of triangles ("meshlets") with one test per cluster.
 *
 * <p>A cluster is summarised by a bounding sphere and a <b>normal cone</b>: an axis (unit vector) and a {@code cutoff}, the sine
 * of the cone's half-angle {@code theta}, where every triangle normal of the cluster lies within {@code theta} of the axis.
 * A cluster whose triangles all face away from the eye can be skipped without looking at a single triangle.
 *
 * <p><b>The test</b> (the well-known conservative form): the cluster is culled when
 * {@code dot(center - eye, axis) >= cutoff * |center - eye| + radius}. The sphere term accounts for the eye seeing different parts
 * of the cluster from different directions, so the test never culls a cluster that has a front-facing triangle. It may keep
 * some fully back-facing ones (it is conservative, never the other way round). For an orthographic view every triangle is seen
 * along the same direction, so the sphere term drops out: {@code dot(axis, viewDir) >= cutoff}.
 *
 * <p>A cluster whose normals spread too widely (some triangle facing more than about 84 degrees from the average) gets
 * {@code cutoff = 1}, which never culls: no cone exists.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class ConeCull {

    private ConeCull() {
    }

    /** Below this, the normals are too spread for a useful cone and the cluster is never culled. */
    private static final float MIN_USEFUL_DOT = 0.1f;

    /**
     * Computes the normal cone of {@code triangleCount} unit normals stored as {@code x, y, z} triples starting at
     * {@code offset}. Writes {@code axisX, axisY, axisZ, cutoff} to {@code out[outOffset..outOffset + 3]}.
     * With no triangles, or normals that cancel out or spread too wide, the result is axis (0, 0, 1) with cutoff 1 (never culls).
     */
    public static void computeCone(float[] normals, int offset, int triangleCount, float[] out, int outOffset) {
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < triangleCount; i++) {
            int o = offset + i * 3;
            sx += normals[o];
            sy += normals[o + 1];
            sz += normals[o + 2];
        }
        double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (!(len > 1e-12)) {
            noCone(out, outOffset);
            return;
        }
        float ax = (float) (sx / len), ay = (float) (sy / len), az = (float) (sz / len);
        float minDot = 1f;
        for (int i = 0; i < triangleCount; i++) {
            int o = offset + i * 3;
            minDot = Math.min(minDot, normals[o] * ax + normals[o + 1] * ay + normals[o + 2] * az);
        }
        if (!(minDot >= MIN_USEFUL_DOT)) {
            noCone(out, outOffset);
            return;
        }
        // widen a hair so float rounding of the axis can never make a normal fall outside the cone
        float slack = 1e-5f;
        float sin = (float) Math.sqrt(Math.max(0f, 1f - minDot * minDot)) + slack;
        out[outOffset] = ax;
        out[outOffset + 1] = ay;
        out[outOffset + 2] = az;
        out[outOffset + 3] = Math.min(1f, sin);
    }

    private static void noCone(float[] out, int o) {
        out[o] = 0f;
        out[o + 1] = 0f;
        out[o + 2] = 1f;
        out[o + 3] = 1f;
    }

    /** True if the cluster is certainly back-facing when seen from {@code (ex, ey, ez)}. */
    public static boolean backfacing(float cx, float cy, float cz, float radius, float ax, float ay, float az, float cutoff,
                                     float ex, float ey, float ez) {
        if (cutoff >= 1f) {
            return false; // no cone: nothing to test (and the axis is meaningless)
        }
        float dx = cx - ex, dy = cy - ey, dz = cz - ez;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dx * ax + dy * ay + dz * az >= cutoff * dist + radius;
    }

    /** Orthographic version: true if every triangle faces away from the view direction {@code (vx, vy, vz)} (unit length). */
    public static boolean backfacingOrthographic(float ax, float ay, float az, float cutoff, float vx, float vy, float vz) {
        return cutoff < 1f && ax * vx + ay * vy + az * vz >= cutoff;
    }

    /** Structure-of-arrays storage for the bounding spheres and normal cones of many clusters. */
    public static final class Clusters {

        private float[] center = new float[3 * 16];
        private float[] radius = new float[16];
        private float[] cone = new float[4 * 16];
        private int size;

        public Clusters() {
        }

        public int size() {
            return size;
        }

        /** Adds a cluster and returns its index. {@code axis} must have unit length. */
        public int add(float cx, float cy, float cz, float radius, float ax, float ay, float az, float cutoff) {
            if (size == this.radius.length) {
                int c = size * 2;
                center = Arrays.copyOf(center, c * 3);
                this.radius = Arrays.copyOf(this.radius, c);
                cone = Arrays.copyOf(cone, c * 4);
            }
            int i = size++;
            center[i * 3] = cx;
            center[i * 3 + 1] = cy;
            center[i * 3 + 2] = cz;
            this.radius[i] = radius;
            cone[i * 4] = ax;
            cone[i * 4 + 1] = ay;
            cone[i * 4 + 2] = az;
            cone[i * 4 + 3] = cutoff;
            return i;
        }

        public void clear() {
            size = 0;
        }

        /** Clears the bit of every visible cluster that is back-facing from the eye position. Returns how many were culled. */
        public int cull(float ex, float ey, float ez, VisibilitySet visible) {
            long[] words = visible.words();
            int culled = 0;
            for (int i = visible.nextSetBit(0); i >= 0 && i < size; i = visible.nextSetBit(i + 1)) {
                if (backfacing(center[i * 3], center[i * 3 + 1], center[i * 3 + 2], radius[i],
                        cone[i * 4], cone[i * 4 + 1], cone[i * 4 + 2], cone[i * 4 + 3], ex, ey, ez)) {
                    words[i >>> 6] &= ~(1L << i);
                    culled++;
                }
            }
            return culled;
        }

        /** Orthographic version of {@link #cull}: {@code (vx, vy, vz)} is the unit direction the camera looks along. */
        public int cullOrthographic(float vx, float vy, float vz, VisibilitySet visible) {
            long[] words = visible.words();
            int culled = 0;
            for (int i = visible.nextSetBit(0); i >= 0 && i < size; i = visible.nextSetBit(i + 1)) {
                if (backfacingOrthographic(cone[i * 4], cone[i * 4 + 1], cone[i * 4 + 2], cone[i * 4 + 3], vx, vy, vz)) {
                    words[i >>> 6] &= ~(1L << i);
                    culled++;
                }
            }
            return culled;
        }
    }
}
