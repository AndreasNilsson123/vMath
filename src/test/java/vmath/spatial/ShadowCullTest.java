package vmath.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.camera.Cascades;
import vmath.camera.Cascades.Cascade;
import vmath.camera.CubeFaces;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

/** Cascade caster culling, point and spot light volumes, and cube-face selection. */
class ShadowCullTest {

    final Rnd rnd = Rnd.create();

    private static BoundsArray one(Aabbf b) {
        BoundsArray a = new BoundsArray(1);
        a.add(b);
        return a;
    }

    private static VisibilitySet all(int n) {
        VisibilitySet v = new VisibilitySet(Math.max(n, 1));
        v.setAll(n);
        return v;
    }

    private Aabbf tinyBoxAround(Vec3f p) {
        return Aabbf.fromCenterHalfExtent(p, Vec3f.splat(0.01f));
    }

    // ------------------------------------------------------------ cube faces

    @Test
    void faceOrientationsAreTheGlAndVulkanOnes() {
        assertEquals(new Vec3f(1f, 0f, 0f), CubeFaces.direction(CubeFaces.POSITIVE_X));
        assertEquals(new Vec3f(0f, 0f, -1f), CubeFaces.direction(CubeFaces.NEGATIVE_Z));
        assertEquals(new Vec3f(0f, 0f, 1f), CubeFaces.up(CubeFaces.POSITIVE_Y));
        assertEquals(new Vec3f(0f, -1f, 0f), CubeFaces.up(CubeFaces.NEGATIVE_X));
        assertThrows(IllegalArgumentException.class, () -> CubeFaces.direction(6));
        assertEquals(CubeFaces.NEGATIVE_Y, CubeFaces.faceOf(new Vec3f(0.2f, -3f, 1f)));
        assertEquals(CubeFaces.POSITIVE_X, CubeFaces.faceOf(new Vec3f(2f, 1.9f, -1.9f)));
        for (int f = 0; f < CubeFaces.COUNT; f++) {
            assertEquals(f, CubeFaces.faceOf(CubeFaces.direction(f)));
        }
    }

    @Test
    void everyDirectionLandsInItsFacesFrustum() {
        for (DepthRange depth : DepthRange.values()) {
            for (int trial = 0; trial < 300; trial++) {
                Vec3f light = rnd.nextVec3f().mul(10f);
                Vec3f dir = rnd.nextVec3f().normalize();
                Vec3f p = light.add(dir.mul((float) rnd.range(0.5, 50)));
                int face = CubeFaces.faceOf(dir);
                Frustumf f = CubeFaces.frustum(face, light, 0.1f, 200f, depth);
                assertTrue(f.intersects(tinyBoxAround(p)), "direction " + dir + " missed face " + face + " for " + depth);
            }
        }
    }

    @Test
    void faceMaskNeverMissesAFaceTheFrustumTouches() {
        int total = 0;
        int boxes = 0;
        for (int trial = 0; trial < 1500; trial++) {
            Vec3f light = rnd.nextVec3f().mul(5f);
            Vec3f c = light.add(rnd.nextVec3f().mul((float) rnd.range(0.5, 25)));
            Vec3f h = new Vec3f((float) rnd.range(0.05, 3), (float) rnd.range(0.05, 3), (float) rnd.range(0.05, 3));
            Aabbf box = Aabbf.fromCenterHalfExtent(c, h);
            int mask = LightCull.faceMask(box.minX() - light.x(), box.minY() - light.y(), box.minZ() - light.z(),
                    box.maxX() - light.x(), box.maxY() - light.y(), box.maxZ() - light.z());
            for (int f = 0; f < 6; f++) {
                Frustumf fr = CubeFaces.frustum(f, light, 0.001f, 1000f, DepthRange.ZERO_TO_ONE);
                if (fr.intersects(box)) {
                    assertTrue((mask & (1 << f)) != 0, "face " + f + " touches the box but the mask is " + mask);
                }
            }
            total += Integer.bitCount(mask);
            boxes++;
        }
        assertTrue(total / (double) boxes < 2.2, "boxes should mostly land in one or two faces, average " + total / (double) boxes);
    }

