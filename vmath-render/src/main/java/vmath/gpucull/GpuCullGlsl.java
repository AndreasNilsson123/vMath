package vmath.gpucull;

import java.util.List;
import vmath.annotations.Experimental;
import vmath.gl.GlslFeature;
import vmath.gl.GlslVersion;
import vmath.gl.DrawElementsIndirectGpu;

/**
 * GLSL for the compute shaders that do what {@link GpuCullReference} and
 * {@link ClusterCullReference} do: one invocation per object (or cluster), the frustum test, the
 * Hi-Z test and an append to an indirect draw list.
 *
 * <p>The struct declarations come from the generated {@code GLSL} constants of the layouts, so
 * offsets cannot drift from the Java side.
 *
 * <p><b>Where it has been run.</b> {@code ShaderCompileTest} compiles the text with glslang at every
 * version from 4.30 (and refuses it below), the other tests check that it is complete and consistent with the
 * layouts, and the demos {@code gpu-culling}, {@code cluster-lod} and {@code clustered-lights} ran both shaders on one
 * NVIDIA GPU under OpenGL 4.5 and compared them with the CPU references (the instance lists as sets per draw, the
 * cluster commands as sets); other vendors, operating systems and Vulkan have not run them.
 *
 * <p>The Hi-Z pyramid is an {@code R32F} {@code sampler2D} with all its mip levels at texture unit
 * 0, each texel the farthest depth of the four below it ({@code max} for conventional depth,
 * {@code min} for reversed-Z); the shader converts every texel to "farness" (larger is farther)
 * before comparing, which is the same test in every convention. <b>Three contracts</b> of the pyramid
 * as a GPU texture are stated in {@link vmath.occlusion.HiZ}: its base must be a power of two on
 * OpenGL ({@code HiZ.baseSize} and {@code HiZ.resampleFarthest} make it), its texels hold normalised device depth
 * ({@code 2 d - 1} for the range -1 to 1), and the passes that build it need {@code GL_TEXTURE_FETCH_BARRIER_BIT}
 * before this shader and {@code GL_TEXTURE_UPDATE_BARRIER_BIT} before a read-back.
 *
 * <p><b>Thread safety.</b> Stateless: every method may be called from any number of threads at the
 * same time. The arrays and buffers you pass in are not synchronised, so two threads must not write
 * the same one.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * String shader = GpuCullGlsl.computeShader(64);                                            // compute shader text for 64 threads per group
 * String clusters = GpuCullGlsl.clusterShader(64);
 * }</pre>
 */
@Experimental("the shader text may change with the layouts")
public final class GpuCullGlsl {

    private GpuCullGlsl() {
    }

    private static void checkGroup(int workGroupSize) {
        if (workGroupSize < 1 || Integer.bitCount(workGroupSize) != 1) {
            throw new IllegalArgumentException("the work group size must be a power of two: " + workGroupSize);
        }
    }

