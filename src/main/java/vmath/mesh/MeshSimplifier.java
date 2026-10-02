package vmath.mesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import vmath.annotations.Experimental;

/**
 * Triangle-count reduction by edge collapse with quadric error metrics (Garland and Heckbert, "Surface Simplification Using Quadric Error Metrics", 1997).
 *
 * <p><b>How it works.</b> Every vertex carries a quadric: the sum of the squared distances to the planes of the faces around it. Collapsing an edge moves the
 * two ends to the point that minimises the summed quadric (or to an end point or the midpoint when that is ill-posed), and costs the quadric value there.
 * The cheapest collapse is done first, using a priority queue; the topology is that of the <em>welded</em> positions.
 *
 * <p><b>What is protected.</b>
 * <ul>
 *   <li><b>Attribute seams</b>: a position that several vertices share with different normals, UVs or tangents (a UV seam, a hard edge) is never moved or
 *       collapsed, so the attribute discontinuities of the input survive exactly. A heavily seamed mesh (a flat-shaded soup) therefore simplifies poorly;
 *       weld it first, or drop the attributes you do not need.</li>
 *   <li><b>Borders</b>: edges with one face get a penalty plane standing on them, so open boundaries keep their shape; {@code lockBorder} forbids touching
 *       them at all. An interior edge between two border vertices is never collapsed (it would pinch the mesh).</li>
 *   <li><b>Manifoldness</b>: a collapse is refused when it breaks the link condition (it would create a non-manifold edge or a hole) or flips the normal of any
 *       remaining triangle by more than about 78 degrees. A closed manifold mesh stays closed and keeps its Euler characteristic.</li>
 * </ul>
 *
 * <p><b>Attributes of the result.</b> When the mesh has normals, tangents or UVs, the new position is restricted to the edge (an end point or the midpoint) and
 * the survivor's attributes are interpolated to match (normals and tangent directions renormalised); without attributes the quadric minimiser is used. Normals are
 * not recomputed: after a strong reduction recompute them ({@link MeshTools#computeSmoothNormals}) when they should follow the new shape.
 *
 * <p><b>Error.</b> The reported error is the square root of the largest quadric cost of any performed collapse: an estimate in world units of how far the
 * surface moved (summed over the faces around the collapsed vertices), not a guaranteed Hausdorff distance. {@code docs/MESH.md} gives the measured true
 * distance next to the estimate.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the error estimate, the options and the result record may change")
public final class MeshSimplifier {

    private MeshSimplifier() {
    }

    /**
     * What {@link #simplify} did.
     *
     * @param trianglesBefore triangles with nonzero area that went in (zero-area triangles are dropped first)
     * @param trianglesAfter triangles left
     * @param verticesAfter vertices left (unused ones are removed)
     * @param error the estimate described in the class comment, in world units
     * @param collapses number of edge collapses performed
     * @param remap for every vertex after, the vertex of the input it came from (identical duplicates were merged into the first of them; a vertex that
     *              survived a collapse carries the interpolated attributes and possibly a moved position, a vertex that took part in none is unchanged)
     * @param attributes when extra attributes were given: {@code stride} values per vertex after, the mean of the attributes of all the input vertices that were
     *                   merged into it; null otherwise
     */
    public record Result(int trianglesBefore, int trianglesAfter, int verticesAfter, float error, int collapses, int[] remap, float[] attributes) {
    }

    private static final double BORDER_WEIGHT = 100.0;

    /** Wraps an int array so that equal contents are equal keys. */
    private record Bits(int[] v) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Bits b && Arrays.equals(v, b.v);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(v);
        }
    }

    private record Candidate(double cost, int a, int b, int va, int vb) {
    }

    /**
     * Reduces {@code mesh} in place to at most {@code targetTriangles} triangles, stopping early when the cheapest remaining collapse would exceed
     * {@code maxError} (in world units, as the estimate described above) or no legal collapse is left.
     */
    public static Result simplify(Mesh mesh, int targetTriangles, float maxError, boolean lockBorder) {
        return simplify(mesh, targetTriangles, maxError, lockBorder, null);
    }

    /**
     * As {@link #simplify(Mesh, int, float, boolean)}, and additionally the vertices marked in {@code lockedVertices} (one flag per vertex, or null) are
     * never moved or collapsed, like a seam; every vertex that shares a position with a locked one is locked with it. This is how the pieces of a cluster
     * hierarchy keep their shared borders identical.
     */
    public static Result simplify(Mesh mesh, int targetTriangles, float maxError, boolean lockBorder, boolean[] lockedVertices) {
        return simplify(mesh, targetTriangles, maxError, lockBorder, lockedVertices, null, 0, 0f);
    }

    /**
     * As above, with extra per-vertex attributes that the collapses must respect, such as skinning weights: {@code attributes} holds {@code stride} floats per
     * vertex (for skin weights, one weight per joint, so a vertex is a dense vector with zeros for the joints that do not influence it). The cost of a collapse
     * gets the term {@code attributeWeight * n_a n_b / (n_a + n_b) * |mean_a - mean_b|^2}, Ward's clustering criterion: the increase in the summed squared
     * distance of the merged vertices' attributes to their common mean, where {@code n} counts the input vertices already merged into each side. Because it works on
     * means and counts it measures accumulated drift, not just the difference across one edge, so a long chain of small steps cannot hide a large change.
     * {@code attributeWeight} is the squared world distance that a squared unit of attribute difference is worth (0.0625 means that swinging a weight from 0 to 1
     * is worth 0.25 units of position error). The survivors' means come back in {@link Result#attributes()}.
     */
    public static Result simplify(Mesh mesh, int targetTriangles, float maxError, boolean lockBorder, boolean[] lockedVertices, float[] attributes, int stride,
                                  float attributeWeight) {
        if (lockedVertices != null && lockedVertices.length < mesh.vertexCount()) {
            throw new IllegalArgumentException("lockedVertices needs one flag per vertex: " + lockedVertices.length + " < " + mesh.vertexCount());
        }
        if (attributes != null && (stride < 1 || attributes.length < (long) stride * mesh.vertexCount() || !(attributeWeight >= 0f))) {
            throw new IllegalArgumentException("attributes needs stride >= 1, stride floats per vertex and a weight >= 0: stride " + stride + ", length " + attributes.length
                    + ", weight " + attributeWeight);
        }
        return new Run(mesh, lockBorder, lockedVertices, attributes, stride, attributeWeight).run(targetTriangles, (double) maxError * maxError);
    }

    private static final class Run {
        final Mesh mesh;
        final boolean lockBorder;
        int nodes;
        double[] pos;           // node positions
        double[] quad;          // 10 per node
        int[] version;
        boolean[] alive, locked;
        int[] nodeVertex;       // the one vertex of a non-seam node
        int tris;
        int[] tn, tv;           // node and vertex of every triangle corner
        boolean[] triAlive;
        int[][] inc;
        int[] incCount;
        int aliveTris;
        final boolean attributes;

        final boolean[] lockedVertices;
        final float[] attr;
        final int stride;
        final double attrWeight;
        double[] mean;          // per node: the mean attribute vector of the input vertices merged into it
        int[] members;          // per node: how many
        int[] nodeOfVertex;
        float[] outAttributes;

        Run(Mesh mesh, boolean lockBorder, boolean[] lockedVertices, float[] attr, int stride, float attrWeight) {
            this.mesh = mesh;
            this.lockBorder = lockBorder;
            this.lockedVertices = lockedVertices;
            this.attr = attr;
            this.stride = stride;
            this.attrWeight = attrWeight;
            boolean any = mesh.hasNormals() || mesh.hasTangents();
            for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
                any |= mesh.hasUvs(s);
            }
            this.attributes = any;
        }

        Result run(int target, double maxCost) {
            build();
            int before = aliveTris;
            PriorityQueue<Candidate> heap = new PriorityQueue<>((x, y) -> Double.compare(x.cost, y.cost));
            HashSet<Long> seen = new HashSet<>();
            for (int t = 0; t < tris; t++) {
                if (!triAlive[t]) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    int a = tn[t * 3 + k], b = tn[t * 3 + (k + 1) % 3];
                    long key = (long) Math.min(a, b) * nodes + Math.max(a, b);
                    if (seen.add(key)) {
                        push(heap, a, b);
                    }
                }
            }
            double worst = 0;
            int collapses = 0;
            while (aliveTris > target && !heap.isEmpty()) {
                Candidate c = heap.poll();
                if (!alive[c.a] || !alive[c.b] || version[c.a] != c.va || version[c.b] != c.vb) {
                    continue;
                }
                if (c.cost > maxCost) {
                    break;
                }
                double[] p = new double[3];
                double cost = evaluate(c.a, c.b, p);
                if (Double.isNaN(cost) || cost > maxCost) {
                    continue;
                }
                if (!legal(c.a, c.b, p)) {
                    continue;
                }
                worst = Math.max(worst, cost);
                collapse(c.a, c.b, p);
                collapses++;
                int s = c.b;
                for (int i = 0; i < incCount[s]; i++) {
                    int t = inc[s][i];
                    for (int k = 0; k < 3; k++) {
                        int o = tn[t * 3 + k];
                        if (o != s) {
                            push(heap, s, o);
                        }
                    }
                }
            }
            int[] remap = finish();
            return new Result(before, aliveTris, mesh.vertexCount(), (float) Math.sqrt(worst), collapses, remap, outAttributes);
        }

        // ---------------------------------------------------------------- setup

        void build() {
            int nv = mesh.vertexCount();
            float[] p = mesh.positions();
            int[] idx = mesh.indices();
            int uvSets = 0;
            for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
                if (mesh.hasUvs(s)) {
                    uvSets++;
                }
            }
            // group vertices by exact position (nodes), and inside a node by exact attributes (distinct vertices)
            int[] node = new int[nv], canon = new int[nv];
            HashMap<Bits, Integer> posIds = new HashMap<>(), attrIds = new HashMap<>();
            List<Integer> distinct = new ArrayList<>();
            for (int v = 0; v < nv; v++) {
                Bits pk = new Bits(new int[] {Float.floatToIntBits(p[v * 3] + 0f), Float.floatToIntBits(p[v * 3 + 1] + 0f), Float.floatToIntBits(p[v * 3 + 2] + 0f)});
                Integer id = posIds.get(pk);
                if (id == null) {
                    id = posIds.size();
                    posIds.put(pk, id);
                    distinct.add(0);
                }
                node[v] = id;
                int[] key = attributeKey(v, id);
                Integer c = attrIds.get(new Bits(key));
                if (c == null) {
                    attrIds.put(new Bits(key), v);
                    canon[v] = v;
                    distinct.set(id, distinct.get(id) + 1);
                } else {
                    canon[v] = c;
                }
            }
            nodes = posIds.size();
            pos = new double[nodes * 3];
            quad = new double[nodes * 10];
            version = new int[nodes];
            alive = new boolean[nodes];
            locked = new boolean[nodes];
            nodeVertex = new int[nodes];
            Arrays.fill(nodeVertex, -1);
            for (int v = 0; v < nv; v++) {
                int g = node[v];
                if (!alive[g]) {
                    alive[g] = true;
                    pos[g * 3] = p[v * 3];
                    pos[g * 3 + 1] = p[v * 3 + 1];
                    pos[g * 3 + 2] = p[v * 3 + 2];
                }
                if (lockedVertices != null && lockedVertices[v]) {
                    locked[g] = true;
                }
                if (distinct.get(g) > 1) {
                    locked[g] = true;
                } else if (nodeVertex[g] < 0) {
                    nodeVertex[g] = canon[v];
                }
            }
            nodeOfVertex = node;
            if (attr != null) {
                mean = new double[nodes * stride];
                members = new int[nodes];
                for (int g = 0; g < nodes; g++) {
                    if (!locked[g] && nodeVertex[g] >= 0) {
                        members[g] = 1;
                        for (int k = 0; k < stride; k++) {
                            mean[g * stride + k] = attr[nodeVertex[g] * stride + k];
                        }
                    }
                }
            }
            // triangles, dropping those with no area
            int inTris = mesh.triangleCount();
            tn = new int[inTris * 3];
            tv = new int[inTris * 3];
            triAlive = new boolean[inTris];
            inc = new int[nodes][];
            incCount = new int[nodes];
            for (int t = 0; t < inTris; t++) {
                int a = idx[t * 3], b = idx[t * 3 + 1], c = idx[t * 3 + 2];
                int na = node[a], nb = node[b], nc = node[c];
                if (na == nb || nb == nc || na == nc) {
                    continue;
                }
                double[] n = faceNormal(na, nb, nc);
                double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                if (!(len > 0) || !Double.isFinite(len)) {
                    continue;
                }
                int q = tris++;
                tn[q * 3] = na;
                tn[q * 3 + 1] = nb;
                tn[q * 3 + 2] = nc;
                tv[q * 3] = canon[a];
                tv[q * 3 + 1] = canon[b];
                tv[q * 3 + 2] = canon[c];
                triAlive[q] = true;
                aliveTris++;
                for (int k = 0; k < 3; k++) {
                    addIncident(tn[q * 3 + k], q);
                }
                // the face plane, added to its three nodes
                double nx = n[0] / len, ny = n[1] / len, nz = n[2] / len;
                double d = -(nx * pos[na * 3] + ny * pos[na * 3 + 1] + nz * pos[na * 3 + 2]);
                for (int k = 0; k < 3; k++) {
                    addPlane(tn[q * 3 + k], nx, ny, nz, d, 1.0);
                }
            }
            // border edges get a penalty plane through the edge, perpendicular to the face
            for (int t = 0; t < tris; t++) {
                if (!triAlive[t]) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    int a = tn[t * 3 + k], b = tn[t * 3 + (k + 1) % 3];
                    if (edgeTriangles(a, b) == 1) {
                        double[] n = faceNormal(tn[t * 3], tn[t * 3 + 1], tn[t * 3 + 2]);
                        double nl = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                        double ex = pos[b * 3] - pos[a * 3], ey = pos[b * 3 + 1] - pos[a * 3 + 1], ez = pos[b * 3 + 2] - pos[a * 3 + 2];
                        double mx = ey * n[2] / nl - ez * n[1] / nl, my = ez * n[0] / nl - ex * n[2] / nl, mz = ex * n[1] / nl - ey * n[0] / nl;
                        double ml = Math.sqrt(mx * mx + my * my + mz * mz);
                        if (ml > 0) {
                            double el2 = ex * ex + ey * ey + ez * ez;
                            mx /= ml;
                            my /= ml;
                            mz /= ml;
                            double d = -(mx * pos[a * 3] + my * pos[a * 3 + 1] + mz * pos[a * 3 + 2]);
                            addPlane(a, mx, my, mz, d, BORDER_WEIGHT * el2);
                            addPlane(b, mx, my, mz, d, BORDER_WEIGHT * el2);
                        }
                    }
                }
            }
        }

        int[] attributeKey(int v, int nodeId) {
            List<Integer> key = new ArrayList<>();
            key.add(nodeId);
            if (mesh.hasNormals()) {
                for (int k = 0; k < 3; k++) {
                    key.add(Float.floatToIntBits(mesh.normals()[v * 3 + k] + 0f));
                }
            }
            if (mesh.hasTangents()) {
                for (int k = 0; k < 4; k++) {
                    key.add(Float.floatToIntBits(mesh.tangents()[v * 4 + k] + 0f));
                }
            }
            for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
                if (mesh.hasUvs(s)) {
                    key.add(Float.floatToIntBits(mesh.uvs(s)[v * 2] + 0f));
                    key.add(Float.floatToIntBits(mesh.uvs(s)[v * 2 + 1] + 0f));
                }
            }
            int[] r = new int[key.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = key.get(i);
            }
            return r;
        }

        double[] faceNormal(int a, int b, int c) {
            double ex = pos[b * 3] - pos[a * 3], ey = pos[b * 3 + 1] - pos[a * 3 + 1], ez = pos[b * 3 + 2] - pos[a * 3 + 2];
            double fx = pos[c * 3] - pos[a * 3], fy = pos[c * 3 + 1] - pos[a * 3 + 1], fz = pos[c * 3 + 2] - pos[a * 3 + 2];
            return new double[] {ey * fz - ez * fy, ez * fx - ex * fz, ex * fy - ey * fx};
        }

        void addIncident(int n, int t) {
            if (inc[n] == null) {
                inc[n] = new int[6];
            } else if (incCount[n] == inc[n].length) {
                inc[n] = Arrays.copyOf(inc[n], incCount[n] * 2);
            }
            inc[n][incCount[n]++] = t;
        }

        void removeIncident(int n, int t) {
            for (int i = 0; i < incCount[n]; i++) {
                if (inc[n][i] == t) {
                    inc[n][i] = inc[n][--incCount[n]];
                    return;
                }
            }
        }

        void addPlane(int n, double a, double b, double c, double d, double w) {
            int q = n * 10;
            quad[q] += w * a * a;
            quad[q + 1] += w * a * b;
            quad[q + 2] += w * a * c;
            quad[q + 3] += w * a * d;
            quad[q + 4] += w * b * b;
            quad[q + 5] += w * b * c;
            quad[q + 6] += w * b * d;
            quad[q + 7] += w * c * c;
            quad[q + 8] += w * c * d;
            quad[q + 9] += w * d * d;
        }

        /** Number of live triangles that contain both nodes. */
        int edgeTriangles(int a, int b) {
            int n = 0;
            for (int i = 0; i < incCount[a]; i++) {
                int t = inc[a][i];
                if (triAlive[t] && (tn[t * 3] == b || tn[t * 3 + 1] == b || tn[t * 3 + 2] == b)) {
                    n++;
                }
            }
            return n;
        }

        boolean isBorder(int n) {
            for (int i = 0; i < incCount[n]; i++) {
                int t = inc[n][i];
                for (int k = 0; k < 3; k++) {
                    int o = tn[t * 3 + k];
                    if (o != n && edgeTriangles(n, o) == 1) {
                        return true;
                    }
                }
            }
            return false;
        }

        static double eval(double[] q, int o, double x, double y, double z) {
            return q[o] * x * x + 2 * q[o + 1] * x * y + 2 * q[o + 2] * x * z + 2 * q[o + 3] * x + q[o + 4] * y * y + 2 * q[o + 5] * y * z + 2 * q[o + 6] * y
                    + q[o + 7] * z * z + 2 * q[o + 8] * z + q[o + 9];
        }

        // ---------------------------------------------------------------- candidates

        void push(PriorityQueue<Candidate> heap, int a, int b) {
            double[] p = new double[3];
            double cost = evaluate(a, b, p);
            if (!Double.isNaN(cost)) {
                heap.add(new Candidate(cost, a, b, version[a], version[b]));
            }
        }

        /** The cost of collapsing {@code a} and {@code b}, writing the new position to {@code out}; NaN when the edge may not be collapsed. */
        double evaluate(int a, int b, double[] out) {
            if (!alive[a] || !alive[b] || locked[a] || locked[b]) {
                return Double.NaN;
            }
            int k = edgeTriangles(a, b);
            if (k == 0 || k > 2) {
                return Double.NaN;
            }
            boolean borderA = isBorder(a), borderB = isBorder(b);
            if ((lockBorder && (borderA || borderB)) || (borderA && borderB && k == 2)) {
                return Double.NaN;
            }
            double[] q = new double[10];
            for (int i = 0; i < 10; i++) {
                q[i] = quad[a * 10 + i] + quad[b * 10 + i];
            }
            if (borderA != borderB) {
                int keep = borderA ? a : b;
                out[0] = pos[keep * 3];
                out[1] = pos[keep * 3 + 1];
                out[2] = pos[keep * 3 + 2];
                return Math.max(0, eval(q, 0, out[0], out[1], out[2])) + ward(a, b);
            }
            double best = Double.MAX_VALUE;
            double[][] cand = new double[4][];
            int count = 0;
            if (!(borderA && borderB) && !attributes) { // with attributes the new position must lie on the edge so they can be interpolated
                double[] sol = solve(q);
                if (sol != null) {
                    cand[count++] = sol;
                }
            }
            cand[count++] = new double[] {pos[a * 3], pos[a * 3 + 1], pos[a * 3 + 2]};
            cand[count++] = new double[] {pos[b * 3], pos[b * 3 + 1], pos[b * 3 + 2]};
            cand[count++] = new double[] {(pos[a * 3] + pos[b * 3]) * 0.5, (pos[a * 3 + 1] + pos[b * 3 + 1]) * 0.5, (pos[a * 3 + 2] + pos[b * 3 + 2]) * 0.5};
            for (int i = 0; i < count; i++) {
                double c = eval(q, 0, cand[i][0], cand[i][1], cand[i][2]);
                if (c < best) {
                    best = c;
                    out[0] = cand[i][0];
                    out[1] = cand[i][1];
                    out[2] = cand[i][2];
                }
            }
            return Math.max(0, best) + ward(a, b);
        }

        /** The attribute term of a collapse (Ward's criterion on the merged means), 0 without attributes. */
        double ward(int a, int b) {
            if (attr == null || attrWeight == 0f) {
                return 0.0;
            }
            double d2 = 0;
            for (int k = 0; k < stride; k++) {
                double d = mean[a * stride + k] - mean[b * stride + k];
                d2 += d * d;
            }
            return attrWeight * ((double) members[a] * members[b] / (members[a] + members[b])) * d2;
        }

        /** The minimiser of the quadric, or null when the 3 x 3 system is (nearly) singular. */
        static double[] solve(double[] q) {
            double a = q[0], b = q[1], c = q[2], d = q[4], e = q[5], f = q[7];
            double det = a * (d * f - e * e) - b * (b * f - c * e) + c * (b * e - c * d);
            double scale = Math.abs(a) + Math.abs(d) + Math.abs(f);
            if (!(Math.abs(det) > 1e-10 * scale * scale * scale)) {
                return null;
            }
            double rx = -q[3], ry = -q[6], rz = -q[8];
            double x = (rx * (d * f - e * e) - b * (ry * f - e * rz) + c * (ry * e - d * rz)) / det;
            double y = (a * (ry * f - e * rz) - rx * (b * f - c * e) + c * (b * rz - c * ry)) / det;
            double z = (a * (d * rz - e * ry) - b * (b * rz - c * ry) + rx * (b * e - c * d)) / det;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return null;
            }
            return new double[] {x, y, z};
        }

        // ---------------------------------------------------------------- legality and collapse

        boolean legal(int a, int b, double[] p) {
            // link condition: the neighbours the two nodes share must be exactly the vertices opposite the edge
            HashSet<Integer> na = new HashSet<>(), nb = new HashSet<>();
            int opposite = 0;
            for (int i = 0; i < incCount[a]; i++) {
                int t = inc[a][i];
                boolean hasB = false;
                for (int k = 0; k < 3; k++) {
                    int o = tn[t * 3 + k];
                    if (o == b) {
                        hasB = true;
                    } else if (o != a) {
                        na.add(o);
                    }
                }
                if (hasB) {
                    opposite++;
                }
            }
            for (int i = 0; i < incCount[b]; i++) {
                int t = inc[b][i];
                for (int k = 0; k < 3; k++) {
                    int o = tn[t * 3 + k];
                    if (o != a && o != b) {
                        nb.add(o);
                    }
                }
            }
            int common = 0;
            for (int o : na) {
                if (nb.contains(o)) {
                    common++;
                }
            }
            if (common != opposite) {
                return false;
            }
            // no surviving triangle may fold over
            for (int pass = 0; pass < 2; pass++) {
                int moving = pass == 0 ? a : b, other = pass == 0 ? b : a;
                for (int i = 0; i < incCount[moving]; i++) {
                    int t = inc[moving][i];
                    int x = tn[t * 3], y = tn[t * 3 + 1], z = tn[t * 3 + 2];
                    if (x == other || y == other || z == other) {
                        continue;
                    }
                    double[] before = faceNormal(x, y, z);
                    double[] save = {pos[moving * 3], pos[moving * 3 + 1], pos[moving * 3 + 2]};
                    pos[moving * 3] = p[0];
                    pos[moving * 3 + 1] = p[1];
                    pos[moving * 3 + 2] = p[2];
                    double[] after = faceNormal(x, y, z);
                    pos[moving * 3] = save[0];
                    pos[moving * 3 + 1] = save[1];
                    pos[moving * 3 + 2] = save[2];
                    double lb = Math.sqrt(before[0] * before[0] + before[1] * before[1] + before[2] * before[2]);
                    double la = Math.sqrt(after[0] * after[0] + after[1] * after[1] + after[2] * after[2]);
                    if (!(la > 1e-12 * Math.max(lb, 1e-30)) || (before[0] * after[0] + before[1] * after[1] + before[2] * after[2]) / (lb * la) < 0.2) {
                        return false;
                    }
                }
            }
            return true;
        }

        /** Merges {@code a} into {@code b} and moves {@code b} to {@code p}. */
        void collapse(int a, int b, double[] p) {
            if (attr != null) {
                int total = members[a] + members[b];
                for (int k = 0; k < stride; k++) {
                    mean[b * stride + k] = (mean[a * stride + k] * members[a] + mean[b * stride + k] * members[b]) / total;
                }
                members[b] = total;
            }
            if (attributes) {
                double dx = pos[a * 3] - pos[b * 3], dy = pos[a * 3 + 1] - pos[b * 3 + 1], dz = pos[a * 3 + 2] - pos[b * 3 + 2];
                double len2 = dx * dx + dy * dy + dz * dz;
                double t = len2 > 0 ? ((p[0] - pos[b * 3]) * dx + (p[1] - pos[b * 3 + 1]) * dy + (p[2] - pos[b * 3 + 2]) * dz) / len2 : 0;
                lerpAttributes(nodeVertex[b], nodeVertex[a], Math.min(Math.max(t, 0), 1));
            }
            int[] list = Arrays.copyOf(inc[a], incCount[a]);
            for (int t : list) {
                if (!triAlive[t]) {
                    continue;
                }
                boolean hasB = tn[t * 3] == b || tn[t * 3 + 1] == b || tn[t * 3 + 2] == b;
                if (hasB) {
                    triAlive[t] = false;
                    aliveTris--;
                    for (int k = 0; k < 3; k++) {
                        int n = tn[t * 3 + k];
                        if (n != a) {
                            removeIncident(n, t);
                        }
                    }
                } else {
                    for (int k = 0; k < 3; k++) {
                        if (tn[t * 3 + k] == a) {
                            tn[t * 3 + k] = b;
                            tv[t * 3 + k] = nodeVertex[b];
                        }
                    }
                    addIncident(b, t);
                }
            }
            incCount[a] = 0;
            alive[a] = false;
            pos[b * 3] = p[0];
            pos[b * 3 + 1] = p[1];
            pos[b * 3 + 2] = p[2];
            for (int i = 0; i < 10; i++) {
                quad[b * 10 + i] += quad[a * 10 + i];
            }
            version[b]++;
            version[a]++;
        }

        /** Moves the attributes of vertex {@code dst} the fraction {@code t} of the way to those of {@code src}. */
        void lerpAttributes(int dst, int src, double t) {
            if (t == 0 || dst < 0 || src < 0) {
                return;
            }
            if (mesh.hasNormals()) {
                lerpUnit(mesh.normals(), dst, src, t, 3);
            }
            if (mesh.hasTangents()) {
                float[] tg = mesh.tangents();
                lerpUnit(tg, dst, src, t, 4);
                tg[dst * 4 + 3] = t < 0.5 ? tg[dst * 4 + 3] : tg[src * 4 + 3];
            }
            for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
                if (mesh.hasUvs(s)) {
                    float[] uv = mesh.uvs(s);
                    for (int k = 0; k < 2; k++) {
                        uv[dst * 2 + k] = (float) (uv[dst * 2 + k] + (uv[src * 2 + k] - uv[dst * 2 + k]) * t);
                    }
                }
            }
        }

        /** Interpolates the first three components of a per-vertex vector stream and renormalises them. */
        static void lerpUnit(float[] a, int dst, int src, double t, int stride) {
            double x = a[dst * stride] + (a[src * stride] - a[dst * stride]) * t;
            double y = a[dst * stride + 1] + (a[src * stride + 1] - a[dst * stride + 1]) * t;
            double z = a[dst * stride + 2] + (a[src * stride + 2] - a[dst * stride + 2]) * t;
            double l = Math.sqrt(x * x + y * y + z * z);
            if (l > 1e-12) {
                x /= l;
                y /= l;
                z /= l;
            } else {
                x = a[dst * stride];
                y = a[dst * stride + 1];
                z = a[dst * stride + 2];
            }
            a[dst * stride] = (float) x;
            a[dst * stride + 1] = (float) y;
            a[dst * stride + 2] = (float) z;
        }

        // ---------------------------------------------------------------- output

        int[] finish() {
            int nv = mesh.vertexCount();
            float[] p = mesh.positions();
            // write the moved positions into the surviving vertices
            for (int n = 0; n < nodes; n++) {
                if (alive[n] && !locked[n] && nodeVertex[n] >= 0) {
                    int v = nodeVertex[n];
                    p[v * 3] = (float) pos[n * 3];
                    p[v * 3 + 1] = (float) pos[n * 3 + 1];
                    p[v * 3 + 2] = (float) pos[n * 3 + 2];
                }
            }
            int[] map = new int[nv];
            Arrays.fill(map, -1);
            int[] newToOld = new int[nv];
            int count = 0;
            int[] out = new int[aliveTris * 3];
            int o = 0;
            for (int t = 0; t < tris; t++) {
                if (!triAlive[t]) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    int v = tv[t * 3 + k];
                    if (map[v] < 0) {
                        map[v] = count;
                        newToOld[count++] = v;
                    }
                    out[o++] = map[v];
                }
            }
            if (attr != null) {
                outAttributes = new float[count * stride];
                for (int i = 0; i < count; i++) {
                    int v = newToOld[i], node = nodeOfVertex[v];
                    for (int k = 0; k < stride; k++) {
                        outAttributes[i * stride + k] = alive[node] && !locked[node] && members[node] > 0 ? (float) mean[node * stride + k] : attr[v * stride + k];
                    }
                }
            }
            MeshOptimizer.rebuild(mesh, newToOld, count, out, o);
            return Arrays.copyOf(newToOld, count);
        }
    }
}
