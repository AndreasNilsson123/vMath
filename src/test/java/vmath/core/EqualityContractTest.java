package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.IntToDoubleFunction;
import org.junit.jupiter.api.Test;

/**
 * The documented behaviour of {@code equals} and {@code hashCode} on the value types (docs/EQUALITY.md): they compare exactly, bit for bit except that every NaN is
 * equal to every NaN, so {@code -0.0} and {@code 0.0} are different and NaN equals itself. Checked reflectively on every record, float and double twin, leaf by leaf.
 */
class EqualityContractTest {

    private static final long SEED = Long.getLong("vmath.seed", 97L);

    private static final List<String> TYPES = List.of("vmath.core.Vec2", "vmath.core.Vec3", "vmath.core.Vec4", "vmath.core.Quat", "vmath.core.Mat3", "vmath.core.Mat4",
            "vmath.core.Mat4x3", "vmath.core.Transform", "vmath.geo.Aabb", "vmath.geo.Sphere", "vmath.geo.Plane", "vmath.geo.Ray", "vmath.geo.Triangle", "vmath.geo.Obb",
            "vmath.geo.Capsule", "vmath.geo.Segment");

    private static int leaves(Class<?> c) {
        if (c == float.class || c == double.class) {
            return 1;
        }
        int n = 0;
        for (RecordComponent rc : c.getRecordComponents()) {
            n += leaves(rc.getType());
        }
        return n;
    }

    /** Builds an instance whose k-th leaf (in declaration order) is {@code leaf.applyAsDouble(k)}. */
    private static Object build(Class<?> c, IntToDoubleFunction leaf, int[] next) throws ReflectiveOperationException {
        RecordComponent[] comps = c.getRecordComponents();
        Class<?>[] types = new Class<?>[comps.length];
        Object[] args = new Object[comps.length];
        for (int i = 0; i < comps.length; i++) {
            types[i] = comps[i].getType();
            if (types[i] == float.class) {
                args[i] = (float) leaf.applyAsDouble(next[0]++);
            } else if (types[i] == double.class) {
                args[i] = leaf.applyAsDouble(next[0]++);
            } else {
                args[i] = build(types[i], leaf, next);
            }
        }
        Constructor<?> ctor = c.getDeclaredConstructor(types);
        return ctor.newInstance(args);
    }

    private static Object instance(Class<?> c, IntToDoubleFunction leaf) throws ReflectiveOperationException {
        return build(c, leaf, new int[1]);
    }

    private static List<Class<?>> classes() throws ClassNotFoundException {
        List<Class<?>> out = new java.util.ArrayList<>();
        for (String t : TYPES) {
            out.add(Class.forName(t + "f"));
            out.add(Class.forName(t + "d"));
        }
        return out;
    }

    private static boolean isFloat(Class<?> c) {
        return c.getSimpleName().endsWith("f");
    }

    @Test
    void negativeZeroIsADifferentValueWithADifferentHash() throws Exception {
        for (Class<?> c : classes()) {
            int n = leaves(c);
            for (int k = 0; k < n; k++) {
                final int leaf = k;
                Object positive = instance(c, i -> i == leaf ? 0.0 : 1.0 + i);
                Object negative = instance(c, i -> i == leaf ? -0.0 : 1.0 + i);
                assertNotEquals(positive, negative, c.getSimpleName() + " leaf " + k + ": -0.0 is not equal to 0.0");
                assertNotEquals(positive.hashCode(), negative.hashCode(), c.getSimpleName() + " leaf " + k);
                assertEquals(positive, instance(c, i -> i == leaf ? 0.0 : 1.0 + i), "equal to an identical copy");
            }
        }
    }

