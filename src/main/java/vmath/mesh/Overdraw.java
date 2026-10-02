package vmath.mesh;

import java.util.Arrays;
import vmath.annotations.Experimental;

/**
 * Overdraw measurement and overdraw-aware triangle ordering, in the spirit of meshoptimizer's {@code optimizeOverdraw} but simpler and checked by
 * measurement rather than trusted.
 *
 * <p><b>Measuring.</b> {@link #measure} rasterises the mesh in submission order, back faces culled, with an early depth test, from the six axis
 * directions, and returns {@code shaded pixels / covered pixels}: 1.0 means no pixel was shaded more than once. It is a model (orthographic, six views, a
 * small grid), good for comparing two orders of the same mesh, not a GPU counter.
 *
 * <p><b>Optimising.</b> {@link #optimize} splits the (already cache-optimised) triangle list into clusters at natural breaks, where a triangle jumps to a
 * new region of the mesh, orders the clusters by how far their average normal points away from the mesh centre, and keeps the result only when it lowers
 * the measured overdraw without raising the vertex-cache miss ratio by more than a threshold. Both sort directions are tried, because which one helps
 * depends on the shape. Only the order of the triangles changes.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
@Experimental("the clustering heuristic and the result record may change")
public final class Overdraw {

    private Overdraw() {
    }

    /** What {@link #optimize} did. {@code applied} is false when no ordering was better, in which case the mesh is unchanged. */
    public record Result(float overdrawBefore, float overdrawAfter, float acmrBefore, float acmrAfter, int clusters, boolean applied) {
    }

    private static final int CLUSTER_CACHE = 16;
    /** A cluster ends when its box is wider than this fraction of the mesh. */
    private static final float CLUSTER_EXTENT = 0.5f;

    private static void resetBox(float[] lo, float[] hi) {
        Arrays.fill(lo, Float.POSITIVE_INFINITY);
        Arrays.fill(hi, Float.NEGATIVE_INFINITY);
    }

    /** Average overdraw over the six axis views at a square grid of {@code resolution} (for example 128). */
    public static float measure(Mesh mesh, int resolution) {
        return measure(mesh.positions(), mesh.indices(), mesh.indexCount(), resolution);
    }

    /** {@link #measure(Mesh, int)} on bare arrays: {@code positions} hold 3 floats per vertex, triangles are {@code indices[0..indexCount)}. */
    public static float measure(float[] positions, int[] indices, int indexCount, int resolution) {
        if (indexCount < 3) {
            return 1f;
        }
        float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < indexCount; i++) {
            for (int k = 0; k < 3; k++) {
                float c = positions[indices[i] * 3 + k];
                lo[k] = Math.min(lo[k], c);
                hi[k] = Math.max(hi[k], c);
            }
        }
        float extent = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        if (!(extent > 0f)) {
            return 1f;
        }
        float scale = (resolution - 1e-3f) / extent;
        float[] depth = new float[resolution * resolution];
        long shaded = 0, covered = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int s = -1; s <= 1; s += 2) {
                Arrays.fill(depth, Float.POSITIVE_INFINITY);
                int ua = (axis + 1) % 3, va = (axis + 2) % 3;
                float uOrigin = s > 0 ? lo[ua] : -hi[ua];
                for (int t = 0; t < indexCount; t += 3) {
                    int a = indices[t] * 3, b = indices[t + 1] * 3, c = indices[t + 2] * 3;
                    float ax = (s * positions[a + ua] - uOrigin) * scale, ay = (positions[a + va] - lo[va]) * scale;
                    float bx = (s * positions[b + ua] - uOrigin) * scale, by = (positions[b + va] - lo[va]) * scale;
                    float cx = (s * positions[c + ua] - uOrigin) * scale, cy = (positions[c + va] - lo[va]) * scale;
                    float area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
                    if (!(area > 0f)) {
                        continue; // back face or degenerate
                    }
                    float az = -s * positions[a + axis], bz = -s * positions[b + axis], cz = -s * positions[c + axis];
                    int x0 = Math.max(0, (int) Math.floor(Math.min(ax, Math.min(bx, cx)))), x1 = Math.min(resolution - 1, (int) Math.ceil(Math.max(ax, Math.max(bx, cx))));
                    int y0 = Math.max(0, (int) Math.floor(Math.min(ay, Math.min(by, cy)))), y1 = Math.min(resolution - 1, (int) Math.ceil(Math.max(ay, Math.max(by, cy))));
                    float inv = 1f / area;
                    for (int y = y0; y <= y1; y++) {
                        float py = y + 0.5f;
                        for (int x = x0; x <= x1; x++) {
                            float px = x + 0.5f;
                            float w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv;
                            float w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv;
                            float w2 = 1f - w0 - w1;
                            if (w0 < 0f || w1 < 0f || w2 < 0f) {
                                continue;
                            }
                            float z = w0 * az + w1 * bz + w2 * cz;
                            int p = y * resolution + x;
                            if (z < depth[p]) {
                                if (depth[p] == Float.POSITIVE_INFINITY) {
                                    covered++;
                                }
                                depth[p] = z;
                                shaded++;
                            }
                        }
                    }
                }
            }
        }
        return covered == 0 ? 1f : (float) shaded / covered;
    }

    /**
     * Reorders the triangles of {@code mesh} to reduce overdraw; call it after {@link MeshOptimizer#optimizeVertexCache} and before
     * {@link MeshOptimizer#optimizeVertexFetch}. The order is kept only if it lowers {@link #measure overdraw} and keeps
     * {@code acmrAfter <= acmrBefore * cacheThreshold} (1.05 allows 5% more vertex shader work).
     *
     * @param resolution grid size of the overdraw model, for example 128
     * @param cacheThreshold allowed growth of the cache miss ratio, at least 1
     */
    public static Result optimize(Mesh mesh, int resolution, float cacheThreshold) {
        int n = mesh.indexCount(), tris = n / 3;
        float[] pos = mesh.positions();
        int[] idx = mesh.indices();
        float before = measure(pos, idx, n, resolution);
        float acmrBefore = MeshOptimizer.acmr(idx, n, CLUSTER_CACHE);
        if (tris < 4) {
            return new Result(before, before, acmrBefore, acmrBefore, tris, false);
        }
        // clusters: runs of triangles, cut where a triangle brings two or more new vertices (a jump to a new region)
        int minSize = 128, maxSize = 512;
        // a cluster also ends when its box gets large: a long ring of triangles around a sphere has normals that cancel, so it could not be ordered at all
        float[] meshLo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] meshHi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                meshLo[k] = Math.min(meshLo[k], pos[idx[i] * 3 + k]);
                meshHi[k] = Math.max(meshHi[k], pos[idx[i] * 3 + k]);
            }
        }
        float maxExtent = Math.max(meshHi[0] - meshLo[0], Math.max(meshHi[1] - meshLo[1], meshHi[2] - meshLo[2])) * CLUSTER_EXTENT;
        float[] cLo = new float[3], cHi = new float[3];
        resetBox(cLo, cHi);
        int[] clusterStart = new int[tris + 1];
        int clusters = 0;
        int[] fifo = new int[CLUSTER_CACHE];
        Arrays.fill(fifo, -1);
        int head = 0, size = 0;
        clusterStart[clusters++] = 0;
        for (int t = 0; t < tris; t++) {
            int misses = 0;
            for (int k = 0; k < 3; k++) {
                int v = idx[t * 3 + k];
                boolean hit = false;
                for (int f = 0; f < fifo.length; f++) {
                    if (fifo[f] == v) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    misses++;
                    fifo[head] = v;
                    head = (head + 1) % fifo.length;
                }
            }
            boolean tooWide = false;
            for (int k = 0; k < 3; k++) {
                for (int c = 0; c < 3; c++) {
                    float x = pos[idx[t * 3 + c] * 3 + k];
                    cLo[k] = Math.min(cLo[k], x);
                    cHi[k] = Math.max(cHi[k], x);
                }
                tooWide |= cHi[k] - cLo[k] > maxExtent;
            }
            if (size >= minSize && (misses >= 2 || size >= maxSize || tooWide)) {
                clusterStart[clusters++] = t;
                size = 0;
                resetBox(cLo, cHi);
                for (int k = 0; k < 3; k++) {
                    for (int c = 0; c < 3; c++) {
                        float x = pos[idx[t * 3 + c] * 3 + k];
                        cLo[k] = Math.min(cLo[k], x);
                        cHi[k] = Math.max(cHi[k], x);
                    }
                }
            }
            size++;
        }
        clusterStart[clusters] = tris;
        if (clusters < 2) {
            return new Result(before, before, acmrBefore, acmrBefore, clusters, false);
        }
        // the key of a cluster: how far out its average normal points, dot(centroid - mesh centroid, normal) / |normal|
        double mx = 0, my = 0, mz = 0;
        for (int i = 0; i < n; i++) {
            mx += pos[idx[i] * 3];
            my += pos[idx[i] * 3 + 1];
            mz += pos[idx[i] * 3 + 2];
        }
        mx /= n;
        my /= n;
        mz /= n;
        double[] key = new double[clusters];
        for (int c = 0; c < clusters; c++) {
            double cx = 0, cy = 0, cz = 0, nx = 0, ny = 0, nz = 0;
            for (int t = clusterStart[c]; t < clusterStart[c + 1]; t++) {
                int a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, d = idx[t * 3 + 2] * 3;
                cx += pos[a] + pos[b] + pos[d];
                cy += pos[a + 1] + pos[b + 1] + pos[d + 1];
                cz += pos[a + 2] + pos[b + 2] + pos[d + 2];
                double ex = pos[b] - pos[a], ey = pos[b + 1] - pos[a + 1], ez = pos[b + 2] - pos[a + 2];
                double fx = pos[d] - pos[a], fy = pos[d + 1] - pos[a + 1], fz = pos[d + 2] - pos[a + 2];
                nx += ey * fz - ez * fy; // area-weighted: the cross product is twice the area
                ny += ez * fx - ex * fz;
                nz += ex * fy - ey * fx;
            }
            double count = 3.0 * (clusterStart[c + 1] - clusterStart[c]);
            double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            key[c] = nl > 0 ? ((cx / count - mx) * nx + (cy / count - my) * ny + (cz / count - mz) * nz) / nl : 0.0;
        }
        Integer[] order = new Integer[clusters];
        for (int c = 0; c < clusters; c++) {
            order[c] = c;
        }
        int[] best = null;
        float bestOverdraw = before, bestAcmr = acmrBefore;
        for (int direction = 0; direction < 2; direction++) {
            final int dir = direction;
            Arrays.sort(order, (p, q) -> dir == 0 ? Double.compare(key[q], key[p]) : Double.compare(key[p], key[q]));
            int[] candidate = new int[n];
            int o = 0;
            for (int c : order) {
                int len = (clusterStart[c + 1] - clusterStart[c]) * 3;
                System.arraycopy(idx, clusterStart[c] * 3, candidate, o, len);
                o += len;
            }
            float ov = measure(pos, candidate, n, resolution);
            float ac = MeshOptimizer.acmr(candidate, n, CLUSTER_CACHE);
            if (ov < bestOverdraw && ac <= acmrBefore * Math.max(1f, cacheThreshold)) {
                best = candidate;
                bestOverdraw = ov;
                bestAcmr = ac;
            }
        }
        if (best == null) {
            return new Result(before, before, acmrBefore, acmrBefore, clusters, false);
        }
        System.arraycopy(best, 0, idx, 0, n);
        return new Result(before, bestOverdraw, acmrBefore, bestAcmr, clusters, true);
    }
}
