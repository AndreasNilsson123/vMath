package vmath.simd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;
import vmath.spatial.CullContext;
import vmath.spatial.CullStages;
import vmath.spatial.FrustumCuller;
import vmath.spatial.FrustumKernel;
import vmath.spatial.FrustumKernels;

class SimdFrustumCullerTest {

    final SplittableRandom r = new SplittableRandom(Long.getLong("vmath.seed", 0x5EEDL));

    private double range(double lo, double hi) {
        return lo + (hi - lo) * r.nextDouble();
    }

    private Frustumf randomFrustum() {
        float fovy = (float) range(0.5, 1.8), aspect = (float) range(0.8, 2.2), near = (float) range(0.1, 1);
        Vec3f eye = new Vec3f((float) range(-10, 10), (float) range(-10, 10), (float) range(-10, 10));
        Vec3f dir = new Vec3f((float) range(-1, 1), (float) range(-1, 1), (float) range(-1, 1));
        Mat4f view = Mat4f.lookAt(eye, eye.add(dir), Math.abs(dir.normalize().y()) > 0.95f ? Vec3f.UNIT_X : Vec3f.UNIT_Y);
        return switch (r.nextInt(3)) {
            case 0 -> Frustumf.fromViewProjection(Mat4f.perspective(fovy, aspect, near, near * 200f, false).mul(view),
                    DepthRange.NEGATIVE_ONE_TO_ONE);
            case 1 -> Frustumf.fromViewProjection(Mat4f.perspective(fovy, aspect, near, near * 200f, true).mul(view),
                    DepthRange.ZERO_TO_ONE);
            default -> Frustumf.fromViewProjection(Mat4f.perspectiveReversedZ(fovy, aspect, near).mul(view),
                    DepthRange.REVERSED_ZERO_TO_ONE);
        };
    }

