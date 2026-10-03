package vmath.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Rnd;

/** The primitive hash maps against {@link HashMap}, through growth, overwrites, absent keys and keys that crowd into few buckets. */
class FastMapsTest {

    private static long key(SplittableRandom r, int style) {
        return switch (style) {
            case 0 -> r.nextLong();
            case 1 -> r.nextInt(300) - 150; // few distinct keys, many repeats, negative ones too
            case 2 -> (long) r.nextInt(64) << 32; // keys that differ only in the high half
            default -> r.nextInt(5000) * 1024L; // multiples of a power of two
        };
    }

    @Test
    void longIntMapBehavesLikeAHashMap() {
        for (int style = 0; style < 4; style++) {
            SplittableRandom r = new SplittableRandom(Rnd.SEED + style);
            FastMaps.LongIntMap map = new FastMaps.LongIntMap(style == 1 ? 1 : 100);
            Map<Long, Integer> ref = new HashMap<>();
            for (int i = 0; i < 20_000; i++) {
                long k = key(r, style);
                switch (r.nextInt(3)) {
                    case 0 -> {
                        int v = r.nextInt(1000);
                        Integer old = ref.put(k, v);
                        assertEquals(old == null ? -1 : old, map.put(k, v), "put returns the previous value");
                    }
                    case 1 -> {
                        int d = r.nextInt(5);
                        int expected = ref.merge(k, d, Integer::sum);
                        assertEquals(expected, map.add(k, d), "add returns the new value");
                    }
                    default -> {
                        Integer expected = ref.get(k);
                        assertEquals(expected == null ? -1 : expected, map.get(k));
                        assertEquals(expected != null, map.containsKey(k));
                    }
                }
                assertEquals(ref.size(), map.size());
            }
            Map<Long, Integer> seen = new HashMap<>();
            map.forEach((k, v) -> assertEquals(null, seen.put(k, v), "forEach visits each key once"));
            assertEquals(ref, seen);
        }
    }

    @Test
    void zeroIsAValueAndAbsentIsMinusOne() {
        FastMaps.LongIntMap map = new FastMaps.LongIntMap(0);
        assertEquals(-1, map.get(7));
        assertEquals(-1, map.put(7, 0));
        assertEquals(0, map.get(7));
        assertTrue(map.containsKey(7));
        assertEquals(0, map.put(7, 3));
        assertEquals(3, map.add(7, 0));
        assertEquals(1, map.size());
        assertEquals(5, map.add(8, 5), "add starts from 0");
        assertThrows(IllegalArgumentException.class, () -> map.put(9, -1));
    }

    @Test
    void tripleIntMapBehavesLikeAHashMap() {
        for (int style = 0; style < 3; style++) {
            SplittableRandom r = new SplittableRandom(Rnd.SEED + 100 + style);
            FastMaps.TripleIntMap map = new FastMaps.TripleIntMap(style == 0 ? 1 : 500);
            Map<String, Integer> ref = new HashMap<>();
            for (int i = 0; i < 20_000; i++) {
                int x, y, z;
                switch (style) {
                    case 0 -> { x = r.nextInt(); y = r.nextInt(); z = r.nextInt(); }
                    case 1 -> { x = r.nextInt(20) - 10; y = r.nextInt(20) - 10; z = r.nextInt(20) - 10; } // a small lattice: many collisions of one coordinate
                    default -> { x = r.nextInt(2000); y = x; z = 0; } // a diagonal
                }
                String k = x + "," + y + "," + z;
                if (r.nextBoolean()) {
                    int v = r.nextInt(100_000);
                    map.put(x, y, z, v);
                    ref.put(k, v);
                } else {
                    Integer expected = ref.get(k);
                    assertEquals(expected == null ? -1 : expected, map.get(x, y, z));
                }
                assertEquals(ref.size(), map.size());
            }
            for (Map.Entry<String, Integer> e : ref.entrySet()) {
                String[] p = e.getKey().split(",");
                assertEquals(e.getValue(), map.get(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
            }
        }
    }

    @Test
    void tripleIntMapRejectsNegativeValuesAndDistinguishesPermutedKeys() {
        FastMaps.TripleIntMap map = new FastMaps.TripleIntMap(4);
        assertThrows(IllegalArgumentException.class, () -> map.put(1, 2, 3, -1));
        map.put(1, 2, 3, 10);
        map.put(3, 2, 1, 20);
        map.put(2, 1, 3, 30);
        assertEquals(10, map.get(1, 2, 3));
        assertEquals(20, map.get(3, 2, 1));
        assertEquals(30, map.get(2, 1, 3));
        assertEquals(-1, map.get(1, 3, 2));
        map.put(1, 2, 3, 0);
        assertEquals(0, map.get(1, 2, 3));
        assertEquals(3, map.size());
    }
}
