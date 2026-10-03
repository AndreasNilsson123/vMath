package vmath.spatial;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Chooses a {@link FrustumKernel}. {@link #best()} returns the supported provider with the highest <em>positive</em> priority, falling
 * back to the scalar kernel (which counts as priority 0). The property {@code -Dvmath.kernel=<name>} forces a specific one ({@code scalar} always works).
 *
 * <p>The providers are looked up once, when the first selection is made, and the list is kept (they come from the class path or module path, which does not change
 * while the program runs); the property is read on every call. A provider that cannot be instantiated, whose {@code isSupported()} throws, or whose module is not
 * resolved is skipped, never fatal.
 *
 * <p><b>Thread safety.</b> Safe to call from any number of threads; every call returns a new kernel instance, and a kernel instance is for one thread.
 */
public final class FrustumKernels {

    private FrustumKernels() {
    }

    /** A new scalar kernel: portable, always available, auto-vectorizable by the JIT where it can. */
    public static FrustumKernel scalar() {
        return new FrustumCuller();
    }

    /**
     * A new kernel instance of the best available implementation. Every call returns a fresh instance, because
     * kernels hold per-thread scratch memory.
     */
    public static FrustumKernel best() {
        String forced = System.getProperty("vmath.kernel");
        FrustumKernelProvider chosen = null;
        for (FrustumKernelProvider p : Providers.LIST) {
            if (forced != null) {
                if (p.name().equals(forced)) {
                    chosen = p;
                    break;
                }
            } else if (p.priority() > 0 && (chosen == null || p.priority() > chosen.priority())) {
                chosen = p;
            }
        }
        if (chosen == null) {
            return scalar();
        }
        try {
            return chosen.create();
        } catch (RuntimeException | LinkageError e) {
            return scalar(); // a provider that cannot build its kernel must not take culling down
        }
    }

    /** Names of every kernel usable here, scalar first. */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        names.add("scalar");
        for (FrustumKernelProvider p : Providers.LIST) {
            names.add(p.name());
        }
        return names;
    }

    /** The supported providers, found once on first use (initialization-on-demand holder: thread-safe without locking). */
    private static final class Providers {
        static final List<FrustumKernelProvider> LIST = List.copyOf(load());

        private static List<FrustumKernelProvider> load() {
            List<FrustumKernelProvider> found = new ArrayList<>();
            var it = ServiceLoader.load(FrustumKernelProvider.class).iterator();
            for (int guard = 0; guard < 64; guard++) {
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    FrustumKernelProvider p = it.next();
                    if (p.isSupported()) {
                        found.add(p);
                    }
                } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                    continue; // for example a provider whose module needs jdk.incubator.vector when that is not resolved, or one that throws
                }
            }
            return found;
        }
    }
}
