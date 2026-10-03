package vmath.core;

import vmath.annotations.Experimental;

/**
 * Geometric predicates whose <b>sign is always exact</b>: the orientation of three points in the
 * plane or four in space, and whether a point lies inside the circle or the sphere through the
 * others.
 *
 * <p>They are the questions a convex hull, a triangulation or a mesh-boolean asks, and with
 * ordinary floating-point arithmetic they are answered wrongly for nearly degenerate input (three
 * points almost on a line, four almost on a plane, five almost on a sphere), which makes those
 * algorithms loop, crash or produce inconsistent meshes.
 *
 * <p>Each predicate evaluates its determinant in double precision together with a bound on the
 * rounding error of that evaluation (Shewchuk's bounds, doubled for safety). When the result is
 * larger than the bound its sign cannot be wrong and it is returned: that is almost every call.
 * Otherwise the determinant is computed <em>exactly</em> with expansion arithmetic (an unevaluated
 * sum of doubles, see {@code Expansions}) and the exact sign is returned. The cost of the second
 * stage is measured in {@code docs/ROBUSTNESS.md}; inputs that are exactly degenerate (a very
 * common case in real meshes) always take it.
 *
 * <p>The value returned is a double whose <em>sign</em> is exact: positive, negative, or exactly
 * zero. Its magnitude is the filtered determinant, or, when the exact stage ran, the exact
 * determinant rounded to double. Every method has an {@code ...Sign} variant that returns just -1,
 * 0 or 1.
 *
 * <p><b>Range.</b> The inputs must be finite. The exactness argument also needs that no
 * intermediate product overflows or underflows: it holds when every coordinate is zero or has a
 * magnitude between {@code 1e-20} and {@code 1e20} (for the sphere test, which multiplies five
 * coordinates, between {@code 1e-30} and {@code 1e30} would still do; the tests use the narrower
 * range for all). Coordinates of {@code float} type convert to double exactly, so for them the
 * range is never a concern. Non-finite input gives NaN.
 *
 * <p><b>Conventions</b> (those of Shewchuk's predicates): {@code orient2d(a, b, c)} is positive
 * when {@code a, b, c} turn counter-clockwise (the plane seen with x to the right and y up);
 * {@code orient3d(a, b, c, d)} is positive when {@code d} lies below the plane through
 * {@code a, b, c}, "below" being the side from which {@code a, b, c} appear counter-clockwise
 * reversed, that is, when {@code (b - a) x (c - a) . (d - a)} is negative;
 * {@code incircle(a, b, c, d)} is positive when {@code d} is
 * inside the circle through {@code a, b, c} given counter-clockwise; {@code insphere(a, b, c, d, e)} is positive when {@code e} is inside the sphere through {@code a, b, c,
 * d} given with positive {@code orient3d} (the sign flips if the four points are given the other way round).
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * // is c to the left of the line from a to b? exact, whatever the rounding of the coordinates
 * int side = Predicates.orient2dSign(0.0, 0.0, 1.0, 0.0, 0.5, 1e-300);   // 1: counter-clockwise
 * double volume = Predicates.orient3d(new Vec3d(0, 0, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1));
 * }</pre>
 */
@Experimental("the set of predicates and the overloads for the vector types may grow")
public final class Predicates {

    private Predicates() {
    }

    private static final double EPS = 0x1p-53;
    // Shewchuk's static filters, doubled: the result of the floating-point evaluation is trusted when it exceeds bound * permanent
    private static final double CCW_BOUND = 2 * (3.0 + 16.0 * EPS) * EPS;
    private static final double O3D_BOUND = 2 * (7.0 + 56.0 * EPS) * EPS;
    private static final double ICC_BOUND = 2 * (10.0 + 96.0 * EPS) * EPS;
    private static final double ISP_BOUND = 2 * (16.0 + 224.0 * EPS) * EPS;

    // ---------------------------------------------------------------- orient2d

    /**
     * Evaluates the orientation predicate with Shewchuk's adaptive-precision arithmetic, so the
     * sign is exact: a floating-point filter handles the common case and exact expansions the
     * near-degenerate one.
     *
     * <p>The sign is exact; see the class comment for the range of the coordinates.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @return the orientation of {@code a, b, c} in the plane: positive for a counter-clockwise
     *     turn, negative for clockwise, exactly zero when the three points are collinear
     */
    public static double orient2d(double ax, double ay, double bx, double by, double cx, double cy) {
        double detLeft = (ax - cx) * (by - cy);
        double detRight = (ay - cy) * (bx - cx);
        double det = detLeft - detRight;
        double detSum;
        if (detLeft > 0.0) {
            if (detRight <= 0.0) {
                return det;
            }
            detSum = detLeft + detRight;
        } else if (detLeft < 0.0) {
            if (detRight >= 0.0) {
                return det;
            }
            detSum = -detLeft - detRight;
        } else {
            return det;
        }
        double bound = CCW_BOUND * detSum;
        if (det >= bound || -det >= bound) {
            return det;
        }
        if (Double.isNaN(det)) {
            return det;
        }
        return orient2dExact(ax, ay, bx, by, cx, cy);
    }