    @Test
    void everyNaNEqualsEveryNaNAndHashesTheSame() throws Exception {
        for (Class<?> c : classes()) {
            int n = leaves(c);
            boolean f = isFloat(c);
            for (int k = 0; k < n; k++) {
                final int leaf = k;
                double quiet = Double.NaN;
                double other = f ? Float.intBitsToFloat(0x7FC00001) : Double.longBitsToDouble(0x7FF8000000000001L);
                double negative = f ? Float.intBitsToFloat(0xFFC00000) : Double.longBitsToDouble(0xFFF8000000000000L);
                Object a = instance(c, i -> i == leaf ? quiet : 1.0 + i);
                Object b = instance(c, i -> i == leaf ? other : 1.0 + i);
                Object d = instance(c, i -> i == leaf ? negative : 1.0 + i);
                assertEquals(a, a, c.getSimpleName() + ": a value with NaN equals itself");
                assertEquals(a, b, c.getSimpleName() + " leaf " + k + ": NaN payloads do not matter");
                assertEquals(a, d, c.getSimpleName() + " leaf " + k + ": nor does the sign of a NaN");
                assertEquals(a.hashCode(), b.hashCode());
                assertEquals(a.hashCode(), d.hashCode());
                // NaN is not equal to a number, and infinity equals itself
                assertNotEquals(a, instance(c, i -> 1.0 + i));
                Object inf1 = instance(c, i -> i == leaf ? Double.POSITIVE_INFINITY : 1.0 + i);
                Object inf2 = instance(c, i -> i == leaf ? Double.POSITIVE_INFINITY : 1.0 + i);
                assertEquals(inf1, inf2);
                assertEquals(inf1.hashCode(), inf2.hashCode());
                assertNotEquals(inf1, instance(c, i -> i == leaf ? Double.NEGATIVE_INFINITY : 1.0 + i));
            }
        }
    }

    @Test
    void valuesWorkAsKeysOfHashMapsAndSets() throws Exception {
        Class<?> c = Class.forName("vmath.core.Vec3f");
        Map<Object, String> map = new HashMap<>();
        map.put(instance(c, i -> 0.0), "plus zero");
        map.put(instance(c, i -> i == 0 ? -0.0 : 0.0), "minus zero");
        map.put(instance(c, i -> Double.NaN), "nan");
        assertEquals(3, map.size(), "0, -0 and NaN are three different keys");
        assertEquals("nan", map.get(instance(c, i -> Float.intBitsToFloat(0x7FC00123))), "any NaN finds the NaN key");
        assertEquals("plus zero", map.get(instance(c, i -> 0.0)));
        // the canonical keys of SpatialHash make zero and negative zero the same
        assertEquals(SpatialHash.floatKey(0f), SpatialHash.floatKey(-0f));
        assertEquals(SpatialHash.floatKey(Float.NaN), SpatialHash.floatKey(Float.intBitsToFloat(0xFFC00042)));
        assertEquals(SpatialHash.doubleKey(0d), SpatialHash.doubleKey(-0d));
        assertEquals(SpatialHash.doubleKey(Double.NaN), SpatialHash.doubleKey(Double.longBitsToDouble(0xFFF8000000000007L)));
        assertEquals(Float.floatToRawIntBits(1f), SpatialHash.floatKey(1f), "other numbers keep their bits");
        assertEquals(Float.floatToRawIntBits(-2.5f), SpatialHash.floatKey(-2.5f));
        assertEquals(Double.doubleToRawLongBits(1d), SpatialHash.doubleKey(1d));
        assertEquals(Double.doubleToRawLongBits(-2.5d), SpatialHash.doubleKey(-2.5d));
        assertNotEquals(0, SpatialHash.floatKey(Float.NaN));
        assertNotEquals(0L, SpatialHash.doubleKey(Double.NaN));
        assertNotEquals(SpatialHash.floatKey(1f), SpatialHash.floatKey(-1f));
        assertNotEquals(SpatialHash.floatKey(Float.POSITIVE_INFINITY), SpatialHash.floatKey(Float.NaN));
    }

    @Test
    void approximateEqualityIsADifferentThingFromEquals() {
        Vec3f a = new Vec3f(1f, 2f, 3f), b = new Vec3f(1f + 1e-7f, 2f, 3f);
        assertNotEquals(a, b);
        assertTrue(a.approxEquals(b, 1e-5f));
        assertTrue(new Vec3f(0f, 0f, 0f).approxEquals(new Vec3f(-0f, 0f, 0f), 0f), "approxEquals sees -0 and 0 as the same number");
        assertNotEquals(new Vec3f(0f, 0f, 0f), new Vec3f(-0f, 0f, 0f));
        Vec3f nan = new Vec3f(Float.NaN, 0f, 0f);
        assertEquals(nan, nan);
        assertTrue(!nan.approxEquals(nan, 1e-3f), "NaN is never approximately equal, even to itself, because its comparison is false");
    }

    // ---------------------------------------------------------------- SpatialHash

