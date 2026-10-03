package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * Service interface through which an optional module contributes a {@link MatrixKernel}.
 *
 * <p>Register it with {@code provides vmath.bulk.MatrixKernelProvider with ...} in
 * {@code module-info.java}, and with a {@code META-INF/services} entry for class-path use.
 *
 * <p><b>Thread safety.</b> Not specified: the library does not define the threading behavior of
 * implementations of this interface; see the methods for what they promise.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * // registered in module-info.java:  provides vmath.bulk.MatrixKernelProvider with my.SimdProvider;
 * MatrixKernel kernel = MatrixKernels.best();
 * List<String> names = MatrixKernels.available();                           // the names of the kernels that can be used
 * }</pre>
 */
@Experimental("the SPI may change")
public interface MatrixKernelProvider {

    /**
     * Exposes the name by which the kernel of this provider is selected through the system
     * property.
     *
     * @return identifier of the kernel this provider creates; also what
     *     {@code -Dvmath.matrixKernel=<name>} selects
     */
    String name();

    /**
     * Exposes the rank of this provider, where the highest priority wins when the best kernel is
     * chosen automatically.
     *
     * <p>The scalar kernel counts as priority 0.
     *
     * @return higher wins in {@link MatrixKernels#best()}
     */
    int priority();

    /**
     * Returns whether the kernel can run here.
     *
     * <p>Must not throw.
     *
     * @return {@code true} if the kernel can run here
     */
    boolean isSupported();

    /**
     * Creates a kernel instance (they hold no state, so one may be shared).
     *
     * @return a new kernel, never {@code null}
     */
    MatrixKernel create();
}
