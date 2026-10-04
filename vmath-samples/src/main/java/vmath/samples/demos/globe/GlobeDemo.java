package vmath.samples.demos.globe;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_J;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FILL;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL45.GL_LINE;
import static org.lwjgl.opengl.GL45.GL_LINEAR;
import static org.lwjgl.opengl.GL45.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL45.GL_RGBA;
import static org.lwjgl.opengl.GL45.GL_RGBA8;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL45.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL45.glBindBuffer;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45.glBindVertexArray;
import static org.lwjgl.opengl.GL45.glCreateBuffers;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glCreateVertexArrays;
import static org.lwjgl.opengl.GL45.glDeleteBuffers;
import static org.lwjgl.opengl.GL45.glDeleteProgram;
import static org.lwjgl.opengl.GL45.glDeleteTextures;
import static org.lwjgl.opengl.GL45.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL45.glDisable;
import static org.lwjgl.opengl.GL45.glEnable;
import static org.lwjgl.opengl.GL45.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glPolygonMode;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage3D;
import static org.lwjgl.opengl.GL45.glTextureSubImage3D;
import static org.lwjgl.opengl.GL45.glUseProgram;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL45.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL45.glVertexArrayBindingDivisor;
import static org.lwjgl.opengl.GL45.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL45.glVertexArrayVertexBuffer;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.lwjgl.system.MemoryUtil;
import vmath.camera.Cameraf;
import vmath.core.Geodetic;
import vmath.core.Morton;
import vmath.core.Vec3d;
import vmath.core.FloatingOrigin;
import vmath.core.Wgs84;
import vmath.geo.Ellipsoids;
import vmath.geo.Frustumd;
import vmath.geo.Rayd;
import vmath.geo.TerrainRgb;
import vmath.geo.TileId;
import vmath.geo.TileSelector;
import vmath.geo.WebMercator;
import vmath.gl.DrawCommandBuffer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.Hud;
import vmath.samples.framework.Stats;

/**
 * The whole Earth as map tiles: a Web Mercator pyramid of height and image tiles on the WGS-84
 * ellipsoid, chosen every frame by the screen-space size of their meshes, streamed in on worker
 * threads and drawn camera-relative with a floating origin, from 20,000 km up to two metres above
 * a summit.
 *
 * <p>The pieces of the library: {@code Wgs84} and {@code Geodetic} place every vertex on the
 * ellipsoid and give the local East-North-Up frames of the camera and of the normals;
 * {@code WebMercator} and {@code TileId} address the tiles; {@code TileBounds} bounds them in ECEF;
 * {@code TileSelector} walks the quadtree with the frustum, {@code HorizonCuller} and a balance that
 * keeps neighbours within one level; {@code TerrainRgb} decodes the Terrarium heights and gives the
 * normals; {@code Ellipsoids} finds the point that the camera looks at; {@code FloatingOrigin}
 * keeps the float positions small ({@code Rebase} is what {@code Jitter} measures); and
 * {@code DrawCommandBuffer} with {@code glMultiDrawElementsIndirect} draws all the tiles at once.
 *
 * <p><b>The data.</b> The demo does not download anything: {@link ProceduralTileSource} produces the
 * heights (as Terrarium bytes, as a tile set would deliver them) and the images from noise on the
 * sphere. The geometry is exact; the continents are not the real ones.
 *
 * <p><b>Streaming.</b> A tile that is wanted and not resident is requested from the workers, and the
 * nearest resident ancestor is drawn instead (once, and only until all the tiles that replace it are
 * there, so nothing is drawn twice). In a scripted run the demo waits for the tiles, after loading
 * those of the whole flight before the first measured frame, so that the numbers do not depend on
 * how fast the workers are.
 *
 * <p>{@code J} switches between the floating origin and the naive pipeline that narrows ECEF
 * positions to {@code float}, which makes the ground move by tenths of a metre (see {@link Jitter}). {@code H} turns the
 * horizon culling off, {@code F} freezes the camera that the selection uses (fly away to see what
 * was chosen), {@code C} colours the tiles by level, {@code T} draws wireframes, {@code G} switches
 * between the scripted descent and the free camera, and {@code [} and {@code ]} change the
 * allowed spacing of the vertices on the screen.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context, except
 * the tile workers of the {@link TileCache}, which only produce arrays.
 */
