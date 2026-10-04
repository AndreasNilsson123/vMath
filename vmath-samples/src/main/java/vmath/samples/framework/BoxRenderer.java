package vmath.samples.framework;

import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1ui;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glUseProgram;

import vmath.camera.Cameraf;
import vmath.mesh.Mesh;
import vmath.mesh.MeshOptimizer;
import vmath.mesh.Primitives;
import vmath.mesh.VertexLayout;

/**
 * Draws the instances of an {@link InstanceStream} as boxes: one unit cube mesh, built with the
 * library's primitives, optimised for the vertex cache and exported with a vertex layout, and a
 * program that scales it by each instance's record and lights it.
 *
 * <p>The GPU time of the draw is measured with the context's {@link GpuTimer}, so the series
 * {@code gpu} of a demo that uses this renderer is the cost of the boxes.
 *
 * <p>Internal: part of the samples, not of the library.
 *
 * <p><b>Thread safety.</b> Not thread-safe: all calls are made on the thread that owns the OpenGL
 * context.
 */
public final class BoxRenderer {

    private static final float UNIT_CUBE_HALF = 0.5f;

    private final GpuTimer gpu;
    private final int program;
    private final int viewProjectionLocation;
    private final int groundIdLocation;
    private final int neutralBelowLocation;
    private final int fogDensityLocation;
    private final int fogColorLocation;
    private final GpuMesh mesh;
    private float fogRed = 0.55f;
    private float fogGreen = 0.7f;
    private float fogBlue = 0.88f;

    /**
     * Builds the program and the cube. The OpenGL context must be current.
     *
     * @param ctx the demo's context, which supplies the arena and the GPU timer; must not be
     *     {@code null}
     */
    public BoxRenderer(DemoContext ctx) {
        this.gpu = ctx.gpu();
        VertexLayout layout = VertexLayout.builder().position().normal().build();
        program = Gl.program(BoxShaders.vertex(layout), BoxShaders.fragment());
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        groundIdLocation = glGetUniformLocation(program, "groundId");
        neutralBelowLocation = glGetUniformLocation(program, "neutralBelow");
        fogDensityLocation = glGetUniformLocation(program, "fogDensity");
        fogColorLocation = glGetUniformLocation(program, "fogColor");
        Mesh cube = Primitives.box(UNIT_CUBE_HALF, UNIT_CUBE_HALF, UNIT_CUBE_HALF);
        MeshOptimizer.optimizeVertexCache(cube, 32);
        mesh = GpuMesh.upload(ctx.arena(), cube, layout);
    }

    /**
     * Reads the cube mesh, for demos that draw it with their own program.
     *
     * @return the uploaded unit cube
     */
    public GpuMesh mesh() {
        return mesh;
    }

    /**
     * Sets the colour that distant boxes fade to, which should be the clear colour.
     *
     * @param red the red component in 0 to 1
     * @param green the green component in 0 to 1
     * @param blue the blue component in 0 to 1
     */
    public void fogColor(float red, float green, float blue) {
        fogRed = red;
        fogGreen = green;
        fogBlue = blue;
    }

    /**
     * Draws the instances written in this frame, timing the draw on the GPU.
     *
     * @param camera the camera whose view projection is used; must not be {@code null}
     * @param stream the instance stream whose current region is drawn; must not be {@code null}
     * @param groundId the index of the box that is drawn as the ground
     * @param neutralBelow the boxes with an index below this are drawn in a neutral colour, the
     *     others tinted by their index
     * @param fogDensity the density of the exponential fog; 0 for none
     */
    public void draw(Cameraf camera, InstanceStream stream, int groundId, int neutralBelow, float fogDensity) {
        gpu.begin();
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, camera.viewProjection());
        glProgramUniform1ui(program, groundIdLocation, groundId);
        glProgramUniform1ui(program, neutralBelowLocation, neutralBelow);
        glProgramUniform1f(program, fogDensityLocation, fogDensity);
        glProgramUniform3f(program, fogColorLocation, fogRed, fogGreen, fogBlue);
        stream.draw(mesh);
        gpu.end();
    }

    /**
     * Deletes the program and the mesh.
     */
    public void dispose() {
        mesh.delete();
        glDeleteProgram(program);
    }
}
