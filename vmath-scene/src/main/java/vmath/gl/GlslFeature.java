package vmath.gl;

import vmath.annotations.Experimental;

/**
 * A construct of the GLSL language that some of the generators of the library emit, with the lowest
 * desktop version that has it: the single table (rule 4 of the GLSL-version tasks) that every gate
 * reads, through {@link GlslVersion#supports} and {@link GlslVersion#require}.
 *
 * <p>The numbers were taken from the GLSL specifications and then <b>checked with glslang 16.6.0</b> (the
 * compile matrix of roadmap item GPU-12, {@code GlslFeatureCompileTest}): a probe shader of each feature is
 * accepted from exactly the version given here and refused below it. Where the compiler disagreed with the
 * specifications as remembered (the boolean selector of {@code mix} is in GLSL 3.30, not 4.50) the table
 * follows the compiler. The features that already exist at the floor of 3.30 are listed too, so that
 * a generator states what it needs and the table says it is free there.
 *
 * <p><b>Thread safety.</b> The constants are immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GlslFeature f = GlslFeature.STORAGE_BLOCK;
 * GlslVersion lowest = f.minimum();                                // 430
 * boolean ok = GlslVersion.V420.supports(f);                       // false
 * }</pre>
 */
@Experimental("the set of features and the numbers may change when the compile matrix has run")
public enum GlslFeature {
    /** Uniform blocks ({@code uniform Name { ... }}). */
    UNIFORM_BLOCK(GlslVersion.V330, GlslEsVersion.V300, "a uniform block", "vert", "uniform B { vec4 a; } b;\nvoid main() { gl_Position = b.a; }\n"),
    /** The {@code std140} block layout. */
    STD140_LAYOUT(GlslVersion.V330, GlslEsVersion.V300, "the std140 layout", "vert", "layout(std140) uniform B { vec4 a; } b;\nvoid main() { gl_Position = b.a; }\n"),
    /** {@code layout(location = n)} on vertex shader inputs. */
    EXPLICIT_ATTRIBUTE_LOCATION(GlslVersion.V330, GlslEsVersion.V300, "an explicit vertex attribute location", "vert", "layout(location = 0) in vec4 p;\nvoid main() { gl_Position = p; }\n"),
    /** Texture buffers: {@code usamplerBuffer} read with {@code texelFetch}. */
    TEXTURE_BUFFER(GlslVersion.V330, GlslEsVersion.V320, "a texture buffer sampler", "vert", "uniform highp usamplerBuffer t;\nvoid main() { gl_Position = vec4(texelFetch(t, 0)); }\n"),
    /** {@code uintBitsToFloat} and the other bit casts. */
    BIT_CASTS(GlslVersion.V330, GlslEsVersion.V300, "bit casts between float and integers", "vert", "uniform uint u;\nvoid main() { gl_Position = vec4(uintBitsToFloat(u)); }\n"),
    /** The double-precision types {@code double}, {@code dvec3}, {@code dmat4}, ... */
    DOUBLE_TYPES(GlslVersion.V400, null, "double-precision types", "vert", "uniform double d;\nvoid main() { gl_Position = vec4(float(d)); }\n"),
    /** {@code layout(binding = n)} on uniform blocks and samplers. */
    EXPLICIT_BINDING(GlslVersion.V420, GlslEsVersion.V310, "an explicit binding point", "vert", "layout(std140, binding = 1) uniform B { vec4 a; } b;\nvoid main() { gl_Position = b.a; }\n"),
    /** The memory qualifiers {@code coherent}, {@code volatile}, {@code restrict}, {@code readonly}, {@code writeonly}. */
    MEMORY_QUALIFIERS(GlslVersion.V420, GlslEsVersion.V310, "memory qualifiers", "vert", "layout(rgba8) uniform readonly highp image2D img;\nvoid main() { gl_Position = imageLoad(img, ivec2(0)); }\n"),
    /** The {@code std430} block layout. */
    STD430_LAYOUT(GlslVersion.V430, GlslEsVersion.V310, "the std430 layout", "vert", "layout(std430) buffer B { vec4 a; } b;\nvoid main() { gl_Position = b.a; }\n"),
    /** Shader storage blocks ({@code buffer Name { ... }}), including an unsized array at the end. */
    STORAGE_BLOCK(GlslVersion.V430, GlslEsVersion.V310, "a shader storage block", "vert", "buffer B { vec4 a[]; } b;\nvoid main() { gl_Position = b.a[0]; }\n"),
    /** Compute shaders ({@code layout(local_size_x = n) in;}, {@code gl_GlobalInvocationID}). */
    COMPUTE_SHADER(GlslVersion.V430, GlslEsVersion.V310, "a compute shader", "comp", "layout(local_size_x = 64) in;\nvoid main() { }\n"),
    /** {@code atomicAdd}, {@code atomicMin} and the other atomic functions on a variable of a storage block. */
    ATOMIC_BUFFER_FUNCTIONS(GlslVersion.V430, GlslEsVersion.V310, "an atomic function on a buffer variable", "vert", "buffer B { uint c; } b;\nvoid main() { atomicAdd(b.c, 1u); gl_Position = vec4(0.0); }\n"),
    /** {@code mix(genType, genType, genBType)}: a boolean vector as the selector. */
    MIX_WITH_BOOLEAN_SELECTOR(GlslVersion.V330, GlslEsVersion.V300, "mix with a boolean selector", "vert", "void main() { vec3 a = mix(vec3(1.0), vec3(2.0), bvec3(true)); gl_Position = vec4(a, 1.0); }\n"),
    /** {@code gl_DrawID} as a core built-in (before it needs the extension {@code GL_ARB_shader_draw_parameters}). */
    BUILTIN_DRAW_ID(GlslVersion.V460, null, "gl_DrawID as a core built-in", "vert", "void main() { gl_Position = vec4(float(gl_DrawID)); }\n");

    private final GlslVersion minimum;
    private final GlslEsVersion minimumEs;
    private final String description;
    private final String probeStage;
    private final String probeBody;

    GlslFeature(GlslVersion minimum, GlslEsVersion minimumEs, String description, String probeStage, String probeBody) {
        this.minimum = minimum;
        this.minimumEs = minimumEs;
        this.description = description;
        this.probeStage = probeStage;
        this.probeBody = probeBody;
    }

    /**
     * Gives the stage of the probe shader of this feature.
     *
     * @return {@code vert} or {@code comp}
     */
    public String probeStage() {
        return probeStage;
    }

    /**
     * Gives a minimal shader that uses the feature and nothing else above 3.30, without a
     * {@code #version} line: the compile tests and the check on a driver put the line of each version
     * in front of it and expect it to be accepted exactly from {@link #minimum()}.
     *
     * @return the text of the shader after the version line
     */
    public String probeBody() {
        return probeBody;
    }

    /**
     * Gives the lowest version that has the feature.
     *
     * @return the version
     */
    public GlslVersion minimum() {
        return minimum;
    }

    /**
     * Gives the lowest version of GLSL ES that has the feature.
     *
     * @return the version, or {@code null} if the feature does not exist in GLSL ES
     */
    public GlslEsVersion minimumEs() {
        return minimumEs;
    }

    /**
     * Describes the feature in words, as the messages of the generators name it.
     *
     * @return a short noun phrase
     */
    public String description() {
        return description;
    }
}
