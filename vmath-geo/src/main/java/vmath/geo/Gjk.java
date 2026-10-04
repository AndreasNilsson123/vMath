package vmath.geo;

import vmath.annotations.Experimental;

/**
 * Collision queries between two {@link ConvexShape}s: whether they overlap, how far apart they are
 * (with the closest point on each), and, when they overlap, how deeply and in which direction (with
 * a contact point on each).
 *
 * <p>They are the Gilbert-Johnson-Keerthi algorithm for the distance and the expanding polytope
 * algorithm (EPA) for the penetration, both working on the Minkowski difference of the shapes
 * through their support functions, in double precision.
 *
 * <p>An instance owns its working arrays, so after construction the queries <b>allocate nothing</b>
 * (the shapes' support functions must not either); use one instance per thread. Results go to a
 * caller-supplied {@link Result}.
 *
 * <p><b>Conventions.</b> {@code normal} is a unit vector such that moving the second shape by
 * {@code normal * depth} (or, for a distance, the first by {@code normal * distance} towards it)
 * separates them: it points from the first shape towards the second. {@code pointA} lies on the
 * first shape and {@code pointB} on the second; for a distance, {@code pointB - pointA} has length
 * {@code distance}; for a penetration, {@code pointA - pointB = normal * depth}.
 *
 * <p><b>Tolerances.</b> The iteration stops when the closest point stops improving by more than a
 * relative {@value #RELATIVE_TOLERANCE}; shapes closer than {@value #TOUCH_DISTANCE} count as
 * touching and are reported as overlapping with a depth of about zero. The distance is accurate to
 * about that relative tolerance of itself; EPA's depth is accurate to about {@value #EPA_TOLERANCE}
 * times the size of the shapes. Both are limited by the {@value #MAX_ITERATIONS} iterations allowed
 * (never reached in the tests, which use polytopes of up to 200 vertices and rounded shapes), and
 * EPA by room for {@value #MAX_FACES} faces; EPA is allowed {@value #MAX_EPA_ITERATIONS}
 * iterations, which is far more than a polytope needs but converges to only about 1e-3 of the
 * radius for two almost concentric spheres. Shapes with no volume (a flat polytope pressed against
 * another) may report a depth of zero.
 *
 * <p><b>Thread safety.</b> Not thread-safe (it holds scratch memory): one instance per thread. The
 * shapes may be shared.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Gjk gjk = new Gjk();                                                 // keeps its scratch space: reuse it
 * Gjk.Result result = new Gjk.Result();
 * ConvexShape a = ConvexShapes.of(Spheref.of(Vec3f.ZERO, 1f));
 * ConvexShape b = ConvexShapes.of(Spheref.of(new Vec3f(1.5f, 0f, 0f), 1f));
 * if (gjk.penetration(a, b, result)) {
 *     double depth = result.depth;                                     // how far they overlap
 *     double nx = result.normal[0];                                    // from a towards b
 * }
 * }</pre>
 */
@Experimental("the tolerances and the Result fields may change when the swept and continuous queries are added")
public final class Gjk {

    /**
     * The relative improvement below which the distance iteration stops.
     */
    public static final double RELATIVE_TOLERANCE = 1e-12;
    /**
     * Shapes closer than this are touching.
     */
    public static final double TOUCH_DISTANCE = 1e-9;
    /**
     * EPA stops when the support point is this close, relative to the size, to the nearest face.
     */
    public static final double EPA_TOLERANCE = 1e-9;
    /**
     * The iteration limit of GJK and EPA.
     */
    public static final int MAX_ITERATIONS = 128;
    /**
     * The iteration limit of EPA, which needs many more steps than GJK to converge on smooth
     * shapes.
     */
    public static final int MAX_EPA_ITERATIONS = 500;
    /**
     * The room for faces of the expanding polytope.
     */
    public static final int MAX_FACES = 2048;
    private static final int MAX_VERTICES = 520;

    /**
     * What a query reports; reuse one object for many queries.
     */
    public static final class Result {
        /**
         * The distance between the shapes: 0 when they overlap or touch.
         */
        public double distance;
        /**
         * The penetration depth when they overlap (0 otherwise).
         */
        public double depth;
        /**
         * Whether the shapes overlap or touch.
         */
        public boolean overlapping;
        /**
         * A unit vector from the first shape towards the second (see the class comment).
         */
        public final double[] normal = new double[3];
        /**
         * The closest point on the first shape (a contact point when overlapping).
         */
        public final double[] pointA = new double[3];
        /**
         * The closest point on the second shape (a contact point when overlapping).
         */
        public final double[] pointB = new double[3];

        /**
         * Creates an empty result.
         */
        public Result() {
        }
    }

