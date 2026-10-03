package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.IntList;
import vmath.geo.Aabbf;
import vmath.geo.Spheref;

/**
 * A uniform grid (spatial hash) over object boxes. Space is cut into cubic cells of one size; each object is registered in
 * every cell its box touches, and a query only looks at the cells it covers. Where a tree adapts to the data, the grid is
 * cheap and flat: insert, remove and move cost a handful of array writes, so it suits many similar-sized objects that move
 * every frame (crowds, particles, projectiles). Choose the cell size near the typical object size or query radius.
 *
 * <p><b>Storage.</b> Everything is in plain {@code int[]}/{@code float[]}/{@code long[]} arrays: per object a box, user data
 * and its cell range; per (object, cell) pair one entry chained into a hash bucket. Nothing is allocated by an operation
 * unless a pool has to grow, and cells that hold nothing cost nothing (there is no dense array, so the world may be large
 * and sparse).
 *
 * <p><b>Big objects.</b> An object that would touch more than {@link #MAX_CELLS} cells is not registered in cells; it goes on
 * a short "oversize" list that every query checks. This keeps a terrain-sized box from costing millions of entries.
 *
 * <p><b>Range.</b> Cell coordinates are 21 bits each, so coordinates must satisfy {@code |v| < 2^20 * cellSize} (a million
 * cells either way); {@link #insert} and {@link #move} throw for NaN, infinite or out-of-range boxes.
 *
 * <p>Each object has a stable integer handle and an {@code int} of user data, as in {@link DynamicAabbTree}. Not thread-safe
 * for updates; concurrent read-only queries are fine with one {@link Query} per thread.
 */
public final class UniformGrid {

    /** Objects touching more cells than this go on the oversize list. */
    public static final int MAX_CELLS = 512;

    private static final int OFFSET = 1 << 20;
    private static final int MAX_CELL = OFFSET - 1;
    private static final int NONE = -1;

    private final float cellSize;
    private final float inverseCell;

    // objects: indexed by handle
    private float[] box = new float[6 * 16];
    private int[] item = new int[16];
    private int[] range = new int[6 * 16];   // ix0 iy0 iz0 ix1 iy1 iz1, inclusive
    private int[] nextFree = new int[16];
    private boolean[] alive = new boolean[16];
    private boolean[] oversize = new boolean[16];
    private int objectHigh;                  // one past the highest handle ever used
    private int freeObject = NONE;
    private int liveCount;

    // entries: one per (object, cell)
    private int[] entryObject = new int[64];
    private long[] entryKey = new long[64];
    private int[] entryNext = new int[64];
    private int entryHigh;
    private int freeEntry = NONE;
    private int entryCount;

    private int[] head;                      // bucket -> first entry
    private int shift;                       // 64 - log2(head.length)

    private int[] bigObjects = new int[8];
    private int bigCount;

    // extents of the cells ever used (never shrink); bound the k-NN search
    private final int[] minCell = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
    private final int[] maxCell = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    /** @param cellSize edge length of a cell; must be positive and finite */
    public UniformGrid(float cellSize) {
        this(cellSize, 64);
    }

    /** @param expectedObjects how many objects to make room for up front */
    public UniformGrid(float cellSize, int expectedObjects) {
        if (!(cellSize > 0f) || Float.isInfinite(cellSize)) {
            throw new IllegalArgumentException("cellSize must be positive and finite: " + cellSize);
        }
        this.cellSize = cellSize;
        this.inverseCell = 1f / cellSize;
        int table = Integer.highestOneBit(Math.max(16, expectedObjects * 2 - 1)) << 1;
        head = new int[table];
        Arrays.fill(head, NONE);
        shift = 64 - Integer.numberOfTrailingZeros(table);
        if (expectedObjects > 16) {
            growObjects(expectedObjects);
        }
    }

    /** The side of a grid cell. */
    public float cellSize() {
        return cellSize;
    }

    /** Number of objects in the grid. */
    public int size() {
        return liveCount;
    }

    /** Number of (object, cell) registrations: how much memory the cells hold. */
    public int entryCount() {
        return entryCount;
    }

    /** Objects that live on the oversize list. */
    public int oversizeCount() {
        return bigCount;
    }

