package vmath.gl;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import vmath.annotations.Experimental;

/**
 * What a graphics API and driver can do, as far as the strategies of this library care: a plain
 * value that the caller builds from the version and the extensions it has queried.
 *
 * <p>The library never calls a graphics API. Everything that has a newer and an older way to be
 * done (submitting many draws, reading an array of structs in a shader, culling on the GPU or the
 * CPU) asks this value which way is possible, through a {@link StrategyChooser}.
 *
 * <p>The flags follow the specifications: a feature of core OpenGL is on from its version, and an
 * extension turns it on in an older context. The table is in the Javadoc of
 * {@link #openGl(int, int, Collection)}. It has been checked against the specifications in the
 * tests, not against a driver; a driver that advertises a feature and does not deliver it is
 * handled with {@link #without(Feature...)}.
 *
 * <p><b>Thread safety.</b> Immutable: safe to share between threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * GraphicsCapabilities caps = GraphicsCapabilities.openGl(4, 5, List.of());
 * boolean indirect = caps.has(GraphicsCapabilities.Feature.MULTI_DRAW_INDIRECT);   // true
 * GraphicsCapabilities older = caps.without(GraphicsCapabilities.Feature.MULTI_DRAW_INDIRECT);
 * DrawSubmission how = DrawSubmission.choose(older, false, false);                  // MULTI_DRAW_CLIENT
 * }</pre>
 */
@Experimental("the set of features may grow")
public final class GraphicsCapabilities {

    /**
     * The graphics API.
     */
    public enum Api {
        /** OpenGL, core profile. */
        OPENGL,
        /** Vulkan, with its optional features given as flags. */
        VULKAN
    }

    /**
     * One thing that a driver can or cannot do.
     */
    public enum Feature {
        /** {@code glMultiDrawArrays} and {@code glMultiDrawElements}, with the counts and offsets in client arrays (OpenGL 1.4; not a Vulkan call). */
        MULTI_DRAW,
        /** A base vertex added to the indices of a draw, as {@code glDrawElementsBaseVertex} (OpenGL 3.2; {@code vertexOffset} in Vulkan). */
        BASE_VERTEX,
        /** Instanced draws, as {@code glDrawArraysInstanced} (OpenGL 3.1). */
        INSTANCED_DRAWS,
        /** Vertex attributes that advance per instance, through an attribute divisor (OpenGL 3.3). */
        INSTANCED_ARRAYS,
        /** Uniform blocks (OpenGL 3.1). */
        UNIFORM_BLOCKS,
        /** Texture buffers, read with {@code texelFetch} (OpenGL 3.1). */
        TEXTURE_BUFFERS,
        /** Draws whose parameters are in a buffer (OpenGL 4.0, {@code ARB_draw_indirect}). */
        DRAW_INDIRECT,
        /** Many indirect draws in one call (OpenGL 4.3, {@code ARB_multi_draw_indirect}; the {@code multiDrawIndirect} feature of Vulkan). */
        MULTI_DRAW_INDIRECT,
        /** A first instance that is not zero, in direct and in indirect draws (OpenGL 4.2, {@code ARB_base_instance}; {@code drawIndirectFirstInstance} in Vulkan). */
        BASE_INSTANCE,
        /** The index of the draw in a multi-draw as {@code gl_DrawID} (OpenGL 4.6, {@code ARB_shader_draw_parameters}; {@code shaderDrawParameters} in Vulkan). */
        SHADER_DRAW_PARAMETERS,
        /** Shader storage blocks (OpenGL 4.3, {@code ARB_shader_storage_buffer_object}). */
        STORAGE_BUFFERS,
        /** Compute shaders (OpenGL 4.3, {@code ARB_compute_shader}). */
        COMPUTE_SHADERS,
        /** Buffers that stay mapped while the GPU uses them (OpenGL 4.4, {@code ARB_buffer_storage}). */
        PERSISTENT_MAPPING
    }

    private final Api api;
    private final int major;
    private final int minor;
    private final GlslVersion glsl;
    private final EnumSet<Feature> features;

