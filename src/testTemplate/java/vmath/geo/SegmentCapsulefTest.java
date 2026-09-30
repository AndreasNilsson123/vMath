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

/** Segments and capsules, and the segment and capsule intersection routines, each against an independent reference (sampling or a convex search). */
@GenerateDouble
class SegmentCapsulefTest {
    @Eps(d = 1e-9)
    static final float EPS = 2e-4f;

    final Rnd rnd = Rnd.create();

    private Segmentf randomSegment() {
        return Segmentf.of(rnd.nextVec3f().mul(0.4f), rnd.nextVec3f().mul(0.4f));
    }

    /** Exact-enough reference distance between two segments: the point-to-segment distance is convex along {@code s}, so a ternary search finds the minimum. */
    private static double referenceDistanceSquared(Segmentf s, Segmentf t) {
        double lo = 0, hi = 1;
        for (int i = 0; i < 100; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            if (t.distanceSquared(s.pointAt((float) m1)) < t.distanceSquared(s.pointAt((float) m2))) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        return t.distanceSquared(s.pointAt((float) ((lo + hi) / 2)));
    }

    // ------------------------------------------------------------ Segment

    @Test
    void segmentClosestPointMatchesSampling() {
        for (int i = 0; i < N; i++) {
            Segmentf s = randomSegment();
            Vec3f p = rnd.nextVec3f().mul(0.6f);
            Vec3f q = s.closestPoint(p);
            double exact = q.distance(p);
            double sampled = Double.MAX_VALUE;
            for (int k = 0; k <= 400; k++) {
                sampled = Math.min(sampled, p.distance(s.pointAt(k / 400f)));
            }
            check(exact <= sampled + EPS, i, "the closest point is at least as near as every sample");
            check(sampled - exact <= s.length() / 400.0 + EPS, i, "and not nearer than sampling resolution allows");
            close(s.distanceSquared(p), exact * exact, EPS * 10, i);
            float t = s.closestParameter(p);
            check(t >= 0f && t <= 1f, i, "parameter in [0, 1]");
            close(s.pointAt(t), q, EPS, i);
        }
        Segmentf point = Segmentf.of(new Vec3f(1f, 2f, 3f), new Vec3f(1f, 2f, 3f));
        close(point.closestPoint(new Vec3f(9f, 9f, 9f)), new Vec3f(1f, 2f, 3f), EPS, 0);
        close(point.closestParameter(new Vec3f(9f, 9f, 9f)), 0.0, EPS, 1);
        close(point.length(), 0.0, EPS, 2);
    }

    @Test
    void segmentGeometry() {
        for (int i = 0; i < N; i++) {
            Segmentf s = randomSegment();
            close(s.length(), s.a().distance(s.b()), EPS * 4, i);
            close(s.midpoint(), s.pointAt(0.5f), EPS, i);
            close(s.direction(), s.b().sub(s.a()), EPS, i);
            Aabbf box = s.aabb();
            check(box.contains(s.a()) && box.contains(s.b()) && box.contains(s.midpoint()), i, "the box holds the segment");
            Mat4f m = rnd.nextTrsMat4f();
            Segmentf u = s.transform(m);
            close(u.pointAt(0.3f), m.transformPosition(s.pointAt(0.3f)), EPS * 10, i);
        }
    }

    @Test
    void segmentSegmentDistanceMatchesAConvexSearchAndItsParametersAreConsistent() {
        float[] st = new float[2];
        for (int i = 0; i < N; i++) {
            Segmentf s = randomSegment(), t = randomSegment();
            double reference = referenceDistanceSquared(s, t);
            double got = Intersectionf.segmentSegmentDistanceSquared(s, t);
            close(got, reference, EPS * 20 * (1.0 + reference), i);
            double withParams = Intersectionf.segmentSegmentClosestParameters(s, t, st);
            close(withParams, got, EPS, i);
            check(st[0] >= 0f && st[0] <= 1f && st[1] >= 0f && st[1] <= 1f, i, "parameters in [0, 1]");
            close(s.pointAt(st[0]).distanceSquared(t.pointAt(st[1])), got, EPS * 20 * (1.0 + got), i);
        }
    }

    @Test
    void segmentSegmentDegenerateCases() {
        Segmentf x = Segmentf.of(new Vec3f(0f, 0f, 0f), new Vec3f(2f, 0f, 0f));
        Segmentf crossing = Segmentf.of(new Vec3f(1f, -1f, 0f), new Vec3f(1f, 1f, 0f));
        close(Intersectionf.segmentSegmentDistanceSquared(x, crossing), 0.0, EPS, 0);
        Segmentf parallel = Segmentf.of(new Vec3f(-1f, 3f, 0f), new Vec3f(1f, 3f, 0f));
        close(Intersectionf.segmentSegmentDistanceSquared(x, parallel), 9.0, EPS, 1);
        Segmentf collinearApart = Segmentf.of(new Vec3f(5f, 0f, 0f), new Vec3f(7f, 0f, 0f));
        close(Intersectionf.segmentSegmentDistanceSquared(x, collinearApart), 9.0, EPS, 2);
        Segmentf pointSeg = Segmentf.of(new Vec3f(1f, 2f, 0f), new Vec3f(1f, 2f, 0f));
        close(Intersectionf.segmentSegmentDistanceSquared(x, pointSeg), 4.0, EPS, 3);
        close(Intersectionf.segmentSegmentDistanceSquared(pointSeg, x), 4.0, EPS, 4);
        close(Intersectionf.segmentSegmentDistanceSquared(pointSeg, pointSeg), 0.0, EPS, 5);
        Segmentf pointB = Segmentf.of(new Vec3f(4f, 0f, 0f), new Vec3f(4f, 0f, 0f));
        close(Intersectionf.segmentSegmentDistanceSquared(pointSeg, pointB), 9.0 + 4.0, EPS, 6);
        Segmentf skew = Segmentf.of(new Vec3f(1f, -1f, 2f), new Vec3f(1f, 1f, 2f));
        close(Intersectionf.segmentSegmentDistanceSquared(x, skew), 4.0, EPS, 7);
    }

    // ------------------------------------------------------------ Capsule

    @Test
    void capsuleContainsClosestPointAndBounds() {
        for (int i = 0; i < N; i++) {
            Capsulef c = Capsulef.of(randomSegment(), (float) rnd.range(0.1, 1.5));
            Vec3f p = rnd.nextVec3f().mul(0.6f);
            double axisDistance = Math.sqrt(c.segment().distanceSquared(p));
            if (Math.abs(axisDistance - c.radius()) > 1e-3) {
                check(c.contains(p) == (axisDistance < c.radius()), i, "contains agrees with the distance to the axis");
            }
            Vec3f q = c.closestPoint(p);
            if (axisDistance <= c.radius()) {
                check(q.equals(p), i, "a point inside is its own closest point");
            } else {
                close(c.segment().distanceSquared(q), c.radius() * c.radius(), EPS * 20, i);
                close(p.distance(q), axisDistance - c.radius(), EPS * 10, i);
            }
            Aabbf box = c.aabb();
            // points on the capsule's surface lie in the box
            Vec3f dir = rnd.nextVec3f().normalize();
            Vec3f onSurface = c.segment().pointAt((float) rnd.range(0, 1)).add(dir.mul(c.radius()));
            check(box.inflate(EPS * 10).contains(onSurface), i, "the box holds the surface");
        }
    }

    @Test
    void capsuleVolumeMatchesTheFormulaAndASphereWhenTheEndsCoincide() {
        Vec3f p = new Vec3f(1f, 2f, 3f);
        Capsulef sphere = Capsulef.of(p, p, 2f);
        close(sphere.volume(), 4.0 / 3 * Math.PI * 8, EPS * 40, 0);
        Capsulef c = Capsulef.of(new Vec3f(0f, 0f, 0f), new Vec3f(0f, 3f, 0f), 1f);
        close(c.volume(), Math.PI * 3 + 4.0 / 3 * Math.PI, EPS * 40, 1);
        // a Monte-Carlo estimate of the same volume, in the capsule's box
        Aabbf box = c.aabb();
        int hits = 0, n = 40000;
        for (int i = 0; i < n; i++) {
            Vec3f s = new Vec3f((float) rnd.range(box.minX(), box.maxX()), (float) rnd.range(box.minY(), box.maxY()), (float) rnd.range(box.minZ(), box.maxZ()));
            if (c.contains(s)) {
                hits++;
            }
        }
        double estimate = box.volume() * hits / n;
        check(Math.abs(estimate - c.volume()) < 0.04 * c.volume(), 2, "sampled volume " + estimate + " against formula " + c.volume());
    }

    @Test
    void capsuleTransformContainsTheTransformedCapsuleAndIsExactForUniformScale() {
        for (int i = 0; i < N; i++) {
            Capsulef c = Capsulef.of(randomSegment(), (float) rnd.range(0.1, 1.5));
            Mat4f m = rnd.nextTrsMat4f();
            Capsulef t = c.transform(m);
            Vec3f on = c.segment().pointAt((float) rnd.range(0, 1)).add(rnd.nextVec3f().normalize().mul(c.radius()));
            check(t.contains(m.transformPosition(on)) || t.segment().distanceSquared(m.transformPosition(on)) <= t.radius() * t.radius() * (1f + EPS * 10), i,
                    "a surface point of the original stays inside the transformed capsule");
            Capsulef u = c.transform(Mat4f.scaling(2f, 2f, 2f));
            close(u.radius(), c.radius() * 2f, EPS * 4, i);
            close(u.a(), c.a().mul(2f), EPS * 4, i);
        }
    }

    // ------------------------------------------------------------ capsule intersections

    @Test
    void capsuleCapsuleAndSphereCapsuleMatchTheDistanceReference() {
        int checked = 0;
        for (int i = 0; i < N; i++) {
            Capsulef a = Capsulef.of(randomSegment(), (float) rnd.range(0.1, 1.2));
            Capsulef b = Capsulef.of(randomSegment(), (float) rnd.range(0.1, 1.2));
            double dist = Math.sqrt(referenceDistanceSquared(a.segment(), b.segment()));
            double reach = a.radius() + b.radius();
            if (Math.abs(dist - reach) > 1e-3) {
                check(Intersectionf.capsuleCapsule(a, b) == (dist < reach), i, "capsuleCapsule, distance " + dist + " reach " + reach);
                checked++;
            }
            Spheref s = Spheref.of(rnd.nextVec3f().mul(0.4f), (float) rnd.range(0.1, 1.2));
            double sd = Math.sqrt(a.segment().distanceSquared(s.center()));
            if (Math.abs(sd - (a.radius() + s.radius())) > 1e-3) {
                check(Intersectionf.sphereCapsule(s, a) == (sd < a.radius() + s.radius()), i, "sphereCapsule");
            }
        }
        check(checked > N / 2, 0, "most random trials must be decidable");
    }

    @Test
    void segmentAabbDistanceMatchesSampling() {
        for (int i = 0; i < N; i++) {
            Segmentf s = randomSegment();
            Vec3f c = rnd.nextVec3f().mul(0.3f);
            Aabbf box = Aabbf.fromCenterHalfExtent(c, new Vec3f((float) rnd.range(0.1, 1.5), (float) rnd.range(0.1, 1.5), (float) rnd.range(0.1, 1.5)));
            double exact = Math.sqrt(Intersectionf.segmentAabbDistanceSquared(s, box));
            double sampled = Double.MAX_VALUE;
            for (int k = 0; k <= 800; k++) {
                sampled = Math.min(sampled, Math.sqrt(box.distanceSquared(s.pointAt(k / 800f))));
            }
            check(exact <= sampled + EPS * 4, i, "the exact distance is at most every sampled one: " + exact + " vs " + sampled);
            check(sampled - exact <= s.length() / 800.0 + EPS * 4, i, "and within sampling resolution of the smallest: " + exact + " vs " + sampled);
        }
        Aabbf unit = new Aabbf(0f, 0f, 0f, 1f, 1f, 1f);
        close(Intersectionf.segmentAabbDistanceSquared(Segmentf.of(new Vec3f(0.2f, 0.2f, 0.2f), new Vec3f(0.4f, 0.4f, 0.4f)), unit), 0.0, EPS, 0);
        close(Intersectionf.segmentAabbDistanceSquared(Segmentf.of(new Vec3f(-1f, 0.5f, 0.5f), new Vec3f(2f, 0.5f, 0.5f)), unit), 0.0, EPS, 1);
        close(Intersectionf.segmentAabbDistanceSquared(Segmentf.of(new Vec3f(3f, 0.5f, 0.5f), new Vec3f(3f, 0.5f, 0.5f)), unit), 4.0, EPS, 2);
        close(Intersectionf.segmentAabbDistanceSquared(Segmentf.of(new Vec3f(2f, 2f, 0.5f), new Vec3f(2f, 2f, 0.5f)), unit), 2.0, EPS, 3);
        check(Intersectionf.capsuleAabb(Capsulef.of(new Vec3f(3f, 0.5f, 0.5f), new Vec3f(3f, 0.5f, 0.5f), 2.5f), unit), 4, "a sphere 2 away with radius 2.5 touches");
        check(!Intersectionf.capsuleAabb(Capsulef.of(new Vec3f(3f, 0.5f, 0.5f), new Vec3f(3f, 0.5f, 0.5f), 1.5f), unit), 5, "radius 1.5 does not reach");
    }

    @Test
    void rayCapsuleMatchesMarchingAlongTheRay() {
        int hitsSeen = 0;
        for (int i = 0; i < N; i++) {
            Capsulef c = Capsulef.of(randomSegment(), (float) rnd.range(0.2, 1.5));
            Vec3f o = rnd.nextVec3f().mul(0.6f);
            Vec3f target = c.segment().pointAt((float) rnd.range(-0.2, 1.2)).add(rnd.nextVec3f().mul(0.2f));
            Rayf ray = Rayf.of(o, target.sub(o).normalize());
            float tMax = 30f;
            float t = Intersectionf.rayCapsule(ray, c, tMax);
            int steps = 6000;
            float step = tMax / steps;
            int first = -1;
            double minDist = Double.MAX_VALUE;
            for (int k = 0; k <= steps; k++) {
                double d = Math.sqrt(c.segment().distanceSquared(ray.pointAt(k * step)));
                minDist = Math.min(minDist, d);
                if (first < 0 && d <= c.radius()) {
                    first = k;
                }
            }
            boolean grazing = Math.abs(minDist - c.radius()) < 0.02 * c.radius();
            if (t == Float.POSITIVE_INFINITY) {
                check(first < 0 || grazing, i, "rayCapsule missed but marching found a hit at " + first * step);
            } else {
                hitsSeen++;
                check(first >= 0 || grazing, i, "rayCapsule hit at " + t + " but marching found nothing (min distance " + minDist + ")");
                if (first >= 0) {
                    check(Math.abs(t - first * step) <= step * 1.5 + EPS * 10, i, "hit distance " + t + " against marched " + first * step);
                }
                double surface = Math.sqrt(c.segment().distanceSquared(ray.pointAt(t)));
                if (t == 0f) {
                    check(c.contains(ray.origin()), i, "t = 0 means the ray starts inside the capsule");
                } else {
                    check(Math.abs(surface - c.radius()) <= 1e-3 * (1 + c.radius()), i, "the hit point must lie on the surface: its distance from the axis is " + surface
                            + " for radius " + c.radius() + " (t " + t + ", ray " + ray + ", capsule " + c + ")");
                }
            }
        }
        check(hitsSeen > N / 10, 0, "the rays must hit often enough to mean something, hits " + hitsSeen);
    }

    @Test
    void rayCapsuleSpecialCases() {
        Capsulef c = Capsulef.of(new Vec3f(0f, 0f, 0f), new Vec3f(0f, 4f, 0f), 1f);
        close(Intersectionf.rayCapsule(new Rayf(0.5f, 2f, 0f, 1f, 0f, 0f), c, 100f), 0.0, EPS, 0); // starts inside the cylinder part
        close(Intersectionf.rayCapsule(new Rayf(5f, 2f, 0f, -1f, 0f, 0f), c, 100f), 4.0, EPS, 1);  // the wall, side on
        close(Intersectionf.rayCapsule(new Rayf(0f, 10f, 0f, 0f, -1f, 0f), c, 100f), 5.0, EPS, 2); // down the axis onto the top cap
        close(Intersectionf.rayCapsule(new Rayf(0f, -10f, 0f, 0f, 1f, 0f), c, 100f), 9.0, EPS, 3); // up the axis onto the bottom cap
        close(Intersectionf.rayCapsule(new Rayf(5f, 4.5f, 0f, -1f, 0f, 0f), c, 100f), 5.0 - Math.sqrt(0.75), EPS * 4, 4); // hits the top cap sphere (centre height 4) off-centre
        check(Intersectionf.rayCapsule(new Rayf(5f, 5.5f, 0f, -1f, 0f, 0f), c, 100f) == Float.POSITIVE_INFINITY, 4, "above the top of the cap (y = 5) is a miss");
        check(Intersectionf.rayCapsule(new Rayf(5f, 2f, 0f, -1f, 0f, 0f), c, 3f) == Float.POSITIVE_INFINITY, 5, "beyond tMax is a miss");
        check(Intersectionf.rayCapsule(new Rayf(5f, 2f, 0f, 1f, 0f, 0f), c, 100f) == Float.POSITIVE_INFINITY, 6, "moving away is a miss");
        check(Intersectionf.rayCapsule(new Rayf(5f, 9f, 0f, -1f, 0f, 0f), c, 100f) == Float.POSITIVE_INFINITY, 7, "passing above the top is a miss");
        Capsulef sphere = Capsulef.of(new Vec3f(0f, 0f, 0f), new Vec3f(0f, 0f, 0f), 1f);
        close(Intersectionf.rayCapsule(new Rayf(5f, 0f, 0f, -1f, 0f, 0f), sphere, 100f), 4.0, EPS, 8);
    }
}
