package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * Service interface through which an optional module contributes a {@link MatrixKernel}. Register it with
 * {@code provides vmath.bulk.MatrixKernelProvider with ...} in {@code module-info.java}, and with a {@code META-INF/services} entry for class-path use.
 */
@Experimental("the SPI may change")
public interface MatrixKernelProvider {

    /** Identifier of the kernel this provider creates; also what {@code -Dvmath.matrixKernel=<name>} selects. */
    String name();

    /** Higher wins in {@link MatrixKernels#best()}. The scalar kernel counts as priority 0. */
    int priority();

    /** Whether the kernel can run here. Must not throw. */
    boolean isSupported();

    /** Creates a kernel instance (they hold no state, so one may be shared). */
    MatrixKernel create();
}
