package vmath.camera;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import vmath.annotations.Experimental;
import vmath.gl.GpuWriter;

/**
 * CPU reference of clustered light assignment: which lights can reach which cluster of a {@link ClusterGrid}, as compact per-cluster lists
 * (a count-then-fill index buffer, the layout a fragment shader reads: {@code offset(cluster)}, {@code count(cluster)}, {@code lightAt}). It is both a usable
 * implementation and the oracle that a compute shader doing the same job is checked against.
 *
 * <p><b>Lights</b> are in view space (x right, y up, z negative forward), added with {@link #addPoint} and {@link #addSpot}. A point light reaches a sphere; a spot light
 * reaches a cone of {@code range} along its axis and a given half angle.
 *
 * <p><b>The tests are conservative.</b> A cluster is a frustum slice and is represented by the box around it, which is a little larger, so a light that only touches the
 * corner of the box may be listed for a cluster it does not reach; a light is never missing from a cluster it reaches. A point light is tested exactly against the box
 * (sphere against box). A spot light is tested against the bounding sphere of its cone and then against the cone with the sphere that circumscribes the box, which is looser.
 * {@code docs/CAMERA.md} gives the measured false-positive rates. A light whose position is NaN is listed for every cluster (NaN is not a separation, as in the culling
 * predicates), never silently dropped.
 *
 * <p><b>Cost.</b> For every light the columns, rows and slices it can touch are found first (the screen extent of its bounding sphere by the tangent-angle method, the
 * depth extent directly), and only those clusters are tested, so the cost follows the area the lights cover, not clusters times lights. Nothing is allocated once the
 * buffers have grown to fit.
 */
@Experimental("the light kinds and the buffer layout may change")
public final class ClusterLights {

    private static final byte POINT = 0, SPOT = 1;

    private int lightCount;
    private float[] x = new float[16], y = new float[16], z = new float[16], range = new float[16];
    private float[] dx = new float[16], dy = new float[16], dz = new float[16], cosHalf = new float[16], sinHalf = new float[16];
    private byte[] kind = new byte[16];
    private float[] sx = new float[16], sy = new float[16], sz = new float[16], sr = new float[16]; // bounding sphere

    private int[] offsets = new int[1];
    private int[] cursor = new int[0];
    private int[] indices = new int[0];
    private final float[] scratch = new float[8];

    public ClusterLights() {
    }

    public void clearLights() {
        lightCount = 0;
    }

    public int lightCount() {
        return lightCount;
    }

    private void ensure(int n) {
        if (n > x.length) {
            int c = Math.max(n, x.length * 2);
            x = Arrays.copyOf(x, c);
            y = Arrays.copyOf(y, c);
            z = Arrays.copyOf(z, c);
            range = Arrays.copyOf(range, c);
            dx = Arrays.copyOf(dx, c);
            dy = Arrays.copyOf(dy, c);
            dz = Arrays.copyOf(dz, c);
            cosHalf = Arrays.copyOf(cosHalf, c);
            sinHalf = Arrays.copyOf(sinHalf, c);
            kind = Arrays.copyOf(kind, c);
            sx = Arrays.copyOf(sx, c);
            sy = Arrays.copyOf(sy, c);
            sz = Arrays.copyOf(sz, c);
            sr = Arrays.copyOf(sr, c);
        }
    }

    /** Adds a point light at a view-space position reaching {@code range}. Returns its index. */
    public int addPoint(float px, float py, float pz, float lightRange) {
        if (!(lightRange >= 0f)) {
            throw new IllegalArgumentException("range must be >= 0: " + lightRange);
        }
        ensure(lightCount + 1);
        int i = lightCount++;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        range[i] = lightRange;
        kind[i] = POINT;
        sx[i] = px;
        sy[i] = py;
        sz[i] = pz;
        sr[i] = lightRange;
        return i;
    }

