package vmath.gl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import vmath.annotations.Experimental;
import vmath.gl.GlslType.Array;
import vmath.gl.GlslType.Mat;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Scalar;
import vmath.gl.GlslType.Struct;
import vmath.gl.GlslType.Vec;

/**
 * Generates a shader include file from the same {@link StructLayout}s that the Java writers use, so
 * a struct is defined once: in the Java record.
 *
 * <p>The header holds an include guard, the struct declarations (nested structs first, each once),
 * the constants shared with the Java code (flag bits, sizes) and, if asked, interface blocks. Write
 * the result to a file and {@code #include} it (GLSL with {@code GL_GOOGLE_include_directive} or a
 * preprocessing step; Slang natively).
 *
 * <pre>{@code
 * String glsl = ShaderHeader.builder("VMATH_CULL")
 *         .struct(CullObjectGpu.LAYOUT)
 *         .constant("OBJECT_NO_OCCLUSION", 1)
 *         .build(ShaderHeader.Language.GLSL);
 * }</pre>
 *
 * <p>The output is deterministic, so a build can regenerate it and compare with the committed file.
 * Member offsets are in a comment on each struct so a diff shows a layout change. <b>Nothing here
 * compiles the result</b>; GLSL is plain text from {@link Struct#glslDeclaration}'s rules, and the
 * Slang output uses {@code float3}, {@code float4x4} and so on with the same member order. Matrices
 * are column-major in the data; in Slang declare them with the row/column-major option that matches
 * (for Vulkan targets {@code -fvk-use-gl-layout}, or {@code column_major} on the members you use),
 * and check the first compile against the reflection with {@link LayoutValidator}.
 *
 * <p><b>Thread safety.</b> Immutable once built, so it can be shared. The {@link Builder} is not
 * thread-safe.
 */
@Experimental("the output format may change")
public final class ShaderHeader {

    /**
     * The shading language to emit.
     */
    public enum Language {
        /**
         * GLSL source, for {@code #version 450} shaders.
         */
        GLSL,
        /**
         * Slang source.
         */
        SLANG
    }

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String guard;
    private final List<StructLayout> structs;
    private final List<String> constants;
    private final List<String> blocks;

    private ShaderHeader(String guard, List<StructLayout> structs, List<String> constants, List<String> blocks) {
        this.guard = guard;
        this.structs = structs;
        this.constants = constants;
        this.blocks = blocks;
    }

    /**
     * Starts a header with this include-guard macro name.
     *
     * @param guard the guard; must not be {@code null}
     * @return the builder, never {@code null}
     */
    public static Builder builder(String guard) {
        return new Builder(guard);
    }

    private static void identifier(String what, String s) {
        if (s == null || !IDENTIFIER.matcher(s).matches()) {
            throw new IllegalArgumentException(what + " must be an identifier: " + s);
        }
    }

    /**
     * Fluent builder.
     */
    public static final class Builder {
        private final String guard;
        private final List<StructLayout> structs = new ArrayList<>();
        private final List<String> constants = new ArrayList<>();
        private final List<String> blocks = new ArrayList<>();
        private final List<String> constantNames = new ArrayList<>();

        private Builder(String guard) {
            identifier("the include guard", guard);
            this.guard = guard;
        }

        /**
         * Adds a struct (and, before it, the structs it contains).
         *
         * <p>The layout's rules are only used for the comment on its size and offsets.
         *
         * @param layout the GPU layout; must not be {@code null}
         * @return this builder, for chaining
         */
        public Builder struct(StructLayout layout) {
            structs.add(layout);
            return this;
        }

        /**
         * Adds an unsigned integer constant to the header, so that flag bits and sizes are shared
         * between Java and shaders.
         *
         * @param name the name; must not be {@code null}
         * @param value the value
         * @return an unsigned integer constant, for flag bits and sizes shared with the Java code
         * @throws IllegalArgumentException if {@code value} does not fit in an unsigned 32-bit
         *     integer
         */
        public Builder constant(String name, long value) {
            if (value < 0 || value > 0xFFFFFFFFL) {
                throw new IllegalArgumentException(name + " does not fit in a uint: " + value);
            }
            return add(name, "uint", Long.toString(value) + "u");
        }

        /**
         * Adds a signed integer constant to the header.
         *
         * @param name the name; must not be {@code null}
         * @param value the value
         * @return a signed integer constant
         */
        public Builder constant(String name, int value) {
            return add(name, "int", Integer.toString(value));
        }

        /**
         * Adds a float constant to the header, written with enough digits to read back exactly.
         *
         * @param name the name; must not be {@code null}
         * @param value the value
         * @return a float constant, written with enough digits to read back exactly
         * @throws IllegalArgumentException if {@code value} is not finite
         */
        public Builder constant(String name, float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return add(name, "float", Float.toString(value));
        }

