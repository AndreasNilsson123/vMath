package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HandleRegistryTest {

    private static final long SEED = Long.getLong("vmath.seed", 53L);

    @Test
    void randomOperationsAgreeWithAMapAndTheDenseArraysStayInStepWithTheRegistry() {
        SplittableRandom r = new SplittableRandom(SEED);
        HandleRegistry reg = new HandleRegistry(2);
        Map<Long, Integer> model = new HashMap<>();   // alive handle -> the value stored for it
        List<Long> dead = new ArrayList<>();
        int[] component = new int[1];                  // a parallel component array indexed by dense index
        int nextValue = 1;
        for (int step = 0; step < 20_000; step++) {
            int op = r.nextInt(10);
            if (op < 5 || model.isEmpty()) {
                long h = reg.create();
                assertTrue(reg.isAlive(h));
                assertNotEquals(HandleRegistry.INVALID, h);
                assertFalse(model.containsKey(h), "a live handle is never handed out twice");
                int dense = reg.size() - 1;
                assertEquals(dense, reg.denseIndex(h));
                if (dense >= component.length) {
                    component = java.util.Arrays.copyOf(component, component.length * 2);
                }
                component[dense] = nextValue;
                model.put(h, nextValue++);
            } else if (op < 9) {
                // destroy a random alive handle and mirror the swap-remove
                Object[] alive = model.keySet().toArray();
                long h = (Long) alive[r.nextInt(alive.length)];
                int d = reg.destroy(h);
                assertTrue(d >= 0);
                int last = reg.size();                 // the old last dense index
                if (d < last) {
                    component[d] = component[last];
                }
                model.remove(h);
                dead.add(h);
                assertFalse(reg.isAlive(h));
                assertEquals(-1, reg.denseIndex(h));
                assertEquals(-1, reg.destroy(h), "destroying twice is harmless");
            } else if (r.nextInt(50) == 0) {
                dead.addAll(model.keySet());
                reg.clear();
                model.clear();
            }
            assertEquals(model.size(), reg.size());
            if (step % 50 == 0) {
                Set<Long> seen = new HashSet<>();
                for (int d = 0; d < reg.size(); d++) {
                    long h = reg.handleAt(d);
                    assertTrue(seen.add(h), "every alive entity appears once in the dense range");
                    assertEquals(model.get(h), component[d], "component of dense index " + d);
                    assertEquals(d, reg.denseIndex(h));
                }
                assertEquals(model.keySet(), seen);
                long[] copy = new long[reg.size()];
                reg.copyHandles(copy);
                for (int d = 0; d < copy.length; d++) {
                    assertEquals(reg.handleAt(d), copy[d]);
                }
                for (int k = 0; k < Math.min(dead.size(), 200); k++) {
                    long h = dead.get(r.nextInt(dead.size()));
                    // a dead handle stays dead unless the very same handle value was handed out again, which the generations prevent
                    assertFalse(reg.isAlive(h), "stale handle " + Long.toHexString(h));
                }
            }
        }
    }

    @Test
    void aReusedSlotGetsANewGeneration() {
        HandleRegistry reg = new HandleRegistry();
        long a = reg.create();
        assertEquals(0, HandleRegistry.slot(a));
        assertEquals(1, HandleRegistry.generation(a));
        assertEquals(0, reg.destroy(a));
        long b = reg.create();
        assertEquals(0, HandleRegistry.slot(b), "the freed slot is reused");
        assertEquals(2, HandleRegistry.generation(b));
        assertNotEquals(a, b);
        assertFalse(reg.isAlive(a));
        assertTrue(reg.isAlive(b));
        assertEquals(b, HandleRegistry.pack(HandleRegistry.slot(b), HandleRegistry.generation(b)));
    }

    @Test
    void invalidAndForgedHandlesAreNeverAlive() {
        HandleRegistry reg = new HandleRegistry();
        long h = reg.create();
        assertFalse(reg.isAlive(HandleRegistry.INVALID));
        assertFalse(reg.isAlive(HandleRegistry.pack(5, 1)), "slot never created");
        assertFalse(reg.isAlive(HandleRegistry.pack(-1, 1)), "negative slot");
        assertFalse(reg.isAlive(HandleRegistry.pack(0, 0)));
        assertFalse(reg.isAlive(HandleRegistry.pack(0, 7)));
        assertFalse(reg.isAlive(-1L));
        assertEquals(-1, reg.destroy(-1L));
        assertEquals(-1, reg.denseIndex(HandleRegistry.pack(0, 2)));
        assertTrue(reg.isAlive(h));
        assertThrows(IndexOutOfBoundsException.class, () -> reg.handleAt(1));
        assertThrows(IllegalArgumentException.class, () -> reg.copyHandles(new long[0]));
    }

    @Test
    void destroyingTheLastEntityMovesNothingAndTheOthersKeepTheirPlace() {
        HandleRegistry reg = new HandleRegistry();
        long[] h = new long[5];
        for (int i = 0; i < 5; i++) {
            h[i] = reg.create();
        }
        assertEquals(4, reg.destroy(h[4]));
        assertEquals(4, reg.size(), "d == size(): nothing moved");
        for (int i = 0; i < 4; i++) {
            assertEquals(i, reg.denseIndex(h[i]));
        }
        assertEquals(1, reg.destroy(h[1]));
        assertEquals(3, reg.size());
        assertEquals(1, reg.denseIndex(h[3]), "the last entity moved into the gap");
        assertEquals(h[3], reg.handleAt(1));
    }

    @Test
    void clearStalesEveryHandleAndTheRegistryIsReusable() {
        HandleRegistry reg = new HandleRegistry();
        long[] old = new long[100];
        for (int i = 0; i < old.length; i++) {
            old[i] = reg.create();
        }
        reg.clear();
        assertEquals(0, reg.size());
        for (long o : old) {
            assertFalse(reg.isAlive(o));
        }
        for (int i = 0; i < old.length; i++) {
            long h = reg.create();
            assertTrue(reg.isAlive(h));
            for (long o : old) {
                assertNotEquals(o, h);
            }
        }
        assertEquals(100, reg.size());
    }
}
