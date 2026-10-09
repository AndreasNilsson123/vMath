package vmath.bulk;

import java.util.List;
import vmath.annotations.Experimental;

/**
 * Chooses a {@link MatrixKernel}.
 *
 * <p>{@link #best()} returns the supported provider with the highest <em>positive</em> priority,
 * falling back to the scalar kernel (which counts as priority 0); the property
 * {@code -Dvmath.matrixKernel=<name>} forces a specific one ({@code scalar} always works).
 * {@link Mat4fArray#multiply(Mat4fArray, Mat4fArray, Mat4fArray)} uses the choice made once at
 * class initialisation. {@code -Dvmath.deterministic=true} selects the scalar kernel in every case
 * (see {@link KernelSelector#isDeterministic()}).
 *
 * <p>The providers are looked up once, at the first selection, and the list is kept; the property
 * is read on every call. A provider that cannot be instantiated, whose {@code isSupported()}
 * throws, or whose module is not resolved is skipped, never fatal; that and a forced name that no
 * provider has are reported once through {@link System.Logger} (see {@link KernelSelector}).
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
    private static final KernelSelector<MatrixKernelProvider, MatrixKernel> SELECTOR = new KernelSelector<>(MatrixKernelProvider.class, p -> KernelProvider.of(p.name(), p.priority(), p::isSupported, p::create), () -> SCALAR, "vmath.matrixKernel");

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
