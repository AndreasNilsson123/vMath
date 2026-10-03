package vmath.geo;

import vmath.annotations.Eps;
import vmath.annotations.GenerateDouble;
import vmath.core.Mat3f;
import vmath.core.Vec3f;

/**
 * Ray casts and shape queries.
 *
 * <p>Ray routines return the distance {@code t} along the ray (in units of the ray direction) of
 * the first hit within {@code [0, tMax]}, or {@code +Infinity} for a miss, so {@code t < tMax} and
 * {@code Math.min} compose naturally when searching for the nearest hit. A ray that starts inside a
 * solid volume reports {@code t = 0}.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Rayf ray = Rayf.of(new Vec3f(0f, 5f, 0f), new Vec3f(0f, -1f, 0f));
 * float tBox = Intersectionf.rayAabb(ray, Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f)), 100f);   // 4
 * boolean hit = tBox != Float.POSITIVE_INFINITY;                    // misses are reported as positive infinity
 * boolean spheres = Intersectionf.sphereCapsule(Spheref.of(Vec3f.ZERO, 1f), Capsulef.of(new Vec3f(0f, 1f, 0f), new Vec3f(0f, 3f, 0f), 0.5f));
 * }</pre>
 */
@GenerateDouble
public final class Intersectionf {

    private Intersectionf() {
    }

    /**
     * Intersects a ray with a box with the slab method, clipping the ray interval against each pair
     * of parallel planes; a ray that runs parallel to a slab and outside it misses.
     *
     * <p>Handles axis-parallel rays exactly (no NaN from 0 * infinity).
     *
     * @param ray the ray; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return slab test
     */
    public static float rayAabb(Rayf ray, Aabbf box, float tMax) {
        float tNear = 0f;
        float tFar = tMax;
        for (int axis = 0; axis < 3; axis++) {
            float o = axis == 0 ? ray.ox() : axis == 1 ? ray.oy() : ray.oz();
            float d = axis == 0 ? ray.dx() : axis == 1 ? ray.dy() : ray.dz();
            float lo = axis == 0 ? box.minX() : axis == 1 ? box.minY() : box.minZ();
            float hi = axis == 0 ? box.maxX() : axis == 1 ? box.maxY() : box.maxZ();
            if (d == 0f) {
                if (o < lo || o > hi) {
                    return Float.POSITIVE_INFINITY;
                }
            } else {
                float inv = 1f / d;
                float t1 = (lo - o) * inv;
                float t2 = (hi - o) * inv;
                tNear = Math.max(tNear, Math.min(t1, t2));
                tFar = Math.min(tFar, Math.max(t1, t2));
                if (tNear > tFar) {
                    return Float.POSITIVE_INFINITY;
                }
            }
        }
        return tNear;
    }

    /**
     * Intersects a ray with a sphere by solving a quadratic; the parameter is in units of the ray
     * direction, so normalise the direction to get a distance.
     *
     * @param ray the ray; must not be {@code null}
     * @param s the sphere; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return the distance {@code t} along the ray to the first point of the sphere, in units of
     *     the ray's direction: 0 when the origin is inside, {@code +Infinity} for a miss, for a
     *     sphere behind the origin and when the hit is beyond {@code tMax}
     */
    public static float raySphere(Rayf ray, Spheref s, float tMax) {
        float lx = ray.ox() - s.cx(), ly = ray.oy() - s.cy(), lz = ray.oz() - s.cz();
        float c = lx * lx + ly * ly + lz * lz - s.radius() * s.radius();
        if (c <= 0f) {
            return 0f;
        }
        float a = ray.dx() * ray.dx() + ray.dy() * ray.dy() + ray.dz() * ray.dz();
        float b = lx * ray.dx() + ly * ray.dy() + lz * ray.dz();
        if (b >= 0f || a == 0f) {
            return Float.POSITIVE_INFINITY; // outside and moving away
        }
        float disc = b * b - a * c;
        if (disc < 0f) {
            return Float.POSITIVE_INFINITY;
        }
        float t = (-b - (float) Math.sqrt(disc)) / a;
        return t <= tMax ? t : Float.POSITIVE_INFINITY;
    }