    @Test
    void cellIndexAndHashBehaveAsDocumented() {
        assertEquals(0, SpatialHash.cell(0.49f, 0.5f));
        assertEquals(1, SpatialHash.cell(0.5f, 0.5f));
        assertEquals(-1, SpatialHash.cell(-0.0001f, 0.5f), "negative values floor away from zero");
        assertEquals(-2, SpatialHash.cell(-0.5001f, 0.5f));
        assertEquals(SpatialHash.cell(0f, 1f), SpatialHash.cell(-0f, 1f));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cell(Float.NaN, 1f));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cell(Float.POSITIVE_INFINITY, 1f));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cell(1e30f, 1f), "more cells from the origin than an int holds");
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cell(1f, 0f));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cell(1f, -1f));
        assertEquals(SpatialHash.hash(3, 4, 5), SpatialHash.hash(3, 4, 5));
        assertNotEquals(SpatialHash.hash(3, 4, 5), SpatialHash.hash(5, 4, 3), "the order of the axes matters");
        assertEquals(SpatialHash.hash(new Vec3f(1.2f, 3.4f, -5.6f), 1f), SpatialHash.hash(1, 3, -6));
        assertEquals(SpatialHash.hash(2, 3), SpatialHash.hash(2, 3));
    }

    @Test
    void hashSpreadsNeighbouringCellsEvenly() {
        // 64 x 64 x 64 cells into 1024 buckets: the mean is 256 per bucket, and a good hash keeps the fullest bucket close to it
        int[] buckets = new int[1024];
        Set<Integer> distinct = new HashSet<>();
        int n = 64;
        for (int x = -n / 2; x < n / 2; x++) {
            for (int y = -n / 2; y < n / 2; y++) {
                for (int z = -n / 2; z < n / 2; z++) {
                    int h = SpatialHash.hash(x, y, z);
                    buckets[h & 1023]++;
                    distinct.add(h);
                }
            }
        }
        int max = 0, min = Integer.MAX_VALUE;
        for (int b : buckets) {
            max = Math.max(max, b);
            min = Math.min(min, b);
        }
        int cells = n * n * n;
        System.out.printf("SpatialHash: %d cells in 1024 buckets, mean %d, fullest %d, emptiest %d, %d distinct hashes%n", cells, cells / 1024, max, min, distinct.size());
        assertTrue(max < 1.35 * cells / 1024 && min > 0.65 * cells / 1024, "buckets from " + min + " to " + max);
        assertTrue(cells - distinct.size() < 40, "hash collisions among " + cells + " cells: " + (cells - distinct.size()));
    }

    @Test
    void twoDimensionalHashSpreadsToo() {
        int[] buckets = new int[256];
        Set<Integer> distinct = new HashSet<>();
        for (int x = -128; x < 128; x++) {
            for (int y = -128; y < 128; y++) {
                int h = SpatialHash.hash(x, y);
                buckets[h & 255]++;
                distinct.add(h);
            }
        }
        int max = 0, min = Integer.MAX_VALUE;
        for (int b : buckets) {
            max = Math.max(max, b);
            min = Math.min(min, b);
        }
        // 65 536 cells, mean 256 per bucket
        assertTrue(max < 1.35 * 256 && min > 0.65 * 256, "buckets from " + min + " to " + max);
        assertTrue(65_536 - distinct.size() < 5, "collisions: " + (65_536 - distinct.size()));
        assertNotEquals(SpatialHash.hash(1, 2), SpatialHash.hash(2, 1));
    }

    @Test
    void packedKeysAreExactAndRoundTrip() {
        SplittableRandom r = new SplittableRandom(SEED);
        Set<Long> keys = new HashSet<>();
        for (int k = 0; k < 20_000; k++) {
            int x = r.nextInt(2 * SpatialHash.MAX_PACKED + 1) - SpatialHash.MAX_PACKED, y = r.nextInt(2 * SpatialHash.MAX_PACKED + 1) - SpatialHash.MAX_PACKED,
                    z = r.nextInt(2 * SpatialHash.MAX_PACKED + 1) - SpatialHash.MAX_PACKED;
            long key = SpatialHash.pack3(x, y, z);
            assertTrue(key >= 0);
            assertEquals(x, SpatialHash.unpackX(key));
            assertEquals(y, SpatialHash.unpackY(key));
            assertEquals(z, SpatialHash.unpackZ(key));
            keys.add(key);
        }
        assertEquals(20_000, keys.size(), "random cells were distinct, so were their keys (collisions would shrink the set)");
        long corner = SpatialHash.pack3(-SpatialHash.MAX_PACKED, SpatialHash.MAX_PACKED, 0);
        assertEquals(-SpatialHash.MAX_PACKED, SpatialHash.unpackX(corner));
        assertEquals(SpatialHash.MAX_PACKED, SpatialHash.unpackY(corner));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.pack3(SpatialHash.MAX_PACKED + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.pack3(0, Integer.MIN_VALUE, 0));
        // every component accepts exactly +-MAX_PACKED
        for (int sign : new int[] {-1, 1}) {
            long k = SpatialHash.pack3(sign * SpatialHash.MAX_PACKED, -sign * SpatialHash.MAX_PACKED, sign * SpatialHash.MAX_PACKED);
            assertEquals(sign * SpatialHash.MAX_PACKED, SpatialHash.unpackZ(k));
            assertThrows(IllegalArgumentException.class, () -> SpatialHash.pack3(0, 0, sign * (SpatialHash.MAX_PACKED + 1)));
            assertThrows(IllegalArgumentException.class, () -> SpatialHash.pack3(0, sign * (SpatialHash.MAX_PACKED + 1), 0));
        }
    }

    @Test
    void anEpsilonQueryAlwaysFindsAPointWithinEpsilon() {
        SplittableRandom r = new SplittableRandom(SEED + 1);
        int[] out = new int[8];
        int[] counts = new int[9];
        for (int k = 0; k < 200_000; k++) {
            float cellSize = (float) (0.05 + r.nextDouble() * 4);
            float eps = (float) (r.nextDouble() * 0.5 * cellSize);
            float scale = (float) Math.pow(10, r.nextInt(5));
            float x = (float) ((r.nextDouble() - 0.5) * scale), y = (float) ((r.nextDouble() - 0.5) * scale), z = (float) ((r.nextDouble() - 0.5) * scale);
            // a stored point within epsilon on every axis, sometimes exactly epsilon away
            float dx = (float) ((r.nextDouble() * 2 - 1) * eps), dy = (float) ((r.nextDouble() * 2 - 1) * eps), dz = (float) ((r.nextDouble() * 2 - 1) * eps);
            if (r.nextInt(8) == 0) {
                dx = r.nextBoolean() ? eps : -eps;
            }
            float qx = x + dx, qy = y + dy, qz = z + dz;
            if (Math.abs((double) qx - x) > eps || Math.abs((double) qy - y) > eps || Math.abs((double) qz - z) > eps) {
                continue; // the float addition rounded it outside the epsilon box: not a valid case
            }
            int n = SpatialHash.cellsOverlapping(x, y, z, eps, cellSize, out);
            counts[n]++;
            assertTrue(n >= 1 && n <= 8);
            int stored = SpatialHash.hash(qx, qy, qz, cellSize);
            boolean found = false;
            for (int i = 0; i < n; i++) {
                found |= out[i] == stored;
            }
            assertTrue(found, "point (" + qx + ", " + qy + ", " + qz + ") within " + eps + " of (" + x + ", " + y + ", " + z + ") for cells of " + cellSize + " was not found");
        }
        System.out.println("cells visited per query (1 to 8): " + java.util.Arrays.toString(counts));
        assertTrue(counts[1] > 0 && counts[8] > 0, "both the single-cell and the corner cases occurred");
        // epsilon zero visits exactly one cell, and the argument checks
        assertEquals(1, SpatialHash.cellsOverlapping(0.3f, 0.3f, 0.3f, 0f, 1f, out));
        assertEquals(SpatialHash.hash(0, 0, 0), out[0]);
        assertEquals(8, SpatialHash.cellsOverlapping(1f, 1f, 1f, 0.25f, 1f, out), "a point at a grid corner overlaps 8 cells");
        assertEquals(8, SpatialHash.cellsOverlapping(1f, 1f, 1f, 0.5f, 1f, out), "epsilon equal to half a cell is allowed");
        assertEquals(1, SpatialHash.cellsOverlapping(0.5f, 0.5f, 0.5f, 0f, 1f, out));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cellsOverlapping(0f, 0f, 0f, 0.1f, 0f, out));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cellsOverlapping(0f, 0f, 0f, 0.1f, Float.POSITIVE_INFINITY, out));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cellsOverlapping(0f, 0f, 0f, 0.6f, 1f, out));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cellsOverlapping(0f, 0f, 0f, -0.1f, 1f, out));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cellsOverlapping(0f, 0f, 0f, 0.1f, 1f, new int[7]));
        assertThrows(IllegalArgumentException.class, () -> SpatialHash.cellsOverlapping(Float.NaN, 0f, 0f, 0.1f, 1f, out));
        assertEquals(1, SpatialHash.cellsOverlapping(new Vec3f(0.5f, 0.5f, 0.5f), 0.1f, 1f, out));
    }
}
