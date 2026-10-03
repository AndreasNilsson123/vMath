package vmath.geo;

/**
 * A signed distance function: the distance from a point to the surface of a solid, negative inside. The zero set is the surface.
 *
 * <p>Every function made by {@link Sdfs} says how far it can be trusted. An <b>exact</b> distance (sphere, box, capsule, ...) is the true distance to the surface everywhere. A <b>bound</b> never exceeds the true
 * distance in absolute value (it is 1-Lipschitz: it changes by at most the distance between two points), which is all that sphere tracing ({@link Sdfs#raycast}) needs to be safe, but it is not the distance.
 * The union of two exact distances, for example, is exact outside both solids and only a bound inside them; the doc of each combinator says which it keeps. A function of your own that overestimates the distance (a
 * distorting warp, for example) is not safe for {@link Sdfs#raycast}: scale it down by its Lipschitz constant first.
 *
 * <p>Implementations must be pure (the same point always gives the same value), allocation-free if they are to be used in hot loops, and safe to call from several threads if you share them. The functions
 * of {@link Sdfs} are immutable lambdas and satisfy all three.
 */
@FunctionalInterface
public interface Sdf {

    /** The signed distance (or a bound of it, see the interface) from the point {@code (x, y, z)} to the surface: negative inside, zero on it. */
    float distance(float x, float y, float z);
}
