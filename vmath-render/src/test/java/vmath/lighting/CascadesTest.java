package vmath.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.lighting.Cascades.Cascade;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.camera.Cameraf;

class CascadesTest {

    final Rnd rnd = Rnd.create();

    private Cameraf randomCamera() {
        return new Cameraf(rnd.nextVec3f().mul(5f), rnd.nextUnitQuatf(), (float) rnd.range(0.6, 1.6), (float) rnd.range(1.0, 2.0),
                (float) rnd.range(0.1, 0.5), (float) rnd.range(100, 500), DepthRange.ZERO_TO_ONE);
    }

    private Vec3f randomLight() {
        Vec3f d = rnd.nextVec3f();
        return d.lengthSquared() < 1f ? Vec3f.UNIT_Y.negate() : d;
    }

    /** The eight corners of a camera slice, computed independently of the class under test. */
    private static Vec3f[] sliceCorners(Cameraf c, float near, float far) {
        Vec3f[] out = new Vec3f[8];
        double t = Math.tan(c.fovy() / 2.0);
        for (int i = 0; i < 8; i++) {
            double d = (i & 4) == 0 ? near : far;
            double h = t * d, w = h * c.aspect();
            Vec3f center = c.position().add(c.forward().mul((float) d));
            out[i] = center.add(c.right().mul((float) ((i & 1) == 0 ? -w : w))).add(c.up().mul((float) ((i & 2) == 0 ? -h : h)));
        }
        return out;
    }

    // ------------------------------------------------------------ splits

    @Test
    void splitsAreMonotonicAndHitTheEnds() {
        for (int i = 0; i < 500; i++) {
            float near = (float) rnd.range(0.05, 2), far = near * (float) rnd.range(5, 2000);
            int count = 1 + (int) rnd.range(0, 8);
            float lambda = (float) rnd.range(0, 1);
            float[] d = Cascades.splitDistances(near, far, count, lambda);
            assertEquals(count + 1, d.length);
            assertEquals(near, d[0]);
            assertEquals(far, d[count]);
            for (int k = 1; k <= count; k++) {
                assertTrue(d[k] > d[k - 1], "strictly increasing: " + java.util.Arrays.toString(d));
            }
        }
    }

