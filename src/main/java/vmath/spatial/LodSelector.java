package vmath.spatial;

import java.util.Arrays;
import vmath.bulk.BoundsArray;
import vmath.bulk.VisibilitySet;

/**
 * Picks a level of detail per object from how large it appears on screen.
 *
 * <p><b>The metric.</b> The size of an object is the diameter, in pixels of screen height, of its bounding sphere:
 * {@code 2 * radius * pixelScale / distance} (see {@link CullContext#pixelScale()}), times an optional bias. It needs no
 * matrices, is conservative for box-shaped objects, and does not change when the camera turns.
 *
 * <p><b>Thresholds.</b> With {@code L} levels there are {@code L - 1} thresholds in strictly <em>descending</em> order.
 * An object at least {@code thresholds[0]} pixels big uses level 0 (full detail); below {@code thresholds[0]} but at least
 * {@code thresholds[1]} it uses level 1; and so on. Below {@code cullBelow} pixels it is not drawn at all.
 *
 * <p><b>Hysteresis.</b> Switching exactly at a threshold makes objects flicker between two levels when the camera hovers there.
 * With {@code hysteresis = h} an object keeps its previous level until its size is clearly past the threshold: it drops a level
 * only once below {@code threshold * (1 - h)} and climbs back only once above {@code threshold * (1 + h)}. The previous levels
 * live in a caller-owned {@code byte[]} that {@link #select} updates in place, so nothing is allocated.
 *
 * <p><b>Cross-fade.</b> The optional {@code fade} output tells a renderer how far the object has moved toward the next, lower
 * level: {@code 0} well inside the chosen level, rising to {@code 1} as the size falls from {@code threshold * (1 + fadeBand)}
 * to {@code threshold}. Draw the chosen level with weight {@code 1 - fade} and the next level with weight {@code fade}. The
 * blend is continuous across a threshold: just above it the object is fully the lower level already (fade 1), and just below
 * it that lower level is the chosen one (fade 0).
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class LodSelector {

    /** Value of a level array entry for "no history yet" (the first {@link #select} computes it without hysteresis). */
    public static final byte NO_LEVEL = -1;

    private final float[] thresholds;
    private final float cullBelow;
    private final float hysteresis;
    private final float fadeBand;

    /**
     * @param thresholds pixel sizes at which the level changes, strictly descending, at most 126 of them
     * @param cullBelow  objects smaller than this many pixels are culled ({@code 0} keeps everything)
     * @param hysteresis fraction of a threshold to overshoot before switching, {@code 0 <= h < 1}
     * @param fadeBand   fraction of a threshold over which the cross-fade ramps, {@code >= 0} ({@code 0} disables fading)
     */
    public LodSelector(float[] thresholds, float cullBelow, float hysteresis, float fadeBand) {
        if (thresholds.length > 126) {
            throw new IllegalArgumentException("at most 126 thresholds");
        }
        for (int i = 0; i < thresholds.length; i++) {
            if (!(thresholds[i] > 0f) || Float.isInfinite(thresholds[i])) {
                throw new IllegalArgumentException("thresholds must be positive and finite: " + Arrays.toString(thresholds));
            }
            if (i > 0 && !(thresholds[i] < thresholds[i - 1])) {
                throw new IllegalArgumentException("thresholds must be strictly descending: " + Arrays.toString(thresholds));
            }
        }
        if (!(cullBelow >= 0f) || Float.isInfinite(cullBelow)) {
            throw new IllegalArgumentException("cullBelow must be >= 0 and finite: " + cullBelow);
        }
        if (thresholds.length > 0 && cullBelow > thresholds[thresholds.length - 1]) {
            throw new IllegalArgumentException("cullBelow must not exceed the last threshold");
        }
        if (!(hysteresis >= 0f && hysteresis < 1f)) {
            throw new IllegalArgumentException("hysteresis must be in [0, 1): " + hysteresis);
        }
        if (!(fadeBand >= 0f) || Float.isInfinite(fadeBand)) {
            throw new IllegalArgumentException("fadeBand must be >= 0 and finite: " + fadeBand);
        }
        this.thresholds = thresholds.clone();
        this.cullBelow = cullBelow;
        this.hysteresis = hysteresis;
        this.fadeBand = fadeBand;
    }

    /** Levels with the given thresholds, no culling, 10% hysteresis and 20% cross-fade. */
    public static LodSelector of(float... thresholds) {
        return new LodSelector(thresholds, 0f, 0.1f, 0.2f);
    }

    /** Number of levels (one more than the number of thresholds). */
    public int levels() {
        return thresholds.length + 1;
    }

    /** The level for a size, with no hysteresis and no history. NaN sizes get level 0 (full detail). */
    public int levelFor(float sizePixels) {
        int level = 0;
        while (level < thresholds.length && sizePixels < thresholds[level]) {
            level++;
        }
        return level;
    }

    /** Cross-fade factor for {@code sizePixels} at {@code level}: 0 inside the level up to 1 at its lower threshold. */
    public float fadeFor(float sizePixels, int level) {
        if (level >= thresholds.length || fadeBand <= 0f) {
            return 0f;
        }
        float t = thresholds[level];
        float f = (t * (1f + fadeBand) - sizePixels) / (t * fadeBand);
        return f != f ? 0f : Math.max(0f, Math.min(1f, f));
    }

    /**
     * Picks a level for every visible object. Objects smaller than {@code cullBelow} have their bit cleared in
     * {@code visible}; all others get their level written to {@code levels[i]} (and the cross-fade to {@code fade[i]} when
     * {@code fade} is not null). Entries of objects that are not visible are left alone.
     *
     * @param levels previous levels in, new levels out; initialise with {@link #NO_LEVEL} (for example
     *               {@code Arrays.fill(levels, LodSelector.NO_LEVEL)}); must hold {@code bounds.size()} entries
     * @param bias   multiplies every size: values above 1 keep more detail, below 1 switch earlier
     */
    public void select(CullContext ctx, BoundsArray bounds, VisibilitySet visible, byte[] levels, float[] fade, float bias) {
        int n = bounds.size();
        if (levels.length < n || (fade != null && fade.length < n)) {
            throw new IllegalArgumentException("levels and fade need " + n + " entries");
        }
        float scale = ctx.pixelScale() * bias;
        float cx = ctx.camera().x(), cy = ctx.camera().y(), cz = ctx.camera().z();
        float[] x0 = bounds.minXs(), y0 = bounds.minYs(), z0 = bounds.minZs();
        float[] x1 = bounds.maxXs(), y1 = bounds.maxYs(), z1 = bounds.maxZs();
        long[] words = visible.words();
        int last = thresholds.length;
        for (int i = visible.nextSetBit(0); i >= 0 && i < n; i = visible.nextSetBit(i + 1)) {
            float size;
            if (scale <= 0f) {
                size = Float.POSITIVE_INFINITY; // size-based selection is off: everything at full detail
            } else {
                float hx = (x1[i] - x0[i]) * 0.5f, hy = (y1[i] - y0[i]) * 0.5f, hz = (z1[i] - z0[i]) * 0.5f;
                float dx = (x0[i] + x1[i]) * 0.5f - cx, dy = (y0[i] + y1[i]) * 0.5f - cy, dz = (z0[i] + z1[i]) * 0.5f - cz;
                float radius = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                size = dist > 0f ? 2f * radius * scale / dist : Float.POSITIVE_INFINITY;
            }
            if (size < cullBelow) {
                words[i >>> 6] &= ~(1L << i);
                continue;
            }
            int level = levels[i];
            if (level < 0 || level > last || size != size) {
                level = levelFor(size);
            } else {
                // stay put until the size is clearly past a threshold
                while (level < last && size < thresholds[level] * (1f - hysteresis)) {
                    level++;
                }
                while (level > 0 && size >= thresholds[level - 1] * (1f + hysteresis)) {
                    level--;
                }
            }
            levels[i] = (byte) level;
            if (fade != null) {
                fade[i] = fadeFor(size, level);
            }
        }
    }

    /** {@link #select(CullContext, BoundsArray, VisibilitySet, byte[], float[], float)} with bias 1. */
    public void select(CullContext ctx, BoundsArray bounds, VisibilitySet visible, byte[] levels, float[] fade) {
        select(ctx, bounds, visible, levels, fade, 1f);
    }
}
