package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import vmath.core.Mat4d;
import vmath.core.Mat4f;
import vmath.core.Quatd;
import vmath.core.Quatf;
import vmath.core.Vec3d;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.Planef;
import vmath.geo.Rayd;
import vmath.geo.DepthRange;
import vmath.geo.Rayf;

/**
 * {@link OrthoCameraf} and its double twin: the matrices against JOML's {@code setOrtho} and
 * {@code lookAt}, the projections and their inverses, the pick ray (a fixed direction and an origin
 * that moves), the depth conversions of every convention, the culling box, pixel-exact views and
 * the precision of the camera-relative form.
 */
class OrthoCameraTest {

    private final SplittableRandom rnd = new SplittableRandom(11);

    private float r(double lo, double hi) {
        return (float) (lo + (hi - lo) * rnd.nextDouble());
    }

    private Quatf randomOrientation() {
        return Quatf.lookRotation(new Vec3f(r(-1, 1), r(-1, 1), r(-1, 1) - 2f), Vec3f.UNIT_Y);
    }

    private OrthoCameraf random(DepthRange depth) {
        float left = r(-20, -0.5), bottom = r(-12, -0.5);
        float near = r(-3, 2);
        return new OrthoCameraf(new Vec3f(r(-50, 50), r(-50, 50), r(-50, 50)), randomOrientation(), left, left + r(1, 40), bottom, bottom + r(1, 25), near, near + r(1, 200), depth);
    }

    private static void assertClose(float expected, float actual, float eps, String what) {
        assertEquals(expected, actual, eps * Math.max(1f, Math.abs(expected)), what);
    }

    private static float[] array(Mat4f m) {
        float[] a = new float[16];
        m.writeTo(a, 0);
        return a;
    }

    // ---------------------------------------------------------------- matrices

    @Test
    void theProjectionIsJomlsOrthoInTheTwoConventionsItHas() {
        for (int rep = 0; rep < 200; rep++) {
            OrthoCameraf c = random(DepthRange.NEGATIVE_ONE_TO_ONE);
            float[] j = new float[16];
            new Matrix4f().setOrtho(c.left(), c.right(), c.bottom(), c.top(), c.near(), c.far()).get(j);
            float[] v = array(c.projection());
            for (int i = 0; i < 16; i++) {
                assertClose(j[i], v[i], 1e-5f, "OpenGL depth, element " + i);
            }
            OrthoCameraf d = new OrthoCameraf(c.position(), c.orientation(), c.left(), c.right(), c.bottom(), c.top(), c.near(), c.far(), DepthRange.ZERO_TO_ONE);
            new Matrix4f().setOrtho(c.left(), c.right(), c.bottom(), c.top(), c.near(), c.far(), true).get(j);
            v = array(d.projection());
            for (int i = 0; i < 16; i++) {
                assertClose(j[i], v[i], 1e-5f, "zero to one depth, element " + i);
            }
        }
    }

    @Test
    void theViewAndTheViewProjectionAreJomlsLookAt() {
        for (int rep = 0; rep < 100; rep++) {
            Vec3f eye = new Vec3f(r(-30, 30), r(-30, 30), r(-30, 30)), target = new Vec3f(r(-5, 5), r(-5, 5), r(-5, 5));
            if (eye.sub(target).length() < 1f) {
                continue;
            }
            OrthoCameraf c = OrthoCameraf.lookingAt(eye, target, Vec3f.UNIT_Y, r(2, 30), r(0.5, 2.5), r(0.1, 2), r(50, 300), DepthRange.NEGATIVE_ONE_TO_ONE);
            Matrix4f view = new Matrix4f().lookAt(new Vector3f(eye.x(), eye.y(), eye.z()), new Vector3f(target.x(), target.y(), target.z()), new Vector3f(0f, 1f, 0f));
            Matrix4f vp = new Matrix4f().setOrtho(c.left(), c.right(), c.bottom(), c.top(), c.near(), c.far()).mul(view);
            float[] j = new float[16];
            vp.get(j);
            float[] v = array(c.viewProjection());
            for (int i = 0; i < 16; i++) {
                assertClose(j[i], v[i], 2e-4f, "view-projection element " + i);
            }
        }
    }