    /**
     * Hits the plane from either side.
     *
     * <p>A ray parallel to the plane misses.
     *
     * @param ray the ray; must not be {@code null}
     * @param p the plane; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return the ray parameter {@code t} of the hit, or {@link Float#POSITIVE_INFINITY} if the ray
     *     misses the plane or the hit lies beyond {@code tMax}
     */
    public static float rayPlane(Rayf ray, Planef p, float tMax) {
        float denom = p.nx() * ray.dx() + p.ny() * ray.dy() + p.nz() * ray.dz();
        if (denom == 0f) {
            return Float.POSITIVE_INFINITY;
        }
        float t = -(p.nx() * ray.ox() + p.ny() * ray.oy() + p.nz() * ray.oz() + p.d()) / denom;
        return t >= 0f && t <= tMax ? t : Float.POSITIVE_INFINITY;
    }

    private static float comp(float x, float y, float z, int k) {
        return k == 0 ? x : k == 1 ? y : z;
    }

    /**
     * Intersects a ray with a triangle with the watertight algorithm of Woop, Benthin and Wald,
     * which does not leak through the shared edges of a mesh; the test is two-sided, so back faces
     * hit as well.
     *
     * <p>Edge functions that come out zero are recomputed in double precision.
     *
     * @param ray the ray; must not be {@code null}
     * @param tri the tri; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return watertight two-sided ray-triangle test (Woop, Benthin and Wald 2013): rays that pass
     *     exactly through a shared edge or vertex of adjacent triangles hit at least one of them,
     *     never slip between
     */
    public static float rayTriangle(Rayf ray, Trianglef tri, float tMax) {
        float dx = ray.dx(), dy = ray.dy(), dz = ray.dz();
        float adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
        int kz = adx > ady ? (adx > adz ? 0 : 2) : (ady > adz ? 1 : 2);
        int kx = kz == 2 ? 0 : kz + 1;
        int ky = kx == 2 ? 0 : kx + 1;
        if (comp(dx, dy, dz, kz) < 0f) {
            int swap = kx;
            kx = ky;
            ky = swap;
        }
        float dkz = comp(dx, dy, dz, kz);
        if (dkz == 0f) {
            return Float.POSITIVE_INFINITY; // zero-length direction
        }
        float sx = comp(dx, dy, dz, kx) / dkz;
        float sy = comp(dx, dy, dz, ky) / dkz;
        float sz = 1f / dkz;

        float ax = tri.ax() - ray.ox(), ay = tri.ay() - ray.oy(), az = tri.az() - ray.oz();
        float bx = tri.bx() - ray.ox(), by = tri.by() - ray.oy(), bz = tri.bz() - ray.oz();
        float cx = tri.cx() - ray.ox(), cy = tri.cy() - ray.oy(), cz = tri.cz() - ray.oz();
        float akz = comp(ax, ay, az, kz), bkz = comp(bx, by, bz, kz), ckz = comp(cx, cy, cz, kz);
        float axp = comp(ax, ay, az, kx) - sx * akz, ayp = comp(ax, ay, az, ky) - sy * akz;
        float bxp = comp(bx, by, bz, kx) - sx * bkz, byp = comp(bx, by, bz, ky) - sy * bkz;
        float cxp = comp(cx, cy, cz, kx) - sx * ckz, cyp = comp(cx, cy, cz, ky) - sy * ckz;

        float u = cxp * byp - cyp * bxp;
        float v = axp * cyp - ayp * cxp;
        float w = bxp * ayp - byp * axp;
        if (u == 0f || v == 0f || w == 0f) {
            double axd = axp, ayd = ayp, bxd = bxp, byd = byp, cxd = cxp, cyd = cyp;
            double ud = cxd * byd - cyd * bxd;
            double vd = axd * cyd - ayd * cxd;
            double wd = bxd * ayd - byd * axd;
            u = (float) ud;
            v = (float) vd;
            w = (float) wd;
        }
        if ((u < 0f || v < 0f || w < 0f) && (u > 0f || v > 0f || w > 0f)) {
            return Float.POSITIVE_INFINITY;
        }
        float det = u + v + w;
        if (det == 0f) {
            return Float.POSITIVE_INFINITY;
        }
        float t = u * (sz * akz) + v * (sz * bkz) + w * (sz * ckz);
        if (det < 0f ? (t > 0f || t < tMax * det) : (t < 0f || t > tMax * det)) {
            return Float.POSITIVE_INFINITY;
        }
        return t / det;
    }

