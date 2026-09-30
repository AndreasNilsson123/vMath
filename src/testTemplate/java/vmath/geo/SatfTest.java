package vmath.geo;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.core.Mat3f;
import vmath.core.Rnd;
import vmath.core.Vec3f;

/**
 * OBB-OBB and box-triangle overlap. The reference is a separating axis test written differently from the library's: it builds the list of candidate axes
 * generically and projects the actual vertices of both shapes onto each, instead of using the closed-form radii. Cases within a small margin of touching
 * are skipped, because there the two are allowed to differ by the library's epsilon.
 */
@GenerateDouble
class SatfTest {
    @Eps(d = 1e-9)
    static final float EPS = 2e-4f;

    /** Distance below which a pair counts as touching and is not judged. */
    @Eps(d = 1e-6)
    static final float MARGIN = 2e-3f;

    final Rnd rnd = Rnd.create();

    private static Vec3f[] vertices(Obbf b) {
        Vec3f[] v = new Vec3f[8];
        for (int i = 0; i < 8; i++) {
            Vec3f local = new Vec3f((i & 1) == 0 ? -b.hx() : b.hx(), (i & 2) == 0 ? -b.hy() : b.hy(), (i & 4) == 0 ? -b.hz() : b.hz());
            v[i] = b.rotation().transform(local).add(b.center());
        }
        return v;
    }

    private static Vec3f[] vertices(Aabbf b) {
        Vec3f[] v = new Vec3f[8];
        for (int i = 0; i < 8; i++) {
            v[i] = new Vec3f((i & 1) == 0 ? b.minX() : b.maxX(), (i & 2) == 0 ? b.minY() : b.maxY(), (i & 4) == 0 ? b.minZ() : b.maxZ());
        }
        return v;
    }

    /** Signed gap between the shadows of two vertex sets on a unit axis: positive when they do not overlap. */
    private static double gap(Vec3f[] a, Vec3f[] b, Vec3f axis) {
        double aLo = Double.MAX_VALUE, aHi = -Double.MAX_VALUE, bLo = Double.MAX_VALUE, bHi = -Double.MAX_VALUE;
        for (Vec3f p : a) {
            double d = p.dot(axis);
            aLo = Math.min(aLo, d);
            aHi = Math.max(aHi, d);
        }
        for (Vec3f p : b) {
            double d = p.dot(axis);
            bLo = Math.min(bLo, d);
            bHi = Math.max(bHi, d);
        }
        return Math.max(bLo - aHi, aLo - bHi);
    }

    /** The largest gap over the axes, skipping cross products too short to define a direction. */
    private static double largestGap(Vec3f[] a, Vec3f[] b, List<Vec3f> axes) {
        double best = -Double.MAX_VALUE;
        for (Vec3f axis : axes) {
            float len = axis.length();
            if (len > 1e-3f) {
                best = Math.max(best, gap(a, b, axis.mul(1f / len)));
            }
        }
        return best;
    }

    private Obbf randomObb() {
        return Obbf.of(rnd.nextVec3f().mul(0.3f), new Vec3f((float) rnd.range(0.2, 1.5), (float) rnd.range(0.2, 1.5), (float) rnd.range(0.2, 1.5)), rnd.nextUnitQuatf());
    }

    // ------------------------------------------------------------ OBB - OBB

