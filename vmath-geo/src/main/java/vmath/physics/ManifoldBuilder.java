package vmath.physics;

import vmath.geo.ConvexPolytope;
import vmath.geo.ConvexShape;
import vmath.geo.Gjk;

/**
 * Builds the {@link ContactManifold} of two convex bodies.
 *
 * <p>{@link #polytopes} is the complete method for two {@link ConvexPolytope}s (boxes are
 * polytopes: {@code ConvexPolytope.of(Aabbf)}): the <b>separating axis test</b> over the facet
 * normals of both and the cross products of their edges finds the axis of least penetration; when
 * it is a facet normal, the facet of one body and the most opposed facet of the other are the
 * <em>reference</em> and the <em>incident</em> facet, the incident polygon is <b>clipped</b>
 * against the side planes of the reference facet (the method of Sutherland and Hodgman, as in the
 * "robust contact creation" of Gregorius, GDC 2015), and the points that lie below the reference
 * plane are the contacts, up to four after a reduction that keeps the deepest point and the ones
 * that spread the contact most; when it is a pair of edges, the contact is the closest pair of
 * points of the two edges. A facet normal is preferred to an edge pair of almost equal separation,
 * so that a contact does not flip between the two from frame to frame.
 *
 * <p>The optional {@code margin} makes the builder <b>speculative</b>: shapes that are apart by
 * less than the margin along the best axis still get contacts, with a negative depth, so that a
 * solver can stop a fast body before it penetrates (the solver lets the body approach by that
 * distance and no more).
 *
 * <p>{@link #boxes} is the same for two {@link OrientedBox}es, written for boxes: the fifteen axes of
 * the separating axis test come from the three axes of each box and the nine dot products between
 * them (no loop over vertices), and the incident face is clipped in the plane of the reference face
 * against its rectangle. It gives the contacts of {@code polytopes} on the same boxes (tested on
 * random poses against it, which is the oracle) and needs no polytope, so a world of boxes
 * allocates nothing per step; PERF-2 measured it against {@code polytopes} (see {@code docs/PHYSICS.md}).
 *
 * <p>{@link #shapes} is the general fallback for any two {@link ConvexShape}s (spheres, capsules,
 * rounded boxes, any support function): one contact point from the penetration query of
 * {@link Gjk}.
 *
 * <p>The cost of {@code polytopes} is O(F (V_a + V_b) + E_a E_b (V_a + V_b)) in the facets, edges
 * and vertices: made for the boxes and small polytopes of the usual collision shapes, not for hulls
 * of hundreds of vertices. A builder owns its scratch memory, so after construction a call
 * allocates nothing; use one per thread.
 *
 * <p><b>Thread safety.</b> Not thread-safe.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * ManifoldBuilder builder = new ManifoldBuilder();                         // owns scratch space: reuse it
 * ConvexPolytope a = ConvexPolytope.of(Aabbf.of(new Vec3f(-1f, -1f, -1f), new Vec3f(1f, 1f, 1f)));
 * ConvexPolytope b = a.transformed(Quatf.rotationY(0.3f), new Vec3f(0f, 1.9f, 0f));
 * ContactManifold manifold = new ContactManifold();
 * if (builder.polytopes(a, b, 0.02, manifold)) {
 *     int points = manifold.count();                                       // at most four contact points
 * }
 * }</pre>
 */
public final class ManifoldBuilder {

    private static final int CAPACITY = 256;

    private final Gjk gjk = new Gjk();
    private final Gjk.Result gjkResult = new Gjk.Result();
    private double[] polyIn = new double[3 * CAPACITY], polyOut = new double[3 * CAPACITY];
    private int[] idIn = new int[CAPACITY], idOut = new int[CAPACITY];
    // candidates before the reduction to four points
    private final double[] candA = new double[3 * CAPACITY], candB = new double[3 * CAPACITY], candDepth = new double[CAPACITY];
    private final int[] candId = new int[CAPACITY];
    private final double[] plane = new double[4], incidentPlane = new double[4];
    private final double[] axis = new double[3];
    private final double[] closest = new double[6];
    // the box path: the dot products between the axes, their absolute values, the centre distance in each frame, the half extents
    private final double[] boxR = new double[9], boxAbs = new double[9], boxDa = new double[3], boxDb = new double[3];
    private final double[] boxHa = new double[3], boxHb = new double[3], boxCorner = new double[12];
    private double[] uvIn = new double[2 * 16], uvOut = new double[2 * 16];

    /**
     * Creates a builder with its scratch memory.
     */
    public ManifoldBuilder() {
    }

    /**
     * Fills {@code out} with the contacts of the two polytopes in the poses they are in (their
     * vertices are in world space: use {@link ConvexPolytope#transformed} for a moved body).
     *
     * <p>Returns false, with {@code out} empty, when they are apart by more than {@code margin}
     * along some separating axis (a margin of 0 asks for contacts only when they overlap or touch).
     *
     * @param a the first convex polytope; must not be {@code null}
     * @param b the second convex polytope; must not be {@code null}
     * @param margin the margin
     * @param out receives the result; must not be {@code null}
     * @return {@code true} if the polytopes touch or overlap within the margin, in which case
     *     {@code out} holds the contacts; {@code false} if they are further apart
     * @throws IllegalArgumentException if {@code margin} is negative
     */
    public boolean polytopes(ConvexPolytope a, ConvexPolytope b, double margin, ContactManifold out) {
        out.clear();
        if (!(margin >= 0)) {
            throw new IllegalArgumentException("the margin must not be negative: " + margin);
        }
        centroidsAndScale(a, b);
        if (!faceAxes(a, b, margin) || !edgeAxes(a, b, margin)) {
            return false; // a separating axis
        }
        if (edgeA >= 0 && bestEdge > bestFace + 1e-4 * (1 + extent)) {
            edgeContact(a, b, edgeA, edgeB, edgeNx, edgeNy, edgeNz, bestEdge, out);
            return out.count() > 0;
        }
        faceContact(a, b, faceOwner, faceIndex, margin, out);
        return out.count() > 0;
    }

