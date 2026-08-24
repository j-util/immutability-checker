package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import java.util.List;

final class DirectFieldWriteScanner extends TreePathScanner<Void, Void> {

    private enum Phase {
        INSTANCE_CONSTRUCTION,
        CLASS_INITIALIZATION,
        OTHER
    }

    private enum HelperCandidate {
        NONE,
        PRIVATE_INSTANCE_METHOD,
        PRIVATE_STATIC_METHOD
    }

    private final TypeElement rootType;
    private final String rootName;
    private final String incomingPath;
    private final Trees trees;
    private final List<ProofFailure> failures;

    private Phase phase = Phase.OTHER;
    private String executableContext = "type body";
    private HelperCandidate helperCandidate = HelperCandidate.NONE;

    DirectFieldWriteScanner(
            TypeElement rootType,
            String rootName,
            String incomingPath,
            Trees trees,
            List<ProofFailure> failures) {
        this.rootType = rootType;
        this.rootName = rootName;
        this.incomingPath = incomingPath;
        this.trees = trees;
        this.failures = failures;
    }

    void scanEnclosingType(TreePath rootPath) {
        TreePath outermostTypePath = rootPath;
        for (TreePath path = rootPath; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree) {
                outermostTypePath = path;
            }
        }
        scan(outermostTypePath, null);
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        if (rootType.equals(element)) {
            return super.visitClass(node, unused);
        }
        return withContext(Phase.OTHER, describeNestedType(element), HelperCandidate.NONE, new ScanAction() {
            @Override
            public Void scan() {
                return DirectFieldWriteScanner.super.visitClass(node, unused);
            }
        });
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        if (isRootField(element)) {
            if (node.getInitializer() == null) {
                return null;
            }
            final boolean staticField = element.getModifiers().contains(Modifier.STATIC);
            final String fieldContext = (staticField
                    ? "static field initializer "
                    : "field initializer ")
                    + rootType.getSimpleName() + "." + element.getSimpleName();
            return withContext(
                    staticField ? Phase.CLASS_INITIALIZATION : Phase.INSTANCE_CONSTRUCTION,
                    fieldContext,
                    HelperCandidate.NONE,
                    new ScanAction() {
                @Override
                public Void scan() {
                    return DirectFieldWriteScanner.this.scan(node.getInitializer(), unused);
                }
            });
        }
        return super.visitVariable(node, unused);
    }

    @Override
    public Void visitBlock(BlockTree node, Void unused) {
        TreePath parent = getCurrentPath().getParentPath();
        if (parent != null && parent.getLeaf() instanceof ClassTree
                && rootType.equals(trees.getElement(parent))) {
            Phase blockPhase = node.isStatic()
                    ? Phase.CLASS_INITIALIZATION
                    : Phase.INSTANCE_CONSTRUCTION;
            String description = node.isStatic()
                    ? "static initializer of " + rootType.getSimpleName()
                    : "instance initializer of " + rootType.getSimpleName();
            return withContext(blockPhase, description, HelperCandidate.NONE, new ScanAction() {
                @Override
                public Void scan() {
                    return DirectFieldWriteScanner.super.visitBlock(node, unused);
                }
            });
        }
        return super.visitBlock(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        final boolean rootConstructor = element instanceof ExecutableElement
                && element.getKind() == ElementKind.CONSTRUCTOR
                && rootType.equals(element.getEnclosingElement());
        final boolean privateMethod = element instanceof ExecutableElement
                && element.getKind() == ElementKind.METHOD
                && rootType.equals(element.getEnclosingElement())
                && element.getModifiers().contains(Modifier.PRIVATE);
        final HelperCandidate methodHelperCandidate;
        if (!privateMethod) {
            methodHelperCandidate = HelperCandidate.NONE;
        } else if (element.getModifiers().contains(Modifier.STATIC)) {
            methodHelperCandidate = HelperCandidate.PRIVATE_STATIC_METHOD;
        } else {
            methodHelperCandidate = HelperCandidate.PRIVATE_INSTANCE_METHOD;
        }
        return withContext(
                rootConstructor ? Phase.INSTANCE_CONSTRUCTION : Phase.OTHER,
                describeExecutable(element),
                methodHelperCandidate,
                new ScanAction() {
                    @Override
                    public Void scan() {
                        return DirectFieldWriteScanner.super.visitMethod(node, unused);
                    }
                });
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
        final String lambdaContext = "lambda in " + executableContext;
        return withContext(Phase.OTHER, lambdaContext, HelperCandidate.NONE, new ScanAction() {
            @Override
            public Void scan() {
                return DirectFieldWriteScanner.super.visitLambdaExpression(node, unused);
            }
        });
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void unused) {
        recordWrite(node.getVariable(), node);
        return super.visitAssignment(node, unused);
    }

    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
        recordWrite(node.getVariable(), node);
        return super.visitCompoundAssignment(node, unused);
    }

    @Override
    public Void visitUnary(UnaryTree node, Void unused) {
        switch (node.getKind()) {
            case PREFIX_INCREMENT:
            case POSTFIX_INCREMENT:
            case PREFIX_DECREMENT:
            case POSTFIX_DECREMENT:
                recordWrite(node.getExpression(), node);
                break;
            default:
                break;
        }
        return super.visitUnary(node, unused);
    }

    private void recordWrite(ExpressionTree target, Tree writeTree) {
        Element targetElement = trees.getElement(new TreePath(getCurrentPath(), target));
        if (!(targetElement instanceof VariableElement)
                || !isRootField(targetElement)) {
            return;
        }

        boolean staticField = targetElement.getModifiers().contains(Modifier.STATIC);
        boolean allowedWrite = staticField
                ? phase == Phase.CLASS_INITIALIZATION
                : phase == Phase.INSTANCE_CONSTRUCTION && isCurrentInstanceTarget(target);
        if (allowedWrite) {
            return;
        }

        String reason;
        if (staticField) {
            reason = "write in " + executableContext + " occurs after class initialization";
            if (helperCandidate == HelperCandidate.PRIVATE_STATIC_METHOD) {
                reason += "; static-initialization-only helper reachability is not analyzed "
                        + "in the current proof model";
            }
        } else if (phase == Phase.INSTANCE_CONSTRUCTION) {
            reason = "write in " + executableContext
                    + " uses a receiver not proven to be the object under construction; "
                    + "constructor receiver-alias analysis is not implemented";
        } else {
            reason = "write in " + executableContext + " occurs outside instance construction";
            if (helperCandidate != HelperCandidate.NONE) {
                reason += "; constructor-only helper reachability is not analyzed in the current proof model";
            }
        }

        failures.add(ProofFailure.create(
                DiagnosticId.POST_FREEZE_WRITE,
                rootName,
                fieldPath(targetElement),
                reason,
                writeTree,
                getCurrentPath().getCompilationUnit(),
                trees));
    }

    private String fieldPath(Element field) {
        String localPath = rootType.getSimpleName()
                + (field.getModifiers().contains(Modifier.STATIC) ? ".<static>." : ".")
                + field.getSimpleName();
        if (incomingPath.isEmpty()) {
            return localPath;
        }
        return incomingPath + " -> " + localPath;
    }

    private boolean isRootField(Element element) {
        return element instanceof VariableElement
                && element.getKind() == ElementKind.FIELD
                && rootType.equals(element.getEnclosingElement());
    }

    private boolean isCurrentInstanceTarget(ExpressionTree target) {
        ExpressionTree unwrapped = unwrap(target);
        if (unwrapped instanceof IdentifierTree) {
            return true;
        }
        if (!(unwrapped instanceof MemberSelectTree)) {
            return false;
        }
        ExpressionTree receiver = unwrap(((MemberSelectTree) unwrapped).getExpression());
        if (receiver instanceof IdentifierTree) {
            return ((IdentifierTree) receiver).getName().contentEquals("this");
        }
        if (!(receiver instanceof MemberSelectTree)) {
            return false;
        }

        MemberSelectTree qualifiedThis = (MemberSelectTree) receiver;
        if (!qualifiedThis.getIdentifier().contentEquals("this")) {
            return false;
        }
        TreePath qualifierPath = TreePath.getPath(
                getCurrentPath().getCompilationUnit(),
                unwrap(qualifiedThis.getExpression()));
        return qualifierPath != null && rootType.equals(trees.getElement(qualifierPath));
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree) {
            current = ((ParenthesizedTree) current).getExpression();
        }
        return current;
    }

    private String describeExecutable(Element element) {
        if (!(element instanceof ExecutableElement)) {
            return "unresolved executable";
        }
        ExecutableElement executable = (ExecutableElement) element;
        Element owner = executable.getEnclosingElement();
        String ownerName = owner == null ? rootType.getSimpleName().toString()
                : owner.getSimpleName().toString();
        if (executable.getKind() == ElementKind.CONSTRUCTOR) {
            return ownerName + "()";
        }
        return ownerName + "." + executable.getSimpleName() + "()";
    }

    private String describeNestedType(Element element) {
        if (element instanceof TypeElement) {
            return "nested type " + ((TypeElement) element).getSimpleName();
        }
        return "anonymous or local type";
    }

    private Void withContext(
            Phase nextPhase,
            String nextDescription,
            HelperCandidate nextHelperCandidate,
            ScanAction action) {
        Phase previousPhase = phase;
        String previousDescription = executableContext;
        HelperCandidate previousHelperCandidate = helperCandidate;
        phase = nextPhase;
        executableContext = nextDescription;
        helperCandidate = nextHelperCandidate;
        try {
            return action.scan();
        } finally {
            phase = previousPhase;
            executableContext = previousDescription;
            helperCandidate = previousHelperCandidate;
        }
    }

    private interface ScanAction {
        Void scan();
    }
}
