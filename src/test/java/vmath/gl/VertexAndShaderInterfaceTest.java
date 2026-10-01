package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;
import vmath.gl.LayoutValidator.Reflected;
import vmath.gl.VertexBufferLayout.GlFormat;
import vmath.gl.VertexBufferLayout.VkAttribute;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullViewGpu;
import vmath.mesh.VertexLayout;

class VertexAndShaderInterfaceTest {

    // ---------------------------------------------------------------- vertex formats

    @Test
    void formatNumbersAreTheRegistryValues() {
        // OpenGL: GL_BYTE, GL_UNSIGNED_BYTE, GL_SHORT, GL_UNSIGNED_SHORT, GL_INT, GL_UNSIGNED_INT, GL_FLOAT, GL_HALF_FLOAT
        assertEquals(0x1406, VertexFormat.FLOAT32X3.glType());
        assertEquals(0x140B, VertexFormat.FLOAT16X2.glType());
        assertEquals(0x1401, VertexFormat.UNORM8X4.glType());
        assertEquals(0x1400, VertexFormat.SNORM8X4.glType());
        assertEquals(0x1402, VertexFormat.SNORM16X2.glType());
        assertEquals(0x1403, VertexFormat.UNORM16X4.glType());
        assertEquals(0x1404, VertexFormat.SINT32.glType());
        assertEquals(0x1405, VertexFormat.UINT32X4.glType());
        // Vulkan: R32G32B32_SFLOAT, R32G32_SFLOAT, R16G16_SFLOAT, R8G8B8A8_UNORM/SNORM/UINT, R16G16_SNORM, R32_UINT
        assertEquals(106, VertexFormat.FLOAT32X3.vkFormat());
        assertEquals(103, VertexFormat.FLOAT32X2.vkFormat());
        assertEquals(83, VertexFormat.FLOAT16X2.vkFormat());
        assertEquals(37, VertexFormat.UNORM8X4.vkFormat());
        assertEquals(38, VertexFormat.SNORM8X4.vkFormat());
        assertEquals(41, VertexFormat.UINT8X4.vkFormat());
        assertEquals(78, VertexFormat.SNORM16X2.vkFormat());
        assertEquals(98, VertexFormat.UINT32.vkFormat());
    }

    @Test
    void formatsAreInternallyConsistent() {
        for (VertexFormat f : VertexFormat.values()) {
            assertEquals(f.components() * f.componentBytes(), f.bytes(), f.name());
            assertTrue(f.components() >= 1 && f.components() <= 4, f.name());
            assertFalse(f.normalized() && f.integer(), f.name() + ": normalized formats are read as floats");
            assertEquals(f.integer() ? f.glsl().startsWith("u") || f.glsl().startsWith("i") : f.glsl().startsWith("float") || f.glsl().startsWith("vec"), true, f.name());
            if (f.components() > 1) {
                assertTrue(f.glsl().endsWith(String.valueOf(f.components())), f.name());
            }
        }
        // the Vulkan numbers are all distinct
        assertEquals(VertexFormat.values().length, java.util.Arrays.stream(VertexFormat.values()).mapToInt(VertexFormat::vkFormat).distinct().count());
    }

    @Test
    void layoutPlacesAttributesAlignedAndPadsTheStride() {
        VertexBufferLayout layout = VertexBufferLayout.builder()
                .attribute("position", 0, VertexFormat.FLOAT32X3)
                .attribute("normal", 1, VertexFormat.SNORM16X2)
                .attribute("uv", 2, VertexFormat.FLOAT16X2)
                .attribute("tint", 3, VertexFormat.UNORM8X4)
                .build();
        assertEquals(0, layout.attribute("position").offset());
        assertEquals(12, layout.attribute("normal").offset());
        assertEquals(16, layout.attribute("uv").offset());
        assertEquals(20, layout.attribute("tint").offset());
        assertEquals(24, layout.stride());
        // a 2-byte attribute followed by a float is aligned up; the stride covers the tail and is a multiple of 4
        VertexBufferLayout odd = VertexBufferLayout.builder()
                .attribute("a", 0, VertexFormat.UNORM16X2).attribute("b", 1, VertexFormat.FLOAT32).build();
        assertEquals(4, odd.attribute("b").offset());
        assertEquals(8, odd.stride());
    }

