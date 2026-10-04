package io.github.jutil.immutability.internal.processor;

import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * javac 8 lowers/erases each class before analyzing the next one. Preserve the
 * attributed source view at ANALYZE, so proofs do not depend on file order or
 * require -XDcompilePolicy. Only public Tree interfaces are reflected; neither
 * user trees nor compiler state are changed. No javac implementation API is used.
 */
final class SourceSnapshots extends Trees {
    private final Trees delegate;
    private final Map<Tree, Node> nodes = new IdentityHashMap<Tree, Node>();
    private final Map<Element, TreePath> declarations = new LinkedHashMap<Element, TreePath>();

    SourceSnapshots(Trees delegate) {
        this.delegate = delegate;
    }

    void capture(TypeElement type) {
        TreePath original = delegate.getPath(type);
        if (original != null) {
            snapshot(original.getLeaf(), original.getParentPath(), original.getParentPath());
        }
    }

    private Tree snapshot(Tree original, TreePath originalParent, TreePath snapshotParent) {
        final Node node = new Node(original, new TreePath(originalParent, original));
        Class<? extends Tree> treeInterface = original.getKind().asInterface();
        if (treeInterface == null) { treeInterface = Tree.class; }
        List<Class<?>> interfaces = new ArrayList<Class<?>>();
        interfaces.add(treeInterface);
        // javac represents some type syntax as an expression (notably diamond
        // allocations), although its most specific public interface only extends Tree.
        if (original instanceof com.sun.source.tree.ExpressionTree
                && !com.sun.source.tree.ExpressionTree.class.isAssignableFrom(treeInterface)) {
            interfaces.add(com.sun.source.tree.ExpressionTree.class);
        }
        if (original instanceof com.sun.source.tree.StatementTree
                && !com.sun.source.tree.StatementTree.class.isAssignableFrom(treeInterface)) {
            interfaces.add(com.sun.source.tree.StatementTree.class);
        }
        Tree copy = (Tree) Proxy.newProxyInstance(Tree.class.getClassLoader(),
                interfaces.toArray(new Class<?>[interfaces.size()]), node);
        node.path = new TreePath(snapshotParent, copy);
        nodes.put(copy, node);
        node.element = delegate.getElement(node.originalPath);
        node.type = delegate.getTypeMirror(node.originalPath);
        if (node.element != null && (original instanceof ClassTree
                || original instanceof MethodTree || original instanceof com.sun.source.tree.VariableTree)) {
            declarations.put(node.element, node.path);
        }
        node.dispatch = visitorDispatch(original);
        for (Method getter : treeInterface.getMethods()) {
            if (getter.getParameterTypes().length == 0) {
                try {
                    node.properties.put(getter.getName(), copyProperty(getter.invoke(original), node));
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot snapshot public compiler tree getter", failure);
                }
            }
        }
        return copy;
    }

    private Object copyProperty(Object value, Node parent) {
        if (value instanceof Tree) {
            return snapshot((Tree) value, parent.originalPath, parent.path);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) { result.add(copyProperty(item, parent)); }
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    // The visitor proxy only records the public visitX method dispatched by accept.
    // Its erased generic return is always null, and no source value is cast/trusted.
    @SuppressWarnings("unchecked")
    private static Method visitorDispatch(Tree tree) {
        final Method[] dispatch = new Method[1];
        TreeVisitor<Object, Object> visitor = (TreeVisitor<Object, Object>) Proxy.newProxyInstance(
                Tree.class.getClassLoader(), new Class<?>[]{TreeVisitor.class}, new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
                        dispatch[0] = method;
                        return null;
                    }
                });
        tree.accept(visitor, null);
        return dispatch[0];
    }

    private static final class Node implements InvocationHandler {
        final Tree original;
        final TreePath originalPath;
        final String text;
        final Map<String, Object> properties = new LinkedHashMap<String, Object>();
        TreePath path;
        Element element;
        TypeMirror type;
        Method dispatch;

        Node(Tree original, TreePath originalPath) {
            this.original = original;
            this.originalPath = originalPath;
            this.text = original.toString();
        }

        @Override public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if ("accept".equals(name)) {
                try {
                    return dispatch.invoke(arguments[0], proxy, arguments[1]);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
            if ("toString".equals(name)) { return text; }
            if ("hashCode".equals(name)) { return System.identityHashCode(proxy); }
            if ("equals".equals(name)) { return proxy == arguments[0]; }
            return properties.get(name);
        }
    }

    private Tree original(Tree tree) {
        Node node = nodes.get(tree);
        return node == null ? tree : node.original;
    }
    private TreePath original(TreePath path) {
        Node node = nodes.get(path.getLeaf());
        return node == null ? path : node.originalPath;
    }
    @Override public SourcePositions getSourcePositions() {
        return new SourcePositions() {
            @Override public long getStartPosition(CompilationUnitTree unit, Tree tree) {
                return delegate.getSourcePositions().getStartPosition(unit, original(tree));
            }
            @Override public long getEndPosition(CompilationUnitTree unit, Tree tree) {
                return delegate.getSourcePositions().getEndPosition(unit, original(tree));
            }
        };
    }
    @Override public Tree getTree(Element element) {
        TreePath path = getPath(element);
        return path == null ? null : path.getLeaf();
    }
    @Override public ClassTree getTree(TypeElement element) { return (ClassTree) getTree((Element) element); }
    @Override public MethodTree getTree(ExecutableElement element) { return (MethodTree) getTree((Element) element); }
    @Override public Tree getTree(Element e, AnnotationMirror a) { return delegate.getTree(e, a); }
    @Override public Tree getTree(Element e, AnnotationMirror a, AnnotationValue v) { return delegate.getTree(e, a, v); }
    @Override public TreePath getPath(Element element) {
        TreePath known = declarations.get(element);
        return known == null ? delegate.getPath(element) : known;
    }
    @Override public TreePath getPath(CompilationUnitTree unit, Tree tree) {
        Node node = nodes.get(tree);
        return node == null ? delegate.getPath(unit, tree) : node.path;
    }
    @Override public TreePath getPath(Element e, AnnotationMirror a) { return delegate.getPath(e, a); }
    @Override public TreePath getPath(Element e, AnnotationMirror a, AnnotationValue v) { return delegate.getPath(e, a, v); }
    @Override public Element getElement(TreePath path) {
        Node node = nodes.get(path.getLeaf());
        return node == null ? delegate.getElement(path) : node.element;
    }
    @Override public TypeMirror getTypeMirror(TreePath path) {
        Node node = nodes.get(path.getLeaf());
        return node == null ? delegate.getTypeMirror(path) : node.type;
    }
    @Override public Scope getScope(TreePath path) { return delegate.getScope(original(path)); }
    @Override public String getDocComment(TreePath path) { return delegate.getDocComment(original(path)); }
    @Override public boolean isAccessible(Scope scope, TypeElement type) { return delegate.isAccessible(scope, type); }
    @Override public boolean isAccessible(Scope scope, Element member, DeclaredType type) { return delegate.isAccessible(scope, member, type); }
    @Override public TypeMirror getOriginalType(ErrorType type) { return delegate.getOriginalType(type); }
    @Override public void printMessage(Diagnostic.Kind kind, CharSequence text, Tree tree, CompilationUnitTree unit) {
        delegate.printMessage(kind, text, original(tree), unit);
    }
    @Override public TypeMirror getLub(CatchTree tree) { return delegate.getLub((CatchTree) original(tree)); }
}
