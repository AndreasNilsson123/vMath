package vmath.geo;

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

/** Sphere, plane, triangle and oriented-box behaviour. */
@GenerateDouble
class ShapesfTest {
    @Eps(d = 1e-10)
    static final float EPS = 1e-3f;

    final Rnd rnd = Rnd.create();

    private Spheref randomSphere() {
        return Spheref.of(rnd.nextVec3f(), (float) rnd.range(0.1, 5));
    }

    private Vec3f randomUnitDirection() {
        return rnd.nextVec3f().normalize();
    }

    // ------------------------------------------------------------ sphere

    @Test
    void sphereOverlapsAndContains() {
        for (int i = 0; i < N; i++) {
            Spheref a = randomSphere();
            Spheref b = randomSphere();
            double d = a.center().distance(b.center());
            if (Math.abs(d - (a.radius() + b.radius())) > 1e-3) {
                check(a.overlaps(b) == (d < a.radius() + b.radius()), i, "sphere overlap by distance");
            }
            check(a.overlaps(b) == b.overlaps(a), i, "symmetric");
            Vec3f p = rnd.nextVec3f();
            double dp = a.center().distance(p);
            if (Math.abs(dp - a.radius()) > 1e-3) {
                check(a.contains(p) == (dp < a.radius()), i, "contains by distance");
            }
        }
    }

    @Test
    void sphereAabbOverlapMatchesClosestPoint() {
        for (int i = 0; i < N; i++) {
            Spheref s = randomSphere();
            Aabbf box = Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
            double d = box.closestPoint(s.center()).distance(s.center());
            if (Math.abs(d - s.radius()) > 1e-3) {
                check(s.overlaps(box) == (d < s.radius()), i, "sphere-box overlap");
            }
        }
    }

    @Test
    void sphereUnionEnclosesBothSpheres() {
        for (int i = 0; i < N; i++) {
            Spheref a = randomSphere();
            Spheref b = randomSphere();
            Spheref u = a.union(b);
            for (Spheref s : new Spheref[] {a, b}) {
                // the farthest point of each sphere from the union centre must be inside the union
                double reach = u.center().distance(s.center()) + s.radius();
                check(reach <= u.radius() * (1 + 1e-4) + 1e-4, i, "union must contain " + s + ", reach " + reach + " > " + u.radius());
            }
            // and it is the smallest: some extreme point touches the boundary
            double r = Math.max(u.center().distance(a.center()) + a.radius(), u.center().distance(b.center()) + b.radius());
            close(r, u.radius(), EPS, i);
        }
    }

    @Test
    void sphereTransformStaysConservative() {
        for (int i = 0; i < N; i++) {
            Spheref s = randomSphere();
            Mat4f m = rnd.nextTrsMat4f();
            Spheref t = s.transform(m);
            for (int k = 0; k < 10; k++) {
                Vec3f dir = randomUnitDirection();
                Vec3f onSphere = s.center().add(dir.mul(s.radius()));
                Vec3f moved = m.transformPosition(onSphere);
                check(moved.distance(t.center()) <= t.radius() * (1f + 1e-4f) + 1e-4f, i, "transformed sphere stays conservative");
            }
        }
    }

    // ------------------------------------------------------------ plane

