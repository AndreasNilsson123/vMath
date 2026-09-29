package vmath.gl;

import java.util.ArrayList;
import java.util.List;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Struct;

/**
 * Where each member of a {@link Struct} lives in memory under one {@link GpuLayout}: byte offsets, the total size and
 * the struct's alignment. Get one from {@link Struct#layout(GpuLayout)}.
 *
 * @param struct    the laid-out struct
 * @param layout    the rules used
 * @param alignment base alignment in bytes; place an instance of this struct at a multiple of it
 * @param size      total size in bytes, already rounded up to {@code alignment}; the stride when used in an array
 * @param fields    members in declaration order with their offsets
 */
public record StructLayout(Struct struct, GpuLayout layout, int alignment, long size, List<Field> fields) {

    /** A member with its position. */
    public record Field(String name, GlslType type, long offset) {

        /** Bytes the member occupies (its type's size, including any tail padding of arrays and structs). */
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

    /** Offset of the named member. */
    public long offsetOf(String member) {
        return field(member).offset();
    }

    public Field field(String member) {
        for (Field f : fields) {
            if (f.name().equals(member)) {
                return f;
            }
        }
        throw new IllegalArgumentException("no member '" + member + "' in " + struct.name());
    }

    /** Bytes of padding: everything in {@link #size} that no member's data can occupy. Useful to spot wasteful ordering. */
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
