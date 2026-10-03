package vmath.occlusion;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.spatial.CullContext;
import vmath.spatial.CullStage;

/**
 * A {@link CullStage} that removes objects hidden behind the occluders in a {@link DepthBuffer}.
 *
 * <p>Fill the buffer once per frame (begin, add occluders) before the pipeline runs; the stage only
 * reads it. Put it after the cheap stages (distance, frustum): it works on the objects that survive
 * them, and each test costs a few texel reads. The decision per object is the buffer's
 * {@link DepthBuffer#isHidden}: conservative, so an object that could be seen is kept.
 *
 * <p>The buffer's camera must be the one the rest of the pipeline uses; the {@link CullContext} is
 * not consulted.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the stage owns scratch memory (its depth buffer), so
 * use one instance per thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * DepthBuffer depth = new DepthBuffer(256, 128);
 * OcclusionStage stage = new OcclusionStage(depth);                     // build the depth buffer before the pipeline runs
 * CullPipeline pipeline = CullPipeline.of(new CullStage[] {new CullStages.Frustum(), stage});
 * }</pre>
 */
public final class OcclusionStage implements CullStage {

    private final DepthBuffer buffer;

    /**
     * Creates a stage that tests against {@code buffer}, which the caller keeps filled with the
     * occluders of the current frame.
     *
     * @param buffer the buffer; must not be {@code null}
     */
    public OcclusionStage(DepthBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
        buffer.finish();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = visible.words();
        for (int i = visible.nextSetBit(0); i >= 0 && i < bounds.size(); i = visible.nextSetBit(i + 1)) {
            if (buffer.isHidden(x0[i], y0[i], z0[i], x1[i], y1[i], z1[i])) {
                words[i >>> 6] &= ~(1L << i);
            }
        }
    }
}
