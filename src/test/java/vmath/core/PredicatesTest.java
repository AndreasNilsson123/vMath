package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.Report;

/**
 * The exact predicates against {@link BigDecimal}, which represents every double exactly and multiplies and adds without rounding. The input is chosen to be hard:
 * points that are exactly degenerate (collinear, coplanar, concyclic, cospherical on integer lattices, at several scales) and points that are degenerate up to the last
 * bits (a point on a line, computed in double and so a few ulps off it). The tests also count how often the plain floating-point determinant gets the sign wrong, to show
 * that the cases are hard enough to need the exact stage.
 */
class PredicatesTest {

    private static BigDecimal bd(double d) {
        return new BigDecimal(d);
    }

    private static int sign(BigDecimal v) {
        return v.signum();
    }

    // ---------------------------------------------------------------- the oracle: the same formulas, in exact arithmetic

    private static int exactOrient2d(double ax, double ay, double bx, double by, double cx, double cy) {
        BigDecimal l = bd(ax).subtract(bd(cx)).multiply(bd(by).subtract(bd(cy)));
        BigDecimal r = bd(ay).subtract(bd(cy)).multiply(bd(bx).subtract(bd(cx)));
        return sign(l.subtract(r));
    }

    private static int exactOrient3d(double[] a, double[] b, double[] c, double[] d) {
        BigDecimal[] u = diff(a, d), v = diff(b, d), w = diff(c, d);
        BigDecimal det = u[0].multiply(v[1].multiply(w[2]).subtract(v[2].multiply(w[1])))
                .subtract(u[1].multiply(v[0].multiply(w[2]).subtract(v[2].multiply(w[0]))))
                .add(u[2].multiply(v[0].multiply(w[1]).subtract(v[1].multiply(w[0]))));
        return sign(det);
    }

    private static BigDecimal[] diff(double[] p, double[] q) {
        BigDecimal[] r = new BigDecimal[p.length];
        for (int i = 0; i < p.length; i++) {
            r[i] = bd(p[i]).subtract(bd(q[i]));
        }
        return r;
    }

