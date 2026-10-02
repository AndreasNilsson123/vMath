package vmath.gltf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Conversion of glTF animation samplers to the linear keys of an {@code AnimationClip}: STEP becomes a hold followed by a quick step, CUBICSPLINE is sampled at a fixed rate. */
final class ClipResampling {

    private ClipResampling() {
    }

    static float[][] stepToLinear(float[] times, float[] values, int comps) {
        int n = times.length;
        float[] t = new float[n * 2];
        float[] v = new float[n * 2 * comps];
        int k = 0;
        for (int i = 0; i < n; i++) {
            t[k] = times[i];
            System.arraycopy(values, i * comps, v, k * comps, comps);
            k++;
            if (i + 1 < n) {
                float hold = times[i + 1] - (times[i + 1] - times[i]) * 1e-3f;
                if (hold > times[i] && hold < times[i + 1]) {
                    t[k] = hold;
                    System.arraycopy(values, i * comps, v, k * comps, comps);
                    k++;
                }
            }
        }
        return new float[][] {Arrays.copyOf(t, k), Arrays.copyOf(v, k * comps)};
    }

    static float[][] resampleCubic(float[] times, float[] values, int comps, float rate) {
        int n = times.length;
        if (n == 1) {
            return new float[][] {times.clone(), Arrays.copyOfRange(values, comps, comps * 2)};
        }
        List<Float> outT = new ArrayList<>();
        List<float[]> outV = new ArrayList<>();
        float dt = 1f / rate;
        for (int k = 0; k + 1 < n; k++) {
            float t0 = times[k], t1 = times[k + 1], span = t1 - t0;
            int samples = Math.max(1, (int) Math.ceil(span / dt));
            for (int j = 0; j < samples; j++) {
                float t = t0 + span * j / samples;
                if (j > 0 && !(t > outT.get(outT.size() - 1))) {
                    continue;
                }
                float s = (t - t0) / span;
                float s2 = s * s, s3 = s2 * s;
                float h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
                float[] v = new float[comps];
                for (int c = 0; c < comps; c++) {
                    float p0 = values[(k * 3 + 1) * comps + c], m0 = values[(k * 3 + 2) * comps + c];
                    float p1 = values[((k + 1) * 3 + 1) * comps + c], m1 = values[((k + 1) * 3) * comps + c];
                    v[c] = h00 * p0 + h10 * span * m0 + h01 * p1 + h11 * span * m1;
                }
                outT.add(t);
                outV.add(v);
            }
        }
        outT.add(times[n - 1]);
        float[] last = new float[comps];
        System.arraycopy(values, ((n - 1) * 3 + 1) * comps, last, 0, comps);
        outV.add(last);
        float[] t = new float[outT.size()];
        float[] v = new float[outT.size() * comps];
        for (int i = 0; i < t.length; i++) {
            t[i] = outT.get(i);
            System.arraycopy(outV.get(i), 0, v, i * comps, comps);
        }
        return new float[][] {t, v};
    }
}
