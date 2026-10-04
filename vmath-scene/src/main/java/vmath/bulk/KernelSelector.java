package vmath.bulk;

import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import vmath.annotations.Experimental;

/**
 * Chooses the kernel of one kind, from the providers that optional modules register.
 *
 * <p>{@link #best()} returns the kernel of the supported provider with the highest
 * <em>positive</em> priority, falling back to the scalar kernel (priority 0). A system property
 * forces a provider by name instead; {@code scalar} always works. The providers are looked up
 * once, when the selector is created, and the list is kept; the properties are read on every
 * call.
 *
 * <p>Nothing here is fatal: a provider that cannot be instantiated, whose {@code isSupported()}
 * throws, whose module is not resolved or whose kernel cannot be built is skipped, and the scalar
 * kernel is used. Each of those, and a forced name that no usable provider has, is reported once
 * through {@link System.Logger} (logger name {@code vmath.kernel}, level
 * {@link System.Logger.Level#WARNING}), so that a program that silently runs the slow path can be
 * found.
 *
 * <p><b>Thread safety.</b> Safe to call from any number of threads; the provider list is
 * immutable after construction. A kernel that {@link #best()} returns is as thread-safe as its
 * provider makes it.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * KernelSelector<MatrixKernelProvider, MatrixKernel> selector = new KernelSelector<>(
 *         MatrixKernelProvider.class,
 *         p -> KernelProvider.of(p.name(), p.priority(), p::isSupported, p::create),
 *         MatrixKernels::scalar, "vmath.matrixKernel");
 * MatrixKernel kernel = selector.best();
 * List<String> names = selector.available();                      // "scalar" first
 * }</pre>
 *
 * @param <P> the provider type, the service interface that the providers implement
 * @param <K> the kernel type
 */
@Experimental("the SPI may change")
public final class KernelSelector<P, K> {

    /**
     * The name that always selects the scalar kernel.
     */
    public static final String SCALAR = "scalar";

    private static final Logger LOG = System.getLogger("vmath.kernel");
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** The most services one lookup reads: a guard against a misbehaving iterator. */
    private static final int MAX_PROVIDERS = 64;

    private final Supplier<? extends K> scalar;
    private final String[] properties;
    private final List<KernelProvider<K>> providers;

    /**
     * Creates a selector and looks up the providers of {@code service}.
     *
     * @param service the provider interface to look up with {@link ServiceLoader}; must not be
     *     {@code null}
     * @param adapter shows a provider as a {@link KernelProvider}, for example with
     *     {@link KernelProvider#of}; must not be {@code null}
     * @param scalar creates or returns the scalar kernel; must not be {@code null}
     * @param properties the names of the system properties that force a provider, the first one
     *     that is set wins; must not be {@code null} or empty
     * @throws IllegalArgumentException if {@code properties} is empty
     */
    public KernelSelector(Class<P> service, Function<? super P, ? extends KernelProvider<K>> adapter, Supplier<? extends K> scalar, String... properties) {
        if (properties.length == 0) {
            throw new IllegalArgumentException("at least one property name is needed");
        }
        this.scalar = scalar;
        this.properties = properties.clone();
        this.providers = List.copyOf(load(service, adapter));
    }

    private static <P, K> List<KernelProvider<K>> load(Class<P> service, Function<? super P, ? extends KernelProvider<K>> adapter) {
        List<KernelProvider<K>> found = new ArrayList<>();
        var it = ServiceLoader.load(service).iterator();
        for (int guard = 0; guard < MAX_PROVIDERS; guard++) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                KernelProvider<K> p = adapter.apply(it.next());
                if (p.isSupported()) {
                    found.add(p);
                }
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                // for example a provider whose module needs jdk.incubator.vector when that is not resolved, or one that throws
                report(service.getSimpleName() + " skipped: " + e);
            }
        }
        return found;
    }

    private static void report(String message) {
        if (REPORTED.add(message)) {
            LOG.log(Logger.Level.WARNING, "{0}", message);
        }
    }

    private String forcedName() {
        for (String property : properties) {
            String value = System.getProperty(property);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Selects the preferred kernel by priority, honouring the override through the system
     * properties.
     *
     * @return the kernel of the chosen provider, or the scalar kernel if there is none or its
     *     provider cannot build it
     */
    public K best() {
        String forced = forcedName();
        KernelProvider<K> chosen = null;
        for (KernelProvider<K> p : providers) {
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
            if (forced != null && !SCALAR.equals(forced)) {
                report("the kernel '" + forced + "' (" + properties[0] + ") is not available, the scalar kernel is used; available: " + available());
            }
            return scalar.get();
        }
        try {
            return chosen.create();
        } catch (RuntimeException | LinkageError e) {
            report("the kernel '" + chosen.name() + "' cannot be created, the scalar kernel is used: " + e);
            return scalar.get();
        }
    }

    /**
     * Lists the kernels that can run on this machine.
     *
     * @return the names of every kernel usable here, {@code "scalar"} first
     */
    public List<String> available() {
        List<String> names = new ArrayList<>();
        names.add(SCALAR);
        for (KernelProvider<K> p : providers) {
            names.add(p.name());
        }
        return names;
    }
}
