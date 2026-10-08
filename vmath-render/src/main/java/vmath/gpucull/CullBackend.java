package vmath.gpucull;

import vmath.annotations.Experimental;
import vmath.gl.GlslVersion;
import vmath.gl.GraphicsCapabilities;
import vmath.gl.GraphicsCapabilities.Feature;
import vmath.gl.StrategyChooser;

/**
 * Where the culling of a frame runs: on the GPU in the compute shaders of {@link GpuCullGlsl}, or
 * on the CPU.
 *
 * <p>Both produce the same thing, the objects that survive grouped by draw: the compute path
 * writes the instance counts of the indirect commands and the list of surviving objects in a
 * buffer, the CPU path culls with the SIMD, parallel or tree kernels of {@code vmath.spatial}
 * and groups the survivors with {@link SurvivorBatcher} into a {@link vmath.gl.DrawList} that
 * {@link vmath.gl.DrawSubmission} then hands to the driver in the best way the capabilities allow.
 * {@link #choose} picks the first when the driver can run it.
 *
 * <p>The compute path needs GLSL 4.30, the lowest version that the text of {@link GpuCullGlsl} can be
 * written for (the compile matrix of GPU-12 confirms that glslang accepts it from there and refuses it
 * below). Write the shaders for the version of the context with {@code GpuCullGlsl.computeShader(group,
 * caps.glsl())}; the methods without a version write {@code #version 450}.
 *
 * <p><b>Thread safety.</b> The constants are immutable and the methods stateless: safe to call
 * from any number of threads.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * CullBackend backend = CullBackend.choose(GraphicsCapabilities.openGl(3, 3, List.of()));   // CPU
 * CullBackend modern = CullBackend.choose(GraphicsCapabilities.openGl(4, 6, List.of()));    // COMPUTE
 * }</pre>
 */
@Experimental("the strategies may change")
public enum CullBackend {
    /** The compute shaders of {@link GpuCullGlsl}; the commands are written on the GPU. */
    COMPUTE,
    /** The kernels of {@code vmath.spatial} and {@link SurvivorBatcher}; works everywhere. */
    CPU;

    private static final StrategyChooser<CullBackend> CHOOSER = StrategyChooser.<CullBackend>builder("culling")
            .optionGlsl(COMPUTE, GlslVersion.V430, "the compute shaders of GpuCullGlsl write the indirect commands",
                    Feature.COMPUTE_SHADERS, Feature.STORAGE_BUFFERS, Feature.DRAW_INDIRECT, Feature.BASE_INSTANCE)
            .option(CPU, "SIMD, parallel or tree kernels, then SurvivorBatcher and DrawList")
            .build();

    /**
     * Gives the table that decides.
     *
     * @return the chooser, whose {@code markdownTable()} the documentation embeds
     */
    public static StrategyChooser<CullBackend> chooser() {
        return CHOOSER;
    }

    /**
     * Chooses the best backend that the capabilities allow.
     *
     * @param caps the capabilities; must not be {@code null}
     * @return {@link #COMPUTE} if the driver can run the shaders, else {@link #CPU}
     */
    public static CullBackend choose(GraphicsCapabilities caps) {
        return CHOOSER.choose(caps);
    }

    /**
     * Checks that a named backend is possible and returns it: how to ask for the CPU one on
     * purpose.
     *
     * @param wanted the backend; must not be {@code null}
     * @param caps the capabilities; must not be {@code null}
     * @return {@code wanted}
     * @throws UnsupportedOperationException if the capabilities do not allow it; the message says
     *     what is missing
     */
    public static CullBackend force(CullBackend wanted, GraphicsCapabilities caps) {
        return CHOOSER.force(wanted, caps);
    }
}
