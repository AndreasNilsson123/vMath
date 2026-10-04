package vmath.geo;

/**
 * The polytope that the expanding polytope algorithm of {@link Gjk} grows inside the Minkowski
 * difference of two shapes: its vertices (with the points of both shapes they came from) and its
 * triangular faces (with outward unit normals and distances from the origin).
 *
 * <p>The faces live in flat arrays, three vertex indices or three normal components per face, and
 * a removed face stays in the arrays, marked dead, until {@link #compactFaces()} moves the live
 * ones to the front. All the memory is allocated once, so that a query allocates nothing.
 *
 * <p>Internal: the fields are package-private on purpose, the algorithm reads them in its inner
 * loops.
 *
 * <p><b>Thread safety.</b> Not thread-safe: the state of one query, owned by one {@link Gjk}.
 */
final class EpaPolytope {

    /** The vertices of the polytope: points of the Minkowski difference. */
    final double[][] ev;
    /** For every vertex the point of the first shape that it came from. */
    final double[][] eva;
    /** For every vertex the point of the second shape that it came from. */
    final double[][] evb;
    /** Three vertex indices per face. */
    final int[] fv;
    /** Three components of the outward unit normal per face. */
    final double[] fn;
    /** The distance of the plane of every face from the origin, never negative. */
    final double[] fd;
    /** Whether a face is still part of the polytope. */
    final boolean[] fAlive;
    /** The edges of the horizon seen from a new vertex, as pairs of vertex indices. */
    final int[] horizon;
    /** The number of vertices. */
    int vertices;
    /** The number of faces, dead ones included. */
    int faces;

    private final double[] bary = new double[3];

    /**
     * Allocates room for a polytope.
     *
     * @param maxVertices the most vertices the polytope can have
     * @param maxFaces the most faces, dead ones included, that the arrays can hold between two
     *     compactions
     */
    EpaPolytope(int maxVertices, int maxFaces) {
        ev = new double[maxVertices][3];
        eva = new double[maxVertices][3];
        evb = new double[maxVertices][3];
        fv = new int[3 * maxFaces];
        fn = new double[3 * maxFaces];
        fd = new double[maxFaces];
        fAlive = new boolean[maxFaces];
        horizon = new int[2 * 3 * maxFaces];
    }

    /**
     * Empties the polytope.
     */
    void clear() {
        vertices = 0;
        faces = 0;
    }

    /**
     * Appends a vertex.
     *
     * @param pw the point of the Minkowski difference
     * @param pa the point of the first shape
     * @param pb the point of the second shape
     */
    void addVertexCopy(double[] pw, double[] pa, double[] pb) {
        System.arraycopy(pw, 0, ev[vertices], 0, 3);
        System.arraycopy(pa, 0, eva[vertices], 0, 3);
        System.arraycopy(pb, 0, evb[vertices], 0, 3);
        vertices++;
    }

    /**
     * Adds the face (a, b, c) with its outward normal.
     *
     * <p>{@code inside} is a vertex known to be inside the polytope, or -1 when the origin is
     * (the first four faces are oriented away from the fourth vertex; later faces keep the
     * winding of the rim edge they were made on, which is already outward).
     *
     * @param a the first vertex of the face
     * @param b the second vertex of the face
     * @param c the third vertex of the face
     * @param inside a vertex inside the polytope, or -1
     */
    void addFace(int a, int b, int c, int inside) {
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
     * @param count the number of edges in the list
     * @param a the first vertex of the edge
     * @param b the second vertex of the edge
     * @return the new edge count
     */
    int addEdge(int count, int a, int b) {
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

    /**
     * Moves the live faces to the front of the face arrays.
     */
    void compactFaces() {
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

    /**
     * Finds the live face nearest to the origin.
     *
     * @return the index of the face, or -1 if no face is alive
     */
    int nearestFace() {
        int closest = -1;
        double nearest = Double.POSITIVE_INFINITY;
        for (int f = 0; f < faces; f++) {
            if (fAlive[f] && fd[f] < nearest) {
                nearest = fd[f];
                closest = f;
            }
        }
        return closest;
    }

    /**
     * Computes the barycentric coordinates, in the triangle of face {@code f}, of the point of the
     * face that is nearest to the origin (the origin projected onto the plane).
     *
     * @param f the face
     * @param out receives the three coordinates, which sum to 1 and are negative where the
     *     projection falls outside the triangle; {@code (1, 0, 0)} for a triangle without area
     * @return {@code true} if the triangle has an area
     */
    boolean barycentric(int f, double[] out) {
        int ia = fv[3 * f], ib = fv[3 * f + 1], ic = fv[3 * f + 2];
        double qx = fn[3 * f] * fd[f], qy = fn[3 * f + 1] * fd[f], qz = fn[3 * f + 2] * fd[f];
        double e0x = ev[ib][0] - ev[ia][0], e0y = ev[ib][1] - ev[ia][1], e0z = ev[ib][2] - ev[ia][2];
        double e1x = ev[ic][0] - ev[ia][0], e1y = ev[ic][1] - ev[ia][1], e1z = ev[ic][2] - ev[ia][2];
        double px = qx - ev[ia][0], py = qy - ev[ia][1], pz = qz - ev[ia][2];
        double d00 = e0x * e0x + e0y * e0y + e0z * e0z, d01 = e0x * e1x + e0y * e1y + e0z * e1z, d11 = e1x * e1x + e1y * e1y + e1z * e1z;
        double d20 = px * e0x + py * e0y + pz * e0z, d21 = px * e1x + py * e1y + pz * e1z;
        double denom = d00 * d11 - d01 * d01;
        if (denom == 0) {
            out[0] = 1;
            out[1] = 0;
            out[2] = 0;
            return false;
        }
        double bv = (d11 * d20 - d01 * d21) / denom, bw = (d00 * d21 - d01 * d20) / denom;
        out[0] = 1 - bv - bw;
        out[1] = bv;
        out[2] = bw;
        return true;
    }

    /**
     * The smallest barycentric coordinate of the projection of the origin on face {@code f}:
     * negative when the projection falls outside the triangle.
     *
     * @param f the face
     * @return the smallest of the three coordinates, or the most negative number for a triangle
     *     without area
     */
    double minBarycentric(int f) {
        if (!barycentric(f, bary)) {
            return -Double.MAX_VALUE;
        }
        return Math.min(bary[0], Math.min(bary[1], bary[2]));
    }

    /**
     * How far the point is from the point, line or plane spanned by the first {@code p} vertices (0
     * for p = 0 is treated as 1: any point is fine).
     *
     * @param p the number of vertices to measure against, 0 to 3
     * @param q the point
     * @return the distance of {@code q} from what the first {@code p} vertices span
     */
    double independence(int p, double[] q) {
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
