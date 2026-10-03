package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.Planef;

class StereoAndParaboloidTest {

    private static final long SEED = Long.getLong("vmath.seed", 11L);

    private static Vec3f ndc(Mat4f viewProjection, Vec3f p) {
        Vec4f c = viewProjection.transform(new Vec4f(p.x(), p.y(), p.z(), 1f));
        return new Vec3f(c.x() / c.w(), c.y() / c.w(), c.z() / c.w());
    }

    @Test
    void symmetricAnglesGiveTheOrdinaryPerspective() {
        float fovy = 1.0f, aspect = 1.5f;
        float tanY = (float) Math.tan(fovy / 2), tanX = tanY * aspect;
        for (ClipSpace space : ClipSpace.values()) {
            Mat4f a = Stereo.projection(-(float) Math.atan(tanX), (float) Math.atan(tanX), (float) Math.atan(tanY), -(float) Math.atan(tanY), 0.1f, 100f, space);
            assertTrue(a.approxEquals(Mat4f.perspective(fovy, aspect, 0.1f, 100f, space), 1e-5f), space.toString());
        }
    }

    @Test
    void eyesAreSeparatedByTheInterpupillaryDistance() {
        Mat4f head = Mat4f.lookAt(new Vec3f(1f, 2f, 3f), new Vec3f(1f, 2f, -5f), new Vec3f(0f, 1f, 0f));
        float ipd = 0.064f;
        Mat4f left = Stereo.eyeView(head, ipd, Stereo.LEFT), right = Stereo.eyeView(head, ipd, Stereo.RIGHT);
        // the eye positions in head space are the origins of the eye views
        Vec3f l = left.invert().transformPosition(new Vec3f(0f, 0f, 0f)), r = right.invert().transformPosition(new Vec3f(0f, 0f, 0f));
        Vec3f h = head.invert().transformPosition(new Vec3f(0f, 0f, 0f));
        // head looks along -z with +x to the right in the world here, so the eyes are at x = h.x -/+ ipd / 2
        assertEquals(h.x() - ipd / 2, l.x(), 1e-5f);
        assertEquals(h.x() + ipd / 2, r.x(), 1e-5f);
        assertEquals(h.y(), l.y(), 1e-6f);
        assertEquals(h.z(), r.z(), 1e-6f);
        assertEquals(-ipd / 2, Stereo.eyeOffset(ipd, Stereo.LEFT));
        assertThrows(IllegalArgumentException.class, () -> Stereo.eyeOffset(ipd, 2));
    }

    @Test
    void offAxisProjectionHasNoParallaxAtTheScreenPlane() {
        SplittableRandom r = new SplittableRandom(SEED);
        float hw = 0.8f, hh = 0.45f, dist = 2.0f, ipd = 0.065f;
        Mat4f head = Mat4f.IDENTITY;
        for (ClipSpace space : ClipSpace.values()) {
            Mat4f pl = Stereo.offAxis(hw, hh, dist, Stereo.eyeOffset(ipd, Stereo.LEFT), 0.1f, 50f, space);
            Mat4f pr = Stereo.offAxis(hw, hh, dist, Stereo.eyeOffset(ipd, Stereo.RIGHT), 0.1f, 50f, space);
            Mat4f vl = Stereo.eyeView(head, ipd, Stereo.LEFT), vr = Stereo.eyeView(head, ipd, Stereo.RIGHT);
            for (int k = 0; k < 200; k++) {
                Vec3f onScreen = new Vec3f((float) ((r.nextDouble() * 2 - 1) * hw), (float) ((r.nextDouble() * 2 - 1) * hh), -dist);
                Vec3f a = ndc(pl.mul(vl), onScreen), b = ndc(pr.mul(vr), onScreen);
                assertEquals(a.x(), b.x(), 1e-5f);
                assertEquals(a.y(), b.y(), 1e-5f);
                // and the screen rectangle fills the whole viewport
                assertTrue(Math.abs(a.x()) <= 1f + 1e-5f && Math.abs(a.y()) <= 1f + 1e-5f);
            }
            // the screen corners are the corners of the viewport
            Vec3f corner = ndc(pl.mul(vl), new Vec3f(-hw, -hh, -dist));
            assertEquals(-1f, corner.x(), 1e-5f);
            assertEquals(space.yDown() ? 1f : -1f, corner.y(), 1e-5f);
            // a nearer point is shifted in opposite directions in the two eyes
            Vec3f near = new Vec3f(0f, 0f, -dist / 2);
            assertTrue(ndc(pl.mul(vl), near).x() > ndc(pr.mul(vr), near).x());
        }
    }

