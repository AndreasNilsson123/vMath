package vmath.bench;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import vmath.bulk.BoundsArray;
import vmath.core.Mat4f;
import vmath.core.Vec3f;
import vmath.geo.DepthRange;
import vmath.geo.Frustumf;

/**
 * Diagnosis for the flat frustum kernel: which loop costs what, and which formulation the JIT handles best.
 * All variants process the same chunk-sized loops over the same 100k boxes; the score is per pass over all boxes.
 *
 * <ul>
 *   <li>{@code slackMinCall}: today's kernel, {@code Math.min} accumulation across planes</li>
 *   <li>{@code slackTernary}: the same with a ternary instead of {@code Math.min}</li>
 *   <li>{@code slackTwoPass}: each plane writes its own array, a second loop takes the minimum</li>
 *   <li>{@code slackFused}: all six planes in one loop over objects (no scratch traffic)</li>
 *   <li>{@code packBranchy}, {@code packSignBit}: turning the slack array into bitset words</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CullKernelBench {

    private static final int N = 100_000;
    private static final int CHUNK = 1024;

    @Param({"256", "512", "1024", "2048"})
    public int chunk;

    private BoundsArray b;
    private final float[] planes = new float[24];
    private final float[] slack = new float[2048];
    private final float[][] perPlane = new float[6][2048];
    private float[] allSlack;
    private long[] words;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(3);
        b = new BoundsArray(N);
        for (int i = 0; i < N; i++) {
            float cx = (float) (r.nextDouble() * 2 - 1) * 500, cy = (float) (r.nextDouble() * 2 - 1) * 500;
            float cz = (float) (r.nextDouble() * 2 - 1) * 500, h = 0.2f + (float) r.nextDouble() * 2f;
            b.add(cx - h, cy - h, cz - h, cx + h, cy + h, cz + h);
        }
        Mat4f view = Mat4f.lookAt(Vec3f.ZERO, new Vec3f(0.3f, 0.1f, -1f), Vec3f.UNIT_Y);
        Frustumf f = Frustumf.fromViewProjection(Mat4f.perspective(1.0f, 16f / 9f, 0.1f, 750f, true).mul(view),
                DepthRange.ZERO_TO_ONE);
        f.writeTo(planes, 0);
        allSlack = new float[N];
        words = new long[(N + 63) / 64];
        // a realistic slack array for the pack benchmarks
        for (int start = 0; start < N; start += CHUNK) {
            slackMinCallChunk(start, Math.min(CHUNK, N - start));
            System.arraycopy(slack, 0, allSlack, start, Math.min(CHUNK, N - start));
        }
    }

    // ------------------------------------------------------------ slack variants

    private void slackMinCallChunk(int start, int n) {
        float[] s = slack;
        for (int p = 0; p < 6; p++) {
            float nx = planes[p * 4], ny = planes[p * 4 + 1], nz = planes[p * 4 + 2], d = planes[p * 4 + 3];
            float[] px = nx >= 0f ? b.maxXs() : b.minXs();
            float[] py = ny >= 0f ? b.maxYs() : b.minYs();
            float[] pz = nz >= 0f ? b.maxZs() : b.minZs();
            if (p == 0) {
                for (int i = 0; i < n; i++) {
                    s[i] = d + nx * px[start + i] + ny * py[start + i] + nz * pz[start + i];
                }
            } else {
                for (int i = 0; i < n; i++) {
                    s[i] = Math.min(s[i], d + nx * px[start + i] + ny * py[start + i] + nz * pz[start + i]);
                }
            }
        }
    }

    @Benchmark
    public float slackMinCall() {
        for (int start = 0; start < N; start += CHUNK) {
            slackMinCallChunk(start, Math.min(CHUNK, N - start));
        }
        return slack[0];
    }

    @Benchmark
    public float slackTernary() {
        float[] s = slack;
        for (int start = 0; start < N; start += CHUNK) {
            int n = Math.min(CHUNK, N - start);
            for (int p = 0; p < 6; p++) {
                float nx = planes[p * 4], ny = planes[p * 4 + 1], nz = planes[p * 4 + 2], d = planes[p * 4 + 3];
                float[] px = nx >= 0f ? b.maxXs() : b.minXs();
                float[] py = ny >= 0f ? b.maxYs() : b.minYs();
                float[] pz = nz >= 0f ? b.maxZs() : b.minZs();
                if (p == 0) {
                    for (int i = 0; i < n; i++) {
                        s[i] = d + nx * px[start + i] + ny * py[start + i] + nz * pz[start + i];
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        float v = d + nx * px[start + i] + ny * py[start + i] + nz * pz[start + i];
                        s[i] = v < s[i] ? v : s[i];
                    }
                }
            }
        }
        return s[0];
    }

    @Benchmark
    public float slackTwoPass() {
        float[] s = slack;
        for (int start = 0; start < N; start += chunk) {
            int n = Math.min(chunk, N - start);
            for (int p = 0; p < 6; p++) {
                float nx = planes[p * 4], ny = planes[p * 4 + 1], nz = planes[p * 4 + 2], d = planes[p * 4 + 3];
                float[] px = nx >= 0f ? b.maxXs() : b.minXs();
                float[] py = ny >= 0f ? b.maxYs() : b.minYs();
                float[] pz = nz >= 0f ? b.maxZs() : b.minZs();
                float[] out = perPlane[p];
                for (int i = 0; i < n; i++) {
                    out[i] = d + nx * px[start + i] + ny * py[start + i] + nz * pz[start + i];
                }
            }
            float[] a = perPlane[0], b1 = perPlane[1], c = perPlane[2], d2 = perPlane[3], e = perPlane[4], f = perPlane[5];
            for (int i = 0; i < n; i++) {
                s[i] = Math.min(Math.min(Math.min(a[i], b1[i]), Math.min(c[i], d2[i])), Math.min(e[i], f[i]));
            }
        }
        return s[0];
    }

    @Benchmark
    public float slackFused() {
        float[] s = slack;
        float[] x0 = b.minXs(), y0 = b.minYs(), z0 = b.minZs(), x1 = b.maxXs(), y1 = b.maxYs(), z1 = b.maxZs();
        // per-plane p-vertex selection hoisted as constants
        float[][] ax = new float[6][], ay = new float[6][], az = new float[6][];
        for (int p = 0; p < 6; p++) {
            ax[p] = planes[p * 4] >= 0f ? x1 : x0;
            ay[p] = planes[p * 4 + 1] >= 0f ? y1 : y0;
            az[p] = planes[p * 4 + 2] >= 0f ? z1 : z0;
        }
        for (int start = 0; start < N; start += CHUNK) {
            int n = Math.min(CHUNK, N - start);
            for (int i = 0; i < n; i++) {
                int k = start + i;
                float m = planes[3] + planes[0] * ax[0][k] + planes[1] * ay[0][k] + planes[2] * az[0][k];
                m = Math.min(m, planes[7] + planes[4] * ax[1][k] + planes[5] * ay[1][k] + planes[6] * az[1][k]);
                m = Math.min(m, planes[11] + planes[8] * ax[2][k] + planes[9] * ay[2][k] + planes[10] * az[2][k]);
                m = Math.min(m, planes[15] + planes[12] * ax[3][k] + planes[13] * ay[3][k] + planes[14] * az[3][k]);
                m = Math.min(m, planes[19] + planes[16] * ax[4][k] + planes[17] * ay[4][k] + planes[18] * az[4][k]);
                m = Math.min(m, planes[23] + planes[20] * ax[5][k] + planes[21] * ay[5][k] + planes[22] * az[5][k]);
                s[i] = m;
            }
        }
        return s[0];
    }

    @Benchmark
    public float slackFusedLocals() {
        float[] s = slack;
        float[] x0 = b.minXs(), y0 = b.minYs(), z0 = b.minZs(), x1 = b.maxXs(), y1 = b.maxYs(), z1 = b.maxZs();
        float[] ax0 = planes[0] >= 0f ? x1 : x0, ay0 = planes[1] >= 0f ? y1 : y0, az0 = planes[2] >= 0f ? z1 : z0;
        float[] ax1 = planes[4] >= 0f ? x1 : x0, ay1 = planes[5] >= 0f ? y1 : y0, az1 = planes[6] >= 0f ? z1 : z0;
        float[] ax2 = planes[8] >= 0f ? x1 : x0, ay2 = planes[9] >= 0f ? y1 : y0, az2 = planes[10] >= 0f ? z1 : z0;
        float[] ax3 = planes[12] >= 0f ? x1 : x0, ay3 = planes[13] >= 0f ? y1 : y0, az3 = planes[14] >= 0f ? z1 : z0;
        float[] ax4 = planes[16] >= 0f ? x1 : x0, ay4 = planes[17] >= 0f ? y1 : y0, az4 = planes[18] >= 0f ? z1 : z0;
        float[] ax5 = planes[20] >= 0f ? x1 : x0, ay5 = planes[21] >= 0f ? y1 : y0, az5 = planes[22] >= 0f ? z1 : z0;
        float n0x = planes[0], n0y = planes[1], n0z = planes[2], d0 = planes[3];
        float n1x = planes[4], n1y = planes[5], n1z = planes[6], d1 = planes[7];
        float n2x = planes[8], n2y = planes[9], n2z = planes[10], d2 = planes[11];
        float n3x = planes[12], n3y = planes[13], n3z = planes[14], d3 = planes[15];
        float n4x = planes[16], n4y = planes[17], n4z = planes[18], d4 = planes[19];
        float n5x = planes[20], n5y = planes[21], n5z = planes[22], d5 = planes[23];
        for (int start = 0; start < N; start += chunk) {
            int n = Math.min(chunk, N - start);
            for (int i = 0; i < n; i++) {
                int k = start + i;
                float m = d0 + n0x * ax0[k] + n0y * ay0[k] + n0z * az0[k];
                m = Math.min(m, d1 + n1x * ax1[k] + n1y * ay1[k] + n1z * az1[k]);
                m = Math.min(m, d2 + n2x * ax2[k] + n2y * ay2[k] + n2z * az2[k]);
                m = Math.min(m, d3 + n3x * ax3[k] + n3y * ay3[k] + n3z * az3[k]);
                m = Math.min(m, d4 + n4x * ax4[k] + n4y * ay4[k] + n4z * az4[k]);
                m = Math.min(m, d5 + n5x * ax5[k] + n5y * ay5[k] + n5z * az5[k]);
                s[i] = m;
            }
        }
        return s[0];
    }

    // ------------------------------------------------------------ pack variants

    @Benchmark
    public long packBranchy() {
        long acc = 0;
        for (int start = 0; start < N; start += 64) {
            int n = Math.min(64, N - start);
            long mask = 0L;
            for (int j = 0; j < n; j++) {
                mask |= (allSlack[start + j] >= 0f ? 1L : 0L) << j;
            }
            words[start >>> 6] = mask;
            acc += mask;
        }
        return acc;
    }

    @Benchmark
    public long packSignBit() {
        long acc = 0;
        for (int start = 0; start < N; start += 64) {
            int n = Math.min(64, N - start);
            long mask = 0L;
            for (int j = 0; j < n; j++) {
                // sign bit of the float: 1 for negative (and -0.0, which is a conservative reject)
                mask |= (long) ((Float.floatToRawIntBits(allSlack[start + j]) >>> 31) ^ 1) << j;
            }
            words[start >>> 6] = mask;
            acc += mask;
        }
        return acc;
    }
}
