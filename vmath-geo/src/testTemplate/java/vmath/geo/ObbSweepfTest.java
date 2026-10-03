package vmath.geo;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import static vmath.core.Check.check;
import static vmath.core.Rnd.N;

import org.junit.jupiter.api.Test;
import vmath.core.Rnd;
import vmath.core.Vec3f;

/** Oriented-box queries, plane-triangle and the sphere sweep, each against a sampling or marching reference. */
@GenerateDouble
class ObbSweepfTest {
    @Eps(d = 1e-9)
    static final float EPS = 2e-3f;

    final Rnd rnd = Rnd.create();

    private Obbf randomObb() {
        return Obbf.of(rnd.nextVec3f().mul(0.2f), new Vec3f((float) rnd.range(0.2, 1.5), (float) rnd.range(0.2, 1.5), (float) rnd.range(0.2, 1.5)), rnd.nextUnitQuatf());
    }

    private Planef randomPlane() {
        Vec3f n = rnd.nextVec3f();
        return Planef.fromPointNormal(rnd.nextVec3f().mul(0.2f), n.mul(1f / n.length()));
    }

    @Test
    void rayObbMatchesMarching() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Obbf box = randomObb();
            Rayf ray = Rayf.through(rnd.nextVec3f().mul(0.4f), rnd.nextVec3f().mul(0.4f));
            ray = Rayf.of(ray.origin(), ray.direction().mul(1f / ray.direction().length()));
            float t = Intersectionf.rayObb(ray, box, 100f);
            // marching: the first sampled point inside the box bounds the hit from above, and no point before the hit is inside
            float step = 0.002f;
            float first = Float.POSITIVE_INFINITY;
            for (float s = 0; s <= 12f; s += step) {
                if (box.contains(ray.pointAt(s))) {
                    first = s;
                    break;
                }
            }
            if (first == Float.POSITIVE_INFINITY) {
                // a graze can fall between two steps, so a reported hit only has to be a real one: a point of the ray on the box
                if (t <= 12f) {
                    check(box.distanceSquared(ray.pointAt(t)) < 1e-5f, i, "marching found nothing but rayObb reports t = " + t + " off the box");
                }
            } else {
                check(t <= first + 1e-4f && t >= first - 2 * step, i, "rayObb t = " + t + " but marching first enters at " + first);
                hits++;
            }
        }
        check(hits > N / 20, 0, "enough rays must hit: " + hits);
    }

    @Test
    void rayObbSpecialCases() {
        Obbf unit = Obbf.fromAabb(new Aabbf(-1f, -1f, -1f, 1f, 1f, 1f));
        check(Intersectionf.rayObb(Rayf.of(new Vec3f(-5f, 0f, 0f), new Vec3f(1f, 0f, 0f)), unit, 100f) == 4f, 0, "straight at a face");
        check(Intersectionf.rayObb(Rayf.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 0f, 0f)), unit, 100f) == 0f, 1, "origin inside");
        check(Intersectionf.rayObb(Rayf.of(new Vec3f(-5f, 0f, 0f), new Vec3f(1f, 0f, 0f)), unit, 3f) == Float.POSITIVE_INFINITY, 2, "beyond tMax");
        check(Intersectionf.rayObb(Rayf.of(new Vec3f(-5f, 2f, 0f), new Vec3f(1f, 0f, 0f)), unit, 100f) == Float.POSITIVE_INFINITY, 3, "passes beside");
    }

    @Test
    void sphereObbMatchesSampling() {
        int hit = 0, miss = 0;
        for (int i = 0; i < N; i++) {
            Obbf box = randomObb();
            Spheref s = Spheref.of(rnd.nextVec3f().mul(0.25f), (float) rnd.range(0.1, 1.2));
            boolean got = Intersectionf.sphereObb(s, box);
            double d = Math.sqrt(box.distanceSquared(s.center())) - s.radius();
            if (Math.abs(d) > 1e-3) {
                check(got == (d < 0), i, "distance gap " + d + " but sphereObb = " + got);
                if (got) {
                    hit++;
                } else {
                    miss++;
                }
            }
        }
        check(hit > N / 20 && miss > N / 20, 0, "both outcomes must occur: " + hit + "/" + miss);
    }

    @Test
    void planeObbAgreesWithTheCorners() {
        int[] seen = new int[3];
        for (int i = 0; i < N; i++) {
            Obbf box = randomObb();
            Planef p = randomPlane();
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int c = 0; c < 8; c++) {
                Vec3f local = new Vec3f((c & 1) == 0 ? -box.hx() : box.hx(), (c & 2) == 0 ? -box.hy() : box.hy(), (c & 4) == 0 ? -box.hz() : box.hz());
                double d = p.distance(box.rotation().transform(local).add(box.center()));
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
            if (Math.abs(lo) < 1e-3 || Math.abs(hi) < 1e-3) {
                continue;
            }
            int expected = lo > 0 ? Containment.INSIDE : hi < 0 ? Containment.OUTSIDE : Containment.INTERSECTING;
            check(Intersectionf.planeObb(p, box) == expected, i, "corners span [" + lo + ", " + hi + "]");
            seen[expected == Containment.INSIDE ? 0 : expected == Containment.OUTSIDE ? 1 : 2]++;
        }
        check(seen[0] > 10 && seen[1] > 10 && seen[2] > 10, 0, "all three outcomes must occur");
    }

    @Test
    void planeTriangleClassifiesByVertices() {
        Planef p = Planef.fromPointNormal(new Vec3f(0f, 0f, 0f), new Vec3f(0f, 1f, 0f));
        check(Intersectionf.planeTriangle(p, Trianglef.of(new Vec3f(0f, 1f, 0f), new Vec3f(1f, 2f, 0f), new Vec3f(0f, 1f, 1f))) == Containment.INSIDE, 0, "in front");
        check(Intersectionf.planeTriangle(p, Trianglef.of(new Vec3f(0f, -1f, 0f), new Vec3f(1f, -2f, 0f), new Vec3f(0f, -1f, 1f))) == Containment.OUTSIDE, 1, "behind");
        check(Intersectionf.planeTriangle(p, Trianglef.of(new Vec3f(0f, -1f, 0f), new Vec3f(1f, 2f, 0f), new Vec3f(0f, 1f, 1f))) == Containment.INTERSECTING, 2, "straddling");
        check(Intersectionf.planeTriangle(p, Trianglef.of(new Vec3f(0f, 0f, 0f), new Vec3f(1f, 0f, 0f), new Vec3f(0f, 0f, 1f))) == Containment.INSIDE, 3, "lying in the plane counts as in front");
    }

    @Test
    void sweepSphereSphereMatchesStepping() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Spheref a = Spheref.of(rnd.nextVec3f().mul(0.1f), (float) rnd.range(0.1, 0.8));
            Spheref b = Spheref.of(rnd.nextVec3f().mul(0.1f), (float) rnd.range(0.1, 0.8));
            Vec3f va = rnd.nextVec3f().mul(0.06f), vb = rnd.nextVec3f().mul(0.06f);
            float t = Intersectionf.sweepSphereSphere(a, va, b, vb, 1f);
            double first = Double.POSITIVE_INFINITY;
            int steps = 4000;
            for (int k = 0; k <= steps; k++) {
                float s = k * (1f / steps); // not (float) k / steps: the double twin would drop the cast and divide as integers
                Vec3f ca = a.center().add(va.mul(s)), cb = b.center().add(vb.mul(s));
                if (ca.distance(cb) <= a.radius() + b.radius()) {
                    first = s;
                    break;
                }
            }
            if (first == Double.POSITIVE_INFINITY) {
                // a graze can fall between two steps, so a reported contact only has to be a real one
                if (t != Float.POSITIVE_INFINITY) {
                    double gap = a.center().add(va.mul(t)).distance(b.center().add(vb.mul(t))) - a.radius() - b.radius();
                    check(t <= 1f && Math.abs(gap) < 1e-3, i, "stepping found no contact but sweep reports t = " + t + " with gap " + gap);
                }
            } else {
                check(t <= first + 1e-4 && t >= first - 2.0 / steps - 1e-4, i, "sweep t = " + t + " but stepping first touches at " + first);
                hits++;
            }
        }
        check(hits > N / 50, 0, "enough sweeps must hit: " + hits);
    }
}
