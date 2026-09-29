package vmath.geo;

import vmath.annotations.GenerateDouble;
import vmath.core.Vec3f;

/**
 * Ray casts and shape queries. Ray routines return the distance {@code t} along the ray (in units of the ray
 * direction) of the first hit within {@code [0, tMax]}, or {@link Float#POSITIVE_INFINITY} for a miss, so
 * {@code t < tMax} and {@code Math.min} compose naturally when searching for the nearest hit. A ray that starts
 * inside a solid volume reports {@code t = 0}.
 */
@GenerateDouble
public final class Intersectionf {

    private Intersectionf() {
    }

    /** Slab test. Handles axis-parallel rays exactly (no NaN from 0 * infinity). */
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

    /** Hits the plane from either side. A ray parallel to the plane misses. */
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
     * Watertight two-sided ray-triangle test (Woop, Benthin and Wald 2013): rays that pass exactly through a shared
     * edge or vertex of adjacent triangles hit at least one of them, never slip between. Edge functions that come out
     * zero are recomputed in double precision.
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

    /** Which side of {@code p} the box is on: {@link Containment#INSIDE} = fully in front, {@code OUTSIDE} = behind. */
    public static int planeAabb(Planef p, Aabbf box) {
        float cx = (box.minX() + box.maxX()) * 0.5f, cy = (box.minY() + box.maxY()) * 0.5f;
        float cz = (box.minZ() + box.maxZ()) * 0.5f;
        float r = (box.maxX() - box.minX()) * 0.5f * Math.abs(p.nx())
                + (box.maxY() - box.minY()) * 0.5f * Math.abs(p.ny())
                + (box.maxZ() - box.minZ()) * 0.5f * Math.abs(p.nz());
        float s = p.nx() * cx + p.ny() * cy + p.nz() * cz + p.d();
        return s - r >= 0f ? Containment.INSIDE : s + r < 0f ? Containment.OUTSIDE : Containment.INTERSECTING;
    }

    /** Which side of {@code p} the sphere is on; the plane normal must be unit length. */
    public static int planeSphere(Planef p, Spheref s) {
        float dist = p.distance(s.center());
        return dist >= s.radius() ? Containment.INSIDE : dist < -s.radius() ? Containment.OUTSIDE : Containment.INTERSECTING;
    }

    /** Squared distance between a point and a triangle. */
    public static float pointTriangleDistanceSquared(Vec3f p, Trianglef tri) {
        return tri.closestPoint(p).distanceSquared(p);
    }

    /** True when the sphere touches the triangle. */
    public static boolean sphereTriangle(Spheref s, Trianglef tri) {
        return pointTriangleDistanceSquared(s.center(), tri) <= s.radius() * s.radius();
    }
}
