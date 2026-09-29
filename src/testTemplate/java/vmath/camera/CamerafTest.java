package vmath.camera;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Quatf;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.core.Vec4f;
import vmath.geo.DepthRange;
import vmath.geo.Rayf;

@GenerateDouble
class CamerafTest {
    @Eps(d = 1e-9)
    static final float EPS = 1e-3f;

    final Rnd rnd = Rnd.create();

    private Cameraf randomCamera(DepthRange depth, boolean infinite) {
        float near = (float) rnd.range(0.1, 1);
        float far = infinite || depth == DepthRange.REVERSED_ZERO_TO_ONE ? Float.POSITIVE_INFINITY : near * (float) rnd.range(50, 400);
        return new Cameraf(rnd.nextVec3f(), rnd.nextUnitQuatf(), (float) rnd.range(0.5, 1.8), (float) rnd.range(0.7, 2.2), near, far, depth);
    }

    /** A point at view distance d straight ahead plus an offset that stays inside the view. */
    private Vec3f pointInView(Cameraf c, float d) {
        float t = (float) Math.tan(c.fovy() * 0.5f);
        float x = (float) rnd.range(-0.9, 0.9) * d * t * c.aspect();
        float y = (float) rnd.range(-0.9, 0.9) * d * t;
        return c.position().add(c.forward().mul(d)).add(c.right().mul(x)).add(c.up().mul(y));
    }

    private float viewDistance(Cameraf c, Vec3f p) {
        return p.sub(c.position()).dot(c.forward());
    }

    private static final DepthRange[] ALL = DepthRange.values();

    // ------------------------------------------------------------ axes and matrices

    @Test
    void axesAreOrthonormalAndLookDownMinusZ() {
        for (int i = 0; i < N; i++) {
            Cameraf c = randomCamera(ALL[i % 3], false);
            close(c.forward().length(), 1.0, EPS, i);
            close(c.forward().dot(c.right()), 0.0, EPS, i);
            close(c.forward().dot(c.up()), 0.0, EPS, i);
            close(c.right().cross(c.up()), c.forward().negate(), EPS, i); // right-handed: x cross y = +z = -forward
        }
        Cameraf identity = new Cameraf(Vec3f.ZERO, Quatf.IDENTITY, 1f, 1f, 0.1f, 10f, DepthRange.ZERO_TO_ONE);
        close(identity.forward(), new Vec3f(0f, 0f, -1f), EPS, 0);
        close(identity.right(), Vec3f.UNIT_X, EPS, 0);
        close(identity.up(), Vec3f.UNIT_Y, EPS, 0);
    }

    @Test
    void viewMatrixMovesTheCameraToTheOriginLookingDownMinusZ() {
        for (int i = 0; i < N; i++) {
            Cameraf c = randomCamera(ALL[i % 3], false);
            Mat4f view = c.view();
            close(view.transformPosition(c.position()), Vec3f.ZERO, EPS * 10, i);
            close(view.transformDirection(c.forward()), new Vec3f(0f, 0f, -1f), EPS, i);
            close(view.transformDirection(c.up()), Vec3f.UNIT_Y, EPS, i);
            float d = (float) rnd.range(1, 20);
            close(view.transformPosition(c.position().add(c.forward().mul(d))), new Vec3f(0f, 0f, -d), EPS * 10, i);
            // the same matrix Mat4f.lookAt builds for this pose
            close(view, Mat4f.lookAt(c.position(), c.position().add(c.forward()), c.up()), EPS * 10, i);
        }
    }

