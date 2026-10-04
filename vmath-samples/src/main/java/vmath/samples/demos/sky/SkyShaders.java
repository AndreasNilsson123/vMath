package vmath.samples.demos.sky;

/**
 * The GLSL of the sky demo: the tone-mapping curves and the sRGB encoding as a port of the
 * library's {@code ToneMap} and {@code Srgb}, a full-screen ray-traced scene (ground, spheres,
 * shadows, the sky from a texture, the disc of the sun) lit by the numbers of the {@link SkyModel},
 * and a compute shader that evaluates the curves so that the port can be checked against the
 * library.
 *
 * <p>Curve numbers in the shader: 0 clamp, 1 Reinhard, 2 extended Reinhard, 3 ACES, 4 Hable, 5
 * {@code 1 - exp(-x)}; number {@code i} from 1 on is the library's {@code ToneMap.Curve} with the
 * ordinal {@code i - 1}.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Stateless: the methods may be called from any number of threads.
 */
final class SkyShaders {

    private SkyShaders() {
    }

    /**
     * The shared GLSL: the curves, {@code toneMap} and {@code srgbEncode}.
     */
    static final String TONE = """
            float tmReinhard(float x) { return x <= 0.0 ? 0.0 : x / (1.0 + x); }
            float tmReinhardExtended(float x, float w) {
                if (x <= 0.0) return 0.0;
                return clamp(x * (1.0 + x / (w * w)) / (1.0 + x), 0.0, 1.0);
            }
            float tmAces(float x) {
                if (x <= 0.0) return 0.0;
                return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
            }
            float hableRaw(float x) {
                return ((x * (0.15 * x + 0.10 * 0.50) + 0.20 * 0.02) / (x * (0.15 * x + 0.50) + 0.20 * 0.30)) - 0.02 / 0.30;
            }
            float tmHable(float x) { return x <= 0.0 ? 0.0 : clamp(hableRaw(x) / hableRaw(11.2), 0.0, 1.0); }
            float tmExposure(float x) { return x <= 0.0 ? 0.0 : 1.0 - exp(-x); }
            float toneMap(int curve, float x, float whitePoint) {
                switch (curve) {
                    case 1: return tmReinhard(x);
                    case 2: return tmReinhardExtended(x, whitePoint);
                    case 3: return tmAces(x);
                    case 4: return tmHable(x);
                    case 5: return tmExposure(x);
                    default: return clamp(x, 0.0, 1.0);
                }
            }
            float srgbEncode(float x) {
                x = clamp(x, 0.0, 1.0);
                return x <= 0.0031308 ? x * 12.92 : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
            }
            """;

    /**
     * Builds the vertex shader: a triangle that covers the screen, with the position in normalised
     * device coordinates passed on.
     *
     * @return the source text
     */
    static String vertex() {
        return """
                #version 450 core
                out vec2 ndc;
                void main() {
                    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    ndc = p * 2.0 - 1.0;
                    gl_Position = vec4(ndc, 0.0, 1.0);
                }
                """;
    }

