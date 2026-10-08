package vmath.samples.verify;

import static org.lwjgl.opengl.GL46.*;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import vmath.gl.DrawElementsIndirectGpu;
import vmath.gl.GlslType;
import vmath.gl.GlslType.Array;
import vmath.gl.GlslType.Mat;
import vmath.gl.GlslType.Member;
import vmath.gl.GlslType.Scalar;
import vmath.gl.GlslType.Struct;
import vmath.gl.GlslType.Vec;
import vmath.gl.GpuLayout;
import vmath.gl.LayoutValidator;
import vmath.gl.ShaderHeader;
import vmath.gl.StructLayout;
import vmath.gpucull.ClusterCullObjectGpu;
import vmath.gpucull.ClusterCullViewGpu;
import vmath.gpucull.CullObjectGpu;
import vmath.gpucull.CullViewGpu;
import vmath.lines.LineGpu;
import vmath.map.AreaRenderPlan;
import vmath.map.SymbolGpu;
import vmath.samples.framework.Gl;

/**
 * Compares the layout that the library computes for a struct ({@code StructLayout}: the offsets,
 * the strides and the size under {@code std140} and {@code std430}) with what the OpenGL driver says
 * about a block of that struct, through the program introspection ({@code glGetProgramResourceiv}:
 * {@code GL_OFFSET}, {@code GL_ARRAY_STRIDE}, {@code GL_MATRIX_STRIDE}, {@code GL_BUFFER_DATA_SIZE}),
 * with {@code LayoutValidator} doing the comparison.
 *
 * <p>For every struct a compute shader is made that declares the struct in a block of the layout (a
 * uniform block for {@code std140}, a storage block for {@code std430}) and reads every leaf member (so that
 * the driver keeps them: it drops what is not used); the structs are those the library generates and uses
 * (the culling records, the line, symbol and area records, the indirect draw record) and some made to exercise
 * the rules (a {@code mat3} and {@code mat4}, arrays of scalars, vectors and structs, nested structs,
 * three-component vectors followed by a scalar).
 *
 * <p>Needs a display and an OpenGL 4.3 driver; {@code ./gradlew -Psamples :vmath-samples:gpuIt}. Internal: part
 * of the samples.
 *
 * <p><b>Thread safety.</b> Not thread-safe: run it on the main thread.
 */
public final class LayoutGpuCheck {

    private LayoutGpuCheck() {
    }

    /** One line of the report. */
    public record Outcome(String name, boolean ok, String detail) {
    }

    private static List<Struct> structs() {
        Struct light = new Struct("Light", List.of(new Member("position", GlslType.VEC3), new Member("radius", GlslType.FLOAT), new Member("color", GlslType.VEC3), new Member("kind", GlslType.INT)));
        Struct inner = new Struct("Inner", List.of(new Member("a", GlslType.VEC3), new Member("b", GlslType.FLOAT), new Member("c", GlslType.VEC2)));
        List<Struct> out = new ArrayList<>();
        out.add(CullObjectGpu.LAYOUT.struct());
        out.add(CullViewGpu.LAYOUT.struct());
        out.add(ClusterCullObjectGpu.LAYOUT.struct());
        out.add(ClusterCullViewGpu.LAYOUT.struct());
        out.add(DrawElementsIndirectGpu.LAYOUT.struct());
        out.add(LineGpu.SEGMENT);
        out.add(LineGpu.STYLE);
        out.add(SymbolGpu.SYMBOL);
        out.add(AreaRenderPlan.STYLE);
        out.add(light);
        out.add(new Struct("Scene", List.of(new Member("viewProjection", GlslType.MAT4), new Member("lights", new Array(light, 4)), new Member("ambient", GlslType.VEC3), new Member("count", GlslType.UINT))));
        out.add(new Struct("Matrices", List.of(new Member("m3", GlslType.MAT3), new Member("f", GlslType.FLOAT), new Member("m2", new Mat(2, 3)), new Member("m4s", new Array(GlslType.MAT4, 2)))));
        out.add(new Struct("Arrays", List.of(new Member("scalars", new Array(GlslType.FLOAT, 5)), new Member("vec2s", new Array(GlslType.VEC2, 3)), new Member("vec3s", new Array(GlslType.VEC3, 3)),
                new Member("tail", GlslType.FLOAT), new Member("ints", new Array(GlslType.INT, 2)))));
        out.add(new Struct("Nested", List.of(new Member("head", GlslType.FLOAT), new Member("inner", inner), new Member("inners", new Array(inner, 3)), new Member("after", GlslType.VEC3), new Member("last", GlslType.UINT))));
        out.add(new Struct("Awkward", List.of(new Member("a", GlslType.VEC3), new Member("b", GlslType.FLOAT), new Member("c", GlslType.VEC3), new Member("d", GlslType.VEC3), new Member("e", GlslType.FLOAT),
                new Member("f", GlslType.VEC2), new Member("g", GlslType.VEC3))));
        return out;
    }

    /** Expressions that read every leaf under a path, so that the driver keeps the member. */
    private static void touches(GlslType type, String path, List<String> out) {
        switch (type) {
            case Scalar s -> out.add("float(" + path + ")");
            case Vec v -> out.add("float(" + path + ".x)");
            case Mat m -> out.add("float(" + path + "[0][0])");
            case Struct s -> {
                for (Member m : s.members()) {
                    touches(m.type(), path + "." + m.name(), out);
                }
            }
            case Array a -> {
                for (int i = 0; i < a.length(); i++) {
                    touches(a.element(), path + "[" + i + "]", out);
                }
            }
        }
    }