    @Test
    void lookingAtAimsTheCamera() {
        for (int i = 0; i < N; i++) {
            Vec3f pos = rnd.nextVec3f(), target = rnd.nextVec3f();
            if (pos.distance(target) < 1f) {
                continue;
            }
            Cameraf c = Cameraf.lookingAt(pos, target, Vec3f.UNIT_Y, 1f, 1.5f, 0.1f, 100f, DepthRange.ZERO_TO_ONE);
            if (Math.abs(target.sub(pos).normalize().y()) > 0.98f) {
                continue; // nearly straight up or down: roll is arbitrary
            }
            close(c.forward(), target.sub(pos).normalize(), EPS, i);
            close(c.up().dot(Vec3f.UNIT_Y) > 0f ? 1.0 : 0.0, 1.0, EPS, i);
            Cameraf moved = c.withPosition(Vec3f.ZERO).lookAt(target, Vec3f.UNIT_Y);
            close(moved.forward(), target.normalize(), EPS, i);
            check(c.withAspect(2f).aspect() == 2f && c.withOrientation(Quatf.IDENTITY).orientation().equals(Quatf.IDENTITY), i, "withers");
        }
    }

    // ------------------------------------------------------------ projecting

    @Test
    void projectAndUnprojectRoundTripInEveryDepthConvention() {
        for (int i = 0; i < N * 3; i++) {
            DepthRange depth = ALL[i % 3];
            Cameraf c = randomCamera(depth, i % 7 == 0);
            float d = c.near() * (float) rnd.range(1.5, 40);
            Vec3f p = pointInView(c, d);
            Vec3f ndc = c.project(p);
            check(Math.abs(ndc.x()) <= 1f && Math.abs(ndc.y()) <= 1f, i, "point built inside the view projects inside: " + ndc);
            close(c.unproject(ndc), p, EPS * 10 * d, i);
            // the depth value is in the convention's range
            if (depth == DepthRange.NEGATIVE_ONE_TO_ONE) {
                check(ndc.z() >= -1f - EPS && ndc.z() <= 1f + EPS, i, "GL depth in [-1, 1]: " + ndc.z());
            } else {
                check(ndc.z() >= -EPS && ndc.z() <= 1f + EPS, i, depth + " depth in [0, 1]: " + ndc.z());
            }
        }
    }

    @Test
    void depthMapsNearAndFarAccordingToTheConvention() {
        Quatf q = Quatf.IDENTITY;
        Cameraf gl = new Cameraf(Vec3f.ZERO, q, 1f, 1f, 0.5f, 50f, DepthRange.NEGATIVE_ONE_TO_ONE);
        Cameraf zo = new Cameraf(Vec3f.ZERO, q, 1f, 1f, 0.5f, 50f, DepthRange.ZERO_TO_ONE);
        Cameraf rv = new Cameraf(Vec3f.ZERO, q, 1f, 1f, 0.5f, Float.POSITIVE_INFINITY, DepthRange.REVERSED_ZERO_TO_ONE);
        Vec3f atNear = new Vec3f(0f, 0f, -0.5f), atFar = new Vec3f(0f, 0f, -50f);
        close(gl.project(atNear).z(), -1.0, EPS, 0);
        close(gl.project(atFar).z(), 1.0, EPS, 0);
        close(zo.project(atNear).z(), 0.0, EPS, 0);
        close(zo.project(atFar).z(), 1.0, EPS, 0);
        close(rv.project(atNear).z(), 1.0, EPS, 0);
        check(rv.project(new Vec3f(0f, 0f, -1e5f)).z() < 1e-4f, 0, "reversed-Z: far away tends to 0");
    }

