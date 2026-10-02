package vmath.mesh;

import java.util.ArrayList;
import java.util.List;
import vmath.annotations.Experimental;

/**
 * Discrete level-of-detail chains: a mesh simplified step by step into coarser copies, with the object-space error of each level and the helpers that turn
 * those errors into the screen-size thresholds {@link vmath.spatial.LodSelector} works with.
 *
 * <p><b>The error of a level</b> is the running sum of the {@link MeshSimplifier.Result#error() estimates} of the steps that produced it, in world units.
 * The estimate is conservative in the measurements in {@code docs/MESH.md} (above the measured distance in both directions by a factor of 3 to 12), so a level
 * chosen by it looks at least as good as the threshold asks for, and the chain errs on the side of more detail. It is not a Hausdorff distance.
 *
 * <p><b>Pixel error.</b> A world-space error {@code e} at distance {@code d} covers {@code e * viewportHeight / (2 * d * tan(fovY / 2))} pixels. With a bounding
 * sphere of radius {@code r} that is {@code e * size / (2 r)} where {@code size} is the on-screen diameter of the sphere in pixels (what {@code LodSelector}
 * measures), so level {@code i} is acceptable for {@code size <= 2 r T / e_i} with pixel budget {@code T}; {@link Chain#selectorThresholds} returns those.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads freely. The arrays it hands out are its own storage: do
 * not modify them.
 */
@Experimental("the error model and the chain builder may change")
public final class MeshLod {

    private MeshLod() {
    }

    /**
     * A chain: {@code levels[0]} is a copy of the source, each next level is coarser.
     *
     * @param levels the meshes, finest first
     * @param errors world-space error of each level (0 for level 0), strictly increasing
     */
    public record Chain(Mesh[] levels, float[] errors) {

        public int count() {
            return levels.length;
        }

        /** Pixels covered by the error of {@code level} at {@code distance} for a vertical field of view {@code fovY} (radians) and a viewport {@code viewportHeight} pixels high. */
        public float pixelError(int level, float distance, float fovY, float viewportHeight) {
            return errors[level] * viewportHeight / (2f * distance * (float) Math.tan(fovY * 0.5f));
        }

        /** The coarsest level whose pixel error at {@code distance} is within {@code pixelBudget}; level 0 is always allowed. */
        public int levelFor(float distance, float fovY, float viewportHeight, float pixelBudget) {
            int best = 0;
            for (int i = 1; i < levels.length; i++) {
                if (pixelError(i, distance, fovY, viewportHeight) <= pixelBudget) {
                    best = i;
                }
            }
            return best;
        }

        /**
         * The thresholds for {@link vmath.spatial.LodSelector#of}: {@code thresholds[i]} is the size in pixels of the bounding sphere below which level
         * {@code i + 1} is within {@code pixelBudget}, that is {@code 2 * boundingRadius * pixelBudget / errors[i + 1]}. Strictly descending because the errors
         * strictly increase.
         */
        public float[] selectorThresholds(float boundingRadius, float pixelBudget) {
            float[] t = new float[levels.length - 1];
            for (int i = 0; i < t.length; i++) {
                t[i] = 2f * boundingRadius * pixelBudget / errors[i + 1];
            }
            return t;
        }
    }

    /**
     * Builds up to {@code levels} levels, each about {@code ratio} times the triangles of the one before (for example 0.5), by {@link MeshSimplifier}; the
     * source mesh is not changed. The chain ends early, with fewer levels, when a step cannot reduce the triangle count by at least 5% or would go below
     * {@code minTriangles}.
     */
    public static Chain build(Mesh source, int levels, float ratio, int minTriangles, boolean lockBorder) {
        if (levels < 1 || !(ratio > 0f && ratio < 1f)) {
            throw new IllegalArgumentException("levels must be at least 1 and ratio in (0, 1): " + levels + ", " + ratio);
        }
        List<Mesh> meshes = new ArrayList<>();
        List<Float> errors = new ArrayList<>();
        Mesh current = source.copy();
        meshes.add(current);
        errors.add(0f);
        float total = 0f;
        for (int l = 1; l < levels; l++) {
            int target = Math.max(minTriangles, (int) (current.triangleCount() * ratio));
            if (target >= current.triangleCount() * 0.95f) {
                break;
            }
            Mesh next = current.copy();
            MeshSimplifier.Result r = MeshSimplifier.simplify(next, target, Float.MAX_VALUE, lockBorder);
            if (r.trianglesAfter() > current.triangleCount() * 0.95f || !(r.error() > 0f)) {
                break;
            }
            total += r.error();
            meshes.add(next);
            errors.add(total);
            current = next;
        }
        float[] e = new float[errors.size()];
        for (int i = 0; i < e.length; i++) {
            e[i] = errors.get(i);
        }
        return new Chain(meshes.toArray(new Mesh[0]), e);
    }
}
