package vmath.samples.demos.shadows;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_L;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;
import static org.lwjgl.opengl.GL45.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL45.GL_COMPARE_REF_TO_TEXTURE;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DEPTH;
import static org.lwjgl.opengl.GL45.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL45.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL45.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL45.GL_LEQUAL;
import static org.lwjgl.opengl.GL45.GL_LINEAR;
import static org.lwjgl.opengl.GL45.GL_NONE;
import static org.lwjgl.opengl.GL45.GL_POLYGON_OFFSET_FILL;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBufferRange;
import static org.lwjgl.opengl.GL45.glBindFramebuffer;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateFramebuffers;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glDepthMask;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glGetInteger;
import static org.lwjgl.opengl.GL45.glGetTextureImage;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glNamedFramebufferDrawBuffer;
import static org.lwjgl.opengl.GL45.glNamedFramebufferReadBuffer;
import static org.lwjgl.opengl.GL45.glNamedFramebufferTextureLayer;
import static org.lwjgl.opengl.GL45.glPolygonOffset;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1fv;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform1ui;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glProgramUniformMatrix4fv;
import static org.lwjgl.opengl.GL45.glTextureParameterfv;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage3D;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glViewport;

import java.lang.foreign.MemorySegment;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.lighting.Cascades;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.Aabbf;
import vmath.geo.DepthRange;
import vmath.geo.Intersectionf;
import vmath.geo.Rayf;
import vmath.gl.InstanceWriter;
import vmath.mesh.VertexLayout;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.GpuMesh;
import vmath.samples.framework.GpuTimer;
import vmath.samples.framework.Hud;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumKernels;
import vmath.util.DebugLines;
import vmath.util.Rng;

