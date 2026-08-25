package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Proves that retained collection fields have one direct supported fresh origin. */
final class CollectionOriginAnalyzer extends TreePathScanner<Void, Void> {

    private enum Phase {
        INSTANCE_FIELD_INITIALIZER,
        INSTANCE_INITIALIZER,
        CONSTRUCTOR,
        STATIC_FIELD_INITIALIZER,
        STATIC_INITIALIZER,
        OTHER
    }

    private final TypeElement ownerType;
    private final String rootName;
    private final Trees trees;
    private final CollectionTypeModel typeModel;
    private final List<ProofFailure> failures;
    private final Map<VariableElement, CollectionProof> proofs;
    private final Map<CollectionProof, List<Candidate>> candidates =
            new LinkedHashMap<CollectionProof, List<Candidate>>();
    private final Set<Element> uncheckedCollectionAliases = new LinkedHashSet<Element>();

    private Phase phase = Phase.OTHER;
    private ExecutableElement executable;
    private int conditionalDepth;

    CollectionOriginAnalyzer(
            TypeElement ownerType,
            String rootName,
            Trees trees,
            CollectionTypeModel typeModel,
            List<CollectionProof> collectionProofs,
            List<ProofFailure> failures) {
        this.ownerType = ownerType;
        this.rootName = rootName;
        this.trees = trees;
        this.typeModel = typeModel;
        this.failures = failures;
        this.proofs = new LinkedHashMap<VariableElement, CollectionProof>();
        for (CollectionProof proof : collectionProofs) {
            proofs.put(proof.getField(), proof);
            candidates.put(proof, new ArrayList<Candidate>());
        }
    }

    void analyze(TreePath ownerPath) {
        scan(ownerPath, null);
        finish();
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        if (ownerType.equals(element)) {
            return super.visitClass(node, unused);
        }
        return null;
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        if (node.getInitializer() == null) {
            recordGenericAliasFlow(element, null);
        }
        if (isOwnerField(element) && node.getInitializer() != null) {
            final Phase initializerPhase = element.getModifiers().contains(Modifier.STATIC)
                    ? Phase.STATIC_FIELD_INITIALIZER
                    : Phase.INSTANCE_FIELD_INITIALIZER;
            final CollectionProof proof = proofs.get(element);
            Void result = withContext(initializerPhase, null, new ScanAction() {
                @Override
                public Void scan() {
                    if (proof != null) {
                        recordCandidate(proof, node.getInitializer(), node.getInitializer());
                    }
                    return CollectionOriginAnalyzer.this.scan(node.getInitializer(), unused);
                }
            });
            recordGenericAliasFlow(element, node.getInitializer());
            return result;
        }
        Void result = super.visitVariable(node, unused);
        if (node.getInitializer() != null) {
            recordGenericAliasFlow(element, node.getInitializer());
        }
        return result;
    }

