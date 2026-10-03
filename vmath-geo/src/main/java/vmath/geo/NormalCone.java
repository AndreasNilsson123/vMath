package vmath.geo;

/**
 * Normal cones of clusters of triangles, and the conservative back-face test against them: the pure
 * geometry behind {@code vmath.spatial.ConeCull} and {@code vmath.mesh.Meshlets}.
 *
 * <p>A cluster is summarised by a bounding sphere and a <b>normal cone</b>: an axis (unit vector)
 * and a {@code cutoff}, the sine of the cone's half-angle {@code theta}, where every triangle
 * normal of the cluster lies within {@code theta} of the axis. A cluster whose triangles all face
 * away from the eye can be skipped without looking at a single triangle.
 *
 * <p><b>The test</b> (the well-known conservative form): the cluster is culled when
 * {@code dot(center - eye, axis) >= cutoff * |center - eye| + radius}. The sphere term accounts for
 * the eye seeing different parts of the cluster from different directions, so the test never culls
 * a cluster that has a front-facing triangle. It may keep some fully back-facing ones (it is
 * conservative, never the other way round). For an orthographic view every triangle is seen along
 * the same direction, so the sphere term drops out: {@code dot(axis, viewDir) >= cutoff}.
 *
 * <p>A cluster whose normals spread too widely (some triangle facing more than about 84 degrees
 * from the average) gets {@code cutoff = 1}, which never culls: no cone exists.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays you pass in are not synchronised, so two threads must not write the same
 * one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] normals = {0f, 0f, 1f, 0.1f, 0f, 0.99f, 0f, 0.1f, 0.99f};   // three triangle normals
 * float[] cone = new float[4];
 * NormalCone.compute(normals, 0, 3, cone, 0);                             // axis (xyz) and cutoff (w)
 * boolean cull = NormalCone.backfacingOrthographic(cone[0], cone[1], cone[2], cone[3], 0f, 0f, -1f);
 * }</pre>
 */
public final class NormalCone {

    private NormalCone() {
    }

    /**
     * Below this, the normals are too spread for a useful cone and the cluster is never culled.
     */
    private static final float MIN_USEFUL_DOT = 0.1f;

    /**
     * Computes the normal cone of {@code triangleCount} unit normals stored as {@code x, y, z}
     * triples starting at {@code offset}.
     *
     * <p>Writes {@code axisX, axisY, axisZ, cutoff} to {@code out[outOffset..outOffset + 3]}. With
     * no triangles, or normals that cancel out or spread too wide, the result is axis (0, 0, 1)
     * with cutoff 1 (never culls).
     *
     * @param normals the normals
     * @param offset the index of the first float of the first normal
     * @param triangleCount the triangle count
     * @param out receives the result
     * @param outOffset the index of the first float written to {@code out}
     */
    public static void compute(float[] normals, int offset, int triangleCount, float[] out, int outOffset) {
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

    /**
     * Tests with the normal cone of a cluster whether none of its triangles can face the eye, which
     * allows skipping whole meshlets; conservative, so a {@code false} result does not mean the
     * cluster is visible.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param radius the radius
     * @param ax the x component of the cone axis
     * @param ay the y component of the cone axis
     * @param az the z component of the cone axis
     * @param cutoff the sine of the half angle of the normal cone; 1 means no useful cone
     * @param ex the x coordinate of the eye, in the space of the cluster data
     * @param ey the y coordinate of the eye, in the space of the cluster data
     * @param ez the z coordinate of the eye, in the space of the cluster data
     * @return {@code true} if the cluster is certainly back-facing when seen from
     *     {@code (ex, ey, ez)}
     */
    public static boolean backfacing(float cx, float cy, float cz, float radius, float ax, float ay, float az, float cutoff,
                                     float ex, float ey, float ez) {
        if (cutoff >= 1f) {
            return false; // no cone: nothing to test (and the axis is meaningless)
        }
        float dx = cx - ex, dy = cy - ey, dz = cz - ez;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        return dx * ax + dy * ay + dz * az >= cutoff * dist + radius;
    }

    /**
     * Returns the orthographic version: true if every triangle faces away from the view direction
     * {@code (vx, vy, vz)} (unit length).
     *
     * @param ax the x component of the cone axis
     * @param ay the y component of the cone axis
     * @param az the z component of the cone axis
     * @param cutoff the sine of the half angle of the normal cone; 1 means no useful cone
     * @param vx the x component of the unit view direction
     * @param vy the y component of the unit view direction
     * @param vz the z component of the unit view direction
     * @return {@code true} if every triangle of the cone faces away from the view direction, so
     *     that the whole cluster can be culled
     */
    public static boolean backfacingOrthographic(float ax, float ay, float az, float cutoff, float vx, float vy, float vz) {
        return cutoff < 1f && ax * vx + ay * vy + az * vz >= cutoff;
    }
}
