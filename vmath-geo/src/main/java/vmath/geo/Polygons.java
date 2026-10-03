package vmath.geo;

import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.core.Predicates;

/**
 * Polygon utilities on plain {@code float[]} vertex arrays: the orientation and area of a polygon,
 * point containment, a simplicity test, ear-clipping triangulation (of simple polygons, with holes,
 * and of planar polygons in 3D) and Sutherland-Hodgman clipping against a half-plane or a convex
 * polygon.
 *
 * <p>A 2D polygon is {@code count} vertices stored as {@code x0, y0, x1, y1, ...}, without
 * repeating the first vertex at the end; a 3D polygon is stored as {@code x, y, z} triples.
 * <b>Counter-clockwise</b> means counter-clockwise with x to the right and y up. Every decision
 * that could flip on rounding (which way a polygon turns, whether a vertex is convex, whether a
 * point lies inside a triangle or on which side of an edge) is made with the exact
 * {@link Predicates#orient2d} on the float coordinates, which convert to double exactly: so a
 * collinear triple is reliably collinear and a polygon is never mistaken for its mirror image.
 * Areas and the coordinates of clip intersections are computed in double and rounded to float at
 * the end.
 *
 * <p><b>Triangulation</b> clips ears from a doubly linked ring of vertices; a vertex whose two
 * edges are collinear is removed without emitting a triangle, which can leave a T-junction along
 * that edge but never changes the area. Holes are joined to the outer ring by bridges (Eberly's
 * method, as in the earcut library), after which the ring is clipped like a simple polygon; the
 * triangles index the <em>original</em> vertices and a bridge vertex appears in more triangles than
 * an ordinary one. The result is only defined for polygons that are simple (the boundary does not
 * cross or touch itself, holes are inside the outer ring and do not overlap): for others the method
 * returns -1 when it gets stuck, and otherwise may return triangles that do not tile the region;
 * {@link #isSimple} tests the first condition exactly. It is O(n^2) in the worst case, which is
 * fine for polygons of hundreds of vertices and slow for hundreds of thousands.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays you pass in are not synchronised.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * float[] square = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};                  // x, y pairs, counter-clockwise
 * double area = Polygons.signedArea(square, 4);                        // 1
 * boolean inside = Polygons.contains(square, 4, 0.5f, 0.5f);
 * int[] triangles = new int[3 * (4 - 2)];
 * int count = Polygons.triangulate(square, 4, triangles);              // 2 triangles, as vertex index triples
 * }</pre>
 */
@Experimental("the set of operations may grow (constrained and Delaunay triangulation, polygon boolean operations)")
public final class Polygons {

    private Polygons() {
    }

    // ---------------------------------------------------------------- orientation, area, containment

