package vmath.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * How the vertices of a {@link Mesh} are laid out in one interleaved buffer: which attributes, in which order, in which format.
 * Build one with the fluent methods, then pass it to {@link MeshExport#write}. The offsets and the stride are computed as attributes are
 * added, so they can be handed straight to a vertex-array or pipeline description.
 *
 * <pre>{@code
 * VertexLayout layout = VertexLayout.builder().position().normalOct16().tangent().uvHalf(0).build();
 * }</pre>
 */
public final class VertexLayout {

    /** The format of one attribute. */
    public enum Format {
        /** Three 32-bit floats. */
        POSITION_F32X3(12),
        /** Three 32-bit floats. */
        NORMAL_F32X3(12),
        /** Two 16-bit octahedral coordinates in one 32-bit word (see {@code vmath.pack.Octahedral}); about 4e-5 radians of error. */
        NORMAL_OCT16(4),
        /** Four 32-bit floats: the tangent and the handedness sign. */
        TANGENT_F32X4(16),
        /** Two 32-bit floats. */
        UV_F32X2(8),
        /** Two half floats in one 32-bit word. */
        UV_HALF2(4);

        private final int bytes;

        Format(int bytes) {
            this.bytes = bytes;
        }

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

    public static Builder builder() {
        return new Builder();
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    /** Bytes from the start of one vertex to the start of the next (padded to a multiple of 4). */
    public int stride() {
        return stride;
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

        public Builder position() {
            return add(Format.POSITION_F32X3, 0);
        }

        public Builder normal() {
            return add(Format.NORMAL_F32X3, 0);
        }

        public Builder normalOct16() {
            return add(Format.NORMAL_OCT16, 0);
        }

        public Builder tangent() {
            return add(Format.TANGENT_F32X4, 0);
        }

        public Builder uv(int set) {
            return add(Format.UV_F32X2, set);
        }

        public Builder uvHalf(int set) {
            return add(Format.UV_HALF2, set);
        }

        public VertexLayout build() {
            if (list.isEmpty()) {
                throw new IllegalStateException("a vertex layout needs at least one attribute");
            }
            return new VertexLayout(list, (offset + 3) & ~3);
        }
    }
}