    /**
     * The Hi-Z test shared by both shaders; it reads the fields that {@code CullView} and
     * {@code ClusterCullView} have in common through the block named {@code view}.
     */
    private static final String HIZ = """
            layout(binding = 0) uniform sampler2D hzb;

            const uint VIEW_Y_DOWN = 1u;

            // larger is farther in every depth convention (depthMode: 0 is -1..1, 1 is 0..1, 2 is reversed-Z)
            float farness(float depth) {
                return view.depthMode == 0u ? (depth + 1.0) * 0.5 : (view.depthMode == 1u ? depth : 1.0 - depth);
            }

            bool hiddenBox(vec3 boxMin, vec3 boxMax) {
                vec2 lo = vec2(1e30), hi = vec2(-1e30);
                float nearest = 1e30;
                for (int c = 0; c < 8; ++c) {
                    vec3 corner = vec3((c & 1) == 0 ? boxMin.x : boxMax.x, (c & 2) == 0 ? boxMin.y : boxMax.y, (c & 4) == 0 ? boxMin.z : boxMax.z);
                    vec4 clip = view.viewProjection * vec4(corner, 1.0);
                    if (!(clip.w > view.nearW)) {
                        return false;   // crosses the camera plane or is behind it: not testable, so visible
                    }
                    vec3 ndc = clip.xyz / clip.w;
                    lo = min(lo, ndc.xy);
                    hi = max(hi, ndc.xy);
                    nearest = min(nearest, farness(ndc.z));
                }
                if (hi.x < -1.0 || lo.x > 1.0 || hi.y < -1.0 || lo.y > 1.0) {
                    return false;       // wholly off screen: the frustum test decides
                }
                lo = max(lo, vec2(-1.0));
                hi = min(hi, vec2(1.0));
                vec2 size0 = vec2(float(view.hzbWidth), float(view.hzbHeight));
                vec2 pMin = vec2(lo.x * 0.5 + 0.5, (view.flags & VIEW_Y_DOWN) != 0u ? 0.5 - hi.y * 0.5 : lo.y * 0.5 + 0.5) * size0;
                vec2 pMax = vec2(hi.x * 0.5 + 0.5, (view.flags & VIEW_Y_DOWN) != 0u ? 0.5 - lo.y * 0.5 : hi.y * 0.5 + 0.5) * size0;
                float extent = max(pMax.x - pMin.x, pMax.y - pMin.y);
                int level = 0;
                while (level + 1 < int(view.hzbLevels) && float(1 << level) < extent) {
                    ++level;
                }
                ivec2 size = ivec2(max(1u, (view.hzbWidth + (1u << level) - 1u) >> level), max(1u, (view.hzbHeight + (1u << level) - 1u) >> level));
                float scale = 1.0 / float(1 << level);
                ivec2 t0 = clamp(ivec2(floor(pMin * scale)), ivec2(0), size - 1);
                ivec2 t1 = clamp(ivec2(floor(pMax * scale)), ivec2(0), size - 1);
                float far = -1e30;
                for (int ty = t0.y; ty <= t1.y; ++ty) {
                    for (int tx = t0.x; tx <= t1.x; ++tx) {
                        far = max(far, farness(texelFetch(hzb, ivec2(tx, ty), level).r));
                    }
                }
                return nearest > far;
            }
            """;

    /** The version of the text that the methods without a version parameter produce. */
    public static final GlslVersion DEFAULT_VERSION = GlslVersion.V450;

    /** The constructs of both shaders: they are all at or below 4.30, which is the lowest version the text can be written for. */
    private static final List<GlslFeature> NEEDED = List.of(GlslFeature.COMPUTE_SHADER, GlslFeature.STORAGE_BLOCK, GlslFeature.STD430_LAYOUT, GlslFeature.MEMORY_QUALIFIERS,
            GlslFeature.ATOMIC_BUFFER_FUNCTIONS, GlslFeature.EXPLICIT_BINDING, GlslFeature.UNIFORM_BLOCK, GlslFeature.STD140_LAYOUT);

    private static void requireVersion(GlslVersion version, String what) {
        if (!version.supports(GlslFeature.COMPUTE_SHADER)) {
            throw new UnsupportedOperationException("the " + what + " is a compute shader, which needs " + GlslFeature.COMPUTE_SHADER.minimum() + " or later, and this is " + version
                    + ": on a context without compute shaders cull on the CPU with GpuCullReference or the kernels of vmath.spatial (CullBackend.CPU)");
        }
        for (GlslFeature f : NEEDED) {
            version.require(f);
        }
    }

    private static String withVersion(String text, GlslVersion version) {
        return version.equals(DEFAULT_VERSION) ? text : "#version " + version.number() + text.substring("#version 450".length());
    }

