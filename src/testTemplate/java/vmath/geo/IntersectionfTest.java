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

/** Ray casts, checked against independently written reference algorithms. */
@GenerateDouble
class IntersectionfTest {
    @Eps(d = 1e-9)
    static final float EPS = 1e-3f;

    static final float MISS = Float.POSITIVE_INFINITY;

    final Rnd rnd = Rnd.create();

    /**
     * A ray that passes exactly through a point within {@code spread} of {@code target}. A fifth of the rays are
     * axis-parallel in one or two axes, which is the classic slab-test trap.
     */
    private Rayf rayToward(Vec3f target, float spread) {
        Vec3f aim = target.add(rnd.nextVec3f().mul(spread / 10f)); // nextVec3f spans [-10, 10]
        Vec3f dir = rnd.nextVec3f();
        double kind = rnd.range(0, 1);
        if (kind < 0.1) {
            dir = new Vec3f(dir.x(), 0f, 0f);
        } else if (kind < 0.2) {
            dir = new Vec3f(0f, dir.y(), 0f);
        } else if (kind < 0.3) {
            dir = new Vec3f(dir.x(), 0f, dir.z());
        }
        if (dir.lengthSquared() < 1e-3f) {
            dir = Vec3f.UNIT_Z;
        }
        dir = dir.normalize().mul((float) rnd.range(0.2, 3));
        float back = (float) rnd.range(-2, 15); // start in front of, inside, or behind the shape
        return Rayf.of(aim.sub(dir.mul(back)), dir);
    }

    // ------------------------------------------------------------ ray vs box

    /** Reference: the ray meets the box iff it starts inside or crosses one of the six faces within the box. */
    private static float referenceRayAabb(Rayf ray, Aabbf box, float tMax) {
        if (box.contains(ray.origin())) {
            return 0f;
        }
        float best = MISS;
        float[] o = {ray.ox(), ray.oy(), ray.oz()};
        float[] d = {ray.dx(), ray.dy(), ray.dz()};
        float[] lo = {box.minX(), box.minY(), box.minZ()};
        float[] hi = {box.maxX(), box.maxY(), box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (d[axis] == 0f) {
                continue;
            }
            for (float plane : new float[] {lo[axis], hi[axis]}) {
                float t = (plane - o[axis]) / d[axis];
                if (t < 0f || t > tMax) {
                    continue;
                }
                boolean inside = true;
                for (int other = 0; other < 3; other++) {
                    if (other != axis) {
                        float c = o[other] + d[other] * t;
                        // exact: a tolerance here would accept crossings just outside an edge and report a slightly earlier t
                        inside &= c >= lo[other] && c <= hi[other];
                    }
                }
                if (inside) {
                    best = Math.min(best, t);
                }
            }
        }
        return best;
    }

