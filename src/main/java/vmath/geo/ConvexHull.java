package vmath.geo;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import vmath.annotations.Experimental;
import vmath.core.Predicates;

/**
 * The convex hull of a set of 3D points, by quickhull: start from a tetrahedron of extreme points, then repeatedly take the point farthest above a face, remove every face
 * that can see it and join the hole's rim to the point.
 *
 * <p>Every decision that changes the shape (is this point above that face, which faces form the horizon) is made with the exact {@link Predicates#orient3d}, so the result is
 * always a closed, convex, consistently oriented triangle mesh: a point that is exactly on a face's plane is <em>not</em> above it, the vertices of the result are exactly the extreme points of the input (a point in the middle
 * of an edge or a face, as in a lattice or on the sides of a cube, is not listed: quickhull's first result is checked for such points with exact arithmetic and the hull is built again
 * without them), and nearly coplanar or nearly cospherical input cannot produce a hull with a dent or a hole. Only the choice of the farthest point uses approximate
 * distances (it affects speed, not correctness). A face that is planar with more than three points is made of several triangles; collinear and coplanar points on the
 * surface are never vertices of the hull.
 *
 * <p>Degenerate input is reported, not rejected: {@link #dimension()} is 3 for a solid hull, 2 when all points are coplanar (the hull is then a convex polygon, given as a
 * triangle fan), 1 when they are collinear (two end points) and 0 when they coincide (one point). Fewer than one point is an {@link IllegalArgumentException}.
 *
 * <p>The cost is O(n log n) on average for random points and O(n^2) in the worst case; the points are a flat {@code float[]} of {@code x, y, z} triples and are converted to
 * double exactly. Every index in the result refers to the input array (the n-th point is {@code n}).
 *
 * <p><b>Thread safety.</b> The result is immutable and may be shared; {@link #of} is stateless.
 */
@Experimental("the result type and the handling of degenerate input may change with the polytope and collision code built on it")
public final class ConvexHull {

    private final int dimension;
    private final int[] vertices;
    private final int[] triangles;
    private final double volume;
    private final double area;

    private ConvexHull(int dimension, int[] vertices, int[] triangles, double volume, double area) {
        this.dimension = dimension;
        this.vertices = vertices;
        this.triangles = triangles;
        this.volume = volume;
        this.area = area;
    }

    /** 3 for a solid hull, 2 for a convex polygon, 1 for a segment, 0 for a single point. */
    public int dimension() {
        return dimension;
    }

    /** The indices of the points that are vertices of the hull, in increasing order. Points on faces, edges or inside are not listed. */
    public int[] vertices() {
        return vertices.clone();
    }

    /** The number of vertices of the hull. */
    public int vertexCount() {
        return vertices.length;
    }

    /**
     * The faces as triples of point indices, counter-clockwise seen from outside (so {@code (b - a) x (c - a)} points out of the hull). Empty when the dimension is below 2; for
     * dimension 2 the triangles fan the polygon and are counter-clockwise seen from the side that the normal's dominant axis (the one with the largest component) points to, with the axis pointing toward the viewer: z for a plane that is mostly horizontal, x or y for the others.
     */
    public int[] triangles() {
        return triangles.clone();
    }

    /** The number of triangles. */
    public int triangleCount() {
        return triangles.length / 3;
    }

    /** The volume (0 below dimension 3). Computed in double from the exact topology; the rounding is that of the float input converted to double. */
    public double volume() {
        return volume;
    }

    /** The surface area (for dimension 2 the area of the polygon, for lower dimensions 0). */
    public double surfaceArea() {
        return area;
    }

    // ---------------------------------------------------------------- construction

    /** The hull of the {@code count} points in {@code xyz}. */
    public static ConvexHull of(float[] xyz, int count) {
        if (count < 1 || (long) count * 3 > xyz.length) {
            throw new IllegalArgumentException("need between 1 and " + xyz.length / 3 + " points: " + count);
        }
        for (int i = 0; i < 3 * count; i++) {
            if (!Float.isFinite(xyz[i])) {
                throw new IllegalArgumentException("coordinate " + i + " is not finite");
            }
        }
        double[] p = new double[3 * count];
        for (int i = 0; i < p.length; i++) {
            p[i] = xyz[i];
        }
        return new Builder(p, count).build();
    }

    private static final class IntBuf {
        int[] a = new int[4];
        int n;

        void add(int v) {
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = v;
        }
    }

