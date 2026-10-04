package vmath.spatial;

import java.util.List;
import vmath.bulk.KernelProvider;
import vmath.bulk.KernelSelector;

/**
 * Chooses a {@link FrustumKernel}.
 *
 * <p>{@link #best()} returns the supported provider with the highest <em>positive</em> priority,
 * falling back to the scalar kernel (which counts as priority 0). The property
 * {@code -Dvmath.frustumKernel=<name>} (or its older name {@code vmath.kernel}) forces a specific
 * one ({@code scalar} always works).
 *
 * <p>The providers are looked up once, when the first selection is made, and the list is kept (they
 * come from the class path or module path, which does not change while the program runs); the
 * property is read on every call. A provider that cannot be instantiated, whose
 * {@code isSupported()} throws, or whose module is not resolved is skipped, never fatal; that and
 * a forced name that no provider has are reported once through {@link System.Logger} (see
 * {@link KernelSelector}).
 *
 * <p><b>Thread safety.</b> Safe to call from any number of threads; every call returns a new kernel
 * instance, and a kernel instance is for one thread.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * FrustumKernel scalar = FrustumKernels.scalar();
 * FrustumKernel best = FrustumKernels.best();                              // SIMD when vmath-simd is present
 * List<String> available = FrustumKernels.available();
 * }</pre>
 */
public final class FrustumKernels {

    private static final KernelSelector<FrustumKernelProvider, FrustumKernel> SELECTOR = new KernelSelector<>(FrustumKernelProvider.class, p -> KernelProvider.of(p.name(), p.priority(), p::isSupported, p::create), FrustumCuller::new, "vmath.frustumKernel", "vmath.kernel");

    private FrustumKernels() {
    }

    /**
     * Creates the portable kernel, which works everywhere and relies on the JIT to vectorise where
     * it can.
     *
     * @return a new scalar kernel: portable, always available, auto-vectorizable by the JIT where
     *     it can
     */
    public static FrustumKernel scalar() {
        return new FrustumCuller();
    }

    /**
     * Creates the preferred kernel by priority, honouring an override through the system property.
     *
     * <p>Every call returns a fresh instance, because kernels hold per-thread scratch memory.
     *
     * @return a new kernel instance of the best available implementation
     */
    public static FrustumKernel best() {
        return SELECTOR.best();
    }

    /**
     * Lists the kernels that can run on this machine.
     *
     * @return names of every kernel usable here, scalar first
     */
    public static List<String> available() {
        return SELECTOR.available();
    }
}
