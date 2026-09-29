package vmath.anim;

import java.util.Arrays;

/**
 * The fixed part of a skeleton: how many joints there are, which joint is each one's parent, the bind pose, and the inverse bind
 * matrices that turn a mesh vertex from bind space into a joint's own space. Immutable once built.
 *
 * <p>Joints are stored parents first (a joint's parent has a smaller index), the same order invariant as {@link TransformHierarchy},
 * so a pose can be turned into world matrices in a single forward pass.
 *
 * <p>The bind pose is given as local transforms of 10 floats per joint: translation {@code x, y, z}, unit quaternion
 * {@code x, y, z, w}, scale {@code x, y, z} (the layout of {@link Pose#data()}). Quaternions are normalised on the way in.
 */
public final class Skeleton {

    private final int[] parent;
    private final float[] bind;
    private final float[] inverseBind;
    private final String[] names;

    /** A skeleton without joint names. */
    public Skeleton(int[] parents, float[] bindLocal) {
        this(parents, bindLocal, null);
    }

    /**
     * @param parents   parent joint of each joint, {@code -1} for a root; every parent index must be smaller than its child's
     * @param bindLocal 10 floats per joint (see the class comment)
     * @param names     optional joint names (one per joint), or {@code null}
     * @throws IllegalArgumentException for a bad parent order, wrong array lengths, non-finite values, or a joint whose bind
     *                                  transform cannot be inverted (a zero scale)
     */
    public Skeleton(int[] parents, float[] bindLocal, String[] names) {
        int n = parents.length;
        if (bindLocal.length != n * TransformMath.TRS) {
            throw new IllegalArgumentException("bindLocal needs " + n * TransformMath.TRS + " floats for " + n + " joints, has " + bindLocal.length);
        }
        if (names != null && names.length != n) {
            throw new IllegalArgumentException("names needs one entry per joint");
        }
        for (int j = 0; j < n; j++) {
            if (parents[j] < -1 || parents[j] >= j) {
                throw new IllegalArgumentException("joint " + j + " has parent " + parents[j] + ": parents must come before their children");
            }
        }
        this.parent = parents.clone();
        this.bind = bindLocal.clone();
        this.names = names == null ? null : names.clone();
        for (int j = 0; j < n; j++) {
            int o = j * TransformMath.TRS;
            for (int k = 0; k < TransformMath.TRS; k++) {
                if (!Float.isFinite(bind[o + k])) {
                    throw new IllegalArgumentException("joint " + j + " has a non-finite bind value");
                }
            }
            normalize(bind, o + 3);
        }
        float[] world = new float[n * 16];
        for (int j = 0; j < n; j++) {
            TransformMath.compose(world, j, parent[j], bind, j * TransformMath.TRS);
        }
        this.inverseBind = new float[n * 16];
        for (int j = 0; j < n; j++) {
            if (!TransformMath.invertAffine(world, j * 16, inverseBind, j * 16)) {
                throw new IllegalArgumentException("joint " + j + " has a singular bind transform (a zero scale?)");
            }
        }
    }

    static void normalize(float[] a, int o) {
        double len = Math.sqrt((double) a[o] * a[o] + (double) a[o + 1] * a[o + 1] + (double) a[o + 2] * a[o + 2] + (double) a[o + 3] * a[o + 3]);
        if (len > 1e-20) {
            a[o] = (float) (a[o] / len);
            a[o + 1] = (float) (a[o + 1] / len);
            a[o + 2] = (float) (a[o + 2] / len);
            a[o + 3] = (float) (a[o + 3] / len);
        } else {
            a[o] = 0f;
            a[o + 1] = 0f;
            a[o + 2] = 0f;
            a[o + 3] = 1f;
        }
    }

    public int jointCount() {
        return parent.length;
    }

    /** Parent of {@code joint}, or {@code -1} for a root. */
    public int parent(int joint) {
        return parent[joint];
    }

    /** The joint's name, or {@code null} if the skeleton has none. */
    public String name(int joint) {
        return names == null ? null : names[joint];
    }

    /** Index of the joint with this name, or {@code -1}. */
    public int indexOf(String name) {
        if (names != null) {
            for (int j = 0; j < names.length; j++) {
                if (names[j].equals(name)) {
                    return j;
                }
            }
        }
        return -1;
    }

    /** The bind pose as local transforms, 10 floats per joint. A copy. */
    public float[] bindLocal() {
        return bind.clone();
    }

    /** Inverse bind matrices, 16 floats per joint, column-major. A copy. */
    public float[] inverseBindMatrices() {
        return inverseBind.clone();
    }

    // package-private live views for the hot loops
    int[] parents() {
        return parent;
    }

    float[] bindArray() {
        return bind;
    }

    float[] inverseBindArray() {
        return inverseBind;
    }

    @Override
    public String toString() {
        return "Skeleton[" + parent.length + " joints, roots " + Arrays.stream(parent).filter(p -> p < 0).count() + "]";
    }
}
