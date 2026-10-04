package vmath.samples.demos.streaming;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The synthetic world of the streaming demo, without OpenGL: a square grid of cells, each with a
 * chunk of points whose size depends on the cell, the order in which the cells around a position are
 * loaded, and the path of the camera.
 *
 * <p><b>Chunks.</b> The chunk of a cell has {@link #MIN_POINTS} to {@link #MAX_POINTS} points of
 * {@link #POINT_BYTES} bytes each (three floats for the position and one word for the colour), the
 * count chosen by a hash of the cell, so the sizes are unevenly distributed like those of real
 * streamed assets. {@link #fill} writes the content of a chunk, which depends only on the cell, so
 * that what the GPU holds can be checked against it at any time.
 *
 * <p><b>Order.</b> {@link #order} lists the cell offsets within a radius, nearest first; the demo
 * walks it to find the cells that are missing around the camera.
 *
 * <p><b>Path.</b> {@link #camera} gives the position of the camera in cells at a time: a smooth
 * loop over the world at a speed, which jumps to a new place every {@code teleport} seconds (a
 * teleport empties the whole neighbourhood at once, the heaviest load a streamer sees).
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Immutable: the methods may be called from any number of threads.
 */
final class StreamPlan {

    /**
     * The fewest points in a chunk.
     */
    static final int MIN_POINTS = 400;

    /**
     * The most points in a chunk.
     */
    static final int MAX_POINTS = 6000;

    /**
     * The size of a point in bytes.
     */
    static final int POINT_BYTES = 16;

    /**
     * The size of a cell in metres.
     */
    static final float CELL = 32f;

    private final int world;
    private final int radius;
    private final int[] orderDx;
    private final int[] orderDz;

    /**
     * Creates the plan.
     *
     * @param world the number of cells along a side of the world, at least {@code 2 * radius + 2}
     * @param radius the radius of the loaded neighbourhood in cells, at least 1
     */
    StreamPlan(int world, int radius) {
        this.world = world;
        this.radius = radius;
        int n = (2 * radius + 1) * (2 * radius + 1);
        long[] keys = new long[n];
        int k = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radius * radius + radius) {
                    keys[k++] = ((long) (dx * dx + dz * dz) << 32) | (((dx + radius) & 0xFFFF) << 16) | ((dz + radius) & 0xFFFF);
                }
            }
        }
        java.util.Arrays.sort(keys, 0, k);
        orderDx = new int[k];
        orderDz = new int[k];
        for (int i = 0; i < k; i++) {
            orderDx[i] = (int) ((keys[i] >> 16) & 0xFFFF) - radius;
            orderDz[i] = (int) (keys[i] & 0xFFFF) - radius;
        }
    }

    /**
     * Gives the number of cells along a side of the world.
     *
     * @return the number of cells
     */
    int world() {
        return world;
    }

    /**
     * Gives the radius of the loaded neighbourhood.
     *
     * @return the radius in cells
     */
    int radius() {
        return radius;
    }

    /**
     * Gives the number of cells in the neighbourhood (a disc of the radius).
     *
     * @return the number of offsets of {@link #dx}
     */
    int neighbourhood() {
        return orderDx.length;
    }

    /**
     * Gives the column offset of the i-th nearest cell of a neighbourhood.
     *
     * @param i the index, 0 to {@code neighbourhood() - 1}
     * @return the offset in cells
     */
    int dx(int i) {
        return orderDx[i];
    }

    /**
     * Gives the row offset of the i-th nearest cell of a neighbourhood.
     *
     * @param i the index, 0 to {@code neighbourhood() - 1}
     * @return the offset in cells
     */
    int dz(int i) {
        return orderDz[i];
    }

    private static int hash(int x, int z, int salt) {
        int h = x * 0x27d4eb2d ^ z * 0x165667b1 ^ salt * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /**
     * Gives the number of points in the chunk of a cell.
     *
     * @param cx the column of the cell
     * @param cz the row of the cell
     * @return the count, from {@link #MIN_POINTS} to {@link #MAX_POINTS}; skewed to small chunks
     */
    static int points(int cx, int cz) {
        double u = (hash(cx, cz, 1) >>> 8) / (double) (1 << 24);
        return MIN_POINTS + (int) ((MAX_POINTS - MIN_POINTS) * u * u);
    }

    /**
     * Gives the size of the chunk of a cell in bytes.
     *
     * @param cx the column of the cell
     * @param cz the row of the cell
     * @return the size, a multiple of {@link #POINT_BYTES}
     */
    static int bytes(int cx, int cz) {
        return points(cx, cz) * POINT_BYTES;
    }

    /**
     * Writes the content of the chunk of a cell: every point is a position inside the cell, with a
     * height from a smooth function of the position, and a colour word.
     *
     * @param cx the column of the cell
     * @param cz the row of the cell
     * @param dst receives {@code bytes(cx, cz)} bytes; must not be {@code null}
     * @param offset the offset in {@code dst}, a multiple of 4
     */
    static void fill(int cx, int cz, MemorySegment dst, long offset) {
        int n = points(cx, cz);
        int h = hash(cx, cz, 2);
        for (int i = 0; i < n; i++) {
            h = h * 1664525 + 1013904223;
            float fx = (h >>> 8) / (float) (1 << 24);
            h = h * 1664525 + 1013904223;
            float fz = (h >>> 8) / (float) (1 << 24);
            float x = (cx + fx) * CELL, z = (cz + fz) * CELL;
            float y = 6f + 5f * (float) Math.sin(x * 0.021) * (float) Math.cos(z * 0.017) + 3f * (float) Math.sin((x + z) * 0.06);
            h = h * 1664525 + 1013904223;
            int shade = 90 + ((h >>> 24) % 120);
            int color = (shade * 3 / 4) | (shade << 8) | ((60 + ((cx + cz) & 7) * 18) << 16) | 0xFF000000;
            long o = offset + (long) i * POINT_BYTES;
            dst.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o, x);
            dst.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o + 4, y);
            dst.set(ValueLayout.JAVA_FLOAT_UNALIGNED, o + 8, z);
            dst.set(ValueLayout.JAVA_INT_UNALIGNED, o + 12, color);
        }
    }

    /**
     * Gives the position of the camera at a time, in cells.
     *
     * <p>The camera follows a rounded loop around a centre that is different after every teleport,
     * at {@code speed} cells per second, and keeps inside the world with a margin of the radius.
     *
     * @param time the time in seconds
     * @param speed the speed in cells per second
     * @param teleport the seconds between jumps to a new centre, or 0 for none
     * @param out receives the column and the row in cells; must not be {@code null}
     */
    void camera(double time, double speed, double teleport, double[] out) {
        int leg = teleport > 0 ? (int) Math.floor(time / teleport) : 0;
        double local = teleport > 0 ? time - leg * teleport : time;
        double span = world - 2.0 * (radius + 1);
        double cx = radius + 1 + span * (0.15 + 0.7 * ((hash(leg, 7, 3) >>> 8) / (double) (1 << 24)));
        double cz = radius + 1 + span * (0.15 + 0.7 * ((hash(leg, 9, 4) >>> 8) / (double) (1 << 24)));
        double loop = Math.min(span * 0.12, 18.0);
        double angle = local * speed / Math.max(loop, 1.0);
        out[0] = Math.max(radius + 1, Math.min(world - radius - 2, cx + loop * Math.cos(angle)));
        out[1] = Math.max(radius + 1, Math.min(world - radius - 2, cz + loop * Math.sin(angle)));
    }
}
