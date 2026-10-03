package vmath.geo;

import vmath.core.Quatf;

/**
 * Signed distance functions ({@link Sdf}) for the usual solids, the operations that combine and move them (CSG), and the queries that use them: normals, projection onto the surface and sphere
 * tracing for picking. Meshing is {@link SurfaceNets}.
 *
 * <p><b>How far each function can be trusted</b> (see {@link Sdf}): {@link #sphere}, {@link #box}, {@link #roundBox}, {@link #plane}, {@link #capsule}, {@link #cylinder} and {@link #torus} are exact
 * distances. {@link #union}, {@link #intersection} and {@link #subtract} keep the exact distance on the side of the surface where only one operand decides (outside a union of exact solids, inside an intersection)
 * and give a bound elsewhere: their <b>zero set and sign are always exact</b>, and they are 1-Lipschitz, so they are safe to sphere-trace and to mesh. The smooth variants move the surface (that is their job)
 * and are 1-Lipschitz too. {@link #onion}, {@link #round} and {@link #invert} keep what the operand has. {@link #translate}, {@link #rotate} and uniform {@link #scale} keep exact distances exact.
 * {@link #repeat} is exact only if the repeated solid fits inside one cell.
 *
 * <p><b>Thread safety.</b> The functions returned here are immutable and may be shared between threads. The query methods allocate nothing and use only their arguments.
 */
public final class Sdfs {

    private Sdfs() {
    }

    // ------------------------------------------------------------ primitives

    /** A sphere with the centre {@code (cx, cy, cz)} and the radius {@code r}: exact. */
    public static Sdf sphere(float cx, float cy, float cz, float r) {
        requireFinite(r, "radius");
        return (x, y, z) -> {
            float dx = x - cx, dy = y - cy, dz = z - cz;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
        };
    }

    /** An axis-aligned box with the centre {@code (cx, cy, cz)} and the half extents {@code (hx, hy, hz)}: exact. */
    public static Sdf box(float cx, float cy, float cz, float hx, float hy, float hz) {
        requireFinite(hx, "half extent x");
        requireFinite(hy, "half extent y");
        requireFinite(hz, "half extent z");
        return (x, y, z) -> boxDistance(x - cx, y - cy, z - cz, hx, hy, hz);
    }

