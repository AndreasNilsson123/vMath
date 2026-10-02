package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * Frustum culling through a {@link StaticBvh} as a {@link CullStage}. For scenes where most objects are outside the
 * view this beats the flat {@link CullStages.Frustum} kernel, because whole subtrees are rejected at once; when most
 * objects are visible the flat kernel wins. Measure both on your data (see the benchmarks).
 *
 * <p>Owns its traversal state and a scratch set, so use one instance per thread. Keep the tree in sync with the
 * bounds ({@link StaticBvh#refit}) before calling.
 */
public final class BvhStage implements CullStage {

    private final BvhQuery query;
    private final VisibilitySet scratch;

    /** A stage that queries {@code bvh} with the context's frustum; it keeps the objects the tree reports visible. */
    public BvhStage(StaticBvh bvh) {
        this.query = new BvhQuery(bvh);
        this.scratch = new VisibilitySet(bvh.primitiveCount());
    }

    @Override
    public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        scratch.ensureCapacity(bounds.size());
        scratch.clearAll();
        query.frustum(ctx.frustum(), bounds, scratch);
        visible.and(scratch);
    }
}