    private static String shader(Struct struct, GpuLayout layout) {
        String storage = layout == GpuLayout.STD430 ? "buffer" : "uniform";
        List<String> terms = new ArrayList<>();
        for (int element = 0; element < 2; element++) {
            for (Member m : struct.members()) {
                touches(m.type(), "data[" + element + "]." + m.name(), terms);
            }
        }
        return "#version 430\nlayout(local_size_x = 1) in;\n" + ShaderHeader.builder("PROBE").struct(struct.layout(layout)).build(ShaderHeader.Language.GLSL)
                + "layout(" + layout.glslName() + ") " + storage + " Probe { " + struct.name() + " data[2]; };\nlayout(std430) buffer Sink { float sink; };\nvoid main() {\n    sink = " + String.join(" + ", terms)
                + ";\n}\n";
    }

    private static String check(Struct struct, GpuLayout layout) {
        return check(struct, layout, layout);
    }

    /** The shader declares the struct in {@code layout}; the Java side is asked in {@code javaLayout} (a different one is the control that the check can fail). */
    private static String check(Struct struct, GpuLayout layout, GpuLayout javaLayout) {
        StructLayout expected = struct.layout(javaLayout);
        int program = Gl.computeProgram(shader(struct, layout));
        try {
            boolean ssbo = layout == GpuLayout.STD430;
            int variables = ssbo ? GL_BUFFER_VARIABLE : GL_UNIFORM;
            int count = glGetProgramInterfacei(program, variables, GL_ACTIVE_RESOURCES);
            List<LayoutValidator.Reflected> reflected = new ArrayList<>();
            int[] props = ssbo ? new int[] {GL_OFFSET, GL_ARRAY_STRIDE, GL_MATRIX_STRIDE, GL_BLOCK_INDEX, GL_TOP_LEVEL_ARRAY_STRIDE} : new int[] {GL_OFFSET, GL_ARRAY_STRIDE, GL_MATRIX_STRIDE, GL_BLOCK_INDEX};
            int[] values = new int[props.length];
            long firstOffset0 = -1, firstOffset1 = -1, topLevelStride = -1;
            String firstName = expected.fields().get(0).name();
            for (int i = 0; i < count; i++) {
                String name = glGetProgramResourceName(program, variables, i);
                glGetProgramResourceiv(program, variables, i, props, null, values);
                if (values[3] < 0) {
                    continue;                                             // a loose uniform, or the sink
                }
                // a block without an instance name: the members are named by the member of the block, data[0].x and data[1].x
                if (name.startsWith("data[0].")) {
                    reflected.add(new LayoutValidator.Reflected(name, values[0], values[1], values[2]));
                    if (ssbo) {
                        topLevelStride = values[4];
                    }
                    if (name.equals("data[0]." + firstName) || name.startsWith("data[0]." + firstName + "[") || name.startsWith("data[0]." + firstName + ".")) {
                        firstOffset0 = firstOffset0 < 0 ? values[0] : Math.min(firstOffset0, values[0]);
                    }
                } else if (name.startsWith("data[1].") && (name.equals("data[1]." + firstName) || name.startsWith("data[1]." + firstName + "[") || name.startsWith("data[1]." + firstName + "."))) {
                    firstOffset1 = firstOffset1 < 0 ? values[0] : Math.min(firstOffset1, values[0]);
                }
            }
            // the size of the struct is the distance between its two elements in an array: the padding at its end
            List<String> problems = LayoutValidator.validate(expected, reflected, "data[0].", true, -1);
            if (ssbo) {
                // a storage block lists the members of the first element only and says how far apart the elements are
                if (topLevelStride != expected.size()) {
                    problems.add("array stride (size) " + topLevelStride + " in the shader, " + expected.size() + " in Java");
                }
            } else if (firstOffset0 < 0 || firstOffset1 < 0) {
                problems.add("the driver did not list the first member of both elements");
            } else if (firstOffset1 - firstOffset0 != expected.size()) {
                problems.add("array stride (size) " + (firstOffset1 - firstOffset0) + " in the shader, " + expected.size() + " in Java");
            }
            if (reflected.isEmpty()) {
                problems.add("the driver listed no member");
            }
            return problems.isEmpty() ? null : String.join("; ", problems);
        } finally {
            glDeleteProgram(program);
        }
    }

    /**
     * Runs the check on the current context.
     *
     * @param out where to print the progress; may be {@code null}
     * @return one outcome per struct and layout
     */
    public static List<Outcome> check(PrintStream out) {
        List<Outcome> results = new ArrayList<>();
        for (Struct s : structs()) {
            for (GpuLayout layout : new GpuLayout[] {GpuLayout.STD140, GpuLayout.STD430}) {
                String name = s.name() + " in " + layout.glslName();
                String problem;
                try {
                    problem = check(s, layout);
                } catch (RuntimeException e) {
                    problem = String.valueOf(e.getMessage());
                }
                Outcome o = new Outcome(name, problem == null, problem == null ? "offsets, strides and size agree with the driver" : problem);
                results.add(o);
                if (out != null) {
                    out.println((o.ok() ? "ok    " : "FAIL  ") + name + (o.ok() ? "" : "\n      " + o.detail()));
                }
            }
        }
        // the control: the driver's std140 layout of arrays of scalars against the Java std430 one must be found different
        String control = null;
        try {
            Struct arrays = structs().stream().filter(t -> t.name().equals("Arrays")).findFirst().orElseThrow();
            control = check(arrays, GpuLayout.STD140, GpuLayout.STD430);
        } catch (RuntimeException e) {
            control = String.valueOf(e.getMessage());
        }
        Outcome o = new Outcome("the comparison rejects the std430 layout of a struct that the driver lays out as std140", control != null, control == null ? "it accepted them" : "rejected: " + control);
        results.add(o);
        if (out != null) {
            out.println((o.ok() ? "ok    " : "FAIL  ") + o.name());
        }
        return results;
    }
}
