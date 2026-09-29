package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vmath.core.Quatd;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3d;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;

/** Camera-relative rendering: why the double camera exists and what {@code cameraRelative()} buys. */
class CameraPrecisionTest {

    final Rnd rnd = Rnd.create();

    @Test
    void cameraRelativeProjectionKeepsPrecisionFarFromTheOrigin() {
        // an Earth-scale world: 6.4 million units from the origin, where float spacing is 0.5
        Vec3d origin = new Vec3d(6_378_137.0, 12_345.0, -87_654.0);
        int checked = 0;
        double sumRelative = 0, sumNaive = 0;
        for (int i = 0; i < 500; i++) {
            Quatd q = rnd.nextUnitQuatd();
            Camerad cam = new Camerad(origin, q, 1.0, 1.6, 0.5, 5_000.0, DepthRange.ZERO_TO_ONE);
            double d = rnd.range(5, 200);
            Vec3d forward = q.transform(new Vec3d(0, 0, -1));
            Vec3d p = origin.add(forward.mul(d)).add(q.transform(new Vec3d(rnd.range(-3, 3), rnd.range(-2, 2), 0)));
            Vec3d exact = cam.project(p);
            if (Math.abs(exact.x()) > 0.9 || Math.abs(exact.y()) > 0.9) {
                continue;
            }
            checked++;

            // camera-relative: subtract in double, then everything is small enough for float
            Cameraf relative = cam.cameraRelative();
            Vec3f local = p.relativeTo(cam.position());
            Vec3f ndc = relative.project(local);
            assertEquals(exact.x(), ndc.x(), 2e-3, "relative x");
            assertEquals(exact.y(), ndc.y(), 2e-3, "relative y");
            assertEquals(exact.z(), ndc.z(), 5e-3 * Math.max(1e-3, exact.z()) + 1e-4, "relative depth");

            // naive: narrow the world coordinates to float first. The quantization (0.5 here) ruins it.
            Cameraf naive = cam.toFloat();
            Vec3f ndcNaive = naive.project(p.toFloat());
            sumRelative += Math.hypot(exact.x() - ndc.x(), exact.y() - ndc.y());
            sumNaive += Math.hypot(exact.x() - ndcNaive.x(), exact.y() - ndcNaive.y());
        }
        assertTrue(sumNaive > 30 * sumRelative,
                "on average the naive float error (" + sumNaive / checked + ") dwarfs the camera-relative one (" + sumRelative / checked + ")");
        assertTrue(sumNaive / checked > 1e-3, "the naive error is visible: " + sumNaive / checked + " NDC units");
        assertTrue(checked > 100, "enough samples inside the view: " + checked);
    }

    @Test
    void doubleCameraMatchesFloatCameraNearTheOrigin() {
        for (int i = 0; i < 200; i++) {
            Quatf q = rnd.nextUnitQuatf();
            Cameraf f = new Cameraf(rnd.nextVec3f(), q, 1f, 1.5f, 0.2f, 100f, DepthRange.NEGATIVE_ONE_TO_ONE);
            Camerad d = f.toDouble();
            Vec3f p = f.position().add(f.forward().mul(8f));
            Vec3f a = f.project(p);
            Vec3d b = d.project(p.toDouble());
            assertEquals(a.x(), b.x(), 1e-4);
            assertEquals(a.y(), b.y(), 1e-4);
            assertEquals(a.z(), b.z(), 1e-4);
            assertEquals(d.toFloat().fovy(), f.fovy());
        }
    }

    @Test
    void cameraRelativeSitsAtTheOriginWithTheSameOrientation() {
        Camerad cam = new Camerad(new Vec3d(1e7, 2e7, 3e7), rnd.nextUnitQuatd(), 1.0, 1.0, 1.0, 100.0, DepthRange.REVERSED_ZERO_TO_ONE);
        Cameraf rel = cam.cameraRelative();
        assertEquals(Vec3f.ZERO, rel.position());
        assertEquals(cam.forward().x(), rel.forward().x(), 1e-6);
        assertEquals(cam.up().y(), rel.up().y(), 1e-6);
        assertEquals(DepthRange.REVERSED_ZERO_TO_ONE, rel.depth());
    }
}
