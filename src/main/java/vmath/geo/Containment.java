package vmath.geo;

/** Result of classifying a bounding volume against a convex region such as a {@code Frustum}. */
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
