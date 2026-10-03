package vmath.validator;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Checks, while the code compiles, the rules that keep the types marked {@code @vmath.annotations.ValueType} valid {@code value record}s under Valhalla (JEP 401). A value object has no identity,
 * so on a value type:
 *
 * <ul>
 *   <li>{@code ==} and {@code !=} are rejected (comparison against {@code null} is fine): use {@code equals} or {@code approxEquals};</li>
 *   <li>{@code synchronized (x)}, and {@code wait}, {@code notify} and {@code notifyAll} on it, are rejected;</li>
 *   <li>{@code System.identityHashCode(x)} is rejected;</li>
 *   <li>{@code IdentityHashMap}, {@code WeakHashMap}, {@code WeakReference}, {@code SoftReference} and {@code PhantomReference} with a value type as their type argument are rejected;</li>
 *   <li>a {@code @ValueType} declaration must be a record, must not be {@code synchronized} in any method, and must not declare {@code finalize}.</li>
 * </ul>
 *
 * <p>Unlike a text scan this sees the <b>types</b>: the operand of {@code ==} is a value type because the compiler resolved it so, whatever it is called, whether it is a field of another
 * class, a method result or a lambda parameter. The check runs after the compiler's attribution of each class ({@code ANALYZE}), through the {@code TaskListener} of the compilation, and reports
 * through the compiler's own diagnostics, so a violation is an error at its line and the build fails.
 *
 * <p><b>What it knows.</b> A type is a value type if its declaration carries {@code @ValueType}. The annotation has class retention, so the marker is read from source for the types compiled
 * together with the code and from the class files of the modules below for the others; the generator keeps it on the float and double twins. Code that uses the library from a jar sees the marker
 * too, if it runs the processor. The option {@code -Avmath.validator=off} turns the processor off, {@code -Avmath.validator=warn} makes violations warnings.
 *
 * <p><b>Thread safety.</b> One instance lives in one compilation; the compiler calls it from one thread.
 */
@SupportedAnnotationTypes("*")
@SupportedOptions("vmath.validator")
public final class ValueTypeProcessor extends AbstractProcessor {

    private static final String VALUE_TYPE = "vmath.annotations.ValueType";
    private static final Set<String> IDENTITY_TYPES = Set.of("java.util.IdentityHashMap", "java.util.WeakHashMap", "java.lang.ref.WeakReference", "java.lang.ref.SoftReference",
            "java.lang.ref.PhantomReference");

    private Trees trees;
    private Diagnostic.Kind severity = Diagnostic.Kind.ERROR;
    private boolean enabled = true;