    private static final class Builder {
        final double[] p;
        final int n;
        int[] fa = new int[64], fb = new int[64], fc = new int[64];
        boolean[] alive = new boolean[64];
        IntBuf[] outside = new IntBuf[64];
        int faces;
        final Map<Long, Integer> edgeFace = new HashMap<>();

        Builder(double[] p, int n) {
            this.p = p;
            this.n = n;
        }

        double x(int i) {
            return p[3 * i];
        }

        double y(int i) {
            return p[3 * i + 1];
        }

        double z(int i) {
            return p[3 * i + 2];
        }

        /** Orientation of the four points as {@link Predicates#orient3d}: negative when d is above the plane of a, b, c (the side of their counter-clockwise normal). */
        double orient(int a, int b, int c, int d) {
            return Predicates.orient3d(x(a), y(a), z(a), x(b), y(b), z(b), x(c), y(c), z(c), x(d), y(d), z(d));
        }

        boolean above(int f, int q) {
            return orient(fa[f], fb[f], fc[f], q) < 0.0;
        }

        /** Approximate height of point q above face f (positive outside): used only to choose the farthest point. */
        double height(int f, int q) {
            int a = fa[f], b = fb[f], c = fc[f];
            double ux = x(b) - x(a), uy = y(b) - y(a), uz = z(b) - z(a);
            double vx = x(c) - x(a), vy = y(c) - y(a), vz = z(c) - z(a);
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            return len == 0 ? 0 : (nx * (x(q) - x(a)) + ny * (y(q) - y(a)) + nz * (z(q) - z(a))) / len;
        }

        ConvexHull build() {
            // an extreme point pair along x (lexicographic order makes ties deterministic)
            int i0 = 0, i1 = 0;
            for (int i = 1; i < n; i++) {
                if (less(i, i0)) {
                    i0 = i;
                }
                if (less(i1, i)) {
                    i1 = i;
                }
            }
            if (!differ(i0, i1)) {
                return new ConvexHull(0, new int[] {i0}, new int[0], 0, 0);
            }
            // a third point, as far from the line as possible; exactly collinear sets are detected with the exact test
            int i2 = -1;
            double best = 0;
            for (int i = 0; i < n; i++) {
                double d = lineDistance2(i0, i1, i);
                if (d > best) {
                    best = d;
                    i2 = i;
                }
            }
            if (i2 < 0 || collinear(i0, i1, i2)) {
                i2 = -1;
                for (int i = 0; i < n && i2 < 0; i++) {
                    if (!collinear(i0, i1, i)) {
                        i2 = i;
                    }
                }
                if (i2 < 0) {
                    return new ConvexHull(1, new int[] {Math.min(i0, i1), Math.max(i0, i1)}, new int[0], 0, 0);
                }
            }
            // a fourth point, as far from the plane as possible; exactly coplanar sets are detected with the exact predicate
            int i3 = -1;
            best = 0;
            for (int i = 0; i < n; i++) {
                double d = Math.abs(orientApprox(i0, i1, i2, i));
                if (d > best) {
                    best = d;
                    i3 = i;
                }
            }
            if (i3 < 0 || orient(i0, i1, i2, i3) == 0.0) {
                i3 = -1;
                for (int i = 0; i < n && i3 < 0; i++) {
                    if (orient(i0, i1, i2, i) != 0.0) {
                        i3 = i;
                    }
                }
                if (i3 < 0) {
                    return planar(i0, i1, i2);
                }
            }
            ConvexHull first = solid(i0, i1, i2, i3);
            // quickhull can keep a point that is exactly on an edge or a face of the final hull (it was above a face of an earlier, smaller hull): such points are not extreme.
            // They are found exactly and the hull is built again from the extreme points alone, which cannot create new ones.
            int[] strict = extremeVertices(first);
            if (strict.length == first.vertices.length) {
                return first;
            }
            double[] q = new double[3 * strict.length];
            for (int i = 0; i < strict.length; i++) {
                System.arraycopy(p, 3 * strict[i], q, 3 * i, 3);
            }
            ConvexHull second = new Builder(q, strict.length).build();
            int[] mapped = new int[second.vertices.length];
            for (int i = 0; i < mapped.length; i++) {
                mapped[i] = strict[second.vertices[i]];
            }
            int[] tri = new int[second.triangles.length];
            for (int i = 0; i < tri.length; i++) {
                tri[i] = strict[second.triangles[i]];
            }
            return new ConvexHull(3, mapped, tri, second.volume, second.area);
        }

