package vmath.samples.demos.globe;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import vmath.geo.TerrainRgb;
import vmath.geo.TileId;

/**
 * Owns the slots that resident tiles live in, loads missing tiles on worker threads and evicts the
 * least recently used ones.
 *
 * <p><b>Slots.</b> A slot is the place of one tile in the GPU's buffers: a block of the vertex buffer
 * and a layer of the image array have the same index. The cache decides which tile is in which slot
 * and the demo uploads the data to it when {@link #poll} hands a loaded tile over. A tile that is
 * wanted but not resident is {@linkplain #request requested}; at most {@code 3 * threads} requests
 * run at a time and the others are dropped (the demo asks again the next frame, so a request never
 * goes stale).
 *
 * <p><b>Eviction.</b> A slot that has not been {@linkplain #touch touched} in the current or the
 * previous frame is free to be reused, the least recently touched first; pinned tiles (the coarsest
 * level, which is the fallback of everything) are never evicted. When no slot can be freed the
 * loaded tile is dropped and requested again later.
 *
 * <p><b>Loading.</b> A worker asks the {@link TileSource} for the tile, decodes the Terrarium
 * heights with {@code TerrainRgb} and builds the vertex block with {@link TileMeshes}; the results
 * wait in a queue for the thread that owns the OpenGL context.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> All methods except the workers' own are called from one thread, the one
 * that owns the OpenGL context; the workers only read the source and put results in a queue.
 */
final class TileCache {

    /**
     * A tile that a worker has finished.
     *
     * @param id the tile
     * @param vertices the vertex block, {@code vertexCount * TileMeshes.FLOATS} floats
     * @param imagery the RGBA image
     * @param reference the ECEF position of the reference point of the vertices
     * @param minHeight the lowest height of the ground in the tile
     * @param maxHeight the highest height of the ground in the tile
     */
    record Loaded(TileId id, float[] vertices, byte[] imagery, double[] reference, float minHeight, float maxHeight) {
    }

    private final TileSource source;
    private final int slots;
    private final int maxInFlight;
    private final ExecutorService pool;
    private final LinkedBlockingQueue<Object> done = new LinkedBlockingQueue<>();
    private final Set<Long> inFlight = new HashSet<>();
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    private final long[] slotKey;
    private final int[] lastUsed;
    private final boolean[] pinned;
    private final double[][] reference;
    private final float[] minHeight;
    private final float[] maxHeight;
    private final long[] mapKeys;
    private final int[] mapValues;
    private final int mapMask;
    private int resident;
    private long loadedCount;
    private long evictedCount;
    private long droppedCount;
    private final AtomicInteger workMicros = new AtomicInteger();

    /**
     * Creates a cache and starts its worker threads.
     *
     * @param source where tiles come from; must not be {@code null}
     * @param slots the number of tiles that can be resident, at least 1
     * @param threads the number of worker threads, at least 1
     */
    TileCache(TileSource source, int slots, int threads) {
        this.source = source;
        this.slots = slots;
        this.maxInFlight = 3 * threads;
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "globe-tiles-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        slotKey = new long[slots];
        lastUsed = new int[slots];
        pinned = new boolean[slots];
        reference = new double[slots][];
        minHeight = new float[slots];
        maxHeight = new float[slots];
        int table = Integer.highestOneBit(4 * slots - 1) << 1;
        mapKeys = new long[table];
        mapValues = new int[table];
        mapMask = table - 1;
        for (int i = slots - 1; i >= 0; i--) {
            free.push(i);
        }
    }

    /**
     * Gives the slot that holds a tile.
     *
     * @param key the tile's {@link TileId#key()}
     * @return the slot, or -1 if the tile is not resident
     */
    int slotOf(long key) {
        int h = hash(key) & mapMask;
        while (mapKeys[h] != 0L) {
            if (mapKeys[h] == key) {
                return mapValues[h];
            }
            h = (h + 1) & mapMask;
        }
        return -1;
    }

    /**
     * Marks a slot as used in a frame, which protects it from eviction.
     *
     * @param slot the slot
     * @param frame the frame number
     */
    void touch(int slot, int frame) {
        lastUsed[slot] = frame;
    }

    /**
     * Makes a resident tile permanent.
     *
     * @param slot the slot
     */
    void pin(int slot) {
        pinned[slot] = true;
    }

    /**
     * Asks for a tile to be loaded, unless it is resident, already requested, or too many requests
     * are running.
     *
     * @param id the tile; must not be {@code null}
     * @return {@code true} if a load was started
     */
    boolean request(TileId id) {
        long key = id.key();
        if (slotOf(key) >= 0 || inFlight.contains(key) || inFlight.size() >= maxInFlight) {
            return false;
        }
        inFlight.add(key);
        pool.execute(() -> done.add(work(id)));
        return true;
    }

