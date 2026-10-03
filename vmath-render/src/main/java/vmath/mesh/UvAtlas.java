package vmath.mesh;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import vmath.annotations.Experimental;

/**
 * A chart-based planar unwrap that gives a mesh a second UV set with no overlap, for lightmaps,
 * baked AO and decals: triangles are grouped into charts of similar orientation, each chart is
 * projected flat onto its own plane, and the charts are packed into one {@code [0, 1]} square at a
 * single texel density.
 *
 * <p><b>What it is and is not.</b> It is a planar projection per chart, not a conformal or
 * distortion-minimising parameterisation (LSCM, ABF++). A chart whose normals stay within the angle
 * limit of its average normal is nearly flat, so the stretch is bounded: a triangle's UV area is
 * between {@code cos(angle)} and 1 times its true area (times the density squared). A sphere or a
 * torus unwrapped with a small angle limit gives many small charts; a box gives six. Charts are
 * built from the triangle adjacency of the <em>welded</em> positions, so a UV or normal seam does
 * not split a chart by itself. The same vertex used by two charts is duplicated (all streams
 * copied); the result lists, for every new vertex, the vertex it came from.
 *
 * <p>Triangles with no area are attached to a neighbouring chart when they have one. A chart that
 * folds over itself when projected (a helical ramp whose normals all stay within the limit) is not
 * detected.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mesh mesh = Primitives.box(1f, 1f, 1f);
 * UvAtlas.Result atlas = UvAtlas.generate(mesh, 1, 45f, 1024, 2);                           // a second UV set without overlaps in [0, 1]
 * float efficiency = atlas.efficiency();
 * }</pre>
 */
@Experimental("a planar chart unwrap only; the chart-growing rule and the result record may change")
public final class UvAtlas {

    private UvAtlas() {
    }

    /**
     * What {@link #generate} did.
     *
     * @param charts number of charts
     * @param resolution the square atlas size in texels that the UVs are normalised by
     * @param texelsPerUnit texels per unit of world length (the same for every chart)
     * @param efficiency fraction of the atlas covered by chart rectangles (without padding)
     * @param verticesBefore vertex count before
     * @param verticesAfter vertex count after splitting at chart borders
     * @param triangleChart chart index of every triangle
     * @param remap for every vertex after, the vertex before it was copied from
     */
    public record Result(int charts, int resolution, float texelsPerUnit, float efficiency, int verticesBefore, int verticesAfter, int[] triangleChart,
                         int[] remap) {
    }

    private record PosKey(int x, int y, int z) {
    }

