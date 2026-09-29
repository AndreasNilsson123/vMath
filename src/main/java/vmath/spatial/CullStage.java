package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * One step of a culling pipeline. A stage only ever <b>clears</b> bits of objects it rejects; it must leave the
 * bits of objects it keeps untouched, so stages compose in any order and each sees the survivors of the previous ones.
 * The call happens once per stage per frame (the per-object work is inside), so an interface costs nothing here.
 */
public interface CullStage {

    void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible);
}
