package vmath.spatial;

/**
 * Service interface through which optional modules contribute a {@link FrustumKernel}.
 *
 * <p>Register it with {@code provides vmath.spatial.FrustumKernelProvider with ...} in
 * {@code module-info.java}, and with a {@code META-INF/services} entry for class-path use.
 *
 * <p><b>Thread safety.</b> Not specified: the library does not define the threading behavior of
 * implementations of this interface; see the methods for what they promise.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * // registered in module-info.java:  provides vmath.spatial.FrustumKernelProvider with my.MyProvider;
 * FrustumKernel kernel = FrustumKernels.best();                            // the supported provider with the highest priority
 * List<String> names = FrustumKernels.available();
 * }</pre>
 */
public interface FrustumKernelProvider {

    /**
     * Exposes the name by which the kernel of this provider is selected through the system
     * property.
     *
     * @return identifier of the kernel this provider creates; also what
     *     {@code -Dvmath.frustumKernel=<name>} selects
     */
    String name();

    /**
     * Exposes the rank of this provider, where the highest priority wins when the best kernel is
     * chosen automatically.
     *
     * <p>The scalar kernel counts as priority 0.
     *
     * @return higher wins in {@link FrustumKernels#best()}
     */
    int priority();

    /**
     * Returns whether the kernel can run here (for example, enough SIMD lanes).
     *
     * <p>Must not throw.
     *
     * @return {@code true} if the kernel can run here (for example, enough SIMD lanes)
     */
    boolean isSupported();

    /**
     * Creates a fresh, single-threaded kernel instance.
     *
     * @return a new kernel, never {@code null}
     */
    FrustumKernel create();
}
