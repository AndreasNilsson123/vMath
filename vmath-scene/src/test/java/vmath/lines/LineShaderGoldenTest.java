package vmath.lines;

import java.util.List;
import org.junit.jupiter.api.Test;
import vmath.gl.GraphicsCapabilities;

/**
 * Pins, byte for byte, the GLSL of every line strategy at the capabilities that choose it, so that
 * a version parameter added to the generators (GPU-9) cannot change the default text unnoticed.
 * The files are under {@code src/test/resources/golden/lines}; the helper writes them with
 * {@code -Dvmath.writeGolden=true}.
 */
class LineShaderGoldenTest {

    @Test
    void everyLineStrategyHasTheTextItHadBefore() {
        record Case(LineStrategy strategy, GraphicsCapabilities caps) {
        }
        List<Case> cases = List.of(new Case(LineStrategy.INDIRECT_DRAW_ID, GraphicsCapabilities.openGl(4, 6, List.of())),
                new Case(LineStrategy.INDIRECT_DRAW_ID, GraphicsCapabilities.openGl(4, 5, List.of("GL_ARB_shader_draw_parameters"))),
                new Case(LineStrategy.INDIRECT_INSTANCE_STYLE, GraphicsCapabilities.openGl(4, 3, List.of())),
                new Case(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.openGl(4, 2, List.of())), new Case(LineStrategy.EXPANDED_MULTIDRAW, GraphicsCapabilities.baseline()),
                new Case(LineStrategy.INSTANCED_LOOP, GraphicsCapabilities.baseline()), new Case(LineStrategy.HAIRLINE, GraphicsCapabilities.baseline()));
        for (Case c : cases) {
            LineRenderPlan plan = LineRenderPlan.force(c.strategy(), c.caps());
            String name = "lines/" + c.strategy() + "-" + c.caps().glsl().number() + (c.caps().glsl().number() == 450 ? "-ext" : "");
            vmath.gl.Golden.check(name + "-vertex", plan.vertexShader());
            vmath.gl.Golden.check(name + "-fragment", plan.fragmentShader());
        }
    }
}
