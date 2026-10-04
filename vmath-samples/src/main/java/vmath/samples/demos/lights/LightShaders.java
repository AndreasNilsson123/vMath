package vmath.samples.demos.lights;

import vmath.lighting.ClusterGrid;
import vmath.gl.InstanceWriter;
import vmath.mesh.VertexLayout;

/**
 * The GLSL of the clustered-lights demo: scaled boxes from the instance buffer, shaded by point
 * lights that the fragment shader finds in one of three ways: from the cluster of the fragment
 * (the lookup text is the library's {@code ClusterGrid.glslLookup}), by looping over every light, or
 * as a heat map of how many lights the fragment's cluster holds.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
final class LightShaders {

    private LightShaders() {
    }

    /**
     * Builds the vertex shader: the instance record gives a scale and a translation, the user data
     * word an id from which the albedo is chosen (grey for the buildings, tinted for the props, a
     * dark grey for the ground, the last box).
     *
     * @param layout the vertex layout of the cube; must not be {@code null}
     * @return the source text
     */
    static String vertex(VertexLayout layout) {
        int vec4s = (int) (InstanceWriter.STRIDE / 16);
        return "#version 450 core\n" + layout.toBufferLayout().glslInputs() + """
                layout(std430, binding = 0) readonly buffer Instances {
                    vec4 data[];
                };
                uniform mat4 viewProjection;
                uniform uint groundId;
                uniform uint neutralBelow;
                out vec3 vWorld;
                out vec3 vNormal;
                out vec3 vAlbedo;

                uint hash(uint x) {
                    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                    return x;
                }

                void main() {
                    int base = gl_InstanceID * %d;
                    vec4 row0 = data[base], row1 = data[base + 1], row2 = data[base + 2];
                    uint id = floatBitsToUint(data[base + 3].x);
                    vec4 p = vec4(position, 1.0);
                    vec3 world = vec3(dot(row0, p), dot(row1, p), dot(row2, p));
                    vec3 scale = vec3(row0.x, row1.y, row2.z);
                    vNormal = normalize(normal / scale);
                    uint h = hash(id);
                    vec3 tint = vec3(float(h & 255u), float((h >> 8) & 255u), float((h >> 16) & 255u)) / 255.0;
                    vAlbedo = id == groundId ? vec3(0.22) : id < neutralBelow ? vec3(0.5) + (tint - 0.5) * 0.08 : mix(vec3(0.5), tint, 0.6);
                    vWorld = world;
                    gl_Position = viewProjection * vec4(world, 1.0);
                }
                """.formatted(vec4s);
    }

    /**
     * Builds the fragment shader for a grid.
     *
     * @param grid the cluster grid, whose lookup function is pasted into the shader; must not be
     *     {@code null}
     * @return the source text
     */
    static String fragment(ClusterGrid grid) {
        return """
                #version 450 core
                in vec3 vWorld;
                in vec3 vNormal;
                in vec3 vAlbedo;
                uniform mat4 viewMatrix;
                uniform int mode;           // 0 clusters, 1 every light, 2 heat map of the lights per cluster
                uniform int bruteCount;
                uniform float exposure;
                uniform float heatScale;
                layout(std430, binding = 1) readonly buffer Ranges { uvec2 ranges[]; };
                layout(std430, binding = 2) readonly buffer Indices { uint lightIndices[]; };
                layout(std430, binding = 3) readonly buffer Lights { vec4 lightData[]; };   // per light: position and range, then colour
                out vec4 color;
                """ + grid.glslLookup() + """
                vec3 light(uint i, vec3 n) {
                    vec4 p = lightData[2u * i], c = lightData[2u * i + 1u];
                    vec3 toLight = p.xyz - vWorld;
                    float d2 = dot(toLight, toLight), r2 = p.w * p.w;
                    if (d2 >= r2) return vec3(0.0);
                    float att = 1.0 - d2 / r2;
                    att *= att;
                    return c.rgb * att * max(dot(n, toLight * inversesqrt(d2)), 0.0);
                }
                void main() {
                    vec3 n = normalize(vNormal);
                    float depth = -(viewMatrix * vec4(vWorld, 1.0)).z;
                    uint cluster = clusterIndex(gl_FragCoord.xy, depth);
                    uvec2 range = ranges[cluster];
                    vec3 sum = vec3(0.0);
                    if (mode == 1) {
                        for (int i = 0; i < bruteCount; i++) sum += light(uint(i), n);
                    } else {
                        for (uint k = 0u; k < range.y; k++) sum += light(lightIndices[range.x + k], n);
                    }
                    vec3 c = vAlbedo * (sum + vec3(0.03) * (0.5 + 0.5 * n.y));
                    c = vec3(1.0) - exp(-c * exposure);
                    c = pow(c, vec3(1.0 / 2.2));
                    if (mode == 2) {
                        float t = clamp(float(range.y) / heatScale, 0.0, 1.0);
                        c = mix(c * 0.4, vec3(t, 1.0 - abs(2.0 * t - 1.0), 1.0 - t), 0.7);
                    }
                    color = vec4(c, 1.0);
                }
                """;
    }
}