    @Test
    void toScreenUsesATopLeftOriginAndPickRayInvertsIt() {
        int w = 1280, h = 720;
        for (int i = 0; i < N; i++) {
            Cameraf c = randomCamera(ALL[i % 3], false).withAspect((float) w / h);
            float d = c.near() * (float) rnd.range(2, 30);
            Vec3f p = pointInView(c, d);
            Vec3f s = c.toScreen(p, w, h);
            check(s.x() >= 0f && s.x() <= w && s.y() >= 0f && s.y() <= h, i, "inside the viewport: " + s);
            Rayf ray = c.pickRay(s.x(), s.y(), w, h);
            close(ray.origin(), c.position(), 0.0, i);
            close(ray.direction().length(), 1.0, EPS, i);
            close(ray.direction(), p.sub(c.position()).normalize(), EPS * 10, i);
        }
        // the centre of the screen looks straight ahead; the top of the screen looks up
        Cameraf c = new Cameraf(Vec3f.ZERO, Quatf.IDENTITY, 1f, 1f, 0.1f, 10f, DepthRange.ZERO_TO_ONE);
        close(c.toScreen(new Vec3f(0f, 0f, -2f), w, h).x(), w / 2.0, EPS, 0);
        close(c.toScreen(new Vec3f(0f, 0f, -2f), w, h).y(), h / 2.0, EPS, 0);
        close(c.pickRay(w / 2f, h / 2f, w, h).direction(), new Vec3f(0f, 0f, -1f), EPS, 0);
        check(c.pickRay(w / 2f, 0f, w, h).direction().y() > 0f, 0, "y grows downward on screen, so row 0 looks up");
        check(c.toScreen(new Vec3f(0f, 1f, -5f), w, h).y() < h / 2f, 0, "a point above the axis is in the upper half");
        check(c.toScreen(new Vec3f(1f, 0f, -5f), w, h).x() > w / 2f, 0, "a point to the right is in the right half");
    }

    // ------------------------------------------------------------ depth utilities

    @Test
    void linearizeDepthInvertsTheProjection() {
        for (int i = 0; i < N * 3; i++) {
            DepthRange depth = ALL[i % 3];
            Cameraf c = randomCamera(depth, i % 5 == 0);
            float d = c.near() * (float) rnd.range(1.2, 40);
            Vec3f p = pointInView(c, d);
            float viewDist = viewDistance(c, p);
            close(c.linearizeDepth(c.project(p).z()), viewDist, EPS * 10 * viewDist, i);
        }
    }

    @Test
    void depthReconstructionGivesBackTheViewAndWorldPosition() {
        for (int i = 0; i < N * 3; i++) {
            DepthRange depth = ALL[i % 3];
            Cameraf c = randomCamera(depth, false);
            float d = c.near() * (float) rnd.range(1.5, 30);
            Vec3f p = pointInView(c, d);
            Vec3f ndc = c.project(p);
            float tol = EPS * 10 * d;
            close(c.viewPositionFromDepth(ndc.x(), ndc.y(), ndc.z()), c.view().transformPosition(p), tol, i);
            close(c.worldPositionFromDepth(ndc.x(), ndc.y(), ndc.z()), p, tol, i);
        }
    }

    // ------------------------------------------------------------ frustum

    @Test
    void frustumAgreesWithTheProjection() {
        int inside = 0, outside = 0;
        for (int i = 0; i < N * 3; i++) {
            DepthRange depth = ALL[i % 3];
            Cameraf c = randomCamera(depth, i % 4 == 0);
            Vec3f p = c.position().add(rnd.nextVec3f().mul(2f));
            Vec4f clip = c.toClip(p);
            if (!(clip.w() > 1e-3f)) {
                check(!c.frustum().contains(p) || clip.w() <= 1e-3f, i, "a point behind the camera is never visible");
                continue;
            }
            Vec3f ndc = new Vec3f(clip.x() / clip.w(), clip.y() / clip.w(), clip.z() / clip.w());
            float zLo = depth == DepthRange.NEGATIVE_ONE_TO_ONE ? -1f : 0f;
            float margin = Math.min(Math.min(1f - Math.abs(ndc.x()), 1f - Math.abs(ndc.y())),
                    Math.min(ndc.z() - zLo, 1f - ndc.z()));
            boolean farIrrelevant = Float.isInfinite(c.far());
            if (Math.abs(margin) < 1e-2f && !farIrrelevant) {
                continue;
            }
            boolean expected = Math.abs(ndc.x()) <= 1f && Math.abs(ndc.y()) <= 1f && ndc.z() >= zLo && (farIrrelevant || ndc.z() <= 1f);
            if (farIrrelevant && (Math.abs(1f - Math.abs(ndc.x())) < 1e-2f || Math.abs(1f - Math.abs(ndc.y())) < 1e-2f
                    || Math.abs(ndc.z() - zLo) < 1e-2f)) {
                continue;
            }
            check(c.frustum().contains(p) == expected, i, depth + " contains vs projection, ndc " + ndc);
            if (expected) {
                inside++;
            } else {
                outside++;
            }
        }
        check(inside > 50 && outside > 50, 0, "need both kinds of samples: " + inside + " inside, " + outside + " outside");
    }