    @Test
    void glVulkanAndGlslDescriptionsCarryTheSameNumbers() {
        VertexBufferLayout layout = VertexBufferLayout.builder()
                .attribute("position", 0, VertexFormat.FLOAT32X3)
                .attribute("joints", 4, VertexFormat.UINT8X4)
                .attribute("weights", 5, VertexFormat.UNORM8X4)
                .perInstance()
                .build();
        List<GlFormat> gl = layout.glFormats();
        assertEquals(new GlFormat(0, 3, 0x1406, false, false, 0), gl.get(0));
        assertEquals(new GlFormat(4, 4, 0x1401, false, true, 12), gl.get(1));
        assertEquals(new GlFormat(5, 4, 0x1401, true, false, 16), gl.get(2));
        List<VkAttribute> vk = layout.vkAttributes(2);
        assertEquals(new VkAttribute(0, 2, 106, 0), vk.get(0));
        assertEquals(new VkAttribute(4, 2, 41, 12), vk.get(1));
        assertEquals(new VkAttribute(5, 2, 37, 16), vk.get(2));
        assertEquals(new VertexBufferLayout.VkBinding(2, 20, true), layout.vkBinding(2));
        assertEquals("""
                layout(location = 0) in vec3 position;
                layout(location = 4) in uvec4 joints;
                layout(location = 5) in vec4 weights;
                """, layout.glslInputs());
    }