/**
 * A city lit by a moving sun with cascaded shadow maps: the view range is split into slices, each
 * slice gets its own light-space orthographic projection and shadow map, and only the boxes that can
 * cast a shadow into a slice are drawn into its map.
 *
 * <p>The pieces of the library: {@code Cascades.fitAll} (the splits and the stabilised, texel-snapped
 * projections), {@code Cascade.frustum()} and {@code CascadeCasters} (the two culling stages of every
 * map), {@code Cascade.textureMatrix()} (world to shadow-map coordinates and depth, used by the
 * shader as it is), the frustum kernel for the view, and {@code DebugLines.frustum} for the volumes.
 * The maps are the layers of one depth array texture that a shadow sampler reads with hardware
 * comparison; a pass draws the casters of one cascade with one instanced call.
 *
 * <p>{@code C} tints the scene by cascade, {@code L} turns the footprint test of
 * {@code CascadeCasters} off (every box in the frustum of a cascade is drawn into its map, which
 * shows what the test saves), {@code X} freezes the camera that the cascades are fitted to and draws
 * their volumes (fly away with {@code G} to see them), {@code P} stops the sun and {@code G} switches
 * between the scripted flight and the free camera.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class ShadowDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("cascaded-shadows", "Cascaded shadow maps with caster culling",
            "A moving sun over a city with four cascades fitted by the library, each drawing only the boxes that can shadow its slice into one layer of a depth array.",
            List.of("rendering", "culling"), 16384, List.of("--blocks", "12", "--props", "10", "--map", "512"),
            "G: free camera (W A S D, Space, Shift, mouse) | C: tint by cascade | L: caster footprint test | X: freeze cascades and draw them | P: stop the sun");

    private static final float FOVY = 1.0f;
    private static final float NEAR = 0.5f;
    private static final float FAR = 1500f;
    private static final float NORMAL_OFFSET_TEXELS = 1.5f;
    private static final float DEPTH_BIAS = 0.0005f;
    private static final float[] CLEAR_DEPTH = {1f};

    private final ShadowOptions options;
    private DemoContext ctx;
    private ShadowScene scene;
    private FlyCamera fly;
    private GpuMesh cube;
    private int shadowProgram;
    private int sceneProgram;
    private int objectBuffer;
    private int listBuffer;
    private long listStride;
    private int shadowTexture;
    private int shadowFramebuffer;
    private IntBuffer listStaging;
    private FloatBuffer matrixStaging;
    private FloatBuffer floatStaging;
    private VisibilitySet viewVisible;
    private CullPipeline viewPipeline;
    private GpuTimer shadowTimer;
    private Cameraf camera;
    private Cameraf fitCamera;
    private Vec3f lightDirection;
    private int sceneDrawn;
    private int shadowDrawn;
    private int frameNumber;
    private int castersOutsideView;
    private double fitMs;
    private double timeOfDay;
    private boolean free;
    private boolean tint;
    private boolean show;
    private boolean footprint;
    private boolean frozen;
    private boolean sunStopped;
    private int sceneLocationViewProjection;
    private int sceneLocationGround;
    private int sceneLocationShadowMatrix;
    private int sceneLocationFar;
    private int sceneLocationOffset;
    private int sceneLocationCount;
    private int sceneLocationTexel;
    private int sceneLocationToLight;
    private int sceneLocationTint;
    private int shadowLocationViewProjection;
    private int fitSeries;
    private int shadowGpuSeries;
    private int viewSeries;
    private int castersSeries;
    private int outsideSeries;
    private int sceneDrawnSeries;
    private int allBoxesSeries;
    private int[] casterSeries;
    private long verifyMismatches;
    private long verifyCompared;
    private String verifyNote = "";

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public ShadowDemo(List<String> args) {
        this.options = ShadowOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        scene = new ShadowScene(options.blocks(), options.props(), options.cascades(), options.map(), options.lambda(), options.distance(), options.stabilize());
        BoundsArray bounds = scene.bounds();
        System.out.printf(Locale.ROOT, "%,d boxes (the last is the ground), %d cascades of %d x %d texels, shadow distance %.0f m%n", bounds.size(), options.cascades(), options.map(), options.map(),
                options.distance());
        float half = options.blocks() * 24f * 0.5f;
        fly = new FlyCamera(new Vec3f(0f, 30f, -half * 0.5f), 0f, -0.2f, FOVY, NEAR, FAR, 40f, 160f);
        viewVisible = new VisibilitySet(bounds.size());
        viewPipeline = CullPipeline.of(new CullStages.Frustum(FrustumKernels.scalar()));
        cube = BoxRenderer.createCube(ctx);
        VertexLayout layout = VertexLayout.builder().position().normal().build();
        shadowProgram = Gl.program(ShadowShaders.shadowVertex(layout), ShadowShaders.shadowFragment());
        sceneProgram = Gl.program(ShadowShaders.sceneVertex(layout), ShadowShaders.sceneFragment());
        shadowLocationViewProjection = glGetUniformLocation(shadowProgram, "lightViewProjection");
        sceneLocationViewProjection = glGetUniformLocation(sceneProgram, "viewProjection");
        sceneLocationGround = glGetUniformLocation(sceneProgram, "groundId");
        sceneLocationShadowMatrix = glGetUniformLocation(sceneProgram, "shadowMatrix");
        sceneLocationFar = glGetUniformLocation(sceneProgram, "cascadeFar");
        sceneLocationOffset = glGetUniformLocation(sceneProgram, "normalOffset");
        sceneLocationCount = glGetUniformLocation(sceneProgram, "cascadeCount");
        sceneLocationTexel = glGetUniformLocation(sceneProgram, "texel");
        sceneLocationToLight = glGetUniformLocation(sceneProgram, "toLight");
        sceneLocationTint = glGetUniformLocation(sceneProgram, "tintCascades");

        MemorySegment objects = ctx.arena().allocate(bounds.size() * InstanceWriter.STRIDE, 16);
        for (int i = 0; i < bounds.size(); i++) {
            InstanceWriter.writeBox(objects, i, 0.5f * (bounds.minX(i) + bounds.maxX(i)), 0.5f * (bounds.minY(i) + bounds.maxY(i)), 0.5f * (bounds.minZ(i) + bounds.maxZ(i)),
                    bounds.maxX(i) - bounds.minX(i), bounds.maxY(i) - bounds.minY(i), bounds.maxZ(i) - bounds.minZ(i), i);
        }
        objectBuffer = glCreateBuffers();
        glNamedBufferStorage(objectBuffer, objects.asByteBuffer(), 0);
        long alignment = Math.max(256, glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT));
        listStride = (4L * bounds.size() + alignment - 1) / alignment * alignment;
        listBuffer = glCreateBuffers();
        glNamedBufferStorage(listBuffer, listStride * (options.cascades() + 1), GL_DYNAMIC_STORAGE_BIT);
        listStaging = MemoryUtil.memAllocInt(bounds.size());
        matrixStaging = MemoryUtil.memAllocFloat(16 * 8);
        floatStaging = MemoryUtil.memAllocFloat(8);

        shadowTexture = createShadowTexture();
        shadowFramebuffer = glCreateFramebuffers();
        glNamedFramebufferDrawBuffer(shadowFramebuffer, GL_NONE);
        glNamedFramebufferReadBuffer(shadowFramebuffer, GL_NONE);
        glNamedFramebufferTextureLayer(shadowFramebuffer, GL_DEPTH_ATTACHMENT, shadowTexture, 0, 0);
        if (glCheckNamedFramebufferStatus(shadowFramebuffer, GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("the shadow framebuffer is incomplete");
        }
        shadowTimer = new GpuTimer();

        Stats stats = ctx.stats();
        fitSeries = stats.timer("fit and cull", "cascades fitted, view and casters culled on the CPU");
        shadowGpuSeries = stats.timer("shadow passes", "GPU time of all the shadow maps");
        viewSeries = stats.series("boxes in view", "", 0, "after the frustum test of the camera");
        castersSeries = stats.series("casters, all cascades", "", 0, "drawn into the shadow maps");
        outsideSeries = stats.series("casters outside the view", "", 0, "boxes that shadow the view from out of it");
        sceneDrawnSeries = stats.series("boxes drawn in the scene", "", 0, "");
        allBoxesSeries = stats.series("boxes in the city", "", 0, "");
        casterSeries = new int[options.cascades()];
        for (int i = 0; i < casterSeries.length; i++) {
            casterSeries[i] = stats.series("casters, cascade " + i, "", 0, "of the boxes in its frustum");
        }
        ctx.clearColor(0.62f, 0.74f, 0.90f);
        footprint = options.footprint();
        tint = options.tint();
        show = options.show();
        timeOfDay = 0.0;
        Warmup.untilQuiet(() -> {
            Cameraf c = placeScripted(0);
            scene.fit(c, ShadowScene.sunDirection(0.0), true);
        }, 3000);
        if (options.verify()) {
            verify();
        }
    }

    private int createShadowTexture() {
        int texture = glCreateTextures(GL_TEXTURE_2D_ARRAY);
        glTextureStorage3D(texture, 1, GL_DEPTH_COMPONENT32F, options.map(), options.map(), options.cascades());
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_BORDER);
        glTextureParameterfv(texture, GL_TEXTURE_BORDER_COLOR, new float[] {1f, 1f, 1f, 1f});
        glTextureParameteri(texture, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTextureParameteri(texture, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
        return texture;
    }

    // a slow circle above the roofs, looking down at the city, so that the shadows of every cascade are in view
    private Cameraf placeScripted(int frame) {
        float half = options.blocks() * 24f * 0.5f;
        float angle = frame * 0.004f, radius = half * 0.45f;
        fly.place(radius * (float) Math.sin(angle), 70f, -radius * (float) Math.cos(angle), angle + (float) Math.PI + 0.35f, -0.30f);
        return fly.camera(16f / 9f);
    }

    @Override
    public void update(FrameInfo frame) {
        frameNumber = frame.frame();
        if (!frame.benchmark()) {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_G)) {
                free = !free;
            }
            if (in.pressed(GLFW_KEY_C)) {
                tint = !tint;
            }
            if (in.pressed(GLFW_KEY_L)) {
                footprint = !footprint;
            }
            if (in.pressed(GLFW_KEY_P)) {
                sunStopped = !sunStopped;
            }
            if (in.pressed(GLFW_KEY_X)) {
                frozen = !frozen;
                show = frozen;
                if (frozen) {
                    fitCamera = camera;
                }
            }
        }
        if (free && !frame.benchmark()) {
            fly.update(frame);
        } else {
            placeScripted(frame.frame());
        }
        camera = fly.camera(frame.aspect());
        if (!sunStopped) {
            timeOfDay = frame.time();
        }
        lightDirection = ShadowScene.sunDirection(timeOfDay);
        Cameraf basis = frozen && fitCamera != null ? fitCamera : camera;
        long t0 = System.nanoTime();
        scene.fit(basis, lightDirection, footprint);
        viewVisible.clearAll();
        viewVisible.setAll(scene.size());
        sceneDrawn = viewPipeline.run(CullContext.perspective(camera.frustum(), camera.position(), camera.fovy(), frame.height()), scene.bounds(), viewVisible);
        fitMs = (System.nanoTime() - t0) / 1e6;

        int total = 0;
        castersOutsideView = 0;
        for (int i = 0; i < options.cascades(); i++) {
            VisibilitySet set = scene.casters(i);
            int n = 0;
            for (int b = set.nextSetBit(0); b >= 0 && b < scene.size(); b = set.nextSetBit(b + 1)) {
                if (!scene.viewVisible().get(b)) {
                    castersOutsideView++;
                }
                n++;
            }
            total += n;
        }
        shadowDrawn = total;
        uploadList(options.cascades(), viewVisible);
        for (int i = 0; i < options.cascades(); i++) {
            uploadList(i, scene.casters(i));
        }
        Stats stats = ctx.stats();
        stats.recordNanos(fitSeries, (long) (fitMs * 1e6));
        stats.record(viewSeries, scene.viewCount());
        stats.record(castersSeries, shadowDrawn);
        stats.record(outsideSeries, castersOutsideView);
        stats.record(sceneDrawnSeries, sceneDrawn);
        stats.record(allBoxesSeries, scene.size());
        for (int i = 0; i < casterSeries.length; i++) {
            stats.record(casterSeries[i], scene.casterCount(i));
        }
    }

    // writes the indices of the set bits as the list of a pass, region 0..cascades-1 for the cascades and the last for the view
    private int uploadList(int region, VisibilitySet set) {
        listStaging.clear();
        int n = 0;
        for (int b = set.nextSetBit(0); b >= 0 && b < scene.size(); b = set.nextSetBit(b + 1)) {
            listStaging.put(b);
            n++;
        }
        listStaging.flip();
        if (n > 0) {
            glNamedBufferSubData(listBuffer, region * listStride, listStaging);
        }
        return n;
    }

    @Override
    public void render(FrameInfo frame) {
        shadowTimer.begin();
        renderShadowMaps(shadowTexture, false);
        shadowTimer.end();

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, frame.width(), frame.height());
        ctx.gpu().begin();
        glUseProgram(sceneProgram);
        Gl.uniform(sceneProgram, sceneLocationViewProjection, camera.viewProjection());
        glProgramUniform1ui(sceneProgram, sceneLocationGround, scene.groundIndex());
        List<Cascades.Cascade> cascades = scene.cascades();
        int n = cascades.size();
        matrixStaging.clear();
        for (int i = 0; i < n; i++) {
            cascades.get(i).textureMatrix().writeTo(matrixStaging, 16 * i);
        }
        matrixStaging.position(0).limit(16 * n);
        glProgramUniformMatrix4fv(sceneProgram, sceneLocationShadowMatrix, false, matrixStaging);
        matrixStaging.clear();
        floatStaging.clear();
        for (int i = 0; i < n; i++) {
            floatStaging.put(i, cascades.get(i).sliceFar());
        }
        floatStaging.position(0).limit(n);
        glProgramUniform1fv(sceneProgram, sceneLocationFar, floatStaging);
        floatStaging.clear();
        for (int i = 0; i < n; i++) {
            floatStaging.put(i, cascades.get(i).texelSize() * NORMAL_OFFSET_TEXELS);
        }
        floatStaging.position(0).limit(n);
        glProgramUniform1fv(sceneProgram, sceneLocationOffset, floatStaging);
        floatStaging.clear();
        glProgramUniform1i(sceneProgram, sceneLocationCount, n);
        glProgramUniform1f(sceneProgram, sceneLocationTexel, 1f / options.map());
        Vec3f toLight = scene.light().negate();
        glProgramUniform3f(sceneProgram, sceneLocationToLight, toLight.x(), toLight.y(), toLight.z());
        glProgramUniform1i(sceneProgram, sceneLocationTint, tint ? 1 : 0);
        glBindTextureUnit(0, shadowTexture);
        glBindVertexArray(cube.vao());
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, objectBuffer, 0, (long) scene.size() * InstanceWriter.STRIDE);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, listBuffer, n * listStride, 4L * Math.max(1, sceneDrawn));
        glDrawElementsInstanced(GL_TRIANGLES, cube.indexCount(), GL_UNSIGNED_INT, 0L, sceneDrawn);
        ctx.gpu().end();

        if (frozen || show) {
            DebugLines lines = new DebugLines(256);
            int[] colors = {DebugLines.RED, DebugLines.GREEN, DebugLines.BLUE, DebugLines.YELLOW, DebugLines.MAGENTA, DebugLines.CYAN, DebugLines.WHITE, DebugLines.WHITE};
            for (int i = 0; i < n; i++) {
                lines.setColor(colors[i]);
                lines.frustum(cascades.get(i).viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE, 1e6f);
            }
            if (frozen) {
                lines.setColor(DebugLines.WHITE);
                lines.frustum(fitCamera.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE, options.distance());
            }
            ctx.debug().draw(lines, camera.viewProjection());
        }
        long s = shadowTimer.poll();
        if (s >= 0) {
            ctx.stats().recordNanos(shadowGpuSeries, s);
        }
    }

    /**
     * Draws the casters of every cascade into the layers of a shadow array.
     *
     * @param texture the depth array texture
     * @param everything whether to draw every box of the city instead of the casters, which is how
     *     the verification gets the maps that culling must not change
     */
    private void renderShadowMaps(int texture, boolean everything) {
        glUseProgram(shadowProgram);
        glBindVertexArray(cube.vao());
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, objectBuffer, 0, (long) scene.size() * InstanceWriter.STRIDE);
        glBindFramebuffer(GL_FRAMEBUFFER, shadowFramebuffer);
        glViewport(0, 0, options.map(), options.map());
        // verification runs in create(), before the runner has set the state of a frame: a depth buffer is only written with the test on
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(2f, 4f);
        List<Cascades.Cascade> cascades = scene.cascades();
        for (int i = 0; i < cascades.size(); i++) {
            glNamedFramebufferTextureLayer(shadowFramebuffer, GL_DEPTH_ATTACHMENT, texture, 0, i);
            glClearNamedFramebufferfv(shadowFramebuffer, GL_DEPTH, 0, CLEAR_DEPTH);
            Gl.uniform(shadowProgram, shadowLocationViewProjection, cascades.get(i).viewProjection());
            int count;
            if (everything) {
                listStaging.clear();
                for (int b = 0; b < scene.size(); b++) {
                    listStaging.put(b);
                }
                listStaging.flip();
                glNamedBufferSubData(listBuffer, (cascades.size()) * listStride, listStaging);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, listBuffer, cascades.size() * listStride, 4L * scene.size());
                count = scene.size();
            } else {
                count = scene.casterCount(i);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, listBuffer, i * listStride, 4L * Math.max(1, count));
            }
            if (count > 0) {
                glDrawElementsInstanced(GL_TRIANGLES, cube.indexCount(), GL_UNSIGNED_INT, 0L, count);
            }
        }
        glDisable(GL_POLYGON_OFFSET_FILL);
        glEnable(GL_CULL_FACE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "%,d boxes in the city, %,d in view | sun %.0f degrees up | %s", scene.size(), scene.viewCount(), Math.toDegrees(Math.asin(-lightDirection.y())),
                frozen ? "cascades FROZEN (X)" : "cascades follow the camera"));
        List<Cascades.Cascade> cascades = scene.cascades();
        for (int i = 0; i < cascades.size(); i++) {
            Cascades.Cascade c = cascades.get(i);
            hud.line(String.format(Locale.ROOT, "cascade %d: %.1f to %.1f m, texel %.3f m | %,d in frustum, %,d casters%s", i, c.sliceNear(), c.sliceFar(), c.texelSize(), scene.frustumOnlyCount(i),
                    scene.casterCount(i), footprint ? "" : " (footprint test off)"));
        }
        hud.line(String.format(Locale.ROOT, "%,d casters in all, %,d of them outside the view | fit and cull %.3f ms", shadowDrawn, castersOutsideView, fitMs));
        if (!verifyNote.isEmpty()) {
            hud.line(verifyNote);
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%d cascades of %d x %d texels, lambda %.2f, shadow distance %.0f m, %s, %,d boxes", options.cascades(), options.map(), options.map(), options.lambda(),
                options.distance(), options.stabilize() ? "stabilised" : "tight fit", scene.size()));
        List<Cascades.Cascade> cascades = scene.cascades();
        for (int i = 0; i < cascades.size(); i++) {
            Cascades.Cascade c = cascades.get(i);
            stats.note(String.format(Locale.ROOT, "cascade %d: slice %.1f to %.1f m, texel %.3f m", i, c.sliceNear(), c.sliceFar(), c.texelSize()));
        }
        if (verifyCompared > 0) {
            stats.note(verifyNote);
        }
    }

    // ---------------------------------------------------------------- verification

    /**
     * Checks the culling and the lookup at three views: the casters that the culling kept against
     * rays traced from points of every slice, the maps drawn from the casters against the maps drawn
     * from every box, and the lookup in the maps (with the library's texture matrix) against a ray
     * from the surface point to the sun. Throws if any of them fails.
     */
    private void verify() {
        int n = options.cascades(), map = options.map();
        int[] frames = {0, 400, 900};
        FloatBuffer culled = MemoryUtil.memAllocFloat(n * map * map);
        FloatBuffer all = MemoryUtil.memAllocFloat(n * map * map);
        int allTexture = createShadowTexture();
        long totalShadowed = 0, totalMissed = 0, lookups = 0, lookupMismatch = 0, lookupStrict = 0;
        int mapsDifferent = 0;
        try {
            for (int f : frames) {
                Cameraf cam = placeScripted(f);
                Vec3f light = ShadowScene.sunDirection(f / 60.0);
                scene.fit(cam, light, true);
                for (int i = 0; i < n; i++) {
                    uploadList(i, scene.casters(i));
                }
                for (int i = 0; i < n; i++) {
                    int[] r = scene.castsIntoSlice(i, cam, 3000, 100 + f + i);
                    totalShadowed += r[0];
                    totalMissed += r[1];
                    System.out.printf(Locale.ROOT, "verify frame %d, cascade %d: %,d casters (%,d in the frustum), of %d points of the slice that are in shadow %d are shadowed by a box that was culled%n", f, i,
                            scene.casterCount(i), scene.frustumOnlyCount(i), r[0], r[1]);
                }
                renderShadowMaps(shadowTexture, false);
                renderShadowMaps(allTexture, true);
                glGetTextureImage(shadowTexture, 0, GL_DEPTH_COMPONENT, GL_FLOAT, culled);
                glGetTextureImage(allTexture, 0, GL_DEPTH_COMPONENT, GL_FLOAT, all);
                for (int i = 0; i < n; i++) {
                    // the casters are those that can shadow the slice, so the maps must agree inside the slice's footprint
                    // (the box around its corners in the map); outside it, in the part of the map that the fitted sphere adds, they may not
                    Cascades.Cascade cas = scene.cascades().get(i);
                    Mat4f tm = cas.textureMatrix();
                    float u0 = 9, u1 = -9, v0 = 9, v1 = -9, zmax = -9;
                    float th = (float) Math.tan(cam.fovy() * 0.5f);
                    for (int k = 0; k < 8; k++) {
                        float d = (k & 4) == 0 ? cas.sliceNear() : cas.sliceFar();
                        float hh = th * d, ww = hh * cam.aspect();
                        Vec3f corner = cam.position().add(cam.forward().mul(d)).add(cam.right().mul((k & 1) == 0 ? -ww : ww)).add(cam.up().mul((k & 2) == 0 ? -hh : hh));
                        Vec3f uv = tm.transformPosition(corner);
                        u0 = Math.min(u0, uv.x());
                        u1 = Math.max(u1, uv.x());
                        v0 = Math.min(v0, uv.y());
                        v1 = Math.max(v1, uv.y());
                        zmax = Math.max(zmax, uv.z());
                    }
                    int x0 = Math.max(0, (int) Math.ceil(u0 * map) + 1), x1 = Math.min(map - 1, (int) Math.floor(u1 * map) - 1);
                    int y0 = Math.max(0, (int) Math.ceil(v0 * map) + 1), y1 = Math.min(map - 1, (int) Math.floor(v1 * map) - 1);
                    int differ = 0, inside = 0, differOutside = 0;
                    for (int y = 0; y < map; y++) {
                        for (int x = 0; x < map; x++) {
                            boolean in = x >= x0 && x <= x1 && y >= y0 && y <= y1;
                            // what lies deeper than the farthest point of the slice can never shadow it, so the maps are compared up to that depth
                            boolean different = Math.min(culled.get(i * map * map + y * map + x), zmax) != Math.min(all.get(i * map * map + y * map + x), zmax);
                            if (in) {
                                inside++;
                                if (different) {
                                    differ++;
                                }
                            } else if (different) {
                                differOutside++;
                            }
                        }
                    }
                    if (differ > 0) {
                        mapsDifferent++;
                    }
                    System.out.printf(Locale.ROOT, "verify frame %d, cascade %d: the map from the casters and the map from all %,d boxes differ in %d of the %,d texels of the slice's footprint, compared up to the depth of its farthest corner (and in %,d outside the footprint, in the extra that the fitted sphere adds)%n", f, i, scene.size(),
                            differ, inside, differOutside);
                }
                long[] l = lookupCheck(cam, light, culled);
                lookups += l[0];
                lookupMismatch += l[1];
                lookupStrict += l[2];
                System.out.printf(Locale.ROOT, "verify frame %d: %,d surface points looked up with the library's texture matrix: %,d disagree with the ray to the sun (%.2f%%), %,d of those with every ray within two texels (%.2f%%)%n", f, l[0], l[2],
                        l[0] == 0 ? 0.0 : 100.0 * l[2] / l[0], l[1], l[0] == 0 ? 0.0 : 100.0 * l[1] / l[0]);
            }
        } finally {
            glDeleteTextures(allTexture);
            MemoryUtil.memFree(culled);
            MemoryUtil.memFree(all);
        }
        verifyCompared = lookups;
        verifyMismatches = lookupMismatch;
        verifyNote = String.format(Locale.ROOT, "verified: %d of %,d shadowing boxes culled, %d maps differ from the unculled ones, lookup disagrees with a ray at %,d of %,d points and with every ray within two texels at %,d", totalMissed,
                totalShadowed, mapsDifferent, lookupStrict, lookups, lookupMismatch);
        System.out.println(verifyNote);
        if (totalMissed > 0 || mapsDifferent > 0 || lookups < 1000 || lookupMismatch > 0.01 * lookups) {
            throw new IllegalStateException("shadow verification failed: " + verifyNote);
        }
    }

    // traces rays through random pixels to the first box, and compares the shadow map's verdict at the hit with a ray to the sun
    private long[] lookupCheck(Cameraf cam, Vec3f light, FloatBuffer maps) {
        int n = options.cascades(), map = options.map();
        BoundsArray b = scene.bounds();
        Rng rng = new Rng(77);
        Vec3f toLight = light.negate();
        float t = (float) Math.tan(cam.fovy() * 0.5f);
        long count = 0, mismatch = 0, strict = 0, mapOnly = 0;
        long[] perCascade = new long[n], perCascadeStrict = new long[n];
        List<Cascades.Cascade> cascades = scene.cascades();
        for (int s = 0; s < 6000; s++) {
            float x = (float) rng.nextDouble(-1.0, 1.0), y = (float) rng.nextDouble(-1.0, 1.0);
            Vec3f dir = cam.forward().add(cam.right().mul(x * t * cam.aspect())).add(cam.up().mul(y * t)).normalize();
            Rayf ray = Rayf.of(cam.position(), dir);
            int hit = -1;
            float best = options.distance() * 2f;
            for (int i = 0; i < b.size(); i++) {
                float h = Intersectionf.rayAabb(ray, new Aabbf(b.minX(i), b.minY(i), b.minZ(i), b.maxX(i), b.maxY(i), b.maxZ(i)), best);
                if (h != Float.POSITIVE_INFINITY && h < best) {
                    best = h;
                    hit = i;
                }
            }
            if (hit < 0) {
                continue;
            }
            Vec3f p = cam.position().add(dir.mul(best));
            // the face that was hit: the one whose plane the point is nearest to
            float[] d = {p.x() - b.minX(hit), b.maxX(hit) - p.x(), p.y() - b.minY(hit), b.maxY(hit) - p.y(), p.z() - b.minZ(hit), b.maxZ(hit) - p.z()};
            int face = 0;
            for (int k = 1; k < 6; k++) {
                if (d[k] < d[face]) {
                    face = k;
                }
            }
            Vec3f normal = new Vec3f(face == 0 ? -1f : face == 1 ? 1f : 0f, face == 2 ? -1f : face == 3 ? 1f : 0f, face == 4 ? -1f : face == 5 ? 1f : 0f);
            if (normal.dot(toLight) < 0.15f) {
                continue;
            }
            float viewDepth = p.sub(cam.position()).dot(cam.forward());
            int c = n - 1;
            for (int i = 0; i < n; i++) {
                if (viewDepth < cascades.get(i).sliceFar()) {
                    c = i;
                    break;
                }
            }
            Cascades.Cascade cas = cascades.get(c);
            Vec3f q = p.add(normal.mul(cas.texelSize() * NORMAL_OFFSET_TEXELS));
            Vec3f sc = cas.textureMatrix().transformPosition(q);
            if (sc.x() < 0f || sc.x() >= 1f || sc.y() < 0f || sc.y() >= 1f || sc.z() > 1f) {
                continue;
            }
            // skip points within two texels of a silhouette edge, where the nearest-texel read and the ray can legitimately differ
            float stored = maps.get(c * map * map + (int) (sc.y() * map) * map + (int) (sc.x() * map));
            boolean byMap = sc.z() - DEPTH_BIAS > stored;
            Vec3f start = p.add(normal.mul(0.02f));
            boolean byRay = scene.shadowed(start.x(), start.y(), start.z(), toLight, -1) >= 0;
            count++;
            perCascade[c]++;
            if (byMap != byRay) {
                strict++;
                perCascadeStrict[c]++;
                if (byMap) {
                    mapOnly++;
                }
                // a map has texels: the verdict is only wrong if no ray within two texels of the point agrees with it
                float reach = 2f * cas.texelSize();
                Vec3f t1 = Math.abs(normal.x()) > 0.5f ? new Vec3f(0f, 1f, 0f) : new Vec3f(1f, 0f, 0f);
                Vec3f t2 = normal.cross(t1);
                t1 = t2.cross(normal);
                boolean agrees = false;
                for (int k = 0; k < 8 && !agrees; k++) {
                    Vec3f dir2 = (k < 4 ? (k % 2 == 0 ? t1 : t2) : (k % 2 == 0 ? t1.add(t2) : t1.sub(t2))).mul(((k / 2) % 2 == 0 ? 1f : -1f) * reach);
                    Vec3f q2 = start.add(dir2);
                    agrees = (scene.shadowed(q2.x(), q2.y(), q2.z(), toLight, -1) >= 0) == byMap;
                }
                if (!agrees) {
                    mismatch++;
                }
            }
        }
        StringBuilder sb = new StringBuilder("  per cascade, points / strict disagreements:");
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.ROOT, " %d: %d / %d", i, perCascade[i], perCascadeStrict[i]));
        }
        sb.append(" | map shadows but ray lights: ").append(mapOnly).append(", ray shadows but map lights: ").append(strict - mapOnly);
        System.out.println(sb);
        return new long[] {count, mismatch, strict};
    }

    @Override
    public void dispose() {
        shadowTimer.dispose();
        cube.delete();
        glDeleteTextures(shadowTexture);
        glDeleteFramebuffers(shadowFramebuffer);
        glDeleteBuffers(objectBuffer);
        glDeleteBuffers(listBuffer);
        glDeleteProgram(shadowProgram);
        glDeleteProgram(sceneProgram);
        MemoryUtil.memFree(listStaging);
        MemoryUtil.memFree(matrixStaging);
        MemoryUtil.memFree(floatStaging);
    }
}
