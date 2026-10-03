package vmath.samples;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetKey;
import static org.lwjgl.glfw.GLFW.glfwGetMouseButton;
import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL45.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL45.GL_BACK;
import static org.lwjgl.opengl.GL45.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL45.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL45.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL45.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL45.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL45.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL45.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL45.GL_RENDERER;
import static org.lwjgl.opengl.GL45.GL_RGBA;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL45.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL45.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL45.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL45.GL_VERSION;
import static org.lwjgl.opengl.GL45.glAttachShader;
import static org.lwjgl.opengl.GL45.glBeginQuery;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindBufferRange;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glClear;
import static org.lwjgl.opengl.GL45.glClearColor;
import static org.lwjgl.opengl.GL45.glClientWaitSync;
import static org.lwjgl.opengl.GL45.glCompileShader;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateProgram;
import static org.lwjgl.opengl.GL45.glCreateQueries;
import static org.lwjgl.opengl.GL45.glCreateShader;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glCullFace;
import static org.lwjgl.opengl.GL45.glDeleteShader;
import static org.lwjgl.opengl.GL45.glDeleteSync;
import static org.lwjgl.opengl.GL45.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glEndQuery;
import static org.lwjgl.opengl.GL45.glFenceSync;
import static org.lwjgl.opengl.GL45.glGetInteger;
import static org.lwjgl.opengl.GL45.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL45.glGetProgrami;
import static org.lwjgl.opengl.GL45.glGetQueryObjecti;
import static org.lwjgl.opengl.GL45.glGetQueryObjectui64;
import static org.lwjgl.opengl.GL45.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL45.glGetShaderi;
import static org.lwjgl.opengl.GL45.glGetString;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glLinkProgram;
import static org.lwjgl.opengl.GL45.glMapNamedBufferRange;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glProgramUniform1ui;
import static org.lwjgl.opengl.GL45.glProgramUniformMatrix4fv;
import static org.lwjgl.opengl.GL45.glReadPixels;
import static org.lwjgl.opengl.GL45.glShaderSource;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;
import static org.lwjgl.opengl.GL45.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.InstanceWriter;
import vmath.gl.VertexBufferLayout;
import vmath.mem.PersistentBufferRing;
import vmath.mesh.Mesh;
import vmath.mesh.MeshExport;
import vmath.mesh.MeshOptimizer;
import vmath.mesh.Primitives;
import vmath.mesh.VertexLayout;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;
import vmath.spatial.ParallelFrustumKernel;

/**
 * A city of one million boxes drawn with OpenGL 4.5 (through LWJGL) and the pieces of vmath that a
 * GPU-driven frame is made of: the box mesh is generated, optimised and exported with the vertex
 * layout; the boxes are culled against the camera frustum on the CPU (optionally on several
 * threads, with the SIMD kernel when it is available); the survivors are written as 64-byte
 * instance records straight into a persistently mapped storage buffer that a ring of regions, one
 * per frame in flight and guarded by fences, shares between the CPU and the GPU; and one indirect
 * draw command, written by the library's draw command buffer, draws them all.
 *
 * <p>Run it interactively with {@code ./gradlew -Psamples :vmath-samples:run}, or as a benchmark
 * with {@code --args="--frames 600"}: a scripted flight over the city, then the time per stage
 * and the allocation per frame, then exit. {@code --help} lists the options. It needs a driver
 * with OpenGL 4.5 (not macOS, which stops at 4.1).
 *
 * <p>Internal: a sample, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that runs {@link #main}, which owns the
 * OpenGL context; only the frustum culling may use worker threads, which it hands back before the
 * frame goes on.
 */
public final class MillionInstances {

    private static final float UNIT_CUBE_HALF = 0.5f;

    private MillionInstances() {
    }