public final class GlobeDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("globe", "The whole Earth as map tiles",
            "A WGS-84 globe of Web Mercator height and image tiles, selected by screen-space error, streamed and drawn camera-relative from orbit down to street level.",
            List.of("large worlds", "geometry", "scale"), 16384, List.of("--max-zoom", "9", "--min-zoom", "2", "--flight", "2", "--cells", "16", "--image", "32", "--resident", "256"),
            "G: free camera (W A S D, Space, Ctrl, Shift, mouse, wheel) | J: floating origin / naive float | H: horizon culling | F: freeze selection | C: tint by level | T: wireframe | [ ]: vertex spacing");

    private static final int POLAR_SEGMENTS = 64;
    private static final int UPLOADS_PER_FRAME = 8;
    private static final double PREFLIGHT_SECONDS = 20.0;
    private static final int SUBSTITUTE_TABLE = 16384;
    private static final int LAYERS = 1024;

    private final GlobeOptions options;
    private DemoContext ctx;
    private TileSource source;
    private TileCache cache;
    private TileSelector selector;
    private GlobeCamera camera;
    private FloatingOrigin origin;
    private double[] summit;
    private int cells;
    private int vertsPerTile;
    private int tileIndexCount;
    private int vertexBuffer;
    private int indexBuffer;
    private int instanceBuffer;
    private int commandBuffer;
    private int vao;
    private final int[] textures = new int[4];
    private int textureCount;
    private int program;
    private int viewProjectionLocation;
    private int cameraLocalLocation;
    private int sunLocation;
    private int tintLocation;
    private int lowAltLocation;
    private int maxDraws;
    private DrawCommandBuffer commands;
    private ByteBuffer commandBytes;
    private FloatBuffer instanceStaging;
    private FloatBuffer vertexStaging;
    private ByteBuffer imageStaging;
    private final long[] substituteKeys = new long[SUBSTITUTE_TABLE];
    private final int[] substituteSlots = new int[SUBSTITUTE_TABLE];
    private int[] resolved;
    private int[] substituteList;
    private int substituteCount;
    private int[] missingList;
    private int missingCount;
    private Cameraf drawCamera;
    private Vec3d sunDirection;
    private float sunX;
    private float sunY;
    private float sunZ;

    private boolean naive;
    private boolean free;
    private boolean horizon;
    private boolean frozen;
    private boolean tint;
    private boolean wire;
    private double pixels;
    private Vec3d frozenPosition;
    private Frustumd frozenFrustum;

    private int selected;
    private int drawn;
    private int zoomMin;
    private int zoomMax;
    private int uploadedThisFrame;
    private double selectMs;
    private double resolveMs;
    private double uploadMs;
    private double lookLatitude = Double.NaN;
    private double lookLongitude;
    private double[] jitter = {0.0, 0.0};
    private long preflightMillis;
    private int preflightTiles;
    private boolean preflighted;
    private int frameNumber;

    private int selectSeries;
    private int resolveSeries;
    private int uploadSeries;
    private int selectedSeries;
    private int drawnSeries;
    private int visitedSeries;
    private int altitudeSeries;
    private int loadsSeries;
    private int residentSeries;
    private int frustumSeries;
    private int horizonSeries;
    private int balanceSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public GlobeDemo(List<String> args) {
        this.options = GlobeOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        cells = options.cells();
        source = new ProceduralTileSource(cells, options.imageSize());
        vertsPerTile = TileMeshes.vertexCount(cells);
        int slots = options.resident();
        cache = new TileCache(source, slots, options.threads());
        selector = new TileSelector(options.minZoom(), options.maxZoom(), 0.0, Planet.MAX_HEIGHT, cells, 4096);
        camera = new GlobeCamera();
        origin = new FloatingOrigin(1024.0, 4096.0);
        summit = GlobeCamera.findSummit();
        System.out.printf(Locale.ROOT, "summit at %.4f, %.4f degrees, %.0f m%n", Math.toDegrees(summit[0]), Math.toDegrees(summit[1]), summit[2]);
        Vec3d e = Wgs84.east(summit[0]), n = Wgs84.north(summit[1], summit[0]), u = Wgs84.up(summit[1], summit[0]);
        sunDirection = u.mul(0.75).add(e.mul(0.55)).add(n.mul(0.25)).normalize();
        sunX = (float) sunDirection.x();
        sunY = (float) sunDirection.y();
        sunZ = (float) sunDirection.z();
        naive = options.naive();
        free = options.free();
        horizon = !options.noHorizon();
        pixels = options.pixels();
        maxDraws = 4096 + 2;
        resolved = new int[4096];
        substituteList = new int[4096];
        missingList = new int[4096];

        createBuffers(slots);
        createProgram();
        Stats stats = ctx.stats();
        selectSeries = stats.timer("select tiles", "TileSelector: frustum, horizon, refinement and balance");
        resolveSeries = stats.timer("resolve tiles", "slots, substitutes and the draw commands");
        uploadSeries = stats.timer("upload tiles", "tiles copied to the GPU this frame");
        selectedSeries = stats.series("tiles selected", "", 0, "chosen by the selector");
        drawnSeries = stats.series("tiles drawn", "", 0, "after substitution");
        visitedSeries = stats.series("nodes visited", "", 0, "quadtree nodes tested");
        altitudeSeries = stats.series("altitude", "m", 0, "above the ellipsoid");
        loadsSeries = stats.series("tiles loaded", "", 0, "placed in the GPU buffers this frame");
        residentSeries = stats.series("tiles resident", "", 0, "in the GPU buffers");
        frustumSeries = stats.series("nodes outside the frustum", "", 1, "culled by the selector");
        horizonSeries = stats.series("nodes behind the horizon", "", 2, "culled by the selector, after the frustum");
        balanceSeries = stats.series("tiles split by the balance", "", 1, "to keep neighbours within one level");
        ctx.clearColor(0.0f, 0.0f, 0.02f);

        loadPinned();
        for (int zoom = 0; zoom < 4; zoom++) {
            double alt = new double[] {100.0, 1000.0, 10000.0, 1_000_000.0}[zoom];
            double[] err = Jitter.maxError(alt);
            System.out.printf(Locale.ROOT, "vertex position error at %,.0f m: naive %.3f m, floating origin %.4f m%n", alt, err[0], err[1]);
        }
        if (options.verify()) {
            verify();
        }
        camera.scripted(0.0, options.flight(), summit);
    }

    private void createBuffers(int slots) {
        long vertexBytes = (long) (slots + 2) * vertsPerTile * TileMeshes.FLOATS * Float.BYTES;
        vertexBuffer = glCreateBuffers();
        glNamedBufferStorage(vertexBuffer, vertexBytes, GL_DYNAMIC_STORAGE_BIT);
        int[] tileIndices = TileMeshes.indices(cells);
        int[] capIndices = TileMeshes.polarCapIndices(POLAR_SEGMENTS);
        tileIndexCount = tileIndices.length;
        IntBuffer ix = MemoryUtil.memAllocInt(tileIndices.length + capIndices.length);
        ix.put(tileIndices).put(capIndices).flip();
        indexBuffer = glCreateBuffers();
        glNamedBufferStorage(indexBuffer, ix, 0);
        MemoryUtil.memFree(ix);
        instanceBuffer = glCreateBuffers();
        glNamedBufferStorage(instanceBuffer, (long) maxDraws * 8 * Float.BYTES, GL_DYNAMIC_STORAGE_BIT);
        instanceStaging = MemoryUtil.memAllocFloat(maxDraws * 8);
        vertexStaging = MemoryUtil.memAllocFloat(vertsPerTile * TileMeshes.FLOATS);
        int size = options.imageSize();
        imageStaging = MemoryUtil.memAlloc(size * size * 4);

        vao = glCreateVertexArrays();
        glVertexArrayVertexBuffer(vao, 0, vertexBuffer, 0, TileMeshes.FLOATS * Float.BYTES);
        glVertexArrayElementBuffer(vao, indexBuffer);
        glVertexArrayAttribFormat(vao, 0, 3, GL_FLOAT, false, 0);
        glVertexArrayAttribFormat(vao, 1, 3, GL_FLOAT, false, 3 * Float.BYTES);
        glVertexArrayAttribFormat(vao, 2, 2, GL_FLOAT, false, 6 * Float.BYTES);
        for (int a = 0; a < 3; a++) {
            glEnableVertexArrayAttrib(vao, a);
            glVertexArrayAttribBinding(vao, a, 0);
        }
        glVertexArrayVertexBuffer(vao, 1, instanceBuffer, 0, 8 * Float.BYTES);
        glVertexArrayBindingDivisor(vao, 1, 1);
        glVertexArrayAttribFormat(vao, 3, 4, GL_FLOAT, false, 0);
        glVertexArrayAttribFormat(vao, 4, 4, GL_FLOAT, false, 4 * Float.BYTES);
        for (int a = 3; a < 5; a++) {
            glEnableVertexArrayAttrib(vao, a);
            glVertexArrayAttribBinding(vao, a, 1);
        }

        // OpenGL limits the layers of an array texture (2048 on this driver), so the images live in several arrays of 1024 layers
        textureCount = (slots + 1 + LAYERS - 1) / LAYERS;
        for (int a = 0; a < textureCount; a++) {
            textures[a] = glCreateTextures(GL_TEXTURE_2D_ARRAY);
            glTextureStorage3D(textures[a], 1, GL_RGBA8, size, size, a == textureCount - 1 ? slots + 1 - a * LAYERS : LAYERS);
            glTextureParameteri(textures[a], GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTextureParameteri(textures[a], GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTextureParameteri(textures[a], GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTextureParameteri(textures[a], GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        }
        // the layer after the last slot is white: the polar caps are ice
        for (int i = 0; i < size * size * 4; i++) {
            imageStaging.put(i, (byte) 0xEE);
        }
        glTextureSubImage3D(textures[slots / LAYERS], 0, 0, 0, slots % LAYERS, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, imageStaging);

        long stride = DrawCommandBuffer.stride(DrawCommandBuffer.Kind.ELEMENTS, false);
        MemorySegment segment = ctx.arena().allocate(stride * maxDraws, 16);
        commandBytes = segment.asByteBuffer();
        commands = new DrawCommandBuffer(segment, DrawCommandBuffer.Kind.ELEMENTS, false);
        commandBuffer = glCreateBuffers();
        glNamedBufferStorage(commandBuffer, segment.byteSize(), GL_DYNAMIC_STORAGE_BIT);

        // the two caps live in the blocks after the tiles
        float[] cap = new float[(POLAR_SEGMENTS + 1) * TileMeshes.FLOATS];
        for (int c = 0; c < 2; c++) {
            double[] ref = new double[3];
            TileMeshes.polarCap(c == 0, POLAR_SEGMENTS, cap, ref);
            capReference[c] = ref;
            glNamedBufferSubData(vertexBuffer, (long) (slots + c) * vertsPerTile * TileMeshes.FLOATS * Float.BYTES, cap);
        }
    }

    private final double[][] capReference = new double[2][];

    private void createProgram() {
        program = Gl.program("""
                #version 450 core
                layout(location = 0) in vec3 position;
                layout(location = 1) in vec3 normal;
                layout(location = 2) in vec2 uv;
                layout(location = 3) in vec4 tileOffset;
                layout(location = 4) in vec4 tileExtra;
                uniform mat4 viewProjection;
                uniform vec3 cameraLocal;
                out vec3 vNormal;
                out vec3 vRel;
                out vec2 vUv;
                flat out float vLayer;
                flat out float vArray;
                flat out float vLevel;
                void main() {
                    // the vertex relative to its tile, the tile relative to the origin, the camera relative to the origin
                    vec3 rel = position + (tileOffset.xyz - cameraLocal);
                    vNormal = normal;
                    vRel = rel;
                    vUv = uv;
                    vLayer = tileOffset.w;
                    vLevel = tileExtra.x;
                    vArray = tileExtra.y;
                    gl_Position = viewProjection * vec4(rel, 1.0);
                }
                """, """
                #version 450 core
                in vec3 vNormal;
                in vec3 vRel;
                in vec2 vUv;
                flat in float vLayer;
                flat in float vArray;
                flat in float vLevel;
                layout(binding = 0) uniform sampler2DArray imagery0;
                layout(binding = 1) uniform sampler2DArray imagery1;
                layout(binding = 2) uniform sampler2DArray imagery2;
                layout(binding = 3) uniform sampler2DArray imagery3;
                uniform vec3 sunDirection;
                uniform int tintLevels;
                uniform float lowAltitude;
                out vec4 color;
                void main() {
                    // constant indices only: the array is chosen per tile, which is not uniform control flow for a sampler array
                    vec3 albedo;
                    if (vArray < 0.5) albedo = texture(imagery0, vec3(vUv, vLayer)).rgb;
                    else if (vArray < 1.5) albedo = texture(imagery1, vec3(vUv, vLayer)).rgb;
                    else if (vArray < 2.5) albedo = texture(imagery2, vec3(vUv, vLayer)).rgb;
                    else albedo = texture(imagery3, vec3(vUv, vLayer)).rgb;
                    if (tintLevels == 1) {
                        float t = fract(vLevel * 0.1618);
                        albedo = mix(albedo, 0.5 + 0.5 * cos(6.2831 * (t + vec3(0.0, 0.33, 0.67))), 0.55);
                    }
                    vec3 n = normalize(vNormal);
                    float diffuse = max(dot(n, sunDirection), 0.0);
                    vec3 lit = albedo * (0.06 + 1.0 * diffuse);
                    float dist = length(vRel);
                    float rim = pow(1.0 - max(dot(n, -vRel / max(dist, 1e-3)), 0.0), 4.0);
                    lit += vec3(0.20, 0.40, 0.85) * rim * 0.45 * (0.15 + diffuse) * (1.0 - lowAltitude);
                    float fog = (1.0 - exp(-dist * 2.2e-5)) * lowAltitude;
                    color = vec4(mix(lit, vec3(0.60, 0.74, 0.92), fog), 1.0);
                }
                """);
        viewProjectionLocation = glGetUniformLocation(program, "viewProjection");
        cameraLocalLocation = glGetUniformLocation(program, "cameraLocal");
        sunLocation = glGetUniformLocation(program, "sunDirection");
        tintLocation = glGetUniformLocation(program, "tintLevels");
        lowAltLocation = glGetUniformLocation(program, "lowAltitude");
    }

    /**
     * Loads the coarsest level, which is the fallback of everything, and keeps it resident.
     */
    private void loadPinned() {
        int n = 1 << options.minZoom();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                TileCache.Loaded t = cache.loadNow(new TileId(options.minZoom(), x, y));
                int slot = cache.place(t, 0);
                upload(slot, t);
                cache.pin(slot);
            }
        }
    }

    private void upload(int slot, TileCache.Loaded t) {
        int size = options.imageSize();
        vertexStaging.clear();
        vertexStaging.put(t.vertices()).flip();
        glNamedBufferSubData(vertexBuffer, (long) slot * vertsPerTile * TileMeshes.FLOATS * Float.BYTES, vertexStaging);
        imageStaging.clear();
        imageStaging.put(t.imagery()).flip();
        glTextureSubImage3D(textures[slot / LAYERS], 0, 0, 0, slot % LAYERS, size, size, 1, GL_RGBA, GL_UNSIGNED_BYTE, imageStaging);
    }

    private static long key(int zoom, int x, int y) {
        return (1L << (2 * zoom)) | Morton.encode2(x, y);
    }

    /**
     * Selects the tiles for the current camera, and works out which resident tile to draw for each:
     * the tile itself, or its nearest resident ancestor. Fills {@code resolved} (the slot of each
     * selected tile that is drawn as itself, or -1), the list of substitutes and the list of tiles
     * that are missing.
     */
    private void select(FrameInfo frame) {
        Vec3d at = frozen ? frozenPosition : camera.position();
        Frustumd frustum = frozen ? frozenFrustum : camera.frustum(frame.aspect());
        long t0 = System.nanoTime();
        selected = selector.select(frustum, at, GlobeCamera.FOVY, frame.height(), pixels, horizon);
        selectMs = (System.nanoTime() - t0) / 1e6;
        long t1 = System.nanoTime();
        if (selected > resolved.length) {
            resolved = new int[selected];
            substituteList = new int[selected];
            missingList = new int[selected];
        }
        Arrays.fill(substituteKeys, 0L);
        substituteCount = 0;
        missingCount = 0;
        zoomMin = 99;
        zoomMax = 0;
        for (int i = 0; i < selected; i++) {
            int z = selector.zoom(i), x = selector.x(i), y = selector.y(i);
            zoomMin = Math.min(zoomMin, z);
            zoomMax = Math.max(zoomMax, z);
            int slot = cache.slotOf(key(z, x, y));
            if (slot >= 0) {
                resolved[i] = slot;
                cache.touch(slot, frameNumber);
                continue;
            }
            resolved[i] = -1;
            missingList[missingCount++] = i;
            int az = z, ax = x, ay = y, aslot = -1;
            while (az > options.minZoom() && aslot < 0) {
                az--;
                ax >>= 1;
                ay >>= 1;
                aslot = cache.slotOf(key(az, ax, ay));
            }
            if (aslot >= 0) {
                cache.touch(aslot, frameNumber);
                long k = key(az, ax, ay);
                int h = hash(k);
                while (substituteKeys[h] != 0L && substituteKeys[h] != k) {
                    h = (h + 1) & (SUBSTITUTE_TABLE - 1);
                }
                if (substituteKeys[h] == 0L) {
                    substituteKeys[h] = k;
                    substituteSlots[h] = aslot;
                    substituteList[substituteCount++] = h;
                }
            }
        }
        resolveMs = (System.nanoTime() - t1) / 1e6;
    }

    private static int hash(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & (SUBSTITUTE_TABLE - 1);
    }

    private boolean hasSubstitutedAncestor(int z, int x, int y) {
        while (z > options.minZoom()) {
            z--;
            x >>= 1;
            y >>= 1;
            long k = key(z, x, y);
            int h = hash(k);
            while (substituteKeys[h] != 0L) {
                if (substituteKeys[h] == k) {
                    return true;
                }
                h = (h + 1) & (SUBSTITUTE_TABLE - 1);
            }
        }
        return false;
    }

    /**
     * Asks the workers for the tiles that are missing, the coarsest missing ancestor of each first,
     * so that the picture improves from the top down.
     */
    private void requestMissing() {
        for (int m = 0; m < missingCount; m++) {
            int i = missingList[m];
            int z = selector.zoom(i), x = selector.x(i), y = selector.y(i);
            int top = z;
            while (top > options.minZoom() && cache.slotOf(key(top - 1, x >> (z - top + 1), y >> (z - top + 1))) < 0) {
                top--;
            }
            for (int level = top; level <= z; level++) {
                int shift = z - level;
                cache.request(new TileId(level, x >> shift, y >> shift));
            }
        }
    }

    private void drain(int limit) {
        long t0 = System.nanoTime();
        int n = 0;
        TileCache.Loaded t;
        while (n < limit && (t = cache.poll()) != null) {
            int slot = cache.place(t, frameNumber);
            if (slot >= 0) {
                upload(slot, t);
            }
            n++;
        }
        uploadedThisFrame += n;
        uploadMs += (System.nanoTime() - t0) / 1e6;
    }

    /**
     * Waits for every missing tile of the current selection, as a scripted run does.
     */
    private void awaitTiles(FrameInfo frame) {
        for (int round = 0; round < 64 && missingCount > 0; round++) {
            requestMissing();
            long t0 = System.nanoTime();
            while (cache.inFlight() > 0) {
                TileCache.Loaded t = cache.take();
                int slot = cache.place(t, frameNumber);
                if (slot >= 0) {
                    upload(slot, t);
                    uploadedThisFrame++;
                }
            }
            uploadMs += (System.nanoTime() - t0) / 1e6;
            select(frame);
        }
    }

    /**
     * Loads the tiles of the whole scripted flight before the first measured frame, so that a run
     * measures drawing and selecting, not generating.
     */
    private void preflight(FrameInfo frame) {
        long t0 = System.nanoTime();
        double step = FrameInfo.SCRIPTED_DT;
        GlobeCamera saved = camera;
        for (int f = 0; f * step <= Math.min(options.flight(), PREFLIGHT_SECONDS) + 1e-9; f++) {
            double t = f * step;
            camera.scripted(t, options.flight(), summit);
            frameNumber++;
            select(frame);
            awaitTiles(frame);
        }
        camera = saved;
        preflightMillis = (System.nanoTime() - t0) / 1_000_000L;
        preflightTiles = (int) cache.loaded();
        System.out.printf(Locale.ROOT, "loaded the tiles of the flight in %,d ms: %,d tiles, %,d resident, %,d evicted%n", preflightMillis, cache.loaded(), cache.resident(), cache.evicted());
    }

    @Override
    public void update(FrameInfo frame) {
        frameNumber++;
        uploadedThisFrame = 0;
        uploadMs = 0.0;
        if (frame.benchmark()) {
            if (!preflighted) {
                preflighted = true;
                preflight(frame);
            }
            camera.scripted(frame.time(), options.flight(), summit);
        } else {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_G)) {
                free = !free;
            }
            if (in.pressed(GLFW_KEY_J)) {
                naive = !naive;
            }
            if (in.pressed(GLFW_KEY_H)) {
                horizon = !horizon;
            }
            if (in.pressed(GLFW_KEY_C)) {
                tint = !tint;
            }
            if (in.pressed(GLFW_KEY_T)) {
                wire = !wire;
            }
            if (in.pressed(GLFW_KEY_F)) {
                frozen = !frozen;
                if (frozen) {
                    frozenPosition = camera.position();
                    frozenFrustum = camera.frustum(frame.aspect());
                }
            }
            if (in.pressed(GLFW_KEY_LEFT_BRACKET)) {
                pixels = Math.max(1.0, pixels / 1.3);
            }
            if (in.pressed(GLFW_KEY_RIGHT_BRACKET)) {
                pixels = Math.min(64.0, pixels * 1.3);
            }
            if (free) {
                camera.update(frame);
            } else {
                camera.scripted(frame.time(), options.flight() * 2.0, summit);
            }
        }

        if (!naive) {
            origin.update(camera.position());
        }

        select(frame);
        if (frame.benchmark()) {
            awaitTiles(frame);
        } else {
            requestMissing();
            drain(UPLOADS_PER_FRAME);
        }
        buildDraws(frame);

        double sky = lowAltitude();
        ctx.clearColor((float) (0.01 + 0.44 * sky), (float) (0.01 + 0.64 * sky), (float) (0.03 + 0.92 * sky));
        drawCamera = camera.drawCamera(frame.aspect());
        updateLook();
        if (frameNumber % 30 == 0) {
            jitter = Jitter.maxError(Math.max(1.0, camera.aboveGround()));
        }
        Stats stats = ctx.stats();
        stats.recordNanos(selectSeries, (long) (selectMs * 1e6));
        stats.recordNanos(resolveSeries, (long) (resolveMs * 1e6));
        stats.recordNanos(uploadSeries, (long) (uploadMs * 1e6));
        stats.record(selectedSeries, selected);
        stats.record(drawnSeries, drawn);
        stats.record(visitedSeries, selector.visited());
        stats.record(altitudeSeries, camera.altitude());
        stats.record(loadsSeries, uploadedThisFrame);
        stats.record(residentSeries, cache.resident());
        stats.record(frustumSeries, selector.frustumCulled());
        stats.record(horizonSeries, selector.horizonCulled());
        stats.record(balanceSeries, selector.balanceSplits());
    }

    /**
     * Writes the instance records and the indirect commands of the tiles to draw: those selected
     * and resident, then the substitutes, then the two polar caps.
     */
    private void buildDraws(FrameInfo frame) {
        commands.clear();
        instanceStaging.clear();
        drawn = 0;
        Vec3d o = naive ? Vec3d.ZERO : origin.origin();
        for (int i = 0; i < selected && drawn < maxDraws - 2; i++) {
            int slot = resolved[i];
            if (slot < 0 || hasSubstitutedAncestor(selector.zoom(i), selector.x(i), selector.y(i))) {
                continue;
            }
            addDraw(slot, selector.zoom(i), o);
        }
        for (int s = 0; s < substituteCount && drawn < maxDraws - 2; s++) {
            int h = substituteList[s];
            addDraw(substituteSlots[h], zoomOfKey(substituteKeys[h]), o);
        }
        for (int c = 0; c < 2; c++) {
            double[] ref = capReference[c];
            commands.addElements(3 * POLAR_SEGMENTS, 1, tileIndexCount, (options.resident() + c) * vertsPerTile, drawn);
            instanceStaging.put((float) (ref[0] - o.x())).put((float) (ref[1] - o.y())).put((float) (ref[2] - o.z())).put((float) (options.resident() % LAYERS));
            instanceStaging.put(0f).put((float) (options.resident() / LAYERS)).put(0f).put(0f);
            drawn++;
        }
        instanceStaging.flip();
        glNamedBufferSubData(commandBuffer, 0, commandBytes);
        glNamedBufferSubData(instanceBuffer, 0, instanceStaging);
    }

    private static int zoomOfKey(long key) {
        return (63 - Long.numberOfLeadingZeros(key)) / 2;
    }

    private void addDraw(int slot, int zoom, Vec3d o) {
        double[] ref = cache.reference(slot);
        commands.addElements(tileIndexCount, 1, 0, slot * vertsPerTile, drawn);
        instanceStaging.put((float) (ref[0] - o.x())).put((float) (ref[1] - o.y())).put((float) (ref[2] - o.z())).put((float) (slot % LAYERS));
        instanceStaging.put((float) zoom).put((float) (slot / LAYERS)).put(0f).put(0f);
        drawn++;
    }

    // 1 inside the atmosphere's haze (below 30 km), 0 in space (above 400 km): drives the sky colour, the fog and the rim glow
    private double lowAltitude() {
        double t = Math.max(0.0, Math.min(1.0, (camera.altitude() - 3.0e4) / 3.7e5));
        return 1.0 - t * t * (3.0 - 2.0 * t);
    }

    private void updateLook() {
        Rayd ray = Rayd.of(camera.position(), camera.forward());
        double t = Ellipsoids.rayWgs84(ray, Double.POSITIVE_INFINITY);
        if (Double.isFinite(t)) {
            Geodetic g = Wgs84.toGeodetic(ray.pointAt(t));
            lookLatitude = g.latitude();
            lookLongitude = g.longitude();
        } else {
            lookLatitude = Double.NaN;
        }
    }

    @Override
    public void render(FrameInfo frame) {
        ctx.gpu().begin();
        glDisable(GL_CULL_FACE);
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        }
        glUseProgram(program);
        Gl.uniform(program, viewProjectionLocation, drawCamera.viewProjection());
        Vec3d o = naive ? Vec3d.ZERO : origin.origin();
        Vec3d c = camera.position();
        glProgramUniform3f(program, cameraLocalLocation, (float) (c.x() - o.x()), (float) (c.y() - o.y()), (float) (c.z() - o.z()));
        glProgramUniform3f(program, sunLocation, sunX, sunY, sunZ);
        glProgramUniform1i(program, tintLocation, tint ? 1 : 0);
        glProgramUniform1f(program, lowAltLocation, (float) lowAltitude());
        for (int a = 0; a < textureCount; a++) {
            glBindTextureUnit(a, textures[a]);
        }
        glBindVertexArray(vao);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, commandBuffer);
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, 0L, drawn, (int) commands.stride());
        if (wire) {
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        }
        glEnable(GL_CULL_FACE);
        ctx.gpu().end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "altitude %s (%s above the ground) | looking at %s", length(camera.altitude()), length(camera.aboveGround()), lookText()));
        hud.line(String.format(Locale.ROOT, "%d tiles selected, %d drawn | zoom %d to %d | %d nodes tested, %d frustum, %d horizon, %d balance splits%s", selected, drawn, zoomMin == 99 ? 0 : zoomMin,
                zoomMax, selector.visited(), selector.frustumCulled(), selector.horizonCulled(), selector.balanceSplits(), selector.truncated() ? " | TRUNCATED" : ""));
        hud.line(String.format(Locale.ROOT, "%d of %d tiles resident, %,d loaded, %,d evicted, %d requests running | spacing %.1f px", cache.resident(), cache.slots(), cache.loaded(), cache.evicted(),
                cache.inFlight(), pixels));
        hud.line(String.format(Locale.ROOT, "%s | vertex error here: naive %.3f m, floating origin %.4f m | horizon %s%s", naive ? "NAIVE float pipeline" : "floating origin", jitter[0], jitter[1],
                horizon ? "on" : "off", frozen ? " | selection frozen" : ""));
        hud.line(String.format(Locale.ROOT, "select %.3f ms, resolve %.3f ms, upload %.3f ms (%d tiles)", selectMs, resolveMs, uploadMs, uploadedThisFrame));
    }

    private String lookText() {
        if (Double.isNaN(lookLatitude)) {
            return "space";
        }
        return String.format(Locale.ROOT, "%.3f, %.3f", Math.toDegrees(lookLatitude), Math.toDegrees(lookLongitude));
    }

    private static String length(double metres) {
        return metres >= 10_000.0 ? String.format(Locale.ROOT, "%,.0f km", metres / 1000.0) : String.format(Locale.ROOT, "%,.1f m", metres);
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "zoom %d to %d, %d x %d cells per tile, %d x %d pixel images, vertex spacing %.1f px, %d slots", options.minZoom(), options.maxZoom(), cells, cells,
                options.imageSize(), options.imageSize(), pixels, cache.slots()));
        stats.note(String.format(Locale.ROOT, "tiles of the flight loaded before the first frame: %,d in %,d ms (%d threads, %.0f ms of thread time); %,d evicted, %,d dropped", preflightTiles,
                preflightMillis, options.threads(), cache.workMillis(), cache.evicted(), cache.dropped()));
        stats.note(String.format(Locale.ROOT, "tiles loaded after that, while measuring: %,d", cache.loaded() - preflightTiles));
        for (double alt : new double[] {100.0, 1000.0, 10000.0, 1_000_000.0}) {
            double[] err = Jitter.maxError(alt);
            stats.note(String.format(Locale.ROOT, "largest vertex position error at %,.0f m: naive %.4f m, floating origin %.5f m", alt, err[0], err[1]));
        }
    }

    // ---------------------------------------------------------------- verification

    /**
     * Checks the selection, the horizon culling and the upload at five views from orbit to street
     * level; throws if anything is wrong.
     */
    private void verify() {
        double[][] views = {{2.0e7, -Math.PI / 2}, {4.0e5, -1.0}, {4.0e5, 0.0}, {2.0e4, -0.5}, {1.0e4, 0.0}, {500.0, -0.15}, {5.0, -0.05}};
        double aspect = 16.0 / 9.0;
        int height = 900;
        Random random = new Random(11);
        for (double[] view : views) {
            camera.place(summit[0], summit[1], summit[2] + view[0], 0.4, view[1]);
            frameNumber++;
            Frustumd frustum = camera.frustum(aspect);
            int n = selector.select(frustum, camera.position(), GlobeCamera.FOVY, height, pixels, true);
            int hidden = selector.horizonCulled();
            int withoutHorizon = selector.select(frustum, camera.position(), GlobeCamera.FOVY, height, pixels, false);
            n = selector.select(frustum, camera.position(), GlobeCamera.FOVY, height, pixels, true);
            // make every selected tile resident, as a scripted run does
            for (int round = 0; round < 64; round++) {
                int missing = 0;
                for (int i = 0; i < n; i++) {
                    if (cache.slotOf(key(selector.zoom(i), selector.x(i), selector.y(i))) < 0) {
                        cache.request(new TileId(selector.zoom(i), selector.x(i), selector.y(i)));
                        missing++;
                    }
                }
                if (missing == 0) {
                    break;
                }
                while (cache.inFlight() > 0) {
                    TileCache.Loaded t = cache.take();
                    int slot = cache.place(t, frameNumber);
                    if (slot >= 0) {
                        upload(slot, t);
                    }
                }
            }
            Set<Long> keys = new HashSet<>();
            int minZ = 99, maxZ = 0;
            for (int i = 0; i < n; i++) {
                keys.add(key(selector.zoom(i), selector.x(i), selector.y(i)));
                minZ = Math.min(minZ, selector.zoom(i));
                maxZ = Math.max(maxZ, selector.zoom(i));
            }
            // no holes: the ground under every pixel of the view (a ray through a random pixel that
            // hits the ellipsoid) must lie in a selected tile
            int tested = 0, holes = 0;
            double tanY = Math.tan(0.5 * GlobeCamera.FOVY), tanX = tanY * aspect;
            Vec3d right = camera.right();
            for (int s = 0; s < 6000; s++) {
                double x = random.nextDouble() * 2 - 1, y = random.nextDouble() * 2 - 1;
                Vec3d dir = camera.forward().add(right.mul(x * tanX)).add(camera.up().mul(y * tanY)).normalize();
                Rayd ray = Rayd.of(camera.position(), dir);
                double t = Ellipsoids.rayWgs84(ray, Double.POSITIVE_INFINITY);
                if (!Double.isFinite(t)) {
                    continue;
                }
                Geodetic g = Wgs84.toGeodetic(ray.pointAt(t));
                if (Math.abs(g.latitude()) >= WebMercator.MAX_LATITUDE) {
                    continue;
                }
                tested++;
                boolean covered = false;
                for (int z = minZ; z <= maxZ && !covered; z++) {
                    covered = keys.contains(TileId.containing(z, g.longitude(), g.latitude()).key());
                }
                if (!covered) {
                    holes++;
                }
            }
            // neighbouring selected tiles differ by at most one level
            int worst = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (touches(selector.zoom(i), selector.x(i), selector.y(i), selector.zoom(j), selector.x(j), selector.y(j))) {
                        worst = Math.max(worst, Math.abs(selector.zoom(i) - selector.zoom(j)));
                    }
                }
            }
            System.out.printf(Locale.ROOT, "verify at %s, pitch %.0f degrees: %d tiles at zoom %d to %d (%d without the horizon test, which removed %d nodes), %d pixels that see the ground tested, %d in no selected tile, neighbouring levels differ by at most %d%n",
                    length(view[0]), Math.toDegrees(view[1]), n, minZ, maxZ, withoutHorizon, hidden, tested, holes, worst);
            if (holes > 0 || worst > 1) {
                throw new IllegalStateException("the selection is wrong at " + length(view[0]) + ": holes " + holes + ", level difference " + worst);
            }
            verifyUpload(n);
        }
    }

    private static boolean touches(int z1, int x1, int y1, int z2, int x2, int y2) {
        int z = Math.max(z1, z2);
        long w = 1L << z;
        long a0x = (long) x1 << (z - z1), a1x = (long) (x1 + 1) << (z - z1), a0y = (long) y1 << (z - z1), a1y = (long) (y1 + 1) << (z - z1);
        long b0x = (long) x2 << (z - z2), b1x = (long) (x2 + 1) << (z - z2), b0y = (long) y2 << (z - z2), b1y = (long) (y2 + 1) << (z - z2);
        if (!(a0y <= b1y && b0y <= a1y)) {
            return false;
        }
        for (long shift = -w; shift <= w; shift += w) {
            if (a0x <= b1x + shift && b0x + shift <= a1x) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads vertices of resident tiles back from the GPU and compares their position, after the
     * reference point is added, with the ECEF position that {@code Wgs84.toEcef} gives for the
     * height in the tile's data.
     */
    private void verifyUpload(int selectedCount) {
        int n = cells + 1;
        double worst = 0.0;
        double worstRelative = 0.0;
        int checked = 0;
        float[] row = new float[TileMeshes.FLOATS];
        FloatBuffer read = MemoryUtil.memAllocFloat(TileMeshes.FLOATS);
        for (int i = 0; i < selectedCount && checked < 6; i += Math.max(1, selectedCount / 6)) {
            TileId id = new TileId(selector.zoom(i), selector.x(i), selector.y(i));
            int slot = cache.slotOf(id.key());
            if (slot < 0) {
                continue;
            }
            TileSource.TileData data = source.load(id);
            double[] ref = cache.reference(slot);
            for (int k = 0; k < 5; k++) {
                int vi = k == 0 ? 0 : k == 1 ? cells : k == 2 ? n * cells : k == 3 ? n * n - 1 : n * (cells / 2) + cells / 2;
                int vx = vi % n, vy = vi / n;
                glGetNamedBufferSubData(vertexBuffer, ((long) slot * vertsPerTile + vi) * TileMeshes.FLOATS * Float.BYTES, read);
                read.get(row).rewind();
                double h = Math.max(0.0, TerrainRgb.decode(TerrainRgb.Encoding.TERRARIUM, data.terrarium()[3 * vi] & 0xFF, data.terrarium()[3 * vi + 1] & 0xFF, data.terrarium()[3 * vi + 2] & 0xFF));
                Vec3d expected = Wgs84.toEcef(ProceduralTileSource.latitudeAt(id, (double) vy / cells), ProceduralTileSource.longitudeAt(id, (double) vx / cells), h);
                double dx = row[0] + ref[0] - expected.x(), dy = row[1] + ref[1] - expected.y(), dz = row[2] + ref[2] - expected.z();
                double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double local = Math.sqrt(row[0] * (double) row[0] + row[1] * (double) row[1] + row[2] * (double) row[2]);
                worst = Math.max(worst, err);
                worstRelative = Math.max(worstRelative, err / Math.max(local, 1.0));
                checked++;
            }
        }
        MemoryUtil.memFree(read);
        System.out.printf(Locale.ROOT, "verify upload: %d vertices read back from the GPU, largest position error %.4f m (%.2e of the distance from the tile's reference point)%n", checked, worst, worstRelative);
        if (checked == 0 || worstRelative > 2e-7) {
            throw new IllegalStateException("a vertex read back from the GPU is wrong: error " + worst + " m, relative " + worstRelative);
        }
    }

    @Override
    public void dispose() {
        cache.close();
        MemoryUtil.memFree(instanceStaging);
        MemoryUtil.memFree(vertexStaging);
        MemoryUtil.memFree(imageStaging);
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vertexBuffer);
        glDeleteBuffers(indexBuffer);
        glDeleteBuffers(instanceBuffer);
        glDeleteBuffers(commandBuffer);
        for (int a = 0; a < textureCount; a++) {
            glDeleteTextures(textures[a]);
        }
        glDeleteProgram(program);
    }
}