    // the centre of each polytope (the mean of its vertices, which orients the edge axes from A to B) and the largest coordinate of any vertex (the scale of the tolerances)
    private double centreAx, centreAy, centreAz, centreBx, centreBy, centreBz, extent;

    private void centroidsAndScale(ConvexPolytope a, ConvexPolytope b) {
        int na = a.vertexCount(), nb = b.vertexCount();
        double cax = 0, cay = 0, caz = 0, cbx = 0, cby = 0, cbz = 0, scale = 0;
        for (int i = 0; i < na; i++) {
            cax += a.vertex(i, 0);
            cay += a.vertex(i, 1);
            caz += a.vertex(i, 2);
            scale = Math.max(scale, Math.max(Math.abs(a.vertex(i, 0)), Math.max(Math.abs(a.vertex(i, 1)), Math.abs(a.vertex(i, 2)))));
        }
        for (int i = 0; i < nb; i++) {
            cbx += b.vertex(i, 0);
            cby += b.vertex(i, 1);
            cbz += b.vertex(i, 2);
            scale = Math.max(scale, Math.max(Math.abs(b.vertex(i, 0)), Math.max(Math.abs(b.vertex(i, 1)), Math.abs(b.vertex(i, 2)))));
        }
        centreAx = cax / na;
        centreAy = cay / na;
        centreAz = caz / na;
        centreBx = cbx / nb;
        centreBy = cby / nb;
        centreBz = cbz / nb;
        extent = scale;
    }

    // the result of the facet axes: the facet of least penetration (the largest separation, so the most negative one is the deepest), and who owns it
    private double bestFace;
    private int faceOwner, faceIndex;

    /**
     * Tests the facet normals of both polytopes as axes and remembers the best one.
     *
     * @return {@code false} if one of them separates the polytopes by more than {@code margin}
     */
    private boolean faceAxes(ConvexPolytope a, ConvexPolytope b, double margin) {
        bestFace = Double.NEGATIVE_INFINITY;
        faceOwner = -1;
        faceIndex = -1;
        for (int owner = 0; owner < 2; owner++) {
            ConvexPolytope p = owner == 0 ? a : b, q = owner == 0 ? b : a;
            for (int f = 0; f < p.facetCount(); f++) {
                p.facetPlane(f, plane);
                double min = Double.POSITIVE_INFINITY;
                for (int i = 0; i < q.vertexCount(); i++) {
                    min = Math.min(min, plane[0] * q.vertex(i, 0) + plane[1] * q.vertex(i, 1) + plane[2] * q.vertex(i, 2));
                }
                double sep = min - plane[3];
                if (sep > margin) {
                    return false;
                }
                if (sep > bestFace) {
                    bestFace = sep;
                    faceOwner = owner;
                    faceIndex = f;
                }
            }
        }
        return true;
    }

    // the result of the edge axes: the pair of edges of least penetration and the axis (from A to B)
    private double bestEdge, edgeNx, edgeNy, edgeNz;
    private int edgeA, edgeB;

    /**
     * Tests the cross products of every pair of edges as axes and remembers the best one.
     *
     * @return {@code false} if one of them separates the polytopes by more than {@code margin}
     */
    private boolean edgeAxes(ConvexPolytope a, ConvexPolytope b, double margin) {
        int na = a.vertexCount(), nb = b.vertexCount();
        bestEdge = Double.NEGATIVE_INFINITY;
        edgeA = -1;
        edgeB = -1;
        for (int i = 0; i < a.edgeCount(); i++) {
            double ax = a.vertex(a.edgeEnd(i), 0) - a.vertex(a.edgeStart(i), 0), ay = a.vertex(a.edgeEnd(i), 1) - a.vertex(a.edgeStart(i), 1), az = a.vertex(a.edgeEnd(i), 2) - a.vertex(a.edgeStart(i), 2);
            double la = Math.sqrt(ax * ax + ay * ay + az * az);
            for (int j = 0; j < b.edgeCount(); j++) {
                double bx = b.vertex(b.edgeEnd(j), 0) - b.vertex(b.edgeStart(j), 0), by = b.vertex(b.edgeEnd(j), 1) - b.vertex(b.edgeStart(j), 1), bz = b.vertex(b.edgeEnd(j), 2) - b.vertex(b.edgeStart(j), 2);
                double lb = Math.sqrt(bx * bx + by * by + bz * bz);
                double x = ay * bz - az * by, y = az * bx - ax * bz, z = ax * by - ay * bx;
                double l = Math.sqrt(x * x + y * y + z * z);
                if (l < 1e-6 * la * lb) {
                    continue; // parallel edges: the facet normals cover them
                }
                x /= l;
                y /= l;
                z /= l;
                if (x * (centreBx - centreAx) + y * (centreBy - centreAy) + z * (centreBz - centreAz) < 0) {
                    x = -x;
                    y = -y;
                    z = -z;
                }
                double maxA = Double.NEGATIVE_INFINITY, minB = Double.POSITIVE_INFINITY;
                for (int k = 0; k < na; k++) {
                    maxA = Math.max(maxA, x * a.vertex(k, 0) + y * a.vertex(k, 1) + z * a.vertex(k, 2));
                }
                for (int k = 0; k < nb; k++) {
                    minB = Math.min(minB, x * b.vertex(k, 0) + y * b.vertex(k, 1) + z * b.vertex(k, 2));
                }
                double sep = minB - maxA;
                if (sep > margin) {
                    return false;
                }
                if (sep > bestEdge) {
                    bestEdge = sep;
                    edgeA = i;
                    edgeB = j;
                    edgeNx = x;
                    edgeNy = y;
                    edgeNz = z;
                }
            }
        }
        return true;
    }

