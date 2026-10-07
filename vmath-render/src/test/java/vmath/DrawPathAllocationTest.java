package vmath;

import static vmath.Alloc.assertNoAllocation;

import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.bulk.VisibilitySet;
import vmath.gl.DrawCommandBuffer;
import vmath.gl.DrawList;
import vmath.gl.DrawSubmission;
import vmath.gl.GraphicsCapabilities;
import vmath.gpucull.CullBackend;
import vmath.gpucull.SurvivorBatcher;
import vmath.lines.LineClipper;
import vmath.lines.LineCulling;
import vmath.lines.LineRenderPlan;
import vmath.lines.LineSet;
import vmath.lines.LineSimplifier;
import vmath.lines.LineStrategy;
import vmath.lines.LineStyle;
import vmath.lines.TrailBuffer;
import vmath.geo.DepthRange;

/**
 * The allocation contract of the capability-driven draw path: choosing a strategy, filling and
 * encoding a {@link DrawList}, and grouping the survivors allocate nothing in steady state.
 */
class DrawPathAllocationTest {

    private static final int WARM = 20_000, CALLS = 20_000;

    @Test
    void choosingAStrategyAllocatesNothing() {
        GraphicsCapabilities gl33 = GraphicsCapabilities.baseline(), gl46 = GraphicsCapabilities.openGl(4, 6, List.of());
        DrawList instanced = new DrawList(DrawList.Kind.ELEMENTS, 4);
        instanced.addElements(36, 0, 0, 10, 3, 0);
        int[] sink = new int[1];
        assertNoAllocation("DrawSubmission.choose and CullBackend.choose", WARM, CALLS, () -> {
            sink[0] += DrawSubmission.choose(gl33, instanced).ordinal() + DrawSubmission.choose(gl46, instanced).ordinal();
            sink[0] += DrawSubmission.choose(gl33, false, false).ordinal() + CullBackend.choose(gl46).ordinal() + CullBackend.choose(gl33).ordinal();
        });
    }

    @Test
    void fillingAndEncodingADrawListAllocatesNothing() {
        DrawList list = new DrawList(DrawList.Kind.ELEMENTS, 512);
        MemorySegment seg = MemorySegment.ofArray(new byte[512 * 20]);
        DrawCommandBuffer commands = new DrawCommandBuffer(seg, DrawCommandBuffer.Kind.ELEMENTS, false);
        int[] counts = new int[512], firsts = new int[512], bases = new int[512];
        long[] offsets = new long[512];
        int[] sink = new int[1];
        DrawList.Visitor visitor = (count, first, baseVertex, instanceCount, baseInstance, user) -> sink[0] += count; // made once: a lambda made in the loop would be the allocation
        assertNoAllocation("DrawList fill, writeIndirect, copy arrays, forEach", WARM / 10, CALLS / 10, () -> {
            list.clear();
            for (int i = 0; i < 512; i++) {
                list.addElements(36, i * 36, i * 8, 1, 0, i);
            }
            commands.clear();
            sink[0] += list.writeIndirect(commands);
            sink[0] += list.copyCounts(counts) + list.copyFirsts(firsts) + list.copyBaseVertices(bases) + list.copyIndexOffsets(offsets, 4);
            list.forEach(visitor);
        });
    }