    // ------------------------------------------------------------ temporal jitter and reprojection

    @Test
    void jitterShiftsEveryPointByTheSameNdcOffset() {
        int w = 1920, h = 1080;
        for (int i = 0; i < N; i++) {
            Cameraf c = randomCamera(ALL[i % 3], i % 5 == 0);
            float jx = (float) rnd.range(-0.5, 0.5), jy = (float) rnd.range(-0.5, 0.5);
            Vec3f p = pointInView(c, c.near() * (float) rnd.range(1.5, 30));
            Vec3f base = c.project(p);
            Vec3f jittered = c.jitteredProjection(jx, jy, w, h).mul(c.view()).transformProject(p);
            close(jittered.x(), base.x() + 2f * jx / w, EPS * 0.1, i);
            close(jittered.y(), base.y() + 2f * jy / h, EPS * 0.1, i);
            close(jittered.z(), base.z(), EPS * 0.1, i);
        }
        Cameraf c = randomCamera(DepthRange.ZERO_TO_ONE, false);
        close(c.jitteredProjection(0f, 0f, 100, 100), c.projection(), 0.0, 0);
    }

    @Test
    void reprojectionMapsCurrentClipToPreviousClip() {
        for (int i = 0; i < N; i++) {
            DepthRange depth = ALL[i % 3];
            Cameraf now = randomCamera(depth, false);
            Cameraf before = now.withPosition(now.position().add(rnd.nextVec3f().mul(0.1f)))
                    .withOrientation(now.orientation().mul(Quatf.fromAxisAngle((float) rnd.range(-0.1, 0.1), rnd.nextVec3f())));
            Vec3f p = pointInView(now, now.near() * (float) rnd.range(2, 20));
            Vec4f clipNow = now.toClip(p);
            Vec4f mapped = now.reprojection(before.viewProjection()).transform(clipNow);
            Vec4f expected = before.toClip(p);
            double tol = EPS * 10 * Math.max(1.0, Math.abs(expected.w()));
            close(mapped.x(), expected.x(), tol, i);
            close(mapped.y(), expected.y(), tol, i);
            close(mapped.z(), expected.z(), tol, i);
            close(mapped.w(), expected.w(), tol, i);
        }
    }

    // ------------------------------------------------------------ validation

    @Test
    void rejectsInvalidParameters() {
        Vec3f o = Vec3f.ZERO;
        Quatf q = Quatf.IDENTITY;
        DepthRange d = DepthRange.ZERO_TO_ONE;
        for (Runnable bad : new Runnable[] {
                () -> new Cameraf(o, q, 0f, 1f, 0.1f, 10f, d),
                () -> new Cameraf(o, q, 3.2f, 1f, 0.1f, 10f, d),
                () -> new Cameraf(o, q, 1f, 0f, 0.1f, 10f, d),
                () -> new Cameraf(o, q, 1f, 1f, 0f, 10f, d),
                () -> new Cameraf(o, q, 1f, 1f, 5f, 5f, d),
                () -> new Cameraf(o, q, Float.NaN, 1f, 0.1f, 10f, d)}) {
            boolean threw = false;
            try {
                bad.run();
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, 0, "invalid camera parameters must be rejected");
        }
    }
}