    /**
     * Adds a spot light: apex at a view-space position, shining along {@code (dirX, dirY, dirZ)} (any length), half angle in radians below pi/2, reaching {@code lightRange}
     * along the axis. Returns its index.
     */
    public int addSpot(float px, float py, float pz, float dirX, float dirY, float dirZ, float halfAngle, float lightRange) {
        if (!(halfAngle > 0f && halfAngle < (float) (Math.PI / 2.0)) || !(lightRange >= 0f)) {
            throw new IllegalArgumentException("need 0 < halfAngle < pi/2 and range >= 0: " + halfAngle + ", " + lightRange);
        }
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (!(len > 0f)) {
            throw new IllegalArgumentException("direction must not be zero");
        }
        ensure(lightCount + 1);
        int i = lightCount++;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        range[i] = lightRange;
        dx[i] = dirX / len;
        dy[i] = dirY / len;
        dz[i] = dirZ / len;
        cosHalf[i] = (float) Math.cos(halfAngle);
        sinHalf[i] = (float) Math.sin(halfAngle);
        kind[i] = SPOT;
        // the smallest sphere around the cone: centred on the base for a wide cone, on the axis at h / (2 cos^2) for a narrow one
        float tan = sinHalf[i] / cosHalf[i];
        float t, r;
        if (tan >= 1f) {
            t = lightRange;
            r = lightRange * tan;
        } else {
            t = lightRange / (2f * cosHalf[i] * cosHalf[i]);
            r = t;
        }
        sx[i] = px + dx[i] * t;
        sy[i] = py + dy[i] * t;
        sz[i] = pz + dz[i] * t;
        sr[i] = r * 1.00001f + 1e-6f;
        return i;
    }

    // ---------------------------------------------------------------- assignment

    /** Assigns every light to the clusters of {@code grid}. */
    public void assign(ClusterGrid grid) {
        assignInternal(grid, null, null);
    }

    /**
     * Tiled assignment: the grid must have a single slice, and the depth range of each tile (from a depth pre-pass: the nearest and farthest depth of the pixels of the
     * tile, as distances along the view direction) replaces the depth range of the slice, which removes lights that are in front of or behind everything in the tile.
     * Arrays have one entry per tile, row by row ({@code row * tilesX + column}); a tile with {@code near > far} (nothing drawn) gets no lights.
     */
    public void assignTiled(ClusterGrid grid, float[] tileNear, float[] tileFar) {
        if (grid.slices() != 1) {
            throw new IllegalArgumentException("a tiled assignment needs a grid with one slice: " + grid.slices());
        }
        int tiles = grid.tilesX() * grid.tilesY();
        if (tileNear.length < tiles || tileFar.length < tiles) {
            throw new IllegalArgumentException("need one depth range per tile: " + tiles);
        }
        assignInternal(grid, tileNear, tileFar);
    }

    private void assignInternal(ClusterGrid grid, float[] tileNear, float[] tileFar) {
        int clusters = grid.clusterCount();
        if (offsets.length < clusters + 1) {
            offsets = new int[clusters + 1];
            cursor = new int[clusters];
        }
        // one visiting pass records every (cluster, light) pair, light by light; a counting sort by cluster then keeps the lights of a cluster in ascending order
        pairCount = 0;
        for (int l = 0; l < lightCount; l++) {
            visit(grid, l, tileNear, tileFar);
        }
        Arrays.fill(offsets, 0, clusters + 1, 0);
        for (int e = 0; e < pairCount; e++) {
            offsets[(int) (pairs[e] >>> 32) + 1]++;
        }
        for (int c = 0; c < clusters; c++) {
            offsets[c + 1] += offsets[c];
        }
        if (indices.length < pairCount) {
            indices = new int[Math.max(pairCount, indices.length * 2)];
        }
        System.arraycopy(offsets, 0, cursor, 0, clusters);
        for (int e = 0; e < pairCount; e++) {
            long pair = pairs[e];
            indices[cursor[(int) (pair >>> 32)]++] = (int) pair;
        }
    }

    private long[] pairs = new long[0];
    private int pairCount;

    private void addPair(int cluster, int light) {
        if (pairCount == pairs.length) {
            pairs = Arrays.copyOf(pairs, Math.max(1024, pairs.length * 2));
        }
        pairs[pairCount++] = ((long) cluster << 32) | light;
    }

