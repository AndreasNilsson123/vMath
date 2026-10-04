package vmath.core;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Check.close;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

/** The {@link ClipSpace} projection builders: depth range and Y direction per graphics API, and agreement with the older boolean overloads. */
@GenerateDouble
@SuppressWarnings("deprecation") // it checks the boolean overloads against the ClipSpace ones
class ClipSpacefTest {
    @Eps(d = 1e-10)
    static final float EPS = 5e-4f;

    final Rnd rnd = Rnd.create();

    /** Normalised device coordinates of a view-space point. */
    private static Vec3f ndc(Mat4f m, Vec3f viewPoint) {
        Vec4f c = m.transform(Vec4f.point(viewPoint));
        return new Vec3f(c.x() / c.w(), c.y() / c.w(), c.z() / c.w());
    }

    @Test
    void theConventionsAreWhatTheApisSay() {
        check(!ClipSpace.OPENGL.zeroToOne() && !ClipSpace.OPENGL.yDown(), 0, "OpenGL: depth -1..1, y up");
        check(ClipSpace.VULKAN.zeroToOne() && ClipSpace.VULKAN.yDown(), 0, "Vulkan: depth 0..1, y down");
        check(ClipSpace.D3D.zeroToOne() && !ClipSpace.D3D.yDown(), 0, "D3D: depth 0..1, y up");
        check(DepthRange.of(ClipSpace.OPENGL) == DepthRange.NEGATIVE_ONE_TO_ONE, 0, "GL depth range");
        check(DepthRange.of(ClipSpace.VULKAN) == DepthRange.ZERO_TO_ONE, 0, "Vulkan depth range");
        check(DepthRange.of(ClipSpace.D3D) == DepthRange.ZERO_TO_ONE, 0, "D3D depth range");
    }

    @Test
    void nearAndFarMapToTheDepthRangeAndYPointsTheRightWay() {
        for (int i = 0; i < N; i++) {
            float fovy = (float) rnd.range(0.4, 2.0);
            float aspect = (float) rnd.range(0.6, 2.5);
            float near = (float) rnd.range(0.05, 2);
            float far = near * (float) rnd.range(20, 500);
            float depth = (float) rnd.range(near * 1.5, far * 0.9);
            float up = (float) rnd.range(0.01, 0.5) * depth * (float) Math.tan(fovy * 0.5f);
            for (ClipSpace space : ClipSpace.values()) {
                Mat4f m = Mat4f.perspective(fovy, aspect, near, far, space);
                close(ndc(m, new Vec3f(0f, 0f, -near)).z(), space.zeroToOne() ? 0.0 : -1.0, EPS, i);
                close(ndc(m, new Vec3f(0f, 0f, -far)).z(), 1.0, EPS, i);
                Vec3f above = ndc(m, new Vec3f(0f, up, -depth));
                check(space.yDown() ? above.y() < 0f : above.y() > 0f, i, space + ": a point above the axis must have " + (space.yDown() ? "negative" : "positive") + " y, was " + above.y());
                check(ndc(m, new Vec3f(up, 0f, -depth)).x() > 0f, i, space + ": x is never flipped");
                // the flip changes only the sign of y
                Mat4f gl = Mat4f.perspective(fovy, aspect, near, far, false);
                close(Math.abs(above.y()), Math.abs(ndc(gl, new Vec3f(0f, up, -depth)).y()), EPS, i);
            }
        }
    }

    @Test
    void theClipSpaceOverloadsAgreeWithTheBooleanOnesPlusTheFlip() {
        for (int i = 0; i < N; i++) {
            float fovy = (float) rnd.range(0.4, 2.0);
            float aspect = (float) rnd.range(0.6, 2.5);
            float near = (float) rnd.range(0.05, 2);
            float far = near * (float) rnd.range(20, 500);
            close(Mat4f.perspective(fovy, aspect, near, far, ClipSpace.OPENGL), Mat4f.perspective(fovy, aspect, near, far, false), EPS, i);
            close(Mat4f.perspective(fovy, aspect, near, far, ClipSpace.D3D), Mat4f.perspective(fovy, aspect, near, far, true), EPS, i);
            close(Mat4f.perspective(fovy, aspect, near, far, ClipSpace.VULKAN), Mat4f.perspective(fovy, aspect, near, far, true).flipY(), EPS, i);
            close(Mat4f.perspectiveInfinite(fovy, aspect, near, ClipSpace.VULKAN), Mat4f.perspectiveInfinite(fovy, aspect, near, true).flipY(), EPS, i);
            close(Mat4f.perspectiveReversedZ(fovy, aspect, near, ClipSpace.D3D), Mat4f.perspectiveReversedZ(fovy, aspect, near), EPS, i);
            close(Mat4f.perspectiveReversedZ(fovy, aspect, near, ClipSpace.VULKAN), Mat4f.perspectiveReversedZ(fovy, aspect, near).flipY(), EPS, i);
            float l = (float) rnd.range(-3, -0.5), r = (float) rnd.range(0.5, 3), b = (float) rnd.range(-3, -0.5), t = (float) rnd.range(0.5, 3);
            close(Mat4f.ortho(l, r, b, t, near, far, ClipSpace.VULKAN), Mat4f.ortho(l, r, b, t, near, far, true).flipY(), EPS, i);
            close(Mat4f.ortho(l, r, b, t, near, far, ClipSpace.OPENGL), Mat4f.ortho(l, r, b, t, near, far, false), EPS, i);
            close(Mat4f.frustum(l, r, b, t, near, far, ClipSpace.VULKAN), Mat4f.frustum(l, r, b, t, near, far, true).flipY(), EPS, i);
            // a symmetric frustum is the symmetric perspective, in every convention
            float h = near * (float) Math.tan(fovy * 0.5f), w = h * aspect;
            for (ClipSpace space : ClipSpace.values()) {
                close(Mat4f.frustum(-w, w, -h, h, near, far, space), Mat4f.perspective(fovy, aspect, near, far, space), EPS * 4, i);
            }
        }
    }