    /**
     * Runs the sample.
     *
     * @param args the command line; see {@code --help}
     * @throws IOException if the screenshot cannot be written
     */
    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.print(Options.USAGE);
            System.exit(2);
            return;
        }
        if (options == null) {
            System.out.print(Options.USAGE);
            return;
        }
        new Run(options).run();
    }

    /**
     * The state of one run: the window, the GPU objects, the scene and the counters.
     */
    private static final class Run {

        private final Options options;
        private long window;
        private int program;
        private int viewProjectionLocation;
        private int groundIdLocation;
        private int vao;
        private int indexCount;
        private int commandBuffer;
        private int instanceBuffer;
        private final int[] queries = new int[4];

        Run(Options options) {
            this.options = options;
        }

        void run() throws IOException {
            System.out.printf("building the city of %,d boxes ...%n", options.instances());
            BoundsArray bounds = City.build(options.instances());
            VisibilitySet visible = new VisibilitySet(bounds.size());
            ExecutorService executor = options.threads() > 1 ? Executors.newFixedThreadPool(options.threads(), r -> {
                Thread t = new Thread(r, "frustum-culling");
                t.setDaemon(true);
                return t;
            }) : null;
            FrustumKernel kernel = executor != null ? new ParallelFrustumKernel(executor, options.threads()) : FrustumKernels.best();
            CullPipeline pipeline = CullPipeline.of(new CullStages.Frustum(kernel));
            System.out.println("frustum kernel: " + kernel.name());

            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                throw new IllegalStateException("cannot initialise GLFW");
            }
            try (Arena arena = Arena.ofConfined()) {
                createWindow();
                System.out.println("OpenGL " + glGetString(GL_VERSION) + " on " + glGetString(GL_RENDERER));
                buildProgram();
                Mesh cube = Primitives.box(UNIT_CUBE_HALF, UNIT_CUBE_HALF, UNIT_CUBE_HALF);
                MeshOptimizer.optimizeVertexCache(cube, 32);
                VertexLayout layout = VertexLayout.builder().position().normal().build();
                buildMesh(arena, cube, layout);

                long recordBytes = InstanceWriter.STRIDE;
                int alignment = Math.max(256, glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
                long regionBytes = (bounds.size() * recordBytes + alignment - 1) / alignment * alignment;
                long totalBytes = regionBytes * options.framesInFlight();
                int flags = GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
                instanceBuffer = glCreateBuffers();
                glNamedBufferStorage(instanceBuffer, totalBytes, flags);
                ByteBuffer mapped = glMapNamedBufferRange(instanceBuffer, 0, totalBytes, flags);
                MemorySegment mappedSegment = MemorySegment.ofBuffer(mapped);
                System.out.printf("instance buffer: %d regions of %,d bytes (%.0f MB mapped)%n", options.framesInFlight(), regionBytes, totalBytes / 1e6);

                MemorySegment commandSegment = arena.allocate(DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false), 16);
                ByteBuffer commandBytes = commandSegment.asByteBuffer();
                DrawCommandBuffer commands = new DrawCommandBuffer(commandSegment, DrawCommandBuffer.Kind.ELEMENTS, false);
                commandBuffer = glCreateBuffers();
                glNamedBufferStorage(commandBuffer, commandSegment.byteSize(), GL_DYNAMIC_STORAGE_BIT);
                for (int i = 0; i < queries.length; i++) {
                    queries[i] = glCreateQueries(GL_TIME_ELAPSED);
                }

                try (PersistentBufferRing<Long> ring = new PersistentBufferRing<>(mappedSegment, options.framesInFlight(), alignment, new Fences())) {
                    loop(bounds, visible, pipeline, ring, mappedSegment, commands, commandBytes);
                }
            } finally {
                glfwDestroyWindow(window);
                glfwTerminate();
                if (executor != null) {
                    executor.shutdownNow();
                }
            }
        }

        private void createWindow() {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            window = glfwCreateWindow(options.width(), options.height(), "vmath: a million instances", NULL, NULL);
            if (window == NULL) {
                throw new IllegalStateException("cannot create an OpenGL 4.5 window; this sample needs a driver with OpenGL 4.5");
            }
            glfwMakeContextCurrent(window);
            glfwSwapInterval(options.vsync() ? 1 : 0);
            GL.createCapabilities();
            glfwShowWindow(window);
        }

        private void buildProgram() {
            VertexLayout layout = VertexLayout.builder().position().normal().build();
            int vs = compile(GL_VERTEX_SHADER, Shaders.vertex(layout));
            int fs = compile(GL_FRAGMENT_SHADER, Shaders.fragment());
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == 0) {
                throw new IllegalStateException("cannot link the program: " + glGetProgramInfoLog(program));
            }
            glDeleteShader(vs);
            glDeleteShader(fs);
            viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
            groundIdLocation = glGetUniformLocation(program, "groundId");
        }

        private static int compile(int type, String source) {
            int shader = glCreateShader(type);
            glShaderSource(shader, source);
            glCompileShader(shader);
            if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException("cannot compile a shader: " + glGetShaderInfoLog(shader) + "\n" + source);
            }
            return shader;
        }

        /**
         * Exports the mesh with the vertex layout into GPU buffers and describes the layout to a
         * vertex array object.
         *
         * @param arena owns the memory the mesh is exported into before it is copied to the GPU;
         *     must not be {@code null}
         * @param mesh the mesh; must not be {@code null}
         * @param layout the vertex layout the mesh is exported with; must not be {@code null}
         */
        private void buildMesh(Arena arena, Mesh mesh, VertexLayout layout) {
            MemorySegment vertices = arena.allocate(MeshExport.vertexBytes(mesh, layout), 16);
            MeshExport.writeVertices(mesh, layout, vertices, 0);
            MemorySegment indices = arena.allocate(MeshExport.indexBytes32(mesh), 16);
            MeshExport.writeIndices32(mesh, indices, 0);
            indexCount = mesh.indexCount();
            int vbo = glCreateBuffers();
            glNamedBufferStorage(vbo, vertices.asByteBuffer(), 0);
            int ebo = glCreateBuffers();
            glNamedBufferStorage(ebo, indices.asByteBuffer(), 0);
            vao = glCreateVertexArrays();
            VertexBufferLayout bufferLayout = layout.toBufferLayout();
            glVertexArrayVertexBuffer(vao, 0, vbo, 0, bufferLayout.stride());
            glVertexArrayElementBuffer(vao, ebo);
            List<VertexBufferLayout.GlFormat> formats = bufferLayout.glFormats();
            for (VertexBufferLayout.GlFormat f : formats) {
                glEnableVertexArrayAttrib(vao, f.location());
                if (f.integer()) {
                    glVertexArrayAttribIFormat(vao, f.location(), f.size(), f.type(), f.relativeOffset());
                } else {
                    glVertexArrayAttribFormat(vao, f.location(), f.size(), f.type(), f.normalized(), f.relativeOffset());
                }
                glVertexArrayAttribBinding(vao, f.location(), 0);
            }
            System.out.printf("mesh: %d vertices, %d indices, %d bytes per vertex%n", mesh.vertexCount(), indexCount, bufferLayout.stride());
        }

        private void loop(BoundsArray bounds, VisibilitySet visible, CullPipeline pipeline, PersistentBufferRing<Long> ring, MemorySegment mapped,
                DrawCommandBuffer commands, ByteBuffer commandBytes) throws IOException {
            int count = bounds.size(); // the boxes and the ground
            boolean benchmark = options.frames() > 0;
            float side = (float) Math.ceil(Math.sqrt(count)) * City.SPACING;
            FlyCamera fly = new FlyCamera(new Vec3f(0f, side * 0.06f + 40f, -side * 0.5f), 0f, -0.1f);
            boolean culling = options.culling();
            boolean vsync = options.vsync();
            com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().threadId();

            long waitNs = 0, cullNs = 0, writeNs = 0, submitNs = 0, frameNs = 0, gpuNs = 0, allocated = 0, visibleSum = 0;
            int measured = 0, gpuSamples = 0;
            int titleFrames = 0;
            double titleTime = glfwGetTime(), lastTime = titleTime;
            double[] mx0 = new double[1], my0 = new double[1];
            glfwGetCursorPos(window, mx0, my0);
            double lastMx = mx0[0], lastMy = my0[0];
            boolean cWas = false, vWas = false;
            int[] size = new int[2], sizeY = new int[2];
            int frame = 0;
            long wallStart = System.nanoTime();

            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);
            glClearColor(0.55f, 0.7f, 0.88f, 1f);

            while (!glfwWindowShouldClose(window) && (!benchmark || frame < options.warmup() + options.frames())) {
                long frameStart = System.nanoTime();
                glfwPollEvents();
                double now = glfwGetTime();
                float dt = (float) Math.min(0.1, now - lastTime);
                lastTime = now;
                glfwGetFramebufferSize(window, size, sizeY);
                int width = Math.max(1, size[0]), height = Math.max(1, sizeY[0]);

                if (benchmark) {
                    fly.scripted(frame, side * 0.28f, 150f);
                } else {
                    if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) {
                        glfwSetWindowShouldClose(window, true);
                    }
                    glfwGetCursorPos(window, mx0, my0);
                    if (glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS) {
                        fly.look(mx0[0] - lastMx, my0[0] - lastMy);
                    }
                    lastMx = mx0[0];
                    lastMy = my0[0];
                    float speed = (glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS ? 600f : 120f) * dt;
                    fly.move(speed * (key(GLFW_KEY_W) - key(GLFW_KEY_S)), speed * (key(GLFW_KEY_D) - key(GLFW_KEY_A)),
                            speed * (key(GLFW_KEY_SPACE) - key(GLFW_KEY_LEFT_CONTROL)));
                    if (key(GLFW_KEY_R) > 0) {
                        fly.reset();
                    }
                    boolean c = key(GLFW_KEY_C) > 0, v = key(GLFW_KEY_V) > 0;
                    if (c && !cWas) {
                        culling = !culling;
                    }
                    if (v && !vWas) {
                        vsync = !vsync;
                        glfwSwapInterval(vsync ? 1 : 0);
                    }
                    cWas = c;
                    vWas = v;
                }
                Cameraf camera = fly.camera((float) width / height);

                long a0 = mx.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                ring.beginFrame();
                long tw = System.nanoTime();
                int n;
                if (culling) {
                    CullContext ctx = CullContext.perspective(camera.frustum(), camera.position(), FlyCamera.fovy(), height);
                    n = pipeline.run(ctx, bounds, visible);
                } else {
                    visible.setAll(count);
                    n = count;
                }
                long t1 = System.nanoTime();
                long regionOffset = ring.regionOffset();
                int written = InstanceWriter.writeVisibleBoxes(mapped, regionOffset / InstanceWriter.STRIDE, visible, bounds);
                commands.clear();
                commands.addElements(indexCount, written, 0, 0, 0);
                long t2 = System.nanoTime();

                int query = queries[frame % queries.length];
                glViewport(0, 0, width, height);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glBeginQuery(GL_TIME_ELAPSED, query);
                glUseProgram(program);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer m = stack.mallocFloat(16);
                    camera.viewProjection().writeTo(m, 0);
                    glProgramUniformMatrix4fv(program, viewProjectionLocation, false, m);
                    glProgramUniform1ui(program, groundIdLocation, count - 1);
                }
                glNamedBufferSubData(commandBuffer, 0, commandBytes);
                glBindVertexArray(vao);
                glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, instanceBuffer, regionOffset, Math.max(InstanceWriter.STRIDE, written * InstanceWriter.STRIDE));
                glDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L);
                glEndQuery(GL_TIME_ELAPSED);
                ring.endFrame();
                long t3 = System.nanoTime();
                long a1 = mx.getThreadAllocatedBytes(tid);

                // the query of a few frames ago is read when it is ready, which keeps the CPU from waiting for the GPU
                long gpu = -1;
                if (frame >= queries.length) {
                    int old = queries[(frame + 1) % queries.length];
                    if (glGetQueryObjecti(old, GL_QUERY_RESULT_AVAILABLE) != 0) {
                        gpu = glGetQueryObjectui64(old, GL_QUERY_RESULT);
                    }
                }
                glfwSwapBuffers(window);
                long frameEnd = System.nanoTime();

                if (frame >= options.warmup() || !benchmark) {
                    waitNs += tw - t0;
                    cullNs += t1 - tw;
                    writeNs += t2 - t1;
                    submitNs += t3 - t2;
                    frameNs += frameEnd - frameStart;
                    allocated += a1 - a0;
                    visibleSum += n;
                    measured++;
                    if (gpu >= 0) {
                        gpuNs += gpu;
                        gpuSamples++;
                    }
                }
                titleFrames++;
                if (!benchmark && now - titleTime >= 0.5) {
                    double fps = titleFrames / (now - titleTime);
                    glfwSetWindowTitle(window, String.format("vmath: %,d of %,d instances | %.0f fps | cull %.2f ms | write %.2f ms | gpu %s | culling %s, vsync %s",
                            n, count, fps, (t1 - tw) / 1e6, (t2 - t1) / 1e6, gpu >= 0 ? String.format("%.2f ms", gpu / 1e6) : "-", culling ? "on" : "off",
                            vsync ? "on" : "off"));
                    titleTime = now;
                    titleFrames = 0;
                }
                frame++;
            }
            long wall = System.nanoTime() - wallStart;
            if (options.screenshot() != null) {
                screenshot(options.screenshot());
            }
            ring.drain();
            if (measured > 0) {
                System.out.printf("%n%,d instances, window %d x %d, %d measured frames after %d warm-up frames, culling %s%n", count, options.width(), options.height(), measured,
                        benchmark ? options.warmup() : 0, culling ? "on" : "off");
                System.out.printf("visible per frame   %,12.0f%n", (double) visibleSum / measured);
                System.out.printf("wait for the ring    %9.3f ms   (the fence of the region, before it is written again)%n", waitNs / 1e6 / measured);
                System.out.printf("cull                %9.3f ms   (frustum, %d thread%s)%n", cullNs / 1e6 / measured, options.threads(), options.threads() == 1 ? "" : "s");
                System.out.printf("write instances     %9.3f ms   (%.1f MB per frame)%n", writeNs / 1e6 / measured, visibleSum / (double) measured * InstanceWriter.STRIDE / 1e6);
                System.out.printf("submit              %9.3f ms   (uniforms, indirect command, draw call, fence)%n", submitNs / 1e6 / measured);
                System.out.printf("gpu                 %9s ms   (GL_TIME_ELAPSED of the draw, %d samples)%n", gpuSamples > 0 ? String.format("%.3f", gpuNs / 1e6 / gpuSamples) : "-", gpuSamples);
                System.out.printf("frame               %9.3f ms   (%.1f fps; %s)%n", frameNs / 1e6 / measured, 1e9 * measured / frameNs, options.vsync() ? "vsync on" : "vsync off");
                System.out.printf("allocated on the render thread %,.0f B per frame (camera, cull, write and submit); stalls waiting for the GPU: %d%n", (double) allocated / measured,
                        ring.stalls());
                System.out.printf("wall time %.1f s%n", wall / 1e9);
            }
        }

        private float key(int key) {
            return glfwGetKey(window, key) == GLFW_PRESS ? 1f : 0f;
        }

        private void screenshot(String file) throws IOException {
            int[] w = new int[1], h = new int[1];
            glfwGetFramebufferSize(window, w, h);
            ByteBuffer pixels = org.lwjgl.system.MemoryUtil.memAlloc(w[0] * h[0] * 4);
            try {
                glReadPixels(0, 0, w[0], h[0], GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                BufferedImage image = new BufferedImage(w[0], h[0], BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h[0]; y++) {
                    for (int x = 0; x < w[0]; x++) {
                        int i = ((h[0] - 1 - y) * w[0] + x) * 4;
                        int r = pixels.get(i) & 255, g = pixels.get(i + 1) & 255, b = pixels.get(i + 2) & 255;
                        image.setRGB(x, y, (r << 16) | (g << 8) | b);
                    }
                }
                ImageIO.write(image, "png", new File(file));
                System.out.println("screenshot: " + file);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(pixels);
            }
        }
    }

    /**
     * The fence operations of OpenGL for the ring of instance regions: a sync object per frame.
     */
    private static final class Fences implements PersistentBufferRing.FenceOps<Long> {

        @Override
        public Long insert() {
            return glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        @Override
        public boolean isSignaled(Long fence) {
            int status = glClientWaitSync(fence, 0, 0L);
            return status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED;
        }

        @Override
        public void await(Long fence) {
            int status;
            do {
                status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            } while (status != GL_ALREADY_SIGNALED && status != GL_CONDITION_SATISFIED);
        }

        @Override
        public void release(Long fence) {
            glDeleteSync(fence);
        }
    }
}