    private void edgeContact(ConvexPolytope a, ConvexPolytope b, int ea, int eb, double nx, double ny, double nz, double separation, ContactManifold out) {
        // the pair found fixes the axis and the directions of the edges; the edges that actually touch are the parallel ones that reach furthest along the axis (a box has four
        // parallel edges, and only one of them is the ridge that the other body rests on)
        int sa = supportEdge(a, ea, nx, ny, nz, true), sb = supportEdge(b, eb, nx, ny, nz, false);
        double[] c = closest;
        segmentSegment(a.vertex(a.edgeStart(sa), 0), a.vertex(a.edgeStart(sa), 1), a.vertex(a.edgeStart(sa), 2), a.vertex(a.edgeEnd(sa), 0), a.vertex(a.edgeEnd(sa), 1), a.vertex(a.edgeEnd(sa), 2),
                b.vertex(b.edgeStart(sb), 0), b.vertex(b.edgeStart(sb), 1), b.vertex(b.edgeStart(sb), 2), b.vertex(b.edgeEnd(sb), 0), b.vertex(b.edgeEnd(sb), 1), b.vertex(b.edgeEnd(sb), 2), c);
        out.setNormal(nx, ny, nz);
        // for the pair of the separating axis the two closest points differ by exactly the separation along it: they are the points on the two surfaces
        out.add(c[0], c[1], c[2], c[3], c[4], c[5], -separation, 0x5000 + sa * 131 + sb);
    }

    /**
     * Of the edges of {@code p} parallel to edge {@code e}, the one that reaches furthest along
     * {@code (nx, ny, nz)} (when {@code max}) or least far (when not): the larger of the smaller
     * end values, or the smaller of the larger ones.
     */
    private static int supportEdge(ConvexPolytope p, int e, double nx, double ny, double nz, boolean max) {
        double dx = p.vertex(p.edgeEnd(e), 0) - p.vertex(p.edgeStart(e), 0), dy = p.vertex(p.edgeEnd(e), 1) - p.vertex(p.edgeStart(e), 1), dz = p.vertex(p.edgeEnd(e), 2) - p.vertex(p.edgeStart(e), 2);
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int best = e;
        double bestValue = Double.NaN;
        for (int i = 0; i < p.edgeCount(); i++) {
            double x = p.vertex(p.edgeEnd(i), 0) - p.vertex(p.edgeStart(i), 0), y = p.vertex(p.edgeEnd(i), 1) - p.vertex(p.edgeStart(i), 1), z = p.vertex(p.edgeEnd(i), 2) - p.vertex(p.edgeStart(i), 2);
            double l = Math.sqrt(x * x + y * y + z * z);
            double cx = y * dz - z * dy, cy = z * dx - x * dz, cz = x * dy - y * dx;
            if (Math.sqrt(cx * cx + cy * cy + cz * cz) > 1e-4 * l * dl) {
                continue; // not parallel
            }
            double v0 = nx * p.vertex(p.edgeStart(i), 0) + ny * p.vertex(p.edgeStart(i), 1) + nz * p.vertex(p.edgeStart(i), 2);
            double v1 = nx * p.vertex(p.edgeEnd(i), 0) + ny * p.vertex(p.edgeEnd(i), 1) + nz * p.vertex(p.edgeEnd(i), 2);
            double value = max ? Math.min(v0, v1) : Math.max(v0, v1);
            if (Double.isNaN(bestValue) || (max ? value > bestValue : value < bestValue)) {
                bestValue = value;
                best = i;
            }
        }
        return best;
    }

    private void faceContact(ConvexPolytope a, ConvexPolytope b, int owner, int facet, double margin, ContactManifold out) {
        ConvexPolytope ref = owner == 0 ? a : b, inc = owner == 0 ? b : a;
        ref.facetPlane(facet, plane);
        double rnx = plane[0], rny = plane[1], rnz = plane[2], rd = plane[3];
        int incFacet = incidentFacet(inc, rnx, rny, rnz);
        int n = clipIncident(ref, facet, inc, incFacet, rnx, rny, rnz);
        int count = candidatesBelow(owner, n, rnx, rny, rnz, rd, margin);
        if (count == 0) {
            count = deepestVertex(owner, inc, rnx, rny, rnz, rd, margin);
        }
        if (owner == 0) {
            out.setNormal(rnx, rny, rnz);
        } else {
            out.setNormal(-rnx, -rny, -rnz);
        }
        reduce(count, out);
    }

    /** The facet of {@code inc} that is the most anti-parallel to the reference normal. */
    private int incidentFacet(ConvexPolytope inc, double rnx, double rny, double rnz) {
        int incFacet = 0;
        double bestDot = Double.POSITIVE_INFINITY;
        for (int f = 0; f < inc.facetCount(); f++) {
            inc.facetPlane(f, incidentPlane);
            double dot = rnx * incidentPlane[0] + rny * incidentPlane[1] + rnz * incidentPlane[2];
            if (dot < bestDot) {
                bestDot = dot;
                incFacet = f;
            }
        }
        return incFacet;
    }