    @Override
    public Void visitBlock(BlockTree node, Void unused) {
        TreePath parent = getCurrentPath().getParentPath();
        if (parent != null && parent.getLeaf() instanceof ClassTree
                && ownerType.equals(trees.getElement(parent))) {
            final Phase initializerPhase = node.isStatic()
                    ? Phase.STATIC_INITIALIZER
                    : Phase.INSTANCE_INITIALIZER;
            return withContext(initializerPhase, null, new ScanAction() {
                @Override
                public Void scan() {
                    return CollectionOriginAnalyzer.super.visitBlock(node, unused);
                }
            });
        }
        return super.visitBlock(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        final ExecutableElement method = element instanceof ExecutableElement
                ? (ExecutableElement) element : null;
        final boolean constructor = method != null
                && method.getKind() == ElementKind.CONSTRUCTOR
                && ownerType.equals(method.getEnclosingElement());
        return withContext(constructor ? Phase.CONSTRUCTOR : Phase.OTHER,
                constructor ? method : null, new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitMethod(node, unused);
            }
        });
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
        return withContext(Phase.OTHER, null, new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitLambdaExpression(node, unused);
            }
        });
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void unused) {
        Element target = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        CollectionProof proof = proofs.get(target);
        if (proof != null && isApplicableInitialization(proof, node.getVariable())) {
            recordCandidate(proof, node.getExpression(), node);
        }
        recordGenericAliasFlow(target, node.getExpression());
        return super.visitAssignment(node, unused);
    }

    @Override
    public Void visitIf(IfTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitIf(node, unused);
            }
        });
    }

    @Override
    public Void visitConditionalExpression(ConditionalExpressionTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitConditionalExpression(node, unused);
            }
        });
    }

    @Override
    public Void visitSwitch(SwitchTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitSwitch(node, unused);
            }
        });
    }

    @Override
    public Void visitForLoop(ForLoopTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitForLoop(node, unused);
            }
        });
    }

    @Override
    public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitEnhancedForLoop(node, unused);
            }
        });
    }

    @Override
    public Void visitWhileLoop(WhileLoopTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitWhileLoop(node, unused);
            }
        });
    }

    @Override
    public Void visitDoWhileLoop(DoWhileLoopTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitDoWhileLoop(node, unused);
            }
        });
    }

    @Override
    public Void visitTry(TryTree node, Void unused) {
        return withConditional(new ScanAction() {
            @Override
            public Void scan() {
                return CollectionOriginAnalyzer.super.visitTry(node, unused);
            }
        });
    }

    private void recordCandidate(CollectionProof proof, ExpressionTree expression, Tree diagnosticTree) {
        if (conditionalDepth > 0) {
            candidates.get(proof).add(Candidate.invalid(
                    diagnosticTree,
                    "collection allocation is conditional; one fresh ownership origin cannot be proven on every path"));
            return;
        }

        ExpressionTree unwrapped = unwrap(expression);
        if (unwrapped instanceof ConditionalExpressionTree) {
            candidates.get(proof).add(Candidate.invalid(
                    diagnosticTree,
                    "collection allocation is conditional; one fresh ownership origin cannot be proven on every path"));
            return;
        }
        if (unwrapped instanceof NewClassTree) {
            NewClassTree allocation = (NewClassTree) unwrapped;
            TreePath allocationPath = TreePath.getPath(
                    getCurrentPath().getCompilationUnit(), allocation);
            Element constructor = allocationPath == null ? null : trees.getElement(allocationPath);
            TypeElement implementation = constructor instanceof ExecutableElement
                    && constructor.getEnclosingElement() instanceof TypeElement
                    ? (TypeElement) constructor.getEnclosingElement()
                    : null;
            if (allocation.getClassBody() != null) {
                candidates.get(proof).add(Candidate.invalid(
                        diagnosticTree,
                        "anonymous or custom collection subclasses are not supported ownership origins"));
            } else if (!typeModel.isSupportedImplementation(implementation)) {
                String implementationName = implementation == null
                        ? String.valueOf(allocation.getIdentifier())
                        : implementation.getQualifiedName().toString();
                candidates.get(proof).add(Candidate.invalid(
                        diagnosticTree,
                        implementationName + " is not one of the supported exact fresh collection implementations"));
            } else {
                ExecutableElement allocationConstructor = constructor instanceof ExecutableElement
                        ? (ExecutableElement) constructor : null;
                int copySourceIndex = typeModel.copySourceArgumentIndex(allocationConstructor);
                if (copySourceIndex >= 0
                        && !hasExactCollectionContract(
                        allocation.getArguments().get(copySourceIndex), proof)) {
                    candidates.get(proof).add(Candidate.invalid(
                            diagnosticTree,
                            "copy-constructor source loses the retained collection's exact generic element/key/value contract"));
                } else {
                    candidates.get(proof).add(Candidate.valid(
                            context(), sourcePosition(diagnosticTree), executable, diagnosticTree));
                }
            }
            return;
        }

        TreePath expressionPath = TreePath.getPath(
                getCurrentPath().getCompilationUnit(), unwrapped);
        Element origin = expressionPath == null ? null : trees.getElement(expressionPath);
        String reason;
        if (origin != null && origin.getKind() == ElementKind.PARAMETER) {
            reason = "constructor or initializer parameter is retained directly; external mutable container alias remains";
        } else if (origin != null && origin.getKind() == ElementKind.FIELD) {
            reason = "field-to-field collection aliasing does not establish exclusive container ownership";
        } else if (unwrapped instanceof MethodInvocationTree) {
            reason = "helper and factory method return values are not supported collection ownership origins";
        } else {
            reason = "retained value does not come directly from a supported fresh collection allocation";
        }
        candidates.get(proof).add(Candidate.invalid(diagnosticTree, reason));
    }

    private void finish() {
        int constructorCount = constructorCount();
        for (Map.Entry<CollectionProof, List<Candidate>> entry : candidates.entrySet()) {
            CollectionProof proof = entry.getKey();
            List<Candidate> fieldCandidates = entry.getValue();
            Candidate invalid = firstInvalid(fieldCandidates);
            if (invalid != null) {
                addFailure(proof, invalid.tree, invalid.reason);
                continue;
            }
            if (fieldCandidates.isEmpty()) {
                addFailure(proof, proof.getFieldTree(),
                        "no direct supported fresh collection allocation establishes exclusive container ownership");
                continue;
            }
            if (fieldCandidates.size() != 1) {
                addFailure(proof, fieldCandidates.get(1).tree,
                        "multiple competing collection ownership origins cannot be proven exclusive");
                continue;
            }
            Candidate candidate = fieldCandidates.get(0);
            if (candidate.context == CollectionProof.OriginContext.CONSTRUCTOR
                    && constructorCount != 1) {
                addFailure(proof, candidate.tree,
                        "constructor-path ownership is not proven across every constructor");
                continue;
            }
            proof.setOrigin(new CollectionProof.Origin(
                    candidate.context, candidate.position, candidate.executable));
        }
    }

    private Candidate firstInvalid(List<Candidate> fieldCandidates) {
        for (Candidate candidate : fieldCandidates) {
            if (candidate.reason != null) {
                return candidate;
            }
        }
        return null;
    }

    private int constructorCount() {
        int count = 0;
        for (Element element : ownerType.getEnclosedElements()) {
            if (element.getKind() == ElementKind.CONSTRUCTOR) {
                count++;
            }
        }
        return count;
    }

    private boolean isApplicableInitialization(CollectionProof proof, ExpressionTree target) {
        if (proof.isStaticState()) {
            return phase == Phase.STATIC_FIELD_INITIALIZER || phase == Phase.STATIC_INITIALIZER;
        }
        return (phase == Phase.INSTANCE_FIELD_INITIALIZER
                || phase == Phase.INSTANCE_INITIALIZER
                || phase == Phase.CONSTRUCTOR) && isCurrentInstanceTarget(target);
    }

    private boolean isCurrentInstanceTarget(ExpressionTree target) {
        ExpressionTree unwrapped = unwrap(target);
        if (!(unwrapped instanceof com.sun.source.tree.MemberSelectTree)) {
            return true;
        }
        ExpressionTree receiver = unwrap(
                ((com.sun.source.tree.MemberSelectTree) unwrapped).getExpression());
        if (receiver instanceof com.sun.source.tree.IdentifierTree) {
            return ((com.sun.source.tree.IdentifierTree) receiver).getName().contentEquals("this");
        }
        if (!(receiver instanceof com.sun.source.tree.MemberSelectTree)) {
            return false;
        }
        com.sun.source.tree.MemberSelectTree qualifiedThis =
                (com.sun.source.tree.MemberSelectTree) receiver;
        if (!qualifiedThis.getIdentifier().contentEquals("this")) {
            return false;
        }
        TreePath qualifierPath = TreePath.getPath(
                getCurrentPath().getCompilationUnit(), qualifiedThis.getExpression());
        return qualifierPath != null && ownerType.equals(trees.getElement(qualifierPath));
    }

    private boolean isOwnerField(Element element) {
        return element instanceof VariableElement
                && element.getKind() == ElementKind.FIELD
                && ownerType.equals(element.getEnclosingElement());
    }

    private CollectionProof.OriginContext context() {
        switch (phase) {
            case INSTANCE_FIELD_INITIALIZER:
                return CollectionProof.OriginContext.INSTANCE_FIELD_INITIALIZER;
            case INSTANCE_INITIALIZER:
                return CollectionProof.OriginContext.INSTANCE_INITIALIZER;
            case CONSTRUCTOR:
                return CollectionProof.OriginContext.CONSTRUCTOR;
            case STATIC_FIELD_INITIALIZER:
                return CollectionProof.OriginContext.STATIC_FIELD_INITIALIZER;
            case STATIC_INITIALIZER:
                return CollectionProof.OriginContext.STATIC_INITIALIZER;
            default:
                throw new IllegalStateException("Not in a collection initialization context");
        }
    }

    private long sourcePosition(Tree tree) {
        long position = trees.getSourcePositions().getStartPosition(
                getCurrentPath().getCompilationUnit(), tree);
        return position < 0 ? Long.MAX_VALUE : position;
    }

    private boolean hasExactCollectionContract(
            ExpressionTree expression,
            CollectionProof proof) {
        TypeMirror expressionType = typeOf(expression);
        if (isModernSwitchFlow(expression)
                || referencesUncheckedCollectionAlias(expression)
                || !typeModel.hasExactRoleContract(expressionType, proof)) {
            return false;
        }
        ExpressionTree unwrapped = expression;
        while (unwrapped instanceof ParenthesizedTree) {
            unwrapped = ((ParenthesizedTree) unwrapped).getExpression();
        }
        if (unwrapped instanceof TypeCastTree) {
            return hasExactCollectionContract(
                    ((TypeCastTree) unwrapped).getExpression(), proof);
        }
        return true;
    }

    private void recordGenericAliasFlow(Element target, ExpressionTree source) {
        if (!(target instanceof VariableElement)
                || !typeModel.isCollectionLike(((VariableElement) target).asType())) {
            return;
        }
        TypeMirror targetType = ((VariableElement) target).asType();
        boolean proven = source == null
                ? typeModel.hasCompleteRoleContract(targetType)
                : hasExactGenericFlow(source, targetType)
                && !referencesUncheckedCollectionAlias(source);
        if (!proven) {
            uncheckedCollectionAliases.add(target);
        }
    }

    private boolean hasExactGenericFlow(
            ExpressionTree expression,
            TypeMirror retainedContract) {
        if (expression == null || isModernSwitchFlow(expression)) {
            return false;
        }
        TypeMirror expressionType = typeOf(expression);
        if (expressionType != null
                && expressionType.getKind() == javax.lang.model.type.TypeKind.NULL) {
            return true;
        }
        if (!typeModel.hasExactRoleContract(expressionType, retainedContract)) {
            return false;
        }
        ExpressionTree unwrapped = expression;
        while (unwrapped instanceof ParenthesizedTree) {
            unwrapped = ((ParenthesizedTree) unwrapped).getExpression();
        }
        if (unwrapped instanceof TypeCastTree) {
            return hasExactGenericFlow(
                    ((TypeCastTree) unwrapped).getExpression(), retainedContract);
        }
        if (unwrapped instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) unwrapped;
            return hasExactGenericFlow(conditional.getTrueExpression(), retainedContract)
                    && hasExactGenericFlow(conditional.getFalseExpression(), retainedContract);
        }
        return true;
    }

    private boolean referencesUncheckedCollectionAlias(ExpressionTree expression) {
        ExpressionTree unwrapped = expression;
        while (unwrapped instanceof ParenthesizedTree || unwrapped instanceof TypeCastTree) {
            unwrapped = unwrapped instanceof ParenthesizedTree
                    ? ((ParenthesizedTree) unwrapped).getExpression()
                    : ((TypeCastTree) unwrapped).getExpression();
        }
        if (unwrapped instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) unwrapped;
            return referencesUncheckedCollectionAlias(conditional.getTrueExpression())
                    || referencesUncheckedCollectionAlias(conditional.getFalseExpression());
        }
        TreePath path = TreePath.getPath(getCurrentPath().getCompilationUnit(), unwrapped);
        return path != null && uncheckedCollectionAliases.contains(trees.getElement(path));
    }

    private boolean isModernSwitchFlow(Tree tree) {
        final boolean[] found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree candidate, Void unused) {
                if (candidate == null || found[0]) {
                    return null;
                }
                String kind = candidate.getKind().name();
                if ("SWITCH_EXPRESSION".equals(kind) || "YIELD".equals(kind)) {
                    found[0] = true;
                    return null;
                }
                return super.scan(candidate, unused);
            }
        }.scan(tree, null);
        return found[0];
    }

    private TypeMirror typeOf(ExpressionTree expression) {
        TreePath path = TreePath.getPath(
                getCurrentPath().getCompilationUnit(), expression);
        return path == null ? null : trees.getTypeMirror(path);
    }

    private void addFailure(CollectionProof proof, Tree tree, String reason) {
        failures.add(ProofFailure.create(
                DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                rootName,
                proof.getPath(),
                proof.getField().asType() + " -> " + reason,
                tree,
                proof.getCompilationUnit(),
                trees));
    }

    private Void withContext(Phase nextPhase, ExecutableElement nextExecutable, ScanAction action) {
        Phase previousPhase = phase;
        ExecutableElement previousExecutable = executable;
        phase = nextPhase;
        executable = nextExecutable;
        try {
            return action.scan();
        } finally {
            phase = previousPhase;
            executable = previousExecutable;
        }
    }

    private Void withConditional(ScanAction action) {
        conditionalDepth++;
        try {
            return action.scan();
        } finally {
            conditionalDepth--;
        }
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree current = expression;
        while (current instanceof ParenthesizedTree) {
            current = ((ParenthesizedTree) current).getExpression();
        }
        return current;
    }

    private interface ScanAction {
        Void scan();
    }

    private static final class Candidate {
        private final CollectionProof.OriginContext context;
        private final long position;
        private final ExecutableElement executable;
        private final Tree tree;
        private final String reason;

        private Candidate(
                CollectionProof.OriginContext context,
                long position,
                ExecutableElement executable,
                Tree tree,
                String reason) {
            this.context = context;
            this.position = position;
            this.executable = executable;
            this.tree = tree;
            this.reason = reason;
        }

        private static Candidate valid(
                CollectionProof.OriginContext context,
                long position,
                ExecutableElement executable,
                Tree tree) {
            return new Candidate(context, position, executable, tree, null);
        }

        private static Candidate invalid(Tree tree, String reason) {
            return new Candidate(null, Long.MAX_VALUE, null, tree, reason);
        }
    }
}
