package vmath.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vmath.annotations.Experimental;
import vmath.gl.GlslType.Array;
import vmath.gl.GlslType.Mat;
import vmath.gl.GlslType.Struct;

/**
 * Compares a Java-side {@link StructLayout} with what the shader compiler says the layout is. The compiler's side comes from program introspection
 * ({@code glGetProgramResourceiv} with {@code GL_OFFSET}, {@code GL_ARRAY_STRIDE}, {@code GL_MATRIX_STRIDE}, {@code GL_BUFFER_DATA_SIZE}) or from SPIR-V reflection
 * (the {@code Offset}, {@code ArrayStride} and {@code MatrixStride} decorations), reduced to a list of {@link Reflected} members. The library calls no graphics API, so
 * the caller supplies that list; run this in an opt-in test that has a GPU or a SPIR-V toolchain.
 *
 * <p>Member names are paths relative to the struct: {@code planes[0]}, {@code lights[0].color}. Reflection often reports an array as its first element
 * ({@code name[0]}), and the check accepts that and the bare name. {@link #expected} produces the list the Java side implies, which is also a convenient thing to
 * print when a validation fails.
 */
@Experimental("the reflection input record may gain fields")
public final class LayoutValidator {

    /**
     * One member as reflection reports it. {@code arrayStride} and {@code matrixStride} are 0 (or negative, as GL reports "none") when the member is neither an array
     * nor a matrix.
     */
    public record Reflected(String name, long offset, long arrayStride, long matrixStride) {
    }

    private LayoutValidator() {
    }

    /** The members the Java layout implies, flattened to leaves (scalars, vectors, matrices) with array elements as {@code [0]}. */
    public static List<Reflected> expected(StructLayout layout) {
        List<Reflected> out = new ArrayList<>();
        flatten(layout.struct(), layout.layout(), 0, "", out);
        return out;
    }

    private static void flatten(Struct struct, GpuLayout rules, long base, String prefix, List<Reflected> out) {
        StructLayout sl = struct.layout(rules);
        for (StructLayout.Field f : sl.fields()) {
            leaf(f.type(), rules, base + f.offset(), prefix + f.name(), out);
        }
    }

    private static void leaf(GlslType t, GpuLayout rules, long offset, String path, List<Reflected> out) {
        switch (t) {
            case Struct s -> flatten(s, rules, offset, path + ".", out);
            case Array a -> {
                long stride = a.stride(rules);
                if (a.element() instanceof Struct s) {
                    for (int i = 0; i < a.length(); i++) {
                        flatten(s, rules, offset + i * stride, path + "[" + i + "].", out);
                    }
                } else {
                    long matrix = a.element() instanceof Mat m ? m.columnStride(rules) : 0;
                    out.add(new Reflected(path + "[0]", offset, stride, matrix));
                }
            }
            case Mat m -> out.add(new Reflected(path, offset, 0, m.columnStride(rules)));
            default -> out.add(new Reflected(path, offset, 0, 0));
        }
    }

    /**
     * Compares the layout with reflected members.
     *
     * @param layout        the Java layout
     * @param reflected     the members from the compiler
     * @param prefix        a prefix to strip from every reflected name, such as {@code "view."} for a block instance named {@code view}; names that do not start with it are
     *                      reported as unexpected
     * @param requireAll    report members that reflection does not list (compilers drop unused members, so this is usually {@code false})
     * @param reflectedSize the block size the compiler reports ({@code GL_BUFFER_DATA_SIZE}), or a negative number to skip the check
     * @return the differences, in plain words; empty when the layouts agree
     */
    public static List<String> validate(StructLayout layout, List<Reflected> reflected, String prefix, boolean requireAll, long reflectedSize) {
        List<String> problems = new ArrayList<>();
        Map<String, Reflected> want = new HashMap<>();
        for (Reflected e : expected(layout)) {
            want.put(e.name(), e);
        }
        Set<String> seen = new HashSet<>();
        for (Reflected r : reflected) {
            String name = r.name();
            if (prefix != null && !prefix.isEmpty()) {
                if (!name.startsWith(prefix)) {
                    problems.add("unexpected member '" + name + "' (does not start with '" + prefix + "')");
                    continue;
                }
                name = name.substring(prefix.length());
            }
            Reflected e = want.get(name);
            if (e == null && !name.endsWith("]")) {
                e = want.get(name + "[0]");
                if (e != null) {
                    name = name + "[0]";
                }
            }
            if (e == null) {
                problems.add("unexpected member '" + name + "': the Java layout of " + layout.struct().name() + " has no such member");
                continue;
            }
            seen.add(name);
            if (r.offset() != e.offset()) {
                problems.add(name + ": offset " + r.offset() + " in the shader, " + e.offset() + " in Java");
            }
            if (e.arrayStride() > 0 && r.arrayStride() != e.arrayStride()) {
                problems.add(name + ": array stride " + r.arrayStride() + " in the shader, " + e.arrayStride() + " in Java");
            }
            if (e.matrixStride() > 0 && r.matrixStride() != e.matrixStride()) {
                problems.add(name + ": matrix stride " + r.matrixStride() + " in the shader, " + e.matrixStride() + " in Java");
            }
        }
        if (requireAll) {
            for (String name : want.keySet()) {
                if (!seen.contains(name)) {
                    problems.add("missing member '" + name + "' in the shader's reflection");
                }
            }
        }
        if (reflectedSize >= 0 && reflectedSize != layout.size()) {
            problems.add("size " + reflectedSize + " in the shader, " + layout.size() + " in Java");
        }
        problems.sort(null);
        return problems;
    }
}