    /** Creates the processor; the compiler does it through the service file. */
    public ValueTypeProcessor() {
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        String mode = env.getOptions().get("vmath.validator");
        if ("off".equals(mode)) {
            enabled = false;
        } else if ("warn".equals(mode)) {
            severity = Diagnostic.Kind.WARNING;
        }
        if (!enabled) {
            return;
        }
        trees = Trees.instance(env);
        JavacTask.instance(env).addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() == TaskEvent.Kind.ANALYZE && e.getTypeElement() != null) {
                    check(e.getCompilationUnit(), e.getTypeElement());
                }
            }
        });
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        return false; // the work is done after attribution, in the task listener; this only registers it
    }

    private void check(CompilationUnitTree unit, TypeElement type) {
        TreePath path = trees.getPath(type);
        if (path == null) {
            return;
        }
        new Scanner(unit).scan(path, null);
    }

    private boolean isValueType(TypeMirror t) {
        if (t == null || t.getKind() != TypeKind.DECLARED) {
            return false; // primitives, arrays (which have identity), type variables, null
        }
        return isValueElement(((DeclaredType) t).asElement());
    }

    private static boolean isValueElement(Element e) {
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(VALUE_TYPE)) {
                return true;
            }
        }
        return false;
    }

    private final class Scanner extends TreePathScanner<Void, Void> {

        private final CompilationUnitTree unit;

        Scanner(CompilationUnitTree unit) {
            this.unit = unit;
        }

        private void report(Tree at, String message) {
            trees.printMessage(severity, message, at, unit);
        }

        private TypeMirror typeOf(Tree tree) {
            return trees.getTypeMirror(new TreePath(getCurrentPath(), tree));
        }

        private String nameOf(TypeMirror t) {
            return ((DeclaredType) t).asElement().getSimpleName().toString();
        }

        @Override
        public Void visitClass(ClassTree c, Void p) {
            Element element = trees.getElement(getCurrentPath());
            if (element != null && isValueElement(element)) {
                if (element.getKind() != ElementKind.RECORD) {
                    report(c, "@ValueType is for records: " + element.getSimpleName() + " is a " + element.getKind().toString().toLowerCase(java.util.Locale.ROOT));
                }
            }
            return super.visitClass(c, p);
        }

        @Override
        public Void visitMethod(MethodTree m, Void p) {
            Element owner = trees.getElement(getCurrentPath());
            if (owner != null && owner.getEnclosingElement() != null && isValueElement(owner.getEnclosingElement())) {
                if (owner.getModifiers().contains(Modifier.SYNCHRONIZED)) {
                    report(m, "synchronized method " + owner.getSimpleName() + " on the value type " + owner.getEnclosingElement().getSimpleName() + "; value objects cannot be locked");
                }
                if (m.getName().contentEquals("finalize") && m.getParameters().isEmpty()) {
                    report(m, "finalize() on the value type " + owner.getEnclosingElement().getSimpleName() + "; value objects cannot be finalized");
                }
            }
            return super.visitMethod(m, p);
        }

        @Override
        public Void visitBinary(BinaryTree b, Void p) {
            if ((b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO) && !isNull(b.getLeftOperand()) && !isNull(b.getRightOperand())) {
                TypeMirror left = typeOf(b.getLeftOperand()), right = typeOf(b.getRightOperand());
                TypeMirror value = isValueType(left) ? left : isValueType(right) ? right : null;
                if (value != null) {
                    report(b, "reference comparison of the value type " + nameOf(value) + "; use equals() or approxEquals()");
                }
            }
            return super.visitBinary(b, p);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree s, Void p) {
            TypeMirror t = typeOf(s.getExpression());
            if (isValueType(t)) {
                report(s, "synchronized on the value type " + nameOf(t) + "; value objects cannot be locked");
            }
            return super.visitSynchronized(s, p);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree m, Void p) {
            ExpressionTree select = m.getMethodSelect();
            if (select instanceof MemberSelectTree ms) {
                String name = ms.getIdentifier().toString();
                if (name.equals("identityHashCode") && m.getArguments().size() == 1 && isValueType(typeOf(m.getArguments().get(0)))) {
                    report(m, "identityHashCode of the value type " + nameOf(typeOf(m.getArguments().get(0))));
                }
                if ((name.equals("wait") || name.equals("notify") || name.equals("notifyAll")) && isValueType(typeOf(ms.getExpression()))) {
                    report(m, name + "() on the value type " + nameOf(typeOf(ms.getExpression())) + "; value objects have no monitor");
                }
            }
            return super.visitMethodInvocation(m, p);
        }

        @Override
        public Void visitNewClass(NewClassTree n, Void p) {
            TypeMirror created = trees.getTypeMirror(getCurrentPath());
            if (created != null && created.getKind() == TypeKind.DECLARED && IDENTITY_TYPES.contains(((TypeElement) ((DeclaredType) created).asElement()).getQualifiedName().toString())) {
                // the inferred type arguments cover the diamond as well as the explicit ones
                for (TypeMirror argument : ((DeclaredType) created).getTypeArguments()) {
                    if (isValueType(argument)) {
                        report(n, nameOf(created) + " of the value type " + nameOf(argument) + "; it relies on object identity");
                    }
                }
            }
            return super.visitNewClass(n, p);
        }

        private boolean isNull(ExpressionTree e) {
            return e instanceof LiteralTree l && l.getKind() == Tree.Kind.NULL_LITERAL;
        }
    }
}
