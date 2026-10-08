package vmath.gl;

import java.util.List;
import java.util.regex.Pattern;
import vmath.annotations.Experimental;
import vmath.gl.GlslType.Mat;
import vmath.gl.GlslType.Scalar;
import vmath.gl.GlslType.Struct;
import vmath.gl.GlslType.Vec;
import vmath.gl.GraphicsCapabilities.Feature;

/**
 * An array of structs in a shader, read the same way by the shader code whichever of four buffer
 * kinds holds it.
 *
 * <p>The shader body calls {@code fetch_<name>(i)} and gets a struct; what is behind it depends on
 * the {@link Mode}:
 *
 * <ul>
 *   <li>{@link Mode#STORAGE_BLOCK}: a shader storage block, {@code std430}, any length (OpenGL 4.3).
 *   <li>{@link Mode#UNIFORM_BLOCK}: an array in a uniform block, {@code std140}, as long as the
 *       maximum block size allows (OpenGL 3.1).
 *   <li>{@link Mode#TEXTURE_BUFFER}: a texture buffer of {@code RGBA32UI} texels, each element a
 *       whole number of texels, any length (OpenGL 3.1).
 *   <li>{@link Mode#VERTEX_ATTRIBUTE}: per-instance vertex attributes; {@code fetch_<name>} then
 *       returns the instance that is being drawn and ignores its argument (OpenGL 3.3).
 * </ul>
 *
 * <p>The Java side writes the elements with the writers of the struct in the layout that
 * {@link #layout()} names and at the stride of {@link #elementStride()}, whichever mode: the
 * buffer is then a storage buffer, a uniform buffer, the store of a texture buffer
 * ({@code RGBA32UI}, {@link #texelsPerElement()} texels per element) or a vertex buffer
 * ({@link #vertexLayout()}).
 *
 * <p>The texture-buffer and attribute modes read scalars and vectors of {@code float}, {@code int}
 * and {@code uint} only. A matrix, array or nested struct member is refused with an
 * {@link UnsupportedOperationException} that names it: the library does not guess a different
 * layout for it.
 *
 * <p>The version limits ({@code layout(binding)} from GLSL 4.20) come from the
 * {@link GraphicsCapabilities} given to {@link #of}; the text is not compiled here, which the tests
 * do with a GLSL compiler when one is installed.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GraphicsCapabilities caps = GraphicsCapabilities.baseline();
 * Mode mode = StructArrayAccess.choose(caps, Need.RANDOM_ACCESS);                          // TEXTURE_BUFFER
 * StructArrayAccess styles = StructArrayAccess.of(style, mode, "styles", 0, -1, caps);
 * String header = ShaderHeader.builder("STYLES").access(styles).build(ShaderHeader.Language.GLSL);
 * }</pre>
 */
@Experimental("the access modes may change")
public final class StructArrayAccess {

    /**
     * Where the elements are.
     */
    public enum Mode {
        /** A shader storage block. */
        STORAGE_BLOCK(Feature.STORAGE_BUFFERS),
        /** An array in a uniform block. */
        UNIFORM_BLOCK(Feature.UNIFORM_BLOCKS),
        /** A texture buffer of unsigned integer texels. */
        TEXTURE_BUFFER(Feature.TEXTURE_BUFFERS),
        /** Vertex attributes that advance per instance. */
        VERTEX_ATTRIBUTE(Feature.INSTANCED_ARRAYS);

        private final Feature requires;

        Mode(Feature requires) {
            this.requires = requires;
        }

        /**
         * Names the feature that the mode needs.
         *
         * @return the feature
         */
        public Feature requires() {
            return requires;
        }
    }

    /**
     * How the shader reads the array.
     */
    public enum Need {
        /** By any index: a table of styles, looked up by a draw or object index. */
        RANDOM_ACCESS,
        /** By the instance that is being drawn, which a vertex attribute gives for free. */
        PER_INSTANCE
    }

