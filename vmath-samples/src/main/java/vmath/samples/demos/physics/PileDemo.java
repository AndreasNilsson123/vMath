package vmath.samples.demos.physics;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;

import java.util.List;
import java.util.Locale;
import vmath.camera.Cameraf;
import vmath.core.Vec3f;
import vmath.physics.ContactManifold;
import vmath.physics.RigidBody;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Hud;
import vmath.samples.framework.InstanceStream;
import vmath.samples.framework.OrbitCamera;
import vmath.samples.framework.Stats;
import vmath.util.DebugLines;

/**
 * A pile of thousands of boxes and spheres dropped into a pit and simulated with the library's
 * rigid-body pieces; see {@link PileSimulation} for what is used and what is left out.
 *
 * <p>The bodies are added a few per frame so that the pile builds up on screen. The step is
 * broken into its parts and each is timed: the broad phase with the dynamic AABB tree, the narrow
 * phase that makes a manifold of up to four points for every touching pair, the solver, and the
 * integration. {@code C} draws the contact points (red) and the contact normals (yellow), and
 * {@code N} adds another thousand bodies if there is room. {@code --verify} checks every 30
 * frames that nothing has left the pit.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class PileDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("rigid-pile", "A pile of boxes and spheres",
            "Thousands of boxes and spheres dropped into a pit and simulated with the library's broad phase, manifold builder and contact solver.",
            List.of("physics"), 4L * 1024 * 1024, List.of("--bodies", "150", "--verify"),
            "left mouse + move: orbit | wheel: zoom | C: contact points | N: add a thousand bodies | R: reset the camera");

    private static final int STEPS_PER_VERIFY = 30;

    private final PileOptions options;
    private PileSimulation sim;
    private BodyRenderer renderer;
    private InstanceStream boxStream;
    private InstanceStream sphereStream;
    private OrbitCamera orbit;
    private DemoContext ctx;
    private final DebugLines lines = new DebugLines();
    private final float[] rows = new float[12];
    private Cameraf camera;
    private boolean showContacts;
    private int target;
    private int broadSeries;
    private int narrowSeries;
    private int solveSeries;
    private int integrateSeries;
    private int stepSeries;
    private int perBodySeries;
    private int bodiesSeries;
    private int pairsSeries;
    private int contactsSeries;
    private double stepMs;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown or invalid
     */
    public PileDemo(List<String> args) {
        this.options = PileOptions.parse(args);
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        target = options.bodies();
        sim = new PileSimulation(target + 1000 * 20);
        sim.warmStart(options.warmStart());
        renderer = new BodyRenderer(ctx);
        int slots = sim.capacity() + sim.staticCount();
        boxStream = new InstanceStream(ctx.arena(), slots, 3);
        sphereStream = new InstanceStream(ctx.arena(), slots, 3);
        orbit = new OrbitCamera(new Vec3f(0f, 0f, 0f), 0.6f, 0.95f, 52f, 0.9f, 0.5f, 400f);
        showContacts = options.contacts();

        Stats stats = ctx.stats();
        broadSeries = stats.timer("broad phase", "moving the boxes of the dynamic tree and asking it for the overlaps");
        narrowSeries = stats.timer("narrow phase", "a manifold for every touching pair");
        solveSeries = stats.timer("solver", "ten sweeps of the sequential-impulse solver over every manifold");
        integrateSeries = stats.timer("integrate", "semi-implicit Euler of every body");
        stepSeries = stats.timer("step", "the whole simulation step");
        perBodySeries = stats.series("step per body", "us", 2, "the step divided by the number of dynamic bodies");
        bodiesSeries = stats.series("bodies", "", 0, "dynamic bodies in the pit");
        pairsSeries = stats.series("pairs", "", 0, "overlapping pairs that the broad phase found");
        contactsSeries = stats.series("contacts", "", 0, "contact points that the narrow phase made");
        ctx.clearColor(0.62f, 0.72f, 0.85f);
    }

    @Override
    public void update(FrameInfo frame) {
        orbit.update(frame);
        if (frame.benchmark()) {
            orbit.scripted(frame.frame(), 0.004f);
        } else if (frame.input().pressed(GLFW_KEY_C)) {
            showContacts = !showContacts;
        } else if (frame.input().pressed(GLFW_KEY_N)) {
            target = Math.min(sim.capacity(), target + 1000);
        }
        camera = orbit.camera(frame.aspect());

        for (int k = 0; k < options.perFrame() && sim.dynamicCount() < target; k++) {
            sim.spawn();
        }
        long t0 = System.nanoTime();
        sim.step();
        long dt = System.nanoTime() - t0;
        stepMs = dt / 1e6;
        if (options.verify() && frame.frame() % STEPS_PER_VERIFY == 0 && sim.lost() > 0) {
            throw new IllegalStateException(sim.lost() + " bodies have left the pit or are not numbers, at frame " + frame.frame() + ": " + sim.describeLost());
        }

        Stats stats = ctx.stats();
        stats.recordNanos(broadSeries, sim.broadNs());
        stats.recordNanos(narrowSeries, sim.narrowNs());
        stats.recordNanos(solveSeries, sim.solveNs());
        stats.recordNanos(integrateSeries, sim.integrateNs());
        stats.recordNanos(stepSeries, dt);
        stats.record(perBodySeries, sim.dynamicCount() == 0 ? 0.0 : dt / 1e3 / sim.dynamicCount());
        stats.record(bodiesSeries, sim.dynamicCount());
        stats.record(pairsSeries, sim.pairs());
        stats.record(contactsSeries, sim.contacts());

        boxStream.begin();
        sphereStream.begin();
        int boxes = 0, spheres = 0;
        for (int i = 0; i < sim.bodyCount(); i++) {
            RigidBody b = sim.body(i);
            double x = b.qx, y = b.qy, z = b.qz, w = b.qw;
            double sx = 2 * sim.halfX(i), sy = 2 * sim.halfY(i), sz = 2 * sim.halfZ(i);
            rows[0] = (float) ((1 - 2 * (y * y + z * z)) * sx);
            rows[1] = (float) (2 * (x * y - z * w) * sy);
            rows[2] = (float) (2 * (x * z + y * w) * sz);
            rows[3] = (float) b.px;
            rows[4] = (float) (2 * (x * y + z * w) * sx);
            rows[5] = (float) ((1 - 2 * (x * x + z * z)) * sy);
            rows[6] = (float) (2 * (y * z - x * w) * sz);
            rows[7] = (float) b.py;
            rows[8] = (float) (2 * (x * z - y * w) * sx);
            rows[9] = (float) (2 * (y * z + x * w) * sy);
            rows[10] = (float) ((1 - 2 * (x * x + y * y)) * sz);
            rows[11] = (float) b.pz;
            if (sim.isSphere(i)) {
                sphereStream.writeInstance(spheres++, rows, i);
            } else {
                boxStream.writeInstance(boxes++, rows, i);
            }
        }
        boxStream.setCount(boxes);
        sphereStream.setCount(spheres);
    }

    @Override
    public void render(FrameInfo frame) {
        renderer.draw(camera, boxStream, sphereStream, sim.staticCount());
        if (showContacts) {
            lines.clear();
            for (int k = 0; k < sim.manifoldCount(); k++) {
                ContactManifold m = sim.manifold(k);
                for (int i = 0; i < m.count(); i++) {
                    float px = (float) m.point(i, 0), py = (float) m.point(i, 1), pz = (float) m.point(i, 2);
                    lines.setColor(1f, 0.1f, 0.1f, 1f).cross(px, py, pz, 0.12f);
                    lines.setColor(1f, 0.9f, 0.1f, 1f).line(px, py, pz, px + (float) m.nx * 0.5f, py + (float) m.ny * 0.5f, pz + (float) m.nz * 0.5f);
                }
            }
            ctx.debug().draw(lines, camera.viewProjection());
        }
        boxStream.end();
        sphereStream.end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        hud.line(String.format(Locale.ROOT, "%,d bodies (of %,d) | %,d pairs | %,d manifolds, %,d contact points", sim.dynamicCount(), target, sim.pairs(), sim.manifoldCount(),
                sim.contacts()));
        hud.line(String.format(Locale.ROOT, "step %.2f ms: broad %.2f | narrow %.2f | solve %.2f | integrate %.2f  (%.2f us per body)", stepMs, sim.broadNs() / 1e6,
                sim.narrowNs() / 1e6, sim.solveNs() / 1e6, sim.integrateNs() / 1e6, sim.dynamicCount() == 0 ? 0.0 : stepMs * 1e3 / sim.dynamicCount()));
        hud.line(String.format(Locale.ROOT, "kinetic energy %.0f J | bodies out of the pit: %d", sim.kineticEnergy(), sim.lost()));
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "%,d dynamic bodies at the end, %d out of the pit, kinetic energy %.1f J", sim.dynamicCount(), sim.lost(), sim.kineticEnergy()));
        stats.note("stalls waiting for the GPU: " + boxStream.stalls() + " (boxes), " + sphereStream.stalls() + " (spheres)");
    }

    @Override
    public void dispose() {
        boxStream.close();
        sphereStream.close();
        renderer.dispose();
    }
}