    /**
     * Returns which side of {@code p} the box is on: {@link Containment#INSIDE} = fully in front,
     * {@code OUTSIDE} = behind.
     *
     * @param p the plane; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @return {@link Containment#INSIDE} if the box is fully in front of the plane,
     *     {@link Containment#OUTSIDE} if it is behind and {@link Containment#INTERSECTING} if it
     *     straddles
     */
    public static int planeAabb(Planef p, Aabbf box) {
        float cx = (box.minX() + box.maxX()) * 0.5f, cy = (box.minY() + box.maxY()) * 0.5f;
        float cz = (box.minZ() + box.maxZ()) * 0.5f;
        float r = (box.maxX() - box.minX()) * 0.5f * Math.abs(p.nx())
                + (box.maxY() - box.minY()) * 0.5f * Math.abs(p.ny())
                + (box.maxZ() - box.minZ()) * 0.5f * Math.abs(p.nz());
        float s = p.nx() * cx + p.ny() * cy + p.nz() * cz + p.d();
        return s - r >= 0f ? Containment.INSIDE : s + r < 0f ? Containment.OUTSIDE : Containment.INTERSECTING;
    }

    /**
     * Returns which side of {@code p} the sphere is on; the plane normal must be unit length.
     *
     * @param p the plane; must not be {@code null}
     * @param s the sphere; must not be {@code null}
     * @return {@link Containment#INSIDE} if the sphere is in front of the plane,
     *     {@link Containment#OUTSIDE} if it is behind and {@link Containment#INTERSECTING} if it
     *     straddles
     */
    public static int planeSphere(Planef p, Spheref s) {
        float dist = p.distance(s.center());
        return dist >= s.radius() ? Containment.INSIDE : dist < -s.radius() ? Containment.OUTSIDE : Containment.INTERSECTING;
    }

    /**
     * Measures the squared distance from a point to a triangle by finding the closest point on its
     * face, edges and vertices, which avoids the square root.
     *
     * @param p the vector; must not be {@code null}
     * @param tri the tri; must not be {@code null}
     * @return squared distance between a point and a triangle
     */
    public static float pointTriangleDistanceSquared(Vec3f p, Trianglef tri) {
        return tri.closestPoint(p).distanceSquared(p);
    }

    /**
     * Tests a sphere against a triangle by comparing the squared distance to the triangle with the
     * squared radius.
     *
     * @param s the sphere; must not be {@code null}
     * @param tri the tri; must not be {@code null}
     * @return {@code true} when the sphere touches the triangle
     */
    public static boolean sphereTriangle(Spheref s, Trianglef tri) {
        return pointTriangleDistanceSquared(s.center(), tri) <= s.radius() * s.radius();
    }

    // ---------------------------------------------------------------- segments and capsules
    //
    // Overlap predicates below return true when they cannot exclude an overlap (a NaN input, for example), like the culling code: they are
    // written as "not separated", never as "overlapping".

    /**
     * Measures the squared distance between two segments with the closest-point method of Ericson,
     * which handles parallel segments and degenerate segments that are points.
     *
     * <p>Segments that are points, parallel, or end-to-end are handled.
     *
     * @param s the segment; must not be {@code null}
     * @param t the segment; must not be {@code null}
     * @return squared distance between two segments (Ericson, Real-Time Collision Detection 5.1.9)
     */
    public static float segmentSegmentDistanceSquared(Segmentf s, Segmentf t) {
        return closestSegmentPoints(s, t, null);
    }

