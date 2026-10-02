package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** The mechanics that {@link FloatElements} shares between the five containers, checked on each of them: sizes, growth, bounds and the live array. */
class FloatElementsTest {

    private record Kind(String name, IntFunction<FloatElements> make, int stride) {
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("Vec3fArray", Vec3fArray::new, Vec3fArray.STRIDE),
            new Kind("Vec4fArray", Vec4fArray::new, Vec4fArray.STRIDE),
            new Kind("QuatArray", QuatArray::new, QuatArray.STRIDE),
            new Kind("Mat4fArray", Mat4fArray::new, Mat4fArray.STRIDE),
            new Kind("TransformArray", TransformArray::new, TransformArray.STRIDE));

    @Test
    void capacityIsInElementsAndAtLeastOne() {
        for (Kind k : KINDS) {
            assertEquals(10, k.make.apply(10).capacity(), k.name);
            assertEquals(1, k.make.apply(0).capacity(), k.name);
            assertEquals(1, k.make.apply(-5).capacity(), k.name);
            FloatElements e = k.make.apply(7);
            assertEquals(7 * k.stride, e.data().length, k.name);
            assertEquals(0, e.size(), k.name);
        }
    }

    @Test
    void setSizeAcceptsZeroToCapacityAndNothingElse() {
        for (Kind k : KINDS) {
            FloatElements e = k.make.apply(4);
            e.setSize(4);
            assertEquals(4, e.size(), k.name);
            e.setSize(0);
            assertEquals(0, e.size(), k.name);
            assertThrows(IllegalArgumentException.class, () -> e.setSize(5), k.name);
            assertThrows(IllegalArgumentException.class, () -> e.setSize(-1), k.name);
            assertEquals(0, e.size(), k.name + ": a refused size changes nothing");
            e.setSize(2);
            e.clear();
            assertEquals(0, e.size(), k.name);
            assertEquals(4, e.capacity(), k.name + ": clear keeps the capacity");
        }
    }

    @Test
    void ensureCapacityKeepsOrDoublesAndKeepsTheContents() {
        for (Kind k : KINDS) {
            FloatElements e = k.make.apply(4);
            float[] before = e.data();
            e.ensureCapacity(4);
            assertSame(before, e.data(), k.name + ": nothing to do when it already fits");
            e.ensureCapacity(0);
            assertSame(before, e.data(), k.name);
            for (int i = 0; i < before.length; i++) {
                before[i] = i + 1;
            }
            e.ensureCapacity(5);
            assertEquals(8, e.capacity(), k.name + ": at least double when it has to grow");
            assertEquals(8 * k.stride, e.data().length, k.name);
            for (int i = 0; i < before.length; i++) {
                assertEquals(i + 1, e.data()[i], k.name + ": the contents survive growth");
            }
            e.ensureCapacity(100);
            assertEquals(100, e.capacity(), k.name + ": a larger request wins over doubling");
        }
    }

    @Test
    void absurdSizesAreRefusedInsteadOfOverflowing() {
        for (Kind k : KINDS) {
            FloatElements e = k.make.apply(4);
            assertThrows(OutOfMemoryError.class, () -> e.ensureCapacity(Integer.MAX_VALUE), k.name);
            assertEquals(4, e.capacity(), k.name + ": a refused growth changes nothing");
        }
    }

    @Test
    void removeSwapAndCompactKeepTheStrideIntact() {
        for (Kind k : KINDS) {
            FloatElements e = k.make.apply(6);
            float[] d = e.data();
            for (int i = 0; i < 4 * k.stride; i++) {
                d[i] = (i / k.stride) * 100 + i % k.stride; // element i holds i * 100 + component
            }
            e.setSize(4);
            assertEquals(3, e.removeSwap(0), k.name + ": the last element moved into slot 0");
            assertEquals(3, e.size(), k.name);
            for (int c = 0; c < k.stride; c++) {
                assertEquals(300 + c, e.data()[c], k.name + ": element 0 is the old last element, whole");
            }
            assertEquals(-1, e.removeSwap(2), k.name + ": removing the last element moves nothing");
            assertThrows(IndexOutOfBoundsException.class, () -> e.removeSwap(2), k.name);
            VisibilitySet keep = new VisibilitySet(2);
            keep.set(1);
            assertEquals(1, e.compact(keep), k.name);
            for (int c = 0; c < k.stride; c++) {
                assertEquals(100 + c, e.data()[c], k.name + ": the surviving element is whole");
            }
        }
    }

    @Test
    void checkIndexUsesTheSizeNotTheCapacity() {
        for (Kind k : KINDS) {
            FloatElements e = k.make.apply(8);
            e.setSize(3);
            e.checkIndex(2);
            assertThrows(IndexOutOfBoundsException.class, () -> e.checkIndex(3), k.name);
            assertThrows(IndexOutOfBoundsException.class, () -> e.checkIndex(-1), k.name);
        }
        assertTrue(KINDS.size() == 5);
    }
}
