package vmath.samples.demos.physics;

import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glProgramUniform1ui;
import static org.lwjgl.opengl.GL45.glUseProgram;

import vmath.camera.Cameraf;
import vmath.gl.InstanceWriter;
import vmath.mesh.Mesh;
import vmath.mesh.MeshOptimizer;
import vmath.mesh.Primitives;
import vmath.mesh.VertexLayout;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GpuMesh;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.InstanceStream;

/**
 * Draws rotated boxes and spheres from two instance streams: a unit cube and a unit-diameter
 * sphere, both scaled, rotated and moved by the 3 by 4 transform of each instance record.
 *
 * <p>The vertex shader reads the record by {@code gl_InstanceID} and finds the normal with the
 * columns of the transform ({@code n' = sum n_k c_k / |c_k|^2}, which is the inverse transpose for
 * a rotation with a scale on each axis). Instances with an id below the static count are drawn in
 * a flat grey, the others in a colour from a hash of the id.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
final class BodyRenderer {

    private final GpuTimer gpu;
    private final int program;
    private final int viewProjectionLocation;
    private final int staticBelowLocation;
    private final GpuMesh cube;
    private final GpuMesh ball;

    BodyRenderer(DemoContext ctx) {
        this.gpu = ctx.gpu();
        VertexLayout layout = VertexLayout.builder().position().normal().build();
        int vec4s = (int) (InstanceWriter.STRIDE / 16);
        program = Gl.program("#version 450 core\n" + layout.toBufferLayout().glslInputs() + """
                layout(std430, binding = 0) readonly buffer Instances {
                    vec4 data[];
                };
                uniform mat4 viewProjection;
                uniform uint staticBelow;
                out vec3 vNormal;
                out vec3 vColor;
                out float vDepth;

                uint hash(uint x) {
                    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
                    return x;
                }

                void main() {
                    int base = gl_InstanceID * %d;
                    vec4 r0 = data[base], r1 = data[base + 1], r2 = data[base + 2];
                    uint id = floatBitsToUint(data[base + 3].x);
                    vec4 p = vec4(position, 1.0);
                    vec3 world = vec3(dot(r0, p), dot(r1, p), dot(r2, p));
                    vec3 c0 = vec3(r0.x, r1.x, r2.x), c1 = vec3(r0.y, r1.y, r2.y), c2 = vec3(r0.z, r1.z, r2.z);
                    vNormal = normalize(normal.x * c0 / dot(c0, c0) + normal.y * c1 / dot(c1, c1) + normal.z * c2 / dot(c2, c2));
                    uint h = hash(id);
                    vec3 tint = vec3(float(h & 255u), float((h >> 8) & 255u), float((h >> 16) & 255u)) / 255.0;
                    vColor = id < staticBelow ? vec3(0.42, 0.44, 0.46) : mix(vec3(0.5), tint, 0.75);
                    gl_Position = viewProjection * vec4(world, 1.0);
                    vDepth = gl_Position.w;
                }
                """.formatted(vec4s), """
                #version 450 core
                in vec3 vNormal;
                in vec3 vColor;
                in float vDepth;
                out vec4 color;
                void main() {
                    vec3 n = normalize(vNormal);
                    float diffuse = max(dot(n, normalize(vec3(0.4, 0.8, 0.3))), 0.0);
                    float light = 0.3 + 0.7 * diffuse;
                    float fog = 1.0 - exp(-vDepth * 0.004);
                    color = vec4(mix(vColor * light, vec3(0.62, 0.72, 0.85), fog), 1.0);
                }
                """);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        staticBelowLocation = glGetUniformLocation(program, "staticBelow");
        Mesh box = Primitives.box(0.5f, 0.5f, 0.5f);
        MeshOptimizer.optimizeVertexCache(box, 32);
        cube = GpuMesh.upload(ctx.arena(), box, layout);
        Mesh sphere = Primitives.uvSphere(0.5f, 24, 16);
        MeshOptimizer.optimizeVertexCache(sphere, 32);
        ball = GpuMesh.upload(ctx.arena(), sphere, layout);
    }

    /**
     * Draws the boxes and then the spheres, timing both on the GPU together.
     *
     * @param camera the camera; must not be {@code null}
     * @param boxes the stream holding the box instances; must not be {@code null}
     * @param spheres the stream holding the sphere instances; must not be {@code null}
     * @param staticBelow instances with an id below this are drawn in grey
     */
    void draw(Cameraf camera, InstanceStream boxes, InstanceStream spheres, int staticBelow) {
        gpu.begin();
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, camera.viewProjection());
        glProgramUniform1ui(program, staticBelowLocation, staticBelow);
        boxes.draw(cube);
        spheres.draw(ball);
        gpu.end();
    }

    void dispose() {
        cube.delete();
        ball.delete();
        glDeleteProgram(program);
    }
}
