package vmath.gl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;

/**
 * The layout rules, checked against numbers worked out by hand from the OpenGL 4.6 specification (section 7.6.2.2, "Standard
 * Uniform Block Layout") and the Vulkan scalar-layout extension, plus structural properties over random structs.
 */
class GlslLayoutTest {

    private static Struct struct(String name, Object... nameAndType) {
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < nameAndType.length; i += 2) {
            members.add(new Member((String) nameAndType[i], (GlslType) nameAndType[i + 1]));
        }
        return new Struct(name, members);
    }

    private static void assertLayout(Struct s, GpuLayout l, long size, int alignment, long... offsets) {
        StructLayout sl = s.layout(l);
        assertEquals(size, sl.size(), s.name() + " size under " + l);
        assertEquals(alignment, sl.alignment(), s.name() + " alignment under " + l);
        for (int i = 0; i < offsets.length; i++) {
            assertEquals(offsets[i], sl.fields().get(i).offset(), s.name() + "." + sl.fields().get(i).name() + " under " + l);
        }
    }

    // ------------------------------------------------------------ scalars and vectors

    @Test
    void vectorAlignmentAndSize() {
        for (GpuLayout l : new GpuLayout[] {GpuLayout.STD140, GpuLayout.STD430}) {
            assertEquals(4, GlslType.FLOAT.alignment(l));
            assertEquals(8, GlslType.VEC2.alignment(l));
            assertEquals(16, GlslType.VEC3.alignment(l));
            assertEquals(16, GlslType.VEC4.alignment(l));
        }
        // a vec3 is 12 bytes although it aligns to 16, so a float can follow it in the same 16-byte slot
        assertEquals(12, GlslType.VEC3.size(GpuLayout.STD140));
        assertEquals(4, GlslType.VEC3.alignment(GpuLayout.SCALAR));
        assertEquals(4, GlslType.VEC4.alignment(GpuLayout.SCALAR));
        assertEquals(8, GlslType.IVEC2.size(GpuLayout.STD140));
        assertEquals(12, GlslType.UVEC3.size(GpuLayout.STD430));
    }

    @Test
    void aVec3IsFollowedByAFloatInTheSameSlot() {
        Struct s = struct("S", "a", GlslType.FLOAT, "v", GlslType.VEC3, "b", GlslType.FLOAT);
        assertLayout(s, GpuLayout.STD140, 32, 16, 0, 16, 28);
        assertLayout(s, GpuLayout.STD430, 32, 16, 0, 16, 28);
        assertLayout(s, GpuLayout.SCALAR, 20, 4, 0, 4, 16);
        Struct packed = struct("P", "v", GlslType.VEC3, "f", GlslType.FLOAT);
        assertLayout(packed, GpuLayout.STD140, 16, 16, 0, 12);
        assertEquals(0, packed.layout(GpuLayout.STD140).paddingBytes());
        assertLayout(struct("Q", "f", GlslType.FLOAT, "v", GlslType.VEC3), GpuLayout.STD140, 32, 16, 0, 16);
        assertEquals(16, struct("Q", "f", GlslType.FLOAT, "v", GlslType.VEC3).layout(GpuLayout.STD140).paddingBytes());
    }

    @Test
    void twoVec2sShareASlot() {
        Struct s = struct("S", "a", GlslType.VEC2, "b", GlslType.VEC2, "c", GlslType.FLOAT);
        assertLayout(s, GpuLayout.STD140, 32, 16, 0, 8, 16);
        assertLayout(s, GpuLayout.STD430, 24, 8, 0, 8, 16); // aligns to its widest member, a vec2, not to 16
        assertLayout(s, GpuLayout.SCALAR, 20, 4, 0, 8, 16);
    }

    // ------------------------------------------------------------ matrices

    @Test
    void matrixSizesAndColumnStrides() {
        // std140: every column takes a vec4 slot
        assertEquals(32, GlslType.MAT2.size(GpuLayout.STD140));
        assertEquals(48, GlslType.MAT3.size(GpuLayout.STD140));
        assertEquals(64, GlslType.MAT4.size(GpuLayout.STD140));
        assertEquals(64, GlslType.mat(4, 3).size(GpuLayout.STD140));
        assertEquals(48, GlslType.mat(3, 4).size(GpuLayout.STD140));
        assertEquals(16, GlslType.MAT3.alignment(GpuLayout.STD140));
        // std430: a column takes the alignment of its own vector, so mat2 is tight but mat3 is still padded
        assertEquals(16, GlslType.MAT2.size(GpuLayout.STD430));
        assertEquals(8, GlslType.MAT2.alignment(GpuLayout.STD430));
        assertEquals(48, GlslType.MAT3.size(GpuLayout.STD430));
        assertEquals(32, GlslType.mat(2, 3).size(GpuLayout.STD430));
        assertEquals(24, GlslType.mat(3, 2).size(GpuLayout.STD430));
        // scalar: columns are packed with no padding at all
        assertEquals(16, GlslType.MAT2.size(GpuLayout.SCALAR));
        assertEquals(36, GlslType.MAT3.size(GpuLayout.SCALAR));
        assertEquals(48, GlslType.mat(4, 3).size(GpuLayout.SCALAR));
        assertEquals(4, GlslType.MAT3.alignment(GpuLayout.SCALAR));
        assertEquals(12, GlslType.MAT3.columnStride(GpuLayout.SCALAR));
        assertEquals(16, GlslType.MAT3.columnStride(GpuLayout.STD140));
        assertEquals(16, GlslType.MAT2.columnStride(GpuLayout.STD140));
        assertEquals(8, GlslType.MAT2.columnStride(GpuLayout.STD430));
    }

    // ------------------------------------------------------------ arrays

    @Test
    void arrayStrideDiffersBetweenStd140AndStd430() {
        GlslType.Array floats = GlslType.array(GlslType.FLOAT, 3);
        assertEquals(16, floats.stride(GpuLayout.STD140));
        assertEquals(48, floats.size(GpuLayout.STD140));
        assertEquals(4, floats.stride(GpuLayout.STD430));
        assertEquals(12, floats.size(GpuLayout.STD430));
        assertEquals(4, floats.stride(GpuLayout.SCALAR));

        GlslType.Array vec2s = GlslType.array(GlslType.VEC2, 3);
        assertEquals(16, vec2s.stride(GpuLayout.STD140));
        assertEquals(8, vec2s.stride(GpuLayout.STD430));

        GlslType.Array vec3s = GlslType.array(GlslType.VEC3, 2);
        assertEquals(16, vec3s.stride(GpuLayout.STD140));
        assertEquals(16, vec3s.stride(GpuLayout.STD430));
        assertEquals(12, vec3s.stride(GpuLayout.SCALAR));
        assertEquals(24, vec3s.size(GpuLayout.SCALAR));

        GlslType.Array mats = GlslType.array(GlslType.MAT3, 2);
        assertEquals(48, mats.stride(GpuLayout.STD140));
        assertEquals(96, mats.size(GpuLayout.STD430));
        assertEquals(36, mats.stride(GpuLayout.SCALAR));
    }

    @Test
    void arrayInAStructKeepsFollowingMembersAligned() {
        Struct s = struct("S", "w", GlslType.FLOAT, "a", GlslType.array(GlslType.FLOAT, 4), "t", GlslType.FLOAT);
        assertLayout(s, GpuLayout.STD140, 96, 16, 0, 16, 80);
        assertLayout(s, GpuLayout.STD430, 24, 4, 0, 4, 20);
        assertLayout(s, GpuLayout.SCALAR, 24, 4, 0, 4, 20);
    }

    // ------------------------------------------------------------ structs

    @Test
    void nestedStructsRoundUpTheirAlignmentInStd140Only() {
        Struct inner = struct("Inner", "a", GlslType.FLOAT, "b", GlslType.FLOAT);
        assertEquals(16, inner.alignment(GpuLayout.STD140));
        assertEquals(16, inner.size(GpuLayout.STD140));
        assertEquals(4, inner.alignment(GpuLayout.STD430));
        assertEquals(8, inner.size(GpuLayout.STD430));

        Struct outer = struct("Outer", "x", GlslType.FLOAT, "s", inner, "y", GlslType.FLOAT);
        assertLayout(outer, GpuLayout.STD140, 48, 16, 0, 16, 32);
        assertLayout(outer, GpuLayout.STD430, 16, 4, 0, 4, 12);

        Struct arr = struct("Arr", "items", GlslType.array(inner, 3));
        assertEquals(48, arr.layout(GpuLayout.STD140).size());
        assertEquals(24, arr.layout(GpuLayout.STD430).size());
        assertEquals(16, ((GlslType.Array) arr.members().get(0).type()).stride(GpuLayout.STD140));
        assertEquals(8, ((GlslType.Array) arr.members().get(0).type()).stride(GpuLayout.STD430));
    }

    @Test
    void structWithVec3MemberInsideAnotherStruct() {
        Struct s = struct("S", "a", GlslType.FLOAT, "b", GlslType.VEC3);
        assertLayout(s, GpuLayout.STD140, 32, 16, 0, 16);
        Struct t = struct("T", "x", GlslType.FLOAT, "s", s, "y", GlslType.FLOAT);
        assertLayout(t, GpuLayout.STD140, 64, 16, 0, 16, 48);
        assertLayout(t, GpuLayout.STD430, 64, 16, 0, 16, 48);
        assertLayout(t, GpuLayout.SCALAR, 24, 4, 0, 4, 20);
    }

    @Test
    void typicalBlocks() {
        Struct camera = struct("Camera", "view", GlslType.MAT4, "proj", GlslType.MAT4,
                "position", GlslType.VEC3, "time", GlslType.FLOAT);
        assertLayout(camera, GpuLayout.STD140, 144, 16, 0, 64, 128, 140);

        Struct light = struct("Light", "position", GlslType.VEC3, "radius", GlslType.FLOAT, "color", GlslType.VEC4,
                "cascades", GlslType.array(GlslType.VEC4, 4), "orientation", GlslType.VEC4);
        assertLayout(light, GpuLayout.STD140, 112, 16, 0, 12, 16, 32, 96);

        Struct particle = struct("Particle", "position", GlslType.VEC3, "life", GlslType.FLOAT,
                "velocity", GlslType.VEC3, "flags", GlslType.UINT);
        assertLayout(particle, GpuLayout.STD430, 32, 16, 0, 12, 16, 28);
        assertEquals(0, particle.layout(GpuLayout.STD430).paddingBytes());
    }

    // ------------------------------------------------------------ text output

    @Test
    void glslNamesAndDeclarations() {
        assertEquals("float", GlslType.FLOAT.glsl());
        assertEquals("ivec3", GlslType.IVEC3.glsl());
        assertEquals("uvec2", GlslType.UVEC2.glsl());
        assertEquals("vec4", GlslType.VEC4.glsl());
        assertEquals("mat3", GlslType.MAT3.glsl());
        assertEquals("mat4x3", GlslType.mat(4, 3).glsl());
        assertEquals("float[4]", GlslType.array(GlslType.FLOAT, 4).glsl());

        Struct s = struct("Light", "position", GlslType.VEC3, "cascades", GlslType.array(GlslType.VEC4, 4), "flags", GlslType.UINT);
        assertEquals("struct Light {\n    vec3 position;\n    vec4 cascades[4];\n    uint flags;\n};", s.glslDeclaration());
        assertEquals("layout(std430) buffer Light {\n    vec3 position;\n    vec4 cascades[4];\n    uint flags;\n} lights;",
                s.glslBlock(GpuLayout.STD430, "buffer", "lights"));
        assertTrue(s.glslBlock(GpuLayout.STD140, "uniform", "").endsWith("};"));
        assertEquals("std140", GpuLayout.STD140.glslName());
    }

    // ------------------------------------------------------------ validation

    @Test
    void rejectsInvalidTypes() {
        assertThrows(IllegalArgumentException.class, () -> new Struct("E", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                struct("D", "a", GlslType.FLOAT, "a", GlslType.INT));
        assertThrows(IllegalArgumentException.class, () -> GlslType.array(GlslType.array(GlslType.FLOAT, 2), 2));
        assertThrows(IllegalArgumentException.class, () -> GlslType.array(GlslType.FLOAT, 0));
        assertThrows(IllegalArgumentException.class, () -> new GlslType.Vec(GlslType.FLOAT, 5));
        assertThrows(IllegalArgumentException.class, () -> GlslType.mat(1, 4));
        assertThrows(IllegalArgumentException.class, () -> struct("S", "a", GlslType.FLOAT).layout(GpuLayout.STD140).offsetOf("b"));
    }

    // ------------------------------------------------------------ properties over random structs

    private GlslType randomType(SplittableRandom r, int depth) {
        GlslType[] simple = {GlslType.FLOAT, GlslType.INT, GlslType.UINT, GlslType.VEC2, GlslType.VEC3, GlslType.VEC4,
                GlslType.IVEC3, GlslType.MAT2, GlslType.MAT3, GlslType.MAT4, GlslType.mat(4, 3), GlslType.mat(2, 4)};
        int kind = r.nextInt(depth > 1 ? 3 : 5);
        return switch (kind) {
            case 3 -> GlslType.array(randomType(r, 2), 1 + r.nextInt(4));
            case 4 -> randomStruct(r, depth + 1);
            default -> simple[r.nextInt(simple.length)];
        };
    }

    private Struct randomStruct(SplittableRandom r, int depth) {
        List<Member> members = new ArrayList<>();
        int n = 1 + r.nextInt(6);
        for (int i = 0; i < n; i++) {
            GlslType t = randomType(r, depth);
            // arrays of arrays are not allowed
            if (t instanceof GlslType.Array a && a.element() instanceof GlslType.Array) {
                t = GlslType.FLOAT;
            }
            members.add(new Member("m" + i, t));
        }
        return new Struct("R" + r.nextInt(1000), members);
    }

    @Test
    void randomStructsSatisfyTheLayoutInvariants() {
        SplittableRandom r = new SplittableRandom(Long.getLong("vmath.seed", 0x5EEDL));
        for (int i = 0; i < 3000; i++) {
            Struct s = randomStruct(r, 0);
            for (GpuLayout l : GpuLayout.values()) {
                StructLayout sl = s.layout(l);
                long end = 0;
                for (StructLayout.Field f : sl.fields()) {
                    assertEquals(0, f.offset() % f.type().alignment(l), "offset aligned: " + s + " " + l + " " + f);
                    assertTrue(f.offset() >= end, "members must not overlap: " + f + " starts before " + end);
                    end = f.offset() + f.type().size(l);
                }
                assertEquals(0, sl.size() % sl.alignment(), "size is a multiple of the alignment");
                assertTrue(sl.size() >= end, "size covers every member");
                assertEquals(sl.size(), s.size(l), "StructLayout and GlslType agree on the size");
                assertEquals(sl.alignment(), s.alignment(l));
                assertTrue(sl.paddingBytes() >= 0, "padding is never negative: " + s + " " + l);
                assertTrue(s.size(GpuLayout.STD140) >= s.size(GpuLayout.STD430), "std140 is never smaller than std430");
                assertTrue(s.size(GpuLayout.STD430) >= s.size(GpuLayout.SCALAR), "std430 is never smaller than scalar");
                if (l == GpuLayout.STD140) {
                    assertEquals(0, sl.alignment() % 16, "std140 structs align to 16");
                }
            }
        }
    }
}