    /**
     * Evaluates the orientation predicate exactly and reduces it to its sign, for callers that only
     * branch on it.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @return {@link #orient2d} as -1, 0 or 1
     */
    public static int orient2dSign(double ax, double ay, double bx, double by, double cx, double cy) {
        double v = orient2d(ax, ay, bx, by, cx, cy);
        return v > 0.0 ? 1 : v < 0.0 ? -1 : 0;
    }

    /**
     * Evaluates the exact two-dimensional orientation predicate for vector arguments; same
     * arithmetic and same exactness as the scalar form.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @return {@link #orient2d(double, double, double, double, double, double)} for vectors
     */
    public static double orient2d(Vec2d a, Vec2d b, Vec2d c) {
        return orient2d(a.x(), a.y(), b.x(), b.y(), c.x(), c.y());
    }

    static double orient2dExact(double ax, double ay, double bx, double by, double cx, double cy) {
        double[] acx = Expansions.diff(ax, cx), bcx = Expansions.diff(bx, cx);
        double[] acy = Expansions.diff(ay, cy), bcy = Expansions.diff(by, cy);
        double[] det = Expansions.sub(Expansions.mul(acx, bcy), Expansions.mul(acy, bcx));
        return signed(det);
    }

    /**
     * The estimate of an exact expansion, with the sign of its largest component guaranteed (the
     * estimate of a non-overlapping expansion has that sign).
     */
    private static double signed(double[] e) {
        double v = Expansions.estimate(e);
        int s = Expansions.sign(e);
        return s == 0 ? 0.0 : s > 0 ? Math.max(v, Double.MIN_VALUE) : Math.min(v, -Double.MIN_VALUE);
    }

    // ---------------------------------------------------------------- orient3d

