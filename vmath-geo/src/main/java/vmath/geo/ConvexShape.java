package vmath.geo;

import vmath.annotations.Experimental;

/**
 * A convex shape known only through its support function: the point of the shape that lies farthest
 * in a given direction.
 *
 * <p>That is all {@link Gjk} needs to find the distance between two shapes or how deeply they
 * overlap, so any convex shape (a sphere, a box, a capsule, a cone, a hull, the sweep of another
 * shape) can take part by implementing one method. {@link ConvexShapes} has the usual ones and the
 * combinators that move and rotate them.
 *
 * <p>Coordinates are double precision so that the iterations of GJK and EPA lose nothing to
 * rounding. Implementations must not allocate (the collision queries promise that they do not) and
 * must be consistent: for the same direction they return the same point, and the point must belong
 * to the shape.
 *
 * <p><b>Thread safety.</b> Implementations must be safe to call from several threads as long as
 * nobody changes the shape, because a support function is a pure function of the direction. The
 * shapes of {@link ConvexShapes} and {@link ConvexPolytope} are immutable (see their documentation)
 * and may be shared.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ConvexShape sphere = ConvexShapes.of(Spheref.of(Vec3f.ZERO, 1f));
 * ConvexShape box = ConvexShapes.translated(ConvexShapes.of(Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f))), 1.5, 0.0, 0.0);
 * boolean overlapping = new Gjk().intersects(sphere, box);
 * }</pre>
 */
@Experimental("the interface may gain a method for the shape's centre or extent if the collision code needs it")
@FunctionalInterface
public interface ConvexShape {

    /**
     * Writes to {@code out[0..2]} a point of the shape that maximises the dot product with
     * {@code (dx, dy, dz)}.
     *
     * <p>The direction is not normalised and is never the zero vector; when several points are
     * equally far (a face perpendicular to the direction) any of them may be returned.
     *
     * @param dx the x component of the direction
     * @param dy the y component of the direction
     * @param dz the z component of the direction
     * @param out receives the result
     */
    void support(double dx, double dy, double dz, double[] out);
}
