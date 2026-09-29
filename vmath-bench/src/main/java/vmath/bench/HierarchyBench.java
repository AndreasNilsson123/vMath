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
import vmath.anim.TransformHierarchy;

/**
 * The scene-transform hierarchy at scale: a tree of {@code count} nodes where each node hangs under one of the previous 100 (so a
 * few dozen levels deep). Run with {@code -prof gc}: all of it should report ~0 B/op.
 *
 * <ul>
 *   <li>{@code updateAllDirty}: every local transform changed, so every world matrix is recomputed</li>
 *   <li>{@code updateOnePercentRandom}: 1% of the nodes changed, picked at random; in this tree most later nodes descend from an earlier one, so this dirties most of the tree (the setup prints how many nodes are recomputed)</li>
 *   <li>{@code updateTailOnePercent}: 1% of the nodes changed, all among the last 1% of the arrays (leaf-like nodes with few descendants): the scan starts late and little is recomputed</li>
 *   <li>{@code updateNothingDirty}: no change at all, the floor of the per-frame cost</li>
 * </ul>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HierarchyBench {

    @Param({"100000"})
    public int count;

    private TransformHierarchy hierarchy;
    private int[] picks;
    private int[] tailPicks;
    private SplittableRandom rnd;
    private float phase;

    @Setup
    public void setup() {
        rnd = new SplittableRandom(9);
        hierarchy = new TransformHierarchy(count);
        for (int i = 0; i < count; i++) {
            int parent = i == 0 ? -1 : Math.max(0, i - 1 - rnd.nextInt(100));
            int n = hierarchy.add(parent);
            hierarchy.setLocal(n, (float) rnd.nextDouble(), (float) rnd.nextDouble(), (float) rnd.nextDouble(), 0f, 0.1f, 0f, 1f, 1f, 1f, 1f);
        }
        hierarchy.update();
        picks = new int[Math.max(1, count / 100)];
        for (int i = 0; i < picks.length; i++) {
            picks[i] = rnd.nextInt(count);
        }
        tailPicks = new int[Math.max(1, count / 100)];
        for (int i = 0; i < tailPicks.length; i++) {
            tailPicks[i] = count - 1 - rnd.nextInt(tailPicks.length);
        }
        int random = updateOnePercentRandom();
        int tail = updateTailOnePercent();
        System.out.println("# " + count + " nodes: a random 1% edit recomputes " + random + " nodes, a tail 1% edit recomputes " + tail);
    }

    @Benchmark
    public int updateAllDirty() {
        phase += 0.01f;
        for (int i = 0; i < count; i++) {
            hierarchy.setTranslation(i, phase, 0f, 1f);
        }
        return hierarchy.update();
    }

    @Benchmark
    public int updateOnePercentRandom() {
        phase += 0.01f;
        for (int p : picks) {
            hierarchy.setTranslation(p, phase, 0f, 1f);
        }
        return hierarchy.update();
    }

    @Benchmark
    public int updateTailOnePercent() {
        phase += 0.01f;
        for (int p : tailPicks) {
            hierarchy.setTranslation(p, phase, 0f, 1f);
        }
        return hierarchy.update();
    }

    @Benchmark
    public int updateNothingDirty() {
        return hierarchy.update();
    }
}
