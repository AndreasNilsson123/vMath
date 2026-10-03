package vmath.geo;

import vmath.core.Quatf;

/**
 * Signed distance functions ({@link Sdf}) for the usual solids, the operations that combine and
 * move them (CSG), and the queries that use them: normals, projection onto the surface and sphere
 * tracing for picking.
 *
 * <p>Meshing is {@link SurfaceNets}.
 *
 * <p><b>How far each function can be trusted</b> (see {@link Sdf}): {@link #sphere}, {@link #box},
 * {@link #roundBox}, {@link #plane}, {@link #capsule}, {@link #cylinder} and {@link #torus} are
 * exact distances. {@link #union}, {@link #intersection} and {@link #subtract} keep the exact
 * distance on the side of the surface where only one operand decides (outside a union of exact
 * solids, inside an intersection) and give a bound elsewhere: their <b>zero set and sign are always
 * exact</b>, and they are 1-Lipschitz, so they are safe to sphere-trace and to mesh. The smooth
 * variants move the surface (that is their job) and are 1-Lipschitz too. {@link #onion},
 * {@link #round} and {@link #invert} keep what the operand has. {@link #translate}, {@link #rotate}
 * and uniform {@link #scale} keep exact distances exact. {@link #repeat} is exact only if the
 * repeated solid fits inside one cell.
 *
 * <p><b>Thread safety.</b> The functions returned here are immutable and may be shared between
 * threads. The query methods allocate nothing and use only their arguments.
 *
 * <p><b>Example:</b> building a solid and picking it
 *
 * <pre>{@code
 * Sdf body = Sdfs.smoothUnion(Sdfs.sphere(-0.4f, 0f, 0f, 0.7f), Sdfs.sphere(0.4f, 0f, 0f, 0.7f), 0.3f);
 * Sdf drilled = Sdfs.subtract(body, Sdfs.cylinder(0f, 0f, 0f, 0.2f, 2f));
 * Sdfs.Hit hit = new Sdfs.Hit();
 * if (Sdfs.raycast(drilled, -3f, 0.5f, 0f, 1f, 0f, 0f, 0f, 10f, 128, 1e-4f, hit)) {
 *     float x = hit.x;                                                   // the point and the normal (hit.nx...) of the hit
 * }
 * }</pre>
 *
 * <p><b>Example:</b> moving and combining
 *
 * <pre>{@code
 * Sdf box = Sdfs.box(0f, 0f, 0f, 1f, 1f, 1f);
 * Sdf turned = Sdfs.rotate(box, Quatf.rotationY(0.5f));
 * Sdf row = Sdfs.repeat(turned, 4f, 0f, 0f);                             // a row of boxes every 4 units
 * float d = row.distance(8f, 0f, 0f);
 * }</pre>
 */
public final class Sdfs {

    private Sdfs() {
    }

    // ------------------------------------------------------------ primitives

    /**
     * Creates the signed distance function of a sphere, which is exact everywhere.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param r the radius
     * @return a sphere with the centre {@code (cx, cy, cz)} and the radius {@code r}: exact
     */
    public static Sdf sphere(float cx, float cy, float cz, float r) {
        requireFinite(r, "radius");
        return (x, y, z) -> {
            float dx = x - cx, dy = y - cy, dz = z - cz;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
        };
    }

    /**
     * Creates the signed distance function of a box, which is exact everywhere.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param hx the half extent along x
     * @param hy the half extent along y
     * @param hz the half extent along z
     * @return an axis-aligned box with the centre {@code (cx, cy, cz)} and the half extents
     *     {@code (hx, hy, hz)}: exact
     */
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

    /**
     * Creates the signed distance function of a box with rounded edges, which is exact everywhere.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param hx the half extent along x
     * @param hy the half extent along y
     * @param hz the half extent along z
     * @param r the rounding radius
     * @return a box with rounded edges and corners: the box of the half extents
     *     {@code (hx, hy, hz)} grown by {@code r} on every side, so the outer size is
     *     {@code h + r}: exact
     */
    public static Sdf roundBox(float cx, float cy, float cz, float hx, float hy, float hz, float r) {
        requireFinite(r, "rounding radius");
        requireFinite(hx, "half extent x");
        requireFinite(hy, "half extent y");
        requireFinite(hz, "half extent z");
        return (x, y, z) -> boxDistance(x - cx, y - cy, z - cz, hx, hy, hz) - r;
    }

