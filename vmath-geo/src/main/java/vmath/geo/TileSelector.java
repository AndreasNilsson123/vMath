package vmath.geo;

import vmath.core.Vec3d;
import vmath.core.Wgs84;

/**
 * Chooses the map tiles to draw for a camera: a quadtree walk from the coarse tiles down, splitting
 * a tile while its mesh is too coarse on the screen, skipping what the frustum and the horizon hide,
 * and keeping neighbouring tiles within one zoom level of each other.
 *
 * <p><b>Refinement.</b> A tile is a grid of {@code cells} by {@code cells} quads, so its vertices are
 * about {@code 2 pi a cos(lat) / (2^zoom cells)} metres apart on the ground ({@link #vertexSpacing},
 * measured at the edge nearest the equator, where the tile is widest). The selector projects that
 * spacing at the distance from the camera to the tile's {@linkplain TileBounds box} and splits the
 * tile into its four children while the result, {@code spacing * viewportHeight / (2 distance
 * tan(fovY / 2))}, exceeds the allowed number of pixels, up to the maximum zoom. A camera inside a
 * tile's box therefore always refines it fully. The vertex spacing, not an error estimate of the
 * data, is the measure: it is what a mesh of this grid can resolve, and a data set with finer
 * detail is shown no finer.
 *
 * <p><b>Culling.</b> A tile that is outside the view frustum (box against the six planes) or hidden
 * behind the horizon ({@link HorizonCuller}, on the bounding sphere) is not drawn and not refined.
 * Both tests are conservative.
 *
 * <p><b>Balance.</b> After the refinement, a tile that is drawn at zoom {@code z} makes every
 * neighbouring region (the eight tiles around it) subdivided at least to {@code z - 1}, splitting
 * coarser tiles where needed, so the zoom levels of two tiles that touch differ by at most one.
 * With that guarantee a skirt (a flap hung down from the border of a tile) of the size of one
 * coarse cell hides every crack between levels. The splits that the balance makes are not culled
 * individually; they are tested like the others before the result is written.
 *
 * <p><b>Limits.</b> The result holds at most {@code capacity} tiles and the walk at most
 * {@code 6 * capacity} nodes. When a limit is reached the refinement stops (the coarse tiles stay,
 * the picture is still complete but coarser, and {@link #truncated()} says so); tiles beyond the
 * capacity are dropped from the result and {@link #overflowed()} is set.
 *
 * <p><b>Cost.</b> The boxes of the tiles are computed once and kept (a few thousand at a time; the
 * cache is cleared when it is full), so a frame costs the tests of the visited nodes. The working
 * memory is allocated in the constructor; a call to {@link #select} allocates the small horizon
 * culler and, for a tile whose box is not cached yet, the temporaries of {@link
 * TileBounds#compute}, and nothing else.
 *
 * <p><b>Output order.</b> The tiles come in the order of the walk (the depth-first order of the
 * stack, which is the same for the same input), then the tiles that the balance created, and the
 * result is identical for identical arguments.
 *
 * <p><b>Thread safety.</b> Not thread-safe: an instance keeps its result and working memory, so one
 * thread at a time may call {@link #select} and read the result.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * TileSelector selector = new TileSelector(2, 16, -500.0, 9000.0, 32, 2048);
 * Frustumd frustum = Frustumd.fromViewProjection(viewProjection, DepthRange.ZERO_TO_ONE);
 * int n = selector.select(frustum, cameraEcef, fovY, 1080, 8.0, true);
 * for (int i = 0; i < n; i++) {
 *     draw(selector.zoom(i), selector.x(i), selector.y(i));
 * }
 * }</pre>
 */
public final class TileSelector {

    private static final int LEAF_VISIBLE = 1;
    private static final int LEAF_CULLED = 2;
    private static final int INTERNAL = 3;
    private static final int LEAF_NEW = 4;

    private final int minZoom;
    private final int maxZoom;
    private final double minHeight;
    private final double maxHeight;
    private final int cells;
    private final int capacity;
    private final int nodeLimit;