    private Object work(TileId id) {
        try {
            long t0 = System.nanoTime();
            int cells = source.cells(), n = cells + 1;
            TileSource.TileData data = source.load(id);
            float[] heights = new float[n * n];
            TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, data.terrarium(), n * n, heights);
            float[] mm = new float[2];
            TerrainRgb.minMax(heights, n * n, mm);
            float[] vertices = new float[TileMeshes.vertexCount(cells) * TileMeshes.FLOATS];
            double[] ref = new double[3];
            TileMeshes.build(id, heights, cells, source.imageSize(), vertices, ref);
            workMicros.addAndGet((int) ((System.nanoTime() - t0) / 1000));
            return new Loaded(id, vertices, data.imagery(), ref, mm[0], mm[1]);
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * Gives the number of requests that are running or whose result has not been collected.
     *
     * @return the count
     */
    int inFlight() {
        return inFlight.size();
    }

    /**
     * Collects a finished tile without waiting.
     *
     * @return the tile, or {@code null} if none is ready
     * @throws IllegalStateException if a worker failed
     */
    Loaded poll() {
        return unwrap(done.poll());
    }

    /**
     * Waits for a finished tile.
     *
     * @return the tile
     * @throws IllegalStateException if a worker failed or the wait was interrupted
     */
    Loaded take() {
        try {
            return unwrap(done.take());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Loaded unwrap(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Throwable t) {
            inFlight.clear();
            throw new IllegalStateException("a tile could not be loaded", t);
        }
        Loaded l = (Loaded) o;
        inFlight.remove(l.id().key());
        return l;
    }

    /**
     * Loads a tile on the calling thread and returns it, without going through the workers.
     *
     * @param id the tile; must not be {@code null}
     * @return the tile
     */
    Loaded loadNow(TileId id) {
        Object o = work(id);
        if (o instanceof Throwable t) {
            throw new IllegalStateException("a tile could not be loaded", t);
        }
        return (Loaded) o;
    }

    /**
     * Gives a loaded tile a slot, evicting the least recently used tile if none is free.
     *
     * @param tile the loaded tile; must not be {@code null}
     * @param frame the current frame number
     * @return the slot, or -1 if every slot was used in the current or the previous frame (the
     *     tile is dropped)
     */
    int place(Loaded tile, int frame) {
        long key = tile.id().key();
        int existing = slotOf(key);
        if (existing >= 0) {
            return existing;
        }
        int slot;
        if (!free.isEmpty()) {
            slot = free.pop();
        } else {
            slot = -1;
            int oldest = Integer.MAX_VALUE;
            for (int s = 0; s < slots; s++) {
                if (!pinned[s] && lastUsed[s] < frame - 1 && lastUsed[s] < oldest) {
                    oldest = lastUsed[s];
                    slot = s;
                }
            }
            if (slot < 0) {
                droppedCount++;
                return -1;
            }
            remove(slotKey[slot]);
            evictedCount++;
            resident--;
        }
        slotKey[slot] = key;
        lastUsed[slot] = frame;
        reference[slot] = tile.reference();
        minHeight[slot] = tile.minHeight();
        maxHeight[slot] = tile.maxHeight();
        insert(key, slot);
        resident++;
        loadedCount++;
        return slot;
    }

    /**
     * Gives the ECEF position of the reference point that the vertices of a resident tile are
     * relative to.
     *
     * @param slot the slot of a resident tile
     * @return three values; do not modify
     */
    double[] reference(int slot) {
        return reference[slot];
    }

    /**
     * Gives the key of the tile in a slot.
     *
     * @param slot the slot of a resident tile
     * @return the tile's {@link TileId#key()}
     */
    long keyOf(int slot) {
        return slotKey[slot];
    }

    /**
     * Gives the number of slots.
     *
     * @return the count
     */
    int slots() {
        return slots;
    }

    /**
     * Gives the number of resident tiles.
     *
     * @return the count
     */
    int resident() {
        return resident;
    }

    /**
     * Gives the number of tiles loaded and placed since the start.
     *
     * @return the count
     */
    long loaded() {
        return loadedCount;
    }

    /**
     * Gives the number of tiles evicted since the start.
     *
     * @return the count
     */
    long evicted() {
        return evictedCount;
    }

    /**
     * Gives the number of loaded tiles that had no slot to go to since the start.
     *
     * @return the count
     */
    long dropped() {
        return droppedCount;
    }

    /**
     * Gives the time that workers have spent producing tiles since the start.
     *
     * @return milliseconds of thread time (the workers run in parallel, so this exceeds the time
     *     that passed)
     */
    double workMillis() {
        return workMicros.get() / 1000.0;
    }

    /**
     * Stops the workers.
     */
    void close() {
        pool.shutdownNow();
    }

    private int hash(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 40);
    }

    private void insert(long key, int value) {
        int h = hash(key) & mapMask;
        while (mapKeys[h] != 0L) {
            h = (h + 1) & mapMask;
        }
        mapKeys[h] = key;
        mapValues[h] = value;
    }

    // linear probing with backward shift, so that no tombstones are needed
    private void remove(long key) {
        int h = hash(key) & mapMask;
        while (mapKeys[h] != key) {
            h = (h + 1) & mapMask;
        }
        int hole = h;
        int next = (hole + 1) & mapMask;
        while (mapKeys[next] != 0L) {
            int ideal = hash(mapKeys[next]) & mapMask;
            boolean movable = hole <= next ? (ideal <= hole || ideal > next) : (ideal <= hole && ideal > next);
            if (movable) {
                mapKeys[hole] = mapKeys[next];
                mapValues[hole] = mapValues[next];
                hole = next;
            }
            next = (next + 1) & mapMask;
        }
        mapKeys[hole] = 0L;
    }
}
