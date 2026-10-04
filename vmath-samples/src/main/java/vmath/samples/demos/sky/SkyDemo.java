package vmath.samples.demos.sky;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.opengl.GL45.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL45.GL_CULL_FACE;
import static org.lwjgl.opengl.GL45.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL45.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL45.GL_FLOAT;
import static org.lwjgl.opengl.GL45.GL_LINEAR;
import static org.lwjgl.opengl.GL45.GL_REPEAT;
import static org.lwjgl.opengl.GL45.GL_RGB;
import static org.lwjgl.opengl.GL45.GL_RGB32F;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL45.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL45.GL_TRIANGLES;
import static org.lwjgl.opengl.GL45.glBindBufferBase;
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
import static org.lwjgl.opengl.GL45.glDispatchCompute;
import static org.lwjgl.opengl.GL45.glDrawArrays;
import static org.lwjgl.opengl.GL45.glGetNamedBufferSubData;
import static org.lwjgl.opengl.GL45.glGetUniformLocation;
import static org.lwjgl.opengl.GL45.glMemoryBarrier;
import static org.lwjgl.opengl.GL45.glNamedBufferStorage;
import static org.lwjgl.opengl.GL45.glProgramUniform1f;
import static org.lwjgl.opengl.GL45.glProgramUniform1i;
import static org.lwjgl.opengl.GL45.glProgramUniform2f;
import static org.lwjgl.opengl.GL45.glProgramUniform3f;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTextureSubImage2D;
import static org.lwjgl.opengl.GL45.glUseProgram;

import java.nio.FloatBuffer;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryUtil;
import vmath.camera.Cameraf;
import vmath.camera.PhysicalCamera;
import vmath.color.Srgb;
import vmath.color.ToneMap;
import vmath.core.Vec3f;
import vmath.samples.framework.Demo;
import vmath.samples.framework.DemoContext;
import vmath.samples.framework.DemoInfo;
import vmath.samples.framework.FlyCamera;
import vmath.samples.framework.FrameInfo;
import vmath.samples.framework.Gl;
import vmath.samples.framework.Hud;
import vmath.samples.framework.Sliders;
import vmath.samples.framework.Stats;

/**
 * The light of a whole day on a lawn with four spheres, from the library's models: the sun's place
 * from the date, the time and the latitude ({@code SolarPosition}), the sky in every direction
 * ({@code PreethamSky}), the colour and strength of the sunlight after the air ({@code Atmosphere}),
 * the exposure of a camera with an aperture, a shutter time and a sensitivity
 * ({@code PhysicalCamera}), and a choice of tone-mapping curves ({@code ToneMap}, with {@code Srgb}
 * for the display).
 *
 * <p>The sky is computed on the CPU into a table of linear radiance in cd/m<sup>2</sup>
 * ({@link SkyModel}) and sampled by the shader, which also lights the scene with the sun's
 * illuminance and the sky's irradiance in lux, so that the numbers on the screen are the physical
 * ones. The camera's exposure scale multiplies the radiance, the curve maps it to the display and
 * the result is encoded as sRGB. The tone curves are ported to GLSL; {@code --verify} evaluates
 * them in a compute shader over a range of values and compares them with {@code ToneMap} and
 * {@code Srgb}, failing the run on a difference.
 *
 * <p>The sliders (drag with the left mouse button) set the hour, the day, the latitude, the
 * turbidity, the focal length, the aperture, the sensitivity, the shutter time and the exposure
 * compensation; the checkbox chooses an automatic shutter time that exposes a mid-grey card in the
 * scene to middle grey ({@code PhysicalCamera.ev100ForLuminance}, {@code shutterTimeFor}); the buttons
 * choose the curve. Dragging with the left mouse button elsewhere looks around, {@code Space} runs the
 * clock. A scripted run goes through a day of the summer solstice at 59.3 degrees north in 1,200
 * frames with the automatic exposure and the ACES curve.
 *
 * <p>Internal: a demo, not part of the library's API.
 *
 * <p><b>Thread safety.</b> Everything happens on the thread that owns the OpenGL context.
 */