    private final long[] nodeKeys;
    private final byte[] nodeState;
    private final int[] usedSlots; // the slots of nodeKeys that hold a key, in the order they were filled
    private final int nodeMask;
    private int nodeCount;

    private final long[] leaves;
    private int leafCount;
    private final long[] stack;

    private final long[] boundsKeys;
    private final double[] boundsData;
    private final double[] scratch = new double[TileBounds.SIZE];
    private final int boundsMask;
    private int boundsCount;

    private final int[] zooms;
    private final int[] xs;
    private final int[] ys;
    private int count;

    private boolean truncated;
    private boolean overflowed;
    private int visited;
    private int frustumCulled;
    private int horizonCulled;
    private int balanceSplits;

    private double camX;
    private double camY;
    private double camZ;
    private Frustumd frustum;
    private HorizonCuller horizon;

    /**
     * Creates a selector and allocates its working memory.
     *
     * @param minZoom the zoom of the coarsest tiles, 0 to {@code maxZoom}; the whole map is
     *     {@code 4^minZoom} tiles, so use 1 or 2 when a single mesh could not hold the globe
     * @param maxZoom the finest zoom, at most {@link TileId#MAX_ZOOM}
     * @param minHeight the lowest ground height in the data, in metres above the ellipsoid; the
     *     boxes of all tiles use the same height range
     * @param maxHeight the highest ground height; at least {@code minHeight}
     * @param cells the number of quads along a tile edge of the mesh, at least 1
     * @param capacity the largest number of tiles in a result, at least 4
     * @throws IllegalArgumentException if an argument is out of range
     */
    public TileSelector(int minZoom, int maxZoom, double minHeight, double maxHeight, int cells, int capacity) {
        if (minZoom < 0 || maxZoom < minZoom || maxZoom > TileId.MAX_ZOOM || minZoom > 12) {
            throw new IllegalArgumentException("zoom range " + minZoom + ".." + maxZoom);
        }
        if (!(maxHeight >= minHeight) || cells < 1 || capacity < 4 || (1 << (2 * minZoom)) > capacity) {
            throw new IllegalArgumentException("height " + minHeight + ".." + maxHeight + ", cells " + cells + " or capacity " + capacity);
        }
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.cells = cells;
        this.capacity = capacity;
        this.nodeLimit = 6 * capacity;
        int tableSize = Integer.highestOneBit(2 * nodeLimit - 1) << 1;
        nodeKeys = new long[tableSize];
        nodeState = new byte[tableSize];
        usedSlots = new int[tableSize];
        nodeMask = tableSize - 1;
        leaves = new long[nodeLimit + 8];
        stack = new long[nodeLimit + 8];
        int boundsCapacity = Math.max(2048, 4 * capacity);
        int boundsTable = Integer.highestOneBit(2 * boundsCapacity - 1) << 1;
        boundsKeys = new long[boundsTable];
        boundsData = new double[boundsTable * TileBounds.SIZE];
        boundsMask = boundsTable - 1;
        zooms = new int[capacity];
        xs = new int[capacity];
        ys = new int[capacity];
    }

    /**
     * Gives the distance between the vertices of a tile's mesh on the ground, which the selector
     * compares with the allowed number of pixels.
     *
     * @param zoom the zoom level
     * @param y the row of the tile
     * @param cells the number of quads along the tile edge
     * @return metres, measured at the tile's edge nearest the equator
     */
    public static double vertexSpacing(int zoom, int y, int cells) {
        double n = 1 << zoom;
        double latNear = WebMercator.latitudeOfV((double) y / n);
        double latFar = WebMercator.latitudeOfV((y + 1.0) / n);
        double cos = latNear * latFar <= 0.0 ? 1.0 : Math.cos(Math.min(Math.abs(latNear), Math.abs(latFar)));
        return 2.0 * Math.PI * Wgs84.A * cos / (n * cells);
    }