    /**
     * Returns like {@link #segmentSegmentDistanceSquared}, and also writes the parameters of the
     * two nearest points to {@code st[0]} (on {@code s}) and {@code st[1]} (on {@code t}), each in
     * [0, 1].
     *
     * <p>Returns the squared distance.
     *
     * @param s the segment; must not be {@code null}
     * @param t the segment; must not be {@code null}
     * @param st receives the parameters of the nearest points in {@code [0, 2)}
     * @return the squared distance
     */
    public static float segmentSegmentClosestParameters(Segmentf s, Segmentf t, float[] st) {
        return closestSegmentPoints(s, t, st);
    }

    private static float closestSegmentPoints(Segmentf s, Segmentf t, float[] st) {
        float d1x = s.bx() - s.ax(), d1y = s.by() - s.ay(), d1z = s.bz() - s.az();
        float d2x = t.bx() - t.ax(), d2y = t.by() - t.ay(), d2z = t.bz() - t.az();
        float rx = s.ax() - t.ax(), ry = s.ay() - t.ay(), rz = s.az() - t.az();
        float a = d1x * d1x + d1y * d1y + d1z * d1z;
        float e = d2x * d2x + d2y * d2y + d2z * d2z;
        float f = d2x * rx + d2y * ry + d2z * rz;
        float u, v; // parameters on s and t
        if (!(a > 0f) && !(e > 0f)) {
            u = 0f;
            v = 0f;
        } else if (!(a > 0f)) {
            u = 0f;
            v = Math.min(Math.max(f / e, 0f), 1f);
        } else {
            float c = d1x * rx + d1y * ry + d1z * rz;
            if (!(e > 0f)) {
                v = 0f;
                u = Math.min(Math.max(-c / a, 0f), 1f);
            } else {
                float b = d1x * d2x + d1y * d2y + d1z * d2z;
                float denom = a * e - b * b;
                u = denom > 0f ? Math.min(Math.max((b * f - c * e) / denom, 0f), 1f) : 0f;
                v = (b * u + f) / e;
                if (v < 0f) {
                    v = 0f;
                    u = Math.min(Math.max(-c / a, 0f), 1f);
                } else if (v > 1f) {
                    v = 1f;
                    u = Math.min(Math.max((b - c) / a, 0f), 1f);
                }
            }
        }
        if (st != null) {
            st[0] = u;
            st[1] = v;
        }
        float px = s.ax() + d1x * u - (t.ax() + d2x * v);
        float py = s.ay() + d1y * u - (t.ay() + d2y * v);
        float pz = s.az() + d1z * u - (t.az() + d2z * v);
        return px * px + py * py + pz * pz;
    }

    /**
     * Tests two capsules by comparing the distance between their axes with the sum of the radii.
     *
     * @param a the first capsule; must not be {@code null}
     * @param b the second capsule; must not be {@code null}
     * @return {@code true} when the two capsules share a point
     */
    public static boolean capsuleCapsule(Capsulef a, Capsulef b) {
        float r = a.radius() + b.radius();
        return !(segmentSegmentDistanceSquared(a.segment(), b.segment()) > r * r);
    }

    /**
     * Tests a sphere against a capsule by comparing the distance from the centre to the axis with
     * the sum of the radii.
     *
     * @param s the sphere; must not be {@code null}
     * @param c the capsule; must not be {@code null}
     * @return {@code true} when the sphere and the capsule share a point
     */
    public static boolean sphereCapsule(Spheref s, Capsulef c) {
        float r = s.radius() + c.radius();
        return !(c.segment().distanceSquared(s.center()) > r * r);
    }

