package vmath.mesh;

import java.util.ArrayList;
import java.util.List;
import vmath.gl.VertexBufferLayout;
import vmath.gl.VertexFormat;

/**
 * How the vertices of a {@link Mesh} are laid out in one interleaved buffer: which attributes, in which order, in which format.
 * Build one with the fluent methods, then pass it to {@link MeshExport#writeVertices}. The offsets and the stride are computed as attributes are
 * added, so they can be handed straight to a vertex-array or pipeline description.
 *
 * <pre>{@code
 * VertexLayout layout = VertexLayout.builder().position().normalOct16().tangent().uvHalf(0).build();
 * }</pre>
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads freely. The arrays it hands out are its own storage: do
 * not modify them.
 */
public final class VertexLayout {

    /** The format of one attribute. */
    public enum Format {
        /** Three 32-bit floats. */
        POSITION_F32X3(12),
        /**
         * Three unorm16 coordinates relative to the mesh's bounding box and a fourth one equal to 1 (so a {@code vec4} read works with one matrix): 8 bytes. The
         * dequantization matrix is {@link MeshExport#positionQuantizer(Mesh)}'s {@code dequantizationMatrix()}; the error is half a step, {@code size / 131070} per axis.
         */
        POSITION_UNORM16X4(8),
        /** Three 32-bit floats. */
        NORMAL_F32X3(12),
        /** Two 16-bit octahedral coordinates in one 32-bit word (see {@code vmath.pack.Octahedral}); about 4e-5 radians of error. */
        NORMAL_OCT16(4),
        /** Four 32-bit floats: the tangent and the handedness sign. */
        TANGENT_F32X4(16),
        /** Two 32-bit floats. */
        UV_F32X2(8),
        /** Two half floats in one 32-bit word. */
        UV_HALF2(4),
        /** Two unorm16 coordinates relative to the rectangle around the set's texture coordinates ({@link MeshExport#uvQuantizer(Mesh, int)}): 4 bytes. */
        UV_UNORM16X2(4);

        private final int bytes;

        Format(int bytes) {
            this.bytes = bytes;
        }

        /** The size of one value of the format in bytes. */
        public int bytes() {
            return bytes;
        }
    }

    /** One attribute of the layout. {@code uvSet} is meaningful for the UV formats only. */
    public record Attribute(Format format, int uvSet, int offset) {
    }

    private final List<Attribute> attributes;
    private final int stride;

    private VertexLayout(List<Attribute> attributes, int stride) {
        this.attributes = List.copyOf(attributes);
        this.stride = stride;
    }

    /** A builder for a layout; attributes are laid out in the order they are added. */
    public static Builder builder() {
        return new Builder();
    }

    /** The attributes in memory order. */
    public List<Attribute> attributes() {
        return attributes;
    }

    /** Bytes from the start of one vertex to the start of the next (padded to a multiple of 4). */
    public int stride() {
        return stride;
    }

    /**
     * The same layout as a {@link VertexBufferLayout}, ready to turn into OpenGL, Vulkan and GLSL descriptions: shader locations 0, 1, 2, ... in attribute order and
     * the names {@code position}, {@code normal}, {@code tangent} and {@code uv<set>} (the second uv set is {@code uv1}).
     */
    public VertexBufferLayout toBufferLayout() {
        VertexBufferLayout.Builder b = VertexBufferLayout.builder();
        int location = 0;
        for (Attribute a : attributes) {
            String name = switch (a.format()) {
                case POSITION_F32X3, POSITION_UNORM16X4 -> "position";
                case NORMAL_F32X3, NORMAL_OCT16 -> "normal";
                case TANGENT_F32X4 -> "tangent";
                case UV_F32X2, UV_HALF2, UV_UNORM16X2 -> "uv" + a.uvSet();
            };
            VertexFormat f = switch (a.format()) {
                case POSITION_F32X3, NORMAL_F32X3 -> VertexFormat.FLOAT32X3;
                case NORMAL_OCT16 -> VertexFormat.SNORM16X2;
                case POSITION_UNORM16X4 -> VertexFormat.UNORM16X4;
                case UV_UNORM16X2 -> VertexFormat.UNORM16X2;
                case TANGENT_F32X4 -> VertexFormat.FLOAT32X4;
                case UV_F32X2 -> VertexFormat.FLOAT32X2;
                case UV_HALF2 -> VertexFormat.FLOAT16X2;
            };
            b.attributeAt(name, location++, f, a.offset());
        }
        return b.build(stride);
    }

    /** Fluent builder for {@link VertexLayout}. */
    public static final class Builder {
        private final List<Attribute> list = new ArrayList<>();
        private int offset;

        private Builder() {
        }

        private Builder add(Format f, int uvSet) {
            list.add(new Attribute(f, uvSet, offset));
            offset += f.bytes();
            return this;
        }

        /** Adds a position of three floats (12 bytes). */
        public Builder position() {
            return add(Format.POSITION_F32X3, 0);
        }

        /** A position quantized to unorm16 inside the mesh's bounding box, padded to four components (8 bytes). */
        public Builder positionUnorm16() {
            return add(Format.POSITION_UNORM16X4, 0);
        }

        /** Adds a normal of three floats (12 bytes). */
        public Builder normal() {
            return add(Format.NORMAL_F32X3, 0);
        }

        /** Adds a normal in octahedral form, two snorm16 (4 bytes). */
        public Builder normalOct16() {
            return add(Format.NORMAL_OCT16, 0);
        }

        /** Adds a tangent of four floats, the fourth the handedness (16 bytes). */
        public Builder tangent() {
            return add(Format.TANGENT_F32X4, 0);
        }

        /** Adds texture coordinate set {@code set} as two floats (8 bytes). */
        public Builder uv(int set) {
            return add(Format.UV_F32X2, set);
        }

        /** Adds texture coordinate set {@code set} as two half floats (4 bytes). */
        public Builder uvHalf(int set) {
            return add(Format.UV_HALF2, set);
        }

        /** A texture coordinate quantized to unorm16 inside the rectangle that covers the set (4 bytes). */
        public Builder uvUnorm16(int set) {
            return add(Format.UV_UNORM16X2, set);
        }

        /** Builds the layout; {@link IllegalStateException} when no attribute was added. */
        public VertexLayout build() {
            if (list.isEmpty()) {
                throw new IllegalStateException("a vertex layout needs at least one attribute");
            }
            return new VertexLayout(list, (offset + 3) & ~3);
        }
    }
}
