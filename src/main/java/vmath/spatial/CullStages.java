package vmath.spatial;

import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/** The built-in {@link CullStage}s. */
public final class CullStages {

    private CullStages() {
    }

    /**
     * Frustum culling with a batch {@link FrustumKernel}. The default constructor takes the best kernel available
     * ({@link FrustumKernels#best()}: SIMD when the {@code vmath-simd} module is present, otherwise scalar). The kernel owns
     * scratch memory, so give each thread its own stage.
     */
    public static final class Frustum implements CullStage {

        private final FrustumKernel kernel;

        public Frustum() {
            this(FrustumKernels.best());
        }

        public Frustum(FrustumKernel kernel) {
            this.kernel = kernel;
        }

        @Override
        public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
            kernel.cull(ctx.frustum(), bounds, visible);
        }
    }

    /** Rejects objects whose bounds are entirely farther than {@code maxDistance} from the camera. */
    public static final class Distance implements CullStage {

        private final float maxDistanceSquared;

        public Distance(float maxDistance) {
            this.maxDistanceSquared = maxDistance * maxDistance;
        }

        @Override
        public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
            float cx = ctx.camera().x(), cy = ctx.camera().y(), cz = ctx.camera().z();
            float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
            float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
            long[] words = visible.words();
            for (int i = visible.nextSetBit(0); i >= 0 && i < bounds.size(); i = visible.nextSetBit(i + 1)) {
                float dx = Math.max(Math.max(x0[i] - cx, 0f), cx - x1[i]);
                float dy = Math.max(Math.max(y0[i] - cy, 0f), cy - y1[i]);
                float dz = Math.max(Math.max(z0[i] - cz, 0f), cz - z1[i]);
                if (dx * dx + dy * dy + dz * dz > maxDistanceSquared) {
                    words[i >>> 6] &= ~(1L << i);
                }
            }
        }
    }

    /**
     * Rejects objects that would cover fewer than {@code minPixels} pixels of screen height: the bounding sphere's
     * projected size, {@code radius * pixelScale / distance}, is a conservative-enough proxy and needs no matrices.
     * Does nothing when the context's {@code pixelScale} is zero.
     */
    public static final class SmallFeature implements CullStage {

        private final float minPixels;

        public SmallFeature(float minPixels) {
            this.minPixels = minPixels;
        }

        @Override
        public void cull(CullContext ctx, BoundsArray bounds, VisibilitySet visible) {
            float scale = ctx.pixelScale();
            if (scale <= 0f) {
                return;
            }
            float cx = ctx.camera().x(), cy = ctx.camera().y(), cz = ctx.camera().z();
            float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
            float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
            long[] words = visible.words();
            for (int i = visible.nextSetBit(0); i >= 0 && i < bounds.size(); i = visible.nextSetBit(i + 1)) {
                float hx = (x1[i] - x0[i]) * 0.5f, hy = (y1[i] - y0[i]) * 0.5f, hz = (z1[i] - z0[i]) * 0.5f;
                float dx = (x0[i] + x1[i]) * 0.5f - cx, dy = (y0[i] + y1[i]) * 0.5f - cy, dz = (z0[i] + z1[i]) * 0.5f - cz;
                float radius2 = hx * hx + hy * hy + hz * hz;
                float dist2 = dx * dx + dy * dy + dz * dz;
                // radius * scale / dist < minPixels, compared without a square root
                if (radius2 * scale * scale < minPixels * minPixels * dist2) {
                    words[i >>> 6] &= ~(1L << i);
                }
            }
        }
    }
}
