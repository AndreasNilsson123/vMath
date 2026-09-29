package vmath.codegen;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Modifier;

/**
 * Generates a {@code <Name>Gpu} class for every record annotated {@code @GpuStruct}. Only component <em>names and types</em>
 * are read (from the source, by simple name), so the generator needs no compiled classes and no dependency on vmath itself.
 * The generated class builds its layout at class-initialization time from vmath's own layout engine
 * ({@code GlslType}, {@code StructLayout}), so the layout rules exist in exactly one place.
 */
final class GpuStructGenerator {

    /** One generated file. */
    record Output(String className, String source) {
    }

    /** How one element type maps to GLSL and how it is written. */
    private record Elem(String typeExpr, String writeMethod, boolean needsColumnStride, String columnStrideType) {
    }

    private final Set<String> structNames = new java.util.HashSet<>();
    private final Map<String, String> structPackages = new HashMap<>();
    private final Map<String, String> structLayouts = new HashMap<>();

    private static boolean mentionsGpu(String source) {
        return source.contains("GpuStruct");
    }

    /** Records the @GpuStruct records of a file so that other structs can refer to them as members. */
    void register(String source, String fileName) {
        if (!mentionsGpu(source)) {
            return;
        }
        Transformer.Parsed p = Transformer.parse(source, fileName);
        String pkg = p.unit().getPackageName() == null ? "" : p.unit().getPackageName().toString();
        for (Tree t : p.unit().getTypeDecls()) {
            if (t instanceof ClassTree c && c.getKind() == Tree.Kind.RECORD
                    && Transformer.find(c.getModifiers(), "GpuStruct") != null) {
                structNames.add(c.getSimpleName().toString());
                structPackages.put(c.getSimpleName().toString(), pkg);
                structLayouts.put(c.getSimpleName().toString(), layoutOf(Transformer.find(c.getModifiers(), "GpuStruct")));
            }
        }
    }