    @Test
    void cubeFacesClearsObjectsOutOfRangeAndRecordsMasks() {
        BoundsArray b = new BoundsArray(3);
        b.add(Aabbf.of(new Vec3f(4f, -0.5f, -0.5f), new Vec3f(5f, 0.5f, 0.5f)));        // straight along +X
        b.add(Aabbf.of(new Vec3f(-0.5f, 90f, -0.5f), new Vec3f(0.5f, 91f, 0.5f)));      // far away
        b.add(Aabbf.of(new Vec3f(4f, 3.5f, -0.5f), new Vec3f(5f, 4.5f, 0.5f)));         // near the +X / +Y boundary
        VisibilitySet vis = all(3);
        byte[] masks = new byte[3];
        assertEquals(1, LightCull.cubeFaces(b, vis, 0f, 0f, 0f, 20f, masks));
        assertTrue(vis.get(0));
        assertFalse(vis.get(1));
        assertTrue(vis.get(2));
        assertEquals(1, masks[0], "only the +X face");
        assertTrue((masks[2] & 1) != 0 && (masks[2] & 4) != 0, "straddles +X and +Y, mask " + masks[2]);
        assertThrows(IllegalArgumentException.class, () -> LightCull.cubeFaces(b, vis, 0f, 0f, 0f, 20f, new byte[1]));
    }

    @Test
    void aLightInsideABoxTouchesEveryFaceAndNaNBoundsAreKept() {
        assertEquals(63, LightCull.faceMask(-1f, -1f, -1f, 1f, 1f, 1f));
        BoundsArray b = new BoundsArray(1);
        b.add(Float.NaN, 0f, 0f, 1f, 1f, 1f);
        VisibilitySet vis = all(1);
        byte[] masks = new byte[1];
        assertEquals(0, LightCull.cubeFaces(b, vis, 0f, 0f, 0f, 5f, masks));
        assertTrue(vis.get(0));
        assertEquals(63, masks[0]);
    }

    // ------------------------------------------------------------ point and spot lights

