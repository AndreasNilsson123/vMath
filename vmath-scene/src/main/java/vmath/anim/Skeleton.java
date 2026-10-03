package vmath.anim;

import java.util.Arrays;

/**
 * The fixed part of a skeleton: how many joints there are, which joint is each one's parent, the
 * bind pose, and the inverse bind matrices that turn a mesh vertex from bind space into a joint's
 * own space.
 *
 * <p>Immutable once built.
 *
 * <p>Joints are stored parents first (a joint's parent has a smaller index), the same order
 * invariant as {@link TransformHierarchy}, so a pose can be turned into world matrices in a single
 * forward pass.
 *
 * <p>The bind pose is given as local transforms of 10 floats per joint: translation
 * {@code x, y, z}, unit quaternion {@code x, y, z, w}, scale {@code x, y, z} (the layout of
 * {@link Pose#data()}). Quaternions are normalised on the way in.
 *
 * <p><b>Thread safety.</b> Immutable after construction, so it can be shared between threads
 * freely. The arrays it hands out are its own storage: do not modify them.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * int[] parents = {-1, 0, 1};                                            // a chain of three joints, parents first
 * float[] bindLocal = {                                                   // translation xyz, rotation xyzw, scale xyz per joint
 *     0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f,
 *     0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f,
 *     0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f};
 * Skeleton skeleton = new Skeleton(parents, bindLocal, new String[] {"hips", "spine", "head"});
 * int head = skeleton.indexOf("head");                                    // 2
 * int parent = skeleton.parent(head);                                     // 1
 * }</pre>
 */
public final class Skeleton {

    private final int[] parent;
    private final float[] bind;
    private final float[] inverseBind;
    private final String[] names;

    /**
     * Creates a skeleton without joint names.
     *
     * @param parents the parents
     * @param bindLocal the bind local
     */
    public Skeleton(int[] parents, float[] bindLocal) {
        this(parents, bindLocal, null);
    }

    /**
     * Creates a skeleton from the parent indices, the local bind pose and the joint names.
     *
     * @param parents   parent joint of each joint, {@code -1} for a root; every parent index must be smaller than its child's
     *
     * @param bindLocal 10 floats per joint (see the class comment)
     * @param names     optional joint names (one per joint), or {@code null}
     * @throws IllegalArgumentException for a bad parent order, wrong array lengths, non-finite
     *     values, or a joint whose bind transform cannot be inverted (a zero scale)
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

    /**
     * Counts the joints of the skeleton.
     *
     * @return the number of joints
     */
    public int jointCount() {
        return parent.length;
    }

    /**
     * Looks up the parent of a joint; parents precede their children in the joint order.
     *
     * @param joint the joint index
     * @return parent of {@code joint}, or {@code -1} for a root
     */
    public int parent(int joint) {
        return parent[joint];
    }

    /**
     * Looks up the name of a joint, which may be absent.
     *
     * @param joint the joint index
     * @return the joint's name, or {@code null} if the skeleton has none
     */
    public String name(int joint) {
        return names == null ? null : names[joint];
    }

    /**
     * Searches the joints by name with a linear scan, so it belongs in setup code and not in
     * per-frame code.
     *
     * @param name the name; must not be {@code null}
     * @return index of the joint with this name, or {@code -1}
     */
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

    /**
     * Exposes the bind pose as local transforms in the layout shared with {@link Pose}.
     *
     * <p>A copy.
     *
     * @return the bind pose as local transforms, 10 floats per joint
     */
    public float[] bindLocal() {
        return bind.clone();
    }

    /**
     * Exposes the inverse bind matrices, which map from model space into the space of each joint's
     * bind pose, in column-major order ready for upload.
     *
     * <p>A copy.
     *
     * @return inverse bind matrices, 16 floats per joint, column-major
     */
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