    // ---------------------------------------------------------------- updates

    /** Adds an object with the given box and returns its handle; {@code userData} is what queries report for it. */
    public int insert(Aabbf b, int userData) {
        return insert(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), userData);
    }

    /**
     * Adds an object with the box given by its six bounds and returns its handle; {@code userData} is what queries report for it. The box must be ordered and lie within 2^20 cells of the origin ({@link IllegalArgumentException} otherwise).
     */
    public int insert(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int userData) {
        checkBox(minX, minY, minZ, maxX, maxY, maxZ);
        int h = allocateObject();
        setBox(h, minX, minY, minZ, maxX, maxY, maxZ);
        item[h] = userData;
        alive[h] = true;
        liveCount++;
        int o = h * 6;
        computeRange(range, o, minX, minY, minZ, maxX, maxY, maxZ);
        register(h);
        return h;
    }

    /** Removes the object; its handle becomes invalid (and may be reused by a later insert). */
    public void remove(int handle) {
        requireLive(handle);
        unregister(handle);
        alive[handle] = false;
        nextFree[handle] = freeObject;
        freeObject = handle;
        liveCount--;
    }

    /**
     * Moves the object to a new box. Returns {@code true} if the set of cells changed (and so entries were rewritten),
     * {@code false} if it still covers the same cells and only the box was updated.
     */
    public boolean move(int handle, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        requireLive(handle);
        checkBox(minX, minY, minZ, maxX, maxY, maxZ);
        setBox(handle, minX, minY, minZ, maxX, maxY, maxZ);
        int o = handle * 6;
        int a0 = cellIndex(minX), a1 = cellIndex(minY), a2 = cellIndex(minZ);
        int b0 = cellIndex(maxX), b1 = cellIndex(maxY), b2 = cellIndex(maxZ);
        if (a0 == range[o] && a1 == range[o + 1] && a2 == range[o + 2]
                && b0 == range[o + 3] && b1 == range[o + 4] && b2 == range[o + 5]) {
            return false;
        }
        unregister(handle);
        range[o] = a0;
        range[o + 1] = a1;
        range[o + 2] = a2;
        range[o + 3] = b0;
        range[o + 4] = b1;
        range[o + 5] = b2;
        register(handle);
        return true;
    }

    /** As the six-bounds {@code move}, with the box given as an {@link Aabbf}. */
    public boolean move(int handle, Aabbf b) {
        return move(handle, b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    /** Removes every object. Capacity is kept. */
    public void clear() {
        Arrays.fill(head, NONE);
        Arrays.fill(alive, false);
        Arrays.fill(oversize, false);
        objectHigh = 0;
        freeObject = NONE;
        liveCount = 0;
        entryHigh = 0;
        freeEntry = NONE;
        entryCount = 0;
        bigCount = 0;
        Arrays.fill(minCell, Integer.MAX_VALUE);
        Arrays.fill(maxCell, Integer.MIN_VALUE);
    }

    // ---------------------------------------------------------------- inspection

    /** True when {@code handle} names an object currently in the grid. */
    public boolean isValid(int handle) {
        return handle >= 0 && handle < objectHigh && alive[handle];
    }

    /** The {@code userData} given when the object was inserted; {@link IllegalArgumentException} for a handle that is not in the grid. */
    public int userData(int handle) {
        requireLive(handle);
        return item[handle];
    }

    /** The object's box as last given to {@link #insert} or {@link #move}. */
    public Aabbf bounds(int handle) {
        requireLive(handle);
        int o = handle * 6;
        return new Aabbf(box[o], box[o + 1], box[o + 2], box[o + 3], box[o + 4], box[o + 5]);
    }

    /** Checks the internal invariants and throws {@link IllegalStateException} for the first violation. */
    public void validate() {
        int live = 0;
        int expectedEntries = 0;
        int big = 0;
        for (int h = 0; h < objectHigh; h++) {
            if (!alive[h]) {
                continue;
            }
            live++;
            if (oversize[h]) {
                big++;
                continue;
            }
            int o = h * 6;
            for (int z = range[o + 2]; z <= range[o + 5]; z++) {
                for (int y = range[o + 1]; y <= range[o + 4]; y++) {
                    for (int x = range[o]; x <= range[o + 3]; x++) {
                        expectedEntries++;
                        int found = 0;
                        long key = key(x, y, z);
                        for (int e = head[bucket(key)]; e != NONE; e = entryNext[e]) {
                            if (entryKey[e] == key && entryObject[e] == h) {
                                found++;
                            }
                        }
                        if (found != 1) {
                            throw new IllegalStateException("object " + h + " has " + found + " entries in cell " + x + "," + y + "," + z);
                        }
                    }
                }
            }
            for (int k = 0; k < 6; k++) {
                if (cellIndex(box[o + k]) != range[o + k]) {
                    throw new IllegalStateException("cell range of object " + h + " does not match its box on component " + k);
                }
            }
        }
        if (live != liveCount) {
            throw new IllegalStateException("counted " + live + " objects, size() is " + liveCount);
        }
        if (expectedEntries != entryCount) {
            throw new IllegalStateException("expected " + expectedEntries + " entries, entryCount is " + entryCount);
        }
        if (big != bigCount) {
            throw new IllegalStateException("counted " + big + " oversize objects, list has " + bigCount);
        }
        int chained = 0;
        for (int b = 0; b < head.length; b++) {
            for (int e = head[b]; e != NONE; e = entryNext[e]) {
                chained++;
                if (bucket(entryKey[e]) != b) {
                    throw new IllegalStateException("entry " + e + " is in the wrong bucket");
                }
            }
        }
        if (chained != entryCount) {
            throw new IllegalStateException("buckets hold " + chained + " entries, entryCount is " + entryCount);
        }
    }

    // ---------------------------------------------------------------- cells and hashing

    private int cellIndex(float v) {
        int c = (int) Math.floor(v * inverseCell);
        return c;
    }

    private void checkBox(float x0, float y0, float z0, float x1, float y1, float z1) {
        float limit = (float) MAX_CELL * cellSize;
        if (!(Math.abs(x0) < limit && Math.abs(y0) < limit && Math.abs(z0) < limit
                && Math.abs(x1) < limit && Math.abs(y1) < limit && Math.abs(z1) < limit)) {
            throw new IllegalArgumentException("box must be finite and within +-" + limit + " (2^20 cells): "
                    + x0 + "," + y0 + "," + z0 + " .. " + x1 + "," + y1 + "," + z1);
        }
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            throw new IllegalArgumentException("box has min > max");
        }
    }

    private void computeRange(int[] out, int o, float x0, float y0, float z0, float x1, float y1, float z1) {
        out[o] = cellIndex(x0);
        out[o + 1] = cellIndex(y0);
        out[o + 2] = cellIndex(z0);
        out[o + 3] = cellIndex(x1);
        out[o + 4] = cellIndex(y1);
        out[o + 5] = cellIndex(z1);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + OFFSET) << 42) | ((long) (y + OFFSET) << 21) | (long) (z + OFFSET);
    }

    private int bucket(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
    }

    private static long cellCount(int[] r, int o) {
        return (long) (r[o + 3] - r[o] + 1) * (r[o + 4] - r[o + 1] + 1) * (r[o + 5] - r[o + 2] + 1);
    }

    // ---------------------------------------------------------------- entries

    /** Registers the object in every cell of its (already computed) range, or on the oversize list. */
    private void register(int h) {
        int o = h * 6;
        for (int k = 0; k < 3; k++) {
            minCell[k] = Math.min(minCell[k], range[o + k]);
            maxCell[k] = Math.max(maxCell[k], range[o + 3 + k]);
        }
        if (cellCount(range, o) > MAX_CELLS) {
            oversize[h] = true;
            if (bigCount == bigObjects.length) {
                bigObjects = Arrays.copyOf(bigObjects, bigCount * 2);
            }
            bigObjects[bigCount++] = h;
            return;
        }
        oversize[h] = false;
        for (int z = range[o + 2]; z <= range[o + 5]; z++) {
            for (int y = range[o + 1]; y <= range[o + 4]; y++) {
                for (int x = range[o]; x <= range[o + 3]; x++) {
                    addEntry(h, key(x, y, z));
                }
            }
        }
    }

    private void unregister(int h) {
        if (oversize[h]) {
            for (int i = 0; i < bigCount; i++) {
                if (bigObjects[i] == h) {
                    bigObjects[i] = bigObjects[--bigCount];
                    break;
                }
            }
            oversize[h] = false;
            return;
        }
        int o = h * 6;
        for (int z = range[o + 2]; z <= range[o + 5]; z++) {
            for (int y = range[o + 1]; y <= range[o + 4]; y++) {
                for (int x = range[o]; x <= range[o + 3]; x++) {
                    removeEntry(h, key(x, y, z));
                }
            }
        }
    }

    private void addEntry(int h, long key) {
        if (entryCount >= head.length) {
            growTable();
        }
        int e;
        if (freeEntry != NONE) {
            e = freeEntry;
            freeEntry = entryNext[e];
        } else {
            if (entryHigh == entryObject.length) {
                int n = entryHigh * 2;
                entryObject = Arrays.copyOf(entryObject, n);
                entryKey = Arrays.copyOf(entryKey, n);
                entryNext = Arrays.copyOf(entryNext, n);
            }
            e = entryHigh++;
        }
        int b = bucket(key);
        entryObject[e] = h;
        entryKey[e] = key;
        entryNext[e] = head[b];
        head[b] = e;
        entryCount++;
    }

    private void removeEntry(int h, long key) {
        int b = bucket(key);
        int prev = NONE;
        for (int e = head[b]; e != NONE; prev = e, e = entryNext[e]) {
            if (entryKey[e] == key && entryObject[e] == h) {
                if (prev == NONE) {
                    head[b] = entryNext[e];
                } else {
                    entryNext[prev] = entryNext[e];
                }
                entryObject[e] = NONE;
                entryNext[e] = freeEntry;
                freeEntry = e;
                entryCount--;
                return;
            }
        }
        throw new IllegalStateException("missing entry for object " + h);
    }

    /** Doubles the bucket table and relinks every live entry. */
    private void growTable() {
        int n = head.length * 2;
        head = new int[n];
        Arrays.fill(head, NONE);
        shift = 64 - Integer.numberOfTrailingZeros(n);
        for (int e = 0; e < entryHigh; e++) {
            if (entryObject[e] != NONE) {
                int b = bucket(entryKey[e]);
                entryNext[e] = head[b];
                head[b] = e;
            }
        }
        // the free chain shared entryNext with the old buckets: rebuild it from the dead entries
        freeEntry = NONE;
        for (int e = entryHigh - 1; e >= 0; e--) {
            if (entryObject[e] == NONE) {
                entryNext[e] = freeEntry;
                freeEntry = e;
            }
        }
    }

    // ---------------------------------------------------------------- objects

    private int allocateObject() {
        int h;
        if (freeObject != NONE) {
            h = freeObject;
            freeObject = nextFree[h];
        } else {
            if (objectHigh == item.length) {
                growObjects(objectHigh * 2);
            }
            h = objectHigh++;
        }
        return h;
    }

    private void growObjects(int capacity) {
        capacity = Math.max(capacity, item.length);
        box = Arrays.copyOf(box, capacity * 6);
        item = Arrays.copyOf(item, capacity);
        range = Arrays.copyOf(range, capacity * 6);
        nextFree = Arrays.copyOf(nextFree, capacity);
        alive = Arrays.copyOf(alive, capacity);
        oversize = Arrays.copyOf(oversize, capacity);
    }

    private void setBox(int h, float x0, float y0, float z0, float x1, float y1, float z1) {
        int o = h * 6;
        box[o] = x0;
        box[o + 1] = y0;
        box[o + 2] = z0;
        box[o + 3] = x1;
        box[o + 4] = y1;
        box[o + 5] = z1;
    }

    private void requireLive(int handle) {
        if (!isValid(handle)) {
            throw new IllegalArgumentException("not a valid object handle: " + handle);
        }
    }

    // ---------------------------------------------------------------- queries

    /** A query object with its own duplicate-suppression state. Create one per thread; reuse it. */
    public Query newQuery() {
        return new Query(this);
    }

    /** Read-only queries on a {@link UniformGrid}. Results are the objects' user data. */
    public static final class Query {

        private final UniformGrid grid;
        private int[] stamp = new int[16];
        private int epoch;

        private Query(UniformGrid grid) {
            this.grid = grid;
        }

        private void begin() {
            if (stamp.length < grid.objectHigh) {
                stamp = Arrays.copyOf(stamp, Math.max(grid.objectHigh, stamp.length * 2));
            }
            if (++epoch == Integer.MAX_VALUE) {
                Arrays.fill(stamp, 0);
                epoch = 1;
            }
        }

        /** Appends the user data of every object whose box overlaps {@code b} (touching counts). */
        public void overlapAabb(Aabbf b, IntList out) {
            overlap(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), 0f, 0f, 0f, -1f, out);
        }

        /** Appends the user data of every object whose box touches {@code s}. */
        public void overlapSphere(Spheref s, IntList out) {
            float r = s.radius();
            // pad the search region by a few ulps so rounding of centre -/+ radius can never lose a boundary object
            float pad = 4f * Math.ulp(Math.max(Math.abs(s.cx()) + Math.abs(s.cy()) + Math.abs(s.cz()), r));
            float e = r + pad;
            overlap(s.cx() - e, s.cy() - e, s.cz() - e, s.cx() + e, s.cy() + e, s.cz() + e,
                    s.cx(), s.cy(), s.cz(), r * r, out);
        }

        /**
         * Shared implementation: the query region is the box {@code [x0..z1]}; when {@code r2 >= 0} the objects are also
         * required to be within {@code sqrt(r2)} of the centre.
         */
        private void overlap(float x0, float y0, float z0, float x1, float y1, float z1,
                             float cx, float cy, float cz, float r2, IntList out) {
            UniformGrid g = grid;
            if (g.liveCount == 0) {
                return;
            }
            begin();
            float[] bx = g.box;
            // big objects are always candidates
            for (int i = 0, n = g.bigCount; i < n; i++) {
                int h = g.bigObjects[i];
                if (accepts(bx, h * 6, x0, y0, z0, x1, y1, z1, cx, cy, cz, r2)) {
                    out.add(g.item[h]);
                }
            }
            if (!(x0 <= x1 && y0 <= y1 && z0 <= z1)) {
                return;
            }
            long limit = (long) MAX_CELL;
            int i0 = clampCell(g.cellIndex(clampCoord(x0, g)), limit), j0 = clampCell(g.cellIndex(clampCoord(y0, g)), limit);
            int k0 = clampCell(g.cellIndex(clampCoord(z0, g)), limit);
            int i1 = clampCell(g.cellIndex(clampCoord(x1, g)), limit), j1 = clampCell(g.cellIndex(clampCoord(y1, g)), limit);
            int k1 = clampCell(g.cellIndex(clampCoord(z1, g)), limit);
            long cells = (long) (i1 - i0 + 1) * (j1 - j0 + 1) * (k1 - k0 + 1);
            if (cells > g.entryCount) {
                // the region covers more cells than there are entries: scan the objects instead
                for (int h = 0; h < g.objectHigh; h++) {
                    if (g.alive[h] && !g.oversize[h] && accepts(bx, h * 6, x0, y0, z0, x1, y1, z1, cx, cy, cz, r2)) {
                        out.add(g.item[h]);
                    }
                }
                return;
            }
            for (int k = k0; k <= k1; k++) {
                for (int j = j0; j <= j1; j++) {
                    for (int i = i0; i <= i1; i++) {
                        long key = key(i, j, k);
                        for (int e = g.head[g.bucket(key)]; e != NONE; e = g.entryNext[e]) {
                            if (g.entryKey[e] != key) {
                                continue;
                            }
                            int h = g.entryObject[e];
                            if (stamp[h] == epoch) {
                                continue;
                            }
                            stamp[h] = epoch;
                            if (accepts(bx, h * 6, x0, y0, z0, x1, y1, z1, cx, cy, cz, r2)) {
                                out.add(g.item[h]);
                            }
                        }
                    }
                }
            }
        }

        private static boolean accepts(float[] b, int o, float x0, float y0, float z0, float x1, float y1, float z1,
                                       float cx, float cy, float cz, float r2) {
            if (b[o] > x1 || b[o + 3] < x0 || b[o + 1] > y1 || b[o + 4] < y0 || b[o + 2] > z1 || b[o + 5] < z0) {
                return false;
            }
            return r2 < 0f || NodeTests.boxDistanceSquared(b, o, cx, cy, cz) <= r2;
        }

        private static float clampCoord(float v, UniformGrid g) {
            float limit = (float) MAX_CELL * g.cellSize;
            return v != v ? 0f : Math.max(-limit, Math.min(limit, v));
        }

        private static int clampCell(int c, long limit) {
            return (int) Math.max(-limit, Math.min(limit, c));
        }

        /**
         * The {@code out.k()} objects whose boxes are closest to the point, nearest first; {@code out} holds their user
         * data (ties: smaller user data). Searches outward in shells of cells and stops once no unseen object can be closer
         * than the worst neighbour kept; visiting more than the occupied cell extents is never necessary.
         */
        public void nearest(float x, float y, float z, Neighbors out) {
            UniformGrid g = grid;
            out.reset();
            if (g.liveCount == 0 || x != x || y != y || z != z) {
                return;
            }
            begin();
            float[] bx = g.box;
            for (int i = 0, n = g.bigCount; i < n; i++) {
                int h = g.bigObjects[i];
                out.offer(g.item[h], NodeTests.boxDistanceSquared(bx, h * 6, x, y, z));
            }
            long limit = (long) MAX_CELL;
            int cx = clampCell(g.cellIndex(clampCoord(x, g)), limit);
            int cy = clampCell(g.cellIndex(clampCoord(y, g)), limit);
            int cz = clampCell(g.cellIndex(clampCoord(z, g)), limit);
            int[] lo = g.minCell, hi = g.maxCell;
            if (lo[0] > hi[0]) {
                out.finish(); // only oversize objects
                return;
            }
            int maxR = Math.max(Math.max(Math.max(cx - lo[0], hi[0] - cx), Math.max(cy - lo[1], hi[1] - cy)),
                    Math.max(cz - lo[2], hi[2] - cz));
            maxR = Math.max(maxR, 0);
            for (int r = 0; r <= maxR; r++) {
                int xa = Math.max(cx - r, lo[0]), xb = Math.min(cx + r, hi[0]);
                int ya = Math.max(cy - r, lo[1]), yb = Math.min(cy + r, hi[1]);
                int za = Math.max(cz - r, lo[2]), zb = Math.min(cz + r, hi[2]);
                for (int k = za; k <= zb; k++) {
                    boolean zShell = Math.abs(k - cz) == r;
                    for (int j = ya; j <= yb; j++) {
                        boolean yShell = zShell || Math.abs(j - cy) == r;
                        if (yShell) {
                            for (int i = xa; i <= xb; i++) {
                                visitCell(i, j, k, x, y, z, out);
                            }
                        } else {
                            if (cx - r >= lo[0]) {
                                visitCell(cx - r, j, k, x, y, z, out);
                            }
                            if (r > 0 && cx + r <= hi[0]) {
                                visitCell(cx + r, j, k, x, y, z, out);
                            }
                        }
                    }
                }
                // every unseen object lies outside the cube of cells [c-r, c+r], so it is at least r cells (less a hair, for
                // the rounding of cell indices) away from the point
                float reach = Math.max(0f, (r - 1e-3f) * g.cellSize);
                if (out.bound() < reach * reach) {
                    break;
                }
            }
            out.finish();
        }

        private void visitCell(int i, int j, int k, float x, float y, float z, Neighbors out) {
            UniformGrid g = grid;
            long key = key(i, j, k);
            for (int e = g.head[g.bucket(key)]; e != NONE; e = g.entryNext[e]) {
                if (g.entryKey[e] != key) {
                    continue;
                }
                int h = g.entryObject[e];
                if (stamp[h] == epoch) {
                    continue;
                }
                stamp[h] = epoch;
                out.offer(g.item[h], NodeTests.boxDistanceSquared(g.box, h * 6, x, y, z));
            }
        }
    }
}
