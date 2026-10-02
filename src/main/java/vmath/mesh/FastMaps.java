package vmath.mesh;

import java.util.Arrays;

/** Open-addressing hash maps with primitive keys and values, for the mesh tools: no boxing, no entry objects. Values must not be negative; -1 means "absent". */
final class FastMaps {

    private FastMaps() {
    }

    private static int capacityFor(int expected) {
        int c = 16;
        while (c < expected * 2) {
            c <<= 1;
        }
        return c;
    }

    private static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        return k ^ (k >>> 33);
    }

    /** long to non-negative int. */
    static final class LongIntMap {
        private long[] keys;
        private int[] values; // -1: empty slot
        private int size;

        LongIntMap(int expected) {
            int c = capacityFor(expected);
            keys = new long[c];
            values = new int[c];
            Arrays.fill(values, -1);
        }

        int size() {
            return size;
        }

        int get(long key) {
            int mask = keys.length - 1;
            for (int i = (int) mix(key) & mask; ; i = (i + 1) & mask) {
                if (values[i] < 0) {
                    return -1;
                }
                if (keys[i] == key) {
                    return values[i];
                }
            }
        }

        boolean containsKey(long key) {
            return get(key) >= 0;
        }

        /** Stores the value and returns the previous one, or -1. */
        int put(long key, int value) {
            if (value < 0) {
                throw new IllegalArgumentException("values must not be negative");
            }
            if ((size + 1) * 2 > keys.length) {
                grow();
            }
            int mask = keys.length - 1;
            for (int i = (int) mix(key) & mask; ; i = (i + 1) & mask) {
                if (values[i] < 0) {
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    return -1;
                }
                if (keys[i] == key) {
                    int old = values[i];
                    values[i] = value;
                    return old;
                }
            }
        }

        /** Adds {@code delta} to the value of the key (starting from 0) and returns the new value. */
        int add(long key, int delta) {
            int old = get(key);
            int now = (old < 0 ? 0 : old) + delta;
            put(key, now);
            return now;
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = values;
            keys = new long[ok.length * 2];
            values = new int[ok.length * 2];
            Arrays.fill(values, -1);
            size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ov[i] >= 0) {
                    put(ok[i], ov[i]);
                }
            }
        }

        /** Calls {@code visit} for every entry. */
        void forEach(EntryVisitor visit) {
            for (int i = 0; i < keys.length; i++) {
                if (values[i] >= 0) {
                    visit.entry(keys[i], values[i]);
                }
            }
        }
    }

    interface EntryVisitor {
        void entry(long key, int value);
    }

    /** Three ints (the bits of a position) to a non-negative int. */
    static final class TripleIntMap {
        private int[] kx, ky, kz;
        private int[] values;
        private int size;

        TripleIntMap(int expected) {
            int c = capacityFor(expected);
            kx = new int[c];
            ky = new int[c];
            kz = new int[c];
            values = new int[c];
            Arrays.fill(values, -1);
        }

        int size() {
            return size;
        }

        private static int hash(int x, int y, int z) {
            return (int) mix(((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y << 21) ^ ((long) z * 0xC2B2AE3D27D4EB4FL) ^ y);
        }

        int get(int x, int y, int z) {
            int mask = kx.length - 1;
            for (int i = hash(x, y, z) & mask; ; i = (i + 1) & mask) {
                if (values[i] < 0) {
                    return -1;
                }
                if (kx[i] == x && ky[i] == y && kz[i] == z) {
                    return values[i];
                }
            }
        }

        /** Stores the value for the key. */
        void put(int x, int y, int z, int value) {
            if (value < 0) {
                throw new IllegalArgumentException("values must not be negative");
            }
            if ((size + 1) * 2 > kx.length) {
                grow();
            }
            int mask = kx.length - 1;
            for (int i = hash(x, y, z) & mask; ; i = (i + 1) & mask) {
                if (values[i] < 0) {
                    kx[i] = x;
                    ky[i] = y;
                    kz[i] = z;
                    values[i] = value;
                    size++;
                    return;
                }
                if (kx[i] == x && ky[i] == y && kz[i] == z) {
                    values[i] = value;
                    return;
                }
            }
        }

        private void grow() {
            int[] ox = kx, oy = ky, oz = kz, ov = values;
            int c = ox.length * 2;
            kx = new int[c];
            ky = new int[c];
            kz = new int[c];
            values = new int[c];
            Arrays.fill(values, -1);
            size = 0;
            for (int i = 0; i < ox.length; i++) {
                if (ov[i] >= 0) {
                    put(ox[i], oy[i], oz[i], ov[i]);
                }
            }
        }
    }
}