    @Test
    void reversedDepthIsTheZeroToOneProjectionTurnedAround() {
        for (int rep = 0; rep < 100; rep++) {
            OrthoCameraf c = random(DepthRange.REVERSED_ZERO_TO_ONE);
            OrthoCameraf d = new OrthoCameraf(c.position(), c.orientation(), c.left(), c.right(), c.bottom(), c.top(), c.near(), c.far(), DepthRange.ZERO_TO_ONE);
            Vec3f world = c.position().add(c.forward().mul(r(c.near(), c.far()))).add(c.rightAxis().mul(r(c.left(), c.right()))).add(c.up().mul(r(c.bottom(), c.top())));
            Vec3f a = c.project(world), b = d.project(world);
            assertClose(b.x(), a.x(), 1e-4f, "x");
            assertClose(b.y(), a.y(), 1e-4f, "y");
            assertClose(1f - b.z(), a.z(), 1e-4f, "reversed depth is one minus the depth");
            assertClose(1f, c.project(c.position().add(c.forward().mul(c.near()))).z(), 1e-4f, "the near plane is at 1");
            assertClose(0f, c.project(c.position().add(c.forward().mul(c.far()))).z(), 1e-4f, "the far plane is at 0");
        }
    }

    // ---------------------------------------------------------------- points

    @Test
    void projectUnprojectAndTheScreenFollowTheBox() {
        for (DepthRange depth : DepthRange.values()) {
            for (int rep = 0; rep < 100; rep++) {
                OrthoCameraf c = random(depth);
                float x = r(c.left(), c.right()), y = r(c.bottom(), c.top()), d = r(c.near(), c.far());
                Vec3f world = c.position().add(c.rightAxis().mul(x)).add(c.up().mul(y)).add(c.forward().mul(d));
                Vec3f ndc = c.project(world);
                assertClose((x - c.left()) / c.width() * 2f - 1f, ndc.x(), 1e-4f, depth + " ndc x");
                assertClose((y - c.bottom()) / c.height() * 2f - 1f, ndc.y(), 1e-4f, depth + " ndc y");
                Vec3f back = c.unproject(ndc);
                assertTrue(back.sub(world).length() < 2e-3f * Math.max(1f, world.length()), depth + " unproject: " + back + " against " + world);
                Vec3f px = c.toScreen(world, 800, 600);
                assertClose((ndc.x() * 0.5f + 0.5f) * 800f, px.x(), 1e-4f, "pixel x");
                assertClose((0.5f - ndc.y() * 0.5f) * 600f, px.y(), 1e-4f, "pixel y from the top");
                assertEquals(1f, c.toClip(world).w(), 1e-6f, "w is 1: nothing divides by the depth");
            }
        }
    }

    @Test
    void aPointBehindTheCameraProjectsLikeAnyOther() {
        OrthoCameraf c = OrthoCameraf.of(Vec3f.ZERO, Quatf.IDENTITY, 10f, 1f, -5f, 20f, DepthRange.NEGATIVE_ONE_TO_ONE);
        Vec3f ndc = c.project(new Vec3f(1f, 2f, 3f));       // z = +3 is behind the camera plane, inside a near plane at -5 (a distance of -3)
        assertClose(0.2f, ndc.x(), 1e-6f, "x");
        assertClose(0.4f, ndc.y(), 1e-6f, "y");
        assertTrue(ndc.z() > -1f && ndc.z() < 1f, "inside the depth range: " + ndc.z());
    }

    // ---------------------------------------------------------------- the pick ray

