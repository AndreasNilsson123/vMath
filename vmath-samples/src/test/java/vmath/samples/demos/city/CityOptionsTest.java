package vmath.samples.demos.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;

/**
 * Tests of the options of the city demo and of the scene it builds.
 *
 * <p><b>Thread safety.</b> Each test builds its own values; the tests may run in parallel.
 */
class CityOptionsTest {

    @Test
    void defaults() {
        CityOptions o = CityOptions.parse(List.of());
        assertEquals(1_000_000, o.instances());
        assertEquals(1, o.threads());
        assertEquals(3, o.framesInFlight());
        assertTrue(o.culling());
    }

    @Test
    void parsesAllOptions() {
        CityOptions o = CityOptions.parse(List.of("--instances", "500", "--threads", "4", "--frames-in-flight", "2", "--no-cull"));
        assertEquals(500, o.instances());
        assertEquals(4, o.threads());
        assertEquals(2, o.framesInFlight());
        assertFalse(o.culling());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> CityOptions.parse(List.of("--nope")));
        assertThrows(IllegalArgumentException.class, () -> CityOptions.parse(List.of("--instances")));
        assertThrows(IllegalArgumentException.class, () -> CityOptions.parse(List.of("--instances", "many")));
        assertThrows(IllegalArgumentException.class, () -> CityOptions.parse(List.of("--threads", "0")));
    }

    @Test
    void theCityHasTheBoxesAndOneGroundAndIsTheSameEveryTime() {
        BoundsArray a = City.build(100);
        BoundsArray b = City.build(100);
        assertEquals(101, a.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.maxY(i), b.maxY(i));
            assertTrue(a.maxY(i) >= a.minY(i));
        }
        assertEquals(0f, a.maxY(100), "the last box is the flat ground");
    }
}
