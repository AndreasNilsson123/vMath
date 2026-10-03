package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * One step of a culling pipeline.
 *
 * <p>A stage only ever <b>clears</b> bits of objects it rejects; it must leave the bits of objects
 * it keeps untouched, so stages compose in any order and each sees the survivors of the previous
 * ones. The call happens once per stage per frame (the per-object work is inside), so an interface
 * costs nothing here.
 *
 * <p><b>Thread safety.</b> A stage is called once per frame by the thread that runs the pipeline.
 * Stages may own scratch memory (the built-in ones that do say so), in which case each thread needs
 * its own instance.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * CullStage everythingBehindTheCamera = (ctx, bounds, visible) -> {
 *     for (int i = visible.nextSetBit(0); i >= 0; i = visible.nextSetBit(i + 1)) {
 *         if (bounds.maxZ(i) < ctx.camera().z() - 1000f) {
 *             visible.clear(i);                                         // a stage only ever clears bits
 *         }
 *     }
 * };
 * }</pre>
 */
public interface CullStage {

    /**
     * Clears the bit of every object in {@code visible} that this stage rejects, and leaves the
     * other bits alone.
     *
     * <p>{@code bounds} holds the objects' boxes; {@code ctx} the view.
     *
     * @param ctx the culling context; must not be {@code null}
     * @param bounds the bounds; must not be {@code null}
     * @param visible the visibility set; must not be {@code null}
     */
    void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible);
}
