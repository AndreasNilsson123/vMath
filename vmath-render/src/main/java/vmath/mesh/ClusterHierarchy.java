package vmath.mesh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import vmath.annotations.Experimental;
import vmath.geo.NormalCone;

/**
 * A cluster hierarchy for continuous level of detail, in the manner of Nanite's cluster DAG: the
 * mesh is cut into clusters (meshlets), neighbouring clusters are grouped, each group is merged and
 * simplified to about half its triangles with its outer border held fixed, and the result is cut
 * into new clusters; the process repeats on the new clusters until one group is left or no progress
 * is made.
 *
 * <p><b>Choosing a level per cluster.</b> Every cluster carries the error of its own simplification
 * ({@link #lodError}) and a bounding sphere for it ({@link #lodCenterX} ...), and the same two
 * values of the group that replaces it one level up ({@link #parentError}, {@link #parentRadius}).
 * All clusters of a group share the same values, and the error never decreases going up.
 * {@link #select} draws a cluster when its projected error is within a pixel budget but its
 * parent's is not. Because the test only depends on the group, a group is always drawn whole or not
 * at all, and because the border of a group is locked while it is simplified, two neighbouring
 * groups drawn at different levels share the same border vertices: <b>the selection has no
 * cracks</b> (the tests check that a closed input mesh gives a closed selected mesh at every
 * budget). Every leaf has exactly one selected ancestor-or-self.
 *
 * <p><b>Vertices.</b> {@link #vertices()} is the pool the cluster indices refer to: the welded
 * input vertices plus, for each group, new vertices for the survivors of its simplification (with
 * interpolated attributes, see {@link MeshSimplifier}). Border vertices of a group are the same
 * pool entries before and after.
 *
 * <p><b>Limits.</b> The error is the running sum of {@link MeshSimplifier}'s estimate up the
 * hierarchy (conservative, not a Hausdorff distance). Borders between groups are locked, so a level
 * cannot simplify past what its borders allow; when a level removes fewer than 10% of the triangles
 * the hierarchy stops there and those clusters become roots. Attribute seams of the input are
 * locked by the simplifier and so never simplify. Built at load time, not for runtime use.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * Mesh mesh = Primitives.icoSphere(1f, 4);
 * ClusterHierarchy hierarchy = ClusterHierarchy.build(mesh, 64, 124, 4);               // clusters of at most 64 vertices and 124 triangles
 * int[] selected = new int[hierarchy.clusterCount()];
 * int count = hierarchy.select(0f, 0f, 5f, 1000f, 1f, selected);                          // the clusters to draw from this eye
 * }</pre>
 */
@Experimental("the build heuristics, the error model and the accessor set may change")
public final class ClusterHierarchy {

    private static final class Cluster {
        int[] indices;              // global vertex indices, three per triangle
        float sx, sy, sz, sr;       // bounding sphere of the geometry
        float ax, ay, az, cutoff;   // normal cone
        float lx, ly, lz, lr;       // the LOD sphere: the group that made this cluster
        float lodError;
        float px, py, pz, pr;       // the parent group's sphere
        float parentError = Float.POSITIVE_INFINITY;
        int level;
        int parentGroup = -1;       // the group whose simplification replaces this cluster, -1 for a root
    }

    private final Mesh pool;
    private final List<Cluster> clusters;
    private final int levels;

    private ClusterHierarchy(Mesh pool, List<Cluster> clusters, int levels) {
        this.pool = pool;
        this.clusters = clusters;
        this.levels = levels;
    }

    /**
     * Builds the hierarchy of {@code source} (not modified).
     *
     * <p>{@code maxVertices} and {@code maxTriangles} are the cluster limits (as for
     * {@link Meshlets}, for example 64 and 128) and {@code groupSize} the number of clusters merged
     * per group (4 is the usual choice).
     *
     * @param source the source; must not be {@code null}
     * @param maxVertices the max vertices
     * @param maxTriangles the max triangles
     * @param groupSize the group size
     * @return the hierarchy, never {@code null}
     * @throws IllegalArgumentException if {@code groupSize} is below 2
     */
    public static ClusterHierarchy build(Mesh source, int maxVertices, int maxTriangles, int groupSize) {
        if (groupSize < 2) {
            throw new IllegalArgumentException("groupSize must be at least 2: " + groupSize);
        }
        return new Builder(source, maxVertices, maxTriangles, groupSize).run();
    }