    /**
     * Generates the GLSL source of the object culling compute shader for a work group size, as for
     * {@link #computeShader(int, GlslVersion)} with {@link #DEFAULT_VERSION} ({@code #version 450}); this
     * text is pinned byte for byte by a golden test.
     *
     * @param workGroupSize the work group size (a power of two, typically 64)
     * @return the object culling compute shader source
     * @throws IllegalArgumentException if the size is not a power of two in the supported range
     */
    public static String computeShader(int workGroupSize) {
        return computeShaderText(workGroupSize);
    }

    /**
     * Generates the GLSL source of the object culling compute shader for a work group size and a GLSL
     * version, which the caller compiles with its graphics API.
     *
     * <p>The constructs in the shader (a compute shader, {@code std430} storage blocks with memory
     * qualifiers, atomic functions on buffer variables, explicit binding points) are those of GLSL 4.30
     * and 4.20, and the compile matrix ({@code ShaderCompileTest}) confirms that the text is accepted from
     * 4.30 on, so that is the lowest version it can be written for; a context with an older GLSL has no
     * compute shaders and culls on the CPU. The text for a version is the text of the default version with
     * the first line changed.
     *
     * @param workGroupSize the work group size (a power of two, typically 64)
     * @param version the GLSL version of the context; must not be {@code null}
     * @return the shader source, starting with {@code #version} and the number of the version
     * @throws IllegalArgumentException if the size is not a power of two in the supported range
     * @throws UnsupportedOperationException if the version has no compute shaders (below 4.30); the message names the CPU path
     */
    public static String computeShader(int workGroupSize, GlslVersion version) {
        requireVersion(version, "object culling shader");
        return withVersion(computeShaderText(workGroupSize), version);
    }

    /**
     * Generates the GLSL source of the cluster culling compute shader for a work group size, as for
     * {@link #clusterShader(int, GlslVersion)} with {@link #DEFAULT_VERSION} ({@code #version 450}); this
     * text is pinned byte for byte by a golden test.
     *
     * @param workGroupSize the work group size (a power of two, typically 64)
     * @return the cluster culling compute shader source
     * @throws IllegalArgumentException if the size is not a power of two in the supported range
     */
    public static String clusterShader(int workGroupSize) {
        return clusterShaderText(workGroupSize);
    }

    /**
     * Generates the GLSL source of the cluster culling compute shader for a work group size and a GLSL
     * version: level of detail, frustum, cone, Hi-Z, one indirect command per surviving cluster. The
     * constructs, the lowest version (4.30) and the refusal below it are those of
     * {@link #computeShader(int, GlslVersion)}.
     *
     * @param workGroupSize the work group size (a power of two, typically 64)
     * @param version the GLSL version of the context; must not be {@code null}
     * @return the shader source, starting with {@code #version} and the number of the version
     * @throws IllegalArgumentException if the size is not a power of two in the supported range
     * @throws UnsupportedOperationException if the version has no compute shaders (below 4.30); the message names the CPU path
     */
    public static String clusterShader(int workGroupSize, GlslVersion version) {
        requireVersion(version, "cluster culling shader");
        return withVersion(clusterShaderText(workGroupSize), version);
    }