    @Test
    void obbObbAgreesWithTheVertexProjectionReference() {
        int overlapping = 0, separated = 0, judged = 0;
        for (int i = 0; i < N; i++) {
            Obbf a = randomObb(), b = randomObb();
            List<Vec3f> axes = new ArrayList<>();
            Mat3f ra = a.axes(), rb = b.axes();
            boolean nearParallel = false;
            for (int k = 0; k < 3; k++) {
                axes.add(ra.column(k));
                axes.add(rb.column(k));
                for (int m = 0; m < 3; m++) {
                    Vec3f cross = ra.column(k).cross(rb.column(m));
                    nearParallel |= cross.length() < 1e-2f;
                    axes.add(cross);
                }
            }
            if (nearParallel) {
                continue; // edges that are nearly parallel make the cross axes unstable for both implementations
            }
            double g = largestGap(vertices(a), vertices(b), axes);
            boolean got = Intersectionf.obbObb(a, b);
            if (g > MARGIN) {
                check(!got, i, "separated by " + g + " but obbObb reports an overlap");
                separated++;
                judged++;
            } else if (g < -MARGIN) {
                check(got, i, "overlapping by " + (-g) + " but obbObb reports a separation");
                overlapping++;
                judged++;
            }
            check(Intersectionf.obbObb(a, b) == Intersectionf.obbObb(b, a), i, "the test is symmetric");
            // a point inside both boxes forces an overlap
            for (int k = 0; k < 12; k++) {
                Vec3f p = a.rotation().transform(new Vec3f((float) rnd.range(-a.hx(), a.hx()), (float) rnd.range(-a.hy(), a.hy()), (float) rnd.range(-a.hz(), a.hz())))
                        .add(a.center());
                if (b.contains(p)) {
                    check(got, i, "a common point exists but obbObb reports a separation");
                    break;
                }
            }
        }
        check(judged > N / 4, 0, "enough trials must be decidable: " + judged);
        check(overlapping > N / 20 && separated > N / 20, 0, "both outcomes must occur: " + overlapping + " overlapping, " + separated + " separated");
    }

    @Test
    void obbObbSpecialCases() {
        Obbf unit = Obbf.fromAabb(new Aabbf(-1f, -1f, -1f, 1f, 1f, 1f));
        check(Intersectionf.obbObb(unit, unit), 0, "a box overlaps itself");
        check(Intersectionf.obbObb(unit, Obbf.fromAabb(new Aabbf(0.5f, 0.5f, 0.5f, 3f, 3f, 3f))), 1, "corner overlap");
        check(!Intersectionf.obbObb(unit, Obbf.fromAabb(new Aabbf(1.5f, -1f, -1f, 3f, 1f, 1f))), 2, "a gap along x");
        check(Intersectionf.obbObb(unit, Obbf.fromAabb(new Aabbf(1f, -1f, -1f, 3f, 1f, 1f))), 3, "touching faces count as overlapping");
        check(Intersectionf.obbObb(unit, Obbf.fromAabb(new Aabbf(-0.2f, -0.2f, -0.2f, 0.2f, 0.2f, 0.2f))), 4, "one box inside the other");
        // a box rotated 45 degrees about z, just too far to touch along x and just close enough
        Obbf diamond = Obbf.of(new Vec3f(2.4f, 0f, 0f), new Vec3f(1f, 1f, 1f), vmath.core.Quatf.rotationZ((float) Math.PI / 4));
        check(Intersectionf.obbObb(unit, diamond), 5, "the diamond's corner reaches x = 2.4 - 1.414 = 0.99 < 1");
        Obbf farDiamond = Obbf.of(new Vec3f(2.5f, 0f, 0f), new Vec3f(1f, 1f, 1f), vmath.core.Quatf.rotationZ((float) Math.PI / 4));
        check(!Intersectionf.obbObb(unit, farDiamond), 6, "its corner at 2.5 - 1.414 = 1.09 > 1 does not");
        // NaN is not a separation
        Obbf bad = new Obbf(Float.NaN, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 0f, 1f);
        check(Intersectionf.obbObb(unit, bad), 7, "an undecidable pair is reported as overlapping");
    }

    // ------------------------------------------------------------ box - triangle