        /**
         * The vertices of the hull that are extreme points: those whose incident face normals span space. When the normals of all faces at a vertex lie in a plane (or are
         * parallel) the vertex is in the middle of an edge or inside a facet. Exact: the coordinates are doubles and the arithmetic is {@link BigDecimal}.
         */
        int[] extremeVertices(ConvexHull h) {
            int f = h.triangles.length / 3;
            Map<Integer, java.util.List<Integer>> incident = new HashMap<>();
            for (int i = 0; i < f; i++) {
                for (int k = 0; k < 3; k++) {
                    incident.computeIfAbsent(h.triangles[3 * i + k], v -> new java.util.ArrayList<>()).add(i);
                }
            }
            IntBuf strict = new IntBuf();
            for (int v : h.vertices) {
                if (spansSpace(v, incident.get(v), h.triangles)) {
                    strict.add(v);
                }
            }
            return Arrays.copyOf(strict.a, strict.n);
        }

        boolean spansSpace(int v, java.util.List<Integer> faces, int[] tri) {
            BigDecimal[][] normals = new BigDecimal[faces.size()][];
            for (int i = 0; i < normals.length; i++) {
                int base = 3 * faces.get(i);
                int a = tri[base], b = tri[base + 1], c = tri[base + 2];
                normals[i] = exactCross(a, b, c);
            }
            // two non-parallel normals give a direction; the normals span space when one of the others is not perpendicular to it
            for (int i = 0; i < normals.length; i++) {
                for (int j = i + 1; j < normals.length; j++) {
                    BigDecimal[] d = cross(normals[i], normals[j]);
                    if (d[0].signum() == 0 && d[1].signum() == 0 && d[2].signum() == 0) {
                        continue;
                    }
                    for (BigDecimal[] other : normals) {
                        if (other[0].multiply(d[0]).add(other[1].multiply(d[1])).add(other[2].multiply(d[2])).signum() != 0) {
                            return true;
                        }
                    }
                    return false;
                }
            }
            return false;
        }

        BigDecimal[] exactCross(int a, int b, int c) {
            BigDecimal ux = d(x(b)).subtract(d(x(a))), uy = d(y(b)).subtract(d(y(a))), uz = d(z(b)).subtract(d(z(a)));
            BigDecimal vx = d(x(c)).subtract(d(x(a))), vy = d(y(c)).subtract(d(y(a))), vz = d(z(c)).subtract(d(z(a)));
            return new BigDecimal[] {uy.multiply(vz).subtract(uz.multiply(vy)), uz.multiply(vx).subtract(ux.multiply(vz)), ux.multiply(vy).subtract(uy.multiply(vx))};
        }

        BigDecimal[] cross(BigDecimal[] u, BigDecimal[] v) {
            return new BigDecimal[] {u[1].multiply(v[2]).subtract(u[2].multiply(v[1])), u[2].multiply(v[0]).subtract(u[0].multiply(v[2])), u[0].multiply(v[1]).subtract(u[1].multiply(v[0]))};
        }

        BigDecimal d(double x) {
            return new BigDecimal(x);
        }

        boolean less(int a, int b) {
            return x(a) < x(b) || (x(a) == x(b) && (y(a) < y(b) || (y(a) == y(b) && z(a) < z(b))));
        }

        boolean differ(int a, int b) {
            return x(a) != x(b) || y(a) != y(b) || z(a) != z(b);
        }

        double lineDistance2(int a, int b, int q) {
            double ux = x(b) - x(a), uy = y(b) - y(a), uz = z(b) - z(a);
            double vx = x(q) - x(a), vy = y(q) - y(a), vz = z(q) - z(a);
            double cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
            return cx * cx + cy * cy + cz * cz;
        }

        /** Whether a, b, q are exactly collinear: the three coordinate-plane projections all have orientation zero. */
        boolean collinear(int a, int b, int q) {
            return Predicates.orient2d(x(a), y(a), x(b), y(b), x(q), y(q)) == 0.0 && Predicates.orient2d(y(a), z(a), y(b), z(b), y(q), z(q)) == 0.0
                    && Predicates.orient2d(z(a), x(a), z(b), x(b), z(q), x(q)) == 0.0;
        }

        double orientApprox(int a, int b, int c, int d) {
            double adx = x(a) - x(d), ady = y(a) - y(d), adz = z(a) - z(d);
            double bdx = x(b) - x(d), bdy = y(b) - y(d), bdz = z(b) - z(d);
            double cdx = x(c) - x(d), cdy = y(c) - y(d), cdz = z(c) - z(d);
            return adx * (bdy * cdz - bdz * cdy) + ady * (bdz * cdx - bdx * cdz) + adz * (bdx * cdy - bdy * cdx);
        }