    @Test
    void planeFromPointsAndDistances() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f(), b = rnd.nextVec3f(), c = rnd.nextVec3f();
            if (b.sub(a).cross(c.sub(a)).length() < 0.5f) {
                continue; // nearly collinear
            }
            Planef p = Planef.fromPoints(a, b, c);
            close(p.distance(a), 0.0, EPS, i);
            close(p.distance(b), 0.0, EPS, i);
            close(p.distance(c), 0.0, EPS, i);
            close(p.normal().length(), 1.0, EPS, i);
            close(p.normal(), b.sub(a).cross(c.sub(a)).normalize(), EPS, i);
            Vec3f q = rnd.nextVec3f();
            Vec3f onPlane = p.closestPoint(q);
            close(p.distance(onPlane), 0.0, EPS, i);
            close(onPlane.distance(q), Math.abs(p.distance(q)), EPS, i);
            close(p.flip().distance(q), -p.distance(q), EPS, i);
            Planef scaled = new Planef(p.nx() * 3f, p.ny() * 3f, p.nz() * 3f, p.d() * 3f);
            close(scaled.normalize().distance(q), p.distance(q), EPS, i);
        }
        check(new Planef(0f, 0f, 0f, 1f).normalize().equals(new Planef(0f, 0f, 0f, 1f)), 0, "degenerate plane is left alone");
    }

    @Test
    void planeTransformKeepsPointsOnThePlane() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f(), b = rnd.nextVec3f(), c = rnd.nextVec3f();
            if (b.sub(a).cross(c.sub(a)).length() < 0.5f) {
                continue;
            }
            Planef p = Planef.fromPoints(a, b, c);
            Mat4f m = rnd.nextTrsMat4f();
            Planef t = p.transform(m);
            close(t.distance(m.transformPosition(a)), 0.0, EPS * 10, i);
            close(t.distance(m.transformPosition(b)), 0.0, EPS * 10, i);
            close(t.distance(m.transformPosition(c)), 0.0, EPS * 10, i);
        }
    }

    @Test
    void planeClassifiesBoxesAndSpheres() {
        for (int i = 0; i < N; i++) {
            Planef p = Planef.fromPointNormal(rnd.nextVec3f(), rnd.nextVec3f());
            Aabbf box = Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            for (int c = 0; c < 8; c++) {
                float d = p.distance(box.corner(c));
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
            int expected = lo >= 0f ? Containment.INSIDE : hi < 0f ? Containment.OUTSIDE : Containment.INTERSECTING;
            if (Math.abs(lo) > 1e-3f && Math.abs(hi) > 1e-3f) {
                check(Intersectionf.planeAabb(p, box) == expected, i, "plane vs box");
            }
            Spheref s = randomSphere();
            float dist = p.distance(s.center());
            if (Math.abs(Math.abs(dist) - s.radius()) > 1e-3f) {
                int es = dist >= s.radius() ? Containment.INSIDE : dist < -s.radius() ? Containment.OUTSIDE : Containment.INTERSECTING;
                check(Intersectionf.planeSphere(p, s) == es, i, "plane vs sphere");
            }
        }
    }

    // ------------------------------------------------------------ triangle

    private Trianglef randomTriangle() {
        return Trianglef.of(rnd.nextVec3f(), rnd.nextVec3f(), rnd.nextVec3f());
    }

    /** Barycentric coordinates lose precision on slivers, so tolerance-based tests skip them. */
    private static boolean sliver(Trianglef t) {
        float ab = t.b().sub(t.a()).length(), ac = t.c().sub(t.a()).length();
        return t.normal().length() < 0.25f * ab * ac || t.normal().length() < 1f;
    }

    @Test
    void triangleAreaBoundsAndBarycentrics() {
        for (int i = 0; i < N; i++) {
            Trianglef t = randomTriangle();
            if (sliver(t)) {
                continue;
            }
            close(t.area(), t.b().sub(t.a()).cross(t.c().sub(t.a())).length() * 0.5, EPS, i);
            close(t.centroid(), t.a().add(t.b()).add(t.c()).div(3f), EPS, i);
            check(t.aabb().contains(t.a()) && t.aabb().contains(t.b()) && t.aabb().contains(t.c()), i, "bounds contain vertices");
            float u = (float) rnd.range(0, 1), v = (float) rnd.range(0, 1 - u);
            Vec3f p = t.a().mul(1f - u - v).add(t.b().mul(u)).add(t.c().mul(v));
            Vec3f bary = t.barycentric(p);
            close(bary.x(), 1f - u - v, EPS, i);
            close(bary.y(), u, EPS, i);
            close(bary.z(), v, EPS, i);
            close(bary.x() + bary.y() + bary.z(), 1.0, EPS, i);
        }
    }

    @Test
    void triangleClosestPointIsNearestAndOnTheTriangle() {
        for (int i = 0; i < N; i++) {
            Trianglef t = randomTriangle();
            if (sliver(t)) {
                continue;
            }
            Vec3f p = rnd.nextVec3f().mul(2f);
            Vec3f q = t.closestPoint(p);
            Vec3f bary = t.barycentric(q);
            check(bary.x() >= -EPS && bary.y() >= -EPS && bary.z() >= -EPS, i, "closest point lies on the triangle: " + bary);
            close(q.sub(t.a()).dot(t.normal()), 0.0, EPS * 20, i);
            for (int k = 0; k < 12; k++) {
                float u = (float) rnd.range(0, 1), v = (float) rnd.range(0, 1 - u);
                Vec3f r = t.a().mul(1f - u - v).add(t.b().mul(u)).add(t.c().mul(v));
                check(r.distanceSquared(p) >= q.distanceSquared(p) - EPS * 20, i, "no triangle point is nearer");
            }
            close(Intersectionf.pointTriangleDistanceSquared(p, t), q.distanceSquared(p), EPS, i);
            float radius = (float) Math.sqrt(q.distanceSquared(p));
            if (radius > 1e-2f) {
                check(Intersectionf.sphereTriangle(Spheref.of(p, radius * 1.01f), t), i, "sphere just reaching the triangle touches it");
                check(!Intersectionf.sphereTriangle(Spheref.of(p, radius * 0.99f), t), i, "sphere just short does not");
            }
        }
    }

    // ------------------------------------------------------------ oriented box

    private Obbf randomObb() {
        return Obbf.of(rnd.nextVec3f(), rnd.nextScaleVec3f(), rnd.nextUnitQuatf());
    }

    @Test
    void obbBoundsAndContainment() {
        for (int i = 0; i < N; i++) {
            Obbf box = randomObb();
            Vec3f lo = Vec3f.splat(Float.POSITIVE_INFINITY);
            Vec3f hi = Vec3f.splat(Float.NEGATIVE_INFINITY);
            Quatf q = box.rotation();
            for (int c = 0; c < 8; c++) {
                Vec3f local = new Vec3f((c & 1) == 0 ? -box.hx() : box.hx(), (c & 2) == 0 ? -box.hy() : box.hy(),
                        (c & 4) == 0 ? -box.hz() : box.hz());
                Vec3f world = q.transform(local).add(box.center());
                lo = lo.min(world);
                hi = hi.max(world);
                check(box.aabb().contains(world.mul(1f)) || box.aabb().inflate(EPS).contains(world), i, "aabb contains corners");
            }
            Aabbf b = box.aabb();
            close(b.min(), lo, EPS, i);
            close(b.max(), hi, EPS, i);
            close(box.toLocal(box.center()).length(), 0.0, EPS, i);
            check(box.contains(box.center()), i, "centre inside");
            Vec3f far = box.center().add(rnd.nextVec3f().normalize().mul(50f));
            check(!box.contains(far), i, "far point outside");
        }
    }

    @Test
    void obbClosestPointMatchesBruteForce() {
        for (int i = 0; i < N; i++) {
            Obbf box = randomObb();
            Vec3f p = rnd.nextVec3f().mul(2f);
            Vec3f q = box.closestPoint(p);
            check(box.contains(q) || box.toLocal(q).abs().sub(box.halfExtents()).maxComponent() < EPS, i, "closest point is on the box");
            if (box.contains(p)) {
                close(box.distanceSquared(p), 0.0, EPS, i);
            }
            for (int k = 0; k < 12; k++) {
                Vec3f local = new Vec3f((float) rnd.range(-box.hx(), box.hx()), (float) rnd.range(-box.hy(), box.hy()),
                        (float) rnd.range(-box.hz(), box.hz()));
                Vec3f r = box.rotation().transform(local).add(box.center());
                check(r.distanceSquared(p) >= q.distanceSquared(p) - EPS * 10, i, "no box point is nearer");
            }
        }
        Aabbf a = Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
        Obbf fromAabb = Obbf.fromAabb(a);
        close(fromAabb.aabb().min(), a.min(), EPS, 0);
        close(fromAabb.aabb().max(), a.max(), EPS, 0);
    }
}