    @Test
    void aabbTriangleAgreesWithTheVertexProjectionReference() {
        int overlapping = 0, separated = 0, judged = 0;
        for (int i = 0; i < N; i++) {
            Aabbf box = Aabbf.fromCenterHalfExtent(rnd.nextVec3f().mul(0.2f),
                    new Vec3f((float) rnd.range(0.2, 1.5), (float) rnd.range(0.2, 1.5), (float) rnd.range(0.2, 1.5)));
            Trianglef tri = Trianglef.of(rnd.nextVec3f().mul(0.3f), rnd.nextVec3f().mul(0.3f), rnd.nextVec3f().mul(0.3f));
            if (tri.normal().length() < 1e-2f) {
                continue; // a sliver
            }
            List<Vec3f> axes = new ArrayList<>();
            axes.add(Vec3f.UNIT_X);
            axes.add(Vec3f.UNIT_Y);
            axes.add(Vec3f.UNIT_Z);
            axes.add(tri.normal());
            Vec3f[] edges = {tri.b().sub(tri.a()), tri.c().sub(tri.b()), tri.a().sub(tri.c())};
            Vec3f[] boxAxes = {Vec3f.UNIT_X, Vec3f.UNIT_Y, Vec3f.UNIT_Z};
            for (Vec3f e : edges) {
                for (Vec3f u : boxAxes) {
                    axes.add(u.cross(e));
                }
            }
            double g = largestGap(vertices(box), new Vec3f[] {tri.a(), tri.b(), tri.c()}, axes);
            boolean got = Intersectionf.aabbTriangle(box, tri);
            if (g > MARGIN) {
                check(!got, i, "separated by " + g + " but aabbTriangle reports an overlap");
                separated++;
                judged++;
            } else if (g < -MARGIN) {
                check(got, i, "overlapping by " + (-g) + " but aabbTriangle reports a separation");
                overlapping++;
                judged++;
            }
            // a point of the triangle inside the box forces an overlap
            for (int k = 0; k < 12; k++) {
                float u = (float) rnd.range(0, 1), v = (float) rnd.range(0, 1 - u);
                Vec3f p = tri.a().add(edges[0].mul(u)).add(tri.c().sub(tri.a()).mul(v));
                if (box.contains(p)) {
                    check(got, i, "a point of the triangle lies in the box but aabbTriangle reports a separation");
                    break;
                }
            }
        }
        check(judged > N / 4, 0, "enough trials must be decidable: " + judged);
        check(overlapping > N / 20 && separated > N / 20, 0, "both outcomes must occur: " + overlapping + " overlapping, " + separated + " separated");
    }

    @Test
    void aabbTriangleSpecialCases() {
        Aabbf unit = new Aabbf(-1f, -1f, -1f, 1f, 1f, 1f);
        check(Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(-0.5f, -0.5f, 0f), new Vec3f(0.5f, -0.5f, 0f), new Vec3f(0f, 0.5f, 0f))), 0, "inside the box");
        check(Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(-5f, -5f, 0f), new Vec3f(5f, -5f, 0f), new Vec3f(0f, 5f, 0f))), 1, "a big triangle slicing through the box");
        check(!Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(-5f, -5f, 2f), new Vec3f(5f, -5f, 2f), new Vec3f(0f, 5f, 2f))), 2, "a parallel plane beyond the box");
        check(Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(-5f, -5f, 1f), new Vec3f(5f, -5f, 1f), new Vec3f(0f, 5f, 1f))), 3, "touching the top face");
        // the classic case that the three box-axis tests miss: the plane of the triangle passes the far corner of the box
        check(!Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(3.2f, 0f, 0f), new Vec3f(0f, 3.2f, 0f), new Vec3f(3.2f, 3.2f, 3.2f))), 4, "plane x + y - z = 3.2 misses the box, whose maximum of that is 3");
        check(Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(3f, 0f, 0f), new Vec3f(0f, 3f, 0f), new Vec3f(0f, 0f, 3f))), 5, "the plane x + y + z = 3 cuts the far corner region: it touches at (1, 1, 1)");
        check(!Intersectionf.aabbTriangle(unit, Trianglef.of(new Vec3f(3.2f, 0f, 0f), new Vec3f(0f, 3.2f, 0f), new Vec3f(0f, 0f, 3.2f))), 6, "x + y + z = 3.2 misses the corner");
        Aabbf bad = new Aabbf(Float.NaN, -1f, -1f, 1f, 1f, 1f);
        check(Intersectionf.aabbTriangle(bad, Trianglef.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 0f, 0f), new Vec3f(0f, 1f, 0f))), 7, "an undecidable pair is reported as overlapping");
    }
}