    private GraphicsCapabilities(Api api, int major, int minor, GlslVersion glsl, EnumSet<Feature> features) {
        this.api = api;
        this.major = major;
        this.minor = minor;
        this.glsl = glsl;
        this.features = features;
    }

    /**
     * Derives the capabilities of an OpenGL context from its version and its extensions.
     *
     * <p>From the core version: instancing, uniform blocks and texture buffers (3.1, so always
     * here), the base vertex (3.2), the attribute divisor (3.3), {@code DRAW_INDIRECT} (4.0), the
     * base instance (4.2), multi-draw indirect, storage blocks and compute shaders (4.3),
     * persistent mapping (4.4) and the draw parameters (4.6). From an extension in an older
     * context: {@code ARB_draw_indirect}, {@code ARB_multi_draw_indirect} (which implies the
     * first), {@code ARB_base_instance}, {@code ARB_shader_draw_parameters},
     * {@code ARB_shader_storage_buffer_object}, {@code ARB_compute_shader} and
     * {@code ARB_buffer_storage}. The GLSL version is the one of the OpenGL version; an extension
     * does not raise it, a shader that uses one needs its {@code #extension} line.
     *
     * @param major the major version of the context, at least 3
     * @param minor the minor version of the context
     * @param extensions the extension strings, with or without the {@code GL_} prefix; must not
     *     be {@code null}
     * @return the capabilities
     * @throws IllegalArgumentException if the version is below 3.3
     */
    public static GraphicsCapabilities openGl(int major, int minor, Collection<String> extensions) {
        GlslVersion glsl = GlslVersion.ofOpenGl(major, minor);
        int v = major * 10 + minor;
        EnumSet<Feature> f = EnumSet.of(Feature.MULTI_DRAW, Feature.BASE_VERTEX, Feature.INSTANCED_DRAWS, Feature.INSTANCED_ARRAYS, Feature.UNIFORM_BLOCKS, Feature.TEXTURE_BUFFERS);
        boolean multiIndirect = v >= 43 || has(extensions, "ARB_multi_draw_indirect");
        if (v >= 40 || multiIndirect || has(extensions, "ARB_draw_indirect")) {
            f.add(Feature.DRAW_INDIRECT);
        }
        if (multiIndirect) {
            f.add(Feature.MULTI_DRAW_INDIRECT);
        }
        if (v >= 42 || has(extensions, "ARB_base_instance")) {
            f.add(Feature.BASE_INSTANCE);
        }
        if (v >= 46 || has(extensions, "ARB_shader_draw_parameters")) {
            f.add(Feature.SHADER_DRAW_PARAMETERS);
        }
        if (v >= 43 || has(extensions, "ARB_shader_storage_buffer_object")) {
            f.add(Feature.STORAGE_BUFFERS);
        }
        if (v >= 43 || has(extensions, "ARB_compute_shader")) {
            f.add(Feature.COMPUTE_SHADERS);
        }
        if (v >= 44 || has(extensions, "ARB_buffer_storage")) {
            f.add(Feature.PERSISTENT_MAPPING);
        }
        return new GraphicsCapabilities(Api.OPENGL, major, minor, glsl, f);
    }

    private static boolean has(Collection<String> extensions, String name) {
        return extensions.contains(name) || extensions.contains("GL_" + name);
    }

    /**
     * Describes a Vulkan device: what Vulkan 1.0 always has, and the three optional features that
     * matter here.
     *
     * <p>Always on: instancing, uniform and storage buffers, texel buffers, compute, indirect
     * draws and a mapping that persists. Off: {@link Feature#MULTI_DRAW} (there is no such call).
     * The GLSL version is 4.50, the one that GLSL for Vulkan is written in.
     *
     * @param multiDrawIndirect the {@code multiDrawIndirect} device feature
     * @param drawIndirectFirstInstance the {@code drawIndirectFirstInstance} device feature
     * @param shaderDrawParameters the {@code shaderDrawParameters} feature (core in Vulkan 1.1)
     * @return the capabilities
     */
    public static GraphicsCapabilities vulkan(boolean multiDrawIndirect, boolean drawIndirectFirstInstance, boolean shaderDrawParameters) {
        EnumSet<Feature> f = EnumSet.of(Feature.BASE_VERTEX, Feature.INSTANCED_DRAWS, Feature.INSTANCED_ARRAYS, Feature.UNIFORM_BLOCKS, Feature.TEXTURE_BUFFERS, Feature.DRAW_INDIRECT,
                Feature.STORAGE_BUFFERS, Feature.COMPUTE_SHADERS, Feature.PERSISTENT_MAPPING);
        if (multiDrawIndirect) {
            f.add(Feature.MULTI_DRAW_INDIRECT);
        }
        if (drawIndirectFirstInstance) {
            f.add(Feature.BASE_INSTANCE);
        }
        if (shaderDrawParameters) {
            f.add(Feature.SHADER_DRAW_PARAMETERS);
        }
        return new GraphicsCapabilities(Api.VULKAN, 1, 0, GlslVersion.V450, f);
    }