    /**
     * One run of the build: the pool of vertices that the clusters index, the clusters made so
     * far and the state of the level that is being merged.
     */
    private static final class Builder {
        final Mesh pool;
        final int maxVertices;
        final int maxTriangles;
        final int groupSize;
        int[] posIds;                 // the id of every pool vertex by position: vertices at one position share an id
        int nextPos;
        final List<Cluster> all = new ArrayList<>();
        int groupCounter;
        int level;
        final int[] tri = new int[3];

        Builder(Mesh source, int maxVertices, int maxTriangles, int groupSize) {
            this.maxVertices = maxVertices;
            this.maxTriangles = maxTriangles;
            this.groupSize = groupSize;
            pool = source.copy();
            MeshOptimizer.weld(pool, 0f, false); // merge identical vertices (same position and attributes) so neighbouring clusters share pool entries
            posIds = new int[pool.vertexCount()];
            nextPos = assignPositionIds(pool, posIds);
        }

        ClusterHierarchy run() {
            List<Cluster> current = leafClusters();
            while (current.size() > 1) {
                int trianglesBefore = triangleCount(current);
                List<Cluster> next = mergeLevel(current, trianglesBefore);
                boolean stalled = triangleCount(next) > trianglesBefore * 0.9 || next.size() >= current.size();
                current = next;
                level++;
                if (stalled) {
                    break;
                }
            }
            return new ClusterHierarchy(pool, all, level + 1);
        }

        private static int triangleCount(List<Cluster> clusters) {
            int n = 0;
            for (Cluster c : clusters) {
                n += c.indices.length / 3;
            }
            return n;
        }

        // the meshlets of the welded mesh are the clusters of level 0
        List<Cluster> leafClusters() {
            List<Cluster> current = new ArrayList<>();
            Meshlets leaves = Meshlets.build(pool, maxVertices, maxTriangles);
            for (int m = 0; m < leaves.count(); m++) {
                Cluster c = new Cluster();
                c.indices = new int[leaves.triangleCount(m) * 3];
                for (int t = 0; t < leaves.triangleCount(m); t++) {
                    leaves.triangle(m, t, tri);
                    System.arraycopy(tri, 0, c.indices, t * 3, 3);
                }
                setGeometryBounds(pool, c);
                c.lx = c.sx;
                c.ly = c.sy;
                c.lz = c.sz;
                c.lr = c.sr;
                c.lodError = 0f;
                current.add(c);
                all.add(c);
            }
            return current;
        }

        // merges the clusters of one level into groups, simplifies every group and splits the result into the clusters of the next level
        List<Cluster> mergeLevel(List<Cluster> current, int trianglesBefore) {
            int[][] groups = group(current, posIds, groupSize);
            FastMaps.LongIntMap border = borderEdges(current, groups, trianglesBefore);
            List<Cluster> next = new ArrayList<>();
            for (int[] members : groups) {
                List<Cluster> children = new ArrayList<>();
                for (int ci : members) {
                    children.add(current.get(ci));
                }
                mergeGroup(children, border, next);
            }
            return next;
        }

