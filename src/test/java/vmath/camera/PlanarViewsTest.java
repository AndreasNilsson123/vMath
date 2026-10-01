package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.ClipSpace;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Planef;

class PlanarViewsTest {

    private static final long SEED = Long.getLong("vmath.seed", 7L);

    private static final class Rnd {
        private final SplittableRandom r;

        Rnd(long seed) {
            r = new SplittableRandom(seed);
        }

        double range(double lo, double hi) {
            return lo + (hi - lo) * r.nextDouble();
        }
    }

    private static Mat4f perspective(DepthRange depth, boolean yDown) {
        Mat4f p = switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> Mat4f.perspective(1.1f, 1.6f, 0.2f, 300f, ClipSpace.OPENGL);
            case ZERO_TO_ONE -> Mat4f.perspective(1.1f, 1.6f, 0.2f, 300f, ClipSpace.D3D);
            case REVERSED_ZERO_TO_ONE -> Mat4f.perspectiveReversedZ(1.1f, 1.6f, 0.2f, ClipSpace.D3D);
        };
        return yDown ? p.flipY() : p;
    }

    /** Does the clip-space point pass the near plane of this convention? */
    private static boolean passesNear(Vec4f c, DepthRange depth) {
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> c.z() + c.w() >= 0f;
            case ZERO_TO_ONE -> c.z() >= 0f;
            case REVERSED_ZERO_TO_ONE -> c.w() - c.z() >= 0f;
        };
    }

    private static Planef randomPlaneInFront(Rnd rnd) {
        // a plane some way in front of the camera, tilted by up to about 60 degrees, kept side facing the camera's far side
        float tx = (float) rnd.range(-0.9, 0.9), ty = (float) rnd.range(-0.9, 0.9);
        Vec3f n = new Vec3f(tx, ty, -1f);
        float len = (float) Math.sqrt(n.x() * n.x() + n.y() * n.y() + n.z() * n.z());
        n = new Vec3f(n.x() / len, n.y() / len, n.z() / len);
        float dist = (float) rnd.range(1.0, 30.0);
        // points p with n.p + d >= 0 are kept; the camera at the origin has d < 0 when the plane lies ahead
        return new Planef(n.x(), n.y(), n.z(), -dist);
    }

    @Test
    void obliqueNearPlaneClipsExactlyTheNegativeSideInEveryConvention() {
        Rnd rnd = new Rnd(SEED);
        for (DepthRange depth : DepthRange.values()) {
            for (boolean yDown : new boolean[] {false, true}) {
                Mat4f p = perspective(depth, yDown);
                int checked = 0;
                for (int plane = 0; plane < 60; plane++) {
                    Planef c = randomPlaneInFront(rnd);
                    Mat4f q = PlanarViews.obliqueNearPlane(p, c, depth);
                    for (int i = 0; i < 300; i++) {
                        Vec3f v = new Vec3f((float) rnd.range(-40, 40), (float) rnd.range(-40, 40), (float) -rnd.range(0.3, 80));
                        float side = c.nx() * v.x() + c.ny() * v.y() + c.nz() * v.z() + c.d();
                        if (Math.abs(side) < 1e-2f) {
                            continue;
                        }
                        assertEquals(side > 0f, passesNear(q.transform(new Vec4f(v.x(), v.y(), v.z(), 1f)), depth),
                                depth + " yDown=" + yDown + " plane " + c + " point " + v);
                        checked++;
                    }
                }
                assertTrue(checked > 10000, "enough points checked: " + checked);
            }
        }
    }

    @Test
    void obliqueNearPlaneKeepsTheSidePlanesAndTheFarCorner() {
        Rnd rnd = new Rnd(SEED + 1);
        for (DepthRange depth : DepthRange.values()) {
            Mat4f p = perspective(depth, false);
            Mat4f inv = p.invert();
            for (int k = 0; k < 40; k++) {
                Planef c = randomPlaneInFront(rnd);
                Mat4f q = PlanarViews.obliqueNearPlane(p, c, depth);
                // x, y and w rows are untouched
                for (int col = 0; col < 4; col++) {
                    assertEquals(p.get(col, 0), q.get(col, 0));
                    assertEquals(p.get(col, 1), q.get(col, 1));
                    assertEquals(p.get(col, 3), q.get(col, 3));
                }
                // the far-plane corner on the kept side stays on the far plane
                float farZ = depth == DepthRange.REVERSED_ZERO_TO_ONE ? 0f : 1f;
                float best = -1e30f;
                Vec4f bestQ = null;
                for (int corner = 0; corner < 4; corner++) {
                    Vec4f w = inv.transform(new Vec4f((corner & 1) == 0 ? -1f : 1f, (corner & 2) == 0 ? -1f : 1f, farZ, 1f));
                    float s = c.nx() * w.x() + c.ny() * w.y() + c.nz() * w.z() + c.d() * w.w();
                    if (s > best) {
                        best = s;
                        bestQ = w;
                    }
                }
                Vec4f clip = q.transform(bestQ);
                assertEquals(farZ, clip.z() / clip.w(), 1e-3f, depth + " far corner depth");
            }
        }
    }

    @Test
    void obliqueNearPlaneRejectsBadPlanes() {
        Mat4f p = perspective(DepthRange.ZERO_TO_ONE, false);
        // the camera on the kept side
        assertThrows(IllegalArgumentException.class, () -> PlanarViews.obliqueNearPlane(p, new Planef(0f, 0f, -1f, 1f), DepthRange.ZERO_TO_ONE));
        // the whole frustum clipped: a plane facing backwards in front of the camera
        assertThrows(IllegalArgumentException.class, () -> PlanarViews.obliqueNearPlane(p, new Planef(0f, 0f, 1f, -1f), DepthRange.ZERO_TO_ONE));
        assertThrows(IllegalArgumentException.class, () -> PlanarViews.obliqueNearPlane(p, new Planef(Float.NaN, 0f, -1f, -1f), DepthRange.ZERO_TO_ONE));
    }

    @Test
    void aPlaneParallelToTheViewGivesAnOrdinaryNearPlane() {
        // the kept side is z <= -5 in view space: equivalent to near = 5
        Planef c = new Planef(0f, 0f, -1f, -5f);
        for (DepthRange depth : new DepthRange[] {DepthRange.ZERO_TO_ONE, DepthRange.NEGATIVE_ONE_TO_ONE}) {
            Mat4f q = PlanarViews.obliqueNearPlane(perspective(depth, false), c, depth);
            Vec4f atPlane = q.transform(new Vec4f(0.3f, 0.2f, -5f, 1f));
            assertEquals(depth == DepthRange.ZERO_TO_ONE ? 0f : -1f, atPlane.z() / atPlane.w(), 1e-4f);
        }
    }

    @Test
    void reflectionMirrorsPointsAndIsItsOwnInverse() {
        Rnd rnd = new Rnd(SEED + 2);
        for (int k = 0; k < 200; k++) {
            Planef plane = new Planef((float) rnd.range(-1, 1), (float) rnd.range(-1, 1), (float) rnd.range(-1, 1) + 2f, (float) rnd.range(-5, 5));
            Planef n = plane.normalize();
            Mat4f m = PlanarViews.reflection(plane);
            Vec3f p = new Vec3f((float) rnd.range(-10, 10), (float) rnd.range(-10, 10), (float) rnd.range(-10, 10));
            Vec3f r = m.transformPosition(p);
            // equal distances on opposite sides, and the segment is along the normal
            assertEquals(-n.distance(p), n.distance(r), 1e-4f);
            Vec3f mid = new Vec3f((p.x() + r.x()) * 0.5f, (p.y() + r.y()) * 0.5f, (p.z() + r.z()) * 0.5f);
            assertEquals(0f, n.distance(mid), 1e-4f);
            assertTrue(m.mul(m).approxEquals(Mat4f.IDENTITY, 1e-5f));
            assertEquals(-1f, m.determinant(), 1e-5f);
            Vec3f onPlane = n.closestPoint(p);
            Vec3f same = m.transformPosition(onPlane);
            assertEquals(0f, Math.max(Math.abs(same.x() - onPlane.x()), Math.max(Math.abs(same.y() - onPlane.y()), Math.abs(same.z() - onPlane.z()))), 1e-4f);
        }
    }

    @Test
    void reflectedViewShowsTheMirrorImageOfTheScene() {
        Mat4f view = Mat4f.lookAt(new Vec3f(3f, 2f, 8f), new Vec3f(0f, 1f, 0f), new Vec3f(0f, 1f, 0f));
        Planef water = new Planef(0f, 1f, 0f, 0f); // y = 0
        Mat4f mirrored = PlanarViews.reflectedView(view, water);
        Vec3f p = new Vec3f(1f, 2f, -3f), image = new Vec3f(1f, -2f, -3f);
        Vec3f a = mirrored.transformPosition(p), b = view.transformPosition(image);
        assertEquals(b.x(), a.x(), 1e-5f);
        assertEquals(b.y(), a.y(), 1e-5f);
        assertEquals(b.z(), a.z(), 1e-5f);
    }

    @Test
    void portalViewMovesTheCameraThroughThePortal() {
        Rnd rnd = new Rnd(SEED + 3);
        for (int k = 0; k < 100; k++) {
            Mat4f view = Mat4f.lookAt(new Vec3f((float) rnd.range(-5, 5), (float) rnd.range(-5, 5), (float) rnd.range(-5, 5)), new Vec3f(0f, 0f, 0f), new Vec3f(0f, 1f, 0f));
            Mat4f source = Mat4f.translation((float) rnd.range(-9, 9), 0f, (float) rnd.range(-9, 9)).mul(Mat4f.rotationY((float) rnd.range(-3, 3)));
            Mat4f dest = Mat4f.translation((float) rnd.range(-9, 9), (float) rnd.range(0, 3), (float) rnd.range(-9, 9)).mul(Mat4f.rotationY((float) rnd.range(-3, 3)));
            Mat4f t = PlanarViews.portalTransform(source, dest);
            Mat4f virtual = PlanarViews.portalView(view, source, dest);
            Vec3f p = new Vec3f((float) rnd.range(-4, 4), (float) rnd.range(-4, 4), (float) rnd.range(-4, 4));
            // the virtual camera sees the moved point as the real one sees the original
            Vec3f a = virtual.transformPosition(t.transformPosition(p)), b = view.transformPosition(p);
            assertEquals(b.x(), a.x(), 2e-3f);
            assertEquals(b.y(), a.y(), 2e-3f);
            assertEquals(b.z(), a.z(), 2e-3f);
            // the portal centre goes to the other portal's centre, and the side it is seen from becomes the side it is left to
            Vec3f c = t.transformPosition(source.getTranslation());
            Vec3f d = dest.getTranslation();
            assertEquals(d.x(), c.x(), 2e-3f);
            assertEquals(d.y(), c.y(), 2e-3f);
            assertEquals(d.z(), c.z(), 2e-3f);
            Vec3f front = t.transformDirection(source.transformDirection(new Vec3f(0f, 0f, 1f)));
            Vec3f destFront = dest.transformDirection(new Vec3f(0f, 0f, 1f));
            assertEquals(-destFront.x(), front.x(), 1e-4f);
            assertEquals(-destFront.y(), front.y(), 1e-4f);
            assertEquals(-destFront.z(), front.z(), 1e-4f);
        }
    }
}
