package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.VisibilitySet;
import vmath.geo.NormalCone;

/**
 * Backface culling of whole clusters of triangles ("meshlets") with one test per cluster, over many clusters at once. The cone computation and the single-cluster
 * test are {@link NormalCone}; the methods here forward to it. See there for the test and its conservativeness.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class ConeCull {

    private ConeCull() {
    }

    /** Forwards to {@link NormalCone#compute}. */
    public static void computeCone(float[] normals, int offset, int triangleCount, float[] out, int outOffset) {
        NormalCone.compute(normals, offset, triangleCount, out, outOffset);
    }

    /** Forwards to {@link NormalCone#backfacing}. */
    public static boolean backfacing(float cx, float cy, float cz, float radius, float ax, float ay, float az, float cutoff,
                                     float ex, float ey, float ez) {
        return NormalCone.backfacing(cx, cy, cz, radius, ax, ay, az, cutoff, ex, ey, ez);
    }

    /** Forwards to {@link NormalCone#backfacingOrthographic}. */
    public static boolean backfacingOrthographic(float ax, float ay, float az, float cutoff, float vx, float vy, float vz) {
        return NormalCone.backfacingOrthographic(ax, ay, az, cutoff, vx, vy, vz);
    }

    /** Structure-of-arrays storage for the bounding spheres and normal cones of many clusters. */
    public static final class Clusters {

        private float[] center = new float[3 * 16];
        private float[] radius = new float[16];
        private float[] cone = new float[4 * 16];
        private int size;

        /** An empty set of clusters. */
        public Clusters() {
        }

        /** The number of clusters. */
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

        /** Removes all clusters; the capacity is kept. */
        public void clear() {
            size = 0;
        }

        /** Clears the bit of every visible cluster that is back-facing from the eye position. Returns how many were culled. */
        public int cull(float ex, float ey, float ez, VisibilitySet visible) {
            long[] words = visible.words();
            int culled = 0;
            for (int i = visible.nextSetBit(0); i >= 0 && i < size; i = visible.nextSetBit(i + 1)) {
                if (NormalCone.backfacing(center[i * 3], center[i * 3 + 1], center[i * 3 + 2], radius[i],
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
                if (NormalCone.backfacingOrthographic(cone[i * 4], cone[i * 4 + 1], cone[i * 4 + 2], cone[i * 4 + 3], vx, vy, vz)) {
                    words[i >>> 6] &= ~(1L << i);
                    culled++;
                }
            }
            return culled;
        }
    }
}