    @Test
    void rayAabbMatchesReference() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Aabbf box = Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
            Rayf ray = rayToward(box.center(), box.halfSize().length() * 1.2f + 0.1f);
            float tMax = rnd.range(0, 1) < 0.5 ? MISS : (float) rnd.range(0.5, 10);
            float t = Intersectionf.rayAabb(ray, box, tMax);
            float ref = referenceRayAabb(ray, box, tMax);
            // tolerate only razor-thin grazing differences: both must agree unless within 1e-3 of an edge
            if ((t == MISS) != (ref == MISS)) {
                Aabbf fat = box.inflate(2e-3f), thin = box.inflate(-2e-3f);
                boolean grazing = Intersectionf.rayAabb(ray, fat, tMax) != Intersectionf.rayAabb(ray, thin, tMax);
                check(grazing || tMax != MISS && Math.min(t, ref) > tMax - 1e-3f, i,
                        "hit/miss disagree: t=" + t + " ref=" + ref + " ray=" + ray + " box=" + box);
            } else if (t != MISS) {
                close(t, ref, EPS, i);
                hits++;
            }
        }
        check(hits > N / 20, 0, "too few hits to be meaningful: " + hits);
    }

    @Test
    void rayAabbHitPointsLieOnTheBox() {
        for (int i = 0; i < N; i++) {
            Aabbf box = Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
            Rayf ray = rayToward(box.center(), box.halfSize().length() * 1.2f + 0.1f);
            float t = Intersectionf.rayAabb(ray, box, MISS);
            if (t != MISS) {
                check(box.inflate(EPS).contains(ray.pointAt(t)), i, "hit point must be on the box");
            }
        }
        // an origin inside the box reports 0, an origin on a face parallel to the ray still hits
        Aabbf unit = Aabbf.of(Vec3f.ZERO, Vec3f.ONE);
        close(Intersectionf.rayAabb(Rayf.of(new Vec3f(0.5f, 0.5f, 0.5f), Vec3f.UNIT_X), unit, MISS), 0.0, EPS, 0);
        close(Intersectionf.rayAabb(Rayf.of(new Vec3f(-1f, 0f, 0.5f), Vec3f.UNIT_X), unit, MISS), 1.0, EPS, 0);
        check(Intersectionf.rayAabb(Rayf.of(new Vec3f(-1f, 2f, 0.5f), Vec3f.UNIT_X), unit, MISS) == MISS, 0, "parallel and outside");
        check(Intersectionf.rayAabb(Rayf.of(new Vec3f(2f, 0.5f, 0.5f), Vec3f.UNIT_X), unit, MISS) == MISS, 0, "pointing away");
        check(Intersectionf.rayAabb(Rayf.of(new Vec3f(-5f, 0.5f, 0.5f), Vec3f.UNIT_X), unit, 3f) == MISS, 0, "beyond tMax");
    }

    // ------------------------------------------------------------ ray vs sphere and plane

    @Test
    void raySphereMatchesClosestApproach() {
        int hits = 0;
        for (int i = 0; i < N; i++) {
            Spheref s = Spheref.of(rnd.nextVec3f(), (float) rnd.range(0.3, 6));
            Rayf ray = rayToward(s.center(), s.radius() * 1.5f);
            float t = Intersectionf.raySphere(ray, s, MISS);
            Vec3f l = ray.origin().sub(s.center());
            float a = ray.direction().lengthSquared();
            float tStar = -l.dot(ray.direction()) / a;
            float dist2 = l.add(ray.direction().mul(tStar)).lengthSquared();
            boolean inside = l.lengthSquared() <= s.radius() * s.radius();
            boolean expectHit = inside || (tStar >= 0f && dist2 <= s.radius() * s.radius());
            if (Math.abs(Math.sqrt(dist2) - s.radius()) > 1e-2 && Math.abs(l.length() - s.radius()) > 1e-2) {
                check((t != MISS) == expectHit, i, "raySphere hit/miss: t=" + t + " expected " + expectHit);
            }
            if (t != MISS) {
                hits++;
                if (!inside) {
                    close(ray.pointAt(t).distance(s.center()), s.radius(), EPS * 10, i);
                } else {
                    close(t, 0.0, EPS, i);
                }
            }
        }
        check(hits > N / 20, 0, "too few hits: " + hits);
    }

    @Test
    void rayPlaneHitsFromEitherSide() {
        for (int i = 0; i < N; i++) {
            Planef p = Planef.fromPointNormal(rnd.nextVec3f(), rnd.nextVec3f());
            Rayf ray = rayToward(rnd.nextVec3f(), 10f);
            float t = Intersectionf.rayPlane(ray, p, MISS);
            float denom = p.normal().dot(ray.direction());
            if (Math.abs(denom) < 1e-4f) {
                continue;
            }
            float expected = -p.distance(ray.origin()) / denom;
            if (expected >= 0f) {
                close(t, expected, EPS, i);
                close(p.distance(ray.pointAt(t)), 0.0, EPS * 10, i);
            } else {
                check(t == MISS, i, "plane behind the ray");
            }
            if (expected >= 0f && Math.abs(expected) > 1e-3f) {
                check(Intersectionf.rayPlane(ray, p, expected * 0.5f) == MISS, i, "tMax before the plane");
            }
        }
        check(Intersectionf.rayPlane(Rayf.of(Vec3f.ZERO, Vec3f.UNIT_X), new Planef(0f, 1f, 0f, -1f), MISS) == MISS, 0, "parallel");
    }

    // ------------------------------------------------------------ ray vs triangle

    /** Moeller-Trumbore in double: a different algorithm, used as the oracle. Returns {t, u, v} or null. */
    private static double[] mollerTrumbore(Rayf ray, Trianglef tri) {
        double[] o = {ray.ox(), ray.oy(), ray.oz()};
        double[] d = {ray.dx(), ray.dy(), ray.dz()};
        double[] a = {tri.ax(), tri.ay(), tri.az()};
        double[] e1 = {tri.bx() - a[0], tri.by() - a[1], tri.bz() - a[2]};
        double[] e2 = {tri.cx() - a[0], tri.cy() - a[1], tri.cz() - a[2]};
        double[] p = cross(d, e2);
        double det = dot(e1, p);
        if (Math.abs(det) < 1e-12) {
            return null;
        }
        double inv = 1.0 / det;
        double[] s = {o[0] - a[0], o[1] - a[1], o[2] - a[2]};
        double u = dot(s, p) * inv;
        double[] q = cross(s, e1);
        double v = dot(d, q) * inv;
        double t = dot(e2, q) * inv;
        return new double[] {t, u, v};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Test
    void rayTriangleMatchesMollerTrumbore() {
        int hits = 0;
        int compared = 0;
        for (int i = 0; i < N * 2; i++) {
            Trianglef tri = Trianglef.of(rnd.nextVec3f(), rnd.nextVec3f(), rnd.nextVec3f());
            if (tri.normal().length() < 1f) {
                continue;
            }
            Rayf ray = rayToward(tri.centroid(), 4f);
            double[] ref = mollerTrumbore(ray, tri);
            if (ref == null) {
                continue;
            }
            double u = ref[1], v = ref[2], w = 1 - u - v;
            double margin = Math.min(Math.min(u, v), Math.min(w, ref[0]));
            boolean nearEdge = Math.abs(u) < 1e-3 || Math.abs(v) < 1e-3 || Math.abs(w) < 1e-3 || Math.abs(ref[0]) < 1e-3;
            if (nearEdge) {
                continue;
            }
            compared++;
            float t = Intersectionf.rayTriangle(ray, tri, MISS);
            boolean refHit = margin > 0;
            check((t != MISS) == refHit, i, "hit/miss: t=" + t + " ref=" + java.util.Arrays.toString(ref));
            if (refHit) {
                close(t, ref[0], EPS, i);
                hits++;
            }
        }
        check(hits > N / 20 && compared > N / 2, 0, "too few samples: hits " + hits + " compared " + compared);
    }

    @Test
    void rayTriangleTMaxAndBackfaces() {
        Trianglef tri = Trianglef.of(new Vec3f(-1f, -1f, 0f), new Vec3f(1f, -1f, 0f), new Vec3f(0f, 1f, 0f));
        Rayf front = Rayf.of(new Vec3f(0f, 0f, 5f), new Vec3f(0f, 0f, -1f));
        Rayf back = Rayf.of(new Vec3f(0f, 0f, -5f), new Vec3f(0f, 0f, 1f));
        close(Intersectionf.rayTriangle(front, tri, MISS), 5.0, EPS, 0);
        close(Intersectionf.rayTriangle(back, tri, MISS), 5.0, EPS, 0);
        check(Intersectionf.rayTriangle(front, tri, 4f) == MISS, 0, "beyond tMax");
        check(Intersectionf.rayTriangle(Rayf.of(new Vec3f(0f, 0f, -5f), new Vec3f(0f, 0f, -1f)), tri, MISS) == MISS, 0,
                "triangle behind the ray");
        check(Intersectionf.rayTriangle(Rayf.of(new Vec3f(0f, 0f, 5f), Vec3f.UNIT_X), tri, MISS) == MISS, 0, "parallel");
        check(Intersectionf.rayTriangle(Rayf.of(Vec3f.ZERO, Vec3f.ZERO), tri, MISS) == MISS, 0, "zero direction");
    }

    private Vec3f intVec(int lo, int hi, int scale) {
        return new Vec3f((int) rnd.range(lo, hi) * scale, (int) rnd.range(lo, hi) * scale, (int) rnd.range(lo, hi) * scale);
    }

    /**
     * All coordinates are small integers, so every edge function is computed exactly and the ray passes exactly
     * through the shared edge. A watertight test must then hit at least one of the two triangles, for rays that see
     * both from the same side. (With inexact float targets a ray can legitimately pass through the wedge between two
     * folded triangles, and a ray in the fold's silhouette sees one face from each side.)
     */
    @Test
    void rayTriangleIsWatertightAcrossSharedEdges() {
        int checked = 0;
        for (int i = 0; i < N * 4; i++) {
            Vec3f p = intVec(-4, 4, 2), q = intVec(-4, 4, 2);
            Vec3f r = intVec(-6, 6, 1), s = intVec(-6, 6, 1);
            Vec3f edge = q.sub(p);
            Trianglef t1 = Trianglef.of(p, q, r);
            Trianglef t2 = Trianglef.of(q, p, s);
            if (edge.length() < 1f || t1.normal().length() < 1f || t2.normal().length() < 1f) {
                continue;
            }
            // a manifold mesh: the triangles lie on opposite sides of the shared edge
            if (r.sub(p).reject(edge).dot(s.sub(p).reject(edge)) >= 0f) {
                continue;
            }
            Vec3f target = p.add(q).mul(0.5f); // exact: p and q have even coordinates
            Vec3f origin = intVec(-9, 9, 1);
            Vec3f dir = target.sub(origin);
            if (dir.lengthSquared() < 1f || dir.cross(edge).lengthSquared() < 1f) {
                continue; // no ray, or a ray running along the edge
            }
            // the guarantee concerns a ray that sees both triangles from the same side
            float side1 = dir.dot(t1.normal()), side2 = dir.dot(t2.normal());
            if (side1 == 0f || side2 == 0f || (side1 < 0f) != (side2 < 0f)) {
                continue;
            }
            Rayf ray = Rayf.of(origin, dir);
            checked++;
            boolean hit = Intersectionf.rayTriangle(ray, t1, MISS) != MISS || Intersectionf.rayTriangle(ray, t2, MISS) != MISS;
            check(hit, i, "ray through shared edge slipped through: " + ray + " " + t1 + " " + t2);
        }
        check(checked > N / 2, 0, "too few samples: " + checked);
    }
}
