package vmath.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import vmath.core.Vec2f;
import vmath.core.Vec3f;

/** The allocation-free {@code pack16(x, y, z)} and {@code pack8(x, y, z)} give the same bits as the best-of-four search written with the value types. */
class OctahedralPrimitivesTest {

    private static final long SEED = Long.getLong("vmath.seed", 101L);

    /** The search as it was written before the primitive version: encode, try floor and ceil of both scaled components, keep the closest decode. */
    private static int reference(Vec3f n, float scale, boolean sixteenBit) {
        Vec2f e = Octahedral.encode(n);
        float sx = e.x() * scale, sy = e.y() * scale;
        int fx = (int) Math.floor(sx), fy = (int) Math.floor(sy);
        float bestError = Float.POSITIVE_INFINITY;
        int bestX = 0, bestY = 0;
        int max = (int) scale;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = 0; dx <= 1; dx++) {
                int qx = Math.max(-max, Math.min(max, fx + dx)), qy = Math.max(-max, Math.min(max, fy + dy));
                float error = Octahedral.decode(qx / scale, qy / scale).cross(n).lengthSquared();
                if (error < bestError) {
                    bestError = error;
                    bestX = qx;
                    bestY = qy;
                }
            }
        }
        return sixteenBit ? (bestX & 0xFFFF) | ((bestY & 0xFFFF) << 16) : (bestX & 0xFF) | ((bestY & 0xFF) << 8);
    }

    @Test
    void primitiveVersionsGiveTheSameBitsAsTheValueTypeSearch() {
        SplittableRandom r = new SplittableRandom(SEED);
        for (int k = 0; k < 300_000; k++) {
            Vec3f n = new Vec3f((float) (r.nextDouble() * 2 - 1), (float) (r.nextDouble() * 2 - 1), (float) (r.nextDouble() * 2 - 1));
            if (k % 7 == 0) {
                n = new Vec3f(r.nextBoolean() ? 1f : -1f, 0f, 0f);       // on an axis, and so on the borders of the unfolded octahedron
            } else if (k % 11 == 0) {
                n = new Vec3f(0f, (float) (r.nextDouble() - 0.5), r.nextBoolean() ? -1f : 1f);
            }
            n = n.normalize();
            if (!Float.isFinite(n.x())) {
                continue;
            }
            assertEquals(reference(n, 32767f, true), Octahedral.pack16(n.x(), n.y(), n.z()), "pack16 of " + n);
            assertEquals(reference(n, 127f, false), Octahedral.pack8(n.x(), n.y(), n.z()), "pack8 of " + n);
            assertEquals(Octahedral.pack16(n), Octahedral.pack16(n.x(), n.y(), n.z()));
        }
    }

    @Test
    void theComponentVersionOfTheQuantizerAgrees() {
        vmath.geo.Aabbf box = new vmath.geo.Aabbf(-3f, 1f, 10f, 5f, 2f, 16f);
        Quantizer q = new Quantizer(box);
        SplittableRandom r = new SplittableRandom(SEED + 1);
        short[] a = new short[3], b = new short[3];
        for (int k = 0; k < 10_000; k++) {
            Vec3f p = new Vec3f((float) (-4 + r.nextDouble() * 10), (float) (r.nextDouble() * 3), (float) (9 + r.nextDouble() * 8));
            q.pack(p, a, 0);
            q.pack(p.x(), p.y(), p.z(), b, 0);
            assertEquals(a[0], b[0]);
            assertEquals(a[1], b[1]);
            assertEquals(a[2], b[2]);
        }
    }
}