    // ---- the GJK simplex: points of the Minkowski difference with the points of A and B they came from
    private final double[][] w = new double[4][3];
    private final double[][] wa = new double[4][3];
    private final double[][] wb = new double[4][3];
    private final double[] lambda = new double[4];
    private int size;
    private final double[] v = new double[3];
    private final double[] tmpA = new double[3];
    private final double[] tmpB = new double[3];
    private final double[] dir = new double[3];

    // ---- scratch of the triangle and tetrahedron closest-point routines
    private final double[] cw = new double[3];
    private final double[] cv = new double[3];
    private final double[] bestV = new double[3];
    private final double[] bestW = new double[4];
    private final int[] bestIdx = new int[4];

    // ---- the expanding polytope
    private final double[][] ev = new double[MAX_VERTICES][3];
    private final double[][] eva = new double[MAX_VERTICES][3];
    private final double[][] evb = new double[MAX_VERTICES][3];
    private final int[] fv = new int[3 * MAX_FACES];
    private final double[] fn = new double[3 * MAX_FACES];
    private final double[] fd = new double[MAX_FACES];
    private final boolean[] fAlive = new boolean[MAX_FACES];
    private final int[] horizon = new int[2 * 3 * MAX_FACES];
    private int vertices;
    private int faces;

    /**
     * Creates a query object with its working arrays; reuse it for many queries.
     */
    public Gjk() {
    }

    // ---------------------------------------------------------------- the Minkowski difference

    /**
     * {@code support_A(d) - support_B(-d)} into {@code simplex[slot]}, remembering the two points.
     */
    private void minkowski(ConvexShape a, ConvexShape b, double dx, double dy, double dz, double[] outW, double[] outA, double[] outB) {
        a.support(dx, dy, dz, outA);
        b.support(-dx, -dy, -dz, outB);
        outW[0] = outA[0] - outB[0];
        outW[1] = outA[1] - outB[1];
        outW[2] = outA[2] - outB[2];
    }

    // ---------------------------------------------------------------- GJK

    /**
     * Returns whether the shapes overlap or touch (their distance is at most
     * {@link #TOUCH_DISTANCE}).
     *
     * @param a the first convex shape; must not be {@code null}
     * @param b the second convex shape; must not be {@code null}
     * @return {@code true} if the shapes overlap or touch (their distance is at most
     *     {@link #TOUCH_DISTANCE})
     */
    public boolean intersects(ConvexShape a, ConvexShape b) {
        return run(a, b);
    }

    /**
     * Computes the distance between two convex shapes with the Gilbert-Johnson-Keerthi algorithm,
     * which works on support functions only; overlapping shapes give zero, and the closest points
     * are written to the result object, which is why the query allocates nothing.
     *
     * @param a the first convex shape; must not be {@code null}
     * @param b the second convex shape; must not be {@code null}
     * @param result receives the result; must not be {@code null}
     * @return the distance between the shapes, 0 when they overlap or touch, with the closest point
     *     on each in {@code result} (when they overlap {@code pointA} and {@code pointB} are a pair
     *     of points that coincide to within the tolerance, and {@code normal} is not set)
     */
    public double distance(ConvexShape a, ConvexShape b, Result result) {
        boolean inside = run(a, b);
        result.overlapping = inside;
        result.depth = 0;
        witness(result);
        if (inside) {
            result.distance = 0;
            return 0;
        }
        double d = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        result.distance = d;
        // v = A - B at the closest pair: B must move along +(A - B)... the normal from A to B is -v / |v|
        result.normal[0] = -v[0] / d;
        result.normal[1] = -v[1] / d;
        result.normal[2] = -v[2] / d;
        return d;
    }

    /**
     * Tests for overlap and, when the shapes overlap, finds the penetration: the depth, the unit
     * normal from the first towards the second shape, and a contact point on each.
     *
     * <p>Returns false (and sets {@code result.distance}) when they are apart.
     *
     * @param a the first convex shape; must not be {@code null}
     * @param b the second convex shape; must not be {@code null}
     * @param result receives the result; must not be {@code null}
     * @return {@code true} if the shapes overlap, in which case {@code result} holds the
     *     penetration; {@code false} otherwise
     */
    public boolean penetration(ConvexShape a, ConvexShape b, Result result) {
        boolean inside = run(a, b);
        result.overlapping = inside;
        if (!inside) {
            witness(result);
            double d = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            result.distance = d;
            result.depth = 0;
            result.normal[0] = -v[0] / d;
            result.normal[1] = -v[1] / d;
            result.normal[2] = -v[2] / d;
            return false;
        }
        result.distance = 0;
        epa(a, b, result);
        return true;
    }

