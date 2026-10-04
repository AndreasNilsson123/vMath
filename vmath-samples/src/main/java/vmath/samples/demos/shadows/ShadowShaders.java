package vmath.samples.demos.shadows;

import vmath.gl.InstanceWriter;
import vmath.mesh.VertexLayout;

/**
 * The GLSL of the cascaded shadow demo: a depth-only vertex shader for the shadow maps and the
 * lit scene with a lookup in the cascade that the view depth selects.
 *
 * <p>Both vertex shaders read the transform of an object from one storage buffer (the layout is
 * the one that {@link InstanceWriter} documents: three rows of a {@code vec4} and a word of user
 * data, 64 bytes) through a list of object indices, which is what the culling produced for the pass.
 *
 * <p><b>The lookup.</b> The cascade is the first whose far distance is beyond the view depth
 * (the {@code w} of the clip position). The world position is pushed along the normal by a distance
 * of about a texel of that cascade (the normal offset), transformed by the cascade's
 * {@code textureMatrix()} and compared with the stored depth through a shadow sampler, 3 by 3
 * taps, each of which the hardware filters bilinearly. Positions outside the map count as lit.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
final class ShadowShaders {

    private ShadowShaders() {
    }

    private static String objectBlock() {
        return """
                layout(std430, binding = 0) readonly buffer Objects {
                    vec4 data[];
                };
                layout(std430, binding = 1) readonly buffer List {
                    uint list[];
                };
                """;
    }

    /**
     * Builds the vertex shader of the shadow pass.
     *
     * @param layout the vertex layout of the mesh; must not be {@code null}
     * @return the source text
     */
    static String shadowVertex(VertexLayout layout) {
        int vec4sPerInstance = (int) (InstanceWriter.STRIDE / 16);
        return "#version 450 core\n" + layout.toBufferLayout().glslInputs() + objectBlock() + """
                uniform mat4 lightViewProjection;
                void main() {
                    int base = int(list[gl_InstanceID]) * %d;
                    vec4 row0 = data[base], row1 = data[base + 1], row2 = data[base + 2];
                    vec4 p = vec4(position, 1.0);
                    gl_Position = lightViewProjection * vec4(dot(row0, p), dot(row1, p), dot(row2, p), 1.0);
                }
                """.formatted(vec4sPerInstance);
    }

    /**
     * Builds the fragment shader of the shadow pass, which writes nothing but the depth.
     *
     * @return the source text
     */
    static String shadowFragment() {
        return """
                #version 450 core
                void main() {
                }
                """;
    }

    /**
     * Builds the vertex shader of the scene.
     *
     * @param layout the vertex layout of the mesh; must not be {@code null}
     * @return the source text
     */
    static String sceneVertex(VertexLayout layout) {
        int vec4sPerInstance = (int) (InstanceWriter.STRIDE / 16);
        return "#version 450 core\n" + layout.toBufferLayout().glslInputs() + objectBlock() + """
                uniform mat4 viewProjection;
                uniform uint groundId;
                out vec3 vWorld;
                out vec3 vNormal;
                out vec3 vColor;
                out float vDepth;

                uint hash(uint x) {
                    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                    return x;
                }

                void main() {
                    int base = int(list[gl_InstanceID]) * %d;
                    vec4 row0 = data[base], row1 = data[base + 1], row2 = data[base + 2];
                    uint id = floatBitsToUint(data[base + 3].x);
                    vec4 p = vec4(position, 1.0);
                    vec3 world = vec3(dot(row0, p), dot(row1, p), dot(row2, p));
                    vec3 scale = vec3(row0.x, row1.y, row2.z);
                    vWorld = world;
                    vNormal = normalize(normal / scale);
                    uint h = hash(id);
                    vec3 tint = vec3(float(h & 255u), float((h >> 8) & 255u), float((h >> 16) & 255u)) / 255.0;
                    vColor = id == groundId ? vec3(0.42, 0.45, 0.40) : mix(vec3(0.72, 0.70, 0.66), tint, 0.25);
                    gl_Position = viewProjection * vec4(world, 1.0);
                    vDepth = gl_Position.w;
                }
                """.formatted(vec4sPerInstance);
    }

    /**
     * Builds the fragment shader of the scene.
     *
     * @return the source text
     */
    static String sceneFragment() {
        return """
                #version 450 core
                in vec3 vWorld;
                in vec3 vNormal;
                in vec3 vColor;
                in float vDepth;
                layout(binding = 0) uniform sampler2DArrayShadow shadowMap;
                uniform mat4 shadowMatrix[8];
                uniform float cascadeFar[8];
                uniform float normalOffset[8];
                uniform int cascadeCount;
                uniform float texel;
                uniform vec3 toLight;
                uniform int tintCascades;
                out vec4 color;
                void main() {
                    vec3 n = normalize(vNormal);
                    int c = cascadeCount - 1;
                    for (int i = 0; i < cascadeCount; i++) {
                        if (vDepth < cascadeFar[i]) { c = i; break; }
                    }
                    float diffuse = max(dot(n, toLight), 0.0);
                    float lit = 1.0;
                    if (diffuse > 0.0) {
                        vec3 wp = vWorld + n * normalOffset[c];
                        vec4 sc = shadowMatrix[c] * vec4(wp, 1.0);
                        if (sc.x >= 0.0 && sc.x <= 1.0 && sc.y >= 0.0 && sc.y <= 1.0 && sc.z <= 1.0) {
                            float sum = 0.0;
                            for (int y = -1; y <= 1; y++) {
                                for (int x = -1; x <= 1; x++) {
                                    sum += texture(shadowMap, vec4(sc.xy + vec2(x, y) * texel, float(c), sc.z - 0.0005));
                                }
                            }
                            lit = sum / 9.0;
                        }
                    }
                    vec3 albedo = vColor;
                    if (tintCascades == 1) {
                        vec3 tints[8] = vec3[8](vec3(1.0, 0.45, 0.45), vec3(0.5, 1.0, 0.5), vec3(0.5, 0.6, 1.0), vec3(1.0, 1.0, 0.45), vec3(1.0, 0.5, 1.0), vec3(0.45, 1.0, 1.0), vec3(1.0, 0.75, 0.4), vec3(0.8, 0.8, 0.8));
                        albedo = mix(albedo, albedo * tints[c], 0.6);
                    }
                    vec3 sky = vec3(0.50, 0.62, 0.80), sun = vec3(1.0, 0.93, 0.80);
                    vec3 ambient = mix(vec3(0.22, 0.21, 0.20), sky * 0.5, 0.5 + 0.5 * n.y);
                    vec3 c3 = albedo * (ambient + sun * 1.05 * diffuse * lit);
                    float fog = 1.0 - exp(-vDepth * 0.0016);
                    color = vec4(mix(c3, vec3(0.62, 0.74, 0.90), fog), 1.0);
                }
                """;
    }
}
