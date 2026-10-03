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
import vmath.anim.AnimationClip;
import vmath.anim.ClipSampler;
import vmath.anim.Pose;
import vmath.anim.Skeleton;
import vmath.anim.Skinning;
import vmath.bulk.Mat4fArray;

/**
 * The per-character cost of skeletal animation for a skeleton of {@code joints} joints and a clip with translation, rotation and
 * scale tracks of 30 keys on every joint. Run with {@code -prof gc}: everything should report ~0 B/op.
 *
 * <ul>
 *   <li>{@code sample}: clip to pose, playing forward (the cursors make each track lookup cheap)</li>
 *   <li>{@code blend}: two poses into one</li>
 *   <li>{@code jointMatrices}: pose to skinning matrices (world matrices and the inverse bind multiply)</li>
 *   <li>{@code fullCharacter}: sample, blend with a second sampled pose, and build the joint matrices: one animated character per frame</li>
 *   <li>{@code skinPositions}: the CPU reference skinning of 10 000 vertices, for scale</li>
 *   <li>{@code jointDualQuaternions} and {@code skinPositionsDualQuat}: the same with dual quaternion blending</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AnimationBench {

    @Param({"64", "128"})
    public int joints;

    private Skeleton skeleton;
    private ClipSampler samplerA;
    private ClipSampler samplerB;
    private Pose a;
    private Pose b;
    private Pose out;
    private Mat4fArray matrices;
    private float[] scratch;
    private float time;

    private static final int VERTICES = 10_000;
    private float[] positions;
    private float[] skinned;
    private int[] jointIndices;
    private float[] weights;
    private float[] dualQuaternions;

    @Setup
    public void setup() {
        SplittableRandom r = new SplittableRandom(4);
        int[] parents = new int[joints];
        float[] bind = new float[joints * 10];
        for (int j = 0; j < joints; j++) {
            parents[j] = j == 0 ? -1 : Math.max(0, j - 1 - r.nextInt(3));
            bind[j * 10 + 1] = 0.3f;
            bind[j * 10 + 6] = 1f;
            bind[j * 10 + 7] = bind[j * 10 + 8] = bind[j * 10 + 9] = 1f;
        }
        skeleton = new Skeleton(parents, bind);
        samplerA = new ClipSampler(makeClip(r));
        samplerB = new ClipSampler(makeClip(r));
        a = new Pose(skeleton);
        b = new Pose(skeleton);
        out = new Pose(skeleton);
        matrices = new Mat4fArray(joints);
        scratch = new float[joints * 16];
        positions = new float[VERTICES * 3];
        skinned = new float[VERTICES * 3];
        jointIndices = new int[VERTICES * 4];
        weights = new float[VERTICES * 4];
        for (int v = 0; v < VERTICES; v++) {
            for (int k = 0; k < 3; k++) {
                positions[v * 3 + k] = (float) r.nextDouble();
            }
            for (int k = 0; k < 4; k++) {
                jointIndices[v * 4 + k] = r.nextInt(joints);
                weights[v * 4 + k] = 0.25f;
            }
        }
        samplerA.sample(0.5f, true, a);
        Skinning.jointMatrices(skeleton, a, scratch, matrices);
        dualQuaternions = new float[joints * 8];
        Skinning.jointDualQuaternions(matrices.data(), joints, dualQuaternions);
    }

    private AnimationClip makeClip(SplittableRandom r) {
        AnimationClip.Builder builder = AnimationClip.builder(joints);
        int keys = 30;
        float[] times = new float[keys];
        for (int k = 0; k < keys; k++) {
            times[k] = k / 29f * 2f;
        }
        for (int j = 0; j < joints; j++) {
            float[] t = new float[keys * 3], q = new float[keys * 4], s = new float[keys * 3];
            for (int k = 0; k < keys; k++) {
                for (int m = 0; m < 3; m++) {
                    t[k * 3 + m] = (float) r.nextDouble();
                    s[k * 3 + m] = 1f;
                }
                q[k * 4] = (float) (r.nextDouble() - 0.5) * 0.4f;
                q[k * 4 + 1] = (float) (r.nextDouble() - 0.5) * 0.4f;
                q[k * 4 + 2] = (float) (r.nextDouble() - 0.5) * 0.4f;
                q[k * 4 + 3] = 1f;
            }
            builder.translation(j, times, t).rotation(j, times, q).scale(j, times, s);
        }
        return builder.build();
    }

    private float nextTime() {
        time += 1f / 60f;
        return time;
    }

    @Benchmark
    public void sample() {
        samplerA.sample(nextTime(), true, a);
    }

    @Benchmark
    public void blend() {
        Pose.lerp(a, b, 0.4f, out);
    }

    @Benchmark
    public int jointMatrices() {
        Skinning.jointMatrices(skeleton, a, scratch, matrices);
        return matrices.size();
    }

    @Benchmark
    public int fullCharacter() {
        float t = nextTime();
        samplerA.sample(t, true, a);
        samplerB.sample(t, true, b);
        Pose.lerp(a, b, 0.5f, out);
        Skinning.jointMatrices(skeleton, out, scratch, matrices);
        return matrices.size();
    }

    @Benchmark
    public float skinPositions() {
        Skinning.skinPositions(matrices.data(), positions, jointIndices, weights, VERTICES, skinned);
        return skinned[0];
    }

    @Benchmark
    public float jointDualQuaternions() {
        Skinning.jointDualQuaternions(matrices.data(), joints, dualQuaternions);
        return dualQuaternions[0];
    }

    @Benchmark
    public float skinPositionsDualQuat() {
        Skinning.skinPositionsDualQuat(dualQuaternions, positions, jointIndices, weights, VERTICES, skinned);
        return skinned[0];
    }
}
