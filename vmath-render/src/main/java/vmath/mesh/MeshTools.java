package vmath.mesh;

import java.util.Arrays;

/**
 * Normals and tangents for a {@link Mesh}.
 *
 * <p><b>Degenerate input never produces NaN.</b> Triangles with (almost) no area, and triangles
 * whose UVs have no area, are skipped when accumulating. A vertex left with nothing to accumulate
 * gets a fixed fallback: normal {@code (0, 1, 0)}, and a tangent chosen perpendicular to its normal
 * with handedness {@code +1}. Non-finite positions are the caller's problem.
 *
 * <p>All accumulation is done in {@code double}, one pass over the triangles, with a few temporary
 * arrays sized to the mesh.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mesh mesh = Primitives.box(1f, 1f, 1f);
 * MeshTools.computeSmoothNormals(mesh);
 * int[] split = MeshTools.computeNormalsWithCrease(mesh, (float) Math.toRadians(30));        // keeps the creases of a box
 * mesh.enableUvs(0);
 * MeshTools.computeTangents(mesh, 0);
 * }</pre>
 */
public final class MeshTools {

    private MeshTools() {
    }

    /**
     * Triangles whose doubled area is below this (relative to their edge lengths squared) count as
     * degenerate.
     */
    private static final double DEGENERATE = 1e-12;

    // ---------------------------------------------------------------- normals

    /**
     * Sets every vertex normal to the angle-weighted average of the face normals around it, so a
     * triangle contributes to each of its corners in proportion to the angle it has there (which
     * makes the result independent of how a surface is triangulated).
     *
     * <p>The vertex count is unchanged and vertices are shared as they are: use
     * {@link #computeNormalsWithCrease} to keep hard edges.
     *
     * @param mesh the mesh; must not be {@code null}
     */
    public static void computeSmoothNormals(Mesh mesh) {
        mesh.enableNormals();
        int vc = mesh.vertexCount();
        double[] acc = new double[vc * 3];
        float[] p = mesh.positions();
        int[] idx = mesh.indices();
        for (int t = 0; t < mesh.indexCount(); t += 3) {
            accumulate(p, idx[t], idx[t + 1], idx[t + 2], acc);
        }
        writeNormals(mesh, acc);
    }

    /**
     * Splits vertices so that smooth shading is kept on gently curved surfaces and hard edges
     * remain sharp; faces are grouped greedily around each vertex, and the grouping is not
     * transitive.
     *
     * <p>The faces at each vertex are grouped greedily: a group starts at the first unassigned face
     * and takes every face whose normal is within {@code creaseAngle} of that first face's. The
     * group order is deterministic but the grouping is not transitive, which is the usual practical
     * definition of a crease angle.
     *
     * <p>Every other stream (positions, UVs, tangents) is copied to the new vertices, so the
     * surface does not change. Returns {@code remap} with one entry per vertex of the resulting
     * mesh: the index of the original vertex it came from (the first {@code oldCount} entries are
     * the identity), which is what to use to carry any extra per-vertex data of your own along.
     *
     * @param mesh the mesh; must not be {@code null}
     * @param creaseAngle the crease angle
     * @return the remap array with one entry per vertex of the resulting mesh: the index of the
     *     original vertex it came from, where the first entries are the identity
     * @throws IllegalArgumentException if {@code creaseAngle} is negative
     */
    public static int[] computeNormalsWithCrease(Mesh mesh, float creaseAngle) {
        if (!(creaseAngle >= 0f)) {
            throw new IllegalArgumentException("creaseAngle must be >= 0: " + creaseAngle);
        }
        mesh.enableNormals();
        int oldCount = mesh.vertexCount();
        int corners = mesh.indexCount();
        int triangles = corners / 3;
        float[] p = mesh.positions();
        int[] idx = mesh.indices();

        // face normals and the angle at each corner
        double[] fn = new double[triangles * 3];
        double[] cornerAngle = new double[corners];
        boolean[] valid = new boolean[triangles];
        for (int t = 0; t < triangles; t++) {
            valid[t] = faceData(p, idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2], fn, t * 3, cornerAngle, t * 3);
        }

