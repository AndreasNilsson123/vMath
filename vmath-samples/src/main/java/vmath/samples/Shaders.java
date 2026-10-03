package vmath.samples;

import vmath.gl.InstanceWriter;
import vmath.mesh.VertexLayout;

/**
 * The GLSL of {@link MillionInstances}.
 *
 * <p>Internal: part of the samples, not of the library. The vertex inputs are generated from the
 * vertex layout of the mesh by the library ({@code VertexBufferLayout.glslInputs()}), and the
 * layout of the instance records is the one that {@link InstanceWriter} documents: three rows of a
 * {@code vec4} for the transform and one {@code uint} of user data, 64 bytes in all.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
final class Shaders {

    private Shaders() {
    }

    /**
     * Builds the vertex shader: every instance is a box, a scale and a translation read from the
     * storage buffer by {@code gl_InstanceID}, and its colour comes from a hash of the user data,
     * which is the index of the box.
     *
     * @param layout the vertex layout of the mesh, whose attributes become the inputs of the
     *     shader; must not be {@code null}
     * @return the source text of the vertex shader
     */
    static String vertex(VertexLayout layout) {
        int vec4sPerInstance = (int) (InstanceWriter.STRIDE / 16);
        return "#version 450 core\n"
                + layout.toBufferLayout().glslInputs()
                + """
                layout(std430, binding = 0) readonly buffer Instances {
                    vec4 data[];   // per instance: row0, row1, row2 of the transform, then the user data word in x
                };
                uniform mat4 viewProjection;
                uniform uint groundId;
                out vec3 vNormal;
                out vec3 vColor;
                out float vHeight;
                out float vDepth;

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
                    // the transform is a scale and a translation: the normal is divided by the scale
                    vec3 scale = vec3(row0.x, row1.y, row2.z);
                    vNormal = normalize(normal / scale);
                    uint h = hash(id);
                    vec3 tint = vec3(float(h & 255u), float((h >> 8) & 255u), float((h >> 16) & 255u)) / 255.0;
                    vColor = id == groundId ? vec3(0.32, 0.34, 0.33) : mix(vec3(0.55, 0.6, 0.7), tint, 0.45);
                    vHeight = world.y;
                    gl_Position = viewProjection * vec4(world, 1.0);
                    vDepth = gl_Position.w;
                }
                """.formatted(vec4sPerInstance);
    }

    /**
     * Builds the fragment shader: a directional light with an ambient term, and a slight
     * brightening with height so that the towers read against the ground.
     *
     * @return the source text of the fragment shader
     */
    static String fragment() {
        return """
                #version 450 core
                in vec3 vNormal;
                in vec3 vColor;
                in float vHeight;
                in float vDepth;
                out vec4 color;
                void main() {
                    vec3 n = normalize(vNormal);
                    float diffuse = max(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0);
                    float light = 0.28 + 0.72 * diffuse;
                    vec3 c = vColor * light * (0.85 + 0.15 * clamp(vHeight / 60.0, 0.0, 1.0));
                    float fog = 1.0 - exp(-vDepth * 0.00045);
                    color = vec4(mix(c, vec3(0.55, 0.7, 0.88), fog), 1.0);
                }
                """;
    }
}