    /**
     * Measures the squared distance between a segment and a box.
     *
     * <p>Exact: the squared distance from a point moving along the segment to the box is a convex,
     * piecewise quadratic function of the parameter, with pieces that change only where the segment
     * crosses one of the six planes of the box, so each piece is minimised in closed form.
     *
     * @param seg the seg; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @return squared distance between a segment and an axis-aligned box (0 when they touch or the
     *     segment is inside)
     */
    public static float segmentAabbDistanceSquared(Segmentf seg, Aabbf box) {
        float[] p0 = {seg.ax(), seg.ay(), seg.az()};
        float[] d = {seg.bx() - seg.ax(), seg.by() - seg.ay(), seg.bz() - seg.az()};
        float[] lo = {box.minX(), box.minY(), box.minZ()};
        float[] hi = {box.maxX(), box.maxY(), box.maxZ()};
        float[] ts = new float[8];
        int n = 0;
        ts[n++] = 0f;
        for (int k = 0; k < 3; k++) {
            if (d[k] != 0f) {
                float t1 = (lo[k] - p0[k]) / d[k], t2 = (hi[k] - p0[k]) / d[k];
                if (t1 > 0f && t1 < 1f) {
                    ts[n++] = t1;
                }
                if (t2 > 0f && t2 < 1f) {
                    ts[n++] = t2;
                }
            }
        }
        ts[n++] = 1f;
        for (int i = 1; i < n; i++) { // insertion sort of at most 8 values
            float key = ts[i];
            int j = i - 1;
            while (j >= 0 && ts[j] > key) {
                ts[j + 1] = ts[j];
                j--;
            }
            ts[j + 1] = key;
        }
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < n; i++) {
            float t0 = ts[i], t1 = ts[i + 1];
            float mid = (t0 + t1) * 0.5f;
            // on this piece each axis contributes a linear excess u + v t (or nothing, when the segment is inside the slab)
            float su = 0f, sv = 0f;
            float[] u = new float[3], v = new float[3];
            for (int k = 0; k < 3; k++) {
                float c = p0[k] + d[k] * mid;
                if (c > hi[k]) {
                    u[k] = p0[k] - hi[k];
                    v[k] = d[k];
                } else if (c < lo[k]) {
                    u[k] = lo[k] - p0[k];
                    v[k] = -d[k];
                }
                su += u[k] * v[k];
                sv += v[k] * v[k];
            }
            float tStar = sv > 0f ? Math.min(Math.max(-su / sv, t0), t1) : t0;
            best = Math.min(best, Math.min(excessSquared(u, v, tStar), Math.min(excessSquared(u, v, t0), excessSquared(u, v, t1))));
        }
        return best;
    }

    private static float excessSquared(float[] u, float[] v, float t) {
        float x = u[0] + v[0] * t, y = u[1] + v[1] * t, z = u[2] + v[2] * t;
        return x * x + y * y + z * z;
    }

    /**
     * Tests a capsule against a box by comparing the distance from the axis to the box with the
     * radius.
     *
     * @param c the capsule; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @return {@code true} when the capsule and the box share a point
     */
    public static boolean capsuleAabb(Capsulef c, Aabbf box) {
        return !(segmentAabbDistanceSquared(c.segment(), box) > c.radius() * c.radius());
    }

    /**
     * Intersects a ray with a capsule within a distance range; the parameter is in units of the ray
     * direction, so normalise the direction to get a distance.
     *
     * <p>The capsule is its cylinder wall and two end spheres; the nearest of the three wins.
     *
     * @param ray the ray; must not be {@code null}
     * @param cap the cap; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return the first hit of the ray with the solid capsule within {@code [0, tMax]} (the
     *     smallest {@code t}, in units of the ray direction), or {@link Float#POSITIVE_INFINITY}; 0
     *     when the ray starts inside
     */
    public static float rayCapsule(Rayf ray, Capsulef cap, float tMax) {
        if (cap.contains(ray.origin())) {
            return 0f; // starting inside the solid (including inside the cylinder part, which neither end sphere covers)
        }
        float best = Math.min(raySphere(ray, Spheref.of(cap.a(), cap.radius()), tMax), raySphere(ray, Spheref.of(cap.b(), cap.radius()), tMax));
        // the cylinder wall: the infinite cylinder around the axis, valid only between the two end planes
        float dx = cap.bx() - cap.ax(), dy = cap.by() - cap.ay(), dz = cap.bz() - cap.az();
        float mx = ray.ox() - cap.ax(), my = ray.oy() - cap.ay(), mz = ray.oz() - cap.az();
        float dd = dx * dx + dy * dy + dz * dz;
        if (dd > 0f) {
            float nd = ray.dx() * dx + ray.dy() * dy + ray.dz() * dz;
            float md = mx * dx + my * dy + mz * dz;
            float nn = ray.dx() * ray.dx() + ray.dy() * ray.dy() + ray.dz() * ray.dz();
            float mn = mx * ray.dx() + my * ray.dy() + mz * ray.dz();
            float mm = mx * mx + my * my + mz * mz;
            float a = dd * nn - nd * nd;
            float b = dd * mn - nd * md;
            float c = dd * (mm - cap.radius() * cap.radius()) - md * md;
            if (a > 0f) { // a ray parallel to the axis cannot hit the wall; the end spheres cover it
                float disc = b * b - a * c;
                if (disc >= 0f) {
                    float t = (-b - (float) Math.sqrt(disc)) / a;
                    float y = md + t * nd; // position along the axis, scaled by |axis|^2
                    if (t >= 0f && t <= tMax && y >= 0f && y <= dd) {
                        best = Math.min(best, t);
                    }
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- separating axis tests

    @Eps(d = 1e-12)
    private static final float SAT_EPS = 1e-6f;

    /**
     * Tests two oriented boxes with the 15-axis separating axis test (three axes of each box and
     * nine edge cross products), using a small epsilon to cope with parallel edges; conservative
     * near-parallel configurations are reported as overlapping.
     *
     * @param a the first oriented box; must not be {@code null}
     * @param b the second oriented box; must not be {@code null}
     * @return {@code true} when the two oriented boxes share a point: the 15-axis separating axis
     *     test (Gottschalk; Ericson 4.4.1), where a small epsilon keeps the cross-product axes of
     *     (nearly) parallel edges from reporting a false separation
     */
    public static boolean obbObb(Obbf a, Obbf b) {
        Mat3f ra = a.axes(), rb = b.axes();
        float[] au = {ra.m00(), ra.m01(), ra.m02(), ra.m10(), ra.m11(), ra.m12(), ra.m20(), ra.m21(), ra.m22()};
        float[] bu = {rb.m00(), rb.m01(), rb.m02(), rb.m10(), rb.m11(), rb.m12(), rb.m20(), rb.m21(), rb.m22()};
        float[] ae = {a.hx(), a.hy(), a.hz()}, be = {b.hx(), b.hy(), b.hz()};
        // R[i][j] = Au[i] . Bu[j] is B's axes expressed in A's frame
        float[] r = new float[9], ar = new float[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                float v = au[i * 3] * bu[j * 3] + au[i * 3 + 1] * bu[j * 3 + 1] + au[i * 3 + 2] * bu[j * 3 + 2];
                r[i * 3 + j] = v;
                ar[i * 3 + j] = Math.abs(v) + SAT_EPS;
            }
        }
        float tx = b.cx() - a.cx(), ty = b.cy() - a.cy(), tz = b.cz() - a.cz();
        float[] t = new float[3];
        for (int i = 0; i < 3; i++) {
            t[i] = tx * au[i * 3] + ty * au[i * 3 + 1] + tz * au[i * 3 + 2]; // the centre offset in A's frame
        }
        for (int i = 0; i < 3; i++) { // A's three axes
            float ra0 = ae[i];
            float rb0 = be[0] * ar[i * 3] + be[1] * ar[i * 3 + 1] + be[2] * ar[i * 3 + 2];
            if (Math.abs(t[i]) > ra0 + rb0) {
                return false;
            }
        }
        for (int j = 0; j < 3; j++) { // B's three axes
            float ra0 = ae[0] * ar[j] + ae[1] * ar[3 + j] + ae[2] * ar[6 + j];
            float rb0 = be[j];
            if (Math.abs(t[0] * r[j] + t[1] * r[3 + j] + t[2] * r[6 + j]) > ra0 + rb0) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) { // the nine edge-edge axes A_i x B_j
            int i1 = (i + 1) % 3, i2 = (i + 2) % 3;
            for (int j = 0; j < 3; j++) {
                int j1 = (j + 1) % 3, j2 = (j + 2) % 3;
                float raa = ae[i1] * ar[i2 * 3 + j] + ae[i2] * ar[i1 * 3 + j];
                float rbb = be[j1] * ar[i * 3 + j2] + be[j2] * ar[i * 3 + j1];
                float proj = t[i2] * r[i1 * 3 + j] - t[i1] * r[i2 * 3 + j];
                if (Math.abs(proj) > raa + rbb) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Tests a box against a triangle with the 13-axis separating axis test of Akenine-Moller.
     *
     * @param box the box; must not be {@code null}
     * @param tri the tri; must not be {@code null}
     * @return {@code true} when the axis-aligned box and the triangle share a point: the 13-axis
     *     separating axis test (Akenine-Moller): the three box axes, the triangle's plane normal,
     *     and the nine cross products of a box axis with a triangle edge
     */
    public static boolean aabbTriangle(Aabbf box, Trianglef tri) {
        float cx = (box.minX() + box.maxX()) * 0.5f, cy = (box.minY() + box.maxY()) * 0.5f, cz = (box.minZ() + box.maxZ()) * 0.5f;
        float[] e = {(box.maxX() - box.minX()) * 0.5f, (box.maxY() - box.minY()) * 0.5f, (box.maxZ() - box.minZ()) * 0.5f};
        // the triangle's vertices relative to the box centre
        float[][] v = {
                {tri.ax() - cx, tri.ay() - cy, tri.az() - cz},
                {tri.bx() - cx, tri.by() - cy, tri.bz() - cz},
                {tri.cx() - cx, tri.cy() - cy, tri.cz() - cz}};
        for (int k = 0; k < 3; k++) { // box axes: the triangle's extent along each must reach the box
            float lo = Math.min(v[0][k], Math.min(v[1][k], v[2][k])), hi = Math.max(v[0][k], Math.max(v[1][k], v[2][k]));
            if (lo > e[k] || hi < -e[k]) {
                return false;
            }
        }
        float[][] f = {sub(v[1], v[0]), sub(v[2], v[1]), sub(v[0], v[2])};
        for (int k = 0; k < 3; k++) { // axis = box axis i x edge k
            for (int i = 0; i < 3; i++) {
                float ax = i == 0 ? 0f : i == 1 ? f[k][2] : -f[k][1];
                float ay = i == 0 ? -f[k][2] : i == 1 ? 0f : f[k][0];
                float az = i == 0 ? f[k][1] : i == 1 ? -f[k][0] : 0f;
                float p0 = v[0][0] * ax + v[0][1] * ay + v[0][2] * az;
                float p1 = v[1][0] * ax + v[1][1] * ay + v[1][2] * az;
                float p2 = v[2][0] * ax + v[2][1] * ay + v[2][2] * az;
                float rad = e[0] * Math.abs(ax) + e[1] * Math.abs(ay) + e[2] * Math.abs(az);
                if (Math.min(p0, Math.min(p1, p2)) > rad || Math.max(p0, Math.max(p1, p2)) < -rad) {
                    return false;
                }
            }
        }
        // the triangle's plane against the box
        float nx = f[0][1] * f[1][2] - f[0][2] * f[1][1];
        float ny = f[0][2] * f[1][0] - f[0][0] * f[1][2];
        float nz = f[0][0] * f[1][1] - f[0][1] * f[1][0];
        float dist = nx * v[0][0] + ny * v[0][1] + nz * v[0][2];
        float rad = e[0] * Math.abs(nx) + e[1] * Math.abs(ny) + e[2] * Math.abs(nz);
        return !(Math.abs(dist) > rad);
    }

    /**
     * Intersects a ray with an oriented box by transforming the ray into the box's frame and using
     * the slab method; the parameter is in units of the ray direction.
     *
     * @param ray the ray; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return ray against an oriented box; the distance along the ray ({@code 0} when the origin is
     *     inside), or {@code +Infinity} for a miss beyond {@code tMax}
     */
    public static float rayObb(Rayf ray, Obbf box, float tMax) {
        Vec3f o = box.toLocal(ray.origin());
        Vec3f d = box.rotation().conjugate().transform(ray.direction());
        Aabbf local = new Aabbf(-box.hx(), -box.hy(), -box.hz(), box.hx(), box.hy(), box.hz());
        return rayAabb(Rayf.of(o, d), local, tMax); // the rotation is rigid, so t is the same in both frames
    }

    /**
     * Tests a sphere against an oriented box by comparing the squared distance to the box with the
     * squared radius.
     *
     * @param s the sphere; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @return {@code true} when the sphere and the oriented box share a point
     */
    public static boolean sphereObb(Spheref s, Obbf box) {
        return !(box.distanceSquared(s.center()) > s.radius() * s.radius());
    }

    /**
     * Returns which side of {@code p} the oriented box is on, as for {@link #planeAabb}; the plane
     * normal must be unit length.
     *
     * @param p the plane; must not be {@code null}
     * @param box the box; must not be {@code null}
     * @return {@link Containment#INSIDE} if the box is in front of the plane,
     *     {@link Containment#OUTSIDE} if it is behind and {@link Containment#INTERSECTING} if it
     *     straddles
     */
    public static int planeObb(Planef p, Obbf box) {
        Mat3f r = box.axes();
        float rad = box.hx() * Math.abs(p.nx() * r.m00() + p.ny() * r.m01() + p.nz() * r.m02())
                + box.hy() * Math.abs(p.nx() * r.m10() + p.ny() * r.m11() + p.nz() * r.m12())
                + box.hz() * Math.abs(p.nx() * r.m20() + p.ny() * r.m21() + p.nz() * r.m22());
        float s = p.distance(box.center());
        return s - rad >= 0f ? Containment.INSIDE : s + rad < 0f ? Containment.OUTSIDE : Containment.INTERSECTING;
    }

    /**
     * Returns which side of {@code p} the triangle is on, as for {@link #planeAabb}: all vertices
     * in front, all behind, or straddling.
     *
     * @param p the plane; must not be {@code null}
     * @param tri the tri; must not be {@code null}
     * @return {@link Containment#INSIDE} if the triangle is in front of the plane,
     *     {@link Containment#OUTSIDE} if it is behind and {@link Containment#INTERSECTING} if it
     *     straddles
     */
    public static int planeTriangle(Planef p, Trianglef tri) {
        float da = p.distance(tri.a()), db = p.distance(tri.b()), dc = p.distance(tri.c());
        float lo = Math.min(da, Math.min(db, dc)), hi = Math.max(da, Math.max(db, dc));
        return lo >= 0f ? Containment.INSIDE : hi < 0f ? Containment.OUTSIDE : Containment.INTERSECTING;
    }

    /**
     * Finds the first time two moving spheres touch by solving a quadratic in the relative motion;
     * spheres that already overlap give zero.
     *
     * @param a the first sphere; must not be {@code null}
     * @param va the vector; must not be {@code null}
     * @param b the second sphere; must not be {@code null}
     * @param vb the vector; must not be {@code null}
     * @param tMax the largest ray parameter to test
     * @return swept sphere against sphere: the first time {@code t} in [0, {@code tMax}] at which
     *     {@code a}, moving by {@code va} per unit time, touches {@code b} moving by {@code vb};
     *     {@code 0} when they already overlap, {@code +Infinity} when they do not meet in time
     */
    public static float sweepSphereSphere(Spheref a, Vec3f va, Spheref b, Vec3f vb, float tMax) {
        Vec3f d = a.center().sub(b.center());
        Vec3f v = va.sub(vb);
        float r = a.radius() + b.radius();
        float c = d.dot(d) - r * r;
        if (c <= 0f) {
            return 0f;
        }
        float vv = v.dot(v), dv = d.dot(v);
        if (!(dv < 0f) || !(vv > 0f)) {
            return Float.POSITIVE_INFINITY; // not approaching
        }
        float disc = dv * dv - vv * c;
        if (!(disc >= 0f)) {
            return Float.POSITIVE_INFINITY;
        }
        float t = (-dv - (float) Math.sqrt(disc)) / vv;
        return t <= tMax ? t : Float.POSITIVE_INFINITY;
    }

    private static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }
}
