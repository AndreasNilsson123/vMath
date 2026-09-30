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

/** The shape operations added for API consistency (see {@code ApiParityTest}), each checked against a definition or a brute-force reference. */
@GenerateDouble
class ShapeParityfTest {
    @Eps(d = 1e-10)
    static final float EPS = 2e-4f;

    final Rnd rnd = Rnd.create();

    @Test
    void sphereClosestPointIsNearestAndIdentityInside() {
        for (int i = 0; i < N; i++) {
            Vec3f c = rnd.nextVec3f().mul(3f);
            float r = (float) rnd.range(0.1, 3);
            Spheref s = Spheref.of(c, r);
            Vec3f p = rnd.nextVec3f().mul(6f);
            Vec3f q = s.closestPoint(p);
            float dist = p.distance(c);
            if (dist <= r) {
                check(q.equals(p), i, "a point inside is its own closest point");
            } else {
                close(q.distance(c), r, EPS * 4, i);
                close(p.distance(q), dist - r, EPS * 4, i);
                // nothing on the surface is nearer than q: sample directions
                for (int k = 0; k < 8; k++) {
                    Vec3f dir = rnd.nextVec3f().normalize();
                    Vec3f onSurface = c.add(dir.mul(r));
                    check(p.distance(onSurface) >= p.distance(q) - EPS * 4, i, "closest point must be nearest");
                }
            }
            check(s.contains(q) || q.distance(c) <= r + EPS * 4, i, "the result is on or in the sphere");
        }
    }

    @Test
    void rayClosestPointClampsBehindTheOriginAndMatchesSampling() {
        for (int i = 0; i < N; i++) {
            Vec3f o = rnd.nextVec3f().mul(3f);
            Vec3f d = rnd.nextVec3f();
            if (d.length() < 0.2f) {
                continue;
            }
            Rayf ray = Rayf.of(o, d);
            Vec3f p = rnd.nextVec3f().mul(6f);
            Vec3f q = ray.closestPoint(p);
            float t = p.sub(o).dot(d) / d.lengthSquared();
            if (t <= 0f) {
                close(q, o, EPS, i);
            } else {
                close(q, ray.pointAt(t), EPS * 4, i);
                // the offset from the query point is perpendicular to the ray
                close(p.sub(q).dot(d), 0.0, EPS * 40, i);
            }
            double best = Double.MAX_VALUE;
            for (int k = 0; k <= 200; k++) {
                best = Math.min(best, p.distance(ray.pointAt(k * 0.1f)));
            }
            check(p.distance(q) <= best + EPS * 4, i, "no sampled point of the ray is nearer than the closest point");
        }
        Rayf point = new Rayf(1f, 2f, 3f, 0f, 0f, 0f);
        close(point.closestPoint(new Vec3f(9f, 9f, 9f)), new Vec3f(1f, 2f, 3f), EPS, 0);
    }

    @Test
    void triangleTransformMovesTheVerticesAndScalesTheArea() {
        for (int i = 0; i < N; i++) {
            Trianglef t = Trianglef.of(rnd.nextVec3f(), rnd.nextVec3f(), rnd.nextVec3f());
            Mat4f m = rnd.nextTrsMat4f();
            Trianglef u = t.transform(m);
            close(u.a(), m.transformPosition(t.a()), EPS * 4, i);
            close(u.b(), m.transformPosition(t.b()), EPS * 4, i);
            close(u.c(), m.transformPosition(t.c()), EPS * 4, i);
            close(t.transform(Mat4f.IDENTITY).a(), t.a(), EPS, i);
            Mat4f scale = Mat4f.scaling(2f, 2f, 2f);
            close(t.transform(scale).area(), t.area() * 4f, EPS * 40, i);
            Trianglef mirrored = t.transform(Mat4f.scaling(-1f, 1f, 1f));
            // a reflection M gives (Mu) x (Mv) = -M (u x v): for the mirror x -> -x the normal becomes (n.x, -n.y, -n.z)
            Vec3f n = t.normal();
            close(mirrored.normal(), new Vec3f(n.x(), -n.y(), -n.z()), EPS * 40, i);
        }
    }
}