        // edges shared with another group are the borders to keep
        FastMaps.LongIntMap borderEdges(List<Cluster> current, int[][] groups, int trianglesBefore) {
            FastMaps.LongIntMap edgeGroup = new FastMaps.LongIntMap(trianglesBefore);
            FastMaps.LongIntMap border = new FastMaps.LongIntMap(1024);
            for (int g = 0; g < groups.length; g++) {
                for (int ci : groups[g]) {
                    int[] idx = current.get(ci).indices;
                    for (int t = 0; t < idx.length; t += 3) {
                        for (int k = 0; k < 3; k++) {
                            long key = edgeKey(posIds[idx[t + k]], posIds[idx[t + (k + 1) % 3]]);
                            int prev = edgeGroup.get(key);
                            if (prev < 0) {
                                edgeGroup.put(key, g);
                            } else if (prev != g) {
                                border.put(key, 1);
                            }
                        }
                    }
                }
            }
            return border;
        }

        /** The triangles of a group as a mesh of its own: the vertices copied from the pool, and the pool index of every local vertex. */
        private record LocalMesh(Mesh mesh, FastMaps.LongIntMap local, int[] localToGlobal) {
        }

        LocalMesh localMesh(List<Cluster> children) {
            FastMaps.LongIntMap local = new FastMaps.LongIntMap(512);
            int[] localToGlobal = new int[256];
            int localCount = 0;
            Mesh gm = new Mesh();
            copyStreams(pool, gm);
            int[] groupTris = new int[768];
            int groupTriCount = 0;
            for (Cluster c : children) {
                for (int v : c.indices) {
                    int l = local.get(v);
                    if (l < 0) {
                        l = appendVertex(pool, v, gm);
                        local.put(v, l);
                        if (localCount == localToGlobal.length) {
                            localToGlobal = Arrays.copyOf(localToGlobal, localCount * 2);
                        }
                        localToGlobal[localCount++] = v;
                    }
                    if (groupTriCount == groupTris.length) {
                        groupTris = Arrays.copyOf(groupTris, groupTriCount * 2);
                    }
                    groupTris[groupTriCount++] = l;
                }
            }
            for (int t = 0; t < groupTriCount; t += 3) {
                gm.addTriangle(groupTris[t], groupTris[t + 1], groupTris[t + 2]);
            }
            return new LocalMesh(gm, local, localToGlobal);
        }

        // the vertices on a border of the group must not move, so that the neighbouring groups keep fitting
        boolean[] lockedVertices(List<Cluster> children, FastMaps.LongIntMap border, LocalMesh lm) {
            boolean[] locked = new boolean[lm.mesh().vertexCount()];
            for (Cluster c : children) {
                for (int t = 0; t < c.indices.length; t += 3) {
                    for (int k = 0; k < 3; k++) {
                        int a = c.indices[t + k], b = c.indices[t + (k + 1) % 3];
                        if (border.containsKey(edgeKey(posIds[a], posIds[b]))) {
                            locked[lm.local().get(a)] = true;
                            locked[lm.local().get(b)] = true;
                        }
                    }
                }
            }
            return locked;
        }

        // survivors of the simplification: locked ones are the same pool entries, the others become new pool vertices; returns the pool index of every vertex of the simplified mesh
        int[] survivors(LocalMesh lm, MeshSimplifier.Result r, boolean[] locked) {
            Mesh gm = lm.mesh();
            int[] globalOf = new int[gm.vertexCount()];
            for (int s = 0; s < gm.vertexCount(); s++) {
                int old = r.remap()[s];
                if (locked[old]) {
                    globalOf[s] = lm.localToGlobal()[old];
                } else {
                    globalOf[s] = appendVertex(gm, s, pool);
                    if (globalOf[s] >= posIds.length) { // grow geometrically: copying the whole array per vertex was quadratic
                        posIds = Arrays.copyOf(posIds, Math.max(posIds.length * 2, globalOf[s] + 1));
                    }
                    posIds[globalOf[s]] = nextPos++;
                }
            }
            return globalOf;
        }