    List<Output> generate(String source, String fileName) {
        List<Output> out = new ArrayList<>();
        if (!mentionsGpu(source)) {
            return out;
        }
        Transformer.Parsed p = Transformer.parse(source, fileName);
        String pkg = p.unit().getPackageName() == null ? "" : p.unit().getPackageName().toString();
        for (Tree t : p.unit().getTypeDecls()) {
            if (t instanceof ClassTree c && c.getKind() == Tree.Kind.RECORD) {
                AnnotationTree ann = Transformer.find(c.getModifiers(), "GpuStruct");
                if (ann != null) {
                    out.add(generateOne(p, c, ann, pkg, fileName));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- one record

    private Output generateOne(Transformer.Parsed p, ClassTree record, AnnotationTree ann, String pkg, String fileName) {
        String name = record.getSimpleName().toString();
        String layout = layoutOf(ann);
        List<VariableTree> components = new ArrayList<>();
        for (Tree m : record.getMembers()) {
            if (m instanceof VariableTree v && !v.getModifiers().getFlags().contains(Modifier.STATIC)) {
                components.add(v);
            }
        }
        if (components.isEmpty()) {
            throw error(fileName, "@GpuStruct record " + name + " has no components");
        }

        Set<String> imports = new LinkedHashSet<>();
        for (ImportTree imp : p.unit().getImports()) {
            String text = imp.toString().trim();
            if (!text.contains("vmath.annotations.")) {
                imports.add(text);
            }
        }
        imports.add("import java.lang.foreign.MemorySegment;");
        imports.add("import java.nio.ByteBuffer;");
        imports.add("import java.util.List;");
        imports.add("import vmath.gl.GlslType;");
        imports.add("import vmath.gl.GpuLayout;");
        imports.add("import vmath.gl.GpuWriter;");
        imports.add("import vmath.gl.StructLayout;");

        StringBuilder members = new StringBuilder();
        StringBuilder constants = new StringBuilder();
        StringBuilder writes = new StringBuilder();

        for (int i = 0; i < components.size(); i++) {
            VariableTree v = components.get(i);
            String field = v.getName().toString();
            String konst = snake(field);
            AnnotationTree arrayAnn = Transformer.find(v.getModifiers(), "GpuArray");
            boolean uint = Transformer.find(v.getModifiers(), "GpuUint") != null;
            Tree typeTree = v.getType();
            boolean isArray = typeTree instanceof ArrayTypeTree;
            String typeName = isArray ? ((ArrayTypeTree) typeTree).getType().toString() : typeTree.toString();
            if (arrayAnn != null && !isArray) {
                throw error(fileName, name + "." + field + ": @GpuArray is only valid on array components");
            }
            if (isArray && arrayAnn == null) {
                throw error(fileName, name + "." + field + ": array components need @GpuArray(length)");
            }
            Elem elem = element(typeName, uint, name + "." + field, fileName, imports, layout);
            String memberType = elem.typeExpr();
            int length = 0;
            if (isArray) {
                length = arrayLength(arrayAnn, name + "." + field, fileName);
                memberType = "GlslType.array(" + elem.typeExpr() + ", " + length + ")";
            }
            members.append("            new GlslType.Member(\"").append(field).append("\", ").append(memberType).append(")");
            members.append(i + 1 < components.size() ? ",\n" : "))");

            constants.append("    public static final long OFFSET_").append(konst)
                    .append(" = LAYOUT.offsetOf(\"").append(field).append("\");\n");
            if (isArray) {
                constants.append("    public static final long STRIDE_").append(konst)
                        .append(" = ((GlslType.Array) LAYOUT.field(\"").append(field).append("\").type()).stride(LAYOUT.layout());\n");
            }
            if (elem.needsColumnStride()) {
                constants.append("    private static final long COLUMN_").append(konst).append(" = ")
                        .append(elem.columnStrideType()).append(".columnStride(GpuLayout.").append(layout).append(");\n");
            }

            String access = "v." + field + "()";
            String offset = "base + OFFSET_" + konst;
            if (!isArray) {
                writes.append("        ").append(writeCall(elem, offset, access, konst)).append("\n");
            } else {
                String local = "a" + i;
                writes.append("        {\n")
                        .append("            ").append(typeName).append("[] ").append(local).append(" = ").append(access).append(";\n")
                        .append("            if (").append(local).append(".length != ").append(length).append(") {\n")
                        .append("                throw new IllegalArgumentException(\"").append(field).append(" must have ")
                        .append(length).append(" elements, has \" + ").append(local).append(".length);\n")
                        .append("            }\n")
                        .append("            for (int i = 0; i < ").append(length).append("; i++) {\n")
                        .append("                ").append(writeCall(elem, offset + " + i * STRIDE_" + konst, local + "[i]", konst)).append("\n")
                        .append("            }\n")
                        .append("        }\n");
            }
        }

        String cls = name + "Gpu";
        StringBuilder sb = new StringBuilder();
        if (!pkg.isEmpty()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        sb.append("// GENERATED from ").append(fileName).append(" (@GpuStruct) by vmath-codegen. Do not edit; edit the record.\n\n");
        for (String imp : imports) {
            sb.append(imp).append('\n');
        }
        sb.append("\n/**\n * Memory layout and writer for {@link ").append(name).append("} under ")
                .append(layout.toLowerCase()).append(".\n * Offsets are byte offsets from the start of the struct; {@link #SIZE} is its stride in an array.\n */\n");
        sb.append("public final class ").append(cls).append(" {\n\n");
        sb.append("    private ").append(cls).append("() {\n    }\n\n");
        sb.append("    /** The computed layout: offsets, size and alignment of every member. */\n");
        sb.append("    public static final StructLayout LAYOUT = new GlslType.Struct(\"").append(name).append("\", List.of(\n");
        sb.append(members).append(".layout(GpuLayout.").append(layout).append(");\n\n");
        sb.append("    /** Size in bytes, rounded up to {@link #ALIGNMENT}: the distance between elements of an array of this struct. */\n");
        sb.append("    public static final long SIZE = LAYOUT.size();\n\n");
        sb.append("    public static final int ALIGNMENT = LAYOUT.alignment();\n\n");
        sb.append(constants).append("\n");
        sb.append("    /** The matching GLSL struct declaration. */\n");
        sb.append("    public static final String GLSL = LAYOUT.struct().glslDeclaration();\n\n");
        sb.append("    /** Writes {@code v} at byte offset {@code base}. Padding bytes are not touched. */\n");
        sb.append("    public static void write(").append(name).append(" v, MemorySegment dst, long base) {\n");
        sb.append(writes);
        sb.append("    }\n\n");
        sb.append("    /** Writes {@code v} at byte offset {@code base} of {@code dst}, in native byte order. */\n");
        sb.append("    public static void write(").append(name).append(" v, ByteBuffer dst, int base) {\n");
        sb.append("        write(v, GpuWriter.of(dst), base);\n    }\n}\n");
        return new Output(cls, sb.toString());
    }

    private String writeCall(Elem elem, String offset, String value, String konst) {
        String m = elem.writeMethod();
        if (m.startsWith("struct:")) {
            return m.substring("struct:".length()) + ".write(" + value + ", dst, " + offset + ");";
        }
        if (elem.needsColumnStride()) {
            return "GpuWriter." + m + "(dst, " + offset + ", " + value + ", COLUMN_" + konst + ");";
        }
        return "GpuWriter." + m + "(dst, " + offset + ", " + value + ");";
    }

    // ---------------------------------------------------------------- type mapping

    private Elem element(String type, boolean uint, String where, String fileName, Set<String> imports, String layout) {
        if (uint && !type.equals("int")) {
            throw error(fileName, where + ": @GpuUint applies to int components only");
        }
        switch (type) {
            case "float":
                return new Elem("GlslType.FLOAT", "putFloat", false, null);
            case "int":
                return new Elem(uint ? "GlslType.UINT" : "GlslType.INT", "putInt", false, null);
            case "Vec2f":
                return new Elem("GlslType.VEC2", "putVec2", false, null);
            case "Vec3f":
                return new Elem("GlslType.VEC3", "putVec3", false, null);
            case "Vec4f":
                return new Elem("GlslType.VEC4", "putVec4", false, null);
            case "Quatf":
                return new Elem("GlslType.VEC4", "putQuat", false, null);
            case "Vec2i":
                return new Elem("GlslType.IVEC2", "putIVec2", false, null);
            case "Vec3i":
                return new Elem("GlslType.IVEC3", "putIVec3", false, null);
            case "Mat4f":
                return new Elem("GlslType.MAT4", "putMat4", false, null);
            case "Mat3f":
                return new Elem("GlslType.MAT3", "putMat3", true, "GlslType.MAT3");
            case "Mat4x3f":
                return new Elem("GlslType.mat(4, 3)", "putMat4x3", true, "GlslType.mat(4, 3)");
            default:
                break;
        }
        if (type.matches("(Vec[234]|Quat|Mat[34](x3)?)d") || type.equals("double") || type.equals("long")) {
            throw error(fileName, where + ": " + type + " is not supported in a GPU struct (no 64-bit types); use the float type");
        }
        if (structNames.contains(type)) {
            // a nested struct is generated with its own layout: mixing rules would silently produce wrong offsets
            if (!structLayouts.get(type).equals(layout)) {
                throw error(fileName, where + ": nested struct " + type + " uses " + structLayouts.get(type).toLowerCase()
                        + " but the enclosing struct uses " + layout.toLowerCase() + "; they must match");
            }
            String pkg = structPackages.get(type);
            if (!pkg.isEmpty()) {
                imports.add("import " + pkg + "." + type + "Gpu;");
            }
            return new Elem(type + "Gpu.LAYOUT.struct()", "struct:" + type + "Gpu", false, null);
        }
        throw error(fileName, where + ": unsupported GPU type '" + type + "'. Supported: float, int, Vec2f/3f/4f, Quatf, "
                + "Vec2i/3i, Mat3f, Mat4f, Mat4x3f and other @GpuStruct records");
    }

    private static String layoutOf(AnnotationTree ann) {
        for (ExpressionTree e : ann.getArguments()) {
            if (e instanceof AssignmentTree as && as.getVariable().toString().equals("layout")) {
                String text = as.getExpression().toString();
                String last = text.substring(text.lastIndexOf('.') + 1);
                if (last.equals("STD140") || last.equals("STD430") || last.equals("SCALAR")) {
                    return last;
                }
                throw new Transformer.TemplateException("unknown @GpuStruct layout '" + text + "'");
            }
        }
        return "STD140";
    }

    private static int arrayLength(AnnotationTree ann, String where, String fileName) {
        for (ExpressionTree e : ann.getArguments()) {
            ExpressionTree value = e instanceof AssignmentTree as ? as.getExpression() : e;
            if (value instanceof LiteralTree lt && lt.getValue() instanceof Integer n) {
                if (n < 1) {
                    throw error(fileName, where + ": @GpuArray length must be at least 1");
                }
                return n;
            }
        }
        throw error(fileName, where + ": @GpuArray needs an integer literal length");
    }

    /** {@code viewProj} becomes {@code VIEW_PROJ}. */
    static String snake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(camel.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static Transformer.TemplateException error(String file, String message) {
        return new Transformer.TemplateException(file + ": " + message);
    }
}