    @Test
    void thePickRayHasTheDirectionOfTheViewAndAnOriginThatMoves() {
        for (DepthRange depth : DepthRange.values()) {
            for (int rep = 0; rep < 100; rep++) {
                OrthoCameraf c = random(depth);
                float px = r(0, 1024), py = r(0, 768);
                Rayf ray = c.pickRay(px, py, 1024, 768);
                assertTrue(ray.direction().sub(c.forward()).length() < 1e-5f, "every ray has the direction of the view");
                // the ray passes through the world point that the pixel shows at any depth
                float t = r(c.near(), c.far());
                float ndcDepth = c.project(c.position().add(c.forward().mul(t))).z();
                Vec3f onRay = c.unproject(new Vec3f(px / 1024f * 2f - 1f, 1f - py / 768f * 2f, ndcDepth));
                Vec3f fromOrigin = onRay.sub(ray.origin());
                assertTrue(fromOrigin.sub(c.forward().mul(fromOrigin.dot(c.forward()))).length() < 2e-3f * Math.max(1f, onRay.length()), depth + ": the ray passes through the pixel");
            }
        }
        OrthoCameraf c = OrthoCameraf.forViewport(new Vec3f(100f, 50f, 10f), 200, 100, 1f, 0.1f, 100f, DepthRange.NEGATIVE_ONE_TO_ONE);
        Rayf middle = c.pickRay(100f, 50f, 200, 100);
        assertTrue(middle.origin().sub(new Vec3f(100f, 50f, 10f)).length() < 1e-4f, "the middle pixel starts at the camera");
        Rayf corner = c.pickRay(0f, 0f, 200, 100);
        assertTrue(corner.origin().sub(new Vec3f(0f, 100f, 10f)).length() < 1e-4f, "the top-left pixel starts a half width left and a half height up: " + corner.origin());
        assertEquals(-1f, corner.direction().z(), 1e-6f);
    }

    // ---------------------------------------------------------------- depth

    @Test
    void depthIsLinearAndComesBackInEveryConvention() {
        for (DepthRange depth : DepthRange.values()) {
            for (int rep = 0; rep < 200; rep++) {
                OrthoCameraf c = random(depth);
                float d = r(c.near(), c.far());
                Vec3f world = c.position().add(c.forward().mul(d)).add(c.rightAxis().mul(r(c.left(), c.right()))).add(c.up().mul(r(c.bottom(), c.top())));
                Vec3f ndc = c.project(world);
                assertClose(d, c.linearizeDepth(ndc.z()), 2e-4f, depth + " linearizeDepth");
                Vec3f view = c.viewPositionFromDepth(ndc.x(), ndc.y(), ndc.z());
                assertClose(-d, view.z(), 2e-4f, "view z");
                assertTrue(c.worldPositionFromDepth(ndc.x(), ndc.y(), ndc.z()).sub(world).length() < 3e-3f * Math.max(1f, world.length()), depth + " world position from depth");
            }
        }
        OrthoCameraf c = OrthoCameraf.of(Vec3f.ZERO, Quatf.IDENTITY, 2f, 1f, 1f, 11f, DepthRange.ZERO_TO_ONE);
        assertEquals(6f, c.linearizeDepth(0.5f), 1e-6f, "halfway in depth is halfway in distance: nothing is exponential");
    }

    // ---------------------------------------------------------------- culling

