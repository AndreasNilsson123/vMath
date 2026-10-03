package vmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.VertexFormat;
import vmath.pack.PackedFormat;
import vmath.tex.TextureFormat;

/**
 * Three enums carry Vulkan {@code VkFormat} numbers that were entered by hand: {@link PackedFormat}, {@link VertexFormat} and {@link TextureFormat}. Where two of
 * them name the same number they must describe the same size, which catches a transposed or mistyped number whenever it lands on another known format. It is a
 * consistency check among our own tables, not a comparison with the Khronos headers (docs/technical-debt.md, TD-01).
 */
class FormatNumbersTest {

    @Test
    void packedFormatsAgreeWithTextureFormatsOnSize() {
        List<String> shared = new ArrayList<>();
        for (PackedFormat f : PackedFormat.values()) {
            TextureFormat t = TextureFormat.fromVkFormat(f.vkFormat());
            if (t != null) {
                shared.add(f.name());
                assertEquals(1, t.blockWidth(), f + " and " + t + " share a number but " + t + " is block compressed");
                assertEquals(f.bytesPerTexel(), t.bytesPerBlock(), f + " and " + t + " share Vulkan format " + f.vkFormat() + " but not the size");
            }
        }
        assertTrue(shared.size() >= 8, "the tables should overlap in more than " + shared.size() + " formats: " + shared);
    }

    @Test
    void vertexFormatsAgreeWithTextureFormatsOnSize() {
        int shared = 0;
        for (VertexFormat f : VertexFormat.values()) {
            TextureFormat t = TextureFormat.fromVkFormat(f.vkFormat());
            if (t != null) {
                shared++;
                assertEquals(f.components() * f.componentBytes(), t.bytesPerBlock(), f + " and " + t + " share Vulkan format " + f.vkFormat() + " but not the size");
            }
        }
        assertTrue(shared >= 5, "only " + shared + " vertex formats overlap with the texture formats");
    }

    @Test
    void numbersAreUniqueWithinEachTable() {
        List<Integer> seen = new ArrayList<>();
        for (PackedFormat f : PackedFormat.values()) {
            assertTrue(!seen.contains(f.vkFormat()), "PackedFormat repeats Vulkan format " + f.vkFormat());
            seen.add(f.vkFormat());
        }
        seen.clear();
        for (TextureFormat f : TextureFormat.values()) {
            assertTrue(!seen.contains(f.vkFormat()), "TextureFormat repeats Vulkan format " + f.vkFormat());
            seen.add(f.vkFormat());
        }
    }
}