    @Test
    void lambdaBlendsUniformAndLogarithmicSpacing() {
        float near = 0.5f, far = 500f;
        float[] uniform = Cascades.splitDistances(near, far, 4, 0f);
        float[] log = Cascades.splitDistances(near, far, 4, 1f);
        for (int i = 0; i <= 4; i++) {
            assertEquals(near + (far - near) * i / 4f, uniform[i], 1e-3f * far, "uniform " + i);
            assertEquals(near * Math.pow(far / near, i / 4.0), log[i], 1e-3f * far, "log " + i);
        }
        float[] mid = Cascades.splitDistances(near, far, 4, 0.5f);
        for (int i = 1; i < 4; i++) {
            assertEquals(0.5f * uniform[i] + 0.5f * log[i], mid[i], 1e-3f * far, "blend " + i);
            assertTrue(log[i] < uniform[i], "logarithmic puts split points nearer to the camera");
        }
        assertThrows(IllegalArgumentException.class, () -> Cascades.splitDistances(1f, 10f, 0, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> Cascades.splitDistances(0f, 10f, 3, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> Cascades.splitDistances(10f, 1f, 3, 0.5f));
        assertThrows(IllegalArgumentException.class, () -> Cascades.splitDistances(1f, 10f, 3, 1.5f));
    }

    // ------------------------------------------------------------ fitting

    @Test
    void everyCascadeContainsItsSliceInEveryDepthConvention() {
        for (int i = 0; i < 600; i++) {
            Cameraf cam = randomCamera();
            Vec3f light = randomLight();
            boolean stable = i % 2 == 0;
            DepthRange depth = DepthRange.values()[i % 3];
            float near = cam.near() * (float) rnd.range(1, 3);
            float far = near * (float) rnd.range(1.5, 20);
            float caster = (float) rnd.range(0, 50);
            Cascade c = Cascades.fit(cam, near, far, light, 1024, stable, caster, depth);
            for (Vec3f corner : sliceCorners(cam, near, far)) {
                Vec3f ndc = c.viewProjection().transformProject(corner);
                double slack = 1e-3;
                assertTrue(Math.abs(ndc.x()) <= 1 + slack && Math.abs(ndc.y()) <= 1 + slack,
                        (stable ? "stable" : "tight") + " " + depth + ": corner outside in x/y, ndc " + ndc);
                double zLo = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? -1 : 0;
                assertTrue(ndc.z() >= zLo - slack && ndc.z() <= 1 + slack, depth + ": corner outside in depth, ndc " + ndc);
            }
        }
    }

    @Test
    void casterDistanceExtendsTheVolumeTowardTheLight() {
        Cameraf cam = randomCamera();
        Vec3f light = randomLight();
        Cascade none = Cascades.fit(cam, 1f, 30f, light, 1024, true, 0f, DepthRange.ZERO_TO_ONE);
        Cascade some = Cascades.fit(cam, 1f, 30f, light, 1024, true, 40f, DepthRange.ZERO_TO_ONE);
        // a point beyond the slice's nearest extent toward the light is outside without a caster margin, inside with one
        Vec3f center = cam.position().add(cam.forward().mul(15.5f));
        float centerZ = none.lightView().transformPosition(center).z();
        float towardLight = none.lightBounds().maxZ() - centerZ + 5f; // 5 units beyond the volume's light-side face
        Vec3f toward = center.sub(light.normalize().mul(towardLight));
        double zNone = none.viewProjection().transformProject(toward).z();
        double zSome = some.viewProjection().transformProject(toward).z();
        assertTrue(zNone < 0, "without a caster margin the point is in front of the near plane: " + zNone);
        assertTrue(zSome >= -1e-3 && zSome <= 1, "with the margin it is inside the depth range: " + zSome);
        assertEquals(none.lightBounds().minX(), some.lightBounds().minX(), 0f, "x/y coverage is unaffected");
    }

    @Test
    void stabilizedCascadesDoNotChangeSizeWhenTheCameraMovesOrTurns() {
        for (int i = 0; i < 300; i++) {
            Cameraf cam = randomCamera();
            Vec3f light = randomLight();
            Cascade base = Cascades.fit(cam, 1f, 40f, light, 2048, true, 10f, DepthRange.ZERO_TO_ONE);
            float width = base.lightBounds().maxX() - base.lightBounds().minX();
            Cameraf moved = cam.withPosition(cam.position().add(rnd.nextVec3f().mul(3f)));
            Cameraf turned = cam.withOrientation(rnd.nextUnitQuatf());
            for (Cameraf other : new Cameraf[] {moved, turned}) {
                Cascade c = Cascades.fit(other, 1f, 40f, light, 2048, true, 10f, DepthRange.ZERO_TO_ONE);
                assertEquals(width, c.lightBounds().maxX() - c.lightBounds().minX(), 1e-4f * width, "width is invariant");
                assertEquals(width, c.lightBounds().maxY() - c.lightBounds().minY(), 1e-4f * width, "height is invariant");
                assertEquals(base.texelSize(), c.texelSize(), 1e-6f * base.texelSize());
            }
        }
    }

    @Test
    void stabilizedOriginSnapsToWholeTexels() {
        for (int i = 0; i < 500; i++) {
            Cameraf cam = randomCamera();
            Cascade c = Cascades.fit(cam, 1f, 40f, randomLight(), 1024, true, 5f, DepthRange.ZERO_TO_ONE);
            double texel = c.texelSize();
            double qx = c.lightBounds().minX() / texel, qy = c.lightBounds().minY() / texel;
            assertEquals(Math.rint(qx), qx, 2e-3 * Math.max(1, Math.abs(qx)) / 100 + 1e-3, "min x is a whole number of texels: " + qx);
            assertEquals(Math.rint(qy), qy, 2e-3 * Math.max(1, Math.abs(qy)) / 100 + 1e-3, "min y is a whole number of texels: " + qy);
        }
    }

    @Test
    void movingTheCameraLessThanATexelShiftsTheMapByWholeTexelsOnly() {
        // the property that removes shimmering: as the camera creeps along a light-space axis, the map's origin advances in
        // whole texels, never fractions, so a static shadow edge always falls on the same texel boundaries
        Cameraf cam = new Cameraf(new Vec3f(3f, 2f, 1f), Quatf.IDENTITY, 1f, 1.5f, 0.2f, 200f, DepthRange.ZERO_TO_ONE);
        Vec3f light = new Vec3f(0.3f, -1f, 0.2f);
        Cascade base = Cascades.fit(cam, 1f, 60f, light, 1024, true, 0f, DepthRange.ZERO_TO_ONE);
        float texel = base.texelSize();
        Vec3f lightRight = base.lightView().transformDirection(Vec3f.UNIT_X); // light-space x axis seen from world
        Vec3f worldAlongLightX = base.lightView().transpose().transformDirection(Vec3f.UNIT_X);
        for (int step = 1; step <= 40; step++) {
            Cameraf moved = cam.withPosition(cam.position().add(worldAlongLightX.mul(step * texel * 0.13f)));
            Cascade c = Cascades.fit(moved, 1f, 60f, light, 1024, true, 0f, DepthRange.ZERO_TO_ONE);
            double shift = (c.lightBounds().minX() - base.lightBounds().minX()) / texel;
            assertEquals(Math.rint(shift), shift, 2e-2, "shift after " + step + " steps is a whole number of texels: " + shift);
            assertEquals(base.lightBounds().minY(), c.lightBounds().minY(), 1e-3f * texel * 1024 / 100 + 2e-2f * texel,
                    "no drift in the other axis");
        }
        assertTrue(lightRight.length() > 0.99f);
    }

    @Test
    void tightFitIsNoLargerThanTheStabilizedFit() {
        for (int i = 0; i < 300; i++) {
            Cameraf cam = randomCamera();
            Vec3f light = randomLight();
            Cascade tight = Cascades.fit(cam, 1f, 30f, light, 1024, false, 0f, DepthRange.ZERO_TO_ONE);
            Cascade stable = Cascades.fit(cam, 1f, 30f, light, 1024, true, 0f, DepthRange.ZERO_TO_ONE);
            float tightArea = (tight.lightBounds().maxX() - tight.lightBounds().minX()) * (tight.lightBounds().maxY() - tight.lightBounds().minY());
            float stableArea = (stable.lightBounds().maxX() - stable.lightBounds().minX()) * (stable.lightBounds().maxY() - stable.lightBounds().minY());
            assertTrue(tightArea <= stableArea * 1.001f, "tight " + tightArea + " vs stable " + stableArea);
        }
    }

    // ------------------------------------------------------------ frustum and texture matrix

    @Test
    void cascadeFrustumContainsTheSliceAndTextureMatrixMapsIntoTheUnitCube() {
        for (int i = 0; i < 300; i++) {
            Cameraf cam = randomCamera();
            DepthRange depth = DepthRange.values()[i % 3];
            Cascade c = Cascades.fit(cam, 1f, 25f, randomLight(), 1024, true, 20f, depth);
            Vec3f center = cam.position().add(cam.forward().mul(13f));
            assertTrue(c.frustum().contains(center), "the slice centre is inside the cascade's culling frustum");
            for (Vec3f corner : sliceCorners(cam, 1f, 25f)) {
                Vec3f p = corner.add(center.sub(corner).mul(0.001f)); // nudge inward to stay off the boundary
                Vec4f uvz = c.textureMatrix().transform(Vec4f.point(p));
                double w = uvz.w();
                assertEquals(1.0, w, 1e-4, "orthographic: w stays 1");
                assertTrue(uvz.x() >= -1e-3 && uvz.x() <= 1 + 1e-3 && uvz.y() >= -1e-3 && uvz.y() <= 1 + 1e-3,
                        depth + ": uv outside [0, 1]: " + uvz);
                assertTrue(uvz.z() >= -1e-3 && uvz.z() <= 1 + 1e-3, depth + ": depth outside [0, 1]: " + uvz);
            }
        }
    }

    @Test
    void reversedShadowDepthMapsTheLightSideNearPlaneToOne() {
        Cameraf cam = randomCamera();
        Vec3f light = new Vec3f(0f, -1f, 0.1f);
        Cascade fwd = Cascades.fit(cam, 1f, 25f, light, 512, true, 30f, DepthRange.ZERO_TO_ONE);
        Cascade rev = Cascades.fit(cam, 1f, 25f, light, 512, true, 30f, DepthRange.REVERSED_ZERO_TO_ONE);
        Vec3f p = cam.position().add(cam.forward().mul(10f));
        double zf = fwd.viewProjection().transformProject(p).z();
        double zr = rev.viewProjection().transformProject(p).z();
        assertEquals(1.0, zf + zr, 1e-4, "reversed depth is exactly one minus the forward depth");
    }

    @Test
    void fitAllCoversTheRangeWithContiguousSlices() {
        Cameraf cam = randomCamera();
        List<Cascade> cascades = Cascades.fitAll(cam, 4, 0.7f, 150f, randomLight(), 1024, true, 10f, DepthRange.ZERO_TO_ONE);
        assertEquals(4, cascades.size());
        assertEquals(cam.near(), cascades.get(0).sliceNear());
        assertEquals(Math.min(150f, cam.far()), cascades.get(3).sliceFar(), "the last slice ends at the shadow distance or the far plane");
        for (int i = 1; i < 4; i++) {
            assertEquals(cascades.get(i - 1).sliceFar(), cascades.get(i).sliceNear(), "slices touch");
            assertTrue(cascades.get(i).texelSize() > cascades.get(i - 1).texelSize(), "farther cascades are coarser");
        }
        // a finite shadow distance beyond the camera's far plane is clamped to it
        Cameraf shortRange = new Cameraf(cam.position(), cam.orientation(), cam.fovy(), cam.aspect(), 0.3f, 50f, DepthRange.ZERO_TO_ONE);
        assertEquals(50f, Cascades.fitAll(shortRange, 2, 0.5f, 1000f, randomLight(), 512, true, 0f, DepthRange.ZERO_TO_ONE).get(1).sliceFar());
    }

    @Test
    void rejectsInvalidSlices() {
        Cameraf cam = randomCamera();
        Vec3f l = Vec3f.UNIT_Y.negate();
        assertThrows(IllegalArgumentException.class, () -> Cascades.fit(cam, 0f, 10f, l, 512, true, 0f, DepthRange.ZERO_TO_ONE));
        assertThrows(IllegalArgumentException.class, () -> Cascades.fit(cam, 5f, 5f, l, 512, true, 0f, DepthRange.ZERO_TO_ONE));
        assertThrows(IllegalArgumentException.class, () -> Cascades.fit(cam, 1f, 5f, l, 0, true, 0f, DepthRange.ZERO_TO_ONE));
        assertThrows(IllegalArgumentException.class, () -> Cascades.fit(cam, 1f, 5f, l, 512, true, -1f, DepthRange.ZERO_TO_ONE));
    }

    @Test
    void straightDownLightDoesNotBreakTheBasis() {
        Cameraf cam = randomCamera();
        for (Vec3f down : new Vec3f[] {Vec3f.UNIT_Y.negate(), Vec3f.UNIT_Y, new Vec3f(0f, -1f, 1e-4f)}) {
            Cascade c = Cascades.fit(cam, 1f, 30f, down, 1024, true, 10f, DepthRange.ZERO_TO_ONE);
            assertTrue(c.viewProjection().isFinite(), "finite matrices for light direction " + down);
            Mat4f v = c.lightView();
            assertEquals(1.0, v.determinant(), 1e-3, "light view is a proper rotation");
        }
    }
}