    /** The smallest maximum size of a uniform block that OpenGL guarantees, in bytes. */
    public static final int GUARANTEED_UNIFORM_BLOCK_BYTES = 16384;

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final StrategyChooser<Mode> RANDOM = StrategyChooser.<Mode>builder("reading an array of structs by index")
            .option(Mode.STORAGE_BLOCK, "a storage block, any length", Feature.STORAGE_BUFFERS)
            .option(Mode.TEXTURE_BUFFER, "a texture buffer of RGBA32UI texels, any length", Feature.TEXTURE_BUFFERS)
            .option(Mode.UNIFORM_BLOCK, "an array in a uniform block, as long as the block size allows", Feature.UNIFORM_BLOCKS)
            .build();

    private static final StrategyChooser<Mode> INSTANCE = StrategyChooser.<Mode>builder("reading an array of structs per instance")
            .option(Mode.VERTEX_ATTRIBUTE, "per-instance vertex attributes, nothing to fetch", Feature.INSTANCED_ARRAYS)
            .option(Mode.STORAGE_BLOCK, "a storage block read by the instance index", Feature.STORAGE_BUFFERS)
            .option(Mode.TEXTURE_BUFFER, "a texture buffer read by the instance index", Feature.TEXTURE_BUFFERS)
            .option(Mode.UNIFORM_BLOCK, "an array in a uniform block read by the instance index", Feature.UNIFORM_BLOCKS)
            .build();

    private final Mode mode;
    private final String name;
    private final Struct struct;
    private final StructLayout layout;
    private final long elementStride;
    private final int texelsPerElement;
    private final VertexBufferLayout vertexLayout;
    private final boolean explicitBinding;
    private final String glsl;
    private final boolean vulkan;

    private StructArrayAccess(Mode mode, String name, Struct struct, StructLayout layout, long elementStride, int texelsPerElement, VertexBufferLayout vertexLayout,
                              boolean explicitBinding, String glsl, boolean vulkan) {
        this.vulkan = vulkan;
        this.mode = mode;
        this.name = name;
        this.struct = struct;
        this.layout = layout;
        this.elementStride = elementStride;
        this.texelsPerElement = texelsPerElement;
        this.vertexLayout = vertexLayout;
        this.explicitBinding = explicitBinding;
        this.glsl = glsl;
    }

    /**
     * Gives the decision table of a need.
     *
     * @param need the need; must not be {@code null}
     * @return the chooser for it
     */
    public static StrategyChooser<Mode> chooser(Need need) {
        return need == Need.RANDOM_ACCESS ? RANDOM : INSTANCE;
    }

    /**
     * Chooses the best mode that the capabilities allow for a need.
     *
     * @param caps the capabilities; must not be {@code null}
     * @param need how the shader reads the array; must not be {@code null}
     * @return the mode: for random access the storage block, else the texture buffer, else the
     *     uniform block; per instance the vertex attribute first
     * @throws UnsupportedOperationException if no mode is possible
     */
    public static Mode choose(GraphicsCapabilities caps, Need need) {
        return chooser(need).choose(caps);
    }

    /**
     * Describes an array of structs in one mode.
     *
     * @param struct the element type; must not be {@code null}
     * @param mode the mode; must not be {@code null}
     * @param name the name of the array in the shader, an identifier; the function that reads it
     *     is {@code fetch_<name>}
     * @param count the number of elements: the length of the array of a uniform block (at least
     *     1), the size of the data otherwise (only checked to be at least 0)
     * @param slot the binding point of a storage or uniform block or of the sampler of a texture
     *     buffer, written only where the GLSL version has explicit bindings (4.20) and when it is
     *     not negative; for {@link Mode#VERTEX_ATTRIBUTE} the first attribute location, which must
     *     not be negative
     * @param caps what the driver can do; must not be {@code null}
     * @return the description, with the GLSL text
     * @throws IllegalArgumentException if {@code name} is not an identifier, {@code count} is out
     *     of range, a uniform block would be larger than {@link #GUARANTEED_UNIFORM_BLOCK_BYTES},
     *     or an attribute location is negative
     * @throws UnsupportedOperationException if the capabilities lack what the mode needs, or the
     *     struct has a member that the mode cannot read
     */
    public static StructArrayAccess of(Struct struct, Mode mode, String name, int count, int slot, GraphicsCapabilities caps) {
        return make(struct, mode, name, count, 0, slot, caps, false);
    }

