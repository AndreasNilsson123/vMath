package vmath.physics;

import vmath.geo.ConvexPolytope;
import vmath.geo.ConvexShape;
import vmath.geo.Gjk;

/**
 * Builds the {@link ContactManifold} of two convex bodies.
 *
 * <p>{@link #polytopes} is the complete method for two {@link ConvexPolytope}s (boxes are polytopes: {@code ConvexPolytope.of(Aabbf)}): the <b>separating axis test</b> over the facet normals
 * of both and the cross products of their edges finds the axis of least penetration; when it is a facet normal, the facet of one body and the most opposed facet of the other are the
 * <em>reference</em> and the <em>incident</em> facet, the incident polygon is <b>clipped</b> against the side planes of the reference facet (the method of Sutherland and Hodgman, as in
 * the "robust contact creation" of Gregorius, GDC 2015), and the points that lie below the reference plane are the contacts, up to four after a reduction that keeps the deepest point and the
 * ones that spread the contact most; when it is a pair of edges, the contact is the closest pair of points of the two edges. A facet normal is preferred to an edge pair of almost equal
 * separation, so that a contact does not flip between the two from frame to frame.
 *
 * <p>The optional {@code margin} makes the builder <b>speculative</b>: shapes that are apart by less than the margin along the best axis still get contacts, with a negative depth, so that a
 * solver can stop a fast body before it penetrates (the solver lets the body approach by that distance and no more).
 *
 * <p>{@link #shapes} is the general fallback for any two {@link ConvexShape}s (spheres, capsules, rounded boxes, any support function): one contact point from the penetration query of
 * {@link Gjk}.
 *
 * <p>The cost of {@code polytopes} is O(F (V_a + V_b) + E_a E_b (V_a + V_b)) in the facets, edges and vertices: made for the boxes and small polytopes of the usual collision shapes, not
 * for hulls of hundreds of vertices. A builder owns its scratch memory, so after construction a call allocates nothing; use one per thread.
 *
 * <p><b>Thread safety.</b> Not thread-safe.
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

    /** A builder with its scratch memory. */
    public ManifoldBuilder() {
    }

    /**
     * Fills {@code out} with the contacts of the two polytopes in the poses they are in (their vertices are in world space: use {@link ConvexPolytope#transformed} for a moved body). Returns
     * false, with {@code out} empty, when they are apart by more than {@code margin} along some separating axis (a margin of 0 asks for contacts only when they overlap or touch).
     */
    public boolean polytopes(ConvexPolytope a, ConvexPolytope b, double margin, ContactManifold out) {
        out.clear();
        if (!(margin >= 0)) {
            throw new IllegalArgumentException("the margin must not be negative: " + margin);
        }
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
        cax /= na;
        cay /= na;
        caz /= na;
        cbx /= nb;
        cby /= nb;
        cbz /= nb;
        // the facets of both: the least separation (the greatest penetration is the most negative one, so the "best" axis has the largest separation)
        double bestFace = Double.NEGATIVE_INFINITY;
        int faceOwner = -1, faceIndex = -1;
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
        // the edge pairs
        double bestEdge = Double.NEGATIVE_INFINITY;
        int edgeA = -1, edgeB = -1;
        double enx = 0, eny = 0, enz = 0;
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
                if (x * (cbx - cax) + y * (cby - cay) + z * (cbz - caz) < 0) {
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
                    enx = x;
                    eny = y;
                    enz = z;
                }
            }
        }
        if (edgeA >= 0 && bestEdge > bestFace + 1e-4 * (1 + scale)) {
            edgeContact(a, b, edgeA, edgeB, enx, eny, enz, bestEdge, out);
            return out.count() > 0;
        }
        faceContact(a, b, faceOwner, faceIndex, margin, out);
        return out.count() > 0;
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
     * Of the edges of {@code p} parallel to edge {@code e}, the one that reaches furthest along {@code (nx, ny, nz)} (when {@code max}) or least far (when not): the larger of the smaller
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
        // the incident facet: the most anti-parallel to the reference normal
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
        int n = Math.min(inc.facetVertexCount(incFacet), CAPACITY / 2);
        for (int k = 0; k < n; k++) {
            int v = inc.facetVertex(incFacet, k);
            polyIn[3 * k] = inc.vertex(v, 0);
            polyIn[3 * k + 1] = inc.vertex(v, 1);
            polyIn[3 * k + 2] = inc.vertex(v, 2);
            idIn[k] = (facet << 16) ^ (incFacet << 8) ^ (k + 1);
        }
        // clip against the side planes of the reference facet: inside is the left of each edge seen from outside, n x edge
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
            mx /= ml;
            my /= ml;
            mz /= ml;
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
                    idOut[m++] = 0x4000 + k * 64 + (idIn[i] & 0x3F);
                }
            }
            double[] td = polyIn;
            polyIn = polyOut;
            polyOut = td;
            int[] ti = idIn;
            idIn = idOut;
            idOut = ti;
            n = m;
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
            // Nothing is left of the incident facet: with deep penetration of a general hull it can lie outside the side planes of the reference facet. The axis is still the one of least
            // penetration, so the vertex of the incident body that reaches deepest below the reference plane is a valid single contact.
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
                addCandidate(count++, owner, inc.vertex(bestVertex, 0), inc.vertex(bestVertex, 1), inc.vertex(bestVertex, 2), bestSep, rnx, rny, rnz, 0x7000 + bestVertex);
            }
        }
        if (owner == 0) {
            out.setNormal(rnx, rny, rnz);
        } else {
            out.setNormal(-rnx, -rny, -rnz);
        }
        reduce(count, out);
    }

    /** Stores the contact for the clipped point {@code (x, y, z)}, which is on the incident body at the signed distance {@code sep} above the reference plane. */
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

    /** Keeps at most four of the {@code count} candidates: the deepest, the one farthest from it, and the two that make the largest triangles on either side of that line. */
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

    /** The closest points of the segments {@code p1 q1} and {@code p2 q2}: on the first into {@code out[0 .. 3)}, on the second into {@code out[3 .. 6)} (Ericson, "Real-Time Collision Detection", 5.1.9). */
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
     * The contact of any two convex shapes from the penetration query of {@link Gjk}: one point, with the normal from {@code a} to {@code b} and the depth. Returns false, with {@code out} empty,
     * when the shapes do not overlap. For two shapes with flat faces use {@link #polytopes}, which gives the whole contact patch.
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