    /**
     * Clips the incident facet against the side planes of the reference facet (inside is the left of each edge seen from outside, n x edge).
     *
     * @return the number of vertices of the clipped polygon, which is in {@code polyIn} with the ids in {@code idIn}
     */
    private int clipIncident(ConvexPolytope ref, int facet, ConvexPolytope inc, int incFacet, double rnx, double rny, double rnz) {
        int n = Math.min(inc.facetVertexCount(incFacet), CAPACITY / 2);
        for (int k = 0; k < n; k++) {
            int v = inc.facetVertex(incFacet, k);
            polyIn[3 * k] = inc.vertex(v, 0);
            polyIn[3 * k + 1] = inc.vertex(v, 1);
            polyIn[3 * k + 2] = inc.vertex(v, 2);
            idIn[k] = (facet << 16) ^ (incFacet << 8) ^ (k + 1);
        }
        int rn = ref.facetVertexCount(facet);
        for (int k = 0; k < rn && n > 0; k++) {
            int v0 = ref.facetVertex(facet, k), v1 = ref.facetVertex(facet, (k + 1) % rn);
            double p0x = ref.vertex(v0, 0), p0y = ref.vertex(v0, 1), p0z = ref.vertex(v0, 2);
            double ex = ref.vertex(v1, 0) - p0x, ey = ref.vertex(v1, 1) - p0y, ez = ref.vertex(v1, 2) - p0z;
            double mx = rny * ez - rnz * ey, my = rnz * ex - rnx * ez, mz = rnx * ey - rny * ex;
            double ml = Math.sqrt(mx * mx + my * my + mz * mz);
            if (!(ml > 0)) {
                continue;
            }
            n = clipAgainst(n, k, mx / ml, my / ml, mz / ml, p0x, p0y, p0z);
        }
        return n;
    }