    /**
     * Makes the access for a Vulkan descriptor set and binding, or, for an OpenGL context, exactly as the
     * method without a set.
     *
     * <p>For capabilities of {@link GraphicsCapabilities.Api#VULKAN} the text is Vulkan GLSL (the method without a set always writes OpenGL GLSL, whatever the capabilities, which is what the plans of the library
     * use): every
     * block and the texture buffer carry {@code layout(set = s, binding = n)} (a binding is then
     * required, so {@code slot} must not be negative), the texture buffer is a {@code utextureBuffer}
     * (Vulkan has no combined sampler for it), and the vertex attributes are as for OpenGL. The text is
     * compiled to SPIR-V by the tests with glslang. For OpenGL capabilities the set must be 0.
     *
     * @param struct the element type; must not be {@code null}
     * @param mode how the array is read
     * @param name the name of the array, an identifier
     * @param count the number of elements (the length of a uniform block's array, at least 1; 0 for the unsized modes)
     * @param set the descriptor set (Vulkan); 0 for OpenGL
     * @param slot the binding point, texture unit or first attribute location; negative for none on OpenGL
     * @param caps the capabilities that decide what can be expressed; must not be {@code null}
     * @return the access
     * @throws IllegalArgumentException as for the method without a set, or if the set is not 0 on OpenGL, or the
     *     binding is negative on Vulkan
     * @throws UnsupportedOperationException if the capabilities do not allow the mode
     */
    public static StructArrayAccess of(Struct struct, Mode mode, String name, int count, int set, int slot, GraphicsCapabilities caps) {
        return make(struct, mode, name, count, set, slot, caps, caps.api() == GraphicsCapabilities.Api.VULKAN);
    }

