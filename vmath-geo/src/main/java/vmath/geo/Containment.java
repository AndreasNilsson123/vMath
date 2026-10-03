package vmath.geo;

/**
 * Result of classifying a bounding volume against a convex region such as a {@code Frustum}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Frustumf frustum = Frustumf.fromViewProjection(Mat4f.IDENTITY, DepthRange.of(ClipSpace.OPENGL));
 * int where = frustum.classify(Aabbf.of(new Vec3f(-0.5f, -0.5f, -0.5f), new Vec3f(0.5f, 0.5f, 0.5f)));
 * if (where == Containment.INSIDE) {
 *     // every plane contains the box: nothing more to test for its children
 * } else if (where == Containment.OUTSIDE) {
 *     // entirely outside one plane: cull it
 * }
 * }</pre>
 */
public final class Containment {

    private Containment() {
    }

    /**
     * Entirely outside.
     *
     * <p>Never reported for a volume that touches the region (tests are conservative).
     */
    public static final int OUTSIDE = 0;
    /**
     * Straddles the boundary, or is too close to call.
     */
    public static final int INTERSECTING = 1;
    /**
     * Entirely inside.
     */
    public static final int INSIDE = 2;
}