    /**
     * Chooses the tiles for a camera.
     *
     * @param view the view frustum in ECEF coordinates (build it from the view-projection matrix
     *     of the camera in the same frame); must not be {@code null}
     * @param camera the camera position in ECEF metres; must not be {@code null}
     * @param fovY the vertical field of view in radians, between 0 and pi
     * @param viewportHeight the height of the view in pixels, positive
     * @param maxSpacingPixels the largest distance in pixels that the vertices of a tile may have
     *     on the screen, positive; smaller values select finer tiles
     * @param useHorizon whether to cull tiles behind the horizon
     * @return the number of tiles in the result, which {@link #zoom}, {@link #x} and {@link #y}
     *     describe
     */
    public int select(Frustumd view, Vec3d camera, double fovY, int viewportHeight, double maxSpacingPixels, boolean useHorizon) {
        try {
            return selectTiles(view, camera, fovY, viewportHeight, maxSpacingPixels, useHorizon);
        } finally {
            // the selector does not keep the view or the horizon of the call, also when the call fails
            frustum = null;
            horizon = null;
        }
    }

    private int selectTiles(Frustumd view, Vec3d camera, double fovY, int viewportHeight, double maxSpacingPixels, boolean useHorizon) {
        frustum = view;
        camX = camera.x();
        camY = camera.y();
        camZ = camera.z();
        horizon = useHorizon ? new HorizonCuller(camera) : null;
        double pixelsPerMetreAtOne = viewportHeight / (2.0 * Math.tan(0.5 * fovY));
        double maxSpacingMetresPerDistance = maxSpacingPixels / pixelsPerMetreAtOne;
        resetState();

        int sp = 0;
        int n0 = 1 << minZoom;
        for (int y = n0 - 1; y >= 0; y--) {
            for (int x = n0 - 1; x >= 0; x--) {
                stack[sp++] = pack(minZoom, x, y);
                insert(pack(minZoom, x, y), LEAF_VISIBLE);
            }
        }
        while (sp > 0) {
            long key = stack[--sp];
            int z = zoomOf(key), x = xOf(key), y = yOf(key);
            visited++;
            int b = bounds(key, z, x, y);
            boolean culled = cull(b, true);
            if (culled) {
                setState(key, LEAF_CULLED);
                leaves[leafCount++] = key;
                continue;
            }
            if (z < maxZoom && nodeCount + 4 <= nodeLimit && leafCount + sp + 4 <= nodeLimit) {
                double dist = distanceToBox(b);
                double spacing = vertexSpacing(z, y, cells);
                if (spacing > maxSpacingMetresPerDistance * dist) {
                    setState(key, INTERNAL);
                    for (int c = 3; c >= 0; c--) {
                        long child = pack(z + 1, (x << 1) | (c & 1), (y << 1) | (c >> 1));
                        insert(child, LEAF_VISIBLE);
                        stack[sp++] = child;
                    }
                    continue;
                }
            } else if (z < maxZoom) {
                truncated = true;
            }
            leaves[leafCount++] = key;
        }

        balance();

        for (int i = 0; i < leafCount; i++) {
            long key = leaves[i];
            int st = stateOf(key);
            if (st != LEAF_VISIBLE && st != LEAF_NEW) {
                continue;
            }
            if (st == LEAF_NEW && cull(bounds(key, zoomOf(key), xOf(key), yOf(key)), false)) {
                continue;
            }
            if (count == capacity) {
                overflowed = true;
                break;
            }
            zooms[count] = zoomOf(key);
            xs[count] = xOf(key);
            ys[count] = yOf(key);
            count++;
        }
        return count;
    }

    // empties the node table by clearing the slots that were used, so the cost follows the tiles of the last call and not the size of the table, and zeroes the counters
    private void resetState() {
        for (int i = 0; i < nodeCount; i++) {
            nodeKeys[usedSlots[i]] = 0L;
        }
        nodeCount = 0;
        leafCount = 0;
        count = 0;
        truncated = false;
        overflowed = false;
        visited = 0;
        frustumCulled = 0;
        horizonCulled = 0;
        balanceSplits = 0;
    }

