package vmath.bulk;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import vmath.annotations.Experimental;

/**
 * Chooses a {@link MatrixKernel}.
 *
 * <p>{@link #best()} returns the supported provider with the highest <em>positive</em> priority,
 * falling back to the scalar kernel (which counts as priority 0); the property
 * {@code -Dvmath.matrixKernel=<name>} forces a specific one ({@code scalar} always works).
 * {@link Mat4fArray#multiply(Mat4fArray, Mat4fArray, Mat4fArray)} uses the choice made once at
 * class initialisation.
 *
 * <p>The providers are looked up once, at the first selection, and the list is kept; the property
 * is read on every call. A provider that cannot be instantiated, whose {@code isSupported()}
 * throws, or whose module is not resolved is skipped, never fatal.
 *
 * <p><b>Thread safety.</b> Safe to call from any number of threads; kernels are stateless, so one
 * instance may be shared.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MatrixKernel scalar = MatrixKernels.scalar();
 * MatrixKernel best = MatrixKernels.best();                                   // the supported provider with the highest priority, else scalar
 * String name = best.name();
 * }</pre>
 */
@Experimental("the SPI may change")
public final class MatrixKernels {

    private static final MatrixKernel SCALAR = ScalarMatrixKernel.INSTANCE;

    private MatrixKernels() {
    }

    /**
     * Provides the portable kernel, which works everywhere and is the reference for the others.
     *
     * @return the portable kernel, always available
     */
    public static MatrixKernel scalar() {
        return SCALAR;
    }

    /**
     * Selects the preferred kernel by priority, honouring an override through the system property.
     *
     * @return the best available kernel (see the class comment)
     */
    public static MatrixKernel best() {
        String forced = System.getProperty("vmath.matrixKernel");
        MatrixKernelProvider chosen = null;
        for (MatrixKernelProvider p : Providers.LIST) {
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
            return SCALAR;
        }
        try {
            return chosen.create();
        } catch (RuntimeException | LinkageError e) {
            return SCALAR; // a provider that cannot build its kernel must not take the batch kernels down
        }
    }

    /**
     * Lists the kernels that can run on this machine.
     *
     * @return names of every kernel usable here, scalar first
     */
    public static List<String> available() {
        List<String> names = new ArrayList<>();
        names.add("scalar");
        for (MatrixKernelProvider p : Providers.LIST) {
            names.add(p.name());
        }
        return names;
    }

    /**
     * The supported providers, found once on first use (initialization-on-demand holder:
     * thread-safe without locking).
     */
    private static final class Providers {
        static final List<MatrixKernelProvider> LIST = List.copyOf(load());

        private static List<MatrixKernelProvider> load() {
            List<MatrixKernelProvider> found = new ArrayList<>();
            var it = ServiceLoader.load(MatrixKernelProvider.class).iterator();
            for (int guard = 0; guard < 64; guard++) {
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    MatrixKernelProvider p = it.next();
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