    private void witness(Result r) {
        double pax = 0, pay = 0, paz = 0, pbx = 0, pby = 0, pbz = 0;
        for (int i = 0; i < size; i++) {
            pax += lambda[i] * wa[i][0];
            pay += lambda[i] * wa[i][1];
            paz += lambda[i] * wa[i][2];
            pbx += lambda[i] * wb[i][0];
            pby += lambda[i] * wb[i][1];
            pbz += lambda[i] * wb[i][2];
        }
        r.pointA[0] = pax;
        r.pointA[1] = pay;
        r.pointA[2] = paz;
        r.pointB[0] = pbx;
        r.pointB[1] = pby;
        r.pointB[2] = pbz;
    }

    /**
     * Runs GJK; returns true when the origin is inside (or within the touch distance of) the
     * Minkowski difference.
     *
     * <p>On return {@code v}, the simplex and {@code lambda} are set.
     */
    private boolean run(ConvexShape a, ConvexShape b) {
        size = 1;
        minkowski(a, b, 1, 0, 0, w[0], wa[0], wb[0]);
        lambda[0] = 1;
        v[0] = w[0][0];
        v[1] = w[0][1];
        v[2] = w[0][2];
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double vv = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
            if (vv <= TOUCH_DISTANCE * TOUCH_DISTANCE) {
                return true;
            }
            minkowski(a, b, -v[0], -v[1], -v[2], tmpW, tmpA, tmpB);
            double vw = v[0] * tmpW[0] + v[1] * tmpW[1] + v[2] * tmpW[2];
            // no support point beyond the current closest point's plane: v is the closest point of the whole difference
            if (vv - vw <= RELATIVE_TOLERANCE * vv) {
                return false;
            }
            // the same point twice means the simplex cannot improve either
            for (int i = 0; i < size; i++) {
                if (w[i][0] == tmpW[0] && w[i][1] == tmpW[1] && w[i][2] == tmpW[2]) {
                    return false;
                }
            }
            saveSimplex();
            System.arraycopy(tmpW, 0, w[size], 0, 3);
            System.arraycopy(tmpA, 0, wa[size], 0, 3);
            System.arraycopy(tmpB, 0, wb[size], 0, 3);
            size++;
            if (reduce()) {
                return true; // the origin is inside the tetrahedron
            }
            double after = v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
            if (after >= vv * (1 - 1e-15)) {
                if (after > vv) {
                    restoreSimplex(); // rounding made the new closest point worse: keep the previous one
                }
                return false; // the new point did not bring the closest point nearer: converged to the precision of the arithmetic
            }
        }
        return false;
    }

    private final double[] tmpW = new double[3];
    private final double[][] savedW = new double[4][3];
    private final double[][] savedA = new double[4][3];
    private final double[][] savedB = new double[4][3];
    private final double[] savedLambda = new double[4];
    private final double[] savedV = new double[3];
    private int savedSize;

    private void saveSimplex() {
        savedSize = size;
        for (int i = 0; i < size; i++) {
            System.arraycopy(w[i], 0, savedW[i], 0, 3);
            System.arraycopy(wa[i], 0, savedA[i], 0, 3);
            System.arraycopy(wb[i], 0, savedB[i], 0, 3);
            savedLambda[i] = lambda[i];
        }
        System.arraycopy(v, 0, savedV, 0, 3);
    }

    private void restoreSimplex() {
        size = savedSize;
        for (int i = 0; i < size; i++) {
            System.arraycopy(savedW[i], 0, w[i], 0, 3);
            System.arraycopy(savedA[i], 0, wa[i], 0, 3);
            System.arraycopy(savedB[i], 0, wb[i], 0, 3);
            lambda[i] = savedLambda[i];
        }
        System.arraycopy(savedV, 0, v, 0, 3);
    }

    // ---------------------------------------------------------------- closest point of the simplex to the origin

    /**
     * Replaces the simplex by the smallest face of it that contains the closest point to the
     * origin, and sets {@code v} and {@code lambda}.
     *
     * <p>Returns true when the simplex is a tetrahedron that contains the origin.
     */
    private boolean reduce() {
        switch (size) {
            case 2 -> {
                closestSegment(0, 1);
                keep(2);
                return false;
            }
            case 3 -> {
                closestTriangle(0, 1, 2);
                bestIdx[0] = 0;
                bestIdx[1] = 1;
                bestIdx[2] = 2;
                bestV[0] = cv[0];
                bestV[1] = cv[1];
                bestV[2] = cv[2];
                bestW[0] = cw[0];
                bestW[1] = cw[1];
                bestW[2] = cw[2];
                keep(3);
                return false;
            }
            default -> {
                return reduceTetrahedron();
            }
        }
    }

    /**
     * Sets the best result for a segment of simplex points {@code i, j}, into bestIdx / bestW /
     * bestV.
     */
    private void closestSegment(int i, int j) {
        double abx = w[j][0] - w[i][0], aby = w[j][1] - w[i][1], abz = w[j][2] - w[i][2];
        double ab2 = abx * abx + aby * aby + abz * abz;
        double t = ab2 > 0 ? -(w[i][0] * abx + w[i][1] * aby + w[i][2] * abz) / ab2 : 0;
        bestIdx[0] = i;
        bestIdx[1] = j;
        if (t <= 0) {
            bestW[0] = 1;
            bestW[1] = 0;
            t = 0;
        } else if (t >= 1) {
            bestW[0] = 0;
            bestW[1] = 1;
            t = 1;
        } else {
            bestW[0] = 1 - t;
            bestW[1] = t;
        }
        bestV[0] = w[i][0] + abx * t;
        bestV[1] = w[i][1] + aby * t;
        bestV[2] = w[i][2] + abz * t;
    }

    /**
     * Moves the first {@code count} entries of bestIdx / bestW into the front of the simplex
     * (dropping zero weights) and sets v and lambda.
     */
    private void keep(int count) {
        int m = 0;
        // copy into the front: indices are increasing, so a forward in-place copy is safe
        for (int k = 0; k < count; k++) {
            if (bestW[k] > 0) {
                int src = bestIdx[k];
                if (src != m) {
                    System.arraycopy(w[src], 0, w[m], 0, 3);
                    System.arraycopy(wa[src], 0, wa[m], 0, 3);
                    System.arraycopy(wb[src], 0, wb[m], 0, 3);
                }
                lambda[m] = bestW[k];
                m++;
            }
        }
        if (m == 0) { // numerically empty: keep the nearest point
            System.arraycopy(w[bestIdx[0]], 0, w[0], 0, 3);
            System.arraycopy(wa[bestIdx[0]], 0, wa[0], 0, 3);
            System.arraycopy(wb[bestIdx[0]], 0, wb[0], 0, 3);
            lambda[0] = 1;
            m = 1;
        }
        size = m;
        v[0] = bestV[0];
        v[1] = bestV[1];
        v[2] = bestV[2];
    }

    /**
     * Closest point of the triangle {@code i, j, k} of the simplex to the origin (Ericson,
     * "Real-Time Collision Detection" 5.1.5): weights in cw, point in cv.
     */
    private double closestTriangle(int i, int j, int k) {
        double ax = w[i][0], ay = w[i][1], az = w[i][2];
        double abx = w[j][0] - ax, aby = w[j][1] - ay, abz = w[j][2] - az;
        double acx = w[k][0] - ax, acy = w[k][1] - ay, acz = w[k][2] - az;
        // a triangle with (almost) no area has no interior: the closest point is on one of its three edges
        double nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
        double n2 = nx * nx + ny * ny + nz * nz;
        if (n2 <= 1e-24 * (abx * abx + aby * aby + abz * abz) * (acx * acx + acy * acy + acz * acz)) {
            return closestOfEdges(i, j, k);
        }
        double apx = -ax, apy = -ay, apz = -az;
        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0 && d2 <= 0) {
            return setTriangle(1, 0, 0, ax, ay, az);
        }
        double bpx = -w[j][0], bpy = -w[j][1], bpz = -w[j][2];
        double d3 = abx * bpx + aby * bpy + abz * bpz;
        double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0 && d4 <= d3) {
            return setTriangle(0, 1, 0, w[j][0], w[j][1], w[j][2]);
        }
        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0 && d1 >= 0 && d3 <= 0) {
            double t = d1 / (d1 - d3);
            return setTriangle(1 - t, t, 0, ax + abx * t, ay + aby * t, az + abz * t);
        }
        double cpx = -w[k][0], cpy = -w[k][1], cpz = -w[k][2];
        double d5 = abx * cpx + aby * cpy + abz * cpz;
        double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0 && d5 <= d6) {
            return setTriangle(0, 0, 1, w[k][0], w[k][1], w[k][2]);
        }
        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0 && d2 >= 0 && d6 <= 0) {
            double t = d2 / (d2 - d6);
            return setTriangle(1 - t, 0, t, ax + acx * t, ay + acy * t, az + acz * t);
        }
        double va = d3 * d6 - d5 * d4;
        if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
            double t = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return setTriangle(0, 1 - t, t, w[j][0] + (w[k][0] - w[j][0]) * t, w[j][1] + (w[k][1] - w[j][1]) * t, w[j][2] + (w[k][2] - w[j][2]) * t);
        }
        double denom = 1.0 / (va + vb + vc);
        double s = vb * denom, t = vc * denom;
        double interior = setTriangle(1 - s - t, s, t, ax + abx * s + acx * t, ay + aby * s + acy * t, az + abz * s + acz * t);
        // a long thin triangle makes the barycentric coordinates imprecise: the interior point must not be worse than the best point of the three edges
        double w0 = cw[0], w1 = cw[1], w2 = cw[2], px = cv[0], py = cv[1], pz = cv[2];
        double onEdges = closestOfEdges(i, j, k);
        if (onEdges < interior) {
            return onEdges;
        }
        return setTriangle(w0, w1, w2, px, py, pz);
    }

    /**
     * The closest point to the origin on the edges of the (flat) triangle {@code i, j, k}; the
     * weights go to cw, the point to cv.
     */
    private double closestOfEdges(int i, int j, int k) {
        double best = Double.POSITIVE_INFINITY;
        for (int e = 0; e < 3; e++) {
            int p = e == 2 ? j : i, q = e == 0 ? j : k;
            double dx = w[q][0] - w[p][0], dy = w[q][1] - w[p][1], dz = w[q][2] - w[p][2];
            double d2 = dx * dx + dy * dy + dz * dz;
            double u = d2 > 0 ? -(w[p][0] * dx + w[p][1] * dy + w[p][2] * dz) / d2 : 0;
            u = Math.max(0, Math.min(1, u));
            double px = w[p][0] + dx * u, py = w[p][1] + dy * u, pz = w[p][2] + dz * u;
            double dist = px * px + py * py + pz * pz;
            if (dist < best) {
                best = dist;
                cw[0] = cw[1] = cw[2] = 0;
                // the weights of the two vertices of the edge, in the order of the triangle's vertices
                if (e == 0) {
                    cw[0] = 1 - u;
                    cw[1] = u;
                } else if (e == 1) {
                    cw[0] = 1 - u;
                    cw[2] = u;
                } else {
                    cw[1] = 1 - u;
                    cw[2] = u;
                }
                cv[0] = px;
                cv[1] = py;
                cv[2] = pz;
            }
        }
        return best;
    }

    private double setTriangle(double w0, double w1, double w2, double px, double py, double pz) {
        cw[0] = w0;
        cw[1] = w1;
        cw[2] = w2;
        cv[0] = px;
        cv[1] = py;
        cv[2] = pz;
        return px * px + py * py + pz * pz;
    }

    /**
     * The tetrahedron case of {@link #reduce()}.
     */
    private boolean reduceTetrahedron() {
        // the origin is inside when its barycentric coordinates are all non-negative and reproduce it; plane-side tests are not used because they are unreliable for the
        // nearly flat tetrahedra that appear when the iteration is converging
        double total = det(w[1], w[2], w[3]) - det(w[0], w[2], w[3]) + det(w[0], w[1], w[3]) - det(w[0], w[1], w[2]);
        if (total != 0) {
            lambda[0] = det(w[1], w[2], w[3]) / total;
            lambda[1] = -det(w[0], w[2], w[3]) / total;
            lambda[2] = det(w[0], w[1], w[3]) / total;
            lambda[3] = -det(w[0], w[1], w[2]) / total;
            boolean inside = true;
            double rx = 0, ry = 0, rz = 0, scale = 0;
            for (int q = 0; q < 4; q++) {
                inside &= lambda[q] >= -1e-12;
                rx += lambda[q] * w[q][0];
                ry += lambda[q] * w[q][1];
                rz += lambda[q] * w[q][2];
                scale = Math.max(scale, Math.abs(w[q][0]) + Math.abs(w[q][1]) + Math.abs(w[q][2]));
            }
            if (inside && rx * rx + ry * ry + rz * rz <= 1e-18 * scale * scale) {
                v[0] = v[1] = v[2] = 0;
                return true;
            }
        }
        // outside: the closest point of the tetrahedron is the closest point of one of its four faces
        double best = Double.POSITIVE_INFINITY;
        for (int face = 0; face < 4; face++) {
            int i = face == 0 ? 1 : 0, j = face <= 1 ? 2 : 1, k = face <= 2 ? 3 : 2;
            best = considerFace(i, j, k, best);
        }
        keep(3);
        return false;
    }

    /**
     * Tries the face {@code (i, j, k)}: when its closest point to the origin is nearer than
     * {@code best}, records it and returns the new best squared distance.
     */
    private double considerFace(int i, int j, int k, double best) {
        double d2 = closestTriangle(i, j, k);
        if (d2 >= best) {
            return best;
        }
        bestIdx[0] = i;
        bestIdx[1] = j;
        bestIdx[2] = k;
        bestW[0] = cw[0];
        bestW[1] = cw[1];
        bestW[2] = cw[2];
        bestV[0] = cv[0];
        bestV[1] = cv[1];
        bestV[2] = cv[2];
        return d2;
    }

    private static double det(double[] a, double[] b, double[] c) {
        return a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0]);
    }

    // ---------------------------------------------------------------- EPA

    private void epa(ConvexShape a, ConvexShape b, Result r) {
        // the polytope starts as a tetrahedron around the origin: the GJK simplex, grown to four points when it ended on a face, an edge or a vertex
        vertices = 0;
        for (int i = 0; i < size; i++) {
            addVertexCopy(w[i], wa[i], wb[i]);
        }
        if (!growToTetrahedron(a, b)) {
            // a shape without volume: report touching along the last search direction
            witness(r);
            r.depth = 0;
            r.normal[0] = 0;
            r.normal[1] = 1;
            r.normal[2] = 0;
            return;
        }
        faces = 0;
        addFace(0, 1, 2, 3);
        addFace(0, 3, 1, 2);
        addFace(1, 3, 2, 0);
        addFace(2, 3, 0, 1);
        double scale = 1;
        for (int i = 0; i < 4; i++) {
            scale = Math.max(scale, Math.sqrt(ev[i][0] * ev[i][0] + ev[i][1] * ev[i][1] + ev[i][2] * ev[i][2]));
        }
        int closest = -1;
        for (int iter = 0; iter < MAX_EPA_ITERATIONS; iter++) {
            closest = -1;
            double dmin = Double.POSITIVE_INFINITY;
            for (int f = 0; f < faces; f++) {
                if (fAlive[f] && fd[f] < dmin) {
                    dmin = fd[f];
                    closest = f;
                }
            }
            if (closest < 0) {
                break;
            }
            double nx = fn[3 * closest], ny = fn[3 * closest + 1], nz = fn[3 * closest + 2];
            if (vertices >= MAX_VERTICES) {
                break;
            }
            int p = vertices;
            minkowski(a, b, nx, ny, nz, ev[p], eva[p], evb[p]);
            double gap = ev[p][0] * nx + ev[p][1] * ny + ev[p][2] * nz - dmin;
            if (gap <= EPA_TOLERANCE * (1 + scale)) {
                break;
            }
            vertices++;
            // remove the faces that can see the new point, and join the rim to it
            if (faces > MAX_FACES - 96) {
                compactFaces();
            }
            int edges = 0;
            for (int f = 0; f < faces; f++) {
                if (!fAlive[f]) {
                    continue;
                }
                int fa = fv[3 * f], fb = fv[3 * f + 1], fc = fv[3 * f + 2];
                double side = (ev[p][0] - ev[fa][0]) * fn[3 * f] + (ev[p][1] - ev[fa][1]) * fn[3 * f + 1] + (ev[p][2] - ev[fa][2]) * fn[3 * f + 2];
                if (side > 1e-12 * scale) {
                    fAlive[f] = false;
                    edges = addEdge(edges, fa, fb);
                    edges = addEdge(edges, fb, fc);
                    edges = addEdge(edges, fc, fa);
                }
            }
            if (faces + edges > MAX_FACES) {
                compactFaces();
            }
            int newFaces = 0;
            for (int e = 0; e < edges && faces < MAX_FACES; e++) {
                addFace(horizon[2 * e], horizon[2 * e + 1], p, -1);
                newFaces++;
            }
            if (newFaces == 0) {
                break;
            }
        }
        // the faces may have been renumbered or replaced since the loop chose one: find the nearest again
        closest = -1;
        double nearest = Double.POSITIVE_INFINITY;
        for (int f = 0; f < faces; f++) {
            if (fAlive[f] && fd[f] < nearest) {
                nearest = fd[f];
                closest = f;
            }
        }
        if (closest < 0) {
            // every face was removed (a polytope that numerically has no volume): report touching along the last search direction
            witness(r);
            r.depth = 0;
            r.normal[0] = 0;
            r.normal[1] = 1;
            r.normal[2] = 0;
            return;
        }
        // faces of one planar facet of the polytope tie for the smallest distance: take the one that contains the projection of the origin
        double tieLimit = fd[closest] + 1e-12 * (1 + scale);
        double bestMin = -Double.MAX_VALUE;
        int chosen = closest;
        for (int f = 0; f < faces; f++) {
            if (fAlive[f] && fd[f] <= tieLimit) {
                double m = minBarycentric(f);
                if (m > bestMin) {
                    bestMin = m;
                    chosen = f;
                }
            }
        }
        closest = chosen;
        // the contact: barycentric coordinates of the origin's projection on the nearest face give the witness points on both shapes
        int ia = fv[3 * closest], ib = fv[3 * closest + 1], ic = fv[3 * closest + 2];
        double depth = fd[closest];
        double nx = fn[3 * closest], ny = fn[3 * closest + 1], nz = fn[3 * closest + 2];
        double qx = nx * depth, qy = ny * depth, qz = nz * depth;
        double e0x = ev[ib][0] - ev[ia][0], e0y = ev[ib][1] - ev[ia][1], e0z = ev[ib][2] - ev[ia][2];
        double e1x = ev[ic][0] - ev[ia][0], e1y = ev[ic][1] - ev[ia][1], e1z = ev[ic][2] - ev[ia][2];
        double qpx = qx - ev[ia][0], qpy = qy - ev[ia][1], qpz = qz - ev[ia][2];
        double d00 = e0x * e0x + e0y * e0y + e0z * e0z, d01 = e0x * e1x + e0y * e1y + e0z * e1z, d11 = e1x * e1x + e1y * e1y + e1z * e1z;
        double d20 = qpx * e0x + qpy * e0y + qpz * e0z, d21 = qpx * e1x + qpy * e1y + qpz * e1z;
        double denom = d00 * d11 - d01 * d01;
        double bv = denom != 0 ? (d11 * d20 - d01 * d21) / denom : 0, bw = denom != 0 ? (d00 * d21 - d01 * d20) / denom : 0;
        double bu = 1 - bv - bw;
        bu = Math.max(0, bu);
        bv = Math.max(0, bv);
        bw = Math.max(0, bw);
        double sum = bu + bv + bw;
        bu /= sum;
        bv /= sum;
        bw /= sum;
        for (int k = 0; k < 3; k++) {
            r.pointA[k] = bu * eva[ia][k] + bv * eva[ib][k] + bw * eva[ic][k];
            r.pointB[k] = bu * evb[ia][k] + bv * evb[ib][k] + bw * evb[ic][k];
        }
        r.depth = depth;
        // the face normal points out of A - B; B moved along it frees the shapes
        r.normal[0] = nx;
        r.normal[1] = ny;
        r.normal[2] = nz;
        // A - B has its nearest boundary point at n * depth, so moving B by +n * depth moves that point to the origin
    }

    /**
     * The smallest barycentric coordinate of the projection of the origin on face {@code f}:
     * negative when the projection falls outside the triangle.
     */
    private double minBarycentric(int f) {
        int ia = fv[3 * f], ib = fv[3 * f + 1], ic = fv[3 * f + 2];
        double qx = fn[3 * f] * fd[f], qy = fn[3 * f + 1] * fd[f], qz = fn[3 * f + 2] * fd[f];
        double e0x = ev[ib][0] - ev[ia][0], e0y = ev[ib][1] - ev[ia][1], e0z = ev[ib][2] - ev[ia][2];
        double e1x = ev[ic][0] - ev[ia][0], e1y = ev[ic][1] - ev[ia][1], e1z = ev[ic][2] - ev[ia][2];
        double px = qx - ev[ia][0], py = qy - ev[ia][1], pz = qz - ev[ia][2];
        double d00 = e0x * e0x + e0y * e0y + e0z * e0z, d01 = e0x * e1x + e0y * e1y + e0z * e1z, d11 = e1x * e1x + e1y * e1y + e1z * e1z;
        double d20 = px * e0x + py * e0y + pz * e0z, d21 = px * e1x + py * e1y + pz * e1z;
        double denom = d00 * d11 - d01 * d01;
        if (denom == 0) {
            return -Double.MAX_VALUE;
        }
        double bv = (d11 * d20 - d01 * d21) / denom, bw = (d00 * d21 - d01 * d20) / denom;
        return Math.min(1 - bv - bw, Math.min(bv, bw));
    }

    /**
     * Moves the live faces to the front of the face arrays.
     */
    private void compactFaces() {
        int m = 0;
        for (int f = 0; f < faces; f++) {
            if (fAlive[f]) {
                if (f != m) {
                    System.arraycopy(fv, 3 * f, fv, 3 * m, 3);
                    System.arraycopy(fn, 3 * f, fn, 3 * m, 3);
                    fd[m] = fd[f];
                    fAlive[m] = true;
                }
                m++;
            }
        }
        for (int f = m; f < faces; f++) {
            fAlive[f] = false;
        }
        faces = m;
    }

    private void addVertexCopy(double[] pw, double[] pa, double[] pb) {
        System.arraycopy(pw, 0, ev[vertices], 0, 3);
        System.arraycopy(pa, 0, eva[vertices], 0, 3);
        System.arraycopy(pb, 0, evb[vertices], 0, 3);
        vertices++;
    }

    /**
     * Adds the face (a, b, c) with its outward normal; {@code inside} is a vertex known to be
     * inside (or -1: the origin is inside).
     */
    private void addFace(int a, int b, int c, int inside) {
        int f = faces++;
        double ux = ev[b][0] - ev[a][0], uy = ev[b][1] - ev[a][1], uz = ev[b][2] - ev[a][2];
        double vx = ev[c][0] - ev[a][0], vy = ev[c][1] - ev[a][1], vz = ev[c][2] - ev[a][2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        double d = nx * ev[a][0] + ny * ev[a][1] + nz * ev[a][2];
        // the first four faces are oriented away from the fourth vertex; later faces keep the winding of the rim edge they were made on, which is already outward
        boolean flip = inside >= 0 && nx * (ev[inside][0] - ev[a][0]) + ny * (ev[inside][1] - ev[a][1]) + nz * (ev[inside][2] - ev[a][2]) > 0;
        if (flip) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
            d = -d;
            int t = b;
            b = c;
            c = t;
        }
        fv[3 * f] = a;
        fv[3 * f + 1] = b;
        fv[3 * f + 2] = c;
        fn[3 * f] = nx;
        fn[3 * f + 1] = ny;
        fn[3 * f + 2] = nz;
        fd[f] = Math.max(d, 0);
        fAlive[f] = true;
    }

    /**
     * Adds the directed edge to the horizon list, cancelling it against its reverse.
     *
     * <p>Returns the new edge count.
     */
    private int addEdge(int count, int a, int b) {
        for (int e = 0; e < count; e++) {
            if (horizon[2 * e] == b && horizon[2 * e + 1] == a) {
                horizon[2 * e] = horizon[2 * (count - 1)];
                horizon[2 * e + 1] = horizon[2 * (count - 1) + 1];
                return count - 1;
            }
        }
        horizon[2 * count] = a;
        horizon[2 * count + 1] = b;
        return count + 1;
    }

    private static final double[][] PROBES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}, {1, 1, 1}, {-1, -1, 1}, {-1, 1, -1}, {1, -1, -1},
            {1, 1, -1}, {-1, 1, 1}, {1, -1, 1}, {-1, -1, -1}};

    private final double[] probeW = new double[3];
    private final double[] probeA = new double[3];
    private final double[] probeB = new double[3];

    /**
     * Adds Minkowski points until the vertices span space, at each step the probe direction whose
     * support point is farthest from what there is already (a nearly flat start would make the
     * faces of the polytope unreliable); false if no probe adds a dimension (the difference has no
     * volume).
     */
    private boolean growToTetrahedron(ConvexShape a, ConvexShape b) {
        while (vertices < 4) {
            int p = vertices;
            double best = 0;
            int bestProbe = -1;
            double scale = 1;
            for (int i = 0; i < p; i++) {
                scale = Math.max(scale, Math.abs(ev[i][0]) + Math.abs(ev[i][1]) + Math.abs(ev[i][2]));
            }
            for (int k = 0; k < PROBES.length; k++) {
                minkowski(a, b, PROBES[k][0], PROBES[k][1], PROBES[k][2], probeW, probeA, probeB);
                double m = independence(p, probeW);
                if (m > best) {
                    best = m;
                    bestProbe = k;
                }
            }
            if (bestProbe < 0 || best <= 1e-9 * scale) {
                return false;
            }
            minkowski(a, b, PROBES[bestProbe][0], PROBES[bestProbe][1], PROBES[bestProbe][2], ev[p], eva[p], evb[p]);
            vertices++;
        }
        return true;
    }

    /**
     * How far the point is from the point, line or plane spanned by the first {@code p} vertices (0
     * for p = 0 is treated as 1: any point is fine).
     */
    private double independence(int p, double[] q) {
        if (p == 0) {
            return 1;
        }
        double ux = q[0] - ev[0][0], uy = q[1] - ev[0][1], uz = q[2] - ev[0][2];
        if (p == 1) {
            return Math.sqrt(ux * ux + uy * uy + uz * uz);
        }
        double ax = ev[1][0] - ev[0][0], ay = ev[1][1] - ev[0][1], az = ev[1][2] - ev[0][2];
        double cx = ay * uz - az * uy, cy = az * ux - ax * uz, cz = ax * uy - ay * ux;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        if (p == 2) {
            return al > 0 ? Math.sqrt(cx * cx + cy * cy + cz * cz) / al : 0;
        }
        double bx = ev[2][0] - ev[0][0], by = ev[2][1] - ev[0][1], bz = ev[2][2] - ev[0][2];
        double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return nl > 0 ? Math.abs(nx * ux + ny * uy + nz * uz) / nl : 0;
    }
}