    /**
     * Builds the fragment shader: for every pixel a ray from the camera, the nearest of the ground
     * and four spheres, direct sunlight with a shadow ray and the light of the sky on the surface, or
     * the sky itself and the disc of the sun; then the exposure, the tone-mapping curve and the sRGB
     * encoding.
     *
     * @return the source text
     */
    static String fragment() {
        return """
                #version 450 core
                in vec2 ndc;
                layout(binding = 0) uniform sampler2D skyTable;
                uniform vec3 camPos, camRight, camUp, camForward;
                uniform vec2 tanHalf;
                uniform vec3 sunDir, sunE, skyE, sunL;
                uniform float exposure, whitePoint;
                uniform int curve;
                out vec4 color;
                const float PI = 3.14159265;
                const vec4 SPHERES[4] = vec4[4](vec4(-2.6, 1.0, -9.0, 1.0), vec4(0.0, 0.75, -7.0, 0.75), vec4(2.4, 1.2, -10.0, 1.2), vec4(-0.6, 0.5, -4.5, 0.5));
                const vec3 ALBEDO[4] = vec3[4](vec3(0.8), vec3(0.18), vec3(0.55, 0.08, 0.08), vec3(0.1, 0.45, 0.14));
                """ + TONE + """
                vec3 skyRadiance(vec3 d) {
                    float theta = acos(clamp(d.y, 0.0, 1.0));
                    float phi = atan(d.x, -d.z);
                    if (phi < 0.0) phi += 2.0 * PI;
                    return texture(skyTable, vec2(phi / (2.0 * PI), theta / (0.5 * PI))).rgb;
                }
                float hitSphere(vec3 o, vec3 d, vec4 s) {
                    vec3 oc = o - s.xyz;
                    float b = dot(oc, d), c = dot(oc, oc) - s.w * s.w, h = b * b - c;
                    if (h < 0.0) return -1.0;
                    float t = -b - sqrt(h);
                    return t > 1e-3 ? t : -1.0;
                }
                float sunVisible(vec3 p) {
                    for (int i = 0; i < 4; i++) if (hitSphere(p, sunDir, SPHERES[i]) > 0.0) return 0.0;
                    return 1.0;
                }
                vec3 shade(vec3 p, vec3 n, vec3 albedo) {
                    vec3 direct = sunE * max(dot(n, sunDir), 0.0) * sunVisible(p + n * 1e-3);
                    vec3 ambient = skyE * (0.5 + 0.5 * n.y);
                    return albedo / PI * (direct + ambient);
                }
                void main() {
                    vec3 d = normalize(camForward + ndc.x * tanHalf.x * camRight + ndc.y * tanHalf.y * camUp);
                    float best = 1e30;
                    int which = -1;
                    for (int i = 0; i < 4; i++) {
                        float t = hitSphere(camPos, d, SPHERES[i]);
                        if (t > 0.0 && t < best) { best = t; which = i; }
                    }
                    float tg = d.y < -1e-4 ? -camPos.y / d.y : -1.0;
                    vec3 radiance;
                    if (tg > 0.0 && tg < best) {
                        vec3 p = camPos + d * tg;
                        float checker = mod(floor(p.x / 2.0) + floor(p.z / 2.0), 2.0);
                        vec3 albedo = vec3(mix(0.22, 0.38, checker));
                        vec3 ground = shade(p, vec3(0.0, 1.0, 0.0), albedo);
                        float fog = 1.0 - exp(-tg / 6000.0);
                        radiance = mix(ground, skyRadiance(normalize(vec3(d.x, 0.0, d.z) + vec3(0.0, 1e-4, 0.0))), fog);
                    } else if (which >= 0) {
                        vec3 p = camPos + d * best;
                        radiance = shade(p, normalize(p - SPHERES[which].xyz), ALBEDO[which]);
                    } else {
                        radiance = skyRadiance(d);
                        float cosA = dot(d, sunDir);
                        float edge = smoothstep(cos(0.00465 * 1.15), cos(0.00465 * 0.9), cosA);
                        radiance += sunL * edge;
                    }
                    vec3 c = radiance * exposure;
                    c = vec3(toneMap(curve, c.r, whitePoint), toneMap(curve, c.g, whitePoint), toneMap(curve, c.b, whitePoint));
                    color = vec4(srgbEncode(c.r), srgbEncode(c.g), srgbEncode(c.b), 1.0);
                }
                """;
    }

    /**
     * Builds the compute shader that evaluates one curve and the sRGB encoding for a list of
     * values, to be read back and compared with {@code ToneMap} and {@code Srgb}.
     *
     * @return the source text
     */
    static String compute() {
        return """
                #version 450 core
                layout(local_size_x = 64) in;
                layout(std430, binding = 0) readonly buffer In { float xs[]; };
                layout(std430, binding = 1) writeonly buffer Out { float ys[]; };   // 2 floats per value: the curve, then the sRGB encoding of it
                uniform int count;
                uniform int curve;
                uniform float whitePoint;
                """ + TONE + """
                void main() {
                    uint i = gl_GlobalInvocationID.x;
                    if (i >= uint(count)) return;
                    float y = toneMap(curve, xs[i], whitePoint);
                    ys[i * 2u] = y;
                    ys[i * 2u + 1u] = srgbEncode(y);
                }
                """;
    }
}
