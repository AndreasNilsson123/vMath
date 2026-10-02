package vmath.bulk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The selection logic of {@link MatrixKernels}, against the providers of {@link TestMatrixKernelProviders} (registered in the test resources). */
class MatrixKernelSelectionTest {

    @AfterEach
    void clearForcedKernel() {
        System.clearProperty("vmath.matrixKernel");
    }

    @Test
    void withoutAForcedNameTheScalarKernelWinsOverEveryProviderThatCannotOrShouldNotBeUsed() {
        // test-low (priority -5) and test-zero (0) do not beat the scalar kernel, test-unsupported says it cannot run, test-throws breaks its contract,
        // test-failing-create cannot build its kernel, and test-broken-constructor cannot even be instantiated: all of that ends in the scalar kernel
        assertEquals("scalar", MatrixKernels.best().name());
    }

    @Test
    void aForcedNameSelectsThatProviderEvenAtLowPriority() {
        System.setProperty("vmath.matrixKernel", "test-low");
        assertEquals("test-low", MatrixKernels.best().name());
        System.setProperty("vmath.matrixKernel", "test-zero");
        assertEquals("test-zero", MatrixKernels.best().name());
    }

    @Test
    void aForcedNameThatIsUnknownUnsupportedOrBrokenFallsBackToScalar() {
        for (String forced : List.of("scalar", "no-such-kernel", "test-unsupported", "test-throws", "test-broken-constructor")) {
            System.setProperty("vmath.matrixKernel", forced);
            assertEquals("scalar", MatrixKernels.best().name(), forced);
        }
        // a provider that is chosen but cannot build its kernel does not take culling down either
        System.setProperty("vmath.matrixKernel", "test-failing-create");
        assertEquals("scalar", MatrixKernels.best().name());
    }

    @Test
    void availableListsScalarFirstAndOnlyProvidersThatAreUsable() {
        List<String> names = MatrixKernels.available();
        assertEquals("scalar", names.get(0));
        assertTrue(names.contains("test-low") && names.contains("test-zero") && names.contains("test-failing-create"), names.toString());
        assertFalse(names.contains("test-unsupported") || names.contains("test-throws") || names.contains("test-broken-constructor"), names.toString());
    }

    @Test
    void manyThreadsMayAskAtOnce() throws Exception {
        assertEquals(MatrixKernels.best().name(), MatrixKernels.best().name(), "the choice does not change between calls");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                results.add(pool.submit(() -> {
                    String last = null;
                    for (int i = 0; i < 500; i++) {
                        MatrixKernel k = MatrixKernels.best();
                        assertNotNull(k);
                        last = k.name();
                        MatrixKernels.available();
                    }
                    return last;
                }));
            }
            for (Future<String> f : results) {
                assertEquals("scalar", f.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
