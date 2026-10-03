package vmath.geo;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.core.Vec3f;

/** The swept tests against dense sampling of the gap between the moving shapes. */
@GenerateDouble
class SweepfTest {
    @Eps(d = 1e-8)
    static final float GAP = 2e-3f;

    static final float T_MAX = 4f;
    static final int STEPS = 2000;

    final Rnd rnd = Rnd.create();

    /** The gap between the shapes at time t: positive when apart, zero or negative when they touch. */
    interface Gap {
        float at(float t);
    }

    private Vec3f point(float scale) {
        return rnd.nextVec3f().mul(scale / 10f);
    }

    private Vec3f aimed(Vec3f from, Vec3f target) {
        Vec3f d = target.sub(from).add(point(1.5f));
        float len = d.length();
        return len > 1e-3f ? d.mul((float) rnd.range(0.4, 2.0) / len) : new Vec3f(1f, 0f, 0f);
    }

    private float radius() {
        return (float) rnd.range(0.1, 1.0);
    }

    private Aabbf box() {
        Vec3f c = point(1.5f);
        Vec3f h = new Vec3f((float) rnd.range(0.2, 1.2), (float) rnd.range(0.2, 1.2), (float) rnd.range(0.2, 1.2));
        return Aabbf.of(c.sub(h), c.add(h));
    }

    private Trianglef triangle() {
        return Trianglef.of(point(2f), point(2f), point(2f));
    }

    private Capsulef capsule(Vec3f centre) {
        return Capsulef.of(centre.add(point(0.8f)), centre.add(point(0.8f)), radius());
    }

    private static Aabbf move(Aabbf b, Vec3f d) {
        return Aabbf.of(b.min().add(d), b.max().add(d));
    }

    private static Trianglef move(Trianglef t, Vec3f d) {
        return Trianglef.of(t.a().add(d), t.b().add(d), t.c().add(d));
    }

    private static Capsulef move(Capsulef c, Vec3f d) {
        return Capsulef.of(c.a().add(d), c.b().add(d), c.radius());
    }

    private static Spheref move(Spheref s, Vec3f d) {
        return Spheref.of(s.center().add(d), s.radius());
    }

    private static Obbf move(Obbf b, Vec3f d) {
        return Obbf.of(b.center().add(d), b.halfExtents(), b.rotation());
    }

    /** An exact sweep: its time must lie in the step that the sampling finds, and the gap there must be zero. */
    private void exact(int i, String what, float got, Gap gap) {
        float dt = T_MAX / STEPS;
        int first = -1;
        for (int k = 0; k <= STEPS; k++) {
            if (gap.at(k * dt) <= 0f) {
                first = k;
                break;
            }
        }
        if (first == 0) {
            check(got == 0f, i, what + ": touching at the start but t = " + got);
        } else if (first > 0) {
            check(got <= first * dt + 1e-4f && got >= (first - 1) * dt - 1e-4f, i, what + ": t = " + got + " but sampling first touches in step " + first * dt);
            check(Math.abs(gap.at(got)) <= GAP, i, what + ": the gap at t = " + got + " is " + gap.at(got));
        } else if (got != Float.POSITIVE_INFINITY) {
            check(Math.abs(gap.at(got)) <= GAP, i, what + ": sampling found no contact but t = " + got + " has the gap " + gap.at(got));
        }
    }

    /** A conservative sweep: never later than the true time, and close to it except for rare grazing passes. */
    private int conservative(int i, String what, float got, Gap gap) {
        float dt = T_MAX / STEPS;
        int first = -1;
        for (int k = 0; k <= STEPS; k++) {
            if (gap.at(k * dt) <= 0f) {
                first = k;
                break;
            }
        }
        if (first == 0) {
            check(got == 0f, i, what + ": touching at the start but t = " + got);
            return 0;
        }
        if (first > 0) {
            check(got <= first * dt + 1e-4f, i, what + ": t = " + got + " is later than the sampled contact at " + first * dt);
            return got >= (first - 1) * dt - 5e-3f ? 0 : 1;
        }
        return got == Float.POSITIVE_INFINITY || gap.at(got) <= GAP ? 0 : 1;
    }