public final class SkyDemo implements Demo {

    /**
     * The description of the demo, registered in {@code vmath.samples.Demos}.
     */
    public static final DemoInfo INFO = new DemoInfo("sky-sun", "Sky, sun, camera exposure and tone mapping",
            "A day of sunlight from the library's sun, sky and atmosphere models, exposed by a physical camera and tone mapped, with sliders for everything.",
            List.of("rendering"), 4096, List.of("--verify"),
            "sliders: drag with the left mouse | left mouse elsewhere: look around | Space: run the clock | R: reset the view");

    private static final String[] CURVES = {"clamp", "Reinhard", "Reinhard+", "ACES", "Hable", "1-exp"};
    private static final float WHITE_POINT = 4f;
    private static final double MIN_SHUTTER = 1.0 / 8000.0;
    private static final double MAX_SHUTTER = 30.0;
    private static final int VERIFY_VALUES = 2048;
    private static final float TOLERANCE = 2e-5f;

    private final boolean verify;
    private final SkyModel model = new SkyModel();
    private final Sliders sliders = new Sliders();
    private DemoContext ctx;
    private FlyCamera look;
    private Cameraf camera;
    private int program;
    private int vao;
    private int texture;
    private FloatBuffer upload;
    private int camPosLocation;
    private int camRightLocation;
    private int camUpLocation;
    private int camForwardLocation;
    private int tanHalfLocation;
    private int sunDirLocation;
    private int sunELocation;
    private int skyELocation;
    private int sunLLocation;
    private int exposureLocation;
    private int whiteLocation;
    private int curveLocation;
    private float hour = 12f;
    private float day = 172f;
    private float latitude = 59.3f;
    private float turbidity = 3f;
    private float focalLength = 24f;
    private float apertureStops = 7f;
    private float isoStops = 0f;
    private float shutterStops = -8f;
    private float compensation = 0f;
    private boolean auto = true;
    private boolean running;
    private int curve = 3;
    private double fNumber;
    private double iso;
    private double shutter;
    private double ev100;
    private double exposure;
    private double verticalFov;
    private double verifiedError;
    private int modelSeries;
    private int uploadSeries;
    private int evSeries;
    private int elevationSeries;
    private int exposureSeries;
    private int shutterSeries;

    /**
     * Creates the demo from its command-line arguments.
     *
     * @param args the demo's arguments, {@code --verify} or nothing; must not be {@code null}
     * @throws IllegalArgumentException if an argument is unknown
     */
    public SkyDemo(List<String> args) {
        boolean v = false;
        for (String a : args) {
            if (a.equals("--verify")) {
                v = true;
            } else {
                throw new IllegalArgumentException("unknown argument " + a + "\noptions of the sky-sun demo:\n  --verify   check the tone curves and the sRGB encoding of the shader against the library on the GPU\n");
            }
        }
        this.verify = v;
    }