    /**
     * Computes the signed area of a polygon with the shoelace formula; the sign tells the winding,
     * and the polygon must not self-intersect for the value to be an area.
     *
     * <p>Computed in double; for a self-intersecting polygon it is the signed sum of its loops.
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param count the number of elements
     * @return the signed area of a 2D polygon: positive when it is counter-clockwise
     */
    public static double signedArea(float[] xy, int count) {
        checkPolygon(xy, count, 2);
        double sum = 0.0;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            sum += ((double) xy[2 * j] * xy[2 * i + 1]) - ((double) xy[2 * i] * xy[2 * j + 1]);
        }
        return sum * 0.5;
    }

    /**
     * Classifies the winding direction of a polygon from the sign of its area.
     *
     * <p>Decided exactly with the orientation at the lowest-leftmost vertex, which is always a
     * convex vertex of a simple polygon, so it does not depend on the rounding of an area sum.
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param count the number of elements
     * @return the winding of a simple 2D polygon: 1 counter-clockwise, -1 clockwise, 0 when it has
     *     no area (all vertices collinear, or fewer than three)
     */
    public static int winding(float[] xy, int count) {
        checkPolygon(xy, count, 2);
        if (count < 3) {
            return 0;
        }
        int lowest = 0;
        for (int i = 1; i < count; i++) {
            if (xy[2 * i + 1] < xy[2 * lowest + 1] || (xy[2 * i + 1] == xy[2 * lowest + 1] && xy[2 * i] < xy[2 * lowest])) {
                lowest = i;
            }
        }
        // the neighbours that differ from the lowest vertex (a repeated vertex is skipped)
        int prev = lowest, next = lowest;
        for (int k = 0; k < count && same(xy, prev, lowest); k++) {
            prev = (prev + count - 1) % count;
        }
        for (int k = 0; k < count && same(xy, next, lowest); k++) {
            next = (next + 1) % count;
        }
        double o = Predicates.orient2d(xy[2 * prev], xy[2 * prev + 1], xy[2 * lowest], xy[2 * lowest + 1], xy[2 * next], xy[2 * next + 1]);
        if (o != 0.0) {
            return o > 0.0 ? 1 : -1;
        }
        double area = signedArea(xy, count);
        return area > 0 ? 1 : area < 0 ? -1 : 0;
    }

    private static boolean same(float[] xy, int a, int b) {
        return xy[2 * a] == xy[2 * b] && xy[2 * a + 1] == xy[2 * b + 1];
    }

    /**
     * Returns whether the point is inside a 2D polygon or on its boundary, by the nonzero winding
     * rule (for a simple polygon every point of the interior has winding &plusmn;1).
     *
     * <p>The crossing decisions are exact.
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param count the number of elements
     * @param px the x coordinate of the point
     * @param py the y coordinate of the point
     * @return {@code true} if the point is inside a 2D polygon or on its boundary, by the nonzero
     *     winding rule (for a simple polygon every point of the interior has winding &plusmn;1)
     */
    public static boolean contains(float[] xy, int count, float px, float py) {
        checkPolygon(xy, count, 2);
        int wn = 0;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double ax = xy[2 * j], ay = xy[2 * j + 1], bx = xy[2 * i], by = xy[2 * i + 1];
            double o = Predicates.orient2d(ax, ay, bx, by, px, py);
            if (o == 0.0 && Math.min(ax, bx) <= px && px <= Math.max(ax, bx) && Math.min(ay, by) <= py && py <= Math.max(ay, by)) {
                return true; // on an edge
            }
            if (ay <= py) {
                if (by > py && o > 0.0) {
                    wn++;
                }
            } else if (by <= py && o < 0.0) {
                wn--;
            }
        }
        return wn != 0;
    }

    /**
     * Returns whether the polygon is simple: no two edges that are not neighbours share a point,
     * and neighbouring edges meet only at their common vertex (so no repeated vertex and no vertex
     * touching an edge).
     *
     * <p>The test is exact and O(n^2).
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param count the number of elements
     * @return {@code true} if the polygon is simple: no two edges that are not neighbours share a
     *     point, and neighbouring edges meet only at their common vertex (so no repeated vertex and
     *     no vertex touching an edge)
     */
    public static boolean isSimple(float[] xy, int count) {
        checkPolygon(xy, count, 2);
        if (count < 3) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            int i2 = (i + 1) % count;
            int i3 = (i + 2) % count;
            // neighbouring edges i-i2 and i2-i3 may share only i2: no repeated vertex, no spike folding back along the same line
            if (same(xy, i, i2) || (Predicates.orient2d(xy[2 * i], xy[2 * i + 1], xy[2 * i2], xy[2 * i2 + 1], xy[2 * i3], xy[2 * i3 + 1]) == 0.0
                    && dot(xy, i2, i, i3) > 0.0)) {
                return false;
            }
            for (int j = i + 1; j < count; j++) {
                int j2 = (j + 1) % count;
                if (j == i2 || j2 == i) {
                    continue; // neighbours, handled above
                }
                if (segmentsIntersect(xy, i, i2, j, j2)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double dot(float[] xy, int o, int a, int b) {
        return ((double) xy[2 * a] - xy[2 * o]) * ((double) xy[2 * b] - xy[2 * o]) + ((double) xy[2 * a + 1] - xy[2 * o + 1]) * ((double) xy[2 * b + 1] - xy[2 * o + 1]);
    }

    private static boolean segmentsIntersect(float[] p, int a, int b, int c, int d) {
        double o1 = Predicates.orient2d(p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1], p[2 * c], p[2 * c + 1]);
        double o2 = Predicates.orient2d(p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1], p[2 * d], p[2 * d + 1]);
        double o3 = Predicates.orient2d(p[2 * c], p[2 * c + 1], p[2 * d], p[2 * d + 1], p[2 * a], p[2 * a + 1]);
        double o4 = Predicates.orient2d(p[2 * c], p[2 * c + 1], p[2 * d], p[2 * d + 1], p[2 * b], p[2 * b + 1]);
        if (((o1 > 0 && o2 < 0) || (o1 < 0 && o2 > 0)) && ((o3 > 0 && o4 < 0) || (o3 < 0 && o4 > 0))) {
            return true;
        }
        return (o1 == 0.0 && onSegment(p, a, b, c)) || (o2 == 0.0 && onSegment(p, a, b, d)) || (o3 == 0.0 && onSegment(p, c, d, a)) || (o4 == 0.0 && onSegment(p, c, d, b));
    }

    /**
     * Whether point {@code q}, known to be collinear with {@code a-b}, lies within the segment.
     */
    private static boolean onSegment(float[] p, int a, int b, int q) {
        return Math.min(p[2 * a], p[2 * b]) <= p[2 * q] && p[2 * q] <= Math.max(p[2 * a], p[2 * b])
                && Math.min(p[2 * a + 1], p[2 * b + 1]) <= p[2 * q + 1] && p[2 * q + 1] <= Math.max(p[2 * a + 1], p[2 * b + 1]);
    }

    private static void checkPolygon(float[] a, int count, int stride) {
        if (count < 0 || (long) count * stride > a.length) {
            throw new IllegalArgumentException("count " + count + " does not fit in an array of " + a.length + " floats");
        }
    }

    // ---------------------------------------------------------------- triangulation

    /**
     * Triangulates a simple 2D polygon (either winding) by ear clipping and writes the triangles as
     * vertex index triples, counter-clockwise, to {@code out}.
     *
     * <p>Returns the number of triangles, at most {@code count - 2}; {@code out} needs
     * {@code 3 * (count - 2)} ints. Returns -1 when the polygon cannot be triangulated (fewer than
     * three vertices, or no ear can be found because the polygon is not simple or has no area).
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param count the number of elements
     * @param out receives the result
     * @return the number of triangles, at most {@code count - 2}
     */
    public static int triangulate(float[] xy, int count, int[] out) {
        return triangulate(xy, new int[] {count}, out);
    }

    /**
     * Triangulates a polygon with holes.
     *
     * <p>The vertices of all rings are in {@code xy} one after the other, {@code ringEnds[i]} being
     * the vertex index at which ring {@code i} ends (so ring 0, the outer boundary, is vertices
     * {@code 0 .. ringEnds[0] - 1}); the rings may have either winding. The triangles are written
     * to {@code out} as index triples into {@code xy}, counter-clockwise, and their number is
     * returned; {@code out} needs {@code 3 * (total vertices + 2 * holes - 2)} ints. Returns -1
     * when no triangulation was found (see the class comment).
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param ringEnds the ring ends (at least 1 elements)
     * @param out receives the result
     * @return -1 when no triangulation was found (see the class comment)
     */
    public static int triangulate(float[] xy, int[] ringEnds, int[] out) {
        int total = ringEnds[ringEnds.length - 1];
        checkPolygon(xy, total, 2);
        int holes = ringEnds.length - 1;
        Ring r = new Ring(total + 2 * holes + 2, xy);
        int outer = r.build(0, ringEnds[0], true);
        if (outer < 0) {
            return -1;
        }
        if (holes > 0) {
            outer = r.eliminateHoles(ringEnds, outer);
            if (outer < 0) {
                return -1;
            }
        }
        return r.clip(outer, out);
    }

    /**
     * Triangulates a planar polygon in 3D ({@code x, y, z} triples): the polygon is projected along
     * the dominant axis of its Newell normal and triangulated there; the triangles are
     * counter-clockwise seen from the side the Newell normal points to, and index the original
     * vertices.
     *
     * <p>Same contract and return value as the 2D method.
     *
     * @param xyz the three components
     * @param count the number of elements
     * @param out receives the result
     * @return the number of triangles written, or -1 if the polygon cannot be triangulated (same
     *     contract as the 2D method)
     */
    public static int triangulate3(float[] xyz, int count, int[] out) {
        checkPolygon(xyz, count, 3);
        double nx = 0, ny = 0, nz = 0;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double ax = xyz[3 * j], ay = xyz[3 * j + 1], az = xyz[3 * j + 2], bx = xyz[3 * i], by = xyz[3 * i + 1], bz = xyz[3 * i + 2];
            nx += (ay - by) * (az + bz);
            ny += (az - bz) * (ax + bx);
            nz += (ax - bx) * (ay + by);
        }
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        float[] xy = new float[2 * count];
        for (int i = 0; i < count; i++) {
            float x = xyz[3 * i], y = xyz[3 * i + 1], z = xyz[3 * i + 2];
            if (az >= ax && az >= ay) { // drop z; keep the handedness when the normal points to -z
                xy[2 * i] = nz >= 0 ? x : y;
                xy[2 * i + 1] = nz >= 0 ? y : x;
            } else if (ax >= ay) { // drop x
                xy[2 * i] = nx >= 0 ? y : z;
                xy[2 * i + 1] = nx >= 0 ? z : y;
            } else { // drop y
                xy[2 * i] = ny >= 0 ? z : x;
                xy[2 * i + 1] = ny >= 0 ? x : z;
            }
        }
        return triangulate(xy, count, out);
    }

    /**
     * A doubly linked ring of vertices for ear clipping, in arrays; node indices are not vertex
     * indices because bridges duplicate vertices.
     */
    private static final class Ring {
        final float[] xy;
        final int[] vertex;
        final int[] next;
        final int[] prev;
        int nodes;

        Ring(int capacity, float[] xy) {
            this.xy = xy;
            vertex = new int[capacity];
            next = new int[capacity];
            prev = new int[capacity];
        }

        double x(int n) {
            return xy[2 * vertex[n]];
        }

        double y(int n) {
            return xy[2 * vertex[n] + 1];
        }

        /**
         * Orientation of the nodes a, b, c: positive when they turn counter-clockwise (exact).
         */
        double orient(int a, int b, int c) {
            return Predicates.orient2d(x(a), y(a), x(b), y(b), x(c), y(c));
        }

        boolean same(int a, int b) {
            return x(a) == x(b) && y(a) == y(b);
        }

        /**
         * Builds the ring of vertices {@code from .. to - 1} as counter-clockwise ({@code ccw}) or
         * clockwise; returns a node of it, or -1 for fewer than three vertices.
         */
        int build(int from, int to, boolean ccw) {
            if (to - from < 3) {
                return -1;
            }
            double area = 0;
            for (int i = from, j = to - 1; i < to; j = i++) {
                area += ((double) xy[2 * j] * xy[2 * i + 1]) - ((double) xy[2 * i] * xy[2 * j + 1]);
            }
            boolean forward = (area > 0) == ccw;
            int last = -1, first = -1;
            for (int k = 0; k < to - from; k++) {
                int v = forward ? from + k : to - 1 - k;
                int n = nodes++;
                vertex[n] = v;
                if (last < 0) {
                    first = n;
                } else {
                    next[last] = n;
                    prev[n] = last;
                }
                last = n;
            }
            next[last] = first;
            prev[first] = last;
            return filter(first);
        }

        void remove(int n) {
            next[prev[n]] = next[n];
            prev[next[n]] = prev[n];
        }

        /**
         * Removes repeated and collinear vertices; returns a surviving node, or -1 when nothing is
         * left.
         */
        int filter(int start) {
            int p = start;
            boolean again;
            do {
                again = false;
                int from = p;
                do {
                    int nx = next[p];
                    if (nx == p) {
                        return -1;
                    }
                    if (same(p, nx) || (prev[p] != nx && orient(prev[p], p, nx) == 0.0)) {
                        int pp = prev[p];
                        remove(p);
                        p = pp;
                        from = pp;
                        again = true;
                        if (next[p] == p || next[next[p]] == p) {
                            return -1;
                        }
                    } else {
                        p = nx;
                    }
                } while (p != from);
            } while (again);
            return p;
        }

        /**
         * Whether the point (node p) lies in or on the triangle a, b, c, which is
         * counter-clockwise.
         */
        boolean inTriangle(int a, int b, int c, int p) {
            return orient(a, b, p) >= 0.0 && orient(b, c, p) >= 0.0 && orient(c, a, p) >= 0.0;
        }

        boolean isEar(int ear) {
            int a = prev[ear], b = ear, c = next[ear];
            if (orient(a, b, c) <= 0.0) {
                return false;
            }
            for (int p = next[c]; p != a; p = next[p]) {
                if (same(p, a) || same(p, b) || same(p, c)) {
                    continue;
                }
                if (inTriangle(a, b, c, p) && orient(prev[p], p, next[p]) <= 0.0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Clips ears until the ring is gone; returns the triangle count or -1.
         */
        int clip(int start, int[] out) {
            int count = 0;
            int ear = start;
            int stop = ear;
            int remaining = 0;
            for (int p = start; ; ) {
                remaining++;
                p = next[p];
                if (p == start) {
                    break;
                }
            }
            while (remaining > 3) {
                int pr = prev[ear], nx = next[ear];
                if (isEar(ear)) {
                    out[3 * count] = vertex[pr];
                    out[3 * count + 1] = vertex[ear];
                    out[3 * count + 2] = vertex[nx];
                    count++;
                    remove(ear);
                    remaining--;
                    ear = next[nx];
                    stop = next[nx];
                    continue;
                }
                ear = nx;
                if (ear == stop) {
                    // a full turn without an ear: try again after removing repeated and collinear vertices, else give up
                    int filtered = filter(ear);
                    if (filtered < 0) {
                        return -1;
                    }
                    int now = 0;
                    for (int p = filtered; ; ) {
                        now++;
                        p = next[p];
                        if (p == filtered) {
                            break;
                        }
                    }
                    if (now == remaining) {
                        return -1;
                    }
                    remaining = now;
                    ear = filtered;
                    stop = filtered;
                }
            }
            if (remaining == 3) {
                int a = prev[ear], c = next[ear];
                if (orient(a, ear, c) > 0.0) {
                    out[3 * count] = vertex[a];
                    out[3 * count + 1] = vertex[ear];
                    out[3 * count + 2] = vertex[c];
                    count++;
                }
            }
            return count;
        }

        // ---- holes (Eberly's bridge construction, as in earcut)

        int eliminateHoles(int[] ringEnds, int outer) {
            int holes = ringEnds.length - 1;
            int[] leftmost = new int[holes];
            Integer[] order = new Integer[holes];
            int count = 0;
            for (int h = 0; h < holes; h++) {
                int node = build(ringEnds[h], ringEnds[h + 1], false);
                if (node < 0) {
                    continue; // a hole with fewer than three vertices or no area is ignored
                }
                int lm = node;
                for (int p = next[node]; p != node; p = next[p]) {
                    if (x(p) < x(lm) || (x(p) == x(lm) && y(p) < y(lm))) {
                        lm = p;
                    }
                }
                leftmost[count] = lm;
                order[count] = count;
                count++;
            }
            Arrays.sort(order, 0, count, (p, q) -> Double.compare(x(leftmost[p]), x(leftmost[q])));
            for (int k = 0; k < count; k++) {
                int hole = leftmost[order[k]];
                int bridge = findBridge(hole, outer);
                if (bridge < 0) {
                    return -1;
                }
                int b2 = split(bridge, hole);
                outer = filter(b2);
                if (outer < 0) {
                    return -1;
                }
            }
            return outer;
        }

        /**
         * Splits the ring at nodes a and b by two coincident bridge edges; returns the copy of b.
         */
        int split(int a, int b) {
            int a2 = nodes++, b2 = nodes++;
            vertex[a2] = vertex[a];
            vertex[b2] = vertex[b];
            int an = next[a], bp = prev[b];
            next[a] = b;
            prev[b] = a;
            next[a2] = an;
            prev[an] = a2;
            next[b2] = a2;
            prev[a2] = b2;
            next[bp] = b2;
            prev[b2] = bp;
            return b2;
        }

        /**
         * A node of the outer ring that the leftmost vertex of the hole can see: the vertex of the
         * nearest edge hit by a ray to the left, or a reflex vertex in front of it.
         */
        int findBridge(int hole, int outer) {
            double hx = x(hole), hy = y(hole);
            double qx = Double.NEGATIVE_INFINITY;
            int m = -1;
            int p = outer;
            do {
                if (hy <= y(p) && hy >= y(next[p]) && y(next[p]) != y(p)) {
                    double xi = x(p) + (hy - y(p)) * (x(next[p]) - x(p)) / (y(next[p]) - y(p));
                    if (xi <= hx && xi > qx) {
                        qx = xi;
                        m = x(p) < x(next[p]) ? p : next[p];
                        if (xi == hx) {
                            return m; // the hole vertex touches this edge
                        }
                    }
                }
                p = next[p];
            } while (p != outer);
            if (m < 0) {
                return -1;
            }
            // look for reflex vertices inside the triangle (hole, intersection, m); the one with the smallest angle to the ray wins
            int stop = m;
            double mx = x(m), my = y(m);
            double tanMin = Double.POSITIVE_INFINITY;
            p = m;
            do {
                if (hx >= x(p) && x(p) >= mx && hx != x(p) && inTriangleXY(hy < my ? hx : qx, hy, mx, my, hy < my ? qx : hx, hy, x(p), y(p))) {
                    double tan = Math.abs(hy - y(p)) / (hx - x(p));
                    if ((tan < tanMin || (tan == tanMin && x(p) > x(m))) && locallyInside(p, hole)) {
                        m = p;
                        tanMin = tan;
                    }
                }
                p = next[p];
            } while (p != stop);
            return m;
        }

        boolean inTriangleXY(double ax, double ay, double bx, double by, double cx, double cy, double px, double py) {
            double o1 = Predicates.orient2d(ax, ay, bx, by, px, py);
            double o2 = Predicates.orient2d(bx, by, cx, cy, px, py);
            double o3 = Predicates.orient2d(cx, cy, ax, ay, px, py);
            return (o1 >= 0 && o2 >= 0 && o3 >= 0) || (o1 <= 0 && o2 <= 0 && o3 <= 0);
        }

        /**
         * Whether the diagonal from node a towards node b starts inside the polygon at a.
         */
        boolean locallyInside(int a, int b) {
            return orient(prev[a], a, next[a]) > 0.0
                    ? orient(a, b, next[a]) <= 0.0 && orient(a, prev[a], b) <= 0.0
                    : orient(a, b, prev[a]) > 0.0 || orient(a, next[a], b) > 0.0;
        }
    }

    // ---------------------------------------------------------------- clipping

    /**
     * Clips a 2D polygon against the half-plane {@code a x + b y + c >= 0} (Sutherland-Hodgman) and
     * returns the number of vertices written to {@code out}, which needs room for {@code count + 1}
     * vertices (2 floats each).
     *
     * <p>The polygon may be concave; the part on the positive side may then be joined by degenerate
     * edges along the clip line. A vertex exactly on the line counts as inside. The side of each
     * vertex is computed in double.
     *
     * @param xy the vertices as {@code x, y} pairs
     * @param count the number of elements
     * @param a the coefficient of x in the half-plane equation
     * @param b the coefficient of y in the half-plane equation
     * @param c the constant term of the half-plane equation
     * @param out receives the result
     * @return the number of vertices written to {@code out}, which needs room for {@code count + 1}
     *     vertices (2 floats each)
     */
    public static int clipHalfPlane(float[] xy, int count, float a, float b, float c, float[] out) {
        checkPolygon(xy, count, 2);
        int n = 0;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double dj = (double) a * xy[2 * j] + (double) b * xy[2 * j + 1] + c;
            double di = (double) a * xy[2 * i] + (double) b * xy[2 * i + 1] + c;
            if ((dj >= 0) != (di >= 0) && dj != 0 && di != 0) { // a vertex exactly on the line is inside and is emitted by itself
                double t = dj / (dj - di);
                out[2 * n] = (float) (xy[2 * j] + t * ((double) xy[2 * i] - xy[2 * j]));
                out[2 * n + 1] = (float) (xy[2 * j + 1] + t * ((double) xy[2 * i + 1] - xy[2 * j + 1]));
                n++;
            }
            if (di >= 0) {
                out[2 * n] = xy[2 * i];
                out[2 * n + 1] = xy[2 * i + 1];
                n++;
            }
        }
        return n;
    }

    /**
     * Clips a 2D polygon against a <b>convex</b> clip polygon (either winding) by
     * Sutherland-Hodgman, and returns the number of vertices written to {@code out}, which needs
     * room for {@code count + clipCount} vertices (2 floats each); the result is empty (0) when
     * they do not overlap.
     *
     * <p>With a concave subject polygon the output can contain degenerate edges along the clip
     * boundary (the standard limitation of the algorithm). The inside tests use the exact
     * orientation of the clip edges; the intersection points are computed in double and rounded.
     *
     * @param subject the subject
     * @param count the number of elements
     * @param clip the clip
     * @param clipCount the clip count
     * @param out receives the result
     * @return the number of vertices written to {@code out}, which needs room for
     *     {@code count + clipCount} vertices (2 floats each)
     */
    public static int clipConvex(float[] subject, int count, float[] clip, int clipCount, float[] out) {
        checkPolygon(subject, count, 2);
        checkPolygon(clip, clipCount, 2);
        if (count == 0 || clipCount < 3) {
            return 0;
        }
        int ccw = winding(clip, clipCount);
        if (ccw == 0) {
            return 0;
        }
        float[] in = Arrays.copyOf(subject, 2 * (count + clipCount + 1));
        float[] work = new float[in.length];
        int n = count;
        for (int e = 0; e < clipCount && n > 0; e++) {
            int e2 = (e + 1) % clipCount;
            double ax = clip[2 * e], ay = clip[2 * e + 1], bx = clip[2 * e2], by = clip[2 * e2 + 1];
            int m = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double side_j = ccw * Predicates.orient2d(ax, ay, bx, by, in[2 * j], in[2 * j + 1]);
                double side_i = ccw * Predicates.orient2d(ax, ay, bx, by, in[2 * i], in[2 * i + 1]);
                if ((side_j >= 0) != (side_i >= 0) && side_j != 0 && side_i != 0) {
                    // intersection of the subject edge j-i with the clip line, computed from the (double) side values of the exact predicate
                    double t = side_j / (side_j - side_i);
                    work[2 * m] = (float) (in[2 * j] + t * ((double) in[2 * i] - in[2 * j]));
                    work[2 * m + 1] = (float) (in[2 * j + 1] + t * ((double) in[2 * i + 1] - in[2 * j + 1]));
                    m++;
                }
                if (side_i >= 0) {
                    work[2 * m] = in[2 * i];
                    work[2 * m + 1] = in[2 * i + 1];
                    m++;
                }
            }
            float[] t = in;
            in = work;
            work = t;
            n = m;
        }
        System.arraycopy(in, 0, out, 0, 2 * n);
        return n;
    }

    /**
     * Clips a 3D polygon against the half-space {@code a x + b y + c z + d >= 0} and returns the
     * number of vertices written to {@code out}, which needs room for {@code count + 1} vertices (3
     * floats each).
     *
     * <p>Vertices on the plane count as inside.
     *
     * @param xyz the three components
     * @param count the number of elements
     * @param a the coefficient of x in the plane equation
     * @param b the coefficient of y in the plane equation
     * @param c the coefficient of z in the plane equation
     * @param d the constant term of the plane equation
     * @param out receives the result
     * @return the number of vertices written to {@code out}, which needs room for {@code count + 1}
     *     vertices (3 floats each)
     */
    public static int clipPlane3(float[] xyz, int count, float a, float b, float c, float d, float[] out) {
        checkPolygon(xyz, count, 3);
        int n = 0;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            double dj = (double) a * xyz[3 * j] + (double) b * xyz[3 * j + 1] + (double) c * xyz[3 * j + 2] + d;
            double di = (double) a * xyz[3 * i] + (double) b * xyz[3 * i + 1] + (double) c * xyz[3 * i + 2] + d;
            if ((dj >= 0) != (di >= 0) && dj != 0 && di != 0) { // a vertex exactly on the line is inside and is emitted by itself
                double t = dj / (dj - di);
                for (int k = 0; k < 3; k++) {
                    out[3 * n + k] = (float) (xyz[3 * j + k] + t * ((double) xyz[3 * i + k] - xyz[3 * j + k]));
                }
                n++;
            }
            if (di >= 0) {
                System.arraycopy(xyz, 3 * i, out, 3 * n, 3);
                n++;
            }
        }
        return n;
    }
}
