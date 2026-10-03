package vmath.spatial;

/**
 * Service interface through which optional modules contribute a {@link FrustumKernel}. Register it with
 * {@code provides vmath.spatial.FrustumKernelProvider with ...} in {@code module-info.java}, and with a
 * {@code META-INF/services} entry for class-path use.
 */
public interface FrustumKernelProvider {

    /** Identifier of the kernel this provider creates; also what {@code -Dvmath.kernel=<name>} selects. */
    String name();

    /** Higher wins in {@link FrustumKernels#best()}. The scalar kernel counts as priority 0. */
    int priority();

    /** Whether the kernel can run here (for example, enough SIMD lanes). Must not throw. */
    boolean isSupported();

    /** Creates a fresh, single-threaded kernel instance. */
    FrustumKernel create();
}
