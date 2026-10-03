package vmath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Counts the public and protected declarations without a doc comment, per package, in the hand-written sources and the float templates (the generator copies the
 * template's comments to the double twins) and in the published annotation and SIMD modules, and fails when a package has more of them than
 * {@link #ALLOWED_MISSING} says (nothing, now), so that no change adds an undocumented public member unnoticed. {@code -Dvmath.verbose=true} prints the table and the
 * first undocumented declarations of each package, {@code -Dvmath.docs.all=true} all of them.
 *
 * <p>Not counted: methods marked {@code @Override} (the contract is the overridden one), record components (private fields, documented by {@code @param} on the
 * record), and {@code serialVersionUID}. A member of a package-private or private class is not public API and is not counted either.
 */
class PublicDocsTest {

    /**
     * Undocumented public declarations allowed per package; every package is complete, so this is empty. A package that must temporarily have gaps gets an entry
     * here with its count, which then only goes down.
     */
    private static final Map<String, Integer> ALLOWED_MISSING = Map.of();

    private record Missing(String where, int line, String what) {
    }

    @Test
    void undocumentedPublicDeclarationsOnlyGoDown() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String part : List.of("vmath-core", "vmath-geo", "vmath-scene", "vmath-render")) {
            roots.add(Path.of("..", part, "src/main/java"));
            roots.add(Path.of("..", part, "src/template/java"));
        }
        roots.add(Path.of("../vmath-annotations/src/main/java"));
        roots.add(Path.of("../vmath-simd/src/main/java"));
        if (!Files.isDirectory(roots.get(0))) {
            return; // running outside the project directory
        }
        Map<String, List<Missing>> byPackage = new TreeMap<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            List<Path> files = new ArrayList<>();
            for (Path root : roots) {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (var walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("module-info.java")).forEach(files::add);
                }
            }
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of("-proc:none"), null, units);
            DocTrees docs = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                String file = Path.of(unit.getSourceFile().toUri()).getFileName().toString();
                new TreePathScanner<Void, Boolean>() {
                    @Override
                    public Void visitClass(ClassTree c, Boolean publicSoFar) {
                        boolean isPublic = publicSoFar && visible(c.getModifiers().getFlags(), getCurrentPath().getParentPath().getLeaf(), c);
                        if (isPublic) {
                            check(getCurrentPath(), c.getSimpleName().toString(), "class");
                        }
                        return super.visitClass(c, isPublic);
                    }

                    @Override
                    public Void visitMethod(MethodTree m, Boolean inPublicClass) {
                        if (inPublicClass && visible(m.getModifiers().getFlags(), getCurrentPath().getParentPath().getLeaf(), m) && !overrides(m)) {
                            check(getCurrentPath(), m.getName().toString(), "method");
                        }
                        return null; // nothing inside a method is API
                    }

                    @Override
                    public Void visitVariable(VariableTree v, Boolean inPublicClass) {
                        Tree parent = getCurrentPath().getParentPath().getLeaf();
                        if (inPublicClass && parent instanceof ClassTree && visible(v.getModifiers().getFlags(), parent, v) && !v.getName().contentEquals("serialVersionUID")) {
                            check(getCurrentPath(), v.getName().toString(), "field");
                        }
                        return null;
                    }

                    @SuppressWarnings("removal") // JDK 28 deprecates getStartPosition(unit, tree); there is no replacement on the JDK 25 baseline
                    private void check(TreePath path, String name, String kind) {
                        if (docs.getDocCommentTree(path) == null) {
                            long pos = docs.getSourcePositions().getStartPosition(unit, path.getLeaf());
                            byPackage.computeIfAbsent(pkg, k -> new ArrayList<>())
                                    .add(new Missing(file, (int) unit.getLineMap().getLineNumber(pos), kind + " " + name));
                        }
                    }
                }.scan(new TreePath(unit), true);
            }
        }
        StringBuilder table = new StringBuilder();
        List<String> failures = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, List<Missing>> e : byPackage.entrySet()) {
            int missing = e.getValue().size();
            total += missing;
            int allowed = ALLOWED_MISSING.getOrDefault(e.getKey(), 0);
            table.append(String.format("%-22s %4d undocumented (allowed %d)%n", e.getKey(), missing, allowed));
            for (Missing m : e.getValue().subList(0, Boolean.getBoolean("vmath.docs.all") ? missing : Math.min(3, missing))) {
                table.append(String.format("    %s:%d %s%n", m.where, m.line, m.what));
            }
            if (missing > allowed) {
                failures.add(e.getKey() + " has " + missing + " undocumented public declarations, " + allowed + " allowed; first: " + e.getValue().get(0));
            } else if (missing < allowed) {
                failures.add(e.getKey() + " has only " + missing + " undocumented declarations: lower ALLOWED_MISSING to " + missing);
            }
        }
        Report.print(table.append("total ").append(total).append(" undocumented").toString());
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    /** Public, or protected, or a member of an interface or annotation (implicitly public). */
    private static boolean visible(java.util.Set<Modifier> flags, Tree parent, Tree self) {
        boolean inInterface = parent instanceof ClassTree c && (c.getKind() == Tree.Kind.INTERFACE || c.getKind() == Tree.Kind.ANNOTATION_TYPE);
        boolean enumConstant = self instanceof VariableTree v && parent instanceof ClassTree c && c.getKind() == Tree.Kind.ENUM && v.getModifiers().getFlags().contains(Modifier.PUBLIC);
        return flags.contains(Modifier.PUBLIC) || flags.contains(Modifier.PROTECTED) || (inInterface && !flags.contains(Modifier.PRIVATE)) || enumConstant;
    }

    private static boolean overrides(MethodTree m) {
        return m.getModifiers().getAnnotations().stream().anyMatch(a -> a.getAnnotationType().toString().endsWith("Override"));
    }
}