        // corners grouped by vertex (counting sort)
        int[] start = new int[oldCount + 1];
        for (int c = 0; c < corners; c++) {
            start[idx[c] + 1]++;
        }
        for (int v = 0; v < oldCount; v++) {
            start[v + 1] += start[v];
        }
        int[] fill = Arrays.copyOf(start, oldCount);
        int[] cornerOf = new int[corners];
        for (int c = 0; c < corners; c++) {
            cornerOf[fill[idx[c]]++] = c;
        }

        double cosCrease = Math.cos(Math.min(creaseAngle, Math.PI));
        int[] remapOut = new int[oldCount];
        for (int v = 0; v < oldCount; v++) {
            remapOut[v] = v;
        }
        int[] extra = new int[Math.max(16, oldCount / 8)];
        int extras = 0;
        int[] cluster = new int[16];
        for (int v = 0; v < oldCount; v++) {
            int a = start[v], b = start[v + 1];
            int n = b - a;
            if (n == 0) {
                mesh.setNormal(v, 0f, 1f, 0f);
                continue;
            }
            if (cluster.length < n) {
                cluster = new int[Math.max(n, cluster.length * 2)];
            }
            Arrays.fill(cluster, 0, n, -1);
            int groups = 0;
            for (int i = 0; i < n; i++) {
                if (cluster[i] != -1) {
                    continue;
                }
                int seedTri = cornerOf[a + i] / 3;
                cluster[i] = groups;
                if (valid[seedTri]) {
                    for (int j = i + 1; j < n; j++) {
                        int tj = cornerOf[a + j] / 3;
                        if (cluster[j] == -1 && valid[tj]
                                && fn[seedTri * 3] * fn[tj * 3] + fn[seedTri * 3 + 1] * fn[tj * 3 + 1]
                                + fn[seedTri * 3 + 2] * fn[tj * 3 + 2] >= cosCrease - 1e-9) {
                            cluster[j] = groups;
                        }
                    }
                } else {
                    // a degenerate face has no normal: it rides along with the first group it can join
                    for (int j = i + 1; j < n; j++) {
                        if (cluster[j] == -1 && !valid[cornerOf[a + j] / 3]) {
                            cluster[j] = groups;
                        }
                    }
                }
                groups++;
            }
            // vertex v keeps group 0; every further group gets a copy of the vertex
            int[] ids = new int[groups];
            ids[0] = v;
            for (int g = 1; g < groups; g++) {
                ids[g] = mesh.copyVertex(v);
                if (extras == extra.length) {
                    extra = Arrays.copyOf(extra, extras * 2);
                }
                extra[extras++] = v;
            }
            double[] sum = new double[groups * 3];
            for (int i = 0; i < n; i++) {
                int c = cornerOf[a + i];
                int t = c / 3;
                int g = cluster[i];
                if (valid[t]) {
                    double w = cornerAngle[c];
                    sum[g * 3] += w * fn[t * 3];
                    sum[g * 3 + 1] += w * fn[t * 3 + 1];
                    sum[g * 3 + 2] += w * fn[t * 3 + 2];
                }
                if (g > 0) {
                    idx[c] = ids[g]; // idx is the mesh's live array (copyVertex never replaces the index array)
                }
            }
            for (int g = 0; g < groups; g++) {
                setNormal(mesh, ids[g], sum[g * 3], sum[g * 3 + 1], sum[g * 3 + 2]);
            }
            p = mesh.positions(); // copyVertex may have grown the arrays
        }
        int[] remap = Arrays.copyOf(remapOut, oldCount + extras);
        System.arraycopy(extra, 0, remap, oldCount, extras);
        return remap;
    }

    private static void writeNormals(Mesh mesh, double[] acc) {
        for (int v = 0; v < mesh.vertexCount(); v++) {
            setNormal(mesh, v, acc[v * 3], acc[v * 3 + 1], acc[v * 3 + 2]);
        }
    }

    private static void setNormal(Mesh mesh, int v, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len > 1e-20) {
            mesh.setNormal(v, (float) (x / len), (float) (y / len), (float) (z / len));
        } else {
            mesh.setNormal(v, 0f, 1f, 0f);
        }
    }

    // ---------------------------------------------------------------- tangents

    /**
     * Computes tangents for UV set {@code uvSet} from the UV derivatives: for each triangle the
     * direction in which {@code u} increases (the tangent) and in which {@code v} increases (the
     * bitangent), accumulated at the corners with angle weights, then made perpendicular to the
     * vertex normal (Gram-Schmidt).
     *
     * <p>The handedness in {@code w} is {@code +1} when {@code cross(normal, tangent)} points the
     * way {@code v} increases and {@code -1} for mirrored UVs, so the bitangent is
     * {@code cross(normal, tangent) * w}.
     *
     * <p>This is the standard per-vertex accumulation used by most engines, not a bit-exact port of
     * MikkTSpace. Normals and the UV set must exist (call a normals method first). Where UVs are
     * mirrored across a shared vertex the accumulation averages the two sides; split such vertices
     * (a hard UV seam is normally already split) to avoid that.
     *
     * @param mesh the mesh; must not be {@code null}
     * @param uvSet the uv set
     * @throws IllegalStateException if the mesh has no normals or the UV set is not enabled
     */
    public static void computeTangents(Mesh mesh, int uvSet) {
        if (!mesh.hasNormals()) {
            throw new IllegalStateException("normals are needed to compute tangents");
        }
        if (!mesh.hasUvs(uvSet)) {
            throw new IllegalStateException("uv set " + uvSet + " is not enabled");
        }
        mesh.enableTangents();
        int vc = mesh.vertexCount();
        double[] tan = new double[vc * 3];
        double[] bit = new double[vc * 3];
        float[] p = mesh.positions();
        float[] uv = mesh.uvs(uvSet);
        int[] idx = mesh.indices();
        double[] angles = new double[3];
        int[] tri = new int[3];
        for (int t = 0; t < mesh.indexCount(); t += 3) {
            int ia = idx[t], ib = idx[t + 1], ic = idx[t + 2];
            double e1x = p[ib * 3] - p[ia * 3], e1y = p[ib * 3 + 1] - p[ia * 3 + 1], e1z = p[ib * 3 + 2] - p[ia * 3 + 2];
            double e2x = p[ic * 3] - p[ia * 3], e2y = p[ic * 3 + 1] - p[ia * 3 + 1], e2z = p[ic * 3 + 2] - p[ia * 3 + 2];
            double du1 = uv[ib * 2] - uv[ia * 2], dv1 = uv[ib * 2 + 1] - uv[ia * 2 + 1];
            double du2 = uv[ic * 2] - uv[ia * 2], dv2 = uv[ic * 2 + 1] - uv[ia * 2 + 1];
            double det = du1 * dv2 - du2 * dv1;
            if (!(Math.abs(det) > 1e-20)) {
                continue; // the UVs have no area
            }
            double r = 1.0 / det;
            double tx = (e1x * dv2 - e2x * dv1) * r, ty = (e1y * dv2 - e2y * dv1) * r, tz = (e1z * dv2 - e2z * dv1) * r;
            double bx = (e2x * du1 - e1x * du2) * r, by = (e2y * du1 - e1y * du2) * r, bz = (e2z * du1 - e1z * du2) * r;
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz), bl = Math.sqrt(bx * bx + by * by + bz * bz);
            if (!(tl > 1e-20) || !(bl > 1e-20)) {
                continue;
            }
                if (!cornerAngles(p, ia, ib, ic, angles)) {
                continue;
            }
            tri[0] = ia;
            tri[1] = ib;
            tri[2] = ic;
            for (int k = 0; k < 3; k++) {
                int v = tri[k];
                double w = angles[k];
                tan[v * 3] += w * tx / tl;
                tan[v * 3 + 1] += w * ty / tl;
                tan[v * 3 + 2] += w * tz / tl;
                bit[v * 3] += w * bx / bl;
                bit[v * 3 + 1] += w * by / bl;
                bit[v * 3 + 2] += w * bz / bl;
            }
        }
        float[] nrm = mesh.normals();
        for (int v = 0; v < vc; v++) {
            double nx = nrm[v * 3], ny = nrm[v * 3 + 1], nz = nrm[v * 3 + 2];
            double tx = tan[v * 3], ty = tan[v * 3 + 1], tz = tan[v * 3 + 2];
            double d = tx * nx + ty * ny + tz * nz;
            tx -= nx * d;
            ty -= ny * d;
            tz -= nz * d;
            double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (!(len > 1e-12)) {
                // no usable tangent: any unit vector perpendicular to the normal
                double hx = Math.abs(nx) < 0.9 ? 1 : 0, hy = Math.abs(nx) < 0.9 ? 0 : 1; // an axis not parallel to the normal
                double dh = hx * nx + hy * ny;
                tx = hx - nx * dh;
                ty = hy - ny * dh;
                tz = -nz * dh;
                len = Math.sqrt(tx * tx + ty * ty + tz * tz);
                if (!(len > 1e-12)) {
                    tx = 1;
                    ty = 0;
                    tz = 0;
                    len = 1;
                }
                mesh.setTangent(v, (float) (tx / len), (float) (ty / len), (float) (tz / len), 1f);
                continue;
            }
            tx /= len;
            ty /= len;
            tz /= len;
            // handedness: does cross(n, t) point along the accumulated bitangent?
            double cx = ny * tz - nz * ty, cy = nz * tx - nx * tz, cz = nx * ty - ny * tx;
            double w = cx * bit[v * 3] + cy * bit[v * 3 + 1] + cz * bit[v * 3 + 2] < 0 ? -1 : 1;
            mesh.setTangent(v, (float) tx, (float) ty, (float) tz, (float) w);
        }
    }

    // ---------------------------------------------------------------- shared geometry

    /**
     * Adds the face normal of triangle {@code (a, b, c)} weighted by the angle at each corner into
     * {@code acc} (x, y, z per vertex).
     */
    private static void accumulate(float[] p, int a, int b, int c, double[] acc) {
        double[] n = new double[3];
        double[] ang = new double[3];
        if (!faceData(p, a, b, c, n, 0, ang, 0)) {
            return;
        }
        int[] tri = {a, b, c};
        for (int k = 0; k < 3; k++) {
            acc[tri[k] * 3] += ang[k] * n[0];
            acc[tri[k] * 3 + 1] += ang[k] * n[1];
            acc[tri[k] * 3 + 2] += ang[k] * n[2];
        }
    }

    /**
     * Writes the unit face normal of a triangle to {@code n[nOff..nOff+2]} and the angles at its
     * three corners to {@code ang[aOff..aOff+2]}.
     *
     * <p>Returns false (leaving zeros) for a degenerate triangle.
     */
    private static boolean faceData(float[] p, int a, int b, int c, double[] n, int nOff, double[] ang, int aOff) {
        double ax = p[a * 3], ay = p[a * 3 + 1], az = p[a * 3 + 2];
        double bx = p[b * 3], by = p[b * 3 + 1], bz = p[b * 3 + 2];
        double cx = p[c * 3], cy = p[c * 3 + 1], cz = p[c * 3 + 2];
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double vx = cx - ax, vy = cy - ay, vz = cz - az;
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double scale = (ux * ux + uy * uy + uz * uz) + (vx * vx + vy * vy + vz * vz);
        if (!(len > DEGENERATE * scale) || !(len > 0.0) || !Double.isFinite(len)) {
            return false;
        }
        n[nOff] = nx / len;
        n[nOff + 1] = ny / len;
        n[nOff + 2] = nz / len;
        cornerAngles(p, a, b, c, ang, aOff);
        return true;
    }

    private static boolean cornerAngles(float[] p, int a, int b, int c, double[] out) {
        return cornerAngles(p, a, b, c, out, 0);
    }

    private static boolean cornerAngles(float[] p, int a, int b, int c, double[] out, int off) {
        double[] q = {p[a * 3], p[a * 3 + 1], p[a * 3 + 2], p[b * 3], p[b * 3 + 1], p[b * 3 + 2], p[c * 3], p[c * 3 + 1], p[c * 3 + 2]};
        for (int k = 0; k < 3; k++) {
            int i = k * 3, j = ((k + 1) % 3) * 3, l = ((k + 2) % 3) * 3;
            double ux = q[j] - q[i], uy = q[j + 1] - q[i + 1], uz = q[j + 2] - q[i + 2];
            double vx = q[l] - q[i], vy = q[l + 1] - q[i + 1], vz = q[l + 2] - q[i + 2];
            double lu = Math.sqrt(ux * ux + uy * uy + uz * uz), lv = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (!(lu > 0.0) || !(lv > 0.0)) {
                out[off + k] = 0.0;
                return false;
            }
            double cos = (ux * vx + uy * vy + uz * vz) / (lu * lv);
            out[off + k] = Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
        }
        return true;
    }
}
