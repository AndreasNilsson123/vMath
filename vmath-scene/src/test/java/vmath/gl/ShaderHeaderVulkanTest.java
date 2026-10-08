package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.StructArrayAccess.Mode;

/** {@link ShaderHeader.Target#VULKAN}: set and binding on every block, push constants, and what does not exist in Vulkan GLSL is refused. */
class ShaderHeaderVulkanTest {

    private static final Struct STYLE = new Struct("Style", List.of(new Member("color", GlslType.VEC4), new Member("width", GlslType.FLOAT)));
    private static final GraphicsCapabilities VK = GraphicsCapabilities.vulkan(true, true, true);
    private static final GraphicsCapabilities GL46 = GraphicsCapabilities.openGl(4, 6, List.of());

    @Test
    void theAccessModesCarrySetAndBinding() {
        assertEquals("layout(std430, set = 1, binding = 3) readonly buffer s_Block {\n    Style s[];\n};\n\nStyle fetch_s(int i) {\n    return s[i];\n}\n",
                StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 1, 3, VK).glsl());
        assertTrue(StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "u", 8, 0, 2, VK).glsl().startsWith("layout(std140, set = 0, binding = 2) uniform u_Block {"));
        String texture = StructArrayAccess.of(STYLE, Mode.TEXTURE_BUFFER, "t", 0, 2, 5, VK).glsl();
        assertTrue(texture.startsWith("layout(set = 2, binding = 5) uniform utextureBuffer t_texels;"), texture);
        assertTrue(texture.contains("texelFetch(t_texels, i * 2)"));
        assertTrue(StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "a", 0, 0, 4, VK).glsl().startsWith("layout(location = 4) in "));
        assertEquals(GraphicsCapabilities.Api.VULKAN, StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, 0, VK).api());
        assertTrue(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, 0, VK).hasExplicitBinding());
        assertEquals(GraphicsCapabilities.Api.OPENGL, StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46).api());
    }

    @Test
    void vulkanNeedsABindingAndOpenGlHasNoSet() {
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, -1, VK));
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 1, 0, GL46), "OpenGL has no descriptor set");
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, -1, 0, VK));
        // the attribute needs a location, not a binding: the same rule as OpenGL
        assertThrows(IllegalArgumentException.class, () -> StructArrayAccess.of(STYLE, Mode.VERTEX_ATTRIBUTE, "a", 0, 0, -1, VK));
    }

    @Test
    void aVulkanHeaderHasBlocksInSetsAndAPushConstantBlock() {
        Struct pc = new Struct("Push", List.of(new Member("viewProjection", GlslType.MAT4), new Member("scale", GlslType.FLOAT)));
        String text = ShaderHeader.builder("H").target(ShaderHeader.Target.VULKAN).version(GlslVersion.V450).withVersionLine().block(STYLE, GpuLayout.STD140, "uniform", "style", 0, 1)
                .block(STYLE, GpuLayout.STD430, "buffer", null, 1, 0).pushConstants(pc, "pc").access(StructArrayAccess.of(STYLE, Mode.UNIFORM_BLOCK, "table", 4, 0, 3, VK)).build()
                .emit(ShaderHeader.Language.GLSL);
        assertTrue(text.contains("#version 450 core\n"), text);
        assertTrue(text.contains("layout(std140, set = 0, binding = 1) uniform Style {"), text);
        assertTrue(text.contains("layout(std430, set = 1, binding = 0) buffer Style {"), text);
        assertTrue(text.contains("layout(std430, push_constant) uniform Push {"), text);
        assertTrue(text.contains("layout(std140, set = 0, binding = 3) uniform table_Block"), text);
        assertFalse(text.contains("usamplerBuffer"));
    }

    @Test
    void whatVulkanLacksIsRefused() {
        Struct big = new Struct("Big", List.of(new Member("a", new GlslType.Array(GlslType.VEC4, 9))));            // 144 bytes
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").target(ShaderHeader.Target.VULKAN).pushConstants(big, "p"));
        Struct small = new Struct("Small", List.of(new Member("a", GlslType.VEC4)));
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").target(ShaderHeader.Target.VULKAN).pushConstants(small, "p").pushConstants(small, "q"));
        // a block without a set, and an OpenGL access, in a Vulkan header; and the Vulkan things in an OpenGL one
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").target(ShaderHeader.Target.VULKAN).block(small, GpuLayout.STD140, "uniform", "s").build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").target(ShaderHeader.Target.VULKAN).access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, GL46)).build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").access(StructArrayAccess.of(STYLE, Mode.STORAGE_BLOCK, "s", 0, 0, 0, VK)).build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").pushConstants(small, "p").build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").block(small, GpuLayout.STD140, "uniform", "s", 0, 0).build());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").block(small, GpuLayout.STD140, "uniform", "s", -1, 0));
        // Vulkan GLSL is 4.50
        ShaderHeader old = ShaderHeader.builder("H").target(ShaderHeader.Target.VULKAN).version(GlslVersion.V430).block(small, GpuLayout.STD140, "uniform", "s", 0, 0).build();
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class, () -> old.emit(ShaderHeader.Language.GLSL));
        assertTrue(e.getMessage().contains("GLSL 4.50"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("H").target(null));
    }

    @Test
    void theDefaultTargetIsOpenGlAndSlangIsNotAffected() {
        Struct small = new Struct("Small", List.of(new Member("a", GlslType.VEC4)));
        String plain = ShaderHeader.builder("H").struct(small.layout(GpuLayout.STD430)).build().emit(ShaderHeader.Language.SLANG);
        String vulkan = ShaderHeader.builder("H").struct(small.layout(GpuLayout.STD430)).target(ShaderHeader.Target.VULKAN).block(small, GpuLayout.STD140, "uniform", "s", 0, 0).build()
                .emit(ShaderHeader.Language.SLANG);
        assertEquals(plain, vulkan);
        assertEquals(ShaderHeader.MAX_PUSH_CONSTANT_BYTES, 128);
    }
}
