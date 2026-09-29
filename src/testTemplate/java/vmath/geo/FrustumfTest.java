package vmath.geo;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.core.Mat4f;
import vmath.core.Rnd;
import vmath.core.Vec3f;
import vmath.core.Vec4f;

/**
 * Frustum extraction and culling. The oracle works in clip space (a point is visible iff the clip-space inequalities
 * hold), so it shares no code with the plane extraction it checks.
 */
@GenerateDouble
class FrustumfTest {
    @Eps(d = 1e-9)
    static final float EPS = 1e-3f;

    final Rnd rnd = Rnd.create();

    /** A random camera: {viewProjection, depth convention}. */
    private Mat4f randomViewProjection(DepthRange[] outDepth) {
        float fovy = (float) rnd.range(0.4, 2.0);
        float aspect = (float) rnd.range(0.6, 2.5);
        float near = (float) rnd.range(0.1, 2);
        float far = near * (float) rnd.range(20, 500);
        Vec3f eye = rnd.nextVec3f();
        Vec3f dir = rnd.nextVec3f();
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), Math.abs(dir.normalize().y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y);
        switch ((int) rnd.range(0, 4)) {
            case 0 -> {
                outDepth[0] = DepthRange.NEGATIVE_ONE_TO_ONE;
                return Mat4f.perspective(fovy, aspect, near, far, false).mul(view);
            }
            case 1 -> {
                outDepth[0] = DepthRange.ZERO_TO_ONE;
                return Mat4f.perspective(fovy, aspect, near, far, true).mul(view);
            }
            case 2 -> {
                outDepth[0] = DepthRange.REVERSED_ZERO_TO_ONE;
                return Mat4f.perspectiveReversedZ(fovy, aspect, near).mul(view);
            }
            default -> {
                outDepth[0] = DepthRange.NEGATIVE_ONE_TO_ONE;
                float s = (float) rnd.range(1, 10);
                return Mat4f.ortho(-s * aspect, s * aspect, -s, s, near, far, false).mul(view);
            }
        }
    }

    /** Clip-space slack of the six planes (left, right, bottom, top, near, far); non-negative means inside. */
    private static float[] slacks(Mat4f vp, Vec3f p, DepthRange depth) {
        Vec4f c = vp.transform(Vec4f.point(p));
        float w = c.w(), x = c.x(), y = c.y(), z = c.z();
        return switch (depth) {
            case NEGATIVE_ONE_TO_ONE -> new float[] {w + x, w - x, w + y, w - y, w + z, w - z};
            case ZERO_TO_ONE -> new float[] {w + x, w - x, w + y, w - y, z, w - z};
            case REVERSED_ZERO_TO_ONE -> new float[] {w + x, w - x, w + y, w - y, w - z, z};
        };
    }

    private static boolean ambiguous(float[] slack, float tol) {
        for (float s : slack) {
            if (Math.abs(s) < tol) {
                return true;
            }
        }
        return false;
    }

    @Test
    void containsMatchesClipSpace() {
        int inside = 0;
        for (int i = 0; i < N * 2; i++) {
            DepthRange[] depth = new DepthRange[1];
            Mat4f vp = randomViewProjection(depth);
            Frustumf f = Frustumf.fromViewProjection(vp, depth[0]);
            // sample points around the camera so that a good share land inside
            Vec3f p = rnd.nextVec3f().mul(3f);
            float[] slack = slacks(vp, p, depth[0]);
            if (ambiguous(slack, 1e-2f)) {
                continue;
            }
            boolean expected = true;
            for (float s : slack) {
                expected &= s >= 0f;
            }
            check(f.contains(p) == expected, i, "contains vs clip space for " + depth[0]);
            if (expected) {
                inside++;
            }
        }
        check(inside > N / 20, 0, "too few inside samples: " + inside);
    }

    @Test
    void boxClassificationMatchesCornerOracle() {
        int outside = 0, inside = 0, straddling = 0;
        for (int i = 0; i < N * 2; i++) {
            DepthRange[] depth = new DepthRange[1];
            Mat4f vp = randomViewProjection(depth);
            Frustumf f = Frustumf.fromViewProjection(vp, depth[0]);
            Vec3f center = rnd.nextVec3f().mul(2f);
            Vec3f half = rnd.nextScaleVec3f().mul((float) rnd.range(0.05, 1));
            Aabbf box = Aabbf.fromCenterHalfExtent(center, half);
            float[][] corner = new float[8][];
            boolean skip = false;
            for (int c = 0; c < 8; c++) {
                corner[c] = slacks(vp, box.corner(c), depth[0]);
                skip |= ambiguous(corner[c], 2e-2f);
            }
            if (skip) {
                continue;
            }
            boolean anyPlaneRejects = false;
            boolean allInside = true;
            for (int plane = 0; plane < 6; plane++) {
                boolean allBehind = true;
                for (int c = 0; c < 8; c++) {
                    boolean behind = corner[c][plane] < 0f;
                    allBehind &= behind;
                    allInside &= !behind;
                }
                anyPlaneRejects |= allBehind;
            }
            int cls = f.classify(box);
            if (anyPlaneRejects) {
                check(cls == Containment.OUTSIDE, i, "box rejected by a plane must be OUTSIDE, got " + cls);
                check(!f.intersects(box), i, "intersects agrees");
                outside++;
            } else if (allInside) {
                check(cls == Containment.INSIDE, i, "box with all corners inside must be INSIDE, got " + cls);
                check(f.intersects(box), i, "intersects agrees");
                inside++;
            } else {
                check(cls != Containment.OUTSIDE, i, "conservative: never OUTSIDE for a box that may be visible");
                check(f.intersects(box), i, "intersects agrees");
                straddling++;
            }
        }
        check(outside > 20 && inside > 20 && straddling > 20, 0,
                "need all three classes: " + outside + " outside, " + inside + " inside, " + straddling + " straddling");
    }

    @Test
    void noFalseNegativesForSpheresBoxesAndObbs() {
        for (int i = 0; i < N; i++) {
            DepthRange[] depth = new DepthRange[1];
            Mat4f vp = randomViewProjection(depth);
            Frustumf f = Frustumf.fromViewProjection(vp, depth[0]);

            Spheref s = Spheref.of(rnd.nextVec3f().mul(2f), (float) rnd.range(0.05, 2));
            Obbf obb = Obbf.of(rnd.nextVec3f().mul(2f), rnd.nextScaleVec3f().mul(0.5f), rnd.nextUnitQuatf());
            int sphereClass = f.classify(s), obbClass = f.classify(obb);
            for (int k = 0; k < 30; k++) {
                Vec3f onSphere = s.center().add(rnd.nextVec3f().normalize().mul(s.radius() * (float) rnd.range(0, 1)));
                boolean visible = f.contains(onSphere);
                if (visible) {
                    check(sphereClass != Containment.OUTSIDE, i, "a visible sphere point means not OUTSIDE");
                }
                if (sphereClass == Containment.INSIDE) {
                    check(visible, i, "INSIDE sphere: all points visible");
                }
                Vec3f local = new Vec3f((float) rnd.range(-obb.hx(), obb.hx()), (float) rnd.range(-obb.hy(), obb.hy()),
                        (float) rnd.range(-obb.hz(), obb.hz()));
                Vec3f inObb = obb.rotation().transform(local).add(obb.center());
                if (f.contains(inObb)) {
                    check(obbClass != Containment.OUTSIDE, i, "a visible OBB point means not OUTSIDE");
                }
                if (obbClass == Containment.INSIDE) {
                    check(f.contains(inObb), i, "INSIDE OBB: all points visible");
                }
            }
            check(f.intersects(s) == (sphereClass != Containment.OUTSIDE), i, "intersects(sphere) agrees");
            check(f.intersects(obb) == (obbClass != Containment.OUTSIDE), i, "intersects(obb) agrees");
        }
    }

    @Test
    void reversedInfiniteProjectionHasADegenerateFarPlane() {
        Mat4f vp = Mat4f.perspectiveReversedZ(1.0f, 1.5f, 0.1f);
        Frustumf f = Frustumf.fromViewProjection(vp, DepthRange.REVERSED_ZERO_TO_ONE);
        check(f.far().nx() == 0f && f.far().ny() == 0f && f.far().nz() == 0f, 0, "far normal is zero: " + f.far());
        check(f.far().d() > 0f, 0, "far offset is positive so every point satisfies it: " + f.far());
        check(f.contains(new Vec3f(0f, 0f, -1e6f)), 0, "a point very far ahead is inside");
        check(!f.contains(new Vec3f(0f, 0f, 1f)), 0, "a point behind the camera is outside");
        check(!f.contains(new Vec3f(0f, 0f, -0.05f)), 0, "a point nearer than the near plane is outside");
    }

    @Test
    void writeToLayoutIsPlaneMajor() {
        DepthRange[] depth = new DepthRange[1];
        Frustumf f = Frustumf.fromViewProjection(randomViewProjection(depth), depth[0]);
        float[] out = new float[2 + 24];
        f.writeTo(out, 2);
        for (int i = 0; i < 6; i++) {
            Planef p = f.plane(i);
            check(out[2 + i * 4] == p.nx() && out[3 + i * 4] == p.ny() && out[4 + i * 4] == p.nz()
                    && out[5 + i * 4] == p.d(), i, "plane " + i);
        }
    }
}
