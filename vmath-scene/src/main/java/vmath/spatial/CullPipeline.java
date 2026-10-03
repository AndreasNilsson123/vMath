package vmath.spatial;

import java.util.List;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * An ordered chain of {@link CullStage}s. {@link #run} starts with every object visible and lets
 * each stage clear the bits it rejects, so cheap stages should come first (distance before frustum
 * before small-feature is typical).
 *
 * <p>It allocates nothing per frame.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the stages own scratch memory (kernels, depth buffers),
 * so use one pipeline, with its own stages, per thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * CullPipeline pipeline = CullPipeline.of(new CullStage[] {new CullStages.Frustum(), new CullStages.Distance(200f)});
 * BoundsArray bounds = new BoundsArray(1000);
 * VisibilitySet visible = new VisibilitySet(1000);
 * Frustumf frustum = Frustumf.fromViewProjection(Mat4f.IDENTITY, DepthRange.of(ClipSpace.OPENGL));
 * int survivors = pipeline.run(CullContext.perspective(frustum, Vec3f.ZERO, 1f, 1080), bounds, visible);
 * }</pre>
 */
public final class CullPipeline {

    private final CullStage[] stages;

    /**
     * Creates a pipeline that runs {@code stages} in the given order.
     *
     * @param stages the stages; must not be {@code null}
     */
    public CullPipeline(List<? extends CullStage> stages) {
        this.stages = stages.toArray(new CullStage[0]);
    }

    /**
     * Builds a pipeline that runs a list of stages in order.
     *
     * @param stages the stages; must not be {@code null}
     * @return a pipeline that runs {@code stages} in the given order
     */
    public static CullPipeline of(CullStage... stages) {
        return new CullPipeline(List.of(stages));
    }

    /**
     * Runs all stages; {@code visible} is resized if needed and holds the survivors afterwards.
     *
     * <p>Returns their number.
     *
     * @param ctx the culling context; must not be {@code null}
     * @param bounds the bounds; must not be {@code null}
     * @param visible the visibility set; must not be {@code null}
     * @return their number
     */
    public int run(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        visible.ensureCapacity(bounds.size());
        visible.setAll(bounds.size());
        for (CullStage stage : stages) {
            stage.cull(ctx, bounds, visible);
        }
        return visible.count();
    }
}