    @Test
    void sphereCapsule() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Spheref s = Spheref.of(point(3f), radius());
            Capsulef c = capsule(point(1f));
            Vec3f vs = aimed(s.center(), c.a()), vc = point(0.3f);
            float t = Intersectionf.sweepSphereCapsule(s, vs, c, vc, T_MAX);
            exact(i, "sphere-capsule", t, k -> (float) Math.sqrt(move(c, vc.mul(k)).segment().distanceSquared(move(s, vs.mul(k)).center())) - s.radius() - c.radius());
            check(t == Intersectionf.sweepCapsuleSphere(c, vc, s, vs, T_MAX), i, "the swapped form agrees");
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void sphereAabb() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Spheref s = Spheref.of(point(3f), radius());
            Aabbf b = box();
            Vec3f vs = aimed(s.center(), b.center()), vb = point(0.3f);
            float t = Intersectionf.sweepSphereAabb(s, vs, b, vb, T_MAX);
            exact(i, "sphere-aabb", t, k -> (float) Math.sqrt(move(b, vb.mul(k)).distanceSquared(move(s, vs.mul(k)).center())) - s.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void sphereAabbAimedAtEdgesAndCorners() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Aabbf b = box();
            Vec3f corner = new Vec3f(rnd.nextBoolean() ? b.minX() : b.maxX(), rnd.nextBoolean() ? b.minY() : b.maxY(), rnd.nextBoolean() ? b.minZ() : b.maxZ());
            Vec3f out = corner.sub(b.center());
            Spheref s = Spheref.of(corner.add(out.mul((float) rnd.range(1.0, 3.0))).add(point(0.5f)), radius());
            Vec3f toCorner = corner.sub(s.center());
            Vec3f vs = toCorner.mul((float) rnd.range(0.4, 2.0) / toCorner.length());
            float t = Intersectionf.sweepSphereAabb(s, vs, b, Vec3f.ZERO, T_MAX);
            exact(i, "sphere-aabb (corner)", t, k -> (float) Math.sqrt(b.distanceSquared(s.center().add(vs.mul(k)))) - s.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 4, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void sphereObb() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Obbf b = Obbf.of(point(1.5f), new Vec3f((float) rnd.range(0.2, 1.2), (float) rnd.range(0.2, 1.2), (float) rnd.range(0.2, 1.2)), rnd.nextUnitQuatf());
            Spheref s = Spheref.of(point(3f), radius());
            Vec3f vs = aimed(s.center(), b.center()), vb = point(0.3f);
            float t = Intersectionf.sweepSphereObb(s, vs, b, vb, T_MAX);
            exact(i, "sphere-obb", t, k -> (float) Math.sqrt(move(b, vb.mul(k)).distanceSquared(s.center().add(vs.mul(k)))) - s.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void sphereTriangle() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Spheref s = Spheref.of(point(3f), radius());
            Trianglef tri = triangle();
            Vec3f vs = aimed(s.center(), tri.centroid()), vt = point(0.3f);
            float t = Intersectionf.sweepSphereTriangle(s, vs, tri, vt, T_MAX);
            exact(i, "sphere-triangle", t, k -> (float) Math.sqrt(Intersectionf.pointTriangleDistanceSquared(s.center().add(vs.mul(k)), move(tri, vt.mul(k)))) - s.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void capsuleCapsule() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Capsulef a = capsule(point(3f)), b = capsule(point(1f));
            Vec3f va = aimed(a.a(), b.a()), vb = point(0.3f);
            float t = Intersectionf.sweepCapsuleCapsule(a, va, b, vb, T_MAX);
            exact(i, "capsule-capsule", t, k -> (float) Math.sqrt(Intersectionf.segmentSegmentDistanceSquared(move(a, va.mul(k)).segment(), move(b, vb.mul(k)).segment())) - a.radius() - b.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void capsuleCapsuleParallelAxes() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Vec3f axis = point(1.5f);
            Vec3f base = point(1f);
            Capsulef a = Capsulef.of(base.add(point(3f)), base.add(point(3f)).add(axis), radius());
            Capsulef b = Capsulef.of(base, base.add(axis.mul((float) rnd.range(0.5, 2.0))), radius());
            Vec3f va = aimed(a.a(), b.a()), vb = Vec3f.ZERO;
            float t = Intersectionf.sweepCapsuleCapsule(a, va, b, vb, T_MAX);
            exact(i, "capsule-capsule (parallel)", t, k -> (float) Math.sqrt(Intersectionf.segmentSegmentDistanceSquared(move(a, va.mul(k)).segment(), b.segment())) - a.radius() - b.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 8, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void capsuleAabbIsConservative() {
        int hits = 0, off = 0;
        for (int i = 0; i < N; i++) {
            Capsulef c = capsule(point(3f));
            Aabbf b = box();
            Vec3f vc = aimed(c.a(), b.center()), vb = point(0.3f);
            float t = Intersectionf.sweepCapsuleAabb(c, vc, b, vb, T_MAX);
            off += conservative(i, "capsule-aabb", t, k -> (float) Math.sqrt(Intersectionf.segmentAabbDistanceSquared(move(c, vc.mul(k)).segment(), move(b, vb.mul(k)))) - c.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
        check(off < N / 50, 0, "too many sweeps far from the sampled time: " + off);
    }

    @Test
    void capsuleObbIsConservative() {
        int hits = 0, off = 0;
        for (int i = 0; i < N; i++) {
            Obbf b = Obbf.of(point(1.5f), new Vec3f((float) rnd.range(0.2, 1.2), (float) rnd.range(0.2, 1.2), (float) rnd.range(0.2, 1.2)), rnd.nextUnitQuatf());
            Capsulef c = capsule(point(3f));
            Vec3f vc = aimed(c.a(), b.center()), vb = point(0.3f);
            float t = Intersectionf.sweepCapsuleObb(c, vc, b, vb, T_MAX);
            off += conservative(i, "capsule-obb", t, k -> {
                Obbf bk = move(b, vb.mul(k));
                Capsulef ck = move(c, vc.mul(k));
                Aabbf local = Aabbf.of(bk.halfExtents().negate(), bk.halfExtents());
                Segmentf seg = Segmentf.of(bk.toLocal(ck.a()), bk.toLocal(ck.b()));
                return (float) Math.sqrt(Intersectionf.segmentAabbDistanceSquared(seg, local)) - c.radius();
            });
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
        check(off < N / 50, 0, "too many sweeps far from the sampled time: " + off);
    }

    @Test
    void capsuleTriangleIsConservative() {
        int hits = 0, off = 0;
        for (int i = 0; i < N; i++) {
            Capsulef c = capsule(point(3f));
            Trianglef tri = triangle();
            Vec3f vc = aimed(c.a(), tri.centroid()), vt = point(0.3f);
            float t = Intersectionf.sweepCapsuleTriangle(c, vc, tri, vt, T_MAX);
            off += conservative(i, "capsule-triangle", t, k -> (float) Math.sqrt(Intersectionf.segmentTriangleDistanceSquared(move(c, vc.mul(k)).segment(), move(tri, vt.mul(k)))) - c.radius());
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
        check(off < N / 50, 0, "too many sweeps far from the sampled time: " + off);
    }

    @Test
    void aabbAabb() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Aabbf a = move(box(), point(2.5f)), b = box();
            Vec3f va = aimed(a.center(), b.center()), vb = point(0.3f);
            float t = Intersectionf.sweepAabbAabb(a, va, b, vb, T_MAX);
            exact(i, "aabb-aabb", t, k -> {
                Aabbf ak = move(a, va.mul(k)), bk = move(b, vb.mul(k));
                return Math.max(Math.max(ak.minX() - bk.maxX(), bk.minX() - ak.maxX()), Math.max(Math.max(ak.minY() - bk.maxY(), bk.minY() - ak.maxY()), Math.max(ak.minZ() - bk.maxZ(), bk.minZ() - ak.maxZ())));
            });
            hits += t < Float.POSITIVE_INFINITY ? 1 : 0;
        }
        check(hits > N / 5, 0, "enough sweeps hit: " + hits);
    }

    @Test
    void segmentTriangleDistanceMatchesSampling() {
        int touching = 0;
        for (int i = 0; i < N; i++) {
            Trianglef tri = triangle();
            Segmentf seg = Segmentf.of(point(2f), point(2f));
            float got = (float) Math.sqrt(Intersectionf.segmentTriangleDistanceSquared(seg, tri));
            // the distance from a point moving along the segment is convex, so sampling finds the minimum
            float best = Float.POSITIVE_INFINITY;
            for (int k = 0; k <= 400; k++) {
                best = Math.min(best, (float) Math.sqrt(Intersectionf.pointTriangleDistanceSquared(seg.pointAt(k / 400f), tri)));
            }
            check(got <= best + 1e-5f && got >= best - 0.05f * Math.max(1f, seg.length()) / 10f, i, "segment-triangle distance " + got + " but sampling gives " + best);
            touching += got == 0f ? 1 : 0;
        }
        check(touching > 0, 0, "some segments must pierce the triangle");
    }

    @Test
    void specialCases() {
        Aabbf unit = Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f));
        Spheref s = Spheref.of(new Vec3f(-5f, 0f, 0f), 1f);
        Vec3f x = new Vec3f(1f, 0f, 0f);
        check(Intersectionf.sweepSphereAabb(s, x, unit, Vec3f.ZERO, 100f) == 3f, 0, "straight at a face: the surface is reached after 3");
        check(Intersectionf.sweepSphereAabb(s, x, unit, Vec3f.ZERO, 2f) == Float.POSITIVE_INFINITY, 1, "beyond tMax");
        check(Intersectionf.sweepSphereAabb(s, Vec3f.ZERO, unit, Vec3f.ZERO, 100f) == Float.POSITIVE_INFINITY, 2, "no motion");
        check(Intersectionf.sweepSphereAabb(Spheref.of(Vec3f.ZERO, 0.5f), x, unit, Vec3f.ZERO, 100f) == 0f, 3, "already inside");
        check(Intersectionf.sweepSphereAabb(s, x.mul(-1f), unit, Vec3f.ZERO, 100f) == Float.POSITIVE_INFINITY, 4, "moving away");
        // the box moves instead of the sphere: only the relative motion counts
        check(Intersectionf.sweepSphereAabb(s, Vec3f.ZERO, unit, x, 100f) == Float.POSITIVE_INFINITY, 5, "box moving away");
        check(Intersectionf.sweepSphereAabb(s, Vec3f.ZERO, unit, x.mul(-1f), 100f) == 3f, 6, "box moving towards the sphere");
        check(Intersectionf.sweepSphereAabb(Spheref.of(new Vec3f(5f, 0f, 0f), 1f), Vec3f.ZERO, unit, new Vec3f(1f, 0f, 0f), 100f) == 3f, 7, "box approaching a sphere on its other side");
        // a corner: a unit sphere moving along the diagonal reaches the corner sphere
        float sq3 = (float) Math.sqrt(3.0);
        Spheref diag = Spheref.of(new Vec3f(-4f, -4f, -4f), 1f);
        float t = Intersectionf.sweepSphereAabb(diag, new Vec3f(1f, 1f, 1f), unit, Vec3f.ZERO, 100f);
        check(Math.abs(t - (3f - 1f / sq3)) < 1e-3f, 8, "corner hit " + t);
        // a triangle face
        Trianglef tri = Trianglef.of(new Vec3f(-2f, -2f, 0f), new Vec3f(2f, -2f, 0f), new Vec3f(0f, 2f, 0f));
        float tf = Intersectionf.sweepSphereTriangle(Spheref.of(new Vec3f(0f, 0f, 5f), 1f), new Vec3f(0f, 0f, -1f), tri, Vec3f.ZERO, 100f);
        check(Math.abs(tf - 4f) < 1e-4f, 9, "triangle face hit " + tf);
        float tb = Intersectionf.sweepSphereTriangle(Spheref.of(new Vec3f(0f, 0f, -5f), 1f), new Vec3f(0f, 0f, 1f), tri, Vec3f.ZERO, 100f);
        check(Math.abs(tb - 4f) < 1e-4f, 10, "triangle is two-sided: " + tb);
        check(Intersectionf.sweepSphereTriangle(Spheref.of(new Vec3f(5f, 5f, 5f), 1f), new Vec3f(0f, 0f, -1f), tri, Vec3f.ZERO, 100f) == Float.POSITIVE_INFINITY, 11, "passes beside the triangle");
        // two crossing capsules
        Capsulef a = Capsulef.of(new Vec3f(-2f, 0f, 0f), new Vec3f(2f, 0f, 0f), 0.5f);
        Capsulef b = Capsulef.of(new Vec3f(0f, -2f, 3f), new Vec3f(0f, 2f, 3f), 0.5f);
        float tc = Intersectionf.sweepCapsuleCapsule(a, new Vec3f(0f, 0f, 1f), b, Vec3f.ZERO, 100f);
        check(Math.abs(tc - 2f) < 1e-4f, 12, "crossing capsules touch after 2: " + tc);
    }
}