        void mergeGroup(List<Cluster> children, FastMaps.LongIntMap border, List<Cluster> next) {
            LocalMesh lm = localMesh(children);
            Mesh gm = lm.mesh();
            boolean[] locked = lockedVertices(children, border, lm);
            int before = gm.triangleCount();
            MeshSimplifier.Result r = MeshSimplifier.simplify(gm, Math.max(2, before / 2), Float.MAX_VALUE, false, locked);
            int[] globalOf = survivors(lm, r, locked);
            // the LOD sphere of the group encloses the children's LOD spheres and geometry
            float[] sphere = {children.get(0).lx, children.get(0).ly, children.get(0).lz, children.get(0).lr};
            float maxChildError = 0f;
            for (Cluster c : children) {
                sphere = union(sphere, new float[] {c.lx, c.ly, c.lz, c.lr});
                sphere = union(sphere, new float[] {c.sx, c.sy, c.sz, c.sr});
                maxChildError = Math.max(maxChildError, c.lodError);
            }
            List<Cluster> made = new ArrayList<>();
            if (gm.triangleCount() > 0) {
                Meshlets split = Meshlets.build(gm, maxVertices, maxTriangles);
                for (int m = 0; m < split.count(); m++) {
                    Cluster c = new Cluster();
                    c.indices = new int[split.triangleCount(m) * 3];
                    for (int t = 0; t < split.triangleCount(m); t++) {
                        split.triangle(m, t, tri);
                        for (int k = 0; k < 3; k++) {
                            c.indices[t * 3 + k] = globalOf[tri[k]];
                        }
                    }
                    setGeometryBounds(pool, c);
                    made.add(c);
                    sphere = union(sphere, new float[] {c.sx, c.sy, c.sz, c.sr});
                }
            }
            float error = maxChildError + r.error();
            int groupId = groupCounter++;
            for (Cluster c : children) {
                c.parentGroup = groupId;
                c.px = sphere[0];
                c.py = sphere[1];
                c.pz = sphere[2];
                c.pr = sphere[3];
                c.parentError = error;
            }
            for (Cluster c : made) {
                c.lx = sphere[0];
                c.ly = sphere[1];
                c.lz = sphere[2];
                c.lr = sphere[3];
                c.lodError = error;
                c.level = level + 1;
                next.add(c);
                all.add(c);
            }
        }
    }

    // ---------------------------------------------------------------- build helpers

    private static int assignPositionIds(Mesh m, int[] out) {
        FastMaps.TripleIntMap ids = new FastMaps.TripleIntMap(m.vertexCount());
        float[] p = m.positions();
        int count = 0;
        for (int v = 0; v < m.vertexCount(); v++) {
            int x = Float.floatToIntBits(p[v * 3] + 0f), y = Float.floatToIntBits(p[v * 3 + 1] + 0f), z = Float.floatToIntBits(p[v * 3 + 2] + 0f);
            int id = ids.get(x, y, z);
            if (id < 0) {
                id = count++;
                ids.put(x, y, z, id);
            }
            out[v] = id;
        }
        return count;
    }

