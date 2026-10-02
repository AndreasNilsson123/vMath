package vmath.geo;

/**
 * Result of classifying a bounding volume against a convex region such as a {@code Frustum}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class Containment {

    private Containment() {
    }

    /** Entirely outside. Never reported for a volume that touches the region (tests are conservative). */
    public static final int OUTSIDE = 0;
    /** Straddles the boundary, or is too close to call. */
    public static final int INTERSECTING = 1;
    /** Entirely inside. */
    public static final int INSIDE = 2;
}