    private static float boxDistance(float px, float py, float pz, float hx, float hy, float hz) {
        float qx = Math.abs(px) - hx, qy = Math.abs(py) - hy, qz = Math.abs(pz) - hz;
        float ox = Math.max(qx, 0f), oy = Math.max(qy, 0f), oz = Math.max(qz, 0f);
        return (float) Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0f);
    }

    /** A box with rounded edges and corners: the box of the half extents {@code (hx, hy, hz)} grown by {@code r} on every side, so the outer size is {@code h + r}: exact. */
    public static Sdf roundBox(float cx, float cy, float cz, float hx, float hy, float hz, float r) {
        requireFinite(r, "rounding radius");
        requireFinite(hx, "half extent x");
        requireFinite(hy, "half extent y");
        requireFinite(hz, "half extent z");
        return (x, y, z) -> boxDistance(x - cx, y - cy, z - cz, hx, hy, hz) - r;
    }

    /** The half space {@code n . p <= offset} with the unit normal {@code (nx, ny, nz)} pointing out of the solid (the normal is normalised for you): exact. */
    public static Sdf plane(float nx, float ny, float nz, float offset) {
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(l > 0f) || Float.isInfinite(l)) {
            throw new IllegalArgumentException("the plane normal must be finite and not zero");
        }
        float ux = nx / l, uy = ny / l, uz = nz / l;
        return (x, y, z) -> ux * x + uy * y + uz * z - offset;
    }

    /** The capsule around the segment from {@code a} to {@code b} with the radius {@code r}: exact. */
    public static Sdf capsule(float ax, float ay, float az, float bx, float by, float bz, float r) {
        requireFinite(r, "radius");
        float bax = bx - ax, bay = by - ay, baz = bz - az, baba = bax * bax + bay * bay + baz * baz;
        return (x, y, z) -> {
            float pax = x - ax, pay = y - ay, paz = z - az;
            float h = baba > 0f ? Math.max(0f, Math.min(1f, (pax * bax + pay * bay + paz * baz) / baba)) : 0f;
            float dx = pax - bax * h, dy = pay - bay * h, dz = paz - baz * h;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
        };
    }

    /** A cylinder with the axis along y through {@code (cx, cy, cz)}, the radius {@code r} and the half height {@code halfHeight}, with flat caps: exact. */
    public static Sdf cylinder(float cx, float cy, float cz, float r, float halfHeight) {
        requireFinite(r, "radius");
        requireFinite(halfHeight, "half height");
        return (x, y, z) -> {
            float px = x - cx, pz = z - cz;
            float dx = (float) Math.sqrt(px * px + pz * pz) - r, dy = Math.abs(y - cy) - halfHeight;
            float ox = Math.max(dx, 0f), oy = Math.max(dy, 0f);
            return Math.min(Math.max(dx, dy), 0f) + (float) Math.sqrt(ox * ox + oy * oy);
        };
    }

    /** A torus around the y axis through {@code (cx, cy, cz)} with the ring radius {@code major} and the tube radius {@code minor}: exact. */
    public static Sdf torus(float cx, float cy, float cz, float major, float minor) {
        requireFinite(major, "major radius");
        requireFinite(minor, "minor radius");
        return (x, y, z) -> {
            float px = x - cx, pz = z - cz;
            float qx = (float) Math.sqrt(px * px + pz * pz) - major, qy = y - cy;
            return (float) Math.sqrt(qx * qx + qy * qy) - minor;
        };
    }

    // ------------------------------------------------------------ CSG

    /** The union (the nearer of the two surfaces): the zero set and sign are exact, a bound inside. */
    public static Sdf union(Sdf a, Sdf b) {
        return (x, y, z) -> Math.min(a.distance(x, y, z), b.distance(x, y, z));
    }

    /** The union of any number of solids; with none, the empty solid (infinitely far everywhere). */
    public static Sdf union(Sdf... all) {
        Sdf[] copy = all.clone();
        return (x, y, z) -> {
            float d = Float.POSITIVE_INFINITY;
            for (Sdf s : copy) {
                d = Math.min(d, s.distance(x, y, z));
            }
            return d;
        };
    }

    /** The intersection (the farther of the two): the zero set and sign are exact, a bound outside. */
    public static Sdf intersection(Sdf a, Sdf b) {
        return (x, y, z) -> Math.max(a.distance(x, y, z), b.distance(x, y, z));
    }

    /** {@code a} with {@code b} cut away: {@code max(a, -b)}. The zero set and sign are exact. */
    public static Sdf subtract(Sdf a, Sdf b) {
        return (x, y, z) -> Math.max(a.distance(x, y, z), -b.distance(x, y, z));
    }

    /**
     * The smooth union: the polynomial smooth minimum with the blend width {@code k} (in distance units; 0 gives the hard union). The result is never above the hard union and never more than {@code k / 4}
     * below it.
     */
    public static Sdf smoothUnion(Sdf a, Sdf b, float k) {
        requireNonNegative(k);
        return (x, y, z) -> smoothMin(a.distance(x, y, z), b.distance(x, y, z), k);
    }

    /** The smooth intersection: never below the hard intersection, and at most {@code k / 4} above it. */
    public static Sdf smoothIntersection(Sdf a, Sdf b, float k) {
        requireNonNegative(k);
        return (x, y, z) -> -smoothMin(-a.distance(x, y, z), -b.distance(x, y, z), k);
    }

    /** {@code a} with {@code b} cut away with a smooth edge of the width {@code k}. */
    public static Sdf smoothSubtract(Sdf a, Sdf b, float k) {
        requireNonNegative(k);
        return (x, y, z) -> -smoothMin(-a.distance(x, y, z), b.distance(x, y, z), k);
    }

    private static float smoothMin(float a, float b, float k) {
        if (!(k > 0f)) {
            return Math.min(a, b);
        }
        float h = Math.max(k - Math.abs(a - b), 0f) / k;
        return Math.min(a, b) - h * h * k * 0.25f;
    }

    /** The inside and the outside swapped. */
    public static Sdf invert(Sdf a) {
        return (x, y, z) -> -a.distance(x, y, z);
    }

    /** A hollow shell of the thickness {@code t} around the surface of {@code a} (half of it on each side): {@code |a| - t / 2}. */
    public static Sdf onion(Sdf a, float thickness) {
        requireNonNegative(thickness);
        float h = thickness * 0.5f;
        return (x, y, z) -> Math.abs(a.distance(x, y, z)) - h;
    }

    /** {@code a} grown by {@code r} (shrunk if negative): the surface moves by {@code r} along its normal, which rounds convex edges. */
    public static Sdf round(Sdf a, float r) {
        requireFinite(r, "rounding radius");
        return (x, y, z) -> a.distance(x, y, z) - r;
    }

    // ------------------------------------------------------------ moving

    /** {@code a} moved by {@code (dx, dy, dz)}. */
    public static Sdf translate(Sdf a, float dx, float dy, float dz) {
        return (x, y, z) -> a.distance(x - dx, y - dy, z - dz);
    }

    /** {@code a} turned by the rotation {@code q} about the origin (the quaternion is normalised for you; a zero quaternion is rejected). */
    public static Sdf rotate(Sdf a, Quatf q) {
        float l = (float) Math.sqrt(q.x() * q.x() + q.y() * q.y() + q.z() * q.z() + q.w() * q.w());
        if (!(l > 0f) || Float.isInfinite(l)) {
            throw new IllegalArgumentException("the quaternion must be finite and not zero");
        }
        // the point is turned by the inverse rotation: the conjugate
        float ux = -q.x() / l, uy = -q.y() / l, uz = -q.z() / l, w = q.w() / l;
        return (x, y, z) -> {
            // v' = v + 2 w (u x v) + 2 u x (u x v)
            float cx = uy * z - uz * y, cy = uz * x - ux * z, cz = ux * y - uy * x;
            float ex = uy * cz - uz * cy, ey = uz * cx - ux * cz, ez = ux * cy - uy * cx;
            return a.distance(x + 2f * (w * cx + ex), y + 2f * (w * cy + ey), z + 2f * (w * cz + ez));
        };
    }

    /** {@code a} scaled by {@code s > 0} about the origin: {@code s a(p / s)}. */
    public static Sdf scale(Sdf a, float s) {
        if (!(s > 0f) || Float.isInfinite(s)) {
            throw new IllegalArgumentException("the scale must be positive and finite: " + s);
        }
        return (x, y, z) -> a.distance(x / s, y / s, z / s) * s;
    }

    /**
     * {@code a} repeated with the periods {@code (px, py, pz)} along the axes (0 leaves an axis alone): the point is folded into the cell around the origin. The result is a distance only if the
     * solid fits inside its cell; solids that reach into a neighbouring cell get cut at the cell border.
     */
    public static Sdf repeat(Sdf a, float px, float py, float pz) {
        requireNonNegative(px);
        requireNonNegative(py);
        requireNonNegative(pz);
        return (x, y, z) -> a.distance(px > 0f ? x - px * Math.round(x / px) : x, py > 0f ? y - py * Math.round(y / py) : y, pz > 0f ? z - pz * Math.round(z / pz) : z);
    }

    // ------------------------------------------------------------ queries

    /**
     * The unit normal of the surface at (or near) {@code (x, y, z)}: the normalised gradient of {@code sdf}, by the four-sample tetrahedron technique with the offset {@code h}, written to
     * {@code out[0 .. 3)}. Returns false and writes zeros where the gradient vanishes (the centre of a sphere, the medial axis).
     */
    public static boolean normal(Sdf sdf, float x, float y, float z, float h, float[] out) {
        float a = sdf.distance(x + h, y - h, z - h), b = sdf.distance(x - h, y - h, z + h), c = sdf.distance(x - h, y + h, z - h), d = sdf.distance(x + h, y + h, z + h);
        float gx = a - b - c + d, gy = -a - b + c + d, gz = -a + b - c + d;
        float l = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
        if (!(l > 0f)) {
            out[0] = out[1] = out[2] = 0f;
            return false;
        }
        out[0] = gx / l;
        out[1] = gy / l;
        out[2] = gz / l;
        return true;
    }

    /**
     * Moves the point {@code (x, y, z)} onto the surface by repeated steps of {@code -d n} along the normal (one step is enough for an exact distance and a smooth surface) and writes it to
     * {@code out[0 .. 3)}. Stops after {@code maxIterations} steps or when {@code |d| <= tolerance}; returns the remaining {@code |d|}. {@code h} is the offset of the normal estimate.
     */
    public static float project(Sdf sdf, float x, float y, float z, int maxIterations, float tolerance, float h, float[] out) {
        float[] n = NORMAL.get();
        float d = sdf.distance(x, y, z);
        for (int i = 0; i < maxIterations && Math.abs(d) > tolerance; i++) {
            if (!normal(sdf, x, y, z, h, n)) {
                break;
            }
            x -= d * n[0];
            y -= d * n[1];
            z -= d * n[2];
            d = sdf.distance(x, y, z);
        }
        out[0] = x;
        out[1] = y;
        out[2] = z;
        return Math.abs(d);
    }

    /** The result of {@link #raycast}: public fields, to be reused between calls. */
    public static final class Hit {
        /** The distance along the ray (in units of the normalised direction). */
        public float t;
        /** The point of the hit. */
        public float x, y, z;
        /** The unit normal of the surface there (zeros where it is undefined). */
        public float nx, ny, nz;
        /** The number of distance evaluations that the march took. */
        public int steps;
        /** True when the ray started inside the solid: then {@code t = tMin} and the point is the start. */
        public boolean inside;

        /** An empty result. */
        public Hit() {
        }
    }

    /**
     * Sphere tracing of the ray {@code o + t d} for {@code t} from {@code tMin} to {@code tMax}: each step moves by the distance, which is safe for any {@link Sdf} that never exceeds the true distance, so
     * the march cannot jump over the surface. The direction is normalised for you. A hit is a point where {@code sdf <= epsilon}; a ray that starts inside the solid hits at {@code tMin} with
     * {@link Hit#inside}. Returns false for a miss, or when {@code maxSteps} evaluations were not enough (grazing rays are the usual cause: raise {@code maxSteps} or {@code epsilon}). The hit normal
     * uses the offset {@code epsilon}. Allocates nothing; use it for picking and for debugging a field.
     */
    public static boolean raycast(Sdf sdf, float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax, int maxSteps, float epsilon, Hit hit) {
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(l > 0f) || Float.isInfinite(l)) {
            throw new IllegalArgumentException("the ray direction must be finite and not zero");
        }
        dx /= l;
        dy /= l;
        dz /= l;
        float t = tMin;
        for (int step = 1; step <= maxSteps; step++) {
            float x = ox + dx * t, y = oy + dy * t, z = oz + dz * t;
            float d = sdf.distance(x, y, z);
            if (d <= epsilon) {
                hit.t = t;
                hit.x = x;
                hit.y = y;
                hit.z = z;
                hit.steps = step;
                hit.inside = d < 0f && t == tMin;
                float[] n = NORMAL.get();
                normal(sdf, x, y, z, epsilon, n);
                hit.nx = n[0];
                hit.ny = n[1];
                hit.nz = n[2];
                return true;
            }
            t += d;
            if (t > tMax) {
                hit.steps = step;
                return false;
            }
        }
        hit.steps = maxSteps;
        return false;
    }

    private static final ThreadLocal<float[]> NORMAL = ThreadLocal.withInitial(() -> new float[3]);

    private static void requireFinite(float v, String what) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            throw new IllegalArgumentException(what + " must be finite: " + v);
        }
    }

    private static void requireNonNegative(float v) {
        if (!(v >= 0f) || Float.isInfinite(v)) {
            throw new IllegalArgumentException("the value must be finite and not negative: " + v);
        }
    }
}