    /**
     * Describes the floor: OpenGL 3.3 with no extension, which every strategy of the library
     * works on.
     *
     * @return the capabilities of OpenGL 3.3
     */
    public static GraphicsCapabilities baseline() {
        return openGl(3, 3, Set.of());
    }

    /**
     * Names the API.
     *
     * @return the API
     */
    public Api api() {
        return api;
    }

    /**
     * Gives the major version of the API (1 for Vulkan 1.x).
     *
     * @return the major version
     */
    public int major() {
        return major;
    }

    /**
     * Gives the minor version of the API.
     *
     * @return the minor version
     */
    public int minor() {
        return minor;
    }

    /**
     * Gives the GLSL version that shaders for this context are written in.
     *
     * @return the GLSL version
     */
    public GlslVersion glsl() {
        return glsl;
    }

    /**
     * Tells whether a feature is available.
     *
     * @param feature the feature; must not be {@code null}
     * @return {@code true} if the context has it
     */
    public boolean has(Feature feature) {
        return features.contains(feature);
    }

    /**
     * Lists the features that are available.
     *
     * @return a copy of the set of features
     */
    public Set<Feature> features() {
        return EnumSet.copyOf(features);
    }

    /**
     * Lists which of some features are missing.
     *
     * @param needed the features that are needed; must not be {@code null}
     * @return the ones that this context does not have, in the order of {@link Feature}
     */
    public Set<Feature> missing(Collection<Feature> needed) {
        EnumSet<Feature> out = EnumSet.noneOf(Feature.class);
        for (Feature f : needed) {
            if (!features.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Takes features away: to test a lower strategy, to save a feature, or to work around a driver
     * that does not deliver what it advertises.
     *
     * <p>A feature that others depend on takes them with it: without {@link
     * Feature#DRAW_INDIRECT} there is no {@link Feature#MULTI_DRAW_INDIRECT} either.
     *
     * @param removed the features to remove
     * @return capabilities without them
     */
    public GraphicsCapabilities without(Feature... removed) {
        EnumSet<Feature> f = EnumSet.copyOf(features);
        for (Feature r : removed) {
            f.remove(r);
        }
        if (!f.contains(Feature.DRAW_INDIRECT)) {
            f.remove(Feature.MULTI_DRAW_INDIRECT);
        }
        return new GraphicsCapabilities(api, major, minor, glsl, f);
    }

    /**
     * Lowers the GLSL version, for example to see what a strategy generates for 3.30 while the
     * context is newer.
     *
     * @param lower the version to use; must not be newer than the current one
     * @return capabilities with the lower GLSL version and the same features
     * @throws IllegalArgumentException if {@code lower} is newer than the current version
     */
    public GraphicsCapabilities withGlsl(GlslVersion lower) {
        if (!glsl.atLeast(lower)) {
            throw new IllegalArgumentException(lower + " is newer than the " + glsl + " of this context");
        }
        return new GraphicsCapabilities(api, major, minor, lower, features);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GraphicsCapabilities c && api == c.api && major == c.major && minor == c.minor && glsl.equals(c.glsl) && features.equals(c.features);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(api, major, minor, glsl, features);
    }

    @Override
    public String toString() {
        return api + " " + major + "." + minor + ", " + glsl + ", " + features;
    }
}