        private Builder add(String name, String type, String literal) {
            identifier("a constant name", name);
            if (constantNames.contains(name)) {
                throw new IllegalArgumentException("duplicate constant " + name);
            }
            constantNames.add(name);
            constants.add(type + "\t" + name + "\t" + literal);
            return this;
        }

        /**
         * Adds a GLSL interface block for a struct, as {@link Struct#glslBlock}.
         *
         * <p>Ignored in Slang output, where buffers are declared with their types.
         *
         * @param struct the struct; must not be {@code null}
         * @param layout the GPU layout; must not be {@code null}
         * @param storage the storage; must not be {@code null}
         * @param instanceName the instance name; must not be {@code null}
         * @return this builder, for chaining
         */
        public Builder block(Struct struct, GpuLayout layout, String storage, String instanceName) {
            blocks.add(struct.glslBlock(layout, storage, instanceName));
            return this;
        }

        /**
         * Builds the header; the builder may be reused.
         *
         * @return the header, never {@code null}
         */
        public ShaderHeader build() {
            return new ShaderHeader(guard, List.copyOf(structs), List.copyOf(constants), List.copyOf(blocks));
        }

        /**
         * Builds the header and emits it in one step.
         *
         * @param language the language; must not be {@code null}
         * @return shortcut for {@code build().emit(language)}
         */
        public String build(Language language) {
            return build().emit(language);
        }
    }

    /**
     * Generates the header as source text for the chosen shading language.
     *
     * @param language the language; must not be {@code null}
     * @return the header text
     */
    public String emit(Language language) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by vmath ShaderHeader from the Java struct layouts. Do not edit.\n");
        sb.append("#ifndef ").append(guard).append('\n').append("#define ").append(guard).append("\n\n");
        Map<String, String> emitted = new LinkedHashMap<>();
        for (StructLayout s : structs) {
            emitStruct(sb, s.struct(), s, emitted, language);
        }
        if (!constants.isEmpty()) {
            for (String c : constants) {
                String[] p = c.split("\t");
                if (language == Language.GLSL) {
                    sb.append("const ").append(p[0]).append(' ').append(p[1]).append(" = ").append(p[2]).append(";\n");
                } else {
                    sb.append("static const ").append(p[0]).append(' ').append(p[1]).append(" = ").append(p[2]).append(";\n");
                }
            }
            sb.append('\n');
        }
        if (language == Language.GLSL) {
            for (String b : blocks) {
                sb.append(b).append("\n\n");
            }
        }
        sb.append("#endif // ").append(guard).append('\n');
        return sb.toString();
    }

    private static void emitStruct(StringBuilder sb, Struct struct, StructLayout layout, Map<String, String> emitted, Language language) {
        identifier("a struct name", struct.name());
        for (Member m : struct.members()) {
            identifier("a member name of " + struct.name(), m.name());
            GlslType t = m.type() instanceof Array a ? a.element() : m.type();
            if (t instanceof Struct inner) {
                emitStruct(sb, inner, null, emitted, language);
            }
        }
        String text = declaration(struct, language);
        String previous = emitted.get(struct.name());
        if (previous != null) {
            if (!previous.equals(text)) {
                throw new IllegalArgumentException("two different structs are named " + struct.name());
            }
            return;
        }
        emitted.put(struct.name(), text);
        if (layout != null) {
            sb.append("// ").append(layout.layout().glslName()).append(", ").append(layout.size()).append(" bytes, alignment ").append(layout.alignment()).append(": ");
            boolean first = true;
            for (StructLayout.Field f : layout.fields()) {
                sb.append(first ? "" : ", ").append(f.name()).append('@').append(f.offset());
                first = false;
            }
            sb.append('\n');
        }
        sb.append(text).append("\n\n");
    }

    private static String declaration(Struct struct, Language language) {
        if (language == Language.GLSL) {
            return struct.glslDeclaration();
        }
        StringBuilder sb = new StringBuilder("struct ").append(struct.name()).append(" {\n");
        for (Member m : struct.members()) {
            sb.append("    ");
            if (m.type() instanceof Array a) {
                if (a.element() instanceof Array) {
                    throw new IllegalArgumentException("arrays of arrays are not supported: " + struct.name() + "." + m.name());
                }
                sb.append(slang(a.element())).append(' ').append(m.name()).append('[').append(a.length()).append(']');
            } else {
                sb.append(slang(m.type())).append(' ').append(m.name());
            }
            sb.append(";\n");
        }
        return sb.append("};").toString();
    }

    private static String slang(GlslType t) {
        return switch (t) {
            case Scalar s -> s.glsl();
            case Vec v -> {
                String base = switch (v.component().glsl()) {
                    case "float" -> "float";
                    case "int" -> "int";
                    default -> "uint";
                };
                yield base + v.length();
            }
            case Mat m -> "float" + m.rows() + "x" + m.cols();
            case Struct s -> s.name();
            case Array a -> throw new IllegalArgumentException("arrays of arrays are not supported");
        };
    }
}