    @Test
    void layoutRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> VertexBufferLayout.builder().attribute("a", 0, VertexFormat.FLOAT32).attribute("a", 1, VertexFormat.FLOAT32));
        assertThrows(IllegalArgumentException.class, () -> VertexBufferLayout.builder().attribute("a", 0, VertexFormat.FLOAT32).attribute("b", 0, VertexFormat.FLOAT32));
        assertThrows(IllegalArgumentException.class, () -> VertexBufferLayout.builder().attributeAt("a", 0, VertexFormat.FLOAT32, 2));
        assertThrows(IllegalArgumentException.class, () -> VertexBufferLayout.builder().attribute("a", -1, VertexFormat.FLOAT32));
        assertThrows(IllegalStateException.class, () -> VertexBufferLayout.builder().build());
        assertThrows(IllegalArgumentException.class, () -> VertexBufferLayout.builder().attribute("a", 0, VertexFormat.FLOAT32X4).build(8));
        assertEquals(32, VertexBufferLayout.builder().attribute("a", 0, VertexFormat.FLOAT32X4).build(32).stride());
        assertThrows(IllegalArgumentException.class, () -> VertexBufferLayout.builder().attribute("a", 0, VertexFormat.FLOAT32).build().attribute("zzz"));
    }

    @Test
    void meshLayoutsConvertWithTheirOffsets() {
        VertexLayout mesh = VertexLayout.builder().position().normalOct16().tangent().uvHalf(0).uv(1).build();
        VertexBufferLayout b = mesh.toBufferLayout();
        assertEquals(mesh.stride(), b.stride());
        assertEquals(5, b.attributes().size());
        for (int i = 0; i < 5; i++) {
            assertEquals(mesh.attributes().get(i).offset(), b.attributes().get(i).offset());
            assertEquals(i, b.attributes().get(i).location());
            assertEquals(mesh.attributes().get(i).format().bytes(), b.attributes().get(i).format().bytes());
        }
        assertEquals("position", b.attributes().get(0).name());
        assertEquals(VertexFormat.SNORM16X2, b.attribute("normal").format());
        assertEquals(VertexFormat.FLOAT16X2, b.attribute("uv0").format());
        assertEquals(VertexFormat.FLOAT32X2, b.attribute("uv1").format());
    }

    // ---------------------------------------------------------------- shader header

    @Test
    void glslHeaderHoldsGuardStructsAndConstants() {
        String h = ShaderHeader.builder("VMATH_CULL")
                .struct(CullObjectGpu.LAYOUT)
                .constant("OBJECT_NO_OCCLUSION", 1)
                .constant("MAX_DRAWS", 4096L)
                .constant("NEAR_EPSILON", 1e-4f)
                .build(ShaderHeader.Language.GLSL);
        assertTrue(h.contains("#ifndef VMATH_CULL\n#define VMATH_CULL"), h);
        assertTrue(h.contains(CullObjectGpu.GLSL), h);
        assertTrue(h.contains("// std430, 32 bytes, alignment 16: min@0, drawIndex@12, max@16, flags@28"), h);
        assertTrue(h.contains("const int OBJECT_NO_OCCLUSION = 1;"), h);
        assertTrue(h.contains("const uint MAX_DRAWS = 4096u;"), h);
        assertTrue(h.contains("const float NEAR_EPSILON = 1.0E-4;"), h);
        assertTrue(h.trim().endsWith("#endif // VMATH_CULL"), h);
        // deterministic
        assertEquals(h, ShaderHeader.builder("VMATH_CULL").struct(CullObjectGpu.LAYOUT).constant("OBJECT_NO_OCCLUSION", 1).constant("MAX_DRAWS", 4096L)
                .constant("NEAR_EPSILON", 1e-4f).build(ShaderHeader.Language.GLSL));
    }

    @Test
    void nestedStructsComeFirstAndOnlyOnce() {
        Struct inner = new Struct("Inner", List.of(new Member("a", GlslType.VEC3), new Member("b", GlslType.FLOAT)));
        Struct outer = new Struct("Outer", List.of(new Member("x", GlslType.FLOAT), new Member("items", GlslType.array(inner, 2)), new Member("m", GlslType.MAT4)));
        ShaderHeader.Builder b = ShaderHeader.builder("NESTED").struct(outer.layout(GpuLayout.STD430)).struct(inner.layout(GpuLayout.STD430));
        String glsl = b.build(ShaderHeader.Language.GLSL);
        assertTrue(glsl.indexOf("struct Inner") < glsl.indexOf("struct Outer"), glsl);
        assertEquals(glsl.indexOf("struct Inner"), glsl.lastIndexOf("struct Inner"), "declared once:\n" + glsl);
        assertTrue(glsl.contains("Inner items[2];"), glsl);
        String slang = b.build(ShaderHeader.Language.SLANG);
        assertTrue(slang.contains("float3 a;"), slang);
        assertTrue(slang.contains("Inner items[2];"), slang);
        assertTrue(slang.contains("float4x4 m;"), slang);
        assertFalse(slang.contains("vec3"), slang);
    }

    @Test
    void headerRejectsBadNamesAndConflicts() {
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("not valid"));
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("G").constant("1x", 1));
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("G").constant("A", 1).constant("A", 2));
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("G").constant("A", -1L));
        assertThrows(IllegalArgumentException.class, () -> ShaderHeader.builder("G").constant("A", Float.NaN));
        Struct one = new Struct("S", List.of(new Member("a", GlslType.FLOAT)));
        Struct two = new Struct("S", List.of(new Member("b", GlslType.FLOAT)));
        ShaderHeader h = ShaderHeader.builder("G").struct(one.layout(GpuLayout.STD430)).struct(two.layout(GpuLayout.STD430)).build();
        assertThrows(IllegalArgumentException.class, () -> h.emit(ShaderHeader.Language.GLSL));
        // the same struct twice is fine
        String ok = ShaderHeader.builder("G").struct(one.layout(GpuLayout.STD430)).struct(one.layout(GpuLayout.STD430)).build(ShaderHeader.Language.GLSL);
        assertEquals(ok.indexOf("struct S"), ok.lastIndexOf("struct S"));
    }

    // ---------------------------------------------------------------- layout validator

    /** What a GL or SPIR-V reflection of the CullView block (instance name {@code view}) reports, worked out by hand from the std140 rules. */
    private static List<Reflected> reflectedCullView() {
        return List.of(
                new Reflected("view.planes[0]", 0, 16, 0),
                new Reflected("view.viewProjection", 96, 0, 16),
                new Reflected("view.objectCount", 160, 0, 0),
                new Reflected("view.hzbWidth", 164, 0, 0),
                new Reflected("view.hzbHeight", 168, 0, 0),
                new Reflected("view.hzbLevels", 172, 0, 0),
                new Reflected("view.nearW", 176, 0, 0),
                new Reflected("view.depthMode", 180, 0, 0),
                new Reflected("view.flags", 184, 0, 0));
    }

    @Test
    void handDerivedReflectionOfARealBlockAgrees() {
        assertEquals(List.of(), LayoutValidator.validate(CullViewGpu.LAYOUT, reflectedCullView(), "view.", true, 192));
    }

    @Test
    void expectedMembersValidateAgainstThemselvesAndNestedStructsFlatten() {
        Struct inner = new Struct("Inner", List.of(new Member("a", GlslType.VEC3), new Member("b", GlslType.FLOAT)));
        Struct outer = new Struct("Outer", List.of(new Member("x", GlslType.FLOAT), new Member("items", GlslType.array(inner, 2)), new Member("m", GlslType.MAT3)));
        StructLayout l = outer.layout(GpuLayout.STD430);
        List<Reflected> e = LayoutValidator.expected(l);
        // x@0; items start at 16 (struct alignment 16), each 16 bytes: a@+0, b@+12; mat3 after at 48 with 16-byte columns under std430
        assertEquals(List.of(
                new Reflected("x", 0, 0, 0),
                new Reflected("items[0].a", 16, 0, 0), new Reflected("items[0].b", 28, 0, 0),
                new Reflected("items[1].a", 32, 0, 0), new Reflected("items[1].b", 44, 0, 0),
                new Reflected("m", 48, 0, 16)), e);
        assertEquals(List.of(), LayoutValidator.validate(l, e, "", true, l.size()));
    }

    @Test
    void everyKindOfMismatchIsReported() {
        List<Reflected> wrong = new java.util.ArrayList<>(reflectedCullView());
        wrong.set(0, new Reflected("view.planes[0]", 0, 32, 0));                 // array stride
        wrong.set(1, new Reflected("view.viewProjection", 96, 0, 12));           // matrix stride
        wrong.set(2, new Reflected("view.objectCount", 156, 0, 0));              // offset
        wrong.remove(8);                                                          // missing flags
        wrong.add(new Reflected("view.extra", 188, 0, 0));                        // unexpected
        wrong.add(new Reflected("other.x", 0, 0, 0));                             // wrong prefix
        List<String> p = LayoutValidator.validate(CullViewGpu.LAYOUT, wrong, "view.", true, 176);
        String all = String.join("\n", p);
        assertTrue(all.contains("planes[0]: array stride 32 in the shader, 16 in Java"), all);
        assertTrue(all.contains("viewProjection: matrix stride 12 in the shader, 16 in Java"), all);
        assertTrue(all.contains("objectCount: offset 156 in the shader, 160 in Java"), all);
        assertTrue(all.contains("missing member 'flags'"), all);
        assertTrue(all.contains("unexpected member 'extra'"), all);
        assertTrue(all.contains("unexpected member 'other.x' (does not start with 'view.')"), all);
        assertTrue(all.contains("size 176 in the shader, 192 in Java"), all);
        assertEquals(7, p.size(), all);
        // unused members may be missing from reflection when requireAll is off, and a negative size skips the check
        assertEquals(List.of(), LayoutValidator.validate(CullViewGpu.LAYOUT, reflectedCullView().subList(0, 4), "view.", false, -1));
    }
}