        // ---- all points coplanar: a convex polygon

        ConvexHull planar(int i0, int i1, int i2) {
            double ux = x(i1) - x(i0), uy = y(i1) - y(i0), uz = z(i1) - z(i0);
            double vx = x(i2) - x(i0), vy = y(i2) - y(i0), vz = z(i2) - z(i0);
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
            // project along the dominant axis of the normal; the polygon is counter-clockwise seen from the side of the positive dominant axis
            int axis = az >= ax && az >= ay ? 2 : ax >= ay ? 0 : 1;
            double[] u = new double[n], w = new double[n];
            for (int i = 0; i < n; i++) {
                double a = axis == 0 ? y(i) : axis == 1 ? z(i) : x(i);
                double b = axis == 0 ? z(i) : axis == 1 ? x(i) : y(i);
                u[i] = a;
                w[i] = b;
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (s, t) -> u[s] != u[t] ? Double.compare(u[s], u[t]) : Double.compare(w[s], w[t]));
            int[] hull = new int[2 * n];
            int k = 0;
            for (int pass = 0; pass < 2; pass++) { // Andrew's monotone chain with the exact orientation
                int start = k;
                for (int idx = 0; idx < n; idx++) {
                    int i = pass == 0 ? order[idx] : order[n - 1 - idx];
                    while (k >= start + 2 && Predicates.orient2d(u[hull[k - 2]], w[hull[k - 2]], u[hull[k - 1]], w[hull[k - 1]], u[i], w[i]) <= 0.0) {
                        k--;
                    }
                    hull[k++] = i;
                }
                k--; // the last point of each chain is the first of the next
            }
            int m = k;
            int[] poly = Arrays.copyOf(hull, m); // the chain runs counter-clockwise in the projected plane
            int[] tri = new int[3 * (m - 2)];
            double areaSum = 0;
            for (int i = 1; i + 1 < m; i++) {
                tri[3 * (i - 1)] = poly[0];
                tri[3 * (i - 1) + 1] = poly[i];
                tri[3 * (i - 1) + 2] = poly[i + 1];
                areaSum += 0.5 * Math.sqrt(sq(cross(poly[0], poly[i], poly[i + 1])));
            }
            int[] verts = poly.clone();
            Arrays.sort(verts);
            return new ConvexHull(2, verts, tri, 0, areaSum);
        }

        double[] cross(int a, int b, int c) {
            double ux = x(b) - x(a), uy = y(b) - y(a), uz = z(b) - z(a);
            double vx = x(c) - x(a), vy = y(c) - y(a), vz = z(c) - z(a);
            return new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx};
        }

        double sq(double[] v) {
            return v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
        }

        // ---- quickhull

        ConvexHull solid(int a, int b, int c, int d) {
            if (orient(a, b, c, d) < 0.0) { // d above abc: swap so that d is below and the face abc points away from d
                int t = b;
                b = c;
                c = t;
            }
            int f0 = addFace(a, b, c), f1 = addFace(a, d, b), f2 = addFace(b, d, c), f3 = addFace(c, d, a);
            boolean[] used = new boolean[n];
            used[a] = used[b] = used[c] = used[d] = true;
            for (int i = 0; i < n; i++) {
                if (used[i]) {
                    continue;
                }
                assign(i, new int[] {f0, f1, f2, f3}, 4);
            }
            ArrayDeque<Integer> work = new ArrayDeque<>();
            for (int f = 0; f < faces; f++) {
                work.add(f);
            }
            boolean[] visible = new boolean[0];
            while (!work.isEmpty()) {
                int f = work.poll();
                if (!alive[f] || outside[f] == null || outside[f].n == 0) {
                    continue;
                }
                // the farthest point above this face
                int far = -1;
                double farH = -1;
                for (int k = 0; k < outside[f].n; k++) {
                    int q = outside[f].a[k];
                    double h = height(f, q);
                    if (h > farH) {
                        farH = h;
                        far = q;
                    }
                }
                // every connected face that can see it
                if (visible.length < faces) {
                    visible = new boolean[Math.max(faces, 2 * visible.length)];
                }
                IntBuf seen = new IntBuf();
                ArrayDeque<Integer> stack = new ArrayDeque<>();
                stack.push(f);
                visible[f] = true;
                seen.add(f);
                while (!stack.isEmpty()) {
                    int g = stack.pop();
                    int[] e = {fa[g], fb[g], fc[g]};
                    for (int k = 0; k < 3; k++) {
                        Integer nb = edgeFace.get(key(e[(k + 1) % 3], e[k]));
                        if (nb != null && alive[nb] && !visible[nb] && above(nb, far)) {
                            visible[nb] = true;
                            seen.add(nb);
                            stack.push(nb);
                        }
                    }
                }
                // the horizon: edges of visible faces whose neighbour is not visible
                IntBuf horizon = new IntBuf();
                for (int s = 0; s < seen.n; s++) {
                    int g = seen.a[s];
                    int[] e = {fa[g], fb[g], fc[g]};
                    for (int k = 0; k < 3; k++) {
                        Integer nb = edgeFace.get(key(e[(k + 1) % 3], e[k]));
                        if (nb == null || !visible[nb]) {
                            horizon.add(e[k]);
                            horizon.add(e[(k + 1) % 3]);
                        }
                    }
                }
                // points of the removed faces that must be reassigned
                IntBuf orphans = new IntBuf();
                for (int s = 0; s < seen.n; s++) {
                    IntBuf o = outside[seen.a[s]];
                    if (o != null) {
                        for (int k = 0; k < o.n; k++) {
                            if (o.a[k] != far) {
                                orphans.add(o.a[k]);
                            }
                        }
                    }
                }
                for (int s = 0; s < seen.n; s++) {
                    int g = seen.a[s];
                    removeFace(g);
                    visible[g] = false;
                }
                int[] created = new int[horizon.n / 2];
                for (int k = 0; k < horizon.n; k += 2) {
                    created[k / 2] = addFace(horizon.a[k], horizon.a[k + 1], far);
                }
                for (int k = 0; k < orphans.n; k++) {
                    assign(orphans.a[k], created, created.length);
                }
                for (int g : created) {
                    work.add(g);
                }
            }
            return finish();
        }

