package vmath.mesh;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline mesh clean-up and reordering for GPU efficiency. None of these passes changes the surface: {@link #weld} merges duplicate
 * vertices, {@link #optimizeVertexCache} reorders the triangles so that recently used vertices are used again soon (fewer vertex shader
 * runs), and {@link #optimizeVertexFetch} reorders the vertices so that memory is read nearly sequentially. Run them in that order:
 * weld, cache, fetch.
 *
 * <p>They are load-time tools, not per-frame ones: they allocate working arrays sized to the mesh.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the same time. The arrays and buffers you pass in are
 * not synchronised, so two threads must not write the same one.
 */
public final class MeshOptimizer {

    private MeshOptimizer() {
    }

    // ---------------------------------------------------------------- weld

    /**
     * Merges vertices that are the same within {@code eps}, in place, and returns {@code remap}: for every <em>old</em> vertex the index
     * it has afterwards. Triangles are re-pointed; a triangle that collapses (two of its corners merged) is dropped.
     *
     * <p>With {@code positionsOnly} false a vertex is a duplicate only if every enabled stream (position, normal, tangent, each UV set)
     * matches within {@code eps} per component, which keeps UV seams and hard edges (their vertices differ in some attribute). With
     * {@code positionsOnly} true only positions are compared and the first vertex's other attributes win, which closes those seams:
     * use it to rebuild connectivity, for example before {@link MeshTools#computeNormalsWithCrease}.
     *
     * <p>Candidates are found through a grid of cell size {@code eps} (neighbouring cells included), so the cost is close to linear.
     */
    public static int[] weld(Mesh mesh, float eps, boolean positionsOnly) {
        if (!(eps >= 0f) || Float.isInfinite(eps)) {
            throw new IllegalArgumentException("eps must be >= 0 and finite: " + eps);
        }
        int n = mesh.vertexCount();
        float[] p = mesh.positions();
        double cell = Math.max(eps, 1e-12f);
        Map<Long, Integer> head = new HashMap<>();
        int[] next = new int[n];      // chain of kept vertices in one cell
        int[] remap = new int[n];
        int[] keptOf = new int[n];    // new index -> old index
        int kept = 0;
        for (int v = 0; v < n; v++) {
            long cx = (long) Math.floor(p[v * 3] / cell), cy = (long) Math.floor(p[v * 3 + 1] / cell), cz = (long) Math.floor(p[v * 3 + 2] / cell);
            int found = -1;
            search:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Integer h = head.get(cellKey(cx + dx, cy + dy, cz + dz));
                        for (int u = h == null ? -1 : h; u != -1; u = next[u]) {
                            if (same(mesh, keptOf[u], v, eps, positionsOnly)) {
                                found = u;
                                break search;
                            }
                        }
                    }
                }
            }
            if (found >= 0) {
                remap[v] = found;
            } else {
                int u = kept++;
                keptOf[u] = v;
                remap[v] = u;
                long key = cellKey(cx, cy, cz);
                Integer h = head.get(key);
                next[u] = h == null ? -1 : h;
                head.put(key, u);
            }
        }
        if (kept == n) {
            return remap; // nothing to merge: leave the mesh untouched
        }
        int[] idx = mesh.indices();
        int out = 0;
        for (int t = 0; t < mesh.indexCount(); t += 3) {
            int a = remap[idx[t]], b = remap[idx[t + 1]], c = remap[idx[t + 2]];
            if (a != b && b != c && a != c) {
                idx[out++] = a;
                idx[out++] = b;
                idx[out++] = c;
            }
        }
        rebuild(mesh, Arrays.copyOf(keptOf, kept), kept, idx, out);
        return remap;
    }

    private static long cellKey(long x, long y, long z) {
        return (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L) ^ ((x & 0xFFFFF) << 40) ^ ((y & 0xFFFFF) << 20) ^ (z & 0xFFFFF);
    }

    private static boolean same(Mesh m, int a, int b, float eps, boolean positionsOnly) {
        if (!close(m.positions(), a * 3, b * 3, 3, eps)) {
            return false;
        }
        if (positionsOnly) {
            return true;
        }
        if (m.hasNormals() && !close(m.normals(), a * 3, b * 3, 3, eps)) {
            return false;
        }
        if (m.hasTangents() && !close(m.tangents(), a * 4, b * 4, 4, eps)) {
            return false;
        }
        for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
            if (m.hasUvs(s) && !close(m.uvs(s), a * 2, b * 2, 2, eps)) {
                return false;
            }
        }
        return true;
    }

    private static boolean close(float[] arr, int a, int b, int n, float eps) {
        for (int k = 0; k < n; k++) {
            if (!(Math.abs(arr[a + k] - arr[b + k]) <= eps)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- vertex cache

    /**
     * Reorders the triangles of {@code mesh} to make good use of the GPU's post-transform vertex cache (Tom Forsyth's scoring
     * algorithm: prefer triangles whose vertices were used very recently, and finish off vertices with few triangles left). Only the
     * order of the triangles changes; each triangle keeps its own vertex order, so the surface and its orientation are untouched.
     *
     * @param cacheSize the modelled cache size, typically 16 to 32 (32 is a good general default)
     */
    public static void optimizeVertexCache(Mesh mesh, int cacheSize) {
        optimizeVertexCache(mesh.indices(), mesh.indexCount(), mesh.vertexCount(), cacheSize);
    }

    /** {@link #optimizeVertexCache(Mesh, int)} on a bare triangle list: reorders {@code indices[0..indexCount)} in place. */
    public static void optimizeVertexCache(int[] indices, int indexCount, int vertexCount, int cacheSize) {
        if (indexCount % 3 != 0) {
            throw new IllegalArgumentException("indexCount must be a multiple of 3: " + indexCount);
        }
        int cache = Math.max(4, Math.min(cacheSize, 64));
        int triangles = indexCount / 3;
        if (triangles < 2) {
            return;
        }
        // triangle lists per vertex (counting sort)
        int[] valence = new int[vertexCount];
        for (int i = 0; i < indexCount; i++) {
            valence[indices[i]]++;
        }
        int[] start = new int[vertexCount + 1];
        for (int v = 0; v < vertexCount; v++) {
            start[v + 1] = start[v] + valence[v];
        }
        int[] fill = Arrays.copyOf(start, vertexCount);
        int[] adjacent = new int[indexCount];
        for (int t = 0; t < triangles; t++) {
            for (int k = 0; k < 3; k++) {
                adjacent[fill[indices[t * 3 + k]]++] = t;
            }
        }
        int[] remaining = valence.clone(); // triangles of each vertex not yet emitted
        int[] cachePos = new int[vertexCount];
        Arrays.fill(cachePos, -1);
        float[] vscore = new float[vertexCount];
        for (int v = 0; v < vertexCount; v++) {
            vscore[v] = vertexScore(-1, remaining[v], cache);
        }
        float[] tscore = new float[triangles];
        boolean[] done = new boolean[triangles];
        for (int t = 0; t < triangles; t++) {
            tscore[t] = vscore[indices[t * 3]] + vscore[indices[t * 3 + 1]] + vscore[indices[t * 3 + 2]];
        }
        int[] out = new int[indexCount];
        int[] lru = new int[cache + 3];
        int lruSize = 0;
        int[] scratch = new int[cache + 3];
        int scanFrom = 0;
        int best = 0;
        for (int i = 1; i < triangles; i++) {
            if (tscore[i] > tscore[best]) {
                best = i;
            }
        }
        for (int emitted = 0; emitted < triangles; emitted++) {
            if (best < 0 || done[best]) {
                best = -1;
                float bs = -1f;
                for (int k = 0; k < lruSize; k++) { // candidates: triangles around vertices still in the cache
                    int v = lru[k];
                    for (int a = start[v]; a < start[v + 1]; a++) {
                        int t = adjacent[a];
                        if (!done[t] && tscore[t] > bs) {
                            bs = tscore[t];
                            best = t;
                        }
                    }
                }
                if (best < 0) { // the cache holds nothing useful: take the first triangle not emitted yet
                    while (done[scanFrom]) {
                        scanFrom++;
                    }
                    best = scanFrom;
                }
            }
            int t = best;
            done[t] = true;
            System.arraycopy(indices, t * 3, out, emitted * 3, 3);
            int a = indices[t * 3], b = indices[t * 3 + 1], c = indices[t * 3 + 2];
            remaining[a]--;
            remaining[b]--;
            remaining[c]--;
            // new cache order: the three vertices first (most recently used), then the old ones
            int n = 0;
            scratch[n++] = c;
            scratch[n++] = b;
            scratch[n++] = a;
            for (int k = 0; k < lruSize; k++) {
                int v = lru[k];
                if (v != a && v != b && v != c) {
                    scratch[n++] = v;
                }
            }
            // vertices that fell out of the cache lose their position score
            for (int k = cache; k < Math.min(n, cache + 3); k++) {
                cachePos[scratch[k]] = -1;
                vscore[scratch[k]] = vertexScore(-1, remaining[scratch[k]], cache);
            }
            lruSize = Math.min(n, cache);
            System.arraycopy(scratch, 0, lru, 0, lruSize);
            for (int k = 0; k < lruSize; k++) {
                cachePos[lru[k]] = k;
                vscore[lru[k]] = vertexScore(k, remaining[lru[k]], cache);
            }
            // rescore the triangles around everything whose score changed and pick the best of them for next time
            best = -1;
            float bs = -1f;
            for (int k = 0; k < Math.min(n, cache + 3); k++) {
                int v = scratch[k];
                for (int q = start[v]; q < start[v + 1]; q++) {
                    int tri = adjacent[q];
                    if (done[tri]) {
                        continue;
                    }
                    tscore[tri] = vscore[indices[tri * 3]] + vscore[indices[tri * 3 + 1]] + vscore[indices[tri * 3 + 2]];
                    if (tscore[tri] > bs) {
                        bs = tscore[tri];
                        best = tri;
                    }
                }
            }
        }
        System.arraycopy(out, 0, indices, 0, indexCount);
    }

    private static float vertexScore(int cachePosition, int remainingTriangles, int cacheSize) {
        if (remainingTriangles <= 0) {
            return -1f;
        }
        float score = 0f;
        if (cachePosition >= 0) {
            if (cachePosition < 3) {
                score = 0.75f; // the vertices of the triangle just emitted: using them again is good, but not preferred by order
            } else {
                float scaler = 1f / (cacheSize - 3);
                score = (float) Math.pow(1f - (cachePosition - 3) * scaler, 1.5);
            }
        }
        return score + 2f * (float) Math.pow(remainingTriangles, -0.5);
    }

    /**
     * Average cache miss ratio: vertex shader invocations per triangle for a FIFO cache of {@code cacheSize} entries. Lower is better;
     * 3 means no reuse at all, and about 0.5 is the best a regular grid can do.
     */
    public static float acmr(int[] indices, int indexCount, int cacheSize) {
        if (indexCount == 0) {
            return 0f;
        }
        int[] fifo = new int[Math.max(1, cacheSize)];
        Arrays.fill(fifo, -1);
        int head = 0;
        long misses = 0;
        for (int i = 0; i < indexCount; i++) {
            int v = indices[i];
            boolean hit = false;
            for (int k = 0; k < fifo.length; k++) {
                if (fifo[k] == v) {
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
        return (float) misses / (indexCount / 3f);
    }

    /** {@link #acmr(int[], int, int)} for a mesh. */
    public static float acmr(Mesh mesh, int cacheSize) {
        return acmr(mesh.indices(), mesh.indexCount(), cacheSize);
    }

    // ---------------------------------------------------------------- vertex fetch

    /**
     * Reorders the vertices, in place, in the order the (already cache-optimised) triangle list first uses them, so that the vertex
     * buffer is read almost sequentially. Unused vertices go to the end in their old order. Every stream follows and the indices are
     * rewritten. Returns {@code remap}: for every old vertex, its new index (a permutation).
     */
    public static int[] optimizeVertexFetch(Mesh mesh) {
        int n = mesh.vertexCount();
        int[] remap = new int[n];
        Arrays.fill(remap, -1);
        int[] newToOld = new int[n];
        int next = 0;
        int[] idx = mesh.indices();
        for (int i = 0; i < mesh.indexCount(); i++) {
            int v = idx[i];
            if (remap[v] < 0) {
                remap[v] = next;
                newToOld[next++] = v;
            }
        }
        for (int v = 0; v < n; v++) {
            if (remap[v] < 0) {
                remap[v] = next;
                newToOld[next++] = v;
            }
        }
        for (int i = 0; i < mesh.indexCount(); i++) {
            idx[i] = remap[idx[i]];
        }
        rebuild(mesh, newToOld, n, idx, mesh.indexCount());
        return remap;
    }

    // ---------------------------------------------------------------- shared

    /** Rebuilds every stream so that new vertex {@code i} is old vertex {@code newToOld[i]}, then installs the given indices. */
    static void rebuild(Mesh mesh, int[] newToOld, int newCount, int[] indices, int indexEntries) {
        float[] pos = new float[Math.max(newCount, 1) * 3];
        gather(mesh.positions(), pos, newToOld, newCount, 3);
        float[] nrm = null, tan = null;
        if (mesh.hasNormals()) {
            nrm = new float[pos.length];
            gather(mesh.normals(), nrm, newToOld, newCount, 3);
        }
        if (mesh.hasTangents()) {
            tan = new float[Math.max(newCount, 1) * 4];
            gather(mesh.tangents(), tan, newToOld, newCount, 4);
        }
        float[][] uvs = new float[Mesh.MAX_UV_SETS][];
        for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
            if (mesh.hasUvs(s)) {
                uvs[s] = new float[Math.max(newCount, 1) * 2];
                gather(mesh.uvs(s), uvs[s], newToOld, newCount, 2);
            }
        }
        mesh.adopt(pos, nrm, tan, uvs, newCount, indices, indexEntries);
    }

    private static void gather(float[] src, float[] dst, int[] newToOld, int count, int stride) {
        for (int i = 0; i < count; i++) {
            System.arraycopy(src, newToOld[i] * stride, dst, i * stride, stride);
        }
    }
}
