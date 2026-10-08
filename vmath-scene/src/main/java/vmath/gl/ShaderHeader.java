package vmath.gl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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

    /**
     * The graphics API whose GLSL the header is written in.
     */
    public enum Target {
        /** OpenGL, the default: what the header has always been. */
        OPENGL,
        /**
         * Vulkan GLSL ({@code GL_KHR_vulkan_glsl}): every block and sampler is in a descriptor set
         * and has a binding ({@code layout(set = s, binding = n)}), constants that change often are
         * in a {@code push_constant} block (std430, at most {@value ShaderHeader#MAX_PUSH_CONSTANT_BYTES} bytes, the size
         * Vulkan guarantees), and there are no loose uniforms. The text needs GLSL 4.50 (what
         * Vulkan 1.0 GLSL is) and is compiled to SPIR-V by the tests with glslang.
         */
        VULKAN
    }

    /** The size of push constants that every Vulkan device has: the most a push constant block may be. */
    public static final int MAX_PUSH_CONSTANT_BYTES = 128;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String guard;
    private final List<StructLayout> structs;
    private final List<String> constants;
    private final List<String> blocks;
    private final List<String> accesses;
    private final GlslVersion version;
    private final GlslEsVersion esVersion;
    private final boolean versionLine;
    private final List<BlockSpec> blockSpecs;
    private final List<StructArrayAccess> accessSpecs;
    private final Target target;
    private final List<Binding> bindings;
    private final List<String> glslAccesses;

    private ShaderHeader(String guard, List<StructLayout> structs, List<String> constants, List<String> blocks, List<String> accesses, GlslVersion version, GlslEsVersion esVersion, boolean versionLine,
                         List<BlockSpec> blockSpecs, List<StructArrayAccess> accessSpecs, Target target) {
        this.target = target;
        this.guard = guard;
        this.structs = structs;
        this.constants = constants;
        this.blocks = blocks;
        this.accesses = accesses;
        this.version = version;
        this.esVersion = esVersion;
        this.versionLine = versionLine;
        this.blockSpecs = blockSpecs;
        this.accessSpecs = accessSpecs;
        List<Binding> missing = new ArrayList<>();
        List<String> texts = accesses;
        if (version != null && !version.supports(GlslFeature.EXPLICIT_BINDING) || esVersion != null && !esVersion.supports(GlslFeature.EXPLICIT_BINDING)) {
            texts = new ArrayList<>();
            for (int i = 0; i < accesses.size(); i++) {
                texts.add(withoutBinding(accessSpecs.get(i), accesses.get(i), missing));
            }
        }
        this.bindings = List.copyOf(missing);
        this.glslAccesses = texts;
    }

    /** What a block of the header needs of the language, kept to check it against a version. */
    private record BlockSpec(GpuLayout layout, String storage) {
    }

    /**
     * A binding point that the header could not write because its version has no explicit binding
     * (below GLSL 4.20): the host sets it after linking.
     *
     * @param name the name of the array, as given to {@link StructArrayAccess#of}
     * @param mode the access mode, {@link StructArrayAccess.Mode#UNIFORM_BLOCK} or {@link StructArrayAccess.Mode#TEXTURE_BUFFER}
     * @param slot the binding point or texture unit that the array was given
     * @param how the call that sets it on the host, for example {@code glUniformBlockBinding(program, glGetUniformBlockIndex(program, "styles_Block"), 1)}
     */
    public record Binding(String name, StructArrayAccess.Mode mode, int slot, String how) {
    }

    /**
     * Gives the binding points that {@link #emit} for GLSL could not write into the text
     * and that the host must set, in the order of the arrays.
     *
     * <p>It is empty for a header without a version (nothing is stripped, the output is as it
     * always was) and for a version from 4.20, which has explicit bindings. The same list is
     * written into the header as a comment.
     *
     * @return the bindings; empty if there are none
     */
    public List<Binding> bindings() {
        return bindings;
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
        private final List<String> accesses = new ArrayList<>();
        private final List<String> constantNames = new ArrayList<>();
        private final List<BlockSpec> blockSpecs = new ArrayList<>();
        private final List<StructArrayAccess> accessSpecs = new ArrayList<>();
        private GlslVersion version;
        private GlslEsVersion esVersion;
        private boolean versionLine;
        private Target target = Target.OPENGL;
        private int plainBlocks;
        private int vulkanBlocks;
        private int pushBlocks;

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
            blockSpecs.add(new BlockSpec(layout, storage));
            plainBlocks++;
            return this;
        }

        /**
         * Sets the graphics API whose GLSL is written; without it OpenGL, as always.
         *
         * @param target the target; must not be {@code null}
         * @return this builder
         */
        public Builder target(Target target) {
            if (target == null) {
                throw new IllegalArgumentException("the target must not be null");
            }
            this.target = target;
            return this;
        }

        /**
         * Adds an interface block in a Vulkan descriptor set: {@code layout(std140, set = s, binding = n) uniform ...}
         * (or {@code std430} and {@code buffer}). Only for {@link Target#VULKAN}.
         *
         * @param struct the members of the block; must not be {@code null}
         * @param layout the layout of the block
         * @param storage {@code "uniform"} or {@code "buffer"}
         * @param instanceName the instance name; may be {@code null}
         * @param set the descriptor set, not negative
         * @param binding the binding in the set, not negative
         * @return this builder
         * @throws IllegalArgumentException if the set or the binding is negative
         */
        public Builder block(Struct struct, GpuLayout layout, String storage, String instanceName, int set, int binding) {
            if (set < 0 || binding < 0) {
                throw new IllegalArgumentException("the set and the binding must not be negative: " + set + ", " + binding);
            }
            blocks.add(struct.glslBlock(layout, storage, instanceName).replaceFirst("layout\\(" + layout.glslName() + "\\)", "layout(" + layout.glslName() + ", set = " + set + ", binding = " + binding + ")"));
            blockSpecs.add(new BlockSpec(layout, storage));
            vulkanBlocks++;
            return this;
        }

        /**
         * Adds the push constant block: {@code layout(std430, push_constant) uniform ...}. Only for {@link Target#VULKAN},
         * once, and at most {@value ShaderHeader#MAX_PUSH_CONSTANT_BYTES} bytes (what every Vulkan device has).
         *
         * @param struct the members of the block; must not be {@code null}
         * @param instanceName the instance name; may be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if there already is a push constant block or the block is larger than the guaranteed size
         */
        public Builder pushConstants(Struct struct, String instanceName) {
            if (pushBlocks > 0) {
                throw new IllegalArgumentException("a shader has one push constant block");
            }
            long size = struct.layout(GpuLayout.STD430).size();
            if (size > MAX_PUSH_CONSTANT_BYTES) {
                throw new IllegalArgumentException("a push constant block of " + size + " bytes is more than the " + MAX_PUSH_CONSTANT_BYTES + " that every Vulkan device has");
            }
            blocks.add(struct.glslBlock(GpuLayout.STD430, "uniform", instanceName).replaceFirst("layout\\(std430\\)", "layout(std430, push_constant)"));
            blockSpecs.add(new BlockSpec(GpuLayout.STD430, "uniform"));
            pushBlocks++;
            return this;
        }

        /**
         * Sets the GLSL version that the header is written for. Without it nothing is checked and
         * the output is exactly what it has always been. With it, {@link ShaderHeader#emit} for GLSL
         * refuses, before it writes anything, a construct that the version does not have (a storage
         * block, a {@code std430} layout or the memory qualifiers below 4.30 and 4.20, and so on, from
         * the table of {@link GlslFeature}) with an {@link UnsupportedOperationException} that names the
         * construct and the lowest version; it never writes another layout instead. From 4.20
         * the text is unchanged; below it, the explicit binding points are left out of the text and
         * listed in {@link ShaderHeader#bindings()} and in a comment, for the host to set.
         *
         * @param version the version; must not be {@code null}
         * @return this builder
         */
        public Builder version(GlslVersion version) {
            if (version == null) {
                throw new IllegalArgumentException("the version must not be null");
            }
            this.version = version;
            return this;
        }

        /**
         * Sets the GLSL ES version that the header is written for, in place of a desktop version
         * ({@link #version}); the rules are those of {@link #version}, with the ES column of
         * {@link GlslFeature#minimumEs()}: below 3.10 a storage block, a {@code std430} layout, the memory
         * qualifiers and explicit binding points do not exist (the bindings are left out and returned,
         * the rest throws), a texture buffer needs 3.20, and a double or {@code gl_DrawID} never exist.
         * The header writes the precision statements that ES needs ({@code precision highp float;},
         * {@code precision highp int;} and one for {@code usamplerBuffer} where a texture buffer is
         * read), so that it can be included into a fragment shader; the {@code #version 300 es} line is
         * written with {@link #withVersionLine}. OpenGL ES only: not with a Vulkan target.
         *
         * @param version the ES version; must not be {@code null}
         * @return this builder
         */
        public Builder esVersion(GlslEsVersion version) {
            if (version == null) {
                throw new IllegalArgumentException("the version must not be null");
            }
            this.esVersion = version;
            return this;
        }

        /**
         * Asks for a {@code #version} line at the top of the GLSL text, for a header that is not
         * included into a shader that has one. Needs {@link #version}.
         *
         * @return this builder
         */
        public Builder withVersionLine() {
            this.versionLine = true;
            return this;
        }

        /**
         * Adds an array of structs together with the function that reads it (GLSL output only).
         *
         * <p>The element struct is declared in the header too, so it need not be added with
         * {@link #struct}. The text of {@link StructArrayAccess#glsl()} comes after the blocks, and
         * its {@code fetch_<name>} function is what shader code calls, whichever access mode the
         * array has.
         *
         * @param access the array and its access mode; must not be {@code null}
         * @return this builder
         */
        public Builder access(StructArrayAccess access) {
            structs.add(access.layout());
            accesses.add(access.glsl());
            accessSpecs.add(access);
            return this;
        }

        /**
         * Builds the header; the builder may be reused.
         *
         * @return the header, never {@code null}
         */
        public ShaderHeader build() {
            if (versionLine && version == null && esVersion == null) {
                throw new IllegalStateException("withVersionLine needs a version");
            }
            if (version != null && esVersion != null) {
                throw new IllegalArgumentException("a header is for a desktop version or an ES version, not both");
            }
            if (esVersion != null && target == Target.VULKAN) {
                throw new IllegalArgumentException("a Vulkan header is not for OpenGL ES");
            }
            if (target == Target.VULKAN) {
                if (plainBlocks > 0) {
                    throw new IllegalArgumentException("a block in a Vulkan header needs a set and a binding: use the overload that takes them, or pushConstants");
                }
                for (StructArrayAccess a : accessSpecs) {
                    if (a.api() != GraphicsCapabilities.Api.VULKAN) {
                        throw new IllegalArgumentException("the access " + a.name() + " was made for OpenGL: make it with Vulkan capabilities");
                    }
                }
            } else {
                if (vulkanBlocks > 0 || pushBlocks > 0) {
                    throw new IllegalArgumentException("a block with a set or a push constant block is Vulkan: set the target");
                }
                for (StructArrayAccess a : accessSpecs) {
                    if (a.api() == GraphicsCapabilities.Api.VULKAN) {
                        throw new IllegalArgumentException("the access " + a.name() + " was made for Vulkan: set the target");
                    }
                }
            }
            return new ShaderHeader(guard, List.copyOf(structs), List.copyOf(constants), List.copyOf(blocks), List.copyOf(accesses), version, esVersion, versionLine, List.copyOf(blockSpecs),
                    List.copyOf(accessSpecs), target);
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
        boolean gated = language == Language.GLSL && (version != null || esVersion != null);
        if (language == Language.GLSL && target == Target.VULKAN && version != null && version.number() < 450) {
            throw new UnsupportedOperationException("Vulkan GLSL needs " + GlslVersion.V450 + " or later, and this is " + version);
        }
        if (gated) {
            checkVersion();
        }
        List<String> accessTexts = glslAccesses;
        List<Binding> missing = bindings;
        StringBuilder sb = new StringBuilder();
        if (gated && versionLine) {
            // first of all, even before the comment: GLSL ES requires the version to be the first thing in the shader
            sb.append(esVersion != null ? esVersion.versionLine() : version.versionLine()).append('\n');
        }
        sb.append("// Generated by vmath ShaderHeader from the Java struct layouts. Do not edit.\n");
        sb.append("#ifndef ").append(guard).append('\n').append("#define ").append(guard).append("\n\n");
        if (gated && esVersion != null) {
            emitPrecision(sb);
        }
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
            if (!missing.isEmpty()) {
                sb.append("// no explicit binding points before ").append(GlslFeature.EXPLICIT_BINDING.minimum()).append(": the host sets them after linking\n");
                for (Binding b : missing) {
                    sb.append("//   ").append(b.how()).append('\n');
                }
                sb.append('\n');
            }
            for (String a : accessTexts) {
                sb.append(a).append('\n');
            }
        }
        sb.append("#endif // ").append(guard).append('\n');
        return sb.toString();
    }

    private void need(GlslFeature feature) {
        if (esVersion != null) {
            esVersion.require(feature);
        } else {
            version.require(feature);
        }
    }

    /** The default precisions that ES needs: a fragment shader has none for float, and a sampler or image needs one. */
    private void emitPrecision(StringBuilder sb) {
        sb.append("precision highp float;\nprecision highp int;\n");
        for (StructArrayAccess a : accessSpecs) {
            if (a.mode() == StructArrayAccess.Mode.TEXTURE_BUFFER) {
                sb.append("precision highp usamplerBuffer;\n");
                break;
            }
        }
        sb.append('\n');
    }

    private void checkVersion() {
        for (BlockSpec b : blockSpecs) {
            need("buffer".equals(b.storage()) ? GlslFeature.STORAGE_BLOCK : GlslFeature.UNIFORM_BLOCK);
            need(b.layout() == GpuLayout.STD430 ? GlslFeature.STD430_LAYOUT : GlslFeature.STD140_LAYOUT);
        }
        for (StructArrayAccess a : accessSpecs) {
            switch (a.mode()) {
                case STORAGE_BLOCK -> {
                    need(GlslFeature.STORAGE_BLOCK);
                    need(GlslFeature.STD430_LAYOUT);
                    need(GlslFeature.MEMORY_QUALIFIERS);
                }
                case UNIFORM_BLOCK -> {
                    need(GlslFeature.UNIFORM_BLOCK);
                    need(GlslFeature.STD140_LAYOUT);
                }
                case TEXTURE_BUFFER -> {
                    need(GlslFeature.TEXTURE_BUFFER);
                    need(GlslFeature.BIT_CASTS);
                }
                case VERTEX_ATTRIBUTE -> need(GlslFeature.EXPLICIT_ATTRIBUTE_LOCATION);
            }
        }
    }

    private static final Pattern BLOCK_BINDING = Pattern.compile(", binding = (\\d+)\\)");
    private static final Pattern SAMPLER_BINDING = Pattern.compile("layout\\(binding = (\\d+)\\) ");

    /** Takes the explicit binding out of the text of an access and records it for the host. */
    private static String withoutBinding(StructArrayAccess access, String text, List<Binding> out) {
        Matcher m = BLOCK_BINDING.matcher(text);
        if (m.find()) {
            int slot = Integer.parseInt(m.group(1));
            out.add(new Binding(access.name(), access.mode(), slot, "glUniformBlockBinding(program, glGetUniformBlockIndex(program, \"" + access.name() + "_Block\"), " + slot + ")"));
            return m.replaceFirst(")");
        }
        m = SAMPLER_BINDING.matcher(text);
        if (m.find()) {
            int slot = Integer.parseInt(m.group(1));
            out.add(new Binding(access.name(), access.mode(), slot, "glUniform1i(glGetUniformLocation(program, \"" + access.name() + "_texels\"), " + slot + ")"));
            return m.replaceFirst("");
        }
        return text;
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
