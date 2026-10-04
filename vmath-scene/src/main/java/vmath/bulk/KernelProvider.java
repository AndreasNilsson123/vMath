package vmath.bulk;

import vmath.annotations.Experimental;

/**
 * What every kernel provider offers, whatever kind of kernel it creates.
 *
 * <p>{@link KernelSelector} works on this shape; the provider interfaces of the library
 * ({@link MatrixKernelProvider} and {@code vmath.spatial.FrustumKernelProvider}) are adapted to it
 * with {@link #of}, so that the stable one does not depend on this experimental type.
 *
 * <p><b>Thread safety.</b> Not specified: the library does not define the threading behavior of
 * implementations of this interface; see the methods for what they promise.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * MatrixKernelProvider service = new SimdMatrixKernelProvider();
 * KernelProvider<MatrixKernel> provider = KernelProvider.of(service.name(), service.priority(), service::isSupported, service::create);
 * if (provider.isSupported()) {
 *     MatrixKernel kernel = provider.create();
 * }
 * }</pre>
 *
 * @param <K> the kind of kernel the provider creates
 */
@Experimental("the SPI may change")
public interface KernelProvider<K> {

    /**
     * Exposes the name by which the kernel of this provider is selected through a system
     * property.
     *
     * @return identifier of the kernel this provider creates
     */
    String name();

    /**
     * Exposes the rank of this provider, where the highest priority wins when the best kernel is
     * chosen automatically.
     *
     * <p>The scalar kernel counts as priority 0, so a provider with a priority of 0 or less is
     * only used when it is selected by name.
     *
     * @return higher wins when the best kernel is chosen
     */
    int priority();

    /**
     * Returns whether the kernel can run here (for example, enough SIMD lanes).
     *
     * <p>Must not throw.
     *
     * @return {@code true} if the kernel can run here
     */
    boolean isSupported();

    /**
     * Creates a kernel.
     *
     * @return a new kernel, never {@code null}
     */
    K create();

    /**
     * Adapts the parts of a provider that some other interface offers.
     *
     * <p>The name and the priority are read once, when the adapter is made; the other two are
     * called on the arguments each time.
     *
     * @param name the name of the kernel; must not be {@code null}
     * @param priority the rank of the provider
     * @param supported whether the kernel can run here; must not be {@code null}
     * @param create creates a kernel; must not be {@code null}
     * @param <K> the kind of kernel
     * @return a provider that forwards to the arguments
     */
    static <K> KernelProvider<K> of(String name, int priority, java.util.function.BooleanSupplier supported, java.util.function.Supplier<? extends K> create) {
        return new KernelProvider<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean isSupported() {
                return supported.getAsBoolean();
            }

            @Override
            public K create() {
                return create.get();
            }
        };
    }
}