    private static StructArrayAccess make(Struct struct, Mode mode, String name, int count, int set, int slot, GraphicsCapabilities caps, boolean vulkan) {
        if (set < 0 || !vulkan && set != 0) {
            throw new IllegalArgumentException("a descriptor set is a Vulkan concept and not negative: " + set);
        }
        if (vulkan && mode != Mode.VERTEX_ATTRIBUTE && slot < 0) {
            throw new IllegalArgumentException("Vulkan needs a binding for every block and sampler, not " + slot);
        }
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("the name of an array must be an identifier: " + name);
        }
        chooser(mode == Mode.VERTEX_ATTRIBUTE ? Need.PER_INSTANCE : Need.RANDOM_ACCESS).force(mode, caps);
        if (count < 0 || (mode == Mode.UNIFORM_BLOCK && count < 1)) {
            throw new IllegalArgumentException("the element count is out of range: " + count);
        }
        boolean binding = vulkan ? mode != Mode.VERTEX_ATTRIBUTE : slot >= 0 && caps.glsl().supports(GlslFeature.EXPLICIT_BINDING);
        String where = vulkan ? ", set = " + set + ", binding = " + slot : binding ? ", binding = " + slot : "";
        StructLayout layout = struct.layout(mode == Mode.UNIFORM_BLOCK ? GpuLayout.STD140 : GpuLayout.STD430);
        String fetch = "fetch_" + name;
        String type = struct.name();
        switch (mode) {
            case STORAGE_BLOCK: {
                long stride = layout.size();
                String text = "layout(std430" + where + ") readonly buffer " + name + "_Block {\n    " + type + " " + name + "[];\n};\n\n"
                        + type + " " + fetch + "(int i) {\n    return " + name + "[i];\n}\n";
                return new StructArrayAccess(mode, name, struct, layout, stride, 0, null, binding, text, vulkan);
            }
            case UNIFORM_BLOCK: {
                long stride = layout.size();
                if (stride * count > GUARANTEED_UNIFORM_BLOCK_BYTES) {
                    throw new IllegalArgumentException(count + " elements of " + stride + " bytes are " + stride * count + " bytes, more than the " + GUARANTEED_UNIFORM_BLOCK_BYTES
                            + " that a uniform block is guaranteed to hold: use a storage block or a texture buffer");
                }
                String text = "layout(std140" + where + ") uniform " + name + "_Block {\n    " + type + " " + name + "[" + count + "];\n};\n\n"
                        + type + " " + fetch + "(int i) {\n    return " + name + "[i];\n}\n";
                return new StructArrayAccess(mode, name, struct, layout, stride, 0, null, binding, text, vulkan);
            }
            case TEXTURE_BUFFER: {
                requireScalarsAndVectors(struct, mode);
                long stride = (layout.size() + 15) & ~15L;
                int texels = (int) (stride / 16);
                StringBuilder sb = new StringBuilder();
                sb.append(vulkan ? "layout(set = " + set + ", binding = " + slot + ") uniform utextureBuffer " : (binding ? "layout(binding = " + slot + ") " : "") + "uniform usamplerBuffer ").append(name)
                        .append("_texels;\n\n");
                sb.append(type).append(' ').append(fetch).append("(int i) {\n");
                for (int t = 0; t < texels; t++) {
                    sb.append("    uvec4 t").append(t).append(" = texelFetch(").append(name).append("_texels, i * ").append(texels).append(t == 0 ? "" : " + " + t).append(");\n");
                }
                sb.append("    ").append(type).append(" r;\n");
                for (StructLayout.Field f : layout.fields()) {
                    sb.append("    r.").append(f.name()).append(" = ").append(decode(f.type(), (int) (f.offset() / 4))).append(";\n");
                }
                sb.append("    return r;\n}\n");
                return new StructArrayAccess(mode, name, struct, layout, stride, texels, null, binding, sb.toString(), vulkan);
            }
            case VERTEX_ATTRIBUTE: {
                requireScalarsAndVectors(struct, mode);
                if (slot < 0) {
                    throw new IllegalArgumentException("a vertex attribute needs a first location, not " + slot);
                }
                long stride = layout.size();
                VertexBufferLayout.Builder b = VertexBufferLayout.builder().perInstance();
                int location = slot;
                for (StructLayout.Field f : layout.fields()) {
                    b.attributeAt(name + "_" + f.name(), location++, formatOf(f.type()), (int) f.offset());
                }
                VertexBufferLayout vl = b.build((int) stride);
                StringBuilder sb = new StringBuilder(vl.glslInputs()).append('\n');
                sb.append("// the attributes hold the instance that is being drawn: the argument is not used\n");
                sb.append(type).append(' ').append(fetch).append("(int i) {\n    ").append(type).append(" r;\n");
                for (StructLayout.Field f : layout.fields()) {
                    sb.append("    r.").append(f.name()).append(" = ").append(name).append('_').append(f.name()).append(";\n");
                }
                sb.append("    return r;\n}\n");
                return new StructArrayAccess(mode, name, struct, layout, stride, 0, vl, false, sb.toString(), vulkan);
            }
            default:
                throw new AssertionError(mode);
        }
    }

    private static void requireScalarsAndVectors(Struct struct, Mode mode) {
        for (GlslType.Member m : struct.members()) {
            if (!(m.type() instanceof Scalar) && !(m.type() instanceof Vec)) {
                String what = m.type() instanceof Mat ? "a matrix" : m.type() instanceof Struct ? "a nested struct" : "an array";
                throw new UnsupportedOperationException("the member " + struct.name() + "." + m.name() + " is " + what + " (" + m.type().glsl() + "), which " + mode
                        + " cannot read: only scalars and vectors of float, int and uint");
            }
        }
    }

    private static final String COMPONENTS = "xyzw";

    private static String word(int w) {
        return "t" + w / 4 + "." + COMPONENTS.charAt(w % 4);
    }

    private static String decodeScalar(String glsl, int w) {
        return switch (glsl) {
            case "float" -> "uintBitsToFloat(" + word(w) + ")";
            case "int" -> "int(" + word(w) + ")";
            default -> word(w);
        };
    }

    private static String decode(GlslType type, int w) {
        if (type instanceof Scalar s) {
            return decodeScalar(s.glsl(), w);
        }
        Vec v = (Vec) type;
        StringBuilder sb = new StringBuilder(v.glsl()).append('(');
        for (int k = 0; k < v.length(); k++) {
            sb.append(k == 0 ? "" : ", ").append(decodeScalar(v.component().glsl(), w + k));
        }
        return sb.append(')').toString();
    }

    private static VertexFormat formatOf(GlslType type) {
        String base = type instanceof Scalar s ? s.glsl() : ((Vec) type).component().glsl();
        int n = type instanceof Vec v ? v.length() : 1;
        return switch (base) {
            case "float" -> new VertexFormat[] {VertexFormat.FLOAT32, VertexFormat.FLOAT32X2, VertexFormat.FLOAT32X3, VertexFormat.FLOAT32X4}[n - 1];
            case "int" -> new VertexFormat[] {VertexFormat.SINT32, VertexFormat.SINT32X2, VertexFormat.SINT32X3, VertexFormat.SINT32X4}[n - 1];
            default -> new VertexFormat[] {VertexFormat.UINT32, VertexFormat.UINT32X2, VertexFormat.UINT32X3, VertexFormat.UINT32X4}[n - 1];
        };
    }

    /**
     * Tells which graphics API the text of this access is written for: Vulkan GLSL carries set and
     * binding numbers on every block and sampler; OpenGL GLSL carries binding numbers from 4.20.
     *
     * @return {@link GraphicsCapabilities.Api#VULKAN} or {@link GraphicsCapabilities.Api#OPENGL}
     */
    public GraphicsCapabilities.Api api() {
        return vulkan ? GraphicsCapabilities.Api.VULKAN : GraphicsCapabilities.Api.OPENGL;
    }

    /**
     * Names the mode.
     *
     * @return the mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Names the array in the shader.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Names the function that reads an element.
     *
     * @return {@code fetch_<name>}
     */
    public String fetchFunction() {
        return "fetch_" + name;
    }

    /**
     * Gives the element type.
     *
     * @return the struct
     */
    public Struct struct() {
        return struct;
    }

    /**
     * Gives the layout in which the Java side writes the elements: {@code std140} for a uniform
     * block, {@code std430} otherwise.
     *
     * @return the layout of one element, with its offsets
     */
    public StructLayout layout() {
        return layout;
    }

    /**
     * Gives the distance between two elements in the buffer.
     *
     * @return the stride in bytes: the size of the struct, rounded up to 16 bytes for a texture
     *     buffer
     */
    public long elementStride() {
        return elementStride;
    }

    /**
     * Gives the size of the data of some elements.
     *
     * @param count the number of elements
     * @return {@code count} times the stride, in bytes
     */
    public long byteSize(int count) {
        return count * elementStride;
    }

    /**
     * Gives the number of {@code RGBA32UI} texels of one element in a texture buffer.
     *
     * @return the texels per element
     * @throws IllegalStateException if the mode is not {@link Mode#TEXTURE_BUFFER}
     */
    public int texelsPerElement() {
        if (mode != Mode.TEXTURE_BUFFER) {
            throw new IllegalStateException("only a texture buffer has texels: " + mode);
        }
        return texelsPerElement;
    }

    /**
     * Gives the vertex buffer layout of the elements, with a divisor of one per instance.
     *
     * @return the layout; its attributes are named {@code <name>_<member>}
     * @throws IllegalStateException if the mode is not {@link Mode#VERTEX_ATTRIBUTE}
     */
    public VertexBufferLayout vertexLayout() {
        if (mode != Mode.VERTEX_ATTRIBUTE) {
            throw new IllegalStateException("only vertex attributes have a vertex layout: " + mode);
        }
        return vertexLayout;
    }

    /**
     * Tells whether the text has a {@code layout(binding = ...)}: only from GLSL 4.20 on and when a
     * binding was given. Otherwise the caller sets the binding through the API.
     *
     * @return {@code true} if the binding is in the text
     */
    public boolean hasExplicitBinding() {
        return explicitBinding;
    }

    /**
     * Gives the GLSL text: the declaration and the function {@code fetch_<name>}. The struct has
     * to be declared before it, which {@link ShaderHeader.Builder#access} does.
     *
     * @return the text
     */
    public String glsl() {
        return glsl;
    }

    /**
     * Lists the members in the order of the struct, with their offsets in the element.
     *
     * @return the fields of the layout
     */
    public List<StructLayout.Field> fields() {
        return layout.fields();
    }
}
