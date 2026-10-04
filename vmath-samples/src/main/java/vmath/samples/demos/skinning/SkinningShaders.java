package vmath.samples.demos.skinning;

/**
 * The GLSL of the skinning demo: linear blend skinning and dual quaternion skinning as one
 * function that a vertex shader and a compute shader share, so that the compute shader can check
 * exactly the code that draws against the library's CPU reference.
 *
 * <p>The dual quaternion code is a line-for-line port of {@code Skinning.skinPositionsDualQuat}
 * and {@code skinNormalsDualQuat}: the joints' dual quaternions come in eight floats each (real
 * part {@code x, y, z, w}, then dual part), each joint is taken with the sign that puts it in the
 * same hemisphere as the first joint that has a weight, the blend is normalised, and the vertex is
 * rotated and then translated by it.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
final class SkinningShaders {

    private SkinningShaders() {
    }

    /**
     * The shared GLSL: the uniforms (matrices and dual quaternions of the joints) and the function
     * {@code skin}.
     */
    static final String SKIN = """
            uniform mat4 jointMatrix[2];
            uniform vec4 jointDq[4];   // per joint: the real part, then the dual part
            uniform int skinMode;      // 0 linear blend skinning, 1 dual quaternion skinning

            void skin(vec3 p, vec3 n, vec4 w, ivec4 j, out vec3 po, out vec3 no) {
                if (skinMode == 0) {
                    vec3 sp = vec3(0.0), sn = vec3(0.0);
                    for (int k = 0; k < 4; k++) {
                        if (w[k] == 0.0) continue;
                        mat4 m = jointMatrix[j[k]];
                        sp += w[k] * (m * vec4(p, 1.0)).xyz;
                        sn += w[k] * (mat3(m) * n);
                    }
                    po = sp;
                    no = normalize(sn);
                    return;
                }
                vec4 r = vec4(0.0), d = vec4(0.0), ref = vec4(0.0);
                bool have = false;
                for (int k = 0; k < 4; k++) {
                    if (w[k] == 0.0) continue;
                    vec4 qr = jointDq[j[k] * 2], qd = jointDq[j[k] * 2 + 1];
                    if (!have) { ref = qr; have = true; }
                    float s = dot(qr, ref) < 0.0 ? -w[k] : w[k];
                    r += s * qr;
                    d += s * qd;
                }
                if (!have) { po = p; no = n; return; }
                float inv = 1.0 / length(r);
                r *= inv;
                d *= inv;
                vec3 c = cross(r.xyz, p) + r.w * p;
                vec3 q = p + 2.0 * cross(r.xyz, c);
                q += 2.0 * (r.w * d.xyz - d.w * r.xyz + cross(r.xyz, d.xyz));
                po = q;
                vec3 cn = cross(r.xyz, n) + r.w * n;
                no = normalize(n + 2.0 * cross(r.xyz, cn));
            }
            """;

    /**
     * Builds the vertex shader: skins the vertex with the chosen method, moves it sideways so that
     * the two methods can stand next to each other, and passes the bind-pose position on for the
     * stripes that make the twist visible.
     *
     * @return the source text
     */
    static String vertex() {
        return """
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in vec3 normal;
                layout(location = 2) in float weight1;
                uniform mat4 viewProjection;
                uniform float offsetX;
                out vec3 vNormal;
                out vec3 vBind;
                """ + SKIN + """
                void main() {
                    vec3 po, no;
                    skin(position, normal, vec4(1.0 - weight1, weight1, 0.0, 0.0), ivec4(0, 1, 0, 0), po, no);
                    vNormal = no;
                    vBind = position;
                    gl_Position = viewProjection * vec4(po + vec3(offsetX, 0.0, 0.0), 1.0);
                }
                """;
    }

    /**
     * Builds the fragment shader: a directional light on a surface with stripes along and around
     * the bind-pose tube, lit on both sides because the tube is open.
     *
     * @return the source text
     */
    static String fragment() {
        return """
                #version 450 core
                in vec3 vNormal;
                in vec3 vBind;
                uniform vec3 baseColor;
                out vec4 color;
                void main() {
                    vec3 n = normalize(vNormal);
                    if (!gl_FrontFacing) n = -n;
                    float along = floor(vBind.x * 2.0 + 0.001);
                    float angle = floor(atan(vBind.z, vBind.y) * 3.0 / 3.14159265 + 6.0);
                    float checker = mod(along + angle, 2.0);
                    vec3 albedo = mix(baseColor, baseColor * 0.45 + vec3(0.12), checker);
                    float diffuse = max(dot(n, normalize(vec3(0.3, 0.6, 0.8))), 0.0);
                    color = vec4(albedo * (0.3 + 0.7 * diffuse), 1.0);
                }
                """;
    }

    /**
     * Builds the compute shader that skins every vertex of the tube and writes the position and
     * the normal, six floats per vertex, to a storage buffer, so that the result of the shader that
     * draws can be read back and compared with the CPU.
     *
     * @return the source text
     */
    static String compute() {
        return """
                #version 450 core
                layout(local_size_x = 64) in;
                layout(std430, binding = 0) readonly buffer In { float vertexData[]; };   // 7 floats per vertex
                layout(std430, binding = 1) writeonly buffer Out { float result[]; };      // 6 floats per vertex
                uniform int vertexCount;
                """ + SKIN + """
                void main() {
                    uint v = gl_GlobalInvocationID.x;
                    if (v >= uint(vertexCount)) return;
                    uint b = v * 7u;
                    vec3 p = vec3(vertexData[b], vertexData[b + 1u], vertexData[b + 2u]);
                    vec3 n = vec3(vertexData[b + 3u], vertexData[b + 4u], vertexData[b + 5u]);
                    float w1 = vertexData[b + 6u];
                    vec3 po, no;
                    skin(p, n, vec4(1.0 - w1, w1, 0.0, 0.0), ivec4(0, 1, 0, 0), po, no);
                    uint o = v * 6u;
                    result[o] = po.x; result[o + 1u] = po.y; result[o + 2u] = po.z;
                    result[o + 3u] = no.x; result[o + 4u] = no.y; result[o + 5u] = no.z;
                }
                """;
    }
}
