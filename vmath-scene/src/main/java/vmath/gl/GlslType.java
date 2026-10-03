package vmath.gl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A GLSL type as far as memory layout is concerned, with the size and alignment rules of
 * {@link GpuLayout} encoded once here.
 *
 * <p>Build types from the constants ({@link #FLOAT}, {@link #VEC3}, {@link #MAT4}, ...),
 * {@link #array} and {@link Struct}; ask a struct for its
 * {@link Struct#layout(GpuLayout) StructLayout} to get member offsets.
 *
 * <p>All scalars here are 4 bytes ({@code float}, {@code int}, {@code uint}); double-precision and
 * 16-bit types are not supported.
 *
 * <p><b>Thread safety.</b> Immutable: the types are values and can be shared between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GlslType matrix = GlslType.MAT4;
 * long size = matrix.size(GpuLayout.STD140);                           // 64
 * GlslType lights = GlslType.array(GlslType.VEC4, 8);
 * long stride = ((GlslType.Array) lights).stride(GpuLayout.STD140);    // 16
 * }</pre>
 */
public sealed interface GlslType {

    /**
     * Computes the base alignment of the type under a layout, which differs between std140, std430
     * and scalar rules.
     *
     * @param layout the GPU layout; must not be {@code null}
     * @return base alignment in bytes under {@code layout}
     */
    int alignment(GpuLayout layout);

    /**
     * Computes the size of the type under a layout, including the padding that belongs to the type.
     *
     * @param layout the GPU layout; must not be {@code null}
     * @return size in bytes under {@code layout}, including any padding that belongs to the type
     *     (array tails, struct tails)
     */
    long size(GpuLayout layout);

    /**
     * Renders the type as it is written in GLSL source.
     *
     * @return the type as written in GLSL source, e.g. {@code vec3}, {@code mat4},
     *     {@code float[4]}, or the struct name
     */
    String glsl();

    // ---------------------------------------------------------------- constants

    /**
     * The GLSL type {@code float}.
     */
    Scalar FLOAT = new Scalar("float");
    /**
     * The GLSL type {@code int}.
     */
    Scalar INT = new Scalar("int");
    /**
     * The GLSL type {@code uint}.
     */
    Scalar UINT = new Scalar("uint");

    /**
     * The GLSL type {@code vec2}.
     */
    Vec VEC2 = new Vec(FLOAT, 2);
    /**
     * The GLSL type {@code vec3}.
     */
    Vec VEC3 = new Vec(FLOAT, 3);
    /**
     * The GLSL type {@code vec4}.
     */
    Vec VEC4 = new Vec(FLOAT, 4);
    /**
     * The GLSL type {@code ivec2}.
     */
    Vec IVEC2 = new Vec(INT, 2);
    /**
     * The GLSL type {@code ivec3}.
     */
    Vec IVEC3 = new Vec(INT, 3);
    /**
     * The GLSL type {@code ivec4}.
     */
    Vec IVEC4 = new Vec(INT, 4);
    /**
     * The GLSL type {@code uvec2}.
     */
    Vec UVEC2 = new Vec(UINT, 2);
    /**
     * The GLSL type {@code uvec3}.
     */
    Vec UVEC3 = new Vec(UINT, 3);
    /**
     * The GLSL type {@code uvec4}.
     */
    Vec UVEC4 = new Vec(UINT, 4);

    /**
     * The GLSL type {@code mat2}.
     */
    Mat MAT2 = new Mat(2, 2);
    /**
     * The GLSL type {@code mat3}.
     */
    Mat MAT3 = new Mat(3, 3);
    /**
     * The GLSL type {@code mat4}.
     */
    Mat MAT4 = new Mat(4, 4);

    /**
     * Creates the type of a matrix with the given numbers of columns and rows.
     *
     * @param cols the cols
     * @param rows the rows
     * @return {@code matCxR} with {@code cols} columns of {@code rows} components; e.g.
     *     {@code mat(4, 3)} is {@code mat4x3}
     */
    static Mat mat(int cols, int rows) {
        return new Mat(cols, rows);
    }

    /**
     * Creates an array type from an element type and a length; arrays of arrays are not allowed.
     *
     * @param element the element; must not be {@code null}
     * @param length the length
     * @return an array of {@code length} (at least 1) elements of {@code element}; arrays of arrays
     *     are not allowed
     */
    static Array array(GlslType element, int length) {
        return new Array(element, length);
    }

    // ---------------------------------------------------------------- implementations

    /**
     * A 4-byte scalar.
     *
     * @param glsl the glsl; must not be {@code null}
     */
    record Scalar(String glsl) implements GlslType {
        @Override
        public int alignment(GpuLayout layout) {
            return 4;
        }

        @Override
        public long size(GpuLayout layout) {
            return 4;
        }
    }

    /**
     * A vector of 2 to 4 scalars.
     *
     * <p>In std140/std430 a 2-vector aligns to 8 and 3- and 4-vectors to 16.
     *
     * @param component the component; must not be {@code null}
     * @param length the length
     */
    record Vec(Scalar component, int length) implements GlslType {
        /**
         * Checks the length: 2 to 4.
         *
         * @param component the component; must not be {@code null}
         * @param length the length
         * @throws IllegalArgumentException if {@code length} is not in {@code [2, 4]}
         */
        public Vec {
            if (length < 2 || length > 4) {
                throw new IllegalArgumentException("vector length must be 2..4: " + length);
            }
        }

        @Override
        public int alignment(GpuLayout layout) {
            if (layout == GpuLayout.SCALAR) {
                return 4;
            }
            return length == 2 ? 8 : 16;
        }

        @Override
        public long size(GpuLayout layout) {
            return 4L * length;
        }

        @Override
        public String glsl() {
            String prefix = switch (component.glsl()) {
                case "float" -> "";
                case "int" -> "i";
                default -> "u";
            };
            return prefix + "vec" + length;
        }
    }

    /**
     * A float matrix, column-major, stored as {@code cols} column vectors of {@code rows}
     * components.
     *
     * <p>The distance between columns ({@link #columnStride}) is what differs between layouts:
     * std140 pads every column to 16 bytes, std430 to the column vector's own alignment, and scalar
     * packs them tightly.
     *
     * @param cols the cols
     * @param rows the rows
     */
    record Mat(int cols, int rows) implements GlslType {
        /**
         * Checks the dimensions: 2 to 4 in each.
         *
         * @param cols the cols
         * @param rows the rows
         * @throws IllegalArgumentException if a dimension is not in {@code [2, 4]}
         */
        public Mat {
            if (cols < 2 || cols > 4 || rows < 2 || rows > 4) {
                throw new IllegalArgumentException("matrix dimensions must be 2..4: " + cols + "x" + rows);
            }
        }

        /**
         * Computes the distance between the starts of consecutive columns under a layout, which for
         * std140 is rounded up to a multiple of 16.
         *
         * @param layout the GPU layout; must not be {@code null}
         * @return bytes from the start of one column to the start of the next
         */
        public int columnStride(GpuLayout layout) {
            int column = new Vec(FLOAT, rows).alignment(layout);
            return switch (layout) {
                case STD140 -> Align.roundUp(column, 16);
                case STD430 -> column;
                case SCALAR -> 4 * rows;
            };
        }

        @Override
        public int alignment(GpuLayout layout) {
            return layout == GpuLayout.SCALAR ? 4 : columnStride(layout);
        }

        @Override
        public long size(GpuLayout layout) {
            return (long) cols * columnStride(layout);
        }

        @Override
        public String glsl() {
            return cols == rows ? "mat" + cols : "mat" + cols + "x" + rows;
        }
    }

    /**
     * A fixed-length array.
     *
     * <p>The {@link #stride} is what differs between layouts.
     *
     * @param element the element; must not be {@code null}
     * @param length the length
     */
    record Array(GlslType element, int length) implements GlslType {
        /**
         * Checks the length (at least 1) and that the element is not itself an array.
         *
         * @param element the element; must not be {@code null}
         * @param length the length
         * @throws IllegalArgumentException if {@code length} is below 1 or the element is itself an
         *     array
         */
        public Array {
            if (length < 1) {
                throw new IllegalArgumentException("array length must be >= 1: " + length);
            }
            if (element instanceof Array) {
                throw new IllegalArgumentException("arrays of arrays are not supported; wrap the inner array in a struct");
            }
        }

        /**
         * Computes the distance between array elements under a layout, which is rounded up to the
         * alignment of the element type for std140.
         *
         * @param layout the GPU layout; must not be {@code null}
         * @return bytes from one element to the next
         */
        public long stride(GpuLayout layout) {
            return Align.roundUp(element.size(layout), alignment(layout));
        }

        @Override
        public int alignment(GpuLayout layout) {
            int a = element.alignment(layout);
            return layout == GpuLayout.STD140 ? Align.roundUp(a, 16) : a;
        }

        @Override
        public long size(GpuLayout layout) {
            return length * stride(layout);
        }

        @Override
        public String glsl() {
            return element.glsl() + "[" + length + "]";
        }
    }

    /**
     * One member of a {@link Struct}.
     *
     * @param name the name; must not be {@code null}
     * @param type the type; must not be {@code null}
     */
    record Member(String name, GlslType type) {
    }

    /**
     * A struct type.
     *
     * <p>Members are laid out in declaration order, each at the next offset that satisfies its
     * alignment.
     *
     * @param name the name; must not be {@code null}
     * @param members the members; must not be {@code null}
     */
    record Struct(String name, List<Member> members) implements GlslType {
        /**
         * Checks that the struct has members and that their names are distinct.
         *
         * @param name the name; must not be {@code null}
         * @param members the members; must not be {@code null}
         * @throws IllegalArgumentException if the struct has no members or two members have the
         *     same name
         */
        public Struct {
            members = List.copyOf(members);
            if (members.isEmpty()) {
                throw new IllegalArgumentException("struct " + name + " has no members");
            }
            Set<String> seen = new HashSet<>();
            for (Member m : members) {
                if (!seen.add(m.name())) {
                    throw new IllegalArgumentException("struct " + name + " has two members named " + m.name());
                }
            }
        }

        /**
         * Computes the member offsets, size and alignment of the struct under a layout, by applying
         * the layout's packing rules member by member.
         *
         * @param layout the GPU layout; must not be {@code null}
         * @return offsets, size and alignment of this struct under {@code layout}
         */
        public StructLayout layout(GpuLayout layout) {
            return StructLayout.compute(this, layout);
        }

        @Override
        public int alignment(GpuLayout layout) {
            int a = 4;
            for (Member m : members) {
                a = Math.max(a, m.type().alignment(layout));
            }
            return layout == GpuLayout.STD140 ? Align.roundUp(a, 16) : a;
        }

        @Override
        public long size(GpuLayout layout) {
            long cursor = 0;
            for (Member m : members) {
                cursor = Align.roundUp(cursor, m.type().alignment(layout)) + m.type().size(layout);
            }
            return Align.roundUp(cursor, alignment(layout));
        }

        @Override
        public String glsl() {
            return name;
        }

        /**
         * Generates the GLSL declaration of the struct as source text, to be included in shader
         * code.
         *
         * <p>Nested structs are not emitted.
         *
         * @return the GLSL declaration, e.g. {@code struct Light { vec3 position; float radius; };}
         */
        public String glslDeclaration() {
            StringBuilder sb = new StringBuilder("struct ").append(name).append(" {\n");
            for (Member m : members) {
                sb.append("    ");
                if (m.type() instanceof Array a) {
                    sb.append(a.element().glsl()).append(' ').append(m.name()).append('[').append(a.length()).append(']');
                } else {
                    sb.append(m.type().glsl()).append(' ').append(m.name());
                }
                sb.append(";\n");
            }
            return sb.append("};").toString();
        }

        /**
         * Generates the GLSL interface block of the struct for a given layout and instance name, as
         * source text.
         *
         * @param layout the GPU layout; must not be {@code null}
         * @param storage {@code "uniform"} or {@code "buffer"}
         * @param instanceName the instance name; may be {@code null}
         * @return a GLSL interface block with this struct's members, e.g.
         *     {@code layout(std140) uniform Light { ... } light;}
         */
        public String glslBlock(GpuLayout layout, String storage, String instanceName) {
            StringBuilder sb = new StringBuilder("layout(").append(layout.glslName()).append(") ")
                    .append(storage).append(' ').append(name).append(" {\n");
            for (Member m : members) {
                sb.append("    ");
                if (m.type() instanceof Array a) {
                    sb.append(a.element().glsl()).append(' ').append(m.name()).append('[').append(a.length()).append(']');
                } else {
                    sb.append(m.type().glsl()).append(' ').append(m.name());
                }
                sb.append(";\n");
            }
            return sb.append('}').append(instanceName == null || instanceName.isEmpty() ? "" : " " + instanceName).append(';').toString();
        }
    }
}
