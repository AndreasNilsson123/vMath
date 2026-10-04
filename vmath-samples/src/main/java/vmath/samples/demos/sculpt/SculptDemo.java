package vmath.samples.demos.sculpt;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FILL;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL45.GL_LINE;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDrawElements;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glPolygonMode;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.Rayf;
import vmath.geo.Sdf;
import vmath.geo.Sdfs;
import vmath.geo.SurfaceNets;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.Hud;
import vmath.samples.framework.OrbitCamera;
import vmath.samples.framework.Stats;
import vmath.util.DebugLines;

/**
 * Sculpting with signed distance fields: a shape built with the library's CSG (smooth union of
 * two spheres, a hole drilled through it, a torus around it) is sampled onto a grid, and a brush
 * adds material ({@code Sdfs.smoothUnion}) or carves it ({@code Sdfs.smoothSubtract}) where the
 * mouse points, which {@code Sdfs.raycast} finds by sphere tracing the field. The surface is
 * re-meshed with {@code SurfaceNets} after every stroke (every frame in a scripted run) and the
 * mesh is uploaded and drawn.
 *
 * <p>Hold the right mouse button to add material and {@code Left Shift} with it to carve; the left
 * button orbits and the wheel zooms; {@code [} and {@code ]} change the brush size; {@code T}
 * draws the wireframe; {@code Z} goes back to the starting shape. A scripted run points the brush
 * along a fixed path on the screen, adding for 150 frames and carving for the next 150, and does the
 * ray cast, the stamp, the meshing and the upload every frame, which is what the numbers measure.
 * {@code --verify} checks that every edge of the mesh has a partner, which fails the run if the mesh
 * is open, and counts the frames in which the surface touches itself along an edge.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class SculptDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("sdf-sculpt", "Sculpting with signed distance fields",
            "CSG shapes sampled onto a grid, a brush that adds and carves with smooth operations, and the surface re-meshed with surface nets every stroke.",
            List.of("geometry", "physics"), 32768, List.of("--grid", "40", "--verify"),
            "right mouse: add | Shift + right mouse: carve | left mouse + move: orbit | wheel: zoom | [ ]: brush size | T: wireframe | Z: reset the shape | R: reset the camera");

    private static final float HALF = 2.4f;
    private static final float SMOOTH = 0.12f;
    private static final int PHASE_FRAMES = 150;
    private static final int VERIFY_EVERY = 10;

    private final SculptOptions options;
    private SculptField field;
    private final SurfaceNets nets = new SurfaceNets();
    private final MeshCheck check = new MeshCheck();
    private final Sdfs.Hit hit = new Sdfs.Hit();
    private final DebugLines lines = new DebugLines();
    private OrbitCamera orbit;
    private DemoContext ctx;
    private Cameraf camera;
    private int vao;
    private int vbo;
    private int ebo;
    private int program;
    private int viewProjectionLocation;
    private int flatLocation;
    private long vboCapacity;
    private long eboCapacity;
    private FloatBuffer stagedVertices = MemoryUtil.memAllocFloat(1);
    private IntBuffer stagedIndices = MemoryUtil.memAllocInt(1);
    private float[] vertexData = new float[0];
    private float brush = 0.35f;
    private boolean wire;
    private boolean pointing;
    private float hitX;
    private float hitY;
    private float hitZ;
    private boolean dirty = true;
    private int pickSeries;
    private int stampSeries;
    private int meshSeries;
    private int uploadSeries;
    private int vertexSeries;
    private int triangleSeries;
    private int rateSeries;
    private int stamps;
    private double meshMs;
    private double stampMs;
    private double uploadMs;
    private long checkedFrames;
    private long nonManifoldFrames;
    private int mostRepeated;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public SculptDemo(List<String> args) {
        this.options = SculptOptions.parse(args);
    }

    /**
     * Builds the starting shape with the library's CSG: two spheres blended smoothly, a cylinder
     * drilled through them, and a torus around the waist.
     */
    private static Sdf startingShape() {
        Sdf body = Sdfs.smoothUnion(Sdfs.sphere(-0.7f, 0f, 0f, 0.85f), Sdfs.sphere(0.7f, 0f, 0f, 0.85f), 0.4f);
        Sdf drilled = Sdfs.subtract(body, Sdfs.cylinder(0f, 0f, 0f, 0.3f, 3f));
        return Sdfs.union(drilled, Sdfs.torus(0f, 0f, 0f, 2.0f, 0.2f));
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        field = new SculptField(options.grid(), HALF, startingShape());
        nets.normals(options.normals()).projection(options.projection());
        program = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in vec3 normal;
                uniform mat4 viewProjection;
                out vec3 vNormal;
                out vec3 vWorld;
                void main() {
                    vNormal = normal;
                    vWorld = position;
                    gl_Position = viewProjection * vec4(position, 1.0);
                }
                """, """
                #version 450 core
                in vec3 vNormal;
                in vec3 vWorld;
                uniform int useFlat;
                out vec4 color;
                void main() {
                    vec3 n = useFlat == 1 ? normalize(cross(dFdx(vWorld), dFdy(vWorld))) : normalize(vNormal);
                    float key = max(dot(n, normalize(vec3(0.4, 0.8, 0.5))), 0.0);
                    float fill = max(dot(n, normalize(vec3(-0.6, 0.1, -0.5))), 0.0);
                    vec3 albedo = mix(vec3(0.82, 0.6, 0.42), vec3(0.55, 0.65, 0.8), clamp(vWorld.y * 0.35 + 0.5, 0.0, 1.0));
                    color = vec4(albedo * (0.22 + 0.75 * key + 0.25 * fill), 1.0);
                }
                """);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        flatLocation = glGetUniformLocation(program, "useFlat");
        vbo = glCreateBuffers();
        ebo = glCreateBuffers();
        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, 6 * Float.BYTES);
        glVertexArrayElementBuffer(vao, ebo);
        for (int i = 0; i < 2; i++) {
            glEnableVertexArrayAttrib(vao, i);
            glVertexArrayAttribFormat(vao, i, 3, GL_FLOAT, false, i * 3 * Float.BYTES);
            glVertexArrayAttribBinding(vao, i, 0);
        }
        orbit = new OrbitCamera(new Vec3f(0f, 0f, 0f), 0.5f, 0.45f, 8f, 0.9f, 0.1f, 100f);

        Stats stats = ctx.stats();
        pickSeries = stats.timer("pick", "Sdfs.raycast from the pointer into the field");
        stampSeries = stats.timer("stamp", "a brush stroke: smooth union or subtraction on the samples near the brush");
        meshSeries = stats.timer("mesh", "SurfaceNets over the whole " + options.grid() + "^3 grid");
        uploadSeries = stats.timer("upload", "the vertices and indices into the GPU buffers");
        vertexSeries = stats.series("vertices", "", 0, "vertices of the mesh");
        triangleSeries = stats.series("triangles", "", 0, "triangles of the mesh");
        rateSeries = stats.series("meshing rate", "Mtri/s", 2, "triangles meshed per second of meshing time");
        ctx.clearColor(0.12f, 0.13f, 0.16f);
        remesh();
    }

    @Override
    public void update(FrameInfo frame) {
        orbit.update(frame);
        boolean add = true, stroke = false;
        float px, py;
        if (frame.benchmark()) {
            orbit.scripted(0, 0f);
            float a = frame.frame() * 0.045f;
            px = frame.width() * (0.5f + 0.22f * (float) Math.cos(a));
            py = frame.height() * (0.5f + 0.18f * (float) Math.sin(a * 1.7f));
            add = (frame.frame() / PHASE_FRAMES) % 2 == 0;
            stroke = true;
        } else {
            var in = frame.input();
            px = in.mouseX();
            py = in.mouseY();
            stroke = in.mouseDown(GLFW_MOUSE_BUTTON_RIGHT);
            add = !in.down(GLFW_KEY_LEFT_SHIFT);
            if (in.pressed(GLFW_KEY_T)) {
                wire = !wire;
            }
            if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
                brush = Math.max(0.1f, brush / 1.15f);
            }
            if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
                brush = Math.min(1.2f, brush * 1.15f);
            }
            if (in.pressed(GLFW_KEY_Z)) {
                field = new SculptField(options.grid(), HALF, startingShape());
                dirty = true;
            }
        }
        camera = orbit.camera(frame.aspect());

        long t0 = System.nanoTime();
        Rayf ray = camera.pickRay(px, py, frame.width(), frame.height());
        float len = (float) Math.sqrt(ray.dx() * ray.dx() + ray.dy() * ray.dy() + ray.dz() * ray.dz());
        pointing = Sdfs.raycast(field, ray.ox(), ray.oy(), ray.oz(), ray.dx() / len, ray.dy() / len, ray.dz() / len, 0f, 40f, 256, 1e-3f, hit);
        long t1 = System.nanoTime();
        if (pointing) {
            hitX = hit.x;
            hitY = hit.y;
            hitZ = hit.z;
        }
        stampMs = 0.0;
        if (pointing && stroke) {
            float limit = HALF - brush - SMOOTH - 2f * field.cellSize();
            float sx = Math.max(-limit, Math.min(limit, hitX)), sy = Math.max(-limit, Math.min(limit, hitY)), sz = Math.max(-limit, Math.min(limit, hitZ));
            long s0 = System.nanoTime();
            field.stamp(add, sx, sy, sz, brush, SMOOTH);
            stampMs = (System.nanoTime() - s0) / 1e6;
            stamps++;
            dirty = true;
            ctx.stats().recordNanos(stampSeries, (long) (stampMs * 1e6));
        }
        ctx.stats().recordNanos(pickSeries, t1 - t0);
        if (dirty) {
            remesh();
            dirty = false;
            if (options.verify() && frame.frame() % VERIFY_EVERY == 0) {
                check.openEdges(nets.indices(), nets.triangleCount(), nets.vertexCount());
                checkedFrames++;
                if (check.unpaired() > 0) {
                    throw new IllegalStateException("the mesh at frame " + frame.frame() + " is not closed: " + check.unpaired() + " edges have no partner");
                }
                if (check.repeated() > 0) {
                    nonManifoldFrames++;
                    mostRepeated = Math.max(mostRepeated, check.repeated());
                }
            }
        }
    }

    /**
     * Meshes the whole field and uploads the result.
     */
    private void remesh() {
        long t0 = System.nanoTime();
        int n = field.cells();
        nets.mesh(field, -HALF, -HALF, -HALF, HALF, HALF, HALF, n, n, n);
        long t1 = System.nanoTime();
        int vertices = nets.vertexCount(), triangles = nets.triangleCount();
        if (vertexData.length < vertices * 6) {
            vertexData = new float[vertices * 6 + 6000];
        }
        float[] p = nets.positions(), nrm = nets.normals();
        boolean haveNormals = options.normals();
        for (int v = 0; v < vertices; v++) {
            vertexData[v * 6] = p[v * 3];
            vertexData[v * 6 + 1] = p[v * 3 + 1];
            vertexData[v * 6 + 2] = p[v * 3 + 2];
            vertexData[v * 6 + 3] = haveNormals ? nrm[v * 3] : 0f;
            vertexData[v * 6 + 4] = haveNormals ? nrm[v * 3 + 1] : 1f;
            vertexData[v * 6 + 5] = haveNormals ? nrm[v * 3 + 2] : 0f;
        }
        long vertexBytes = (long) vertices * 6 * Float.BYTES, indexBytes = (long) triangles * 3 * Integer.BYTES;
        if (vertexBytes > vboCapacity) {
            glDeleteBuffers(vbo);
            vbo = glCreateBuffers();
            vboCapacity = Math.max(vertexBytes * 2, 1 << 20);
            glNamedBufferStorage(vbo, vboCapacity, GL_DYNAMIC_STORAGE_BIT);
            glVertexArrayVertexBuffer(vao, 0, vbo, 0, 6 * Float.BYTES);
            MemoryUtil.memFree(stagedVertices);
            stagedVertices = MemoryUtil.memAllocFloat((int) (vboCapacity / Float.BYTES));
        }
        if (indexBytes > eboCapacity) {
            glDeleteBuffers(ebo);
            ebo = glCreateBuffers();
            eboCapacity = Math.max(indexBytes * 2, 1 << 20);
            glNamedBufferStorage(ebo, eboCapacity, GL_DYNAMIC_STORAGE_BIT);
            glVertexArrayElementBuffer(vao, ebo);
            MemoryUtil.memFree(stagedIndices);
            stagedIndices = MemoryUtil.memAllocInt((int) (eboCapacity / Integer.BYTES));
        }
        stagedVertices.clear();
        stagedVertices.put(vertexData, 0, vertices * 6).flip();
        glNamedBufferSubData(vbo, 0, stagedVertices);
        stagedIndices.clear();
        stagedIndices.put(nets.indices(), 0, triangles * 3).flip();
        glNamedBufferSubData(ebo, 0, stagedIndices);
        long t2 = System.nanoTime();
        meshMs = (t1 - t0) / 1e6;
        uploadMs = (t2 - t1) / 1e6;
        Stats stats = ctx.stats();
        stats.recordNanos(meshSeries, t1 - t0);
        stats.recordNanos(uploadSeries, t2 - t1);
        stats.record(vertexSeries, vertices);
        stats.record(triangleSeries, triangles);
        stats.record(rateSeries, triangles / (meshMs * 1e3));
    }

    @Override
    public void render(FrameInfo frame) {
        ctx.gpu().begin();
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, camera.viewProjection());
        glProgramUniform1i(program, flatLocation, options.normals() ? 0 : 1);
        glBindVertexArray(vao);
        glDrawElements(GL_TRIANGLES, nets.triangleCount() * 3, GL_UNSIGNED_INT, 0L);
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }
        ctx.gpu().end();
        if (pointing) {
            lines.clear();
            lines.setColor(1f, 0.9f, 0.2f, 1f).sphere(hitX, hitY, hitZ, brush, 32);
            ctx.debug().draw(lines, camera.viewProjection());
        }
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "%d^3 grid | %,d vertices, %,d triangles | brush %.2f | %d strokes", options.grid(), nets.vertexCount(), nets.triangleCount(), brush, stamps));
        hud.line(String.format(Locale.ROOT, "mesh %.2f ms (%.1f Mtri/s) | stamp %.3f ms | upload %.2f ms", meshMs, meshMs == 0.0 ? 0.0 : nets.triangleCount() / (meshMs * 1e3), stampMs, uploadMs));
        if (options.verify()) {
            hud.line(String.format(Locale.ROOT, "closed at all %d checks; non-manifold edges in %d of them (most: %d)", checkedFrames, nonManifoldFrames, mostRepeated));
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%d strokes, final mesh %,d vertices and %,d triangles on a %d^3 grid", stamps, nets.vertexCount(), nets.triangleCount(), options.grid()));
        if (options.verify()) {
            stats.note(String.format(Locale.ROOT, "the mesh was closed (no edge without a partner) at all %d checks; %d of them found edges shared by more than two triangles, at most %d", checkedFrames,
                    nonManifoldFrames, mostRepeated));
        }
    }

    @Override
    public void dispose() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteProgram(program);
        MemoryUtil.memFree(stagedVertices);
        MemoryUtil.memFree(stagedIndices);
    }
}
