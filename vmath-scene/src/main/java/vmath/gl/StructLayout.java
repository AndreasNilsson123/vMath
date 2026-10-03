package vmath.gl;

import java.util.ArrayList;
import java.util.List;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;

/**
 * Where each member of a {@link Struct} lives in memory under one {@link GpuLayout}: byte offsets,
 * the total size and the struct's alignment.
 *
 * <p>Get one from {@link Struct#layout(GpuLayout)}.
 *
 * <p><b>Thread safety.</b> Immutable: instances can be shared between threads without
 * synchronization.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GlslType.Struct block = new GlslType.Struct("Camera", List.of(new GlslType.Member("view", GlslType.MAT4), new GlslType.Member("position", GlslType.VEC3)));
 * StructLayout layout = block.layout(GpuLayout.STD140);
 * long offset = layout.offsetOf("position");                           // 64
 * long size = layout.size();
 * }</pre>
 *
 * @param struct    the laid-out struct
 * @param layout    the rules used
 *
 * @param alignment base alignment in bytes; place an instance of this struct at a multiple of it
 * @param size      total size in bytes, already rounded up to {@code alignment}; the stride when used in an array
 * @param fields    members in declaration order with their offsets
 */
public record StructLayout(Struct struct, GpuLayout layout, int alignment, long size, List<Field> fields) {

    /**
     * A member with its position.
     *
     * @param name the name; must not be {@code null}
     * @param type the type; must not be {@code null}
     * @param offset the index of the first element to read or write
     */
    public record Field(String name, GlslType type, long offset) {

        /**
         * Exposes the size of the member under a layout, including the padding that belongs to its
         * type.
         *
         * @param layout the GPU layout; must not be {@code null}
         * @return bytes the member occupies (its type's size, including any tail padding of arrays
         *     and structs)
         */
        public long size(GpuLayout layout) {
            return type.size(layout);
        }
    }

    static StructLayout compute(Struct struct, GpuLayout layout) {
        List<Field> fields = new ArrayList<>();
        long cursor = 0;
        for (Member m : struct.members()) {
            long offset = Align.roundUp(cursor, m.type().alignment(layout));
            fields.add(new Field(m.name(), m.type(), offset));
            cursor = offset + m.type().size(layout);
        }
        int alignment = struct.alignment(layout);
        return new StructLayout(struct, layout, alignment, Align.roundUp(cursor, alignment), List.copyOf(fields));
    }

    /**
     * Looks up the byte offset of a member by name; unknown names are rejected.
     *
     * @param member the member; must not be {@code null}
     * @return offset of the named member
     */
    public long offsetOf(String member) {
        return field(member).offset();
    }

    /**
     * Looks up a member by name; unknown names are rejected.
     *
     * @param member the member; must not be {@code null}
     * @return the named member's field (its offset, size and type);
     *     {@link IllegalArgumentException} for a name the struct does not have
     * @throws IllegalArgumentException if the struct has no member of that name
     */
    public Field field(String member) {
        for (Field f : fields) {
            if (f.name().equals(member)) {
                return f;
            }
        }
        throw new IllegalArgumentException("no member '" + member + "' in " + struct.name());
    }

    /**
     * Counts the bytes of the struct that no member can use, a measure of how much the layout
     * wastes on alignment.
     *
     * <p>Useful to spot wasteful ordering.
     *
     * @return bytes of padding: everything in {@link #size} that no member's data can occupy
     */
    public long paddingBytes() {
        long used = 0;
        for (Field f : fields) {
            used += dataBytes(f.type());
        }
        return size - used;
    }

    private long dataBytes(GlslType t) {
        return switch (t) {
            case GlslType.Scalar s -> 4;
            case GlslType.Vec v -> 4L * v.length();
            case GlslType.Mat m -> 4L * m.cols() * m.rows();
            case GlslType.Array a -> a.length() * dataBytes(a.element());
            case Struct s -> {
                long sum = 0;
                for (Member m : s.members()) {
                    sum += dataBytes(m.type());
                }
                yield sum;
            }
        };
    }
}
