package vmath.spatial;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Chooses a {@link FrustumKernel}. {@link #best()} returns the supported provider with the highest priority, falling
 * back to the scalar kernel. The property {@code -Dvmath.kernel=<name>} forces a specific one ({@code scalar} always works).
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
        for (FrustumKernelProvider p : providers()) {
            if (forced != null) {
                if (p.name().equals(forced)) {
                    chosen = p;
                    break;
                }
            } else if (chosen == null || p.priority() > chosen.priority()) {
                chosen = p;
            }
        }
        return chosen != null ? chosen.create() : scalar();
    }

    /** Names of every kernel usable here, scalar first. */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        names.add("scalar");
        for (FrustumKernelProvider p : providers()) {
            names.add(p.name());
        }
        return names;
    }

    /** The supported providers on the module path or class path. Broken providers are skipped, never fatal. */
    private static List<FrustumKernelProvider> providers() {
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
            } catch (ServiceConfigurationError | LinkageError e) {
                // for example a provider whose module needs jdk.incubator.vector when that is not resolved
                continue;
            }
        }
        return found;
    }
}