    /** One step of Sutherland-Hodgman: keeps the part of {@code polyIn[0, n)} on the inside of the plane through p0 with the normal m, and swaps the buffers. */
    private int clipAgainst(int n, int side, double mx, double my, double mz, double p0x, double p0y, double p0z) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double di = mx * (polyIn[3 * i] - p0x) + my * (polyIn[3 * i + 1] - p0y) + mz * (polyIn[3 * i + 2] - p0z);
            double dj = mx * (polyIn[3 * j] - p0x) + my * (polyIn[3 * j + 1] - p0y) + mz * (polyIn[3 * j + 2] - p0z);
            if (di >= 0 && m < CAPACITY - 1) {
                polyOut[3 * m] = polyIn[3 * i];
                polyOut[3 * m + 1] = polyIn[3 * i + 1];
                polyOut[3 * m + 2] = polyIn[3 * i + 2];
                idOut[m++] = idIn[i];
            }
            if ((di >= 0) != (dj >= 0) && m < CAPACITY - 1) {
                double t = di / (di - dj);
                polyOut[3 * m] = polyIn[3 * i] + t * (polyIn[3 * j] - polyIn[3 * i]);
                polyOut[3 * m + 1] = polyIn[3 * i + 1] + t * (polyIn[3 * j + 1] - polyIn[3 * i + 1]);
                polyOut[3 * m + 2] = polyIn[3 * i + 2] + t * (polyIn[3 * j + 2] - polyIn[3 * i + 2]);
                idOut[m++] = 0x4000 + side * 64 + (idIn[i] & 0x3F);
            }
        }
        double[] td = polyIn;
        polyIn = polyOut;
        polyOut = td;
        int[] ti = idIn;
        idIn = idOut;
        idOut = ti;
        return m;
    }

    /**
     * The points of the clipped polygon below (or within the margin of) the reference plane are the contacts.
     *
     * @return the number of candidates written
     */
    private int candidatesBelow(int owner, int n, double rnx, double rny, double rnz, double rd, double margin) {
        int count = 0;
        double deepest = Double.POSITIVE_INFINITY;
        int deepestIndex = -1;
        for (int i = 0; i < n; i++) {
            double sep = rnx * polyIn[3 * i] + rny * polyIn[3 * i + 1] + rnz * polyIn[3 * i + 2] - rd;
            if (sep < deepest) {
                deepest = sep;
                deepestIndex = i;
            }
            if (sep <= margin) {
                addCandidate(count++, owner, polyIn[3 * i], polyIn[3 * i + 1], polyIn[3 * i + 2], sep, rnx, rny, rnz, idIn[i]);
            }
        }
        if (count == 0 && deepestIndex >= 0 && deepest <= margin) {
            addCandidate(count++, owner, polyIn[3 * deepestIndex], polyIn[3 * deepestIndex + 1], polyIn[3 * deepestIndex + 2], deepest, rnx, rny, rnz, idIn[deepestIndex]);
        }
        return count;
    }

    /**
     * Nothing is left of the incident facet: with deep penetration of a general hull it can lie outside the side planes of the reference facet. The axis is still
     * the one of least penetration, so the vertex of the incident body that reaches deepest below the reference plane is a valid single contact.
     *
     * @return 1 if there is such a vertex within the margin, else 0
     */
    private int deepestVertex(int owner, ConvexPolytope inc, double rnx, double rny, double rnz, double rd, double margin) {
        int bestVertex = -1;
        double bestSep = Double.POSITIVE_INFINITY;
        for (int v = 0; v < inc.vertexCount(); v++) {
            double sep = rnx * inc.vertex(v, 0) + rny * inc.vertex(v, 1) + rnz * inc.vertex(v, 2) - rd;
            if (sep < bestSep) {
                bestSep = sep;
                bestVertex = v;
            }
        }
        if (bestVertex >= 0 && bestSep <= margin) {
            addCandidate(0, owner, inc.vertex(bestVertex, 0), inc.vertex(bestVertex, 1), inc.vertex(bestVertex, 2), bestSep, rnx, rny, rnz, 0x7000 + bestVertex);
            return 1;
        }
        return 0;
    }

    /**
     * Stores the contact for the clipped point {@code (x, y, z)}, which is on the incident body at
     * the signed distance {@code sep} above the reference plane.
     */
    private void addCandidate(int i, int owner, double x, double y, double z, double sep, double rnx, double rny, double rnz, int id) {
        double ox = x - sep * rnx, oy = y - sep * rny, oz = z - sep * rnz; // the point projected onto the reference plane
        if (owner == 0) { // the reference is body A: its point is the projection, B's is the incident point
            candA[3 * i] = ox;
            candA[3 * i + 1] = oy;
            candA[3 * i + 2] = oz;
            candB[3 * i] = x;
            candB[3 * i + 1] = y;
            candB[3 * i + 2] = z;
        } else {
            candB[3 * i] = ox;
            candB[3 * i + 1] = oy;
            candB[3 * i + 2] = oz;
            candA[3 * i] = x;
            candA[3 * i + 1] = y;
            candA[3 * i + 2] = z;
        }
        candDepth[i] = -sep;
        candId[i] = id;
    }

    /**
     * Keeps at most four of the {@code count} candidates: the deepest, the one farthest from it,
     * and the two that make the largest triangles on either side of that line.
     */
    private void reduce(int count, ContactManifold out) {
        if (count <= ContactManifold.MAX_POINTS) {
            for (int i = 0; i < count; i++) {
                out.add(candA[3 * i], candA[3 * i + 1], candA[3 * i + 2], candB[3 * i], candB[3 * i + 1], candB[3 * i + 2], candDepth[i], candId[i]);
            }
            return;
        }
        int i0 = 0;
        for (int i = 1; i < count; i++) {
            if (candDepth[i] > candDepth[i0]) {
                i0 = i;
            }
        }
        int i1 = -1;
        double far = -1;
        for (int i = 0; i < count; i++) {
            double dx = candB[3 * i] - candB[3 * i0], dy = candB[3 * i + 1] - candB[3 * i0 + 1], dz = candB[3 * i + 2] - candB[3 * i0 + 2];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (i != i0 && d2 > far) {
                far = d2;
                i1 = i;
            }
        }
        int i2 = -1, i3 = -1;
        double maxArea = 0, minArea = 0;
        double ex = candB[3 * i1] - candB[3 * i0], ey = candB[3 * i1 + 1] - candB[3 * i0 + 1], ez = candB[3 * i1 + 2] - candB[3 * i0 + 2];
        for (int i = 0; i < count; i++) {
            if (i == i0 || i == i1) {
                continue;
            }
            double dx = candB[3 * i] - candB[3 * i0], dy = candB[3 * i + 1] - candB[3 * i0 + 1], dz = candB[3 * i + 2] - candB[3 * i0 + 2];
            // twice the signed area on the side of the normal
            double cx = ey * dz - ez * dy, cy = ez * dx - ex * dz, cz = ex * dy - ey * dx;
            double area = cx * out.nx + cy * out.ny + cz * out.nz;
            if (area > maxArea) {
                maxArea = area;
                i2 = i;
            }
            if (area < minArea) {
                minArea = area;
                i3 = i;
            }
        }
        for (int k = 0; k < 4; k++) {
            int i = k == 0 ? i0 : k == 1 ? i1 : k == 2 ? i2 : i3;
            if (i >= 0) {
                out.add(candA[3 * i], candA[3 * i + 1], candA[3 * i + 2], candB[3 * i], candB[3 * i + 1], candB[3 * i + 2], candDepth[i], candId[i]);
            }
        }
    }

    /**
     * The closest points of the segments {@code p1 q1} and {@code p2 q2}: on the first into
     * {@code out[0 .. 3)}, on the second into {@code out[3 .. 6)} (Ericson, "Real-Time Collision
     * Detection", 5.1.9).
     */
    static void segmentSegment(double p1x, double p1y, double p1z, double q1x, double q1y, double q1z, double p2x, double p2y, double p2z, double q2x, double q2y, double q2z, double[] out) {
        double d1x = q1x - p1x, d1y = q1y - p1y, d1z = q1z - p1z, d2x = q2x - p2x, d2y = q2y - p2y, d2z = q2z - p2z;
        double rx = p1x - p2x, ry = p1y - p2y, rz = p1z - p2z;
        double a = d1x * d1x + d1y * d1y + d1z * d1z, e = d2x * d2x + d2y * d2y + d2z * d2z, f = d2x * rx + d2y * ry + d2z * rz;
        double s, t;
        if (a <= 1e-300 && e <= 1e-300) {
            s = t = 0;
        } else if (a <= 1e-300) {
            s = 0;
            t = clamp(f / e);
        } else {
            double c = d1x * rx + d1y * ry + d1z * rz;
            if (e <= 1e-300) {
                t = 0;
                s = clamp(-c / a);
            } else {
                double b = d1x * d2x + d1y * d2y + d1z * d2z, denom = a * e - b * b;
                s = denom > 1e-12 * a * e ? clamp((b * f - c * e) / denom) : 0;
                t = (b * s + f) / e;
                if (t < 0) {
                    t = 0;
                    s = clamp(-c / a);
                } else if (t > 1) {
                    t = 1;
                    s = clamp((b - c) / a);
                }
            }
        }
        out[0] = p1x + d1x * s;
        out[1] = p1y + d1y * s;
        out[2] = p1z + d1z * s;
        out[3] = p2x + d2x * t;
        out[4] = p2y + d2y * t;
        out[5] = p2z + d2z * t;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * Fills {@code out} with the contacts of two oriented boxes: the same result as
     * {@link #polytopes} on the boxes as polytopes (the separating axis test over the six face axes
     * and the nine edge pairs, a face preferred to an edge pair of almost equal separation, the
     * incident face clipped against the reference face and reduced to four points, or the closest
     * points of two edges), without building polytopes.
     *
     * <p>Returns false, with {@code out} empty, when the boxes are apart by more than
     * {@code margin} along some axis, and also when a number is NaN (nothing can be said about such
     * a pose). The contact ids differ from those of {@code polytopes}, since the faces are numbered
     * {@code 2 axis} for the positive side and {@code 2 axis + 1} for the negative one, but they are
     * stable from step to step for a pair of boxes, which is what {@link ContactManifold#warmStartFrom}
     * needs.
     *
     * @param a the first box; must not be {@code null}
     * @param b the second box; must not be {@code null}
     * @param margin the margin; contacts are made for boxes that are apart by less than this
     * @param out receives the result; must not be {@code null}
     * @return {@code true} if the boxes touch or overlap within the margin, in which case {@code out}
     *     holds the contacts; {@code false} if they are further apart
     * @throws IllegalArgumentException if {@code margin} is negative
     */
    public boolean boxes(OrientedBox a, OrientedBox b, double margin, ContactManifold out) {
        out.clear();
        if (!(margin >= 0)) {
            throw new IllegalArgumentException("the margin must not be negative: " + margin);
        }
        final double[] axA = a.axes, axB = b.axes, r = boxR, ar = boxAbs, da = boxDa, db = boxDb, ha = boxHa, hb = boxHb;
        ha[0] = a.hx;
        ha[1] = a.hy;
        ha[2] = a.hz;
        hb[0] = b.hx;
        hb[1] = b.hy;
        hb[2] = b.hz;
        double dx = b.cx - a.cx, dy = b.cy - a.cy, dz = b.cz - a.cz;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double v = axA[3 * i] * axB[3 * j] + axA[3 * i + 1] * axB[3 * j + 1] + axA[3 * i + 2] * axB[3 * j + 2];
                r[3 * i + j] = v;
                ar[3 * i + j] = Math.abs(v);
            }
            da[i] = dx * axA[3 * i] + dy * axA[3 * i + 1] + dz * axA[3 * i + 2];
            db[i] = dx * axB[3 * i] + dy * axB[3 * i + 1] + dz * axB[3 * i + 2];
        }
        // the six face axes: the separation is the distance from the face plane to the nearest point of the other box
        double bestFace = Double.NEGATIVE_INFINITY, faceSign = 1;
        int faceOwner = -1, faceAxis = -1;
        for (int i = 0; i < 3; i++) {
            double sep = Math.abs(da[i]) - (ha[i] + hb[0] * ar[3 * i] + hb[1] * ar[3 * i + 1] + hb[2] * ar[3 * i + 2]);
            if (sep > margin) {
                return false;
            }
            if (sep > bestFace) {
                bestFace = sep;
                faceOwner = 0;
                faceAxis = i;
                faceSign = da[i] >= 0 ? 1 : -1; // the face of A that looks at B
            }
        }
        for (int j = 0; j < 3; j++) {
            double sep = Math.abs(db[j]) - (hb[j] + ha[0] * ar[j] + ha[1] * ar[3 + j] + ha[2] * ar[6 + j]);
            if (sep > margin) {
                return false;
            }
            if (sep > bestFace) {
                bestFace = sep;
                faceOwner = 1;
                faceAxis = j;
                faceSign = db[j] >= 0 ? -1 : 1; // the face of B that looks at A
            }
        }
        if (faceOwner < 0) {
            return false; // NaN
        }
        // the nine edge pairs (the cross product of two parallel axes has no direction: the faces cover them)
        double bestEdge = Double.NEGATIVE_INFINITY;
        int edgeI = -1, edgeJ = -1;
        for (int i = 0; i < 3; i++) {
            int i1 = (i + 1) % 3, i2 = (i + 2) % 3;
            for (int j = 0; j < 3; j++) {
                int j1 = (j + 1) % 3, j2 = (j + 2) % 3;
                double rij = r[3 * i + j], len2 = 1 - rij * rij;
                if (len2 <= 1e-12) {
                    continue;
                }
                double ra = ha[i1] * ar[3 * i2 + j] + ha[i2] * ar[3 * i1 + j];
                double rb = hb[j1] * ar[3 * i + j2] + hb[j2] * ar[3 * i + j1];
                double dist = Math.abs(da[i2] * r[3 * i1 + j] - da[i1] * r[3 * i2 + j]);
                double sep = (dist - ra - rb) / Math.sqrt(len2);
                if (sep > margin) {
                    return false;
                }
                if (sep > bestEdge) {
                    bestEdge = sep;
                    edgeI = i;
                    edgeJ = j;
                }
            }
        }
        double scale = 0;
        for (int c = 0; c < 3; c++) {
            double ea = ha[0] * Math.abs(axA[c]) + ha[1] * Math.abs(axA[3 + c]) + ha[2] * Math.abs(axA[6 + c]);
            double eb = hb[0] * Math.abs(axB[c]) + hb[1] * Math.abs(axB[3 + c]) + hb[2] * Math.abs(axB[6 + c]);
            scale = Math.max(scale, Math.max(Math.abs(c == 0 ? a.cx : c == 1 ? a.cy : a.cz) + ea, Math.abs(c == 0 ? b.cx : c == 1 ? b.cy : b.cz) + eb));
        }
        if (edgeI >= 0 && bestEdge > bestFace + 1e-4 * (1 + scale)) {
            boxEdgeContact(a, b, edgeI, edgeJ, dx, dy, dz, bestEdge, out);
            return out.count() > 0;
        }
        boxFaceContact(a, b, faceOwner, faceAxis, faceSign, margin, out);
        return out.count() > 0;
    }

    private void boxEdgeContact(OrientedBox a, OrientedBox b, int i, int j, double dx, double dy, double dz, double separation, ContactManifold out) {
        final double[] axA = a.axes, axB = b.axes, ha = boxHa, hb = boxHb, c = boxCorner;
        double nx = axA[3 * i + 1] * axB[3 * j + 2] - axA[3 * i + 2] * axB[3 * j + 1];
        double ny = axA[3 * i + 2] * axB[3 * j] - axA[3 * i] * axB[3 * j + 2];
        double nz = axA[3 * i] * axB[3 * j + 1] - axA[3 * i + 1] * axB[3 * j];
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= l;
        ny /= l;
        nz /= l;
        if (nx * dx + ny * dy + nz * dz < 0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        // the edges that touch are the ones, among the four parallel to the axis, that reach furthest along the axis of separation
        int idA = edgeEnds(a, axA, ha, i, nx, ny, nz, 1.0, c, 0), idB = edgeEnds(b, axB, hb, j, nx, ny, nz, -1.0, c, 6);
        segmentSegment(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], closest);
        out.setNormal(nx, ny, nz);
        out.add(closest[0], closest[1], closest[2], closest[3], closest[4], closest[5], -separation, 0x5000 + idA * 131 + idB);
    }

    /**
     * Writes the two ends of the edge of {@code box} along its axis {@code along} that lies furthest
     * along {@code (nx, ny, nz)} (times {@code toward}: +1 for the box on the side of the normal's
     * origin, -1 for the other), and returns the number of that edge, 0 to 11.
     */
    private static int edgeEnds(OrientedBox box, double[] ax, double[] h, int along, double nx, double ny, double nz, double toward, double[] out, int at) {
        double px = box.cx, py = box.cy, pz = box.cz;
        int code = along * 4, bit = 1;
        for (int k = 0; k < 3; k++) {
            if (k == along) {
                continue;
            }
            double side = (nx * ax[3 * k] + ny * ax[3 * k + 1] + nz * ax[3 * k + 2]) * toward >= 0 ? 1 : -1;
            px += side * h[k] * ax[3 * k];
            py += side * h[k] * ax[3 * k + 1];
            pz += side * h[k] * ax[3 * k + 2];
            if (side > 0) {
                code += bit;
            }
            bit <<= 1;
        }
        double ex = h[along] * ax[3 * along], ey = h[along] * ax[3 * along + 1], ez = h[along] * ax[3 * along + 2];
        out[at] = px - ex;
        out[at + 1] = py - ey;
        out[at + 2] = pz - ez;
        out[at + 3] = px + ex;
        out[at + 4] = py + ey;
        out[at + 5] = pz + ez;
        return code;
    }

    private void boxFaceContact(OrientedBox a, OrientedBox b, int owner, int axis, double sign, double margin, ContactManifold out) {
        OrientedBox ref = owner == 0 ? a : b, inc = owner == 0 ? b : a;
        final double[] rx = ref.axes, ix = inc.axes;
        double[] rh = owner == 0 ? boxHa : boxHb, ih = owner == 0 ? boxHb : boxHa;
        int k1 = (axis + 1) % 3, k2 = (axis + 2) % 3;
        double rnx = sign * rx[3 * axis], rny = sign * rx[3 * axis + 1], rnz = sign * rx[3 * axis + 2];
        double rd = rnx * ref.cx + rny * ref.cy + rnz * ref.cz + rh[axis];
        // the incident face: the face of the other box that is the most anti-parallel to the reference normal
        int m = 0;
        double bestAbs = -1, bestDot = 0;
        for (int q = 0; q < 3; q++) {
            double dot = rnx * ix[3 * q] + rny * ix[3 * q + 1] + rnz * ix[3 * q + 2];
            if (Math.abs(dot) > bestAbs) {
                bestAbs = Math.abs(dot);
                m = q;
                bestDot = dot;
            }
        }
        double side = bestDot > 0 ? -1 : 1;
        int u = (m + 1) % 3, v = (m + 2) % 3;
        double fx = inc.cx + side * ih[m] * ix[3 * m], fy = inc.cy + side * ih[m] * ix[3 * m + 1], fz = inc.cz + side * ih[m] * ix[3 * m + 2];
        int refFace = 2 * axis + (sign > 0 ? 0 : 1), incFace = 2 * m + (side > 0 ? 0 : 1);
        double[] pin = polyIn, uin = uvIn;
        int n = 4;
        for (int q = 0; q < 4; q++) {
            double su = (q == 0 || q == 3) ? 1 : -1, sv = (q < 2) ? 1 : -1;
            double px = fx + su * ih[u] * ix[3 * u] + sv * ih[v] * ix[3 * v];
            double py = fy + su * ih[u] * ix[3 * u + 1] + sv * ih[v] * ix[3 * v + 1];
            double pz = fz + su * ih[u] * ix[3 * u + 2] + sv * ih[v] * ix[3 * v + 2];
            pin[3 * q] = px;
            pin[3 * q + 1] = py;
            pin[3 * q + 2] = pz;
            double qx = px - ref.cx, qy = py - ref.cy, qz = pz - ref.cz;
            uin[2 * q] = qx * rx[3 * k1] + qy * rx[3 * k1 + 1] + qz * rx[3 * k1 + 2];
            uin[2 * q + 1] = qx * rx[3 * k2] + qy * rx[3 * k2 + 1] + qz * rx[3 * k2 + 2];
            idIn[q] = (refFace << 16) ^ (incFace << 8) ^ (q + 1);
        }
        // clip against the four sides of the reference face: |u| <= half extent of the first other axis, |v| <= that of the second
        for (int plane = 0; plane < 4 && n > 0; plane++) {
            int comp = plane >> 1;
            double half = rh[comp == 0 ? k1 : k2], sgn = (plane & 1) == 0 ? -1 : 1; // inside: half + sgn * coordinate >= 0
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double di = half + sgn * uvIn[2 * i + comp], dj = half + sgn * uvIn[2 * j + comp];
                if (di >= 0 && cnt < CAPACITY - 1) {
                    polyOut[3 * cnt] = polyIn[3 * i];
                    polyOut[3 * cnt + 1] = polyIn[3 * i + 1];
                    polyOut[3 * cnt + 2] = polyIn[3 * i + 2];
                    uvOut[2 * cnt] = uvIn[2 * i];
                    uvOut[2 * cnt + 1] = uvIn[2 * i + 1];
                    idOut[cnt++] = idIn[i];
                }
                if ((di >= 0) != (dj >= 0) && cnt < CAPACITY - 1) {
                    double t = di / (di - dj);
                    polyOut[3 * cnt] = polyIn[3 * i] + t * (polyIn[3 * j] - polyIn[3 * i]);
                    polyOut[3 * cnt + 1] = polyIn[3 * i + 1] + t * (polyIn[3 * j + 1] - polyIn[3 * i + 1]);
                    polyOut[3 * cnt + 2] = polyIn[3 * i + 2] + t * (polyIn[3 * j + 2] - polyIn[3 * i + 2]);
                    uvOut[2 * cnt] = uvIn[2 * i] + t * (uvIn[2 * j] - uvIn[2 * i]);
                    uvOut[2 * cnt + 1] = uvIn[2 * i + 1] + t * (uvIn[2 * j + 1] - uvIn[2 * i + 1]);
                    idOut[cnt++] = 0x4000 + plane * 64 + (idIn[i] & 0x3F);
                }
            }
            double[] td = polyIn;
            polyIn = polyOut;
            polyOut = td;
            td = uvIn;
            uvIn = uvOut;
            uvOut = td;
            int[] ti = idIn;
            idIn = idOut;
            idOut = ti;
            n = cnt;
        }
        // the points below (or within the margin of) the reference plane are the contacts
        int count = 0;
        double deepest = Double.POSITIVE_INFINITY;
        int deepestIndex = -1;
        for (int i = 0; i < n; i++) {
            double sep = rnx * polyIn[3 * i] + rny * polyIn[3 * i + 1] + rnz * polyIn[3 * i + 2] - rd;
            if (sep < deepest) {
                deepest = sep;
                deepestIndex = i;
            }
            if (sep <= margin) {
                addCandidate(count++, owner, polyIn[3 * i], polyIn[3 * i + 1], polyIn[3 * i + 2], sep, rnx, rny, rnz, idIn[i]);
            }
        }
        if (count == 0 && deepestIndex >= 0 && deepest <= margin) {
            addCandidate(count++, owner, polyIn[3 * deepestIndex], polyIn[3 * deepestIndex + 1], polyIn[3 * deepestIndex + 2], deepest, rnx, rny, rnz, idIn[deepestIndex]);
        }
        if (count == 0) {
            // nothing is left of the incident face: the corner of the incident box that reaches deepest below the reference plane is a valid single contact
            int bestCorner = -1;
            double bestSep = Double.POSITIVE_INFINITY;
            for (int c = 0; c < 8; c++) {
                double sx = (c & 1) == 0 ? -1 : 1, sy = (c & 2) == 0 ? -1 : 1, sz = (c & 4) == 0 ? -1 : 1;
                double px = inc.cx + sx * ih[0] * ix[0] + sy * ih[1] * ix[3] + sz * ih[2] * ix[6];
                double py = inc.cy + sx * ih[0] * ix[1] + sy * ih[1] * ix[4] + sz * ih[2] * ix[7];
                double pz = inc.cz + sx * ih[0] * ix[2] + sy * ih[1] * ix[5] + sz * ih[2] * ix[8];
                double sep = rnx * px + rny * py + rnz * pz - rd;
                if (sep < bestSep) {
                    bestSep = sep;
                    bestCorner = c;
                }
            }
            if (bestCorner >= 0 && bestSep <= margin) {
                double sx = (bestCorner & 1) == 0 ? -1 : 1, sy = (bestCorner & 2) == 0 ? -1 : 1, sz = (bestCorner & 4) == 0 ? -1 : 1;
                addCandidate(count++, owner, inc.cx + sx * ih[0] * ix[0] + sy * ih[1] * ix[3] + sz * ih[2] * ix[6], inc.cy + sx * ih[0] * ix[1] + sy * ih[1] * ix[4] + sz * ih[2] * ix[7],
                        inc.cz + sx * ih[0] * ix[2] + sy * ih[1] * ix[5] + sz * ih[2] * ix[8], bestSep, rnx, rny, rnz, 0x7000 + bestCorner);
            }
        }
        if (owner == 0) {
            out.setNormal(rnx, rny, rnz);
        } else {
            out.setNormal(-rnx, -rny, -rnz);
        }
        reduce(count, out);
    }

    /**
     * Returns the contact of any two convex shapes from the penetration query of {@link Gjk}: one
     * point, with the normal from {@code a} to {@code b} and the depth.
     *
     * <p>Returns false, with {@code out} empty, when the shapes do not overlap. For two shapes with
     * flat faces use {@link #polytopes}, which gives the whole contact patch.
     *
     * @param a the first convex shape; must not be {@code null}
     * @param b the second convex shape; must not be {@code null}
     * @param out receives the result; must not be {@code null}
     * @return {@code true} if the shapes overlap, in which case {@code out} holds one contact;
     *     {@code false} otherwise
     */
    public boolean shapes(ConvexShape a, ConvexShape b, ContactManifold out) {
        out.clear();
        if (!gjk.penetration(a, b, gjkResult)) {
            return false;
        }
        out.setNormal(gjkResult.normal[0], gjkResult.normal[1], gjkResult.normal[2]);
        out.add(gjkResult.pointA[0], gjkResult.pointA[1], gjkResult.pointA[2], gjkResult.pointB[0], gjkResult.pointB[1], gjkResult.pointB[2], gjkResult.depth, 0x7000);
        return true;
    }
}
