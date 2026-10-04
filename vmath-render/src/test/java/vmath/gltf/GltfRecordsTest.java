package vmath.gltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The records of {@link Gltf} that hold arrays: they own copies, hand out copies, and are equal
 * when the contents of their arrays are.
 */
class GltfRecordsTest {

    private static Gltf.Node node(float[] translation, int[] children) {
        return new Gltf.Node("n", children, 0, -1, translation, null, null, null);
    }

    @Test
    void aNodeIsNotChangedByTheArraysItWasGivenOrHandsOut() {
        float[] t = {1f, 2f, 3f};
        int[] c = {4, 5};
        Gltf.Node n = node(t, c);
        t[0] = 99f;
        c[0] = 99;
        assertArrayEquals(new float[] {1f, 2f, 3f}, n.translation());
        assertArrayEquals(new int[] {4, 5}, n.children());
        n.translation()[1] = 99f;
        assertArrayEquals(new float[] {1f, 2f, 3f}, n.translation(), "the accessor hands out a copy");
        assertNotSame(n.translation(), n.translation());
        assertNull(n.rotation(), "an absent array stays absent");
    }

    @Test
    void equalContentsMeanEqualRecords() {
        Gltf.Node a = node(new float[] {1f, 2f, 3f}, new int[] {4});
        Gltf.Node b = node(new float[] {1f, 2f, 3f}, new int[] {4});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(false, a.equals(node(new float[] {1f, 2f, 9f}, new int[] {4})));
        Gltf.VertexSkinning s = new Gltf.VertexSkinning(new int[] {1, 2}, new float[] {0.5f, 0.5f});
        assertEquals(s, new Gltf.VertexSkinning(new int[] {1, 2}, new float[] {0.5f, 0.5f}));
    }
}
