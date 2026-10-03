package vmath.spatial;

import java.util.Arrays;

/**
 * A potentially visible set as a bit matrix: bit {@code (from, to)} says that sector {@code to} may
 * be seen from sector {@code from}.
 *
 * <p>It implements {@link SectorVisibility}, so it can be handed to
 * {@link PortalCuller#setVisibility}, and it has a plain binary format so that a level tool can
 * write it and the engine read it back without this library on the writing side.
 *
 * <p><b>Binary format.</b> {@link #toBytes()} writes the matrix row by row, one row per source
 * sector; a row is {@code ceil(sectors / 8)} bytes and bit {@code j} of a row is bit {@code j % 8}
 * (least significant first) of byte {@code j / 8}. The number of sectors is not stored: the reader
 * supplies it.
 *
 * <p>{@link #fromConnectivity} builds the one set this library can promise to be conservative
 * without any geometry: every sector that can be reached through portals. A real PVS (from a
 * visibility compiler) is much tighter; load it with {@link #fromBytes}.
 *
 * <p><b>Thread safety.</b> Not thread-safe for changes; any number of threads may read it while
 * nobody changes it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * PortalGraph.Builder builder = PortalGraph.builder();
 * builder.addBox(Aabbf.of(Vec3f.ZERO, new Vec3f(10f, 3f, 10f)));
 * builder.addBox(Aabbf.of(new Vec3f(10f, 0f, 0f), new Vec3f(20f, 3f, 10f)));
 * builder.autoPortals(0.01f);
 * PvsMatrix pvs = PvsMatrix.fromConnectivity(builder.build());               // conservative: everything connected is visible
 * boolean sees = pvs.isVisible(0, 1);
 * byte[] baked = pvs.toBytes();                                              // a binary format a level tool can write
 * }</pre>
 */
public final class PvsMatrix implements SectorVisibility {

    private final int sectors;
    private final int rowBytes;
    private final byte[] bits;

    /**
     * Creates an empty matrix for {@code sectors} sectors: nothing can see anything else.
     *
     * @param sectors the sectors
     * @throws IllegalArgumentException if {@code sectors} is not positive
     */
    public PvsMatrix(int sectors) {
        if (sectors < 1) {
            throw new IllegalArgumentException("the number of sectors must be positive: " + sectors);
        }
        this.sectors = sectors;
        this.rowBytes = (sectors + 7) / 8;
        this.bits = new byte[rowBytes * sectors];
    }

    /**
     * Counts the sectors of the matrix.
     *
     * @return the number of sectors
     */
    public int sectorCount() {
        return sectors;
    }

    @Override
    public boolean isVisible(int from, int to) {
        return (bits[from * rowBytes + (to >>> 3)] & (1 << (to & 7))) != 0;
    }

    /**
     * Sets or clears bit {@code (from, to)}.
     *
     * @param from the sector that sees
     * @param to the sector that is seen
     * @param visible the visibility set
     * @throws IndexOutOfBoundsException if {@code from} or {@code to} is not a sector
     */
    public void set(int from, int to, boolean visible) {
        if (from < 0 || to < 0 || from >= sectors || to >= sectors) {
            throw new IndexOutOfBoundsException(from + ", " + to);
        }
        int i = from * rowBytes + (to >>> 3);
        if (visible) {
            bits[i] |= (byte) (1 << (to & 7));
        } else {
            bits[i] &= (byte) ~(1 << (to & 7));
        }
    }

    /**
     * Sets every bit: everything can see everything, the matrix of no knowledge (a safe starting
     * point for a tool that clears what it can prove hidden).
     */
    public void setAll() {
        Arrays.fill(bits, (byte) 0xFF);
    }

    /**
     * Counts the pairs of sectors that may see each other, which shows how restrictive the
     * visibility data is.
     *
     * @return the number of set bits: the pairs of sectors that may see each other
     */
    public int count() {
        int n = 0;
        for (int from = 0; from < sectors; from++) {
            for (int to = 0; to < sectors; to++) {
                if (isVisible(from, to)) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Computes a conservative visibility matrix from the portal graph, by reachability through
     * portals regardless of whether they are open or closed, so that closing a door never
     * invalidates it.
     *
     * <p>A sector sees itself. This is conservative and cheap, and useful when the level has
     * several disconnected parts (it then removes the other parts altogether); it does not know
     * about walls or distance.
     *
     * @param graph the graph; must not be {@code null}
     * @return the matrix of reachability: {@code (from, to)} is set when {@code to} can be reached
     *     from {@code from} through portals, whether they are open or closed (so that the answer
     *     stays valid when doors are opened)
     */
    public static PvsMatrix fromConnectivity(PortalGraph graph) {
        int n = graph.sectorCount();
        PvsMatrix m = new PvsMatrix(n);
        int[] queue = new int[n];
        boolean[] seen = new boolean[n];
        for (int from = 0; from < n; from++) {
            Arrays.fill(seen, false);
            int head = 0, tail = 0;
            queue[tail++] = from;
            seen[from] = true;
            while (head < tail) {
                int s = queue[head++];
                m.set(from, s, true);
                for (int k = 0; k < graph.portalsOf(s); k++) {
                    int o = graph.otherSector(graph.portalOf(s, k), s);
                    if (!seen[o]) {
                        seen[o] = true;
                        queue[tail++] = o;
                    }
                }
            }
        }
        return m;
    }

    /**
     * Serialises the matrix into the compact bit format described for the class.
     *
     * @return the matrix in the binary format of the class comment:
     *     {@code sectors * ceil(sectors / 8)} bytes
     */
    public byte[] toBytes() {
        return bits.clone();
    }

    /**
     * Reads a matrix written by {@link #toBytes()} for {@code sectors} sectors;
     * {@link IllegalArgumentException} when the array has the wrong length.
     *
     * @param sectors the sectors
     * @param data the data
     * @return the matrix, never {@code null}
     * @throws IllegalArgumentException if {@code data} does not have the length of a matrix for
     *     {@code sectors} sectors
     */
    public static PvsMatrix fromBytes(int sectors, byte[] data) {
        PvsMatrix m = new PvsMatrix(sectors);
        if (data.length != m.bits.length) {
            throw new IllegalArgumentException("a matrix for " + sectors + " sectors takes " + m.bits.length + " bytes, got " + data.length);
        }
        System.arraycopy(data, 0, m.bits, 0, data.length);
        return m;
    }
}