    private static BigDecimal lift(BigDecimal[] v) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal x : v) {
            s = s.add(x.multiply(x));
        }
        return s;
    }

    /** Determinant of the 3x3 matrix with rows {x, y, lift} (incircle) as the exact sign. */
    private static int exactIncircle(double[] a, double[] b, double[] c, double[] d) {
        BigDecimal[] p = diff(a, d), q = diff(b, d), r = diff(c, d);
        BigDecimal pl = lift(p), ql = lift(q), rl = lift(r);
        BigDecimal det = pl.multiply(q[0].multiply(r[1]).subtract(r[0].multiply(q[1])))
                .add(ql.multiply(r[0].multiply(p[1]).subtract(p[0].multiply(r[1]))))
                .add(rl.multiply(p[0].multiply(q[1]).subtract(q[0].multiply(p[1]))));
        return sign(det);
    }

    /** Determinant of the 4x4 matrix with rows {x, y, z, lift} (insphere), by cofactor expansion along the last column, in exact arithmetic. */
    private static int exactInsphere(double[] a, double[] b, double[] c, double[] d, double[] e) {
        BigDecimal[][] m = {diff(a, e), diff(b, e), diff(c, e), diff(d, e)};
        BigDecimal[] lifts = new BigDecimal[4];
        for (int i = 0; i < 4; i++) {
            lifts[i] = lift(m[i]);
        }
        // Shewchuk's sign convention: det of rows (x, y, z, lift) gives a positive value inside for positively oriented a, b, c, d, with the orientation of rows reversed
        BigDecimal det = BigDecimal.ZERO;
        for (int i = 0; i < 4; i++) {
            int[] rest = new int[3];
            int k = 0;
            for (int j = 0; j < 4; j++) {
                if (j != i) {
                    rest[k++] = j;
                }
            }
            BigDecimal minor = m[rest[0]][0].multiply(m[rest[1]][1].multiply(m[rest[2]][2]).subtract(m[rest[1]][2].multiply(m[rest[2]][1])))
                    .subtract(m[rest[0]][1].multiply(m[rest[1]][0].multiply(m[rest[2]][2]).subtract(m[rest[1]][2].multiply(m[rest[2]][0]))))
                    .add(m[rest[0]][2].multiply(m[rest[1]][0].multiply(m[rest[2]][1]).subtract(m[rest[1]][1].multiply(m[rest[2]][0]))));
            BigDecimal term = lifts[i].multiply(minor);
            det = (i + 3) % 2 == 0 ? det.add(term) : det.subtract(term);
        }
        return sign(det);
    }

    // ---------------------------------------------------------------- generators

    private static double scale(SplittableRandom r) {
        return Math.scalb(1.0, r.nextInt(41) - 20);
    }

    /** A coordinate: a small integer, a random double in [-1, 1], or a double with random low bits, times a power of two. */
    private static double coord(SplittableRandom r, int kind, double s) {
        return switch (kind) {
            case 0 -> (r.nextInt(21) - 10) * s;
            case 1 -> (r.nextDouble() * 2 - 1) * s;
            default -> Double.longBitsToDouble(Double.doubleToLongBits(1.0 + r.nextDouble()) ^ r.nextInt(8)) * (r.nextBoolean() ? s : -s);
        };
    }

    // ---------------------------------------------------------------- orient2d

    @Test
    void orient2dMatchesTheExactSignOnHardInput() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED);
        int cases = Math.max(60_000, 10 * Rnd.N);
        int zero = 0, naiveWrong = 0;
        for (int i = 0; i < cases; i++) {
            double ax, ay, bx, by, cx, cy;
            double s = scale(r);
            switch (i % 4) {
                case 0 -> { // random points
                    ax = coord(r, 1, s); ay = coord(r, 1, s); bx = coord(r, 1, s); by = coord(r, 1, s); cx = coord(r, 1, s); cy = coord(r, 1, s);
                }
                case 1 -> { // c on the line ab up to rounding
                    ax = coord(r, 1, s); ay = coord(r, 1, s); bx = coord(r, 1, s); by = coord(r, 1, s);
                    double t = r.nextDouble() * 3 - 1;
                    cx = ax + t * (bx - ax);
                    cy = ay + t * (by - ay);
                }
                case 2 -> { // integer lattice: collinear very often
                    ax = coord(r, 0, s); ay = coord(r, 0, s); bx = coord(r, 0, s); by = coord(r, 0, s);
                    int t = r.nextInt(5) - 2;
                    cx = ax + t * (bx - ax);
                    cy = ay + t * (by - ay);
                }
                default -> { // nearly equal coordinates with random low bits
                    ax = coord(r, 2, s); ay = coord(r, 2, s); bx = coord(r, 2, s); by = coord(r, 2, s); cx = coord(r, 2, s); cy = coord(r, 2, s);
                }
            }
            int expected = exactOrient2d(ax, ay, bx, by, cx, cy);
            int got = Predicates.orient2dSign(ax, ay, bx, by, cx, cy);
            assertEquals(expected, got, "orient2d " + ax + "," + ay + " " + bx + "," + by + " " + cx + "," + cy);
            double naive = (ax - cx) * (by - cy) - (ay - cy) * (bx - cx);
            if (Math.signum(naive) != expected) {
                naiveWrong++;
            }
            if (expected == 0) {
                zero++;
                assertTrue(Predicates.orient2d(ax, ay, bx, by, cx, cy) == 0.0, "an exactly degenerate input gives exactly zero");
            }
            // symmetries that only an exact predicate has
            assertEquals(-expected, Predicates.orient2dSign(bx, by, ax, ay, cx, cy));
            assertEquals(expected, Predicates.orient2dSign(bx, by, cx, cy, ax, ay));
        }
        Report.printf("orient2d: %d cases, %d exactly collinear, the plain determinant had the wrong sign in %d%n", cases, zero, naiveWrong);
        assertTrue(zero > cases / 20 && naiveWrong > 100, "the cases must be hard: " + zero + " zero, " + naiveWrong + " naive errors");
    }

    @Test
    void orient2dConventionAndVectors() {
        assertTrue(Predicates.orient2d(0, 0, 1, 0, 0, 1) > 0, "counter-clockwise is positive");
        assertTrue(Predicates.orient2d(0, 0, 0, 1, 1, 0) < 0);
        assertTrue(Predicates.orient2d(0, 0, 1, 1, 2, 2) == 0.0);
        assertTrue(Predicates.orient2d(new Vec2d(0, 0), new Vec2d(1, 0), new Vec2d(0, 1)) > 0);
        assertTrue(Double.isNaN(Predicates.orient2d(0, 0, 1, Double.NaN, 0, 1)));
        // the classic failure of floating point: points that are collinear in the real numbers, one coordinate a step off the line
        double e = Math.ulp(1.0);
        assertTrue(Predicates.orient2d(0.5, 0.5, 12, 12, 24, 24 + 24 * e) > 0);
    }

    // ---------------------------------------------------------------- orient3d

    @Test
    void orient3dMatchesTheExactSignOnHardInput() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED + 1);
        int cases = Math.max(40_000, 7 * Rnd.N);
        int zero = 0, naiveWrong = 0;
        for (int i = 0; i < cases; i++) {
            double s = scale(r);
            int kind = i % 4 == 0 ? 1 : i % 4 == 2 ? 0 : 2;
            double[] a = pt(r, kind, s, 3), b = pt(r, kind, s, 3), c = pt(r, kind, s, 3), d;
            if (i % 4 == 1 || i % 4 == 2) { // d in the plane of a, b, c: a + u (b - a) + v (c - a), with small integers when exact
                double u = kind == 0 ? r.nextInt(5) - 2 : r.nextDouble() * 3 - 1, v = kind == 0 ? r.nextInt(5) - 2 : r.nextDouble() * 3 - 1;
                d = new double[3];
                for (int k = 0; k < 3; k++) {
                    d[k] = a[k] + u * (b[k] - a[k]) + v * (c[k] - a[k]);
                }
            } else {
                d = pt(r, kind, s, 3);
            }
            int expected = exactOrient3d(a, b, c, d);
            int got = Predicates.orient3dSign(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]);
            assertEquals(expected, got, "orient3d at case " + i);
            double naive = naiveOrient3d(a, b, c, d);
            if (Math.signum(naive) != expected) {
                naiveWrong++;
            }
            if (expected == 0) {
                zero++;
                assertTrue(Predicates.orient3d(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]) == 0.0);
            }
            assertEquals(-expected, Predicates.orient3dSign(b[0], b[1], b[2], a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2]), "swapping two points flips the sign");
        }
        Report.printf("orient3d: %d cases, %d exactly coplanar, the plain determinant had the wrong sign in %d%n", cases, zero, naiveWrong);
        assertTrue(zero > cases / 40 && naiveWrong > 100, "the cases must be hard: " + zero + " zero, " + naiveWrong + " naive errors");
    }

    private static double[] pt(SplittableRandom r, int kind, double s, int n) {
        double[] p = new double[n];
        for (int i = 0; i < n; i++) {
            p[i] = coord(r, kind, s);
        }
        return p;
    }

    private static double naiveOrient3d(double[] a, double[] b, double[] c, double[] d) {
        double adx = a[0] - d[0], bdx = b[0] - d[0], cdx = c[0] - d[0];
        double ady = a[1] - d[1], bdy = b[1] - d[1], cdy = c[1] - d[1];
        double adz = a[2] - d[2], bdz = b[2] - d[2], cdz = c[2] - d[2];
        return adz * (bdx * cdy - cdx * bdy) + bdz * (cdx * ady - adx * cdy) + cdz * (adx * bdy - bdx * ady);
    }

    @Test
    void orient3dConvention() {
        assertTrue(Predicates.orient3d(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, -1) > 0, "d below the plane of a counter-clockwise triangle is positive");
        assertTrue(Predicates.orient3d(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1) < 0);
        assertTrue(Predicates.orient3d(0, 0, 0, 1, 0, 0, 0, 1, 0, 5, 7, 0) == 0.0);
        assertTrue(Predicates.orient3d(new Vec3d(0, 0, 0), new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, -1)) > 0);
        // agrees with the triple product: (b - a) x (c - a) . (d - a) negative <=> positive orient3d
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < 1000; i++) {
            Vec3d a = rv(r), b = rv(r), c = rv(r), d = rv(r);
            double triple = b.sub(a).cross(c.sub(a)).dot(d.sub(a));
            assertEquals(triple < 0 ? 1 : -1, Predicates.orient3dSign(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z(), d.x(), d.y(), d.z()));
        }
    }

    private static Vec3d rv(SplittableRandom r) {
        return new Vec3d(r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1, r.nextDouble() * 2 - 1);
    }

    // ---------------------------------------------------------------- incircle

    @Test
    void incircleMatchesTheExactSignOnHardInput() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED + 2);
        int cases = Math.max(40_000, 7 * Rnd.N);
        // lattice points on circles: all on x^2 + y^2 = 5^2, 13^2, 25^2, 65^2 (several each), concyclic exactly
        int[][] circle = {{3, 4}, {4, 3}, {5, 0}, {0, 5}, {-3, 4}, {-4, -3}, {0, -5}, {3, -4}, {-5, 0}, {4, -3}, {-4, 3}, {-3, -4}};
        int zero = 0, naiveWrong = 0;
        for (int i = 0; i < cases; i++) {
            double s = scale(r);
            double[][] p = new double[4][];
            switch (i % 3) {
                case 0 -> {
                    for (int k = 0; k < 4; k++) {
                        p[k] = pt(r, 1, s, 2);
                    }
                }
                case 1 -> { // four distinct points of the lattice circle of radius 5, scaled: exactly concyclic
                    int[] pick = new int[4];
                    for (int k = 0; k < 4; k++) {
                        int c;
                        boolean fresh;
                        do {
                            c = r.nextInt(circle.length);
                            fresh = true;
                            for (int q = 0; q < k; q++) {
                                fresh &= pick[q] != c;
                            }
                        } while (!fresh);
                        pick[k] = c;
                        p[k] = new double[] {circle[c][0] * s, circle[c][1] * s};
                    }
                }
                default -> { // d near the circle through a, b, c (a, b, c on the unit circle, d on it up to rounding)
                    double[] angles = new double[4];
                    for (int k = 0; k < 4; k++) {
                        angles[k] = r.nextDouble() * 2 * Math.PI;
                        p[k] = new double[] {Math.cos(angles[k]) * s, Math.sin(angles[k]) * s};
                    }
                }
            }
            int expected = exactIncircle(p[0], p[1], p[2], p[3]);
            int got = Predicates.incircleSign(p[0][0], p[0][1], p[1][0], p[1][1], p[2][0], p[2][1], p[3][0], p[3][1]);
            assertEquals(expected, got, "incircle at case " + i);
            if (expected == 0) {
                zero++;
            }
            double adx = p[0][0] - p[3][0], ady = p[0][1] - p[3][1], bdx = p[1][0] - p[3][0], bdy = p[1][1] - p[3][1], cdx = p[2][0] - p[3][0], cdy = p[2][1] - p[3][1];
            double naive = (adx * adx + ady * ady) * (bdx * cdy - cdx * bdy) + (bdx * bdx + bdy * bdy) * (cdx * ady - adx * cdy) + (cdx * cdx + cdy * cdy) * (adx * bdy - bdx * ady);
            if (Math.signum(naive) != expected) {
                naiveWrong++;
            }
        }
        Report.printf("incircle: %d cases, %d exactly concyclic, the plain determinant had the wrong sign in %d%n", cases, zero, naiveWrong);
        assertTrue(zero > cases / 10 && naiveWrong > 50, "the cases must be hard: " + zero + " zero, " + naiveWrong + " naive errors");
    }

    @Test
    void incircleConvention() {
        assertTrue(Predicates.incircle(1, 0, 0, 1, -1, 0, 0, 0) > 0, "the origin is inside the unit circle through three counter-clockwise points");
        assertTrue(Predicates.incircle(1, 0, 0, 1, -1, 0, 2, 2) < 0);
        assertTrue(Predicates.incircle(1, 0, 0, 1, -1, 0, 0, -1) == 0.0);
        assertTrue(Predicates.incircle(new Vec2d(1, 0), new Vec2d(0, 1), new Vec2d(-1, 0), new Vec2d(0, 0)) > 0);
    }

    // ---------------------------------------------------------------- insphere

    @Test
    void insphereMatchesTheExactSignOnHardInput() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED + 3);
        int cases = Math.max(20_000, 4 * Rnd.N);
        // lattice points on the sphere x^2 + y^2 + z^2 = 9 and 25 (exactly cospherical)
        int[][] sphere = {{3, 0, 0}, {-3, 0, 0}, {0, 3, 0}, {0, -3, 0}, {0, 0, 3}, {0, 0, -3}, {1, 2, 2}, {-1, 2, 2}, {1, -2, 2}, {1, 2, -2}, {2, 1, 2}, {2, 2, 1}, {-2, -2, -1}, {2, -1, -2}};
        int zero = 0, naiveWrong = 0;
        for (int i = 0; i < cases; i++) {
            double s = scale(r);
            double[][] p = new double[5][];
            if (i % 3 == 0) {
                for (int k = 0; k < 5; k++) {
                    p[k] = pt(r, 1, s, 3);
                }
            } else if (i % 3 == 1) {
                int[] pick = new int[5];
                for (int k = 0; k < 5; k++) {
                    int c;
                    boolean fresh;
                    do {
                        c = r.nextInt(sphere.length);
                        fresh = true;
                        for (int q = 0; q < k; q++) {
                            fresh &= pick[q] != c;
                        }
                    } while (!fresh);
                    pick[k] = c;
                    p[k] = new double[] {sphere[c][0] * s, sphere[c][1] * s, sphere[c][2] * s};
                }
            } else {
                for (int k = 0; k < 5; k++) { // on the sphere up to rounding
                    double[] v = {r.nextGaussian(), r.nextGaussian(), r.nextGaussian()};
                    double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                    p[k] = new double[] {v[0] / len * s, v[1] / len * s, v[2] / len * s};
                }
            }
            int expected = exactInsphere(p[0], p[1], p[2], p[3], p[4]);
            double v = Predicates.insphere(p[0][0], p[0][1], p[0][2], p[1][0], p[1][1], p[1][2], p[2][0], p[2][1], p[2][2], p[3][0], p[3][1], p[3][2], p[4][0], p[4][1], p[4][2]);
            // the oracle's convention: the sign of the determinant of rows (x, y, z, lift), matching Shewchuk's insphere for any orientation of a, b, c, d
            assertEquals(expected, v > 0 ? 1 : v < 0 ? -1 : 0, "insphere at case " + i);
            if (expected == 0) {
                zero++;
            }
            if (v != 0 && Math.signum(naiveInsphere(p)) != expected) {
                naiveWrong++;
            } else if (v == 0 && naiveInsphere(p) != 0) {
                naiveWrong++;
            }
        }
        Report.printf("insphere: %d cases, %d exactly cospherical, the plain determinant had the wrong sign in %d%n", cases, zero, naiveWrong);
        assertTrue(zero > cases / 10 && naiveWrong > 30, "the cases must be hard: " + zero + " zero, " + naiveWrong + " naive errors");
    }

    private static double naiveInsphere(double[][] p) {
        double[][] m = new double[4][4];
        for (int i = 0; i < 4; i++) {
            double x = p[i][0] - p[4][0], y = p[i][1] - p[4][1], z = p[i][2] - p[4][2];
            m[i] = new double[] {x, y, z, x * x + y * y + z * z};
        }
        double det = 0;
        for (int i = 0; i < 4; i++) {
            int[] rest = new int[3];
            int k = 0;
            for (int j = 0; j < 4; j++) {
                if (j != i) {
                    rest[k++] = j;
                }
            }
            double minor = m[rest[0]][0] * (m[rest[1]][1] * m[rest[2]][2] - m[rest[1]][2] * m[rest[2]][1])
                    - m[rest[0]][1] * (m[rest[1]][0] * m[rest[2]][2] - m[rest[1]][2] * m[rest[2]][0])
                    + m[rest[0]][2] * (m[rest[1]][0] * m[rest[2]][1] - m[rest[1]][1] * m[rest[2]][0]);
            det += ((i + 3) % 2 == 0 ? 1 : -1) * m[i][3] * minor;
        }
        return det;
    }

    @Test
    void insphereConvention() {
        // a, b, c, d with positive orient3d, e inside the unit sphere: positive
        double[] a = {1, 0, 0}, b = {0, 1, 0}, c = {0, 0, 1}, d = {-1, 0, 0};
        if (Predicates.orient3d(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]) < 0) {
            double[] t = a;
            a = b;
            b = t;
        }
        assertTrue(Predicates.orient3d(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]) > 0);
        assertTrue(Predicates.insphere(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], 0, 0, 0) > 0, "inside is positive");
        assertTrue(Predicates.insphere(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], 3, 3, 3) < 0, "outside is negative");
        assertTrue(Predicates.insphere(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2], 0, -1, 0) == 0.0);
        assertTrue(Predicates.insphere(new Vec3d(a[0], a[1], a[2]), new Vec3d(b[0], b[1], b[2]), new Vec3d(c[0], c[1], c[2]), new Vec3d(d[0], d[1], d[2]), new Vec3d(0, 0, 0)) > 0);
    }

    // ---------------------------------------------------------------- the expansion arithmetic underneath

    private static BigDecimal value(double[] e) {
        BigDecimal s = BigDecimal.ZERO;
        for (double c : e) {
            s = s.add(bd(c));
        }
        return s;
    }

    @Test
    void expansionArithmeticIsExact() {
        SplittableRandom r = new SplittableRandom(Rnd.SEED + 4);
        for (int i = 0; i < 20_000; i++) {
            double a = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(80) - 40), b = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(80) - 40);
            double c = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(80) - 40), d = (r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(80) - 40);
            double[] x = Expansions.diff(a, b), y = Expansions.diff(c, d);
            assertEquals(0, value(x).compareTo(bd(a).subtract(bd(b))), "diff");
            assertEquals(0, value(Expansions.sum(x, y)).compareTo(bd(a).subtract(bd(b)).add(bd(c).subtract(bd(d)))), "sum");
            assertEquals(0, value(Expansions.sub(x, y)).compareTo(bd(a).subtract(bd(b)).subtract(bd(c).subtract(bd(d)))), "sub");
            assertEquals(0, value(Expansions.scale(x, c)).compareTo(bd(a).subtract(bd(b)).multiply(bd(c))), "scale");
            double[] prod = Expansions.mul(x, y);
            BigDecimal expected = bd(a).subtract(bd(b)).multiply(bd(c).subtract(bd(d)));
            assertEquals(0, value(prod).compareTo(expected), "mul");
            assertEquals(expected.signum(), Expansions.sign(prod), "the sign of a product is the sign of its last component");
            double[] big = Expansions.mul(Expansions.mul(x, y), Expansions.sum(x, y));
            assertEquals(Expansions.sign(big), value(big).signum(), "sign of a longer expansion");
        }
    }
}
