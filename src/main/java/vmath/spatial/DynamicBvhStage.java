package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * Frustum culling through a {@link DynamicAabbTree} as a {@link CullStage}. The tree owns the bounds, so the
 * {@code bounds} argument of {@link #cull} is ignored: each object's user data must be its index in the
 * {@link VisibilitySet}, i.e. smaller than its capacity.
 *
 * <p>Owns its traversal state and a scratch set, so use one instance per thread.
 */
public final class DynamicBvhStage implements CullStage {

    private final DynamicAabbTree.Query query;
    private final VisibilitySet scratch = new VisibilitySet(64);

    /** A stage that queries {@code tree} with the context's frustum; it keeps the objects the tree reports visible. */
    public DynamicBvhStage(DynamicAabbTree tree) {
        this.query = tree.newQuery();
    }

    @Override
    public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        scratch.ensureCapacity(visible.capacity());
        scratch.clearAll();
        query.frustum(ctx.frustum(), scratch);
        visible.and(scratch);
    }
}