    @Test
    void flipYNegatesExactlyRowOneAndIsItsOwnInverse() {
        for (int i = 0; i < N; i++) {
            Mat4f m = rnd.nextDenseMat4f();
            Mat4f f = m.flipY();
            check(f.flipY().equals(m), i, "flipping twice gives the original");
            check(f.m00() == m.m00() && f.m02() == m.m02() && f.m03() == m.m03(), i, "rows 0, 2, 3 are untouched (column 0)");
            check(f.m01() == -m.m01() && f.m11() == -m.m11() && f.m21() == -m.m21() && f.m31() == -m.m31(), i, "row 1 is negated");
            Vec4f v = rnd.nextVec4f();
            Vec4f a = m.transform(v), b = f.transform(v);
            close(b.x(), a.x(), EPS * 10, i);
            close(b.y(), -a.y(), EPS * 10, i);
            close(b.z(), a.z(), EPS * 10, i);
            close(b.w(), a.w(), EPS * 10, i);
        }
    }

    @Test
    void reversedZMapsNearToOneAndFarToZeroAndRejectsOpenGl() {
        for (int i = 0; i < N; i++) {
            float fovy = (float) rnd.range(0.4, 2.0);
            float near = (float) rnd.range(0.05, 2);
            for (ClipSpace space : new ClipSpace[] {ClipSpace.D3D, ClipSpace.VULKAN}) {
                Mat4f m = Mat4f.perspectiveReversedZ(fovy, 1.5f, near, space);
                close(ndc(m, new Vec3f(0f, 0f, -near)).z(), 1.0, EPS, i);
                check(ndc(m, new Vec3f(0f, 0f, -near * 1e4f)).z() < 2e-4f, i, "far away tends to 0");
            }
        }
        boolean threw = false;
        try {
            Mat4f.perspectiveReversedZ(1f, 1f, 0.1f, ClipSpace.OPENGL);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        check(threw, 0, "reversed-Z in OpenGL's [-1, 1] depth range is rejected");
    }

    @Test
    void frustumExtractionWorksForEveryConventionAndAYFlipKeepsTheSamePlanes() {
        for (int i = 0; i < N; i++) {
            float near = (float) rnd.range(0.1, 1);
            float far = near * 100f;
            // the eye sits 2 to 8 units from the origin: beyond the near plane (at most 1) and inside the far plane (at least 10)
            Mat4f view = Mat4f.lookAt(rnd.nextVec3f().normalize().mul((float) rnd.range(2, 8)), Vec3f.ZERO, Vec3f.UNIT_Y);
            Aabbf inFront = Aabbf.of(new Vec3f(-0.05f, -0.05f, -0.05f), new Vec3f(0.05f, 0.05f, 0.05f));
            Aabbf behind = inFront.transform(Mat4f.translation(0f, 0f, 500f));
            for (ClipSpace space : ClipSpace.values()) {
                Mat4f viewProjection = Mat4f.perspective(1.0f, 1.5f, near, far, space).mul(view);
                Frustumf f = Frustumf.fromViewProjection(viewProjection, DepthRange.of(space));
                // the origin is where the view looks
                check(view.transformPosition(Vec3f.ZERO).length() > near && view.transformPosition(Vec3f.ZERO).length() < far, i, "test setup: origin between near and far");
                check(f.intersects(inFront), i, space + ": the box the camera looks at must be inside the frustum");
                check(!f.intersects(behind), i, space + ": a box far away from the origin must be outside");
            }
            Frustumf vulkan = Frustumf.fromViewProjection(Mat4f.perspective(1.0f, 1.5f, near, far, ClipSpace.VULKAN).mul(view), DepthRange.ZERO_TO_ONE);
            Frustumf d3d = Frustumf.fromViewProjection(Mat4f.perspective(1.0f, 1.5f, near, far, ClipSpace.D3D).mul(view), DepthRange.ZERO_TO_ONE);
            close(vulkan.top().nx(), d3d.bottom().nx(), EPS * 10, i); // a Y flip swaps top and bottom: same six planes
            close(vulkan.top().ny(), d3d.bottom().ny(), EPS * 10, i);
            close(vulkan.bottom().d(), d3d.top().d(), EPS * 10, i);
            close(vulkan.near().d(), d3d.near().d(), EPS * 10, i);
        }
    }
}
