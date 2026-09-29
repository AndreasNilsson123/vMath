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

@GenerateDouble
class AabbfTest {
    @Eps(d = 1e-11)
    static final float EPS = 1e-4f;

    final Rnd rnd = Rnd.create();

    private Aabbf randomBox() {
        return Aabbf.of(rnd.nextVec3f(), rnd.nextVec3f());
    }

    private void closeBox(Aabbf a, Aabbf e, int trial) {
        close(a.minX(), e.minX(), EPS, trial);
        close(a.minY(), e.minY(), EPS, trial);
        close(a.minZ(), e.minZ(), EPS, trial);
        close(a.maxX(), e.maxX(), EPS, trial);
        close(a.maxY(), e.maxY(), EPS, trial);
        close(a.maxZ(), e.maxZ(), EPS, trial);
    }

    @Test
    void constructionAndAccessors() {
        for (int i = 0; i < N; i++) {
            Vec3f a = rnd.nextVec3f();
            Vec3f b = rnd.nextVec3f();
            Aabbf box = Aabbf.of(a, b);
            close(box.min(), a.min(b), EPS, i);
            close(box.max(), a.max(b), EPS, i);
            close(box.center(), a.add(b).mul(0.5f), EPS, i);
            close(box.size(), a.sub(b).abs(), EPS, i);
            close(box.halfSize(), a.sub(b).abs().mul(0.5f), EPS, i);
            closeBox(Aabbf.fromCenterHalfExtent(box.center(), box.halfSize()), box, i);
            check(!box.isEmpty(), i, "a box built from two points is never empty");
            for (int c = 0; c < 8; c++) {
                Vec3f corner = box.corner(c);
                check(corner.x() == ((c & 1) == 0 ? box.minX() : box.maxX())
                        && corner.y() == ((c & 2) == 0 ? box.minY() : box.maxY())
                        && corner.z() == ((c & 4) == 0 ? box.minZ() : box.maxZ()), i, "corner bit layout " + c);
            }
        }
        check(Aabbf.EMPTY.isEmpty(), 0, "EMPTY is empty");
        check(Aabbf.EMPTY.volume() == 0f && Aabbf.EMPTY.surfaceArea() == 0f, 0, "empty has no volume or area");
    }

    @Test
    void fromPointsMatchesComponentwiseMinMax() {
        for (int i = 0; i < N / 10; i++) {
            int n = 1 + (int) rnd.range(0, 30);
            float[] pts = new float[3 + n * 3];
            Vec3f lo = Vec3f.splat(Float.POSITIVE_INFINITY);
            Vec3f hi = Vec3f.splat(Float.NEGATIVE_INFINITY);
            for (int k = 0; k < n; k++) {
                Vec3f p = rnd.nextVec3f();
                p.writeTo(pts, 3 + k * 3);
                lo = lo.min(p);
                hi = hi.max(p);
            }
            closeBox(Aabbf.fromPoints(pts, 3, n), Aabbf.of(lo, hi), i);
        }
        check(Aabbf.fromPoints(new float[0], 0, 0).isEmpty(), 0, "no points is an empty box");
    }

    @Test
    void unionIntersectionAndOverlap() {
        for (int i = 0; i < N; i++) {
            Aabbf a = randomBox();
            Aabbf b = randomBox();
            Aabbf u = a.union(b);
            check(u.contains(a) && u.contains(b), i, "union must contain both");
            closeBox(Aabbf.EMPTY.union(a), a, i);
            closeBox(a.union(a.center()), a, i);
            Aabbf x = a.intersection(b);
            check(x.isEmpty() == !a.overlaps(b), i, "intersection is empty exactly when the boxes do not overlap");
            check(a.overlaps(b) == b.overlaps(a), i, "overlap is symmetric");
            if (!x.isEmpty()) {
                check(a.contains(x) && b.contains(x), i, "intersection lies in both");
            }
            // sampled points agree with the containment predicates
            Vec3f p = rnd.nextVec3f();
            check(u.contains(p) || !(a.contains(p) || b.contains(p)), i, "a point in a or b is in the union");
            check(x.isEmpty() || (x.contains(p) == (a.contains(p) && b.contains(p))), i, "intersection contains exactly the common points");
        }
    }

    @Test
    void volumeSurfaceAreaAndInflate() {
        for (int i = 0; i < N; i++) {
            Aabbf box = randomBox();
            Vec3f s = box.size();
            close(box.volume(), s.x() * s.y() * s.z(), EPS, i);
            close(box.surfaceArea(), 2 * (s.x() * s.y() + s.y() * s.z() + s.z() * s.x()), EPS, i);
            float m = (float) rnd.range(0, 2);
            Aabbf inflated = box.inflate(m);
            close(inflated.size(), s.add(Vec3f.splat(2 * m)), EPS, i);
            close(inflated.center(), box.center(), EPS, i);
            check(inflated.contains(box), i, "inflated contains original");
        }
    }

    @Test
    void closestPointAndDistance() {
        for (int i = 0; i < N; i++) {
            Aabbf box = randomBox();
            Vec3f p = rnd.nextVec3f().mul(2f);
            Vec3f q = box.closestPoint(p);
            check(box.contains(q), i, "closest point lies in the box");
            close(box.distanceSquared(p), q.distanceSquared(p), EPS * 10, i);
            if (box.contains(p)) {
                close(box.distanceSquared(p), 0.0, EPS, i);
            }
            // no other box point is nearer: probe with random points of the box
            for (int k = 0; k < 8; k++) {
                Vec3f r = box.min().add(box.size().mul(new Vec3f((float) rnd.range(0, 1),
                        (float) rnd.range(0, 1), (float) rnd.range(0, 1))));
                check(r.distanceSquared(p) >= q.distanceSquared(p) - EPS * 10, i, "closest point must be nearest");
            }
        }
    }

    @Test
    void affineTransformIsTheTightBoundOfTheTransformedCorners() {
        for (int i = 0; i < N; i++) {
            Aabbf box = randomBox();
            Mat4f m = rnd.nextTrsMat4f();
            Vec3f lo = Vec3f.splat(Float.POSITIVE_INFINITY);
            Vec3f hi = Vec3f.splat(Float.NEGATIVE_INFINITY);
            for (int c = 0; c < 8; c++) {
                Vec3f p = m.transformPosition(box.corner(c));
                lo = lo.min(p);
                hi = hi.max(p);
            }
            Aabbf t = box.transform(m);
            close(t.min(), lo, EPS * 10, i);
            close(t.max(), hi, EPS * 10, i);
        }
    }

    @Test
    void boundingSphereContainsAllCorners() {
        for (int i = 0; i < N; i++) {
            Aabbf box = randomBox();
            Spheref s = box.boundingSphere();
            for (int c = 0; c < 8; c++) {
                check(box.corner(c).distance(s.center()) <= s.radius() * (1f + EPS), i, "corner inside bounding sphere");
            }
            check(s.aabb().contains(box), i, "sphere box contains the box");
        }
    }
}