    /** Records a pair for every cluster that light {@code l} may reach. */
    private void visit(ClusterGrid grid, int l, float[] tileNear, float[] tileFar) {
        float near = grid.near(), far = grid.far();
        float depth = -sz[l], r = sr[l];
        boolean unknown = Float.isNaN(sx[l]) || Float.isNaN(sy[l]) || Float.isNaN(sz[l]); // NaN is not a separation: the light is listed everywhere rather than dropped
        if (!unknown && (!(depth + r >= near) || !(depth - r <= far))) {
            return; // entirely in front of the near plane or beyond the far plane
        }
        float[] ext = scratch;
        // screen extent of the bounding sphere, as a range of columns and rows
        if (unknown) {
            ext[0] = -1f;
            ext[1] = 1f;
            ext[2] = -1f;
            ext[3] = 1f;
        } else {
            ndcExtent(sx[l], depth, r, grid.tanHalfFovX(), ext, 0);
            ndcExtent(sy[l], depth, r, grid.tanHalfFovY(), ext, 2);
        }
        int tilesX = grid.tilesX(), tilesY = grid.tilesY();
        float perTileX = 0.5f * grid.viewportWidth() / grid.tilePixels(), perTileY = 0.5f * grid.viewportHeight() / grid.tilePixels();
        int c0 = clampIndex((int) Math.floor((ext[0] + 1f) * perTileX - 1e-3f), tilesX);
        int c1 = clampIndex((int) Math.floor((ext[1] + 1f) * perTileX + 1e-3f), tilesX);
        int r0, r1;
        if (grid.yDown()) {
            r0 = clampIndex((int) Math.floor((1f - ext[3]) * perTileY - 1e-3f), tilesY);
            r1 = clampIndex((int) Math.floor((1f - ext[2]) * perTileY + 1e-3f), tilesY);
        } else {
            r0 = clampIndex((int) Math.floor((ext[2] + 1f) * perTileY - 1e-3f), tilesY);
            r1 = clampIndex((int) Math.floor((ext[3] + 1f) * perTileY + 1e-3f), tilesY);
        }
        int s0 = unknown ? 0 : grid.sliceOf(Math.max(near, depth - r)), s1 = unknown ? grid.slices() - 1 : grid.sliceOf(Math.min(far, depth + r));
        float[] b = boxScratch;
        for (int s = s0; s <= s1; s++) {
            for (int row = r0; row <= r1; row++) {
                for (int col = c0; col <= c1; col++) {
                    float dNear, dFar;
                    if (tileNear != null) {
                        int t = row * tilesX + col;
                        dNear = tileNear[t];
                        dFar = tileFar[t];
                        if (!(dNear <= dFar)) {
                            continue;
                        }
                    } else {
                        dNear = grid.sliceBoundary(s);
                        dFar = grid.sliceBoundary(s + 1);
                    }
                    grid.bounds(col, row, s, dNear, dFar, b, 0);
                    if (unknown || reaches(l, b)) {
                        addPair(grid.index(col, row, s), l);
                    }
                }
            }
        }
    }

    private final float[] boxScratch = new float[6];

    private static int clampIndex(int i, int n) {
        return i < 0 ? 0 : Math.min(i, n - 1);
    }

    /**
     * The ndc range of a sphere along one screen axis, by the tangent-angle method: the sphere, centre at lateral offset {@code c} and depth {@code d}, radius {@code r}, seen
     * from the camera, covers the angles {@code atan2(c, d) +- asin(r / dist)}. The full range is returned when the sphere reaches the camera plane or contains the camera.
     * Writes {@code lo, hi} to {@code out[at..at + 1]}, slightly widened.
     */
    private static void ndcExtent(float c, float d, float r, float tanHalf, float[] out, int at) {
        float dist2 = c * c + d * d;
        if (!(d - r > 1e-6f) || !(dist2 > r * r)) {
            out[at] = -1f;
            out[at + 1] = 1f;
            return;
        }
        double dist = Math.sqrt(dist2);
        double phi = Math.atan2(c, d), alpha = Math.asin(Math.min(1.0, r / dist));
        double lo = phi - alpha, hi = phi + alpha;
        double limit = Math.PI / 2 - 1e-4;
        out[at] = lo <= -limit ? -1f : (float) Math.max(-1.0, Math.tan(lo) / tanHalf - 1e-5);
        out[at + 1] = hi >= limit ? 1f : (float) Math.min(1.0, Math.tan(hi) / tanHalf + 1e-5);
    }