    @Test
    void reversedStereoProjectionMapsNearToOneAndInfinityToZero() {
        float a = 0.8f, b = 0.7f, c = 0.6f, d = 0.9f;
        Mat4f p = Stereo.projectionReversedZ(-a, b, c, -d, 0.25f, ClipSpace.D3D);
        assertEquals(1f, ndc(p, new Vec3f(0f, 0f, -0.25f)).z(), 1e-5f);
        assertEquals(0f, ndc(p, new Vec3f(0f, 0f, -1e6f)).z(), 1e-5f);
        // edges: the left edge at one unit of depth is at tan(angleLeft)
        assertEquals(-1f, ndc(p, new Vec3f(-(float) Math.tan(a), 0f, -1f)).x(), 1e-5f);
        assertEquals(1f, ndc(p, new Vec3f((float) Math.tan(b), 0f, -1f)).x(), 1e-5f);
        assertEquals(1f, ndc(p, new Vec3f(0f, (float) Math.tan(c), -1f)).y(), 1e-5f);
        assertEquals(-1f, ndc(p, new Vec3f(0f, -(float) Math.tan(d), -1f)).y(), 1e-5f);
        assertThrows(IllegalArgumentException.class, () -> Stereo.projectionReversedZ(-a, b, c, -d, 0.25f, ClipSpace.OPENGL));
        Mat4f flipped = Stereo.projectionReversedZ(-a, b, c, -d, 0.25f, ClipSpace.VULKAN);
        assertEquals(-ndc(p, new Vec3f(0.1f, 0.2f, -3f)).y(), ndc(flipped, new Vec3f(0.1f, 0.2f, -3f)).y(), 1e-6f);
    }

    // ---------------------------------------------------------------- dual paraboloid

    @Test
    void paraboloidProjectionAndDirectionAreInverse() {
        SplittableRandom r = new SplittableRandom(SEED);
        float[] disc = new float[3], dir = new float[3];
        int checked = 0;
        for (int k = 0; k < 20000; k++) {
            float x = (float) (r.nextDouble() * 2 - 1), y = (float) (r.nextDouble() * 2 - 1), z = (float) (-r.nextDouble());
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 0.05f) {
                continue;
            }
            assertTrue(DualParaboloid.project(x, y, z, 0.1f, 10f, disc));
            assertTrue(disc[0] * disc[0] + disc[1] * disc[1] <= 1f + 1e-5f);
            assertTrue(DualParaboloid.direction(disc[0], disc[1], dir));
            assertEquals(x / len, dir[0], 2e-5f);
            assertEquals(y / len, dir[1], 2e-5f);
            assertEquals(z / len, dir[2], 2e-5f);
            assertEquals((len - 0.1f) / 9.9f, disc[2], 1e-5f);
            checked++;
        }
        assertTrue(checked > 15000);
    }

    @Test
    void paraboloidCentreAxisAndEquator() {
        float[] o = new float[3];
        assertTrue(DualParaboloid.project(0f, 0f, -2f, 1f, 5f, o));
        assertEquals(0f, o[0]);
        assertEquals(0f, o[1]);
        assertEquals(0.25f, o[2], 1e-6f);
        // the equator is the rim of the disc
        assertTrue(DualParaboloid.project(3f, 0f, 0f, 1f, 5f, o));
        assertEquals(1f, o[0], 1e-6f);
        assertTrue(DualParaboloid.project(0f, -3f, 0f, 1f, 5f, o));
        assertEquals(-1f, o[1], 1e-6f);
        // behind the hemisphere and at the centre: not mapped
        assertFalse(DualParaboloid.project(0f, 0f, 1f, 1f, 5f, o));
        assertFalse(DualParaboloid.project(0f, 0f, 0f, 1f, 5f, o));
        assertFalse(DualParaboloid.direction(0.8f, 0.8f, o));
        assertTrue(DualParaboloid.direction(0f, 0f, o));
        assertEquals(-1f, o[2]);
    }

    @Test
    void everyDirectionBelongsToOneHemisphereAndItsViewMapsItToTheDisc() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        Vec3f centre = new Vec3f(2f, -1f, 4f);
        float[] o = new float[3];
        for (int k = 0; k < 5000; k++) {
            Vec3f d = new Vec3f((float) (r.nextDouble() * 2 - 1), (float) (r.nextDouble() * 2 - 1), (float) (r.nextDouble() * 2 - 1));
            float len = (float) Math.sqrt(d.x() * d.x() + d.y() * d.y() + d.z() * d.z());
            if (len < 0.1f) {
                continue;
            }
            Vec3f world = new Vec3f(centre.x() + d.x() * 5f, centre.y() + d.y() * 5f, centre.z() + d.z() * 5f);
            int h = DualParaboloid.hemisphereOf(d);
            Vec3f v = DualParaboloid.view(h, centre).transformPosition(world);
            assertTrue(DualParaboloid.project(v.x(), v.y(), v.z(), 0.1f, 100f, o), "direction " + d);
            Planef half = DualParaboloid.halfSpace(h, centre);
            assertTrue(half.distance(world) >= -1e-4f);
            if (d.z() != 0f) {
                // and the other hemisphere's half-space does not contain it
                assertTrue(DualParaboloid.halfSpace(1 - h, centre).distance(world) <= 1e-4f);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> DualParaboloid.view(2, centre));
        assertTrue(DualParaboloid.glsl().contains("d.xy / (1.0 - d.z)"));
    }
}