    @Test
    void groupingTheSurvivorsAllocatesNothing() {
        int n = 20_000, draws = 32;
        int[] drawOf = new int[n];
        VisibilitySet visible = new VisibilitySet(n);
        for (int i = 0; i < n; i++) {
            drawOf[i] = i % draws;
            if (i % 3 != 0) {
                visible.set(i);
            }
        }
        DrawList list = new DrawList(DrawList.Kind.ELEMENTS, draws);
        for (int d = 0; d < draws; d++) {
            list.addElements(36, 0, 0, 1, 0, d);
        }
        SurvivorBatcher batcher = new SurvivorBatcher();
        int[] sink = new int[1];
        assertNoAllocation("SurvivorBatcher.batch", 2_000, 2_000, () -> sink[0] += batcher.batch(visible, n, drawOf, list, null));
    }
    @Test
    void editingAndUpdatingALineSetAllocatesNothing() {
        for (LineStrategy strategy : new LineStrategy[] {LineStrategy.INDIRECT_DRAW_ID, LineStrategy.EXPANDED_MULTIDRAW, LineStrategy.HAIRLINE}) {
            LineRenderPlan plan = LineRenderPlan.force(strategy, GraphicsCapabilities.openGl(4, 6, List.of()));
            LineSet set = new LineSet(plan, 20_000);
            LineStyle[] styles = {LineStyle.pixels(2f), LineStyle.pixels(4f).withColor(0xFF0000FF), LineStyle.pixels(3f).withLayer(1)};
            long[] handles = new long[300];
            double[] xyz = new double[3 * 8];
            for (int i = 0; i < handles.length; i++) {
                for (int k = 0; k < 8; k++) {
                    xyz[3 * k] = i + k * 3;
                    xyz[3 * k + 1] = (i * 7 + k * 5) % 50;
                }
                handles[i] = set.add(xyz, 0, 8, i % 5 == 0, styles[i % 3]);
            }
            MemorySegment data = MemorySegment.ofArray(new byte[(int) set.dataBytes()]), styleBuffer = MemorySegment.ofArray(new byte[(int) set.styleBytes() + 4096]);
            DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 512);
            set.update(data, styleBuffer, draws);
            int[] counter = new int[1];
            assertNoAllocation("LineSet.set and update (" + strategy + ")", WARM, CALLS, () -> {
                int i = counter[0]++ % handles.length;
                for (int k = 0; k < 8; k++) {
                    xyz[3 * k] = counter[0] % 90 + k * 3;
                    xyz[3 * k + 1] = (i * 7 + k * 5) % 50;
                }
                set.set(handles[i], xyz, 0, 8, i % 5 == 0);
                counter[0] += set.update(data, styleBuffer, draws) + set.dirtyRangeCount();
            });
        }
    }

    @Test
    void cullingALineSetAndSimplifyingAndClippingSegmentsAllocateNothing() {
        LineRenderPlan plan = LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.openGl(4, 6, List.of()));
        LineSet set = new LineSet(plan, 10_000);
        double[] xyz = new double[3 * 4];
        for (int i = 0; i < 200; i++) {
            for (int k = 0; k < 4; k++) {
                xyz[3 * k] = i * 2 + k;
                xyz[3 * k + 1] = (i * 13 + k * 7) % 100;
            }
            set.add(xyz, 0, 4, false, LineStyle.pixels(2f));
        }
        LineCulling culling = new LineCulling(vmath.spatial.FrustumKernels.scalar());
        VisibilitySet visible = new VisibilitySet(256);
        float[] vp = {0.01f, 0, 0, 0, 0, 0.02f, 0, 0, 0, 0, 1f, 0, -1f, -1f, 0f, 1f};
        int[] sink = new int[1];
        // the frustum of a call is a few small objects that the JIT can remove or not: the arrays and the set allocate nothing, which fillBounds shows
        vmath.bulk.BoundsArray bounds = new vmath.bulk.BoundsArray(256);
        assertNoAllocation("LineSet.fillBounds", 3_000, 6_000, () -> sink[0] += set.fillBounds(bounds, 1f));
        assertNoAllocation("LineSimplifier.select and copySelected", 3_000, 6_000, () -> {
            sink[0] += LineSimplifier.select(IMPORTANCE, 64, 1.5, INDICES) + LineSimplifier.copySelected(POINTS, 0, 64, IMPORTANCE, 1.5, OUT, 0);
        });
        double[] seg = {-50, 10, 0, 150, 90, 7};
        double[] out = new double[8];
        double[] planes = new double[24];
        LineClipper.frustumPlanes(new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, -1.02, -1, 0, 0, -0.2, 0}, DepthRange.NEGATIVE_ONE_TO_ONE, planes);
        assertNoAllocation("LineClipper segment clipping", 3_000, 6_000, () -> {
            sink[0] += LineClipper.clipRectangle(seg, 0, 0, 0, 100, 100, out, 0) ? 1 : 0;
            sink[0] += LineClipper.clipPlanes(seg, 0, planes, 6, out, 0) ? 1 : 0;
        });
        culling.cull(set, vp, DepthRange.NEGATIVE_ONE_TO_ONE, 1f, visible);
        sink[0] += visible.count();
    }

    private static final double[] POINTS = new double[3 * 64];
    private static final double[] IMPORTANCE = new double[64];
    private static final int[] INDICES = new int[64];
    private static final double[] OUT = new double[3 * 64];

    static {
        for (int i = 0; i < 64; i++) {
            POINTS[3 * i] = i;
            POINTS[3 * i + 1] = (i * 37) % 11;
        }
        LineSimplifier.douglasPeuckerImportance(POINTS, 0, 64, IMPORTANCE);
    }

    @Test
    void aTrailAllocatesNothingWhileItMoves() {
        LineSet set = new LineSet(LineRenderPlan.force(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.openGl(4, 6, List.of())), 20_000);
        TrailBuffer trails = new TrailBuffer(10, 64, 4);
        LineStyle style = LineStyle.pixels(2f).withColor(0x00FF00FF);
        int[] tracks = new int[10];
        for (int t = 0; t < tracks.length; t++) {
            tracks[t] = trails.addTrack();
        }
        MemorySegment data = MemorySegment.ofArray(new byte[(int) set.dataBytes()]), styleBuffer = MemorySegment.ofArray(new byte[4096]);
        DrawList draws = new DrawList(DrawList.Kind.ARRAYS, 256);
        double[] clock = new double[1];
        Runnable frame = () -> {
            clock[0] += 0.1;
            for (int t = 0; t < tracks.length; t++) {
                trails.push(tracks[t], clock[0] * 3 + t, Math.sin(clock[0] + t) * 20, 0, clock[0]);
                trails.updateLines(set, tracks[t], clock[0], 4.0, style);
            }
            set.update(data, styleBuffer, draws);
        };
        for (int i = 0; i < 400; i++) {
            frame.run();   // fill the rings and let the pieces reach their sizes
        }
        assertNoAllocation("TrailBuffer.push, updateLines and LineSet.update", 3_000, 6_000, frame);
    }
}
