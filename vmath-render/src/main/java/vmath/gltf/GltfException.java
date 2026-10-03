package vmath.gltf;

/**
 * Thrown for a glTF file that is malformed, inconsistent, truncated or uses something this loader
 * does not support.
 *
 * <p><b>Thread safety.</b> Adds no state to {@link RuntimeException}: it can be handed between
 * threads like any other exception.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * try {
 *     Gltf.parse(new byte[] {1, 2, 3}, uri -> new byte[0]);
 * } catch (GltfException e) {
 *     String why = e.getMessage();                                                       // what is wrong and where
 * }
 * }</pre>
 */
public final class GltfException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates a malformed or unsupported file; {@code message} says what and where.
     *
     * @param message the message; must not be {@code null}
     */
    public GltfException(String message) {
        super(message);
    }

    /**
     * Creates as {@link #GltfException(String)}, with the exception that caused it (for example an
     * {@link java.io.IOException} from the URI resolver).
     *
     * @param message the message; must not be {@code null}
     * @param cause the cause; must not be {@code null}
     */
    public GltfException(String message, Throwable cause) {
        super(message, cause);
    }
}