    private void balance() {
        for (int z = maxZoom; z > minZoom; z--) {
            for (int i = 0; i < leafCount; i++) {
                long key = leaves[i];
                if (zoomOf(key) != z) {
                    continue;
                }
                int st = stateOf(key);
                if (st != LEAF_VISIBLE && st != LEAF_NEW) {
                    continue;
                }
                int x = xOf(key), y = yOf(key), n = 1 << z;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= n) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx != 0 || dy != 0) {
                            ensureNode(z - 1, Math.floorMod(x + dx, n) >> 1, ny >> 1);
                        }
                    }
                }
            }
        }
    }

    // makes sure that tile (z, x, y) exists in the tree, splitting the coarser leaf that covers it
    private void ensureNode(int z, int x, int y) {
        if (find(pack(z, x, y)) >= 0) {
            return;
        }
        int level = z;
        while (level > minZoom && find(pack(level, x >> (z - level), y >> (z - level))) < 0) {
            level--;
        }
        for (; level < z; level++) {
            int shift = z - level;
            int qx = x >> shift, qy = y >> shift;
            long q = pack(level, qx, qy);
            if (nodeCount + 4 > nodeLimit || leafCount + 4 > leaves.length) {
                truncated = true;
                return;
            }
            setState(q, INTERNAL);
            balanceSplits++;
            for (int c = 0; c < 4; c++) {
                long child = pack(level + 1, (qx << 1) | (c & 1), (qy << 1) | (c >> 1));
                insert(child, LEAF_NEW);
                leaves[leafCount++] = child;
            }
        }
    }

    private boolean cull(int b, boolean count) {
        double[] d = boundsData;
        double cx = d[b + TileBounds.CENTER], cy = d[b + TileBounds.CENTER + 1], cz = d[b + TileBounds.CENTER + 2];
        double hx = d[b + TileBounds.HALF], hy = d[b + TileBounds.HALF + 1], hz = d[b + TileBounds.HALF + 2];
        int a = b + TileBounds.AXES;
        for (int i = 0; i < 6; i++) {
            Planed p = frustum.plane(i);
            double s = p.nx() * cx + p.ny() * cy + p.nz() * cz + p.d();
            double r = hx * Math.abs(p.nx() * d[a] + p.ny() * d[a + 1] + p.nz() * d[a + 2])
                    + hy * Math.abs(p.nx() * d[a + 3] + p.ny() * d[a + 4] + p.nz() * d[a + 5])
                    + hz * Math.abs(p.nx() * d[a + 6] + p.ny() * d[a + 7] + p.nz() * d[a + 8]);
            if (s + r < 0.0) {
                if (count) {
                    frustumCulled++;
                }
                return true;
            }
        }
        if (horizon != null && horizon.isHidden(cx, cy, cz, d[b + TileBounds.RADIUS])) {
            if (count) {
                horizonCulled++;
            }
            return true;
        }
        return false;
    }

    private double distanceToBox(int b) {
        double[] d = boundsData;
        double px = camX - d[b + TileBounds.CENTER], py = camY - d[b + TileBounds.CENTER + 1], pz = camZ - d[b + TileBounds.CENTER + 2];
        int a = b + TileBounds.AXES;
        double sum = 0.0;
        for (int k = 0; k < 3; k++) {
            double l = Math.abs(px * d[a + 3 * k] + py * d[a + 3 * k + 1] + pz * d[a + 3 * k + 2]) - d[b + TileBounds.HALF + k];
            if (l > 0.0) {
                sum += l * l;
            }
        }
        return Math.sqrt(sum);
    }

    private int bounds(long key, int z, int x, int y) {
        int h = hash(key) & boundsMask;
        while (boundsKeys[h] != 0L) {
            if (boundsKeys[h] == key) {
                return h * TileBounds.SIZE;
            }
            h = (h + 1) & boundsMask;
        }
        if (boundsCount >= (boundsMask + 1) / 2) {
            java.util.Arrays.fill(boundsKeys, 0L);
            boundsCount = 0;
            h = hash(key) & boundsMask;
            while (boundsKeys[h] != 0L) {
                h = (h + 1) & boundsMask;
            }
        }
        boundsKeys[h] = key;
        boundsCount++;
        TileBounds.compute(z, x, y, minHeight, maxHeight, scratch);
        System.arraycopy(scratch, 0, boundsData, h * TileBounds.SIZE, TileBounds.SIZE);
        return h * TileBounds.SIZE;
    }

    private int hash(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 40);
    }

    // not the encoding of TileId.key(), which interleaves the bits of x and y: this one keeps the three fields apart so that zoomOf, xOf and yOf are a shift and a mask, and the top bit keeps a key from ever being 0, the empty marker of the tables
    private static long pack(int z, int x, int y) {
        return (1L << 63) | ((long) z << 58) | ((long) x << 29) | y;
    }

    private static int zoomOf(long key) {
        return (int) ((key >>> 58) & 31);
    }

    private static int xOf(long key) {
        return (int) ((key >>> 29) & 0x1FFFFFFF);
    }

    private static int yOf(long key) {
        return (int) (key & 0x1FFFFFFF);
    }

    private int find(long key) {
        int h = hash(key) & nodeMask;
        while (nodeKeys[h] != 0L) {
            if (nodeKeys[h] == key) {
                return h;
            }
            h = (h + 1) & nodeMask;
        }
        return -1;
    }

    private void insert(long key, int state) {
        int h = hash(key) & nodeMask;
        while (nodeKeys[h] != 0L && nodeKeys[h] != key) {
            h = (h + 1) & nodeMask;
        }
        if (nodeKeys[h] == 0L) {
            nodeKeys[h] = key;
            usedSlots[nodeCount++] = h;
        }
        nodeState[h] = (byte) state;
    }

    private void setState(long key, int state) {
        nodeState[find(key)] = (byte) state;
    }

    private int stateOf(long key) {
        return nodeState[find(key)];
    }

    /**
     * Gives the number of tiles in the last result.
     *
     * @return the count
     */
    public int count() {
        return count;
    }

    /**
     * Gives the zoom level of a tile of the last result.
     *
     * @param i the index, 0 to {@code count() - 1}
     * @return the zoom
     */
    public int zoom(int i) {
        return zooms[i];
    }

    /**
     * Gives the column of a tile of the last result.
     *
     * @param i the index, 0 to {@code count() - 1}
     * @return the column
     */
    public int x(int i) {
        return xs[i];
    }

    /**
     * Gives the row of a tile of the last result.
     *
     * @param i the index, 0 to {@code count() - 1}
     * @return the row
     */
    public int y(int i) {
        return ys[i];
    }

    /**
     * Tells whether a limit stopped the refinement in the last call.
     *
     * @return {@code true} if some tile is coarser than the allowed pixels because of the node
     *     limit
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Tells whether the last result was cut at the capacity.
     *
     * @return {@code true} if tiles were dropped from the result
     */
    public boolean overflowed() {
        return overflowed;
    }

    /**
     * Gives the number of nodes that the last call tested.
     *
     * @return the count, including the nodes that were culled
     */
    public int visited() {
        return visited;
    }

    /**
     * Gives the number of nodes that the frustum removed in the last call.
     *
     * @return the count
     */
    public int frustumCulled() {
        return frustumCulled;
    }

    /**
     * Gives the number of nodes that the horizon removed in the last call (those that the frustum
     * kept).
     *
     * @return the count
     */
    public int horizonCulled() {
        return horizonCulled;
    }

    /**
     * Gives the number of tiles that the balance split in the last call.
     *
     * @return the count
     */
    public int balanceSplits() {
        return balanceSplits;
    }
}
