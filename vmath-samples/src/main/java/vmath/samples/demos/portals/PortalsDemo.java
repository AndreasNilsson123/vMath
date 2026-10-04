package vmath.samples.demos.portals;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_G;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_K;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;

import java.util.List;
import java.util.Locale;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.samples.framework.BoxRenderer;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.InstanceStream;
import vmath.samples.framework.Stats;
import vmath.samples.framework.Warmup;
import vmath.spatial.CullContext;
import vmath.spatial.CullPipeline;
import vmath.spatial.CullStages;
import vmath.spatial.PortalCuller;
import vmath.spatial.PortalGraph;
import vmath.spatial.PortalStage;
import vmath.util.DebugLines;

/**
 * Portal culling in a generated building: hundreds of rooms joined by doors that open and close,
 * furniture in every room, and a camera inside. The frustum alone keeps everything in front of
 * the camera, walls or not; the portal traversal only looks into a room through a door that is
 * visible, and a room behind a shut door is not looked into at all.
 *
 * <p>Every frame the demo runs two stages of the library's cull pipeline, the frustum kernel and
 * the {@link PortalStage}, and shows how many objects each leaves. {@code E} shuts or opens the
 * nearest door, {@code O} opens and {@code K} shuts every door, {@code G} draws the visible
 * sectors, the portals (yellow open, red shut) and the rectangle of the screen through which each
 * sector is seen, {@code C} takes the ceilings away, and {@code X} freezes the camera that the
 * culling uses so that flying up in the cutaway shows exactly what was drawn.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class PortalsDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("interior-portals", "Interior with doors: portal culling",
            "A building of hundreds of rooms and a maze of doors that open and close, culled by the frustum and then by portals.",
            List.of("culling", "scale"), 16384, List.of("--rooms", "6", "--props", "40"),
            "left mouse + move: look | W A S D: walk | Space, Ctrl: up, down | Shift: fast | E: door | O, K: open, shut all | G: overlay | C: cutaway | X: freeze the culling camera");

    private static final float FOG = 0.012f;
    private static final float FAR = 400f;
    private static final long WARMUP_MILLIS = 4000;

    private final PortalsOptions options;
    private Building building;
    private BoundsArray bounds;
    private VisibilitySet visible;
    private CullPipeline frustumPipeline;
    private PortalStage portalStage;
    private InstanceStream stream;
    private BoxRenderer boxes;
    private FlyCamera fly;
    private DemoContext ctx;
    private final DebugLines lines = new DebugLines();
    private final float[] box = new float[6];
    private final float[] polygon = new float[3 * PortalGraph.MAX_PORTAL_VERTICES];
    private final double[] rect = new double[4];
    private Cameraf camera;
    private Cameraf frozen;
    private boolean overlay;
    private boolean cutaway;
    private boolean freeze;
    private int frustumSeries;
    private int portalSeries;
    private int frustumCountSeries;
    private int portalCountSeries;
    private int sectorSeries;
    private int frustumCount;
    private int portalCount;
    private double frustumMs;
    private double portalMs;
    private int lastDoor = -1;
    private float pathX;
    private float pathLength;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public PortalsDemo(List<String> args) {
        this.options = PortalsOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        long t0 = System.nanoTime();
        building = Building.generate(options.rooms(), options.props(), 5);
        bounds = building.bounds();
        System.out.printf(Locale.ROOT, "built %,d rooms, %,d doors, %,d portals and %,d objects in %.0f ms%n", building.roomCount(), building.doorCount(),
                building.graph().portalCount(), bounds.size(), (System.nanoTime() - t0) / 1e6);
        visible = new VisibilitySet(bounds.size());
        frustumPipeline = CullPipeline.of(new CullStages.Frustum());
        portalStage = new PortalStage(building.graph());
        stream = new InstanceStream(ctx.arena(), bounds.size(), 3);
        boxes = new BoxRenderer(ctx);
        boxes.fogColor(0.08f, 0.09f, 0.11f);
        pathX = (options.rooms() / 2) * Building.PITCH + 6f;
        pathLength = (options.rooms() - 1) * Building.PITCH;
        fly = new FlyCamera(new Vec3f(pathX, 1.7f, 6f), (float) Math.PI, 0f, 1.0f, 0.1f, FAR, 4f, 12f);
        overlay = options.overlay();

        Stats stats = ctx.stats();
        frustumSeries = stats.timer("frustum", "the frustum kernel over every object");
        portalSeries = stats.timer("portals", "locate the camera, traverse the doors, cull the objects of the reached rooms");
        frustumCountSeries = stats.series("after frustum", "", 0, "objects in the frustum, of " + bounds.size());
        portalCountSeries = stats.series("after portals", "", 0, "objects that the doors let the camera see");
        sectorSeries = stats.series("rooms reached", "", 1, "sectors that the traversal reached, of " + building.sectorCount());
        ctx.clearColor(0.08f, 0.09f, 0.11f);
        prewarm();
    }

    /**
     * Runs the culling of a frame (the frustum stage and the portal traversal) before the first
     * frame until the JIT has compiled it and it has stopped allocating ({@link Warmup}), so that
     * the first frames measure compiled code.
     */
    private void prewarm() {
        Cameraf start = fly.camera(16f / 9f);
        CullContext cull = CullContext.perspective(start.frustum(), start.position(), fly.fovy(), 900);
        int rounds = Warmup.untilQuiet(() -> {
            frustumPipeline.run(cull, bounds, visible);
            portalStage.setView(start.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE);
            portalStage.cull(cull, bounds, visible);
            visible.count();
        }, WARMUP_MILLIS);
        System.out.println("warm after " + rounds + " culling passes");
    }

    @Override
    public void update(FrameInfo frame) {
        fly.update(frame);
        if (frame.benchmark()) {
            scripted(frame.frame());
        } else {
            keys(frame);
        }
        camera = fly.camera(frame.aspect());
        Cameraf cullCamera = frozen != null ? frozen : camera;

        long t0 = System.nanoTime();
        CullContext cull = CullContext.perspective(cullCamera.frustum(), cullCamera.position(), fly.fovy(), frame.height());
        frustumCount = frustumPipeline.run(cull, bounds, visible);
        long t1 = System.nanoTime();
        portalStage.setView(cullCamera.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE);
        portalStage.cull(cull, bounds, visible);
        for (int d = 0; d < building.doorCount(); d++) {
            if (building.isDoorOpen(d)) {
                visible.clear(building.doorSlab(d));
            }
        }
        if (cutaway) {
            for (int room = 0; room < building.roomCount(); room++) {
                visible.clear(2 * room + 1); // the ceilings are the second object of every room
            }
        }
        portalCount = visible.count();
        long t2 = System.nanoTime();
        frustumMs = (t1 - t0) / 1e6;
        portalMs = (t2 - t1) / 1e6;

        stream.begin();
        stream.write(visible, bounds);
        Stats stats = ctx.stats();
        stats.recordNanos(frustumSeries, t1 - t0);
        stats.recordNanos(portalSeries, t2 - t1);
        stats.record(frustumCountSeries, frustumCount);
        stats.record(portalCountSeries, portalCount);
        stats.record(sectorSeries, portalStage.culler().visibleSectorCount());
    }

    /**
     * Walks the camera up the column of rooms that the maze guarantees doors in, and back, with
     * the view swinging from side to side; it depends only on the frame number.
     */
    private void scripted(int frame) {
        float distance = frame * 0.15f;
        float p = distance % (2f * pathLength);
        boolean forward = p < pathLength;
        float z = 6f + (forward ? p : 2f * pathLength - p);
        float yaw = (forward ? (float) Math.PI : 0f) + 0.7f * (float) Math.sin(frame * 0.02);
        fly.place(pathX, 1.7f, z, yaw, 0f);
    }

    private void keys(FrameInfo frame) {
        var in = frame.input();
        if (in.pressed(GLFW_KEY_E)) {
            Vec3f p = camera.position();
            lastDoor = building.nearestDoor(p.x(), p.z());
            if (lastDoor >= 0) {
                building.setDoorOpen(lastDoor, !building.isDoorOpen(lastDoor));
            }
        }
        if (in.pressed(GLFW_KEY_O)) {
            for (int d = 0; d < building.doorCount(); d++) {
                building.setDoorOpen(d, true);
            }
        }
        if (in.pressed(GLFW_KEY_K)) {
            for (int d = 0; d < building.doorCount(); d++) {
                building.setDoorOpen(d, false);
            }
        }
        if (in.pressed(GLFW_KEY_G)) {
            overlay = !overlay;
        }
        if (in.pressed(GLFW_KEY_C)) {
            cutaway = !cutaway;
        }
        if (in.pressed(GLFW_KEY_X)) {
            freeze = !freeze;
            frozen = freeze ? camera : null;
        }
    }

    @Override
    public void render(FrameInfo frame) {
        boxes.draw(camera, stream, -1, building.structureEnd(), FOG);
        if (overlay || frozen != null) {
            lines.clear();
            if (overlay) {
                overlayLines();
            }
            if (frozen != null) {
                lines.setColor(1f, 0.9f, 0.1f, 1f).frustum(frozen.viewProjection(), DepthRange.NEGATIVE_ONE_TO_ONE, FAR);
            }
            ctx.debug().draw(lines, camera.viewProjection());
        }
        stream.end();
    }

    /**
     * Adds the boxes of the sectors that the traversal reached (green, the sector of the camera in
     * white) and the polygons of their portals (yellow if open, red if shut).
     */
    private void overlayLines() {
        PortalCuller culler = portalStage.culler();
        PortalGraph graph = building.graph();
        int here = portalStage.lastSector();
        for (int i = 0; i < culler.visibleSectorCount(); i++) {
            int s = culler.visibleSector(i);
            building.sectorBox(s, box);
            if (s == here) {
                lines.setColor(1f, 1f, 1f, 1f);
            } else {
                lines.setColor(0.2f, 0.9f, 0.3f, 1f);
            }
            lines.box(box[0], box[1], box[2], box[3], box[4], box[5]);
            for (int k = 0; k < graph.portalsOf(s); k++) {
                int p = graph.portalOf(s, k);
                if (graph.isPortalOpen(p)) {
                    lines.setColor(1f, 0.9f, 0.1f, 1f);
                } else {
                    lines.setColor(1f, 0.15f, 0.1f, 1f);
                }
                graph.portalVertices(p, polygon);
                lines.polyline(polygon, 0, graph.portalVertexCount(p), true);
            }
        }
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        PortalCuller culler = portalStage.culler();
        hud.line(String.format(Locale.ROOT, "%,d objects | frustum alone keeps %,d (%.1f%%) | with portals %,d (%.2f%%)", bounds.size(), frustumCount,
                100.0 * frustumCount / bounds.size(), portalCount, 100.0 * portalCount / bounds.size()));
        hud.line(String.format(Locale.ROOT, "frustum %.3f ms | portals %.3f ms | rooms reached %d of %d | camera in sector %d", frustumMs, portalMs,
                culler.visibleSectorCount(), building.sectorCount(), portalStage.lastSector()));
        if (lastDoor >= 0) {
            hud.line(String.format(Locale.ROOT, "last door %d: %s", lastDoor, building.isDoorOpen(lastDoor) ? "open" : "shut"));
        }
        if (overlay) {
            int shown = 0;
            for (int i = 0; i < culler.visibleSectorCount() && shown < 300; i++) {
                int s = culler.visibleSector(i);
                if (s == portalStage.lastSector()) {
                    continue;
                }
                culler.sectorRect(s, rect);
                float x0 = (float) ((rect[0] + 1.0) * 0.5 * frame.width()), x1 = (float) ((rect[2] + 1.0) * 0.5 * frame.width());
                float y0 = (float) ((1.0 - rect[3]) * 0.5 * frame.height()), y1 = (float) ((1.0 - rect[1]) * 0.5 * frame.height());
                if (x1 - x0 < 2f || y1 - y0 < 2f) {
                    continue;
                }
                hud.rect(x0, y0, x1 - x0, 2f, 0.2f, 0.9f, 0.3f, 0.8f).rect(x0, y1 - 2f, x1 - x0, 2f, 0.2f, 0.9f, 0.3f, 0.8f)
                        .rect(x0, y0, 2f, y1 - y0, 0.2f, 0.9f, 0.3f, 0.8f).rect(x1 - 2f, y0, 2f, y1 - y0, 0.2f, 0.9f, 0.3f, 0.8f);
                shown++;
            }
        }
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%,d rooms, %,d doors, %,d portals, %,d objects", building.roomCount(), building.doorCount(),
                building.graph().portalCount(), bounds.size()));
        stats.note("stalls waiting for the GPU: " + stream.stalls());
    }

    @Override
    public void dispose() {
        stream.close();
        boxes.dispose();
    }
}
