package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

/**
 * The size-based stages in an orthographic context: the projected size of an object does not depend
 * on its distance from the camera, so the small-feature stage and the level-of-detail selector
 * must not use the distance (CAM-12).
 */
class OrthoCullTest {

    private static final int VIEWPORT = 1000;
    private static final float VIEW_HEIGHT = 100f;       // 10 pixels per world unit

    private static CullContext ortho() {
        Frustumf f = Frustumf.fromViewProjection(Mat4f.ortho(-100f, 100f, -50f, 50f, 0.1f, 1e4f, ClipSpace.D3D), DepthRange.ZERO_TO_ONE);
        return CullContext.orthographic(f, Vec3f.ZERO, VIEWPORT, VIEW_HEIGHT);
    }

    /** A box of half-extent 0.5 centred at depth {@code distance}. */
    private static BoundsArray boxes(float... distances) {
        BoundsArray b = new BoundsArray(distances.length);
        for (float d : distances) {
            b.add(-0.5f, -0.5f, -d - 0.5f, 0.5f, 0.5f, -d + 0.5f);
        }
        return b;
    }

    private static VisibilitySet all(int n) {
        VisibilitySet v = new VisibilitySet(Math.max(n, 1));
        v.setAll(n);
        return v;
    }

    @Test
    void theContextHasThePixelsPerUnit() {
        CullContext c = ortho();
        assertTrue(c.orthographic());
        assertEquals(10f, c.pixelScale(), 1e-6f);
        assertFalse(new CullContext(c.frustum(), c.camera(), 5f).orthographic(), "the three-argument form is a perspective context");
        assertThrows(IllegalArgumentException.class, () -> CullContext.orthographic(c.frustum(), c.camera(), 0, 10f));
        assertThrows(IllegalArgumentException.class, () -> CullContext.orthographic(c.frustum(), c.camera(), 100, 0f));
    }

    @Test
    void smallFeatureIgnoresTheDistance() {
        // the sphere of a box of half-extent 0.5 has the radius 0.866: 8.7 pixels, at any distance
        BoundsArray b = boxes(1f, 10f, 100f, 1000f, 5000f);
        VisibilitySet near = all(5), far = all(5);
        new CullStages.SmallFeature(8f).cull(ortho(), b, near);
        assertEquals(5, near.count(), "8.7 pixels are above the limit of 8 everywhere");
        new CullStages.SmallFeature(9f).cull(ortho(), b, far);
        assertEquals(0, far.count(), "and below the limit of 9 everywhere");
        // the perspective stage would have culled the far ones with the same numbers
        VisibilitySet perspective = all(5);
        new CullStages.SmallFeature(8f).cull(new CullContext(ortho().frustum(), Vec3f.ZERO, 10f), b, perspective);
        assertTrue(perspective.count() < 5, "the perspective formula depends on the distance");
    }

    @Test
    void levelOfDetailFollowsTheSizeOnTheScreenNotTheDistance() {
        LodSelector lod = new LodSelector(new float[] {100f, 30f}, 0f, 0f, 0f);
        BoundsArray b = boxes(1f, 50f, 3000f);
        VisibilitySet v = all(3);
        byte[] levels = new byte[3];
        Arrays.fill(levels, LodSelector.NO_LEVEL);
        lod.select(ortho(), b, v, levels, null, 1f);
        // 2 * 0.866 * 10 = 17.3 pixels: below both thresholds, the last level for every object
        for (int i = 0; i < 3; i++) {
            assertEquals(2, levels[i], "object " + i + " has the same level at any distance");
        }
        // twice the zoom doubles the size: 34.6 pixels, level 1
        CullContext zoomed = CullContext.orthographic(ortho().frustum(), Vec3f.ZERO, VIEWPORT, VIEW_HEIGHT / 2f);
        Arrays.fill(levels, LodSelector.NO_LEVEL);
        lod.select(zoomed, b, v, levels, null, 1f);
        for (int i = 0; i < 3; i++) {
            assertEquals(1, levels[i], "zoomed in");
        }
        // the bias still multiplies
        Arrays.fill(levels, LodSelector.NO_LEVEL);
        lod.select(ortho(), b, v, levels, null, 10f);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, levels[i], "a bias of 10 gives 173 pixels");
        }
    }
}