    @Test
    void theFrustumIsTheBox() {
        for (DepthRange depth : DepthRange.values()) {
            OrthoCameraf c = OrthoCameraf.lookingAt(new Vec3f(0f, 0f, 10f), Vec3f.ZERO, Vec3f.UNIT_Y, 10f, 2f, 1f, 30f, depth);
            var frustum = c.frustum();
            assertTrue(frustum.intersects(Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f))), depth + ": inside");
            assertTrue(frustum.intersects(Aabbf.of(new Vec3f(9.5f, 0f, 0f), new Vec3f(11f, 1f, 1f))), depth + ": straddles the right edge (the half width is 10)");
            assertFalse(frustum.intersects(Aabbf.of(new Vec3f(10.5f, 0f, 0f), new Vec3f(12f, 1f, 1f))), depth + ": beyond the right edge");
            assertFalse(frustum.intersects(Aabbf.of(new Vec3f(0f, 5.5f, 0f), new Vec3f(1f, 7f, 1f))), depth + ": above the top edge");
            assertFalse(frustum.intersects(Aabbf.of(new Vec3f(0f, 0f, 10f), new Vec3f(1f, 1f, 10.5f))), depth + ": nearer than the near plane");
            assertFalse(frustum.intersects(Aabbf.of(new Vec3f(0f, 0f, -22f), new Vec3f(1f, 1f, -21f))), depth + ": beyond the far plane");
            assertTrue(frustum.intersects(Aabbf.of(new Vec3f(0f, 0f, -19f), new Vec3f(1f, 1f, -18f))), depth + ": just inside the far plane");
        }
    }

    // ---------------------------------------------------------------- pixel-exact views

    @Test
    void aViewportCameraMapsWorldUnitsToPixels() {
        OrthoCameraf c = OrthoCameraf.forViewport(new Vec3f(640f, 360f, 5f), 1280, 720, 1f, 0.1f, 10f, DepthRange.ZERO_TO_ONE);
        assertEquals(1f, c.pixelSize(720), 1e-6f);
        for (int rep = 0; rep < 100; rep++) {
            float x = rnd.nextInt(1280), y = rnd.nextInt(720);
            Vec3f px = c.toScreen(new Vec3f(x, y, 0f), 1280, 720);
            assertEquals(x, px.x(), 1e-3f, "the world x is the pixel x");
            assertEquals(720f - y, px.y(), 1e-3f, "the world y is the pixel row counted from the bottom");
        }
        OrthoCameraf twice = OrthoCameraf.forViewport(new Vec3f(0f, 0f, 5f), 1280, 720, 2f, 0.1f, 10f, DepthRange.ZERO_TO_ONE);
        assertEquals(2f, twice.pixelSize(720), 1e-6f);
        assertEquals(2560f, twice.width(), 1e-4f);
        OrthoCameraf zoomed = c.zoomed(4f);
        assertEquals(0.25f, zoomed.pixelSize(720), 1e-6f, "zooming in makes a pixel smaller");
        assertClose((c.left() + c.right()) / 2f, (zoomed.left() + zoomed.right()) / 2f, 1e-6f, "about the same middle");
        OrthoCameraf wide = c.withAspect(2f);
        assertEquals(2f, wide.aspect(), 1e-5f);
        assertEquals(c.height(), wide.height(), 1e-6f, "the height stays");
    }

    @Test
    void jitterMovesEveryPointByTheSameFractionOfAPixel() {
        for (DepthRange depth : DepthRange.values()) {
            OrthoCameraf c = random(depth);
            float jx = r(-0.5, 0.5), jy = r(-0.5, 0.5);
            Mat4f jittered = c.jitteredProjection(jx, jy, 800, 600).mul(c.view());
            Mat4f plain = c.viewProjection();
            for (int i = 0; i < 20; i++) {
                Vec3f p = c.position().add(c.forward().mul(r(c.near(), c.far()))).add(c.rightAxis().mul(r(c.left(), c.right()))).add(c.up().mul(r(c.bottom(), c.top())));
                Vec3f a = plain.transformProject(p), b = jittered.transformProject(p);
                assertClose(a.x() + 2f * jx / 800f, b.x(), 1e-4f, depth + " x");
                assertClose(a.y() + 2f * jy / 600f, b.y(), 1e-4f, depth + " y");
                assertClose(a.z(), b.z(), 1e-5f, depth + " depth is not touched");
            }
        }
    }

    @Test
    void reprojectionOfTheSameCameraIsTheIdentity() {
        OrthoCameraf c = random(DepthRange.ZERO_TO_ONE);
        Mat4f id = c.reprojection(c.viewProjection());
        float[] a = array(id);
        for (int i = 0; i < 16; i++) {
            assertEquals(i % 5 == 0 ? 1f : 0f, a[i], 2e-3f, "element " + i);
        }
    }

    // ---------------------------------------------------------------- types and precision

    @Test
    void theDoubleTwinAgreesAndTheCameraRelativeFormKeepsPrecisionFarAway() {
        Vec3d origin = new Vec3d(6_378_137.0, 12_345.0, -87_654.0);
        Quatd q = Quatd.lookRotation(new Vec3d(0.2, -0.3, -1.0), Vec3d.UNIT_Y);
        OrthoCamerad cam = new OrthoCamerad(origin, q, -40.0, 40.0, -22.5, 22.5, 0.5, 500.0, DepthRange.ZERO_TO_ONE);
        double worst = 0, worstNaive = 0;
        for (int i = 0; i < 300; i++) {
            Vec3d p = origin.add(q.transform(new Vec3d(r(-35, 35), r(-20, 20), -r(1, 400))));
            Vec3d exact = cam.project(p);
            Vec3f local = p.relativeTo(cam.position());
            Vec3f relative = cam.cameraRelative().project(local);
            worst = Math.max(worst, Math.abs(exact.x() - relative.x()) + Math.abs(exact.y() - relative.y()));
            Vec3f naive = cam.toFloat().project(p.toFloat());
            worstNaive = Math.max(worstNaive, Math.abs(exact.x() - naive.x()) + Math.abs(exact.y() - naive.y()));
        }
        assertTrue(worst < 2e-3, "camera-relative error " + worst);
        assertTrue(worstNaive > 20 * worst, "narrowing the world first is much worse: " + worstNaive + " against " + worst);
        OrthoCameraf f = random(DepthRange.NEGATIVE_ONE_TO_ONE);
        OrthoCameraf round = f.toDouble().toFloat();
        assertEquals(f, round, "float to double and back is exact");
    }

    @Test
    void anObliqueNearPlaneWorksOnAnOrthographicProjectionToo() {
        // the replaced near plane of an orthographic projection: a point on the plane has the depth of the near plane, and the kept side the rest
        for (DepthRange depth : DepthRange.values()) {
            OrthoCameraf c = OrthoCameraf.of(Vec3f.ZERO, Quatf.IDENTITY, 10f, 1.5f, 0.1f, 100f, depth);
            Planef plane = new Planef(0.3f, 0.2f, -1f, -5f).normalize();           // kept side: n . p + d >= 0, the camera at the origin is on the other side
            Mat4f oblique = vmath.camera.PlanarViews.obliqueNearPlane(c.projection(), plane, depth);
            float onPlane = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? -1f : depth == DepthRange.ZERO_TO_ONE ? 0f : 1f;
            for (int i = 0; i < 50; i++) {
                float x = r(-5, 5), y = r(-4, 4);
                float zOn = (-plane.d() - plane.nx() * x - plane.ny() * y) / plane.nz();
                Vec3f p = new Vec3f(x, y, zOn);
                assertEquals(onPlane, oblique.transformProject(p).z(), 1e-4f, depth + ": on the plane");
                Vec3f kept = p.add(new Vec3f(plane.nx(), plane.ny(), plane.nz()).mul(0.5f));
                float dk = oblique.transformProject(kept).z();
                assertTrue(depth == DepthRange.REVERSED_ZERO_TO_ONE ? dk < onPlane : dk > onPlane, depth + ": the kept side is farther in depth");
                Vec3f clipped = p.sub(new Vec3f(plane.nx(), plane.ny(), plane.nz()).mul(0.5f));
                float dc = oblique.transformProject(clipped).z();
                assertTrue(depth == DepthRange.REVERSED_ZERO_TO_ONE ? dc > onPlane : dc < onPlane, depth + ": the clipped side is outside the depth range");
            }
        }
    }

    @Test
    void theDoubleTwinHasTheSameApiAndTheSameAnswers() {
        for (DepthRange depth : DepthRange.values()) {
            Vec3d eye = new Vec3d(3.0, -2.0, 9.0), target = new Vec3d(0.5, 0.25, -1.0);
            OrthoCamerad d = OrthoCamerad.lookingAt(eye, target, Vec3d.UNIT_Y, 12.0, 1.5, 0.2, 80.0, depth);
            OrthoCameraf f = OrthoCameraf.lookingAt(eye.toFloat(), target.toFloat(), Vec3f.UNIT_Y, 12f, 1.5f, 0.2f, 80f, depth);
            assertEquals(f.width(), d.width(), 1e-4);
            assertEquals(f.height(), d.height(), 1e-4);
            assertEquals(f.aspect(), d.aspect(), 1e-5);
            assertEquals(f.pixelSize(600), d.pixelSize(600), 1e-5);
            Vec3d world = eye.add(d.forward().mul(10.0)).add(d.rightAxis().mul(3.0)).add(d.up().mul(-2.0));
            Vec3d ndc = d.project(world);
            Vec3f ndcF = f.project(world.toFloat());
            assertEquals(ndcF.x(), ndc.x(), 2e-4);
            assertEquals(ndcF.y(), ndc.y(), 2e-4);
            assertEquals(ndcF.z(), ndc.z(), 2e-4);
            assertTrue(d.unproject(ndc).sub(world).length() < 1e-9, "unproject in double is exact");
            assertEquals(d.toScreen(world, 800, 600).x(), (ndc.x() * 0.5 + 0.5) * 800, 1e-9);
            assertEquals(1.0, d.toClip(world).w(), 1e-12);
            assertEquals(10.0, d.linearizeDepth(ndc.z()), 1e-9);
            assertTrue(d.worldPositionFromDepth(ndc.x(), ndc.y(), ndc.z()).sub(world).length() < 1e-9);
            assertEquals(-10.0, d.viewPositionFromDepth(ndc.x(), ndc.y(), ndc.z()).z(), 1e-9);
            Rayd ray = d.pickRay(200.5, 100.5, 800, 600);
            assertTrue(ray.direction().sub(d.forward()).length() < 1e-12);
            assertTrue(d.frustum().intersects(vmath.geo.Aabbd.of(world.sub(new Vec3d(0.1, 0.1, 0.1)), world.add(new Vec3d(0.1, 0.1, 0.1)))));
            Mat4d vp = d.viewProjection();
            Mat4d jittered = d.jitteredProjection(0.25, -0.25, 800, 600).mul(d.view());
            assertEquals(vp.transformProject(world).x() + 0.5 / 800, jittered.transformProject(world).x(), 1e-9);
            assertTrue(d.reprojection(vp).transformProject(ndc).sub(ndc).length() < 1e-9, "the same camera reprojects to itself");
            assertEquals(d, d.withPosition(d.position()).withOrientation(d.orientation()).withAspect(d.aspect()).zoomed(1.0).lookAt(target, Vec3d.UNIT_Y), "an unchanged camera");
            assertEquals(0.5, d.zoomed(2.0).width() / d.width(), 1e-9);
            assertEquals(0.5, OrthoCamerad.forViewport(eye, 1000, 500, 0.5, 0.1, 10.0, depth).pixelSize(500), 1e-12);
            assertEquals(1.0, OrthoCamerad.of(eye, d.orientation(), 4.0, 2.0, 0.0, 1.0, depth).zoomed(1.0).height() / 4.0, 1e-12);
            assertEquals(f.near(), d.toFloat().near(), 1e-6f);
            assertEquals(Vec3f.ZERO, d.cameraRelative().position());
            assertTrue(d.toFloat().toDouble().position().sub(d.position()).length() < 1e-5, "a float round trip loses only float digits");
        }
        Vec3d p = Vec3d.ZERO;
        Quatd q = Quatd.IDENTITY;
        DepthRange z = DepthRange.ZERO_TO_ONE;
        assertThrows(IllegalArgumentException.class, () -> new OrthoCamerad(p, q, 1.0, 1.0, -1.0, 1.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> new OrthoCamerad(p, q, -1.0, 1.0, 1.0, -1.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> new OrthoCamerad(p, q, -1.0, 1.0, -1.0, 1.0, 1.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> new OrthoCamerad(p, q, Double.NaN, 1.0, -1.0, 1.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.of(p, q, -1.0, 1.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.of(p, q, 1.0, 0.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.forViewport(p, 10, 0, 1.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.forViewport(p, 10, 10, -1.0, 0.0, 1.0, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.of(p, q, 1.0, 1.0, 0.0, 1.0, z).zoomed(-1.0));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.of(p, q, 1.0, 1.0, 0.0, 1.0, z).withAspect(0.0));
        assertThrows(IllegalArgumentException.class, () -> OrthoCamerad.of(p, q, 1.0, 1.0, 0.0, 1.0, z).pixelSize(0));
        assertThrows(NullPointerException.class, () -> new OrthoCameraf(null, Quatf.IDENTITY, -1f, 1f, -1f, 1f, 0f, 1f, DepthRange.ZERO_TO_ONE));
        assertThrows(NullPointerException.class, () -> new OrthoCameraf(Vec3f.ZERO, null, -1f, 1f, -1f, 1f, 0f, 1f, DepthRange.ZERO_TO_ONE));
        assertThrows(NullPointerException.class, () -> new OrthoCameraf(Vec3f.ZERO, Quatf.IDENTITY, -1f, 1f, -1f, 1f, 0f, 1f, null));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p.toFloat(), Quatf.IDENTITY, Float.NaN, 1f, 0f, 1f, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p.toFloat(), Quatf.IDENTITY, 1f, Float.POSITIVE_INFINITY, 0f, 1f, z));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p.toFloat(), Quatf.IDENTITY, 1f, 1f, 0f, 1f, z).zoomed(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p.toFloat(), Quatf.IDENTITY, 1f, 1f, 0f, 1f, z).withAspect(Float.NaN));
    }

    @Test
    void badArgumentsAreRefused() {
        Vec3f p = Vec3f.ZERO;
        Quatf q = Quatf.IDENTITY;
        DepthRange d = DepthRange.ZERO_TO_ONE;
        assertThrows(IllegalArgumentException.class, () -> new OrthoCameraf(p, q, 1f, 1f, -1f, 1f, 0f, 1f, d), "empty width");
        assertThrows(IllegalArgumentException.class, () -> new OrthoCameraf(p, q, -1f, 1f, 1f, -1f, 0f, 1f, d), "negative height");
        assertThrows(IllegalArgumentException.class, () -> new OrthoCameraf(p, q, -1f, 1f, -1f, 1f, 1f, 1f, d), "far equal to near");
        assertThrows(IllegalArgumentException.class, () -> new OrthoCameraf(p, q, -1f, 1f, -1f, 1f, 0f, Float.POSITIVE_INFINITY, d), "there is no infinite orthographic projection");
        assertThrows(IllegalArgumentException.class, () -> new OrthoCameraf(p, q, Float.NaN, 1f, -1f, 1f, 0f, 1f, d));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p, q, 0f, 1f, 0f, 1f, d));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p, q, 1f, 0f, 0f, 1f, d));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.forViewport(p, 0, 10, 1f, 0f, 1f, d));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.forViewport(p, 10, 10, 0f, 0f, 1f, d));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p, q, 1f, 1f, 0f, 1f, d).zoomed(0f));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p, q, 1f, 1f, 0f, 1f, d).withAspect(-1f));
        assertThrows(IllegalArgumentException.class, () -> OrthoCameraf.of(p, q, 1f, 1f, 0f, 1f, d).pixelSize(0));
        OrthoCameraf ok = new OrthoCameraf(p, q, -1f, 1f, -1f, 1f, -2f, 1f, d);   // a near plane behind the camera is allowed
        assertEquals(-2f, ok.near());
    }
}