    /** True when light {@code l} may reach the box {@code b} (minX, minY, minZ, maxX, maxY, maxZ). */
    private boolean reaches(int l, float[] b) {
        // the bounding sphere against the box: exact for a point light
        float ex = Math.max(Math.max(b[0] - sx[l], sx[l] - b[3]), 0f);
        float ey = Math.max(Math.max(b[1] - sy[l], sy[l] - b[4]), 0f);
        float ez = Math.max(Math.max(b[2] - sz[l], sz[l] - b[5]), 0f);
        if (ex * ex + ey * ey + ez * ez > sr[l] * sr[l]) {
            return false;
        }
        if (kind[l] == POINT) {
            return true;
        }
        // a spot light: the cone against the sphere around the box (an overestimate of the box, so conservative)
        float hx = (b[3] - b[0]) * 0.5f, hy = (b[4] - b[1]) * 0.5f, hz = (b[5] - b[2]) * 0.5f;
        float rb = (float) Math.sqrt(hx * hx + hy * hy + hz * hz) * 1.0001f + 1e-6f;
        float vx = (b[0] + b[3]) * 0.5f - x[l], vy = (b[1] + b[4]) * 0.5f - y[l], vz = (b[2] + b[5]) * 0.5f - z[l];
        float along = vx * dx[l] + vy * dy[l] + vz * dz[l];
        float perp = (float) Math.sqrt(Math.max(0f, vx * vx + vy * vy + vz * vz - along * along));
        float toCone = cosHalf[l] * perp - along * sinHalf[l];
        return !(toCone > rb || along > range[l] + rb || along < -rb);
    }

    // ---------------------------------------------------------------- results

    public int count(int cluster) {
        return offsets[cluster + 1] - offsets[cluster];
    }

    public int offset(int cluster) {
        return offsets[cluster];
    }

    /** Index (as returned by {@code addPoint} / {@code addSpot}) of the {@code i}-th light of a cluster, in ascending order. */
    public int lightAt(int cluster, int i) {
        return indices[offsets[cluster] + i];
    }

    /** Number of (cluster, light) pairs. */
    public int totalAssignments(ClusterGrid grid) {
        return offsets[grid.clusterCount()];
    }

    public boolean contains(int cluster, int light) {
        for (int e = offsets[cluster]; e < offsets[cluster + 1]; e++) {
            if (indices[e] == light) {
                return true;
            }
        }
        return false;
    }

    /** Bytes of one range record written by {@link #writeRanges}: {@code uvec2(offset, count)}. */
    public static final int RANGE_BYTES = 8;

    /** Writes {@code uvec2(offset, count)} for every cluster at {@code byteOffset}: the {@code clusterRanges[]} storage buffer of a fragment shader. */
    public void writeRanges(ClusterGrid grid, MemorySegment dst, long byteOffset) {
        int n = grid.clusterCount();
        for (int c = 0; c < n; c++) {
            GpuWriter.putInt(dst, byteOffset + (long) c * RANGE_BYTES, offsets[c]);
            GpuWriter.putInt(dst, byteOffset + (long) c * RANGE_BYTES + 4, offsets[c + 1] - offsets[c]);
        }
    }

    /** Writes the light indices ({@code uint} each) at {@code byteOffset}: the {@code clusterLightIndices[]} storage buffer. */
    public void writeIndices(ClusterGrid grid, MemorySegment dst, long byteOffset) {
        int total = offsets[grid.clusterCount()];
        for (int e = 0; e < total; e++) {
            GpuWriter.putInt(dst, byteOffset + 4L * e, indices[e]);
        }
    }
}