    private BoundsArray scene(int n) {
        BoundsArray b = new BoundsArray(Math.max(n, 1));
        for (int i = 0; i < n; i++) {
            float cx = (float) range(-40, 40), cy = (float) range(-40, 40), cz = (float) range(-40, 40);
            float hx = (float) range(0.01, 2), hy = (float) range(0.01, 2), hz = (float) range(0.01, 2);
            b.add(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
        }
        return b;
    }

    private static void assertSameBits(VisibilitySet expected, VisibilitySet actual, String what) {
        long[] e = expected.words(), a = actual.words();
        assertEquals(e.length, a.length);
        for (int i = 0; i < e.length; i++) {
            assertEquals(Long.toHexString(e[i]), Long.toHexString(a[i]), what + ": word " + i);
        }
    }

    @Test
    void producesExactlyTheSameBitsAsTheScalarKernel() {
        FrustumKernel simd = new SimdFrustumCuller();
        FrustumKernel scalar = new FrustumCuller();
        int[] sizes = {0, 1, 2, 3, 7, 8, 9, 15, 16, 17, 63, 64, 65, 127, 128, 129, 1000, 1023, 1024, 1025, 4097, 20000};
        for (int n : sizes) {
            for (int rep = 0; rep < 4; rep++) {
                Frustumf f = randomFrustum();
                BoundsArray b = scene(n);
                VisibilitySet a = new VisibilitySet(n + 130), c = new VisibilitySet(n + 130);
                a.setAll(n);
                c.setAll(n);
                scalar.cull(f, b, a);
                simd.cull(f, b, c);
                assertSameBits(a, c, "n=" + n);
                assertEquals(a.count(), c.count());
            }
        }
    }

    @Test
    void refinesAnExistingSetAndHonoursRanges() {
        FrustumKernel simd = new SimdFrustumCuller();
        FrustumKernel scalar = new FrustumCuller();
        int n = 5000;
        for (int rep = 0; rep < 10; rep++) {
            Frustumf f = randomFrustum();
            BoundsArray b = scene(n);
            VisibilitySet a = new VisibilitySet(n), c = new VisibilitySet(n);
            a.setAll(n);
            c.setAll(n);
            for (int i = 0; i < n; i++) {
                if (r.nextInt(4) == 0) { // earlier stages already rejected a quarter of the objects
                    a.clear(i);
                    c.clear(i);
                }
            }
            int from = 64 * r.nextInt(20), to = from + r.nextInt(n - from);
            scalar.cull(f, b, from, to, a);
            simd.cull(f, b, from, to, c);
            assertSameBits(a, c, "range [" + from + ", " + to + ")");
        }
        assertThrows(IllegalArgumentException.class, () -> simd.cull(randomFrustum(), scene(10), 3, 10, new VisibilitySet(10)));
    }

    @Test
    void nanBoundsStayVisibleLikeTheScalarKernel() {
        BoundsArray b = scene(200);
        b.set(5, Float.NaN, 0f, 0f, 1f, 1f, 1f);
        b.set(70, 0f, 0f, 0f, Float.NaN, 1f, 1f);
        b.set(199, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
        Frustumf f = randomFrustum();
        VisibilitySet a = new VisibilitySet(200), c = new VisibilitySet(200);
        a.setAll(200);
        c.setAll(200);
        new FrustumCuller().cull(f, b, a);
        new SimdFrustumCuller().cull(f, b, c);
        assertSameBits(a, c, "NaN bounds");
        assertTrue(c.get(199), "an object whose bounds are all NaN cannot be judged, so it is kept");
    }

    @Test
    void isSelectedByTheServiceLoaderAndCanBeOverridden() {
        assertTrue(FrustumKernels.available().contains("simd"), "available: " + FrustumKernels.available());
        assertEquals("simd", FrustumKernels.best().name());
        assertEquals("scalar", FrustumKernels.scalar().name());
        assertTrue(FrustumKernels.best() != FrustumKernels.best(), "each call returns a fresh instance");
        String old = System.getProperty("vmath.kernel");
        try {
            System.setProperty("vmath.kernel", "scalar");
            // "scalar" is not a provider, so a forced scalar choice must fall back to the scalar kernel
            assertEquals("scalar", FrustumKernels.best().name());
            System.setProperty("vmath.kernel", "simd");
            assertEquals("simd", FrustumKernels.best().name());
        } finally {
            if (old == null) {
                System.clearProperty("vmath.kernel");
            } else {
                System.setProperty("vmath.kernel", old);
            }
        }
    }

    @Test
    void cullStageUsesTheBestKernel() {
        Frustumf f = randomFrustum();
        BoundsArray b = scene(3000);
        CullContext ctx = new CullContext(f, Vec3f.ZERO, 0f);
        VisibilitySet viaStage = new VisibilitySet(3000), viaScalar = new VisibilitySet(3000);
        viaStage.setAll(3000);
        viaScalar.setAll(3000);
        new CullStages.Frustum().cull(ctx, b, viaStage);
        new CullStages.Frustum(new FrustumCuller()).cull(ctx, b, viaScalar);
        assertSameBits(viaScalar, viaStage, "stage");
        assertTrue(viaStage.count() > 0 && viaStage.count() < 3000, "a meaningful mix: " + viaStage.count());
    }

    @Test
    void moduleDeclaresItsProviderAndNeedsTheIncubatorModule() {
        String p = System.getProperty("vmath.simd.jar");
        if (p == null || !Files.exists(Path.of(p))) {
            return;
        }
        ModuleDescriptor d = ModuleFinder.of(Path.of(p)).find("vmath.simd").orElseThrow().descriptor();
        assertEquals(Set.of("vmath.simd"), d.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet()));
        Set<String> requires = d.requires().stream().map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());
        assertTrue(requires.contains("vmath") && requires.contains("jdk.incubator.vector"), "requires " + requires);
        assertFalse(d.requires().stream().filter(x -> x.name().equals("jdk.incubator.vector"))
                .anyMatch(x -> x.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)));
        assertEquals(Set.of("vmath.simd.SimdFrustumKernelProvider"),
                d.provides().stream().filter(x -> x.service().equals("vmath.spatial.FrustumKernelProvider"))
                        .flatMap(x -> x.providers().stream()).collect(Collectors.toSet()));
    }
}