    @Override
    public void create(DemoContext ctx) {
        this.ctx = ctx;
        program = Gl.program(SkyShaders.vertex(), SkyShaders.fragment());
        camPosLocation = glGetUniformLocation(program, "camPos");
        camRightLocation = glGetUniformLocation(program, "camRight");
        camUpLocation = glGetUniformLocation(program, "camUp");
        camForwardLocation = glGetUniformLocation(program, "camForward");
        tanHalfLocation = glGetUniformLocation(program, "tanHalf");
        sunDirLocation = glGetUniformLocation(program, "sunDir");
        sunELocation = glGetUniformLocation(program, "sunE");
        skyELocation = glGetUniformLocation(program, "skyE");
        sunLLocation = glGetUniformLocation(program, "sunL");
        exposureLocation = glGetUniformLocation(program, "exposure");
        whiteLocation = glGetUniformLocation(program, "whitePoint");
        curveLocation = glGetUniformLocation(program, "curve");
        vao = glCreateVertexArrays();
        texture = glCreateTextures(GL_TEXTURE_2D);
        glTextureStorage2D(texture, 1, GL_RGB32F, SkyModel.WIDTH, SkyModel.HEIGHT);
        glTextureParameteri(texture, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTextureParameteri(texture, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTextureParameteri(texture, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        upload = MemoryUtil.memAllocFloat(SkyModel.WIDTH * SkyModel.HEIGHT * 3);
        look = new FlyCamera(new Vec3f(0f, 1.7f, 0f), 0.35f, 0.12f, 1f, 0.1f, 1000f, 0f, 0f);

        Stats stats = ctx.stats();
        modelSeries = stats.timer("sky model", "sun, sky table, sun light and sky irradiance on the CPU");
        uploadSeries = stats.timer("table upload", "the sky table into the texture");
        evSeries = stats.series("EV100", "", 2, "exposure value of the camera settings");
        exposureSeries = stats.series("exposure scale", "", 6, "what scene luminance in cd/m2 is multiplied by to reach the sensor");
        shutterSeries = stats.series("shutter time", "s", 5, "the automatic shutter time");
        elevationSeries = stats.series("sun elevation", "deg", 1, "above the horizon");
        ctx.clearColor(0f, 0f, 0f);
        if (verify) {
            verifyCurves();
        }
    }

    @Override
    public void update(FrameInfo frame) {
        if (frame.benchmark()) {
            hour = (float) ((frame.frame() / 1200.0 * 24.0) % 24.0);
            day = 172f;
            latitude = 59.3f;
            turbidity = 3f;
            auto = true;
            curve = 3;
            compensation = 0f;
            look.place(0f, 1.7f, 0f, 0.35f, 0.12f);
        } else {
            var in = frame.input();
            if (in.pressed(GLFW_KEY_SPACE)) {
                running = !running;
            }
            if (running) {
                hour = (float) ((hour + frame.dt() * 0.5) % 24.0);
            }
            if (!sliders.capturing()) {
                look.update(frame);
            }
        }
        camera = look.camera(frame.aspect());

        long t0 = System.nanoTime();
        model.update(hour, Math.round(day), latitude, turbidity);
        long t1 = System.nanoTime();
        upload.clear();
        upload.put(model.radiance()).flip();
        glTextureSubImage2D(texture, 0, 0, 0, SkyModel.WIDTH, SkyModel.HEIGHT, GL_RGB, GL_FLOAT, upload);
        long t2 = System.nanoTime();

        fNumber = PhysicalCamera.fNumberOfStops(apertureStops);
        iso = 100.0 * Math.pow(2.0, isoStops);
        if (auto) {
            double target = PhysicalCamera.ev100ForLuminance(model.cardLuminance()) - compensation;
            shutter = Math.max(MIN_SHUTTER, Math.min(MAX_SHUTTER, PhysicalCamera.shutterTimeFor(target, fNumber, iso)));
        } else {
            shutter = Math.max(MIN_SHUTTER, Math.min(MAX_SHUTTER, Math.pow(2.0, shutterStops)));
        }
        PhysicalCamera physical = PhysicalCamera.fullFrame(focalLength, fNumber, shutter, iso, 10.0);
        ev100 = physical.ev100();
        exposure = physical.exposure();
        verticalFov = physical.verticalFov();

        Stats stats = ctx.stats();
        stats.recordNanos(modelSeries, t1 - t0);
        stats.recordNanos(uploadSeries, t2 - t1);
        stats.record(evSeries, ev100);
        stats.record(exposureSeries, exposure);
        stats.record(shutterSeries, shutter);
        stats.record(elevationSeries, Math.toDegrees(model.elevation()));
    }

    @Override
    public void render(FrameInfo frame) {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        ctx.gpu().begin();
        glUseProgram(program);
        Vec3f p = camera.position(), r = camera.right(), u = camera.up(), f = camera.forward();
        float tanY = (float) Math.tan(verticalFov * 0.5);
        glProgramUniform3f(program, camPosLocation, p.x(), p.y(), p.z());
        glProgramUniform3f(program, camRightLocation, r.x(), r.y(), r.z());
        glProgramUniform3f(program, camUpLocation, u.x(), u.y(), u.z());
        glProgramUniform3f(program, camForwardLocation, f.x(), f.y(), f.z());
        glProgramUniform2f(program, tanHalfLocation, tanY * frame.aspect(), tanY);
        double[] s = model.sunDirection(), e = model.sunIlluminance(), k = model.skyIrradiance(), l = model.sunRadiance();
        glProgramUniform3f(program, sunDirLocation, (float) s[0], (float) s[1], (float) s[2]);
        glProgramUniform3f(program, sunELocation, (float) e[0], (float) e[1], (float) e[2]);
        glProgramUniform3f(program, skyELocation, (float) k[0], (float) k[1], (float) k[2]);
        glProgramUniform3f(program, sunLLocation, (float) l[0], (float) l[1], (float) l[2]);
        glProgramUniform1f(program, exposureLocation, (float) exposure);
        glProgramUniform1f(program, whiteLocation, WHITE_POINT);
        glProgramUniform1i(program, curveLocation, curve);
        glBindTextureUnit(0, texture);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        ctx.gpu().end();
    }

    @Override
    public void hud(Hud hud, FrameInfo frame) {
        double[] e = model.sunIlluminance(), k = model.skyIrradiance();
        hud.line(String.format(Locale.ROOT, "sun: %.1f degrees up, azimuth %.0f | direct %,.0f lux | sky %,.0f lux | zenith %,.0f cd/m2", Math.toDegrees(model.elevation()),
                Math.toDegrees(model.azimuth()), e[1], k[1], model.zenithLuminance()));
        hud.line(String.format(Locale.ROOT, "camera: f/%.1f, 1/%.0f s, ISO %.0f, %.0f mm (%.0f degrees) | EV100 %.1f | exposure scale %.2e", fNumber, 1.0 / shutter, iso, focalLength,
                Math.toDegrees(verticalFov), ev100, exposure));
        hud.line(String.format(Locale.ROOT, "a mid-grey card in the scene: %,.1f cd/m2%s", model.cardLuminance(), auto ? " (the automatic shutter exposes it to middle grey)" : ""));
        if (verify) {
            hud.line(String.format(Locale.ROOT, "tone curves of the shader against the library: largest error %.1e", verifiedError));
        }
        float lh = Sliders.height(hud);
        float width = 270f, x0 = 12f, x1 = 12f + width + 36f, x2 = x1 + width + 36f;
        float base = frame.height() - 2 * hud.lineHeight() - 40f - 4 * lh - 34f;
        hud.color(0.8f, 0.9f, 1f);
        curve = sliders.choice(hud, frame.input(), CURVES, curve, x0, base - 32f);
        auto = sliders.checkbox(hud, frame.input(), "automatic shutter", auto, x2, base - 30f);
        hour = sliders.slider(hud, frame.input(), "hour (UT)", String.format(Locale.ROOT, "%.2f", hour), hour, 0f, 24f, x0, base, width);
        day = sliders.slider(hud, frame.input(), "day of 2026", String.format(Locale.ROOT, "%.0f", day), day, 1f, 365f, x0, base + lh, width);
        latitude = sliders.slider(hud, frame.input(), "latitude", String.format(Locale.ROOT, "%.1f", latitude), latitude, -85f, 85f, x0, base + 2 * lh, width);
        turbidity = sliders.slider(hud, frame.input(), "turbidity", String.format(Locale.ROOT, "%.1f", turbidity), turbidity, 1.5f, 10f, x0, base + 3 * lh, width);
        focalLength = sliders.slider(hud, frame.input(), "focal length", String.format(Locale.ROOT, "%.0f mm", focalLength), focalLength, 14f, 200f, x1, base, width);
        apertureStops = sliders.slider(hud, frame.input(), "aperture", String.format(Locale.ROOT, "f/%.1f", fNumber), apertureStops, 0f, 9f, x1, base + lh, width);
        isoStops = sliders.slider(hud, frame.input(), "sensitivity", String.format(Locale.ROOT, "ISO %.0f", iso), isoStops, 0f, 6f, x1, base + 2 * lh, width);
        if (auto) {
            compensation = sliders.slider(hud, frame.input(), "exposure compensation", String.format(Locale.ROOT, "%+.1f EV", compensation), compensation, -4f, 4f, x1, base + 3 * lh, width);
        } else {
            shutterStops = sliders.slider(hud, frame.input(), "shutter time", String.format(Locale.ROOT, "1/%.0f s", 1.0 / shutter), shutterStops, -13f, 5f, x1, base + 3 * lh, width);
        }
        sliders.endFrame();
    }

    @Override
    public void report(Stats stats) {
        stats.note(String.format(Locale.ROOT, "a scripted day at 59.3 degrees north, solstice, turbidity 3, ACES, automatic shutter; %d x %d sky table", SkyModel.WIDTH, SkyModel.HEIGHT));
        if (verify) {
            stats.note(String.format(Locale.ROOT, "the shader's tone curves and sRGB encoding match the library at %d values for %d curves: largest error %.2e", VERIFY_VALUES, CURVES.length - 1,
                    verifiedError));
        }
    }

    /**
     * Evaluates each curve of the shader and the sRGB encoding for a range of values from 0.001 to
     * 1,000 (and 0), reads the results back and compares them with the library's {@code ToneMap} and
     * {@code Srgb}.
     *
     * @throws IllegalStateException if a value differs by more than the tolerance
     */
    private void verifyCurves() {
        float[] xs = new float[VERIFY_VALUES];
        xs[0] = 0f;
        for (int i = 1; i < VERIFY_VALUES; i++) {
            xs[i] = (float) Math.pow(10.0, -3.0 + 6.0 * (i - 1) / (VERIFY_VALUES - 2));
        }
        int compute = Gl.computeProgram(SkyShaders.compute());
        int in = glCreateBuffers(), out = glCreateBuffers();
        glNamedBufferStorage(in, xs, 0);
        glNamedBufferStorage(out, (long) VERIFY_VALUES * 2 * Float.BYTES, GL_DYNAMIC_STORAGE_BIT);
        FloatBuffer back = MemoryUtil.memAllocFloat(VERIFY_VALUES * 2);
        double worst = 0.0;
        try {
            glUseProgram(compute);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, in);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, out);
            glProgramUniform1i(compute, glGetUniformLocation(compute, "count"), VERIFY_VALUES);
            glProgramUniform1f(compute, glGetUniformLocation(compute, "whitePoint"), WHITE_POINT);
            int curveLoc = glGetUniformLocation(compute, "curve");
            for (int c = 1; c < CURVES.length; c++) {
                glProgramUniform1i(compute, curveLoc, c);
                glDispatchCompute((VERIFY_VALUES + 63) / 64, 1, 1);
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                back.clear();
                glGetNamedBufferSubData(out, 0, back);
                ToneMap.Curve curve = ToneMap.Curve.values()[c - 1];
                for (int i = 0; i < VERIFY_VALUES; i++) {
                    float expected = ToneMap.apply(curve, xs[i], WHITE_POINT);
                    float expectedSrgb = Srgb.fromLinear(expected);
                    double e1 = Math.abs(back.get(i * 2) - expected), e2 = Math.abs(back.get(i * 2 + 1) - expectedSrgb);
                    worst = Math.max(worst, Math.max(e1, e2));
                    if (e1 > TOLERANCE || e2 > TOLERANCE) {
                        throw new IllegalStateException(String.format(Locale.ROOT, "the shader's %s differs from the library at x = %g: curve %.7f against %.7f, sRGB %.7f against %.7f", CURVES[c],
                                xs[i], back.get(i * 2), expected, back.get(i * 2 + 1), expectedSrgb));
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(back);
            glDeleteBuffers(in);
            glDeleteBuffers(out);
            glDeleteProgram(compute);
        }
        verifiedError = worst;
        System.out.printf(Locale.ROOT, "verified the tone curves and the sRGB encoding of the shader against the library at %d values: largest error %.2e%n", VERIFY_VALUES, worst);
    }

    @Override
    public void dispose() {
        MemoryUtil.memFree(upload);
        glDeleteVertexArrays(vao);
        glDeleteTextures(texture);
        glDeleteProgram(program);
    }
}