    /**
     * Writes UV set {@code uvSet} (enabled if needed) so that no two charts overlap and everything
     * lies in {@code [0, 1]}.
     *
     * @param mesh the mesh; must not be {@code null}
     * @param uvSet the uv set
     * @param maxAngleDegrees the largest angle between a triangle's normal and its chart's average
     *     normal, for example 45; smaller means more, flatter charts
     * @param resolution atlas size in texels (a square), for example 1024; it only sets the density
     *     and the padding, not memory
     * @param paddingTexels empty texels around every chart (keeps bilinear filtering and bleeding
     *     off the neighbours), at least 0
     * @return the outcome of the unwrapping, never {@code null}
     * @throws IllegalArgumentException when the charts cannot fit even at the smallest scale
     */
    public static Result generate(Mesh mesh, int uvSet, float maxAngleDegrees, int resolution, int paddingTexels) {
        if (maxAngleDegrees <= 0f || maxAngleDegrees >= 89f) {
            throw new IllegalArgumentException("maxAngleDegrees must be in (0, 89): " + maxAngleDegrees);
        }
        if (resolution < 2 || paddingTexels < 0) {
            throw new IllegalArgumentException("resolution must be at least 2 and padding at least 0");
        }
        mesh.enableUvs(uvSet);
        int tris = mesh.triangleCount();
        int nv = mesh.vertexCount();
        if (tris == 0) {
            return new Result(0, resolution, 0f, 0f, nv, nv, new int[0], identity(nv));
        }
        float[] pos = mesh.positions();
        int[] idx = mesh.indices();

        // welded ids by exact position, so seams between duplicated vertices do not cut charts apart
        int[] weld = new int[nv];
        HashMap<PosKey, Integer> ids = new HashMap<>();
        for (int v = 0; v < nv; v++) {
            PosKey k = new PosKey(Float.floatToIntBits(pos[v * 3] + 0f), Float.floatToIntBits(pos[v * 3 + 1] + 0f), Float.floatToIntBits(pos[v * 3 + 2] + 0f));
            Integer id = ids.get(k);
            if (id == null) {
                id = ids.size();
                ids.put(k, id);
            }
            weld[v] = id;
        }

        // triangle normals (unit) and areas
        double[] nx = new double[tris], ny = new double[tris], nz = new double[tris], area = new double[tris];
        for (int t = 0; t < tris; t++) {
            int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
            double ex = pos[b] - pos[a], ey = pos[b + 1] - pos[a + 1], ez = pos[b + 2] - pos[a + 2];
            double fx = pos[c] - pos[a], fy = pos[c + 1] - pos[a + 1], fz = pos[c + 2] - pos[a + 2];
            double cx = ey * fz - ez * fy, cy = ez * fx - ex * fz, cz = ex * fy - ey * fx;
            double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
            area[t] = 0.5 * len;
            if (len > 0 && Double.isFinite(len)) {
                nx[t] = cx / len;
                ny[t] = cy / len;
                nz[t] = cz / len;
            } else {
                area[t] = 0;
            }
        }

        // edge adjacency: slot = triangle * 3 + corner, chained through the welded edge key
        HashMap<Long, Integer> head = new HashMap<>();
        int[] next = new int[tris * 3];
        long[] slotKey = new long[tris * 3];
        for (int t = 0; t < tris; t++) {
            for (int k = 0; k < 3; k++) {
                int a = weld[idx[t * 3 + k]], b = weld[idx[t * 3 + (k + 1) % 3]];
                long key = (long) Math.min(a, b) * 0x7fffffffL + Math.max(a, b);
                int slot = t * 3 + k;
                slotKey[slot] = key;
                Integer h = head.put(key, slot);
                next[slot] = h == null ? -1 : h;
            }
        }

        // charts by flood fill
        int[] chartOf = new int[tris];
        Arrays.fill(chartOf, -1);
        double cosLimit = Math.cos(Math.toRadians(maxAngleDegrees));
        List<double[]> chartNormals = new ArrayList<>();
        int charts = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int seed = 0; seed < tris; seed++) {
                if (chartOf[seed] >= 0 || (pass == 0 && area[seed] == 0)) {
                    continue;
                }
                int chart = charts++;
                double sx = nx[seed] * area[seed], sy = ny[seed] * area[seed], sz = nz[seed] * area[seed];
                double[] cn = {nx[seed], ny[seed], nz[seed]};
                if (area[seed] == 0) {
                    cn = new double[] {0, 1, 0};
                }
                chartOf[seed] = chart;
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(seed);
                while (!queue.isEmpty()) {
                    int t = queue.poll();
                    for (int k = 0; k < 3; k++) {
                        for (int s = head.get(slotKey[t * 3 + k]); s >= 0; s = next[s]) {
                            int u = s / 3;
                            if (chartOf[u] >= 0) {
                                continue;
                            }
                            boolean ok = area[u] == 0 || nx[u] * cn[0] + ny[u] * cn[1] + nz[u] * cn[2] >= cosLimit;
                            if (ok) {
                                chartOf[u] = chart;
                                if (area[u] > 0) {
                                    sx += nx[u] * area[u];
                                    sy += ny[u] * area[u];
                                    sz += nz[u] * area[u];
                                    double l = Math.sqrt(sx * sx + sy * sy + sz * sz);
                                    if (l > 0) {
                                        cn = new double[] {sx / l, sy / l, sz / l};
                                    }
                                }
                                queue.add(u);
                            }
                        }
                    }
                }
                chartNormals.add(cn);
            }
        }

        // per chart: a frame, the projected vertices, the rotation giving the smallest box
        double[][] frame = new double[charts][];       // t (3), b (3)
        double[] umin = new double[charts], vmin = new double[charts], cw = new double[charts], ch = new double[charts];
        double[] theta = new double[charts];
        double[] chartArea = new double[charts];
        int[] firstTri = new int[charts];
        Arrays.fill(firstTri, -1);
        List<List<Integer>> chartTris = new ArrayList<>();
        for (int c = 0; c < charts; c++) {
            chartTris.add(new ArrayList<>());
        }
        for (int t = 0; t < tris; t++) {
            chartTris.get(chartOf[t]).add(t);
            chartArea[chartOf[t]] += area[t];
        }
        for (int c = 0; c < charts; c++) {
            double[] n = chartNormals.get(c);
            double ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
            double[] axis = ax <= ay && ax <= az ? new double[] {1, 0, 0} : ay <= az ? new double[] {0, 1, 0} : new double[] {0, 0, 1};
            double[] tv = cross(n, axis);
            normalize(tv);
            double[] bv = cross(n, tv);
            frame[c] = new double[] {tv[0], tv[1], tv[2], bv[0], bv[1], bv[2]};
            // the unique vertices of the chart, projected
            List<Integer> verts = new ArrayList<>();
            for (int t : chartTris.get(c)) {
                for (int k = 0; k < 3; k++) {
                    verts.add(idx[t * 3 + k]);
                }
            }
            int m = verts.size();
            double[] pu = new double[m], pv = new double[m];
            for (int i = 0; i < m; i++) {
                int v = verts.get(i) * 3;
                pu[i] = pos[v] * tv[0] + pos[v + 1] * tv[1] + pos[v + 2] * tv[2];
                pv[i] = pos[v] * bv[0] + pos[v + 1] * bv[1] + pos[v + 2] * bv[2];
            }
            double bestArea = Double.MAX_VALUE;
            for (int deg = 0; deg < 90; deg += 5) {
                double th = Math.toRadians(deg), cs = Math.cos(th), sn = Math.sin(th);
                double u0 = Double.MAX_VALUE, u1 = -Double.MAX_VALUE, v0 = Double.MAX_VALUE, v1 = -Double.MAX_VALUE;
                for (int i = 0; i < m; i++) {
                    double ru = cs * pu[i] - sn * pv[i], rv = sn * pu[i] + cs * pv[i];
                    u0 = Math.min(u0, ru);
                    u1 = Math.max(u1, ru);
                    v0 = Math.min(v0, rv);
                    v1 = Math.max(v1, rv);
                }
                double a = Math.max(u1 - u0, 1e-9) * Math.max(v1 - v0, 1e-9);
                if (a < bestArea - 1e-12) {
                    bestArea = a;
                    theta[c] = th;
                    umin[c] = u0;
                    vmin[c] = v0;
                    cw[c] = u1 - u0;
                    ch[c] = v1 - v0;
                }
            }
        }

        // the largest texel density at which every padded chart rectangle packs into the atlas
        double totalArea = 0;
        for (int c = 0; c < charts; c++) {
            totalArea += cw[c] * ch[c];
        }
        double hiScale = Math.sqrt((double) resolution * resolution / Math.max(totalArea, 1e-30));
        double loScale = 0, best = -1;
        int[] rw = new int[charts], rh = new int[charts];
        int[] px = new int[charts], py = new int[charts];
        boolean[] rot = new boolean[charts];
        int[] bx = null, by = null;
        boolean[] brot = null;
        int[] bcw = null, bch = null;
        for (int iter = 0; iter < 30; iter++) {
            double s = iter == 0 ? hiScale : 0.5 * (loScale + hiScale);
            int[] contentW = new int[charts], contentH = new int[charts];
            for (int c = 0; c < charts; c++) {
                contentW[c] = Math.max(1, (int) Math.ceil(cw[c] * s));
                contentH[c] = Math.max(1, (int) Math.ceil(ch[c] * s));
                rw[c] = contentW[c] + 2 * paddingTexels;
                rh[c] = contentH[c] + 2 * paddingTexels;
            }
            if (RectPacker.pack(rw, rh, resolution, resolution, true, px, py, rot)) {
                best = s;
                bx = px.clone();
                by = py.clone();
                brot = rot.clone();
                bcw = contentW;
                bch = contentH;
                loScale = s;
                if (iter == 0) {
                    break;
                }
            } else {
                hiScale = s;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException(charts + " charts do not fit a " + resolution + " texel atlas with padding " + paddingTexels);
        }

        // write the UVs: first use of a vertex keeps its slot, later charts copy it
        boolean[] used = new boolean[nv];
        HashMap<Long, Integer> chartVertex = new HashMap<>();
        List<Integer> remap = new ArrayList<>();
        for (int v = 0; v < nv; v++) {
            remap.add(v);
        }
        int[] newIdx = new int[tris * 3];
        double inv = 1.0 / resolution;
        for (int t = 0; t < tris; t++) {
            int c = chartOf[t];
            double[] f = frame[c];
            double cs = Math.cos(theta[c]), sn = Math.sin(theta[c]);
            for (int k = 0; k < 3; k++) {
                int v = idx[t * 3 + k];
                long key = (long) c * nv + v;
                Integer existing = chartVertex.get(key);
                int target;
                if (existing != null) {
                    target = existing;
                } else {
                    if (!used[v]) {
                        used[v] = true;
                        target = v;
                    } else {
                        target = mesh.copyVertex(v);
                        remap.add(v);
                    }
                    chartVertex.put(key, target);
                    double u0 = pos[v * 3] * f[0] + pos[v * 3 + 1] * f[1] + pos[v * 3 + 2] * f[2];
                    double v0 = pos[v * 3] * f[3] + pos[v * 3 + 1] * f[4] + pos[v * 3 + 2] * f[5];
                    double ru = (cs * u0 - sn * v0 - umin[c]) * best, rv = (sn * u0 + cs * v0 - vmin[c]) * best;
                    ru = Math.min(Math.max(ru, 0), bcw[c]);
                    rv = Math.min(Math.max(rv, 0), bch[c]);
                    double tu, tv;
                    if (brot[c]) { // rotated a quarter turn: (a, b) -> (b, contentWidth - a), placed in a rectangle of swapped size
                        tu = bx[c] + paddingTexels + rv;
                        tv = by[c] + paddingTexels + (bcw[c] - ru);
                    } else {
                        tu = bx[c] + paddingTexels + ru;
                        tv = by[c] + paddingTexels + rv;
                    }
                    mesh.setUv(uvSet, target, (float) (tu * inv), (float) (tv * inv));
                }
                newIdx[t * 3 + k] = target;
            }
        }
        System.arraycopy(newIdx, 0, mesh.indices(), 0, tris * 3);

        double covered = 0;
        for (int c = 0; c < charts; c++) {
            covered += (double) bcw[c] * bch[c];
        }
        int[] remapArray = new int[remap.size()];
        for (int i = 0; i < remapArray.length; i++) {
            remapArray[i] = remap.get(i);
        }
        return new Result(charts, resolution, (float) best, (float) (covered / ((double) resolution * resolution)), nv, mesh.vertexCount(), chartOf, remapArray);
    }

    private static int[] identity(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 0) {
            v[0] /= l;
            v[1] /= l;
            v[2] /= l;
        }
    }
}