    private static long edgeKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Math.max(a, b);
    }

    private static void copyStreams(Mesh from, Mesh to) {
        if (from.hasNormals()) {
            to.enableNormals();
        }
        if (from.hasTangents()) {
            to.enableTangents();
        }
        for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
            if (from.hasUvs(s)) {
                to.enableUvs(s);
            }
        }
    }

    /**
     * Appends vertex {@code v} of {@code from} (every stream) to {@code to}, which must have the
     * same streams enabled.
     */
    private static int appendVertex(Mesh from, int v, Mesh to) {
        float[] p = from.positions();
        int i = to.addVertex(p[v * 3], p[v * 3 + 1], p[v * 3 + 2]);
        if (from.hasNormals()) {
            float[] n = from.normals();
            to.setNormal(i, n[v * 3], n[v * 3 + 1], n[v * 3 + 2]);
        }
        if (from.hasTangents()) {
            float[] t = from.tangents();
            to.setTangent(i, t[v * 4], t[v * 4 + 1], t[v * 4 + 2], t[v * 4 + 3]);
        }
        for (int s = 0; s < Mesh.MAX_UV_SETS; s++) {
            if (from.hasUvs(s)) {
                float[] uv = from.uvs(s);
                to.setUv(s, i, uv[v * 2], uv[v * 2 + 1]);
            }
        }
        return i;
    }

    /**
     * Groups of cluster indices: each grown from a cluster with few free neighbours by taking the
     * neighbour it shares most edges with.
     */
    private static int[][] group(List<Cluster> clusters, int[] posIds, int groupSize) {
        int n = clusters.size();
        // neighbours: two clusters that own the same edge, weighted by how many edges they share
        FastMaps.LongIntMap firstOwner = new FastMaps.LongIntMap(1024);
        FastMaps.LongIntMap pairs = new FastMaps.LongIntMap(1024);
        for (int c = 0; c < n; c++) {
            int[] idx = clusters.get(c).indices;
            for (int t = 0; t < idx.length; t += 3) {
                for (int k = 0; k < 3; k++) {
                    long key = edgeKey(posIds[idx[t + k]], posIds[idx[t + (k + 1) % 3]]);
                    int owner = firstOwner.get(key);
                    if (owner < 0) {
                        firstOwner.put(key, c);
                    } else if (owner != c) {
                        pairs.add(((long) Math.min(owner, c) << 32) | Math.max(owner, c), 1);
                    }
                }
            }
        }
        int[] degree = new int[n + 1];
        pairs.forEach((key, weight) -> {
            degree[(int) (key >>> 32)]++;
            degree[(int) key]++;
        });
        int[] start = new int[n + 1];
        for (int c = 0; c < n; c++) {
            start[c + 1] = start[c] + degree[c];
        }
        long[] packed = new long[start[n]]; // neighbour in the high bits, so that sorting a range orders it by neighbour
        int[] fill = Arrays.copyOf(start, n);
        pairs.forEach((key, weight) -> {
            int a = (int) (key >>> 32), b = (int) key;
            packed[fill[a]++] = ((long) b << 32) | weight;
            packed[fill[b]++] = ((long) a << 32) | weight;
        });
        for (int c = 0; c < n; c++) {
            Arrays.sort(packed, start[c], start[c + 1]);
        }
        boolean[] taken = new boolean[n];
        int[] free = new int[n]; // untaken neighbours of every cluster
        for (int c = 0; c < n; c++) {
            free[c] = start[c + 1] - start[c];
        }
        List<int[]> groups = new ArrayList<>();
        int[] members = new int[groupSize];
        for (int round = 0; round < n; round++) {
            int seed = -1, seedFree = Integer.MAX_VALUE;
            for (int c = 0; c < n; c++) {
                if (!taken[c] && free[c] < seedFree) {
                    seedFree = free[c];
                    seed = c;
                }
            }
            if (seed < 0) {
                break;
            }
            int count = 0;
            members[count++] = seed;
            take(seed, taken, free, packed, start);
            while (count < groupSize) {
                int best = -1, bestShared = 0;
                for (int i = 0; i < count; i++) {
                    for (int e = start[members[i]]; e < start[members[i] + 1]; e++) {
                        int other = (int) (packed[e] >>> 32), shared = (int) packed[e];
                        if (!taken[other] && shared > bestShared) {
                            bestShared = shared;
                            best = other;
                        }
                    }
                }
                if (best < 0) {
                    break;
                }
                members[count++] = best;
                take(best, taken, free, packed, start);
            }
            groups.add(Arrays.copyOf(members, count));
        }
        return groups.toArray(new int[0][]);
    }

    private static void take(int c, boolean[] taken, int[] free, long[] packed, int[] start) {
        taken[c] = true;
        for (int e = start[c]; e < start[c + 1]; e++) {
            free[(int) (packed[e] >>> 32)]--;
        }
    }

    private static void setGeometryBounds(Mesh pool, Cluster c) {
        float[] p = pool.positions();
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Float.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (int v : c.indices) {
            x0 = Math.min(x0, p[v * 3]);
            y0 = Math.min(y0, p[v * 3 + 1]);
            z0 = Math.min(z0, p[v * 3 + 2]);
            x1 = Math.max(x1, p[v * 3]);
            y1 = Math.max(y1, p[v * 3 + 1]);
            z1 = Math.max(z1, p[v * 3 + 2]);
        }
        c.sx = (x0 + x1) * 0.5f;
        c.sy = (y0 + y1) * 0.5f;
        c.sz = (z0 + z1) * 0.5f;
        double r2 = 0;
        for (int v : c.indices) {
            double dx = p[v * 3] - c.sx, dy = p[v * 3 + 1] - c.sy, dz = p[v * 3 + 2] - c.sz;
            r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
        }
        c.sr = (float) Math.nextUp(Math.sqrt(r2)) * (1f + 1e-6f);
        float[] normals = new float[c.indices.length];
        for (int t = 0; t < c.indices.length; t += 3) {
            int a = c.indices[t] * 3, b = c.indices[t + 1] * 3, d = c.indices[t + 2] * 3;
            double ex = p[b] - p[a], ey = p[b + 1] - p[a + 1], ez = p[b + 2] - p[a + 2];
            double fx = p[d] - p[a], fy = p[d + 1] - p[a + 1], fz = p[d + 2] - p[a + 2];
            double nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 0) {
                normals[t] = (float) (nx / len);
                normals[t + 1] = (float) (ny / len);
                normals[t + 2] = (float) (nz / len);
            }
        }
        float[] cone = new float[4];
        NormalCone.compute(normals, 0, c.indices.length / 3, cone, 0);
        c.ax = cone[0];
        c.ay = cone[1];
        c.az = cone[2];
        c.cutoff = cone[3];
    }

    /**
     * The smallest sphere containing two spheres {@code (x, y, z, r)}.
     */
    private static float[] union(float[] a, float[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d + b[3] <= a[3]) {
            return a;
        }
        if (d + a[3] <= b[3]) {
            return b;
        }
        double r = (d + a[3] + b[3]) * 0.5;
        double t = d > 0 ? (r - a[3]) / d : 0;
        return new float[] {(float) (a[0] + dx * t), (float) (a[1] + dy * t), (float) (a[2] + dz * t), Math.nextUp((float) r) * (1f + 1e-6f)};
    }

    // ---------------------------------------------------------------- access

    /**
     * Exposes the shared vertex array that the cluster indices refer to.
     *
     * <p>A live mesh; do not modify it.
     *
     * @return the vertices the cluster indices refer to (its own triangles are the welded input,
     *     that is the leaf level)
     */
    public Mesh vertices() {
        return pool;
    }

    /**
     * Counts the clusters over all levels of the hierarchy.
     *
     * @return the number of clusters over all levels
     */
    public int clusterCount() {
        return clusters.size();
    }

    /**
     * Counts the levels of the hierarchy, where level zero is the original geometry.
     *
     * @return number of levels (0 is the original detail)
     */
    public int levelCount() {
        return levels;
    }

    /**
     * Reads the level of a cluster, where higher levels are coarser.
     *
     * @param cluster the cluster index
     * @return the level of {@code cluster}: 0 is the original detail, higher levels are coarser
     */
    public int level(int cluster) {
        return clusters.get(cluster).level;
    }

    /**
     * Counts the triangles of a cluster.
     *
     * @param cluster the cluster index
     * @return the number of triangles of {@code cluster}
     */
    public int triangleCount(int cluster) {
        return clusters.get(cluster).indices.length / 3;
    }

    /**
     * Reads the triangles of one cluster as indices into the shared vertex array.
     *
     * <p>A copy.
     *
     * @param cluster the cluster index
     * @return the triangles of a cluster as pool vertex indices, three per triangle
     */
    public int[] indices(int cluster) {
        return clusters.get(cluster).indices.clone();
    }

    /**
     * Reads the x of the bounding sphere centre of a cluster's own geometry, which is what frustum
     * culling tests.
     *
     * @param c the cluster index
     * @return bounding sphere of the cluster's own geometry (for culling): centre x, y, z and
     *     radius
     */
    public float sphereX(int c) {
        return clusters.get(c).sx;
    }

    /**
     * Reads the y of the bounding sphere centre of a cluster's own geometry.
     *
     * @param c the cluster index
     * @return the y of the cluster's bounding sphere centre
     */
    public float sphereY(int c) {
        return clusters.get(c).sy;
    }

    /**
     * Reads the z of the bounding sphere centre of a cluster's own geometry.
     *
     * @param c the cluster index
     * @return the z of the cluster's bounding sphere centre
     */
    public float sphereZ(int c) {
        return clusters.get(c).sz;
    }

    /**
     * Reads the radius of the bounding sphere of a cluster's own geometry.
     *
     * @param c the cluster index
     * @return the radius of the cluster's bounding sphere
     */
    public float sphereRadius(int c) {
        return clusters.get(c).sr;
    }

    /**
     * Reads the x of the normal cone axis of a cluster, which back-face culling of whole clusters
     * uses.
     *
     * @param c the cluster index
     * @return normal cone of the cluster, as in {@link Meshlets}
     */
    public float coneAxisX(int c) {
        return clusters.get(c).ax;
    }

    /**
     * Reads the y of the normal cone axis of a cluster.
     *
     * @param c the cluster index
     * @return the y of the cluster's normal cone axis
     */
    public float coneAxisY(int c) {
        return clusters.get(c).ay;
    }

    /**
     * Reads the z of the normal cone axis of a cluster.
     *
     * @param c the cluster index
     * @return the z of the cluster's normal cone axis
     */
    public float coneAxisZ(int c) {
        return clusters.get(c).az;
    }

    /**
     * Reads the cutoff of the normal cone of a cluster, which is the sine of the cone's half-angle;
     * the value 1 disables cone culling for that cluster.
     *
     * @param c the cluster index
     * @return the sine of the cone's half-angle; 1 means no useful cone (the cluster is never
     *     back-face culled)
     */
    public float coneCutoff(int c) {
        return clusters.get(c).cutoff;
    }

    /**
     * Reads the geometric error that simplification introduced in a cluster, which is the input to
     * the level-of-detail decision.
     *
     * @param c the cluster index
     * @return error of this cluster's simplification: 0 for the original detail
     */
    public float lodError(int c) {
        return clusters.get(c).lodError;
    }

    /**
     * Reads the x of the centre of the sphere that bounds the cluster's simplification error;
     * shared by all clusters of a group so that they switch level together.
     *
     * @param c the cluster index
     * @return the x of the centre of the sphere that bounds this cluster's simplification error
     *     (the same for every cluster of a group)
     */
    public float lodCenterX(int c) {
        return clusters.get(c).lx;
    }

    /**
     * Reads the y of the centre of the sphere that bounds the cluster's simplification error.
     *
     * @param c the cluster index
     * @return the y of the centre of the sphere that bounds this cluster's simplification error
     */
    public float lodCenterY(int c) {
        return clusters.get(c).ly;
    }

    /**
     * Reads the z of the centre of the sphere that bounds the cluster's simplification error.
     *
     * @param c the cluster index
     * @return the z of the centre of the sphere that bounds this cluster's simplification error
     */
    public float lodCenterZ(int c) {
        return clusters.get(c).lz;
    }

    /**
     * Reads the radius of the sphere that bounds the cluster's simplification error.
     *
     * @param c the cluster index
     * @return the radius of the sphere that bounds this cluster's simplification error
     */
    public float lodRadius(int c) {
        return clusters.get(c).lr;
    }

    /**
     * Reads which group replaces a cluster at the next coarser level, which lets the runtime walk
     * the hierarchy.
     *
     * <p>Clusters of one group share their parent values.
     *
     * @param c the cluster index
     * @return identifier of the group whose simplification replaces this cluster one level up,
     *     {@code -1} for a root
     */
    public int parentGroup(int c) {
        return clusters.get(c).parentGroup;
    }

    /**
     * Reads the error of the coarser group that replaces a cluster; a cluster is drawn when its own
     * error is acceptable and its parent's is not.
     *
     * @param c the cluster index
     * @return error of the group that replaces this cluster one level up, {@code +Infinity} for a
     *     root
     */
    public float parentError(int c) {
        return clusters.get(c).parentError;
    }

    /**
     * Reads the x of the error sphere centre of the group that replaces a cluster.
     *
     * @param c the cluster index
     * @return the x of the centre of the error sphere of the group that replaces this cluster one
     *     level up
     */
    public float parentCenterX(int c) {
        return clusters.get(c).px;
    }

    /**
     * Reads the y of the error sphere centre of the group that replaces a cluster.
     *
     * @param c the cluster index
     * @return the y of the centre of the error sphere of the group that replaces this cluster one
     *     level up
     */
    public float parentCenterY(int c) {
        return clusters.get(c).py;
    }

    /**
     * Reads the z of the error sphere centre of the group that replaces a cluster.
     *
     * @param c the cluster index
     * @return the z of the centre of the error sphere of the group that replaces this cluster one
     *     level up
     */
    public float parentCenterZ(int c) {
        return clusters.get(c).pz;
    }

    /**
     * Reads the radius of the error sphere of the group that replaces a cluster.
     *
     * @param c the cluster index
     * @return the radius of the error sphere of the group that replaces this cluster one level up
     */
    public float parentRadius(int c) {
        return clusters.get(c).pr;
    }

    /**
     * Writes the indices of the clusters to draw from the eye at {@code (ex, ey, ez)} into
     * {@code out} and returns how many.
     *
     * <p>A cluster is chosen when the projected error of its own level is at most
     * {@code pixelBudget} and that of its parent is more: {@code error * pixelScale / distance},
     * where {@code distance} runs to the near side of the error sphere (never below 1e-4) and
     * {@code pixelScale} is {@code viewportHeight / (2 tan(fovY / 2))} (see
     * {@code CullContext#pixelScale}). {@code out} must have room for every cluster.
     *
     * @param ex the x coordinate of the eye
     * @param ey the y coordinate of the eye
     * @param ez the z coordinate of the eye
     * @param pixelScale the pixel scale
     * @param pixelBudget the pixel budget
     * @param out receives the result
     * @return how many
     */
    public int select(float ex, float ey, float ez, float pixelScale, float pixelBudget, int[] out) {
        int n = 0;
        for (int i = 0; i < clusters.size(); i++) {
            Cluster c = clusters.get(i);
            if (projected(c.lodError, c.lx, c.ly, c.lz, c.lr, ex, ey, ez, pixelScale) <= pixelBudget
                    && projected(c.parentError, c.px, c.py, c.pz, c.pr, ex, ey, ez, pixelScale) > pixelBudget) {
                out[n++] = i;
            }
        }
        return n;
    }

    private static float projected(float error, float cx, float cy, float cz, float r, float ex, float ey, float ez, float pixelScale) {
        if (error == Float.POSITIVE_INFINITY) {
            return Float.POSITIVE_INFINITY;
        }
        double dx = cx - ex, dy = cy - ey, dz = cz - ez;
        double d = Math.max(Math.sqrt(dx * dx + dy * dy + dz * dz) - r, 1e-4);
        return (float) (error * pixelScale / d);
    }

    /**
     * Gathers the triangles of several clusters into one index list, for drawing a selection with a
     * single call.
     *
     * @param clusterIndices the cluster indices
     * @param count the number of elements
     * @return the triangles of the given clusters concatenated, as pool vertex indices
     */
    public int[] triangles(int[] clusterIndices, int count) {
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += clusters.get(clusterIndices[i]).indices.length;
        }
        int[] out = new int[total];
        int o = 0;
        for (int i = 0; i < count; i++) {
            int[] idx = clusters.get(clusterIndices[i]).indices;
            System.arraycopy(idx, 0, out, o, idx.length);
            o += idx.length;
        }
        return out;
    }
}
