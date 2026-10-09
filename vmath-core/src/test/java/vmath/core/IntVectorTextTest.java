package vmath.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class IntVectorTextTest {

    @Test
    void theFormsReadBack() {
        Vec2i a = new Vec2i(-3, 4);
        Vec3i b = new Vec3i(1, -2, 3);
        Vec4i c = new Vec4i(1, 2, 3, -4);
        assertEquals(a, Vec2i.parse(a.toString()));
        assertEquals(a, Vec2i.parse(a.toCompactString()));
        assertEquals("(1, -2, 3)", b.toCompactString());
        assertEquals(b, Vec3i.parse("z=3, x=1, y=-2"));
        assertEquals(b, Vec3i.parse("[1; -2; 3]"));
        assertEquals(c, Vec4i.parse(c.toString()));
        assertEquals(c, Vec4i.parse("1 2 3 -4"));
    }

    @Test
    void badTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Vec3i.parse("1 2"));
        assertThrows(IllegalArgumentException.class, () -> Vec3i.parse("1 2 3.5"));
        assertThrows(IllegalArgumentException.class, () -> Vec3i.parse("1 2 99999999999"));
        assertThrows(IllegalArgumentException.class, () -> Vec2i.parse("Vec3i[x=1, y=2]"));
        assertThrows(NullPointerException.class, () -> Vec2i.parse(null));
    }
}
