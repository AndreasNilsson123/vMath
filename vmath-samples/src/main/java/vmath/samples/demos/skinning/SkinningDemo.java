package vmath.samples.demos.skinning;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FILL;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL45.GL_LINE;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBufferBase;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glDispatchCompute;
import static org.lwjgl.opengl.GL45.glDrawElements;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glPolygonMode;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glProgramUniform4fv;
import static org.lwjgl.opengl.GL45.glProgramUniformMatrix4fv;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.Hud;
import vmath.samples.framework.OrbitCamera;
import vmath.samples.framework.Stats;

/**
 * An arm twisted by one joint, skinned two ways side by side: on the left with linear blend
 * skinning, on the right with dual quaternion skinning, both in the vertex shader and both from the
 * same {@link vmath.anim.Pose}. At half a turn the linear blend pinches the blended band to a point
 * (the candy-wrapper artefact) and the dual quaternion keeps its radius.
 *
 * <p>The joint matrices and the dual quaternions come from the library's
 * {@code Skinning.jointMatrices} and {@code Skinning.jointDualQuaternions} ({@link TwistRig}). The
 * shader's skinning code is checked against the library's CPU reference: with {@code --verify} the
 * demo runs the same GLSL function in a compute shader at ten twist angles, reads the skinned
 * positions and normals back and compares them with {@code Skinning.skinPositions},
 * {@code skinPositionsDualQuat} and the normal versions, and fails if they differ by more than a
 * small tolerance. Every frame the demo also skins the ring in the middle of the tube on the CPU
 * and shows its radius for both methods.
 *
 * <p>The left and right arrow keys turn the joint, {@code Space} runs the twist back and forth by
 * itself, and {@code L} draws wireframes.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class SkinningDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("dq-vs-lbs", "Dual quaternion against linear blend skinning",
            "A twisted arm skinned two ways in the shaders: linear blending pinches it to a point at half a turn, dual quaternions keep its volume.",
            List.of("animation"), 4096, List.of("--verify"),
            "left mouse + move: orbit | wheel: zoom | Left, Right: twist | Space: automatic twist | L: wireframe | R: reset the camera");

    private static final float OFFSET = 3.2f;
    private static final float[] VERIFY_DEGREES = {0f, 45f, 90f, 135f, 170f, 180f, 190f, 270f, 359f, 360f};
    private static final float POSITION_TOLERANCE = 2e-4f;
    private static final float NORMAL_TOLERANCE = 2e-3f;

    private final boolean verify;
    private Tube tube;
    private final TwistRig rig = new TwistRig();
    private int vao;
    private int vbo;
    private int ebo;
    private int program;
    private int viewProjectionLocation;
    private int offsetLocation;
    private int modeLocation;
    private int baseColorLocation;
    private int matrixLocation;
    private int dualLocation;
    private OrbitCamera orbit;
    private DemoContext ctx;
    private Cameraf camera;
    private float[] ringPositions;
    private int[] ringJoints;
    private float[] ringWeights;
    private final float[] ringOut = new float[3 * 64];
    private float twist;
    private boolean automatic = true;
    private boolean wire;
    private float waistLinear;
    private float waistDual;
    private float smallestLinear = Float.MAX_VALUE;
    private float smallestDual = Float.MAX_VALUE;
    private double verifiedError;
    private int waistLinearSeries;
    private int waistDualSeries;
    private int twistSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments, {@code --verify} or nothing; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown
     */
    public SkinningDemo(List<String> args) {
        boolean v = false;
        for (String a : args) {
            if (a.equals("--verify")) {
                v = true;
            } else {
                throw new IllegalArgumentException("unknown argument " + a + "\noptions of the dq-vs-lbs demo:\n  --verify   check the shader's skinning against the library's CPU reference on the GPU\n");
            }
        }
        this.verify = v;
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        tube = new Tube(41, 32);
        vbo = glCreateBuffers();
        glNamedBufferStorage(vbo, tube.interleaved(), 0);
        ebo = glCreateBuffers();
        glNamedBufferStorage(ebo, tube.indices(), 0);
        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, 7 * Float.BYTES);
        glVertexArrayElementBuffer(vao, ebo);
        int[] sizes = {3, 3, 1};
        int offset = 0;
        for (int i = 0; i < 3; i++) {
            glEnableVertexArrayAttrib(vao, i);
            glVertexArrayAttribFormat(vao, i, sizes[i], GL_FLOAT, false, offset * Float.BYTES);
            glVertexArrayAttribBinding(vao, i, 0);
            offset += sizes[i];
        }
        program = Gl.program(SkinningShaders.vertex(), SkinningShaders.fragment());
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        offsetLocation = glGetUniformLocation(program, "offsetX");
        modeLocation = glGetUniformLocation(program, "skinMode");
        baseColorLocation = glGetUniformLocation(program, "baseColor");
        matrixLocation = glGetUniformLocation(program, "jointMatrix");
        dualLocation = glGetUniformLocation(program, "jointDq");
        orbit = new OrbitCamera(new Vec3f(0f, 0f, 0f), 0f, 0.2f, 8.5f, 0.9f, 0.1f, 100f);

        int around = tube.aroundCount();
        ringPositions = new float[around * 3];
        ringJoints = new int[around * 4];
        ringWeights = new float[around * 4];
        int first = tube.middleRing();
        System.arraycopy(tube.positions(), first * 3, ringPositions, 0, around * 3);
        System.arraycopy(tube.joints(), first * 4, ringJoints, 0, around * 4);
        System.arraycopy(tube.weights(), first * 4, ringWeights, 0, around * 4);

        Stats stats = ctx.stats();
        twistSeries = stats.series("twist", "deg", 1, "the turn of joint 1");
        waistLinearSeries = stats.series("waist, linear blend", "m", 3, "mean radius of the ring in the middle, skinned on the CPU (bind pose 0.500)");
        waistDualSeries = stats.series("waist, dual quaternion", "m", 3, "the same ring with dual quaternion skinning");
        ctx.clearColor(0.16f, 0.18f, 0.22f);
        if (verify) {
            verifyAgainstCpu();
        }
    }

    @Override
    public void update(FrameInfo frame) {
        orbit.update(frame);
        if (frame.benchmark()) {
            orbit.scripted(frame.frame(), 0f);
            float phase = (float) (frame.frame() / 240.0 * 2.0 * Math.PI);
            twist = (float) Math.toRadians(200.0 * (0.5 - 0.5 * Math.cos(phase)));
        } else {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_SPACE)) {
                automatic = !automatic;
            }
            if (in.pressed(GLFW_KEY_L)) {
                wire = !wire;
            }
            float manual = in.axis(GLFW_KEY_RIGHT, GLFW_KEY_LEFT);
            if (manual != 0f) {
                automatic = false;
                twist = Math.max(0f, Math.min((float) (2 * Math.PI), twist + manual * 1.2f * frame.dt()));
            } else if (automatic) {
                twist = (float) Math.toRadians(200.0 * (0.5 - 0.5 * Math.cos(frame.time() * 0.9)));
            }
        }
        camera = orbit.camera(frame.aspect());
        rig.twist(twist);

        int around = tube.aroundCount();
        rig.skin(false, ringPositions, ringJoints, ringWeights, around, ringOut);
        waistLinear = TwistRig.radius(ringOut, around);
        rig.skin(true, ringPositions, ringJoints, ringWeights, around, ringOut);
        waistDual = TwistRig.radius(ringOut, around);
        smallestLinear = Math.min(smallestLinear, waistLinear);
        smallestDual = Math.min(smallestDual, waistDual);
        Stats stats = ctx.stats();
        stats.record(twistSeries, Math.toDegrees(twist));
        stats.record(waistLinearSeries, waistLinear);
        stats.record(waistDualSeries, waistDual);
    }

    @Override
    public void render(FrameInfo frame) {
        ctx.gpu().begin();
        glDisable(GL_CULL_FACE);
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, camera.viewProjection());
        uploadJoints(program, matrixLocation, dualLocation);
        glBindVertexArray(vao);
        glProgramUniform1i(program, modeLocation, 0);
        glProgramUniform1f(program, offsetLocation, -OFFSET);
        glProgramUniform3f(program, baseColorLocation, 0.9f, 0.45f, 0.35f);
        glDrawElements(GL_TRIANGLES, tube.indices().length, GL_UNSIGNED_INT, 0L);
        glProgramUniform1i(program, modeLocation, 1);
        glProgramUniform1f(program, offsetLocation, OFFSET);
        glProgramUniform3f(program, baseColorLocation, 0.4f, 0.8f, 0.5f);
        glDrawElements(GL_TRIANGLES, tube.indices().length, GL_UNSIGNED_INT, 0L);
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }
        ctx.gpu().end();
    }

    /**
     * Uploads the joint matrices and the dual quaternions of the current twist to a program.
     */
    private void uploadJoints(int prog, int matrices, int duals) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer m = stack.mallocFloat(32);
            m.put(rig.matrices(), 0, 32).flip();
            glProgramUniformMatrix4fv(prog, matrices, false, m);
            FloatBuffer d = stack.mallocFloat(16);
            d.put(rig.dualQuaternions(), 0, 16).flip();
            glProgramUniform4fv(prog, duals, d);
        }
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        float width = frame.width(), height = frame.height();
        hud.line(String.format(Locale.ROOT, "twist of joint 1: %.0f degrees%s", Math.toDegrees(twist), automatic ? " (automatic)" : ""));
        hud.bar(8f, hud.cursorY() + 4f, 360f, 14f, twist / (float) (2 * Math.PI), 0.9f, 0.8f, 0.3f);
        hud.gap(26f);
        hud.line(String.format(Locale.ROOT, "waist radius (bind pose 0.500): linear blend %.3f | dual quaternion %.3f", waistLinear, waistDual));
        hud.line(String.format(Locale.ROOT, "smallest so far: linear blend %.3f | dual quaternion %.3f", smallestLinear, smallestDual));
        if (verify) {
            hud.line(String.format(Locale.ROOT, "shader against CPU reference: largest error %.1e", verifiedError));
        }
        Vec3f left = camera.toScreen(new Vec3f(-OFFSET, -1.2f, 0f), (int) width, (int) height);
        Vec3f right = camera.toScreen(new Vec3f(OFFSET, -1.2f, 0f), (int) width, (int) height);
        hud.color(1f, 0.7f, 0.6f).text(left.x() - 130f, height - left.y() - 6f, "linear blend skinning");
        hud.color(0.6f, 1f, 0.7f).text(right.x() - 140f, height - right.y() - 6f, "dual quaternion skinning");
        hud.color(0.8f, 0.9f, 1f);
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "smallest waist radius over the run: linear blend %.3f m, dual quaternion %.3f m (bind pose %.3f m)", smallestLinear, smallestDual,
                Tube.RADIUS));
        if (verify) {
            stats.note(String.format(Locale.ROOT, "the shader's skinning matches the CPU reference at %d angles: largest error %.2e", VERIFY_DEGREES.length, verifiedError));
        }
    }

    /**
     * Runs the shader's skinning function in a compute shader over every vertex of the tube at the
     * test angles, reads the results back, and compares them with the library's CPU reference for
     * both methods.
     *
     * @throws IllegalStateException if the GPU and the CPU differ by more than the tolerance
     */
    private void verifyAgainstCpu() {
        int n = tube.vertexCount();
        int compute = Gl.computeProgram(SkinningShaders.compute());
        int in = glCreateBuffers();
        glNamedBufferStorage(in, tube.interleaved(), 0);
        int out = glCreateBuffers();
        glNamedBufferStorage(out, (long) n * 6 * Float.BYTES, GL_DYNAMIC_STORAGE_BIT);
        int modeLoc = glGetUniformLocation(compute, "skinMode"), countLoc = glGetUniformLocation(compute, "vertexCount");
        int matLoc = glGetUniformLocation(compute, "jointMatrix"), dqLoc = glGetUniformLocation(compute, "jointDq");
        FloatBuffer gpu = MemoryUtil.memAllocFloat(n * 6);
        float[] cpuPositions = new float[n * 3], cpuNormals = new float[n * 3];
        double worst = 0.0;
        try {
            glUseProgram(compute);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, in);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, out);
            glProgramUniform1i(compute, countLoc, n);
            for (float degrees : VERIFY_DEGREES) {
                rig.twist((float) Math.toRadians(degrees));
                uploadJoints(compute, matLoc, dqLoc);
                for (int mode = 0; mode < 2; mode++) {
                    glProgramUniform1i(compute, modeLoc, mode);
                    glDispatchCompute((n + 63) / 64, 1, 1);
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                    gpu.clear();
                    glGetNamedBufferSubData(out, 0, gpu);
                    rig.skin(mode == 1, tube.positions(), tube.joints(), tube.weights(), n, cpuPositions);
                    rig.skinNormals(mode == 1, tube.normals(), tube.joints(), tube.weights(), n, cpuNormals);
                    for (int v = 0; v < n; v++) {
                        for (int k = 0; k < 3; k++) {
                            double ep = Math.abs(gpu.get(v * 6 + k) - cpuPositions[v * 3 + k]);
                            double en = Math.abs(gpu.get(v * 6 + 3 + k) - cpuNormals[v * 3 + k]);
                            worst = Math.max(worst, Math.max(ep, en));
                            if (ep > POSITION_TOLERANCE || en > NORMAL_TOLERANCE) {
                                throw new IllegalStateException(String.format(Locale.ROOT,
                                        "the %s shader differs from the CPU reference at %.0f degrees, vertex %d: position error %.2e, normal error %.2e",
                                        mode == 0 ? "linear blend" : "dual quaternion", degrees, v, ep, en));
                            }
                        }
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(gpu);
            glDeleteBuffers(in);
            glDeleteBuffers(out);
            glDeleteProgram(compute);
        }
        verifiedError = worst;
        System.out.printf(Locale.ROOT, "verified the skinning shaders against the CPU reference at %d angles: largest error %.2e%n", VERIFY_DEGREES.length, worst);
    }

    @Override
    public void dispose() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteProgram(program);
    }
}