    /**
     * Evaluates the orientation of four points with Shewchuk's adaptive-precision arithmetic, so
     * that the sign is exact even for nearly coplanar input; the magnitude is not meaningful beyond
     * its sign.
     *
     * <p>The sign is exact.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param az the z coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param bz the z coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @param cz the z coordinate of the third point
     * @param dx the x coordinate of the fourth point
     * @param dy the y coordinate of the fourth point
     * @param dz the z coordinate of the fourth point
     * @return the orientation of four points in space: positive when {@code d} lies below the plane
     *     through {@code a, b, c} (the side opposite the normal {@code (b - a) x (c - a)}),
     *     negative above, exactly zero when the four are coplanar
     */
    public static double orient3d(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, double dx, double dy, double dz) {
        double adx = ax - dx, bdx = bx - dx, cdx = cx - dx;
        double ady = ay - dy, bdy = by - dy, cdy = cy - dy;
        double adz = az - dz, bdz = bz - dz, cdz = cz - dz;
        double bdxcdy = bdx * cdy, cdxbdy = cdx * bdy;
        double cdxady = cdx * ady, adxcdy = adx * cdy;
        double adxbdy = adx * bdy, bdxady = bdx * ady;
        double det = adz * (bdxcdy - cdxbdy) + bdz * (cdxady - adxcdy) + cdz * (adxbdy - bdxady);
        double permanent = (Math.abs(bdxcdy) + Math.abs(cdxbdy)) * Math.abs(adz) + (Math.abs(cdxady) + Math.abs(adxcdy)) * Math.abs(bdz)
                + (Math.abs(adxbdy) + Math.abs(bdxady)) * Math.abs(cdz);
        double bound = O3D_BOUND * permanent;
        if (det > bound || -det > bound || Double.isNaN(det)) {
            return det;
        }
        return orient3dExact(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }

    /**
     * Evaluates the three-dimensional orientation predicate exactly and reduces it to its sign, for
     * callers that only branch on it.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param az the z coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param bz the z coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @param cz the z coordinate of the third point
     * @param dx the x coordinate of the fourth point
     * @param dy the y coordinate of the fourth point
     * @param dz the z coordinate of the fourth point
     * @return {@link #orient3d} as -1, 0 or 1
     */
    public static int orient3dSign(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, double dx, double dy, double dz) {
        double v = orient3d(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
        return v > 0.0 ? 1 : v < 0.0 ? -1 : 0;
    }

    /**
     * Evaluates the exact three-dimensional orientation predicate for vector arguments; same
     * arithmetic and same exactness as the scalar form.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @param d the vector; must not be {@code null}
     * @return
     *     {@link #orient3d(double, double, double, double, double, double, double, double, double, double, double, double)}
     *     for vectors
     */
    public static double orient3d(Vec3d a, Vec3d b, Vec3d c, Vec3d d) {
        return orient3d(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z(), d.x(), d.y(), d.z());
    }

    static double orient3dExact(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz, double dx, double dy, double dz) {
        double[] adx = Expansions.diff(ax, dx), bdx = Expansions.diff(bx, dx), cdx = Expansions.diff(cx, dx);
        double[] ady = Expansions.diff(ay, dy), bdy = Expansions.diff(by, dy), cdy = Expansions.diff(cy, dy);
        double[] adz = Expansions.diff(az, dz), bdz = Expansions.diff(bz, dz), cdz = Expansions.diff(cz, dz);
        double[] ab = Expansions.sub(Expansions.mul(bdx, cdy), Expansions.mul(cdx, bdy));
        double[] bc = Expansions.sub(Expansions.mul(cdx, ady), Expansions.mul(adx, cdy));
        double[] ca = Expansions.sub(Expansions.mul(adx, bdy), Expansions.mul(bdx, ady));
        double[] det = Expansions.sum(Expansions.sum(Expansions.mul(adz, ab), Expansions.mul(bdz, bc)), Expansions.mul(cdz, ca));
        return signed(det);
    }

    // ---------------------------------------------------------------- incircle

    /**
     * Returns whether {@code d} is inside the circle through {@code a, b, c}, which must be in
     * counter-clockwise order ({@link #orient2d} positive): positive inside, negative outside,
     * exactly zero when the four points are concyclic.
     *
     * <p>If {@code a, b, c} are clockwise the sign is reversed. The sign is exact.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @param dx the x coordinate of the fourth point
     * @param dy the y coordinate of the fourth point
     * @return a positive value if {@code d} is inside the circle, a negative value if it is outside
     *     and zero if it is on the circle; the sign is exact
     */
    public static double incircle(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double adx = ax - dx, bdx = bx - dx, cdx = cx - dx;
        double ady = ay - dy, bdy = by - dy, cdy = cy - dy;
        double bdxcdy = bdx * cdy, cdxbdy = cdx * bdy;
        double alift = adx * adx + ady * ady;
        double cdxady = cdx * ady, adxcdy = adx * cdy;
        double blift = bdx * bdx + bdy * bdy;
        double adxbdy = adx * bdy, bdxady = bdx * ady;
        double clift = cdx * cdx + cdy * cdy;
        double det = alift * (bdxcdy - cdxbdy) + blift * (cdxady - adxcdy) + clift * (adxbdy - bdxady);
        double permanent = (Math.abs(bdxcdy) + Math.abs(cdxbdy)) * alift + (Math.abs(cdxady) + Math.abs(adxcdy)) * blift + (Math.abs(adxbdy) + Math.abs(bdxady)) * clift;
        double bound = ICC_BOUND * permanent;
        if (det > bound || -det > bound || Double.isNaN(det)) {
            return det;
        }
        return incircleExact(ax, ay, bx, by, cx, cy, dx, dy);
    }

    /**
     * Evaluates the in-circle predicate exactly and reduces it to its sign, for callers that only
     * branch on it.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @param dx the x coordinate of the fourth point
     * @param dy the y coordinate of the fourth point
     * @return {@link #incircle} as -1, 0 or 1
     */
    public static int incircleSign(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double v = incircle(ax, ay, bx, by, cx, cy, dx, dy);
        return v > 0.0 ? 1 : v < 0.0 ? -1 : 0;
    }

    /**
     * Evaluates the exact in-circle predicate for vector arguments; same arithmetic and same
     * exactness as the scalar form.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @param d the vector; must not be {@code null}
     * @return {@link #incircle(double, double, double, double, double, double, double, double)} for
     *     vectors
     */
    public static double incircle(Vec2d a, Vec2d b, Vec2d c, Vec2d d) {
        return incircle(a.x(), a.y(), b.x(), b.y(), c.x(), c.y(), d.x(), d.y());
    }

    static double incircleExact(double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy) {
        double[] adx = Expansions.diff(ax, dx), bdx = Expansions.diff(bx, dx), cdx = Expansions.diff(cx, dx);
        double[] ady = Expansions.diff(ay, dy), bdy = Expansions.diff(by, dy), cdy = Expansions.diff(cy, dy);
        double[] alift = Expansions.sum(Expansions.mul(adx, adx), Expansions.mul(ady, ady));
        double[] blift = Expansions.sum(Expansions.mul(bdx, bdx), Expansions.mul(bdy, bdy));
        double[] clift = Expansions.sum(Expansions.mul(cdx, cdx), Expansions.mul(cdy, cdy));
        double[] bc = Expansions.sub(Expansions.mul(bdx, cdy), Expansions.mul(cdx, bdy));
        double[] ca = Expansions.sub(Expansions.mul(cdx, ady), Expansions.mul(adx, cdy));
        double[] ab = Expansions.sub(Expansions.mul(adx, bdy), Expansions.mul(bdx, ady));
        double[] det = Expansions.sum(Expansions.sum(Expansions.mul(alift, bc), Expansions.mul(blift, ca)), Expansions.mul(clift, ab));
        return signed(det);
    }

    // ---------------------------------------------------------------- insphere

    /**
     * Returns whether {@code e} is inside the sphere through {@code a, b, c, d}, which must be
     * ordered so that {@link #orient3d} of them is positive: positive inside, negative outside,
     * exactly zero when the five points are cospherical.
     *
     * <p>With the other order the sign is reversed. The sign is exact.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param az the z coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param bz the z coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @param cz the z coordinate of the third point
     * @param dx the x coordinate of the fourth point
     * @param dy the y coordinate of the fourth point
     * @param dz the z coordinate of the fourth point
     * @param ex the x coordinate of the point {@code e}
     * @param ey the y coordinate of the point {@code e}
     * @param ez the z coordinate of the point {@code e}
     * @return a positive value if {@code e} is inside the sphere, a negative value if it is outside
     *     and zero if it is on the sphere; the sign is exact
     */
    public static double insphere(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                                  double dx, double dy, double dz, double ex, double ey, double ez) {
        double aex = ax - ex, bex = bx - ex, cex = cx - ex, dex = dx - ex;
        double aey = ay - ey, bey = by - ey, cey = cy - ey, dey = dy - ey;
        double aez = az - ez, bez = bz - ez, cez = cz - ez, dez = dz - ez;
        double aexbey = aex * bey, bexaey = bex * aey;
        double ab = aexbey - bexaey;
        double bexcey = bex * cey, cexbey = cex * bey;
        double bc = bexcey - cexbey;
        double cexdey = cex * dey, dexcey = dex * cey;
        double cd = cexdey - dexcey;
        double dexaey = dex * aey, aexdey = aex * dey;
        double da = dexaey - aexdey;
        double aexcey = aex * cey, cexaey = cex * aey;
        double ac = aexcey - cexaey;
        double bexdey = bex * dey, dexbey = dex * bey;
        double bd = bexdey - dexbey;
        double abc = aez * bc - bez * ac + cez * ab;
        double bcd = bez * cd - cez * bd + dez * bc;
        double cda = cez * da + dez * ac + aez * cd;
        double dab = dez * ab + aez * bd + bez * da;
        double alift = aex * aex + aey * aey + aez * aez;
        double blift = bex * bex + bey * bey + bez * bez;
        double clift = cex * cex + cey * cey + cez * cez;
        double dlift = dex * dex + dey * dey + dez * dez;
        double det = (dlift * abc - clift * dab) + (blift * cda - alift * bcd);
        double aezp = Math.abs(aez), bezp = Math.abs(bez), cezp = Math.abs(cez), dezp = Math.abs(dez);
        double abp = Math.abs(aexbey) + Math.abs(bexaey), bcp = Math.abs(bexcey) + Math.abs(cexbey), cdp = Math.abs(cexdey) + Math.abs(dexcey);
        double dap = Math.abs(dexaey) + Math.abs(aexdey), acp = Math.abs(aexcey) + Math.abs(cexaey), bdp = Math.abs(bexdey) + Math.abs(dexbey);
        double permanent = ((cdp * bezp + bdp * cezp + bcp * dezp) * alift) + ((dap * cezp + acp * dezp + cdp * aezp) * blift)
                + ((abp * dezp + bdp * aezp + dap * bezp) * clift) + ((bcp * aezp + acp * bezp + abp * cezp) * dlift);
        double bound = ISP_BOUND * permanent;
        if (det > bound || -det > bound || Double.isNaN(det)) {
            return det;
        }
        return insphereExact(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
    }

    /**
     * Evaluates the in-sphere predicate exactly and reduces it to its sign, for callers that only
     * branch on it.
     *
     * @param ax the x coordinate of the first point
     * @param ay the y coordinate of the first point
     * @param az the z coordinate of the first point
     * @param bx the x coordinate of the second point
     * @param by the y coordinate of the second point
     * @param bz the z coordinate of the second point
     * @param cx the x coordinate of the third point
     * @param cy the y coordinate of the third point
     * @param cz the z coordinate of the third point
     * @param dx the x coordinate of the fourth point
     * @param dy the y coordinate of the fourth point
     * @param dz the z coordinate of the fourth point
     * @param ex the x coordinate of the point {@code e}
     * @param ey the y coordinate of the point {@code e}
     * @param ez the z coordinate of the point {@code e}
     * @return {@link #insphere} as -1, 0 or 1
     */
    public static int insphereSign(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                                   double dx, double dy, double dz, double ex, double ey, double ez) {
        double v = insphere(ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz, ex, ey, ez);
        return v > 0.0 ? 1 : v < 0.0 ? -1 : 0;
    }

    /**
     * Evaluates the exact in-sphere predicate for vector arguments; same arithmetic and same
     * exactness as the scalar form.
     *
     * @param a the first vector; must not be {@code null}
     * @param b the second vector; must not be {@code null}
     * @param c the vector; must not be {@code null}
     * @param d the vector; must not be {@code null}
     * @param e the vector; must not be {@code null}
     * @return
     *     {@link #insphere(double, double, double, double, double, double, double, double, double, double, double, double, double, double, double)}
     *     for vectors
     */
    public static double insphere(Vec3d a, Vec3d b, Vec3d c, Vec3d d, Vec3d e) {
        return insphere(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z(), d.x(), d.y(), d.z(), e.x(), e.y(), e.z());
    }

    static double insphereExact(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz,
                                double dx, double dy, double dz, double ex, double ey, double ez) {
        double[] aex = Expansions.diff(ax, ex), bex = Expansions.diff(bx, ex), cex = Expansions.diff(cx, ex), dex = Expansions.diff(dx, ex);
        double[] aey = Expansions.diff(ay, ey), bey = Expansions.diff(by, ey), cey = Expansions.diff(cy, ey), dey = Expansions.diff(dy, ey);
        double[] aez = Expansions.diff(az, ez), bez = Expansions.diff(bz, ez), cez = Expansions.diff(cz, ez), dez = Expansions.diff(dz, ez);
        double[] ab = Expansions.sub(Expansions.mul(aex, bey), Expansions.mul(bex, aey));
        double[] bc = Expansions.sub(Expansions.mul(bex, cey), Expansions.mul(cex, bey));
        double[] cd = Expansions.sub(Expansions.mul(cex, dey), Expansions.mul(dex, cey));
        double[] da = Expansions.sub(Expansions.mul(dex, aey), Expansions.mul(aex, dey));
        double[] ac = Expansions.sub(Expansions.mul(aex, cey), Expansions.mul(cex, aey));
        double[] bd = Expansions.sub(Expansions.mul(bex, dey), Expansions.mul(dex, bey));
        double[] abc = Expansions.sum(Expansions.sub(Expansions.mul(aez, bc), Expansions.mul(bez, ac)), Expansions.mul(cez, ab));
        double[] bcd = Expansions.sum(Expansions.sub(Expansions.mul(bez, cd), Expansions.mul(cez, bd)), Expansions.mul(dez, bc));
        double[] cda = Expansions.sum(Expansions.sum(Expansions.mul(cez, da), Expansions.mul(dez, ac)), Expansions.mul(aez, cd));
        double[] dab = Expansions.sum(Expansions.sum(Expansions.mul(dez, ab), Expansions.mul(aez, bd)), Expansions.mul(bez, da));
        double[] alift = Expansions.sum(Expansions.sum(Expansions.mul(aex, aex), Expansions.mul(aey, aey)), Expansions.mul(aez, aez));
        double[] blift = Expansions.sum(Expansions.sum(Expansions.mul(bex, bex), Expansions.mul(bey, bey)), Expansions.mul(bez, bez));
        double[] clift = Expansions.sum(Expansions.sum(Expansions.mul(cex, cex), Expansions.mul(cey, cey)), Expansions.mul(cez, cez));
        double[] dlift = Expansions.sum(Expansions.sum(Expansions.mul(dex, dex), Expansions.mul(dey, dey)), Expansions.mul(dez, dez));
        double[] left = Expansions.sub(Expansions.mul(dlift, abc), Expansions.mul(clift, dab));
        double[] right = Expansions.sub(Expansions.mul(blift, cda), Expansions.mul(alift, bcd));
        return signed(Expansions.sum(left, right));
    }
}
