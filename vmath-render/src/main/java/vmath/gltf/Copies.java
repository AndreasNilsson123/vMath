package vmath.gltf;

/**
 * Null-safe array copies for the records of {@link Gltf}.
 *
 * <p>Internal: the records keep copies of the arrays they are given and hand out copies, so that
 * a record, like the values it describes, cannot be changed after it was made.
 *
 * <p><b>Thread safety.</b> Stateless: safe to call from any number of threads.
 */
final class Copies {

    private Copies() {
    }

    /**
     * Copies an array.
     *
     * @param a the array, or {@code null}
     * @return a copy of {@code a}, or {@code null} if {@code a} is {@code null}
     */
    static float[] of(float[] a) {
        return a == null ? null : a.clone();
    }

    /**
     * Copies an array.
     *
     * @param a the array, or {@code null}
     * @return a copy of {@code a}, or {@code null} if {@code a} is {@code null}
     */
    static int[] of(int[] a) {
        return a == null ? null : a.clone();
    }
}