    private static String computeShaderText(int workGroupSize) {
        checkGroup(workGroupSize);
        return """
                #version 450
                layout(local_size_x = %d) in;

                %s
                %s
                %s

                layout(std140, binding = 0) uniform ViewBlock { CullView view; };
                layout(std430, binding = 1) readonly buffer Objects { CullObject objects[]; };
                layout(std430, binding = 2) buffer Commands { DrawElementsIndirect commands[]; };
                layout(std430, binding = 3) writeonly buffer Visible { uint visible[]; };
                layout(std430, binding = 4) readonly buffer Capacity { uint capacity[]; };
                layout(std430, binding = 5) buffer Overflow { uint overflowCount; };

                %s

                const uint OBJECT_NO_OCCLUSION = 1u;

                bool inFrustum(CullObject o) {
                    for (int p = 0; p < 6; ++p) {
                        vec4 plane = view.planes[p];
                        vec3 positive = mix(o.min, o.max, greaterThanEqual(plane.xyz, vec3(0.0)));
                        if (dot(plane.xyz, positive) + plane.w < 0.0) {
                            return false;
                        }
                    }
                    return true;
                }

                void main() {
                    uint i = gl_GlobalInvocationID.x;
                    if (i >= view.objectCount) {
                        return;
                    }
                    CullObject o = objects[i];
                    if (!inFrustum(o)) {
                        return;
                    }
                    if ((o.flags & OBJECT_NO_OCCLUSION) == 0u && hiddenBox(o.min, o.max)) {
                        return;
                    }
                    uint slot = atomicAdd(commands[o.drawIndex].instanceCount, 1u);
                    if (slot < capacity[o.drawIndex]) {
                        visible[commands[o.drawIndex].baseInstance + slot] = i;
                    } else {
                        atomicAdd(overflowCount, 1u);
                        atomicMin(commands[o.drawIndex].instanceCount, capacity[o.drawIndex]);
                    }
                }
                """.formatted(workGroupSize, CullObjectGpu.GLSL, CullViewGpu.GLSL, DrawElementsIndirectGpu.GLSL, HIZ);
    }

    private static String clusterShaderText(int workGroupSize) {
        checkGroup(workGroupSize);
        return """
                #version 450
                layout(local_size_x = %d) in;

                %s
                %s
                %s

                layout(std140, binding = 0) uniform ViewBlock { ClusterCullView view; };
                layout(std430, binding = 1) readonly buffer Clusters { ClusterCullObject clusters[]; };
                layout(std430, binding = 2) writeonly buffer Commands { DrawElementsIndirect commands[]; };
                layout(std430, binding = 3) buffer Counters { uint drawCount; uint overflowCount; };

                %s

                float projected(float error, vec4 sphere) {
                    if (isinf(error)) {
                        return error;
                    }
                    float d = max(distance(sphere.xyz, view.eyePixelScale.xyz) - sphere.w, 1e-4);
                    return error * view.eyePixelScale.w / d;
                }

                bool sphereInFrustum(vec4 sphere) {
                    for (int p = 0; p < 6; ++p) {
                        vec4 plane = view.planes[p];
                        if (dot(plane.xyz, sphere.xyz) + plane.w < -sphere.w) {
                            return false;
                        }
                    }
                    return true;
                }

                // the conservative cone test of ConeCull.backfacing
                bool backFacing(vec4 sphere, vec4 cone) {
                    if (cone.w >= 1.0) {
                        return false;
                    }
                    vec3 toCenter = sphere.xyz - view.eyePixelScale.xyz;
                    return dot(toCenter, cone.xyz) >= cone.w * length(toCenter) + sphere.w;
                }

                void main() {
                    uint i = gl_GlobalInvocationID.x;
                    if (i >= view.clusterCount) {
                        return;
                    }
                    ClusterCullObject c = clusters[i];
                    float own = projected(c.lodError, c.lodSphere), up = projected(c.parentError, c.parentSphere);
                    if (!(own <= view.pixelBudget && up > view.pixelBudget)) {
                        return;
                    }
                    if (!sphereInFrustum(c.sphere) || backFacing(c.sphere, c.cone)) {
                        return;
                    }
                    if (hiddenBox(c.sphere.xyz - vec3(c.sphere.w), c.sphere.xyz + vec3(c.sphere.w))) {
                        return;
                    }
                    uint slot = atomicAdd(drawCount, 1u);
                    if (slot < uint(commands.length())) {
                        commands[slot] = DrawElementsIndirect(c.indexCount, 1u, c.firstIndex, 0, i);
                    } else {
                        atomicAdd(overflowCount, 1u);
                    }
                }
                """.formatted(workGroupSize, ClusterCullObjectGpu.GLSL, ClusterCullViewGpu.GLSL, DrawElementsIndirectGpu.GLSL, HIZ);
    }
}