        void assign(int q, int[] candidates, int count) {
            for (int k = 0; k < count; k++) {
                int f = candidates[k];
                if (above(f, q)) {
                    if (outside[f] == null) {
                        outside[f] = new IntBuf();
                    }
                    outside[f].add(q);
                    return;
                }
            }
        }

        static long key(int u, int v) {
            return ((long) u << 32) | (v & 0xFFFFFFFFL);
        }

        int addFace(int a, int b, int c) {
            if (faces == fa.length) {
                int m = faces * 2;
                fa = Arrays.copyOf(fa, m);
                fb = Arrays.copyOf(fb, m);
                fc = Arrays.copyOf(fc, m);
                alive = Arrays.copyOf(alive, m);
                outside = Arrays.copyOf(outside, m);
            }
            int f = faces++;
            fa[f] = a;
            fb[f] = b;
            fc[f] = c;
            alive[f] = true;
            outside[f] = null;
            edgeFace.put(key(a, b), f);
            edgeFace.put(key(b, c), f);
            edgeFace.put(key(c, a), f);
            return f;
        }

        void removeFace(int f) {
            alive[f] = false;
            edgeFace.remove(key(fa[f], fb[f]));
            edgeFace.remove(key(fb[f], fc[f]));
            edgeFace.remove(key(fc[f], fa[f]));
            outside[f] = null;
        }

        ConvexHull finish() {
            int count = 0;
            for (int f = 0; f < faces; f++) {
                if (alive[f]) {
                    count++;
                }
            }
            int[] tri = new int[3 * count];
            boolean[] isVertex = new boolean[n];
            double vol = 0, area = 0;
            int k = 0;
            // the volume as a sum of signed tetrahedra to the first vertex of the first face (exact topology, double arithmetic)
            int ref = -1;
            for (int f = 0; f < faces; f++) {
                if (!alive[f]) {
                    continue;
                }
                if (ref < 0) {
                    ref = fa[f];
                }
                tri[k++] = fa[f];
                tri[k++] = fb[f];
                tri[k++] = fc[f];
                isVertex[fa[f]] = isVertex[fb[f]] = isVertex[fc[f]] = true;
                double[] cr = cross(fa[f], fb[f], fc[f]);
                area += 0.5 * Math.sqrt(sq(cr));
                vol += (cr[0] * (x(fa[f]) - x(ref)) + cr[1] * (y(fa[f]) - y(ref)) + cr[2] * (z(fa[f]) - z(ref))) / 6.0;
            }
            int vc = 0;
            for (boolean b : isVertex) {
                if (b) {
                    vc++;
                }
            }
            int[] verts = new int[vc];
            vc = 0;
            for (int i = 0; i < n; i++) {
                if (isVertex[i]) {
                    verts[vc++] = i;
                }
            }
            return new ConvexHull(3, verts, tri, vol, area);
        }
    }
}
