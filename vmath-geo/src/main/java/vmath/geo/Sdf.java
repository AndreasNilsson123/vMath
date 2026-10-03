package vmath.geo;

/**
 * A signed distance function: the distance from a point to the surface of a solid, negative inside.
 *
 * <p>The zero set is the surface.
 *
 * <p>Every function made by {@link Sdfs} says how far it can be trusted. An <b>exact</b> distance
 * (sphere, box, capsule, ...) is the true distance to the surface everywhere. A <b>bound</b> never
 * exceeds the true distance in absolute value (it is 1-Lipschitz: it changes by at most the
 * distance between two points), which is all that sphere tracing ({@link Sdfs#raycast}) needs to be
 * safe, but it is not the distance. The union of two exact distances, for example, is exact outside
 * both solids and only a bound inside them; the doc of each combinator says which it keeps. A
 * function of your own that overestimates the distance (a distorting warp, for example) is not safe
 * for {@link Sdfs#raycast}: scale it down by its Lipschitz constant first.
 *
 * <p>Implementations must be pure (the same point always gives the same value), allocation-free if
 * they are to be used in hot loops, and safe to call from several threads if you share them. The
 * functions of {@link Sdfs} are immutable lambdas and satisfy all three.
 *
 * <p><b>Thread safety.</b> Implementations must be pure to be shared between threads. The functions
 * made by {@link Sdfs} are immutable and may be called from any thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Sdf sphere = (x, y, z) -> (float) Math.sqrt(x * x + y * y + z * z) - 1f;   // any pure function is an Sdf
 * float inside = sphere.distance(0f, 0f, 0f);                                   // -1
 * Sdf moved = Sdfs.translate(sphere, 2f, 0f, 0f);
 * }</pre>
 */
@FunctionalInterface
public interface Sdf {

    /**
     * Evaluates the signed distance function at a point; whether the value is exact or a bound is
     * stated by the implementation.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @return the signed distance (or a bound of it, see the interface) from the point
     *     {@code (x, y, z)} to the surface: negative inside, zero on it
     */
    float distance(float x, float y, float z);
}