    @Test
    void pointLightMatchesTheDistanceOracle() {
        for (int trial = 0; trial < 50; trial++) {
            int n = 300;
            BoundsArray b = new BoundsArray(n);
            Aabbf[] boxes = new Aabbf[n];
            for (int i = 0; i < n; i++) {
                boxes[i] = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(20f),
                        new Vec3f((float) rnd.range(0.05, 2), (float) rnd.range(0.05, 2), (float) rnd.range(0.05, 2)));
                b.add(boxes[i]);
            }
            Vec3f light = rnd.nextVec3f().mul(15f);
            float range = (float) rnd.range(1, 25);
            VisibilitySet vis = all(n);
            int cleared = LightCull.pointLight(b, vis, light.x(), light.y(), light.z(), range);
            int expectedCleared = 0;
            for (int i = 0; i < n; i++) {
                boolean in = NearestTest.dist2(boxes[i], light.x(), light.y(), light.z()) <= range * range;
                assertEquals(in, vis.get(i), "object " + i);
                if (!in) {
                    expectedCleared++;
                }
            }
            assertEquals(expectedCleared, cleared);
        }
    }

    @Test
    void spotLightNeverRemovesAnObjectInsideItsCone() {
        int culled = 0;
        for (int trial = 0; trial < 300; trial++) {
            Vec3f apex = rnd.nextVec3f().mul(10f);
            Vec3f axis = rnd.nextVec3f().normalize();
            float half = (float) rnd.range(0.1, 1.4);
            float range = (float) rnd.range(2, 40);
            // a point inside the cone: rotate the axis by less than the half-angle toward a random perpendicular
            Vec3f helper = Math.abs(axis.x()) < 0.9f ? Vec3f.UNIT_X : Vec3f.UNIT_Y;
            Vec3f u = axis.cross(helper).normalize();
            Vec3f v = axis.cross(u);
            float phi = (float) rnd.range(0, 2 * Math.PI);
            float theta = (float) (rnd.range(0, 1) * half * 0.999);
            Vec3f dir = axis.mul((float) Math.cos(theta))
                    .add(u.mul((float) (Math.sin(theta) * Math.cos(phi)))).add(v.mul((float) (Math.sin(theta) * Math.sin(phi))));
            Vec3f inside = apex.add(dir.mul((float) rnd.range(0.01, 1) * range));
            BoundsArray b = one(Aabbf.fromCenterHalfExtent(inside, Vec3f.splat((float) rnd.range(0.005, 1))));
            VisibilitySet vis = all(1);
            LightCull.spotLight(b, vis, apex.x(), apex.y(), apex.z(), axis.x() * 3f, axis.y() * 3f, axis.z() * 3f, half, range);
            assertTrue(vis.get(0), "an object inside the cone was culled (trial " + trial + ")");

            // and one clearly behind the light is removed
            Vec3f behind = apex.sub(axis.mul(5f + range));
            BoundsArray bb = one(Aabbf.fromCenterHalfExtent(behind, Vec3f.splat(0.5f)));
            VisibilitySet vv = all(1);
            culled += LightCull.spotLight(bb, vv, apex.x(), apex.y(), apex.z(), axis.x(), axis.y(), axis.z(), half, range);
        }
        assertEquals(300, culled, "objects behind the light are always removed");
    }

    @Test
    void spotLightRemovesObjectsBeyondRangeAndOutsideTheCone() {
        BoundsArray b = new BoundsArray(3);
        b.add(Aabbf.fromCenterHalfExtent(new Vec3f(0f, 0f, -30f), Vec3f.splat(0.5f)));  // on the axis, beyond range 10
        b.add(Aabbf.fromCenterHalfExtent(new Vec3f(20f, 0f, -5f), Vec3f.splat(0.5f)));  // way off to the side
        b.add(Aabbf.fromCenterHalfExtent(new Vec3f(0.5f, 0f, -5f), Vec3f.splat(0.5f))); // inside
        VisibilitySet vis = all(3);
        assertEquals(2, LightCull.spotLight(b, vis, 0f, 0f, 0f, 0f, 0f, -1f, 0.5f, 10f));
        assertFalse(vis.get(0));
        assertFalse(vis.get(1));
        assertTrue(vis.get(2));
        assertThrows(IllegalArgumentException.class, () -> LightCull.spotLight(b, vis, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 10f));
        assertThrows(IllegalArgumentException.class, () -> LightCull.spotLight(b, vis, 0f, 0f, 0f, 0f, 0f, -1f, 1.6f, 10f));
        assertThrows(IllegalArgumentException.class, () -> LightCull.spotLight(b, vis, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 10f));
    }

    // ------------------------------------------------------------ cascades

    private Cameraf randomCamera() {
        Vec3f eye = rnd.nextVec3f().mul(5f);
        Vec3f target = eye.add(rnd.nextVec3f().normalize().mul(10f));
        return Cameraf.lookingAt(eye, target, Vec3f.UNIT_Y, (float) rnd.range(0.6, 1.4), (float) rnd.range(1.0, 2.0), 0.3f, 300f,
                DepthRange.ZERO_TO_ONE);
    }

    @Test
    void casterCullingNeverRemovesAnObjectThatShadowsTheSlice() {
        int culledSomething = 0;
        for (int trial = 0; trial < 40; trial++) {
            Cameraf cam = randomCamera();
            Vec3f lightDir = rnd.nextVec3f().normalize();
            boolean stabilize = trial % 2 == 0;
            List<Cascade> cascades = Cascades.fitAll(cam, 3, 0.7f, 120f, lightDir, 1024, stabilize, 200f, DepthRange.ZERO_TO_ONE);
            for (Cascade c : cascades) {
                CascadeCasters stage = new CascadeCasters(cam, c, 0.05f);
                float th = (float) Math.tan(cam.fovy() * 0.5f);
                for (int s = 0; s < 60; s++) {
                    // a point P inside the slice, and a caster Q on the ray from P toward the light
                    float d = (float) rnd.range(c.sliceNear(), c.sliceFar());
                    float x = (float) rnd.range(-1, 1) * th * d * cam.aspect(), y = (float) rnd.range(-1, 1) * th * d;
                    Vec3f p = cam.position().add(cam.forward().mul(d)).add(cam.right().mul(x)).add(cam.up().mul(y));
                    Vec3f q = p.sub(lightDir.mul((float) rnd.range(0, 150)));
                    BoundsArray b = one(tinyBoxAround(q));
                    VisibilitySet vis = all(1);
                    stage.cull(null, b, vis);
                    assertTrue(vis.get(0), "a caster on a shadow ray into the slice was culled (trial " + trial + ")");
                }
                // objects far off to the side of the slice footprint, or entirely behind it, go
                BoundsArray far = new BoundsArray(2);
                Vec3f mid = cam.position().add(cam.forward().mul((c.sliceNear() + c.sliceFar()) * 0.5f));
                Vec3f side = lightDir.cross(Vec3f.UNIT_Y).length() > 0.1f ? lightDir.cross(Vec3f.UNIT_Y).normalize()
                        : lightDir.cross(Vec3f.UNIT_X).normalize();
                far.add(Aabbf.fromCenterHalfExtent(mid.add(side.mul(2000f)), Vec3f.splat(1f)));
                far.add(Aabbf.fromCenterHalfExtent(mid.add(lightDir.mul(2000f)), Vec3f.splat(1f))); // far downstream of the slice
                VisibilitySet vis = all(2);
                stage.cull(null, far, vis);
                assertFalse(vis.get(0), "an object far off to the side cannot shadow the slice");
                assertFalse(vis.get(1), "an object entirely beyond the slice (away from the light) cannot shadow it");
                culledSomething += 2 - vis.count();
            }
        }
        assertEquals(40 * 3 * 2, culledSomething);
    }

    @Test
    void casterCullingIsTighterThanTheCascadeVolume() {
        Cameraf cam = Cameraf.lookingAt(Vec3f.ZERO, new Vec3f(0f, 0f, -1f), Vec3f.UNIT_Y, 1f, 1.5f, 0.3f, 300f, DepthRange.ZERO_TO_ONE);
        Vec3f lightDir = new Vec3f(0.3f, -1f, -0.2f).normalize();
        Cascade c = Cascades.fitAll(cam, 3, 0.7f, 120f, lightDir, 1024, true, 200f, DepthRange.ZERO_TO_ONE).get(1);
        CascadeCasters tight = new CascadeCasters(cam, c, 0f);
        Frustumf volume = c.frustum();
        int inVolume = 0;
        int kept = 0;
        int n = 30000;
        BoundsArray b = new BoundsArray(n);
        for (int i = 0; i < n; i++) {
            b.add(Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(15f), Vec3f.splat((float) rnd.range(0.1, 0.6))));
            if (volume.intersects(b.get(i))) {
                inVolume++;
            }
        }
        VisibilitySet vis = all(n);
        tight.cull(null, b, vis);
        for (int i = 0; i < n; i++) {
            if (vis.get(i)) {
                kept++;
            }
        }
        // in this scene every object is well inside the caster distance, so the only difference is the footprint: the map's box
        // is fitted around the slice's bounding sphere, the tight test around the slice itself
        assertTrue(kept < inVolume * 0.7, "the tight test should keep clearly fewer than the map's volume: " + kept + " vs " + inVolume);
    }

    @Test
    void badCascadeCasterArgumentsAreRejected() {
        Cameraf cam = randomCamera();
        Cascade c = Cascades.fit(cam, 0.3f, 10f, new Vec3f(0f, -1f, 0f), 512, true, 50f, DepthRange.ZERO_TO_ONE);
        assertThrows(IllegalArgumentException.class, () -> new CascadeCasters(cam, c, -1f));
    }
}
