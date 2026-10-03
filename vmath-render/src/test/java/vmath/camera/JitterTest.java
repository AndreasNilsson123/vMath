package vmath.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vmath.core.Vec2f;

class JitterTest {

    @Test
    void haltonBase2And3MatchTheDefinition() {
        float[] base2 = {0.5f, 0.25f, 0.75f, 0.125f, 0.625f, 0.375f, 0.875f, 0.0625f};
        for (int i = 0; i < base2.length; i++) {
            assertEquals(base2[i], Jitter.halton(i + 1, 2), 1e-7f, "base 2, index " + (i + 1));
        }
        float[] base3 = {1f / 3, 2f / 3, 1f / 9, 4f / 9, 7f / 9, 2f / 9, 5f / 9, 8f / 9};
        for (int i = 0; i < base3.length; i++) {
            assertEquals(base3[i], Jitter.halton(i + 1, 3), 1e-6f, "base 3, index " + (i + 1));
        }
        assertEquals(0f, Jitter.halton(0, 2));
    }

    @Test
    void haltonValuesStayInTheUnitIntervalAndDoNotRepeat() {
        for (int base : new int[] {2, 3, 5}) {
            Set<Float> seen = new HashSet<>();
            for (int i = 1; i <= 1000; i++) {
                float h = Jitter.halton(i, base);
                assertTrue(h >= 0f && h < 1f, "halton(" + i + ", " + base + ") = " + h);
                assertTrue(seen.add(h), "repeated value at index " + i + " base " + base);
            }
        }
    }

    @Test
    void offsetsAreSubPixelCenteredAndCycle() {
        for (int length : new int[] {1, 4, 8, 16}) {
            double sx = 0, sy = 0;
            for (int f = 0; f < length; f++) {
                Vec2f o = Jitter.offset(f, length);
                assertTrue(o.x() >= -0.5f && o.x() < 0.5f && o.y() >= -0.5f && o.y() < 0.5f, "offset " + o);
                assertEquals(o, Jitter.offset(f + length, length), "the pattern repeats every " + length + " frames");
                assertEquals(o, Jitter.offset(f - length, length), "negative frames wrap too");
                sx += o.x();
                sy += o.y();
            }
            if (length >= 8) {
                assertTrue(Math.abs(sx / length) < 0.1 && Math.abs(sy / length) < 0.1,
                        "a full cycle is roughly centred: " + sx / length + ", " + sy / length);
            }
        }
        // a cycle covers each quadrant of the pixel
        boolean[] quadrant = new boolean[4];
        for (int f = 0; f < 8; f++) {
            Vec2f o = Jitter.offset(f, 8);
            quadrant[(o.x() < 0 ? 0 : 1) + (o.y() < 0 ? 0 : 2)] = true;
        }
        for (boolean q : quadrant) {
            assertTrue(q, "all four pixel quadrants are sampled within 8 frames");
        }
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> Jitter.halton(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> Jitter.halton(1, 1));
        assertThrows(IllegalArgumentException.class, () -> Jitter.offset(0, 0));
    }
}