    /**
     * Creates the signed distance function of a half space bounded by a plane, which is exact
     * everywhere.
     *
     * @param nx the x component of the normal
     * @param ny the y component of the normal
     * @param nz the z component of the normal
     * @param offset the index of the first element to read or write
     * @return the half space {@code n . p <= offset} with the unit normal {@code (nx, ny, nz)}
     *     pointing out of the solid (the normal is normalised for you): exact
     * @throws IllegalArgumentException if the normal is zero or not finite
     */
    public static Sdf plane(float nx, float ny, float nz, float offset) {
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(l > 0f) || Float.isInfinite(l)) {
            throw new IllegalArgumentException("the plane normal must be finite and not zero");
        }
        float ux = nx / l, uy = ny / l, uz = nz / l;
        return (x, y, z) -> ux * x + uy * y + uz * z - offset;
    }

    /**
     * Creates the signed distance function of a capsule, the points within a radius of a line
     * segment, which is exact everywhere.
     *
     * @param ax the x coordinate of the first end of the segment
     * @param ay the y coordinate of the first end of the segment
     * @param az the z coordinate of the first end of the segment
     * @param bx the x coordinate of the second end of the segment
     * @param by the y coordinate of the second end of the segment
     * @param bz the z coordinate of the second end of the segment
     * @param r the radius
     * @return the capsule around the segment from {@code a} to {@code b} with the radius {@code r}:
     *     exact
     */
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

    /**
     * Creates the signed distance function of a cylinder with flat caps, which is exact everywhere.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param r the radius
     * @param halfHeight the half height
     * @return a cylinder with the axis along y through {@code (cx, cy, cz)}, the radius {@code r}
     *     and the half height {@code halfHeight}, with flat caps: exact
     */
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

    /**
     * Creates the signed distance function of a torus, which is exact everywhere.
     *
     * @param cx the x coordinate of the center
     * @param cy the y coordinate of the center
     * @param cz the z coordinate of the center
     * @param major the major
     * @param minor the minor
     * @return a torus around the y axis through {@code (cx, cy, cz)} with the ring radius
     *     {@code major} and the tube radius {@code minor}: exact
     */
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

    /**
     * Combines two solids with the minimum of their distances; exact on the surface and outside,
     * only a bound inside, which is enough for ray marching.
     *
     * @param a the first sdf; must not be {@code null}
     * @param b the second sdf; must not be {@code null}
     * @return the union (the nearer of the two surfaces): the zero set and sign are exact, a bound
     *     inside
     */
    public static Sdf union(Sdf a, Sdf b) {
        return (x, y, z) -> Math.min(a.distance(x, y, z), b.distance(x, y, z));
    }

    /**
     * Combines any number of solids with the minimum of their distances; an empty list gives the
     * empty solid.
     *
     * @param all the all; must not be {@code null}
     * @return the union of any number of solids; with none, the empty solid (infinitely far
     *     everywhere)
     */
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

    /**
     * Intersects two solids with the maximum of their distances; exact on the surface and inside,
     * only a bound outside.
     *
     * @param a the first sdf; must not be {@code null}
     * @param b the second sdf; must not be {@code null}
     * @return the intersection (the farther of the two): the zero set and sign are exact, a bound
     *     outside
     */
    public static Sdf intersection(Sdf a, Sdf b) {
        return (x, y, z) -> Math.max(a.distance(x, y, z), b.distance(x, y, z));
    }

    /**
     * Cuts one solid out of another by intersecting it with the inverse of the other; only a bound
     * near the cut edge.
     *
     * <p>The zero set and sign are exact.
     *
     * @param a the first sdf; must not be {@code null}
     * @param b the second sdf; must not be {@code null}
     * @return {@code a} with {@code b} cut away: {@code max(a, -b)}
     */
    public static Sdf subtract(Sdf a, Sdf b) {
        return (x, y, z) -> Math.max(a.distance(x, y, z), -b.distance(x, y, z));
    }

    /**
     * Blends two solids with a polynomial smooth minimum, which rounds the crease where they meet;
     * the blend width is in distance units, and zero gives the hard union.
     *
     * <p>The result is never above the hard union and never more than {@code k / 4} below it.
     *
     * @param a the first sdf; must not be {@code null}
     * @param b the second sdf; must not be {@code null}
     * @param k the blend width in distance units
     * @return the smooth union: the polynomial smooth minimum with the blend width {@code k} (in
     *     distance units; 0 gives the hard union)
     */
    public static Sdf smoothUnion(Sdf a, Sdf b, float k) {
        requireNonNegative(k);
        return (x, y, z) -> smoothMin(a.distance(x, y, z), b.distance(x, y, z), k);
    }

    /**
     * Intersects two solids with a smooth maximum, which rounds the crease where they meet; the
     * result never lies below the hard intersection.
     *
     * @param a the first sdf; must not be {@code null}
     * @param b the second sdf; must not be {@code null}
     * @param k the blend width in distance units
     * @return the smooth intersection: never below the hard intersection, and at most {@code k / 4}
     *     above it
     */
    public static Sdf smoothIntersection(Sdf a, Sdf b, float k) {
        requireNonNegative(k);
        return (x, y, z) -> -smoothMin(-a.distance(x, y, z), -b.distance(x, y, z), k);
    }

    /**
     * Cuts one solid out of another with a smooth edge; the blend width is in distance units.
     *
     * @param a the first sdf; must not be {@code null}
     * @param b the second sdf; must not be {@code null}
     * @param k the blend width in distance units
     * @return {@code a} with {@code b} cut away with a smooth edge of the width {@code k}
     */
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

    /**
     * Turns a solid inside out by negating the distance.
     *
     * @param a the first sdf; must not be {@code null}
     * @return the inside and the outside swapped
     */
    public static Sdf invert(Sdf a) {
        return (x, y, z) -> -a.distance(x, y, z);
    }

    /**
     * Hollows a solid into a shell around its surface by taking the absolute distance; the
     * thickness is the full wall thickness.
     *
     * @param a the first sdf; must not be {@code null}
     * @param thickness the thickness
     * @return a hollow shell of the thickness {@code t} around the surface of {@code a} (half of it
     *     on each side): {@code |a| - t / 2}
     */
    public static Sdf onion(Sdf a, float thickness) {
        requireNonNegative(thickness);
        float h = thickness * 0.5f;
        return (x, y, z) -> Math.abs(a.distance(x, y, z)) - h;
    }

    /**
     * Offsets the surface along its normal, which grows the solid and rounds its convex edges; a
     * negative amount shrinks it.
     *
     * @param a the first sdf; must not be {@code null}
     * @param r the distance to grow by (negative shrinks)
     * @return {@code a} grown by {@code r} (shrunk if negative): the surface moves by {@code r}
     *     along its normal, which rounds convex edges
     */
    public static Sdf round(Sdf a, float r) {
        requireFinite(r, "rounding radius");
        return (x, y, z) -> a.distance(x, y, z) - r;
    }

    // ------------------------------------------------------------ moving

    /**
     * Moves a solid by evaluating the distance at the shifted point.
     *
     * @param a the first sdf; must not be {@code null}
     * @param dx the offset along x
     * @param dy the offset along y
     * @param dz the offset along z
     * @return {@code a} moved by {@code (dx, dy, dz)}
     */
    public static Sdf translate(Sdf a, float dx, float dy, float dz) {
        return (x, y, z) -> a.distance(x - dx, y - dy, z - dz);
    }

    /**
     * Rotates a solid by evaluating the distance at the inversely rotated point; exact for a unit
     * quaternion, which is why the quaternion is normalised first.
     *
     * @param a the first sdf; must not be {@code null}
     * @param q the quaternion; must not be {@code null}
     * @return {@code a} turned by the rotation {@code q} about the origin (the quaternion is
     *     normalised for you; a zero quaternion is rejected)
     * @throws IllegalArgumentException if the quaternion is zero or not finite
     */
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

    /**
     * Scales a solid uniformly, correcting the distance by the factor so that it remains a true
     * distance.
     *
     * @param a the first sdf; must not be {@code null}
     * @param s the scale factor; must be positive and finite
     * @return {@code a} scaled by {@code s > 0} about the origin: {@code s a(p / s)}
     * @throws IllegalArgumentException if {@code s} is not positive and finite
     */
    public static Sdf scale(Sdf a, float s) {
        if (!(s > 0f) || Float.isInfinite(s)) {
            throw new IllegalArgumentException("the scale must be positive and finite: " + s);
        }
        return (x, y, z) -> a.distance(x / s, y / s, z / s) * s;
    }

    /**
     * Repeats a solid on a grid by folding space into one cell, which makes any number of copies at
     * a constant cost; the bound is valid only if the solid fits inside its cell.
     *
     * <p>The result is a distance only if the solid fits inside its cell; solids that reach into a
     * neighbouring cell get cut at the cell border.
     *
     * @param a the first sdf; must not be {@code null}
     * @param px the period along x; 0 leaves the axis alone
     * @param py the period along y; 0 leaves the axis alone
     * @param pz the period along z; 0 leaves the axis alone
     * @return {@code a} repeated with the periods {@code (px, py, pz)} along the axes (0 leaves an
     *     axis alone): the point is folded into the cell around the origin
     */
    public static Sdf repeat(Sdf a, float px, float py, float pz) {
        requireNonNegative(px);
        requireNonNegative(py);
        requireNonNegative(pz);
        return (x, y, z) -> a.distance(px > 0f ? x - px * Math.round(x / px) : x, py > 0f ? y - py * Math.round(y / py) : y, pz > 0f ? z - pz * Math.round(z / pz) : z);
    }

    // ------------------------------------------------------------ queries

    /**
     * Returns the unit normal of the surface at (or near) {@code (x, y, z)}: the normalised
     * gradient of {@code sdf}, by the four-sample tetrahedron technique with the offset {@code h},
     * written to {@code out[0 .. 3)}.
     *
     * <p>Returns false and writes zeros where the gradient vanishes (the centre of a sphere, the
     * medial axis).
     *
     * @param sdf the sdf; must not be {@code null}
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param h the offset of the samples from the point, in distance units
     * @param out receives the result in {@code [0, 3)}
     * @return {@code true} if the gradient is not zero and the normal was written; {@code false},
     *     with zeros written, where the gradient vanishes
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
     * Moves the point {@code (x, y, z)} onto the surface by repeated steps of {@code -d n} along
     * the normal (one step is enough for an exact distance and a smooth surface) and writes it to
     * {@code out[0 .. 3)}.
     *
     * <p>Stops after {@code maxIterations} steps or when {@code |d| <= tolerance}; returns the
     * remaining {@code |d|}. {@code h} is the offset of the normal estimate.
     *
     * @param sdf the sdf; must not be {@code null}
     * @param x the x component
     * @param y the y component
     * @param z the z component
     * @param maxIterations the max iterations
     * @param tolerance the tolerance
     * @param h the offset of the samples from the point, in distance units
     * @param out receives the result in {@code [0, 3)}
     * @return the remaining {@code |d|}
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

    /**
     * The result of {@link #raycast}: public fields, to be reused between calls.
     */
    public static final class Hit {
        /**
         * The distance along the ray (in units of the normalised direction).
         */
        public float t;
        /**
         * The point of the hit.
         */
        public float x, y, z;
        /**
         * The unit normal of the surface there (zeros where it is undefined).
         */
        public float nx, ny, nz;
        /**
         * The number of distance evaluations that the march took.
         */
        public int steps;
        /**
         * True when the ray started inside the solid: then {@code t = tMin} and the point is the
         * start.
         */
        public boolean inside;

        /**
         * Creates an empty result.
         */
        public Hit() {
        }
    }

    /**
     * Returns the sphere tracing of the ray {@code o + t d} for {@code t} from {@code tMin} to
     * {@code tMax}: each step moves by the distance, which is safe for any {@link Sdf} that never
     * exceeds the true distance, so the march cannot jump over the surface.
     *
     * <p>The direction is normalised for you. A hit is a point where {@code sdf <= epsilon}; a ray
     * that starts inside the solid hits at {@code tMin} with {@link Hit#inside}. Returns false for
     * a miss, or when {@code maxSteps} evaluations were not enough (grazing rays are the usual
     * cause: raise {@code maxSteps} or {@code epsilon}). The hit normal uses the offset
     * {@code epsilon}. Allocates nothing; use it for picking and for debugging a field.
     *
     * @param sdf the sdf; must not be {@code null}
     * @param ox the x coordinate of the origin
     * @param oy the y coordinate of the origin
     * @param oz the z coordinate of the origin
     * @param dx the x component of the direction
     * @param dy the y component of the direction
     * @param dz the z component of the direction
     * @param tMin the smallest ray parameter to test
     * @param tMax the largest ray parameter to test
     * @param maxSteps the max steps
     * @param epsilon the epsilon
     * @param hit the hit; must not be {@code null}
     * @return {@code true} if the ray hit the surface, in which case {@code hit} holds the hit;
     *     {@code false} for a miss or when the step budget ran out
     * @throws IllegalArgumentException if the direction is zero or not finite
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
