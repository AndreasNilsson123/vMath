package vmath.spatial;

import java.util.List;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * An ordered chain of {@link CullStage}s. {@link #run} starts with every object visible and lets each stage clear
 * the bits it rejects, so cheap stages should come first (distance before frustum before small-feature is typical).
 * It allocates nothing per frame.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the stages own scratch memory (kernels, depth buffers), so use one pipeline, with its own stages, per
 * thread.
 */
public final class CullPipeline {

    private final CullStage[] stages;

    /** A pipeline that runs {@code stages} in the given order. */
    public CullPipeline(List<? extends CullStage> stages) {
        this.stages = stages.toArray(new CullStage[0]);
    }

    /** A pipeline that runs {@code stages} in the given order. */
    public static CullPipeline of(CullStage... stages) {
        return new CullPipeline(List.of(stages));
    }

    /** Runs all stages; {@code visible} is resized if needed and holds the survivors afterwards. Returns their number. */
    public int run(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        visible.ensureCapacity(bounds.size());
        visible.setAll(bounds.size());
        for (CullStage stage : stages) {
            stage.cull(ctx, bounds, visible);
        }
        return visible.count();
    }
}
