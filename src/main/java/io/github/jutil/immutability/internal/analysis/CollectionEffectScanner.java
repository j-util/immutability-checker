package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks collection operations, simple local aliases, and container escapes. */
final class CollectionEffectScanner extends TreePathScanner<Void, Void> {

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

    private Map<Element, Set<CollectionProof>> aliases =
            new LinkedHashMap<Element, Set<CollectionProof>>();
    private final Set<Element> uncheckedCollectionAliases = new LinkedHashSet<Element>();
    private Phase phase = Phase.OTHER;
    private ExecutableElement executable;
    private String executableContext = "type body";

    CollectionEffectScanner(
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
        }
    }

    void scanEnclosingType(TreePath ownerPath) {
        TreePath outermostTypePath = ownerPath;
        for (TreePath path = ownerPath; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree) {
                outermostTypePath = path;
            }
        }
        scan(outermostTypePath, null);
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        if (ownerType.equals(element)) {
            return super.visitClass(node, unused);
        }
        return withExecutableContext(Phase.OTHER, null, describeNestedType(element), new ScanAction() {
            @Override
            public Void scan() {
                return CollectionEffectScanner.super.visitClass(node, unused);
            }
        });
    }

    @Override
    public Void visitVariable(VariableTree node, Void unused) {
        Element element = trees.getElement(getCurrentPath());
        if (node.getInitializer() == null) {
            recordGenericAliasFlow(element, null);
        }
        if (isOwnerField(element) && node.getInitializer() != null) {
            final boolean staticField = element.getModifiers().contains(Modifier.STATIC);
            final Phase initializerPhase = staticField
                    ? Phase.STATIC_FIELD_INITIALIZER : Phase.INSTANCE_FIELD_INITIALIZER;
            final String description = (staticField ? "static field initializer " : "field initializer ")
                    + ownerType.getSimpleName() + "." + element.getSimpleName();
            Void result = withExecutableContext(initializerPhase, null, description, new ScanAction() {
                @Override
                public Void scan() {
                    return CollectionEffectScanner.this.scan(node.getInitializer(), unused);
                }
            });
            recordGenericAliasFlow(element, node.getInitializer());
            recordVariableFlow(element, node.getInitializer(), node);
            return result;
        }

        Void result = super.visitVariable(node, unused);
        if (element != null && node.getInitializer() != null) {
            recordGenericAliasFlow(element, node.getInitializer());
            recordVariableFlow(element, node.getInitializer(), node);
        }
        return result;
    }

    @Override
    public Void visitBlock(BlockTree node, Void unused) {
        TreePath parent = getCurrentPath().getParentPath();
        if (parent != null && parent.getLeaf() instanceof ClassTree
                && ownerType.equals(trees.getElement(parent))) {
            final Phase initializerPhase = node.isStatic()
                    ? Phase.STATIC_INITIALIZER : Phase.INSTANCE_INITIALIZER;
            final String description = node.isStatic()
                    ? "static initializer of " + ownerType.getSimpleName()
                    : "instance initializer of " + ownerType.getSimpleName();
            return withExecutableContext(initializerPhase, null, description, new ScanAction() {
                @Override
                public Void scan() {
                    return CollectionEffectScanner.super.visitBlock(node, unused);
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
        return withExecutableContext(
                constructor ? Phase.CONSTRUCTOR : Phase.OTHER,
                constructor ? method : null,
                describeExecutable(method),
                new ScanAction() {
                    @Override
                    public Void scan() {
                        return CollectionEffectScanner.super.visitMethod(node, unused);
                    }
                });
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
        final String description = "lambda in " + executableContext;
        if (node.getBody() instanceof ExpressionTree) {
            for (CollectionProof returned : referencedProofs((ExpressionTree) node.getBody())) {
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        returned,
                        node,
                        "retained mutable collection escapes through a callback result in " + description);
            }
        }
        return withExecutableContext(Phase.OTHER, null, description, new ScanAction() {
            @Override
            public Void scan() {
                return CollectionEffectScanner.super.visitLambdaExpression(node, unused);
            }
        });
    }

    @Override
    public Void visitAssignment(AssignmentTree node, Void unused) {
        Set<CollectionProof> referenced = referencedProofs(node.getExpression());
        Element target = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        recordGenericAliasFlow(target, node.getExpression());
        if (isLocalAliasTarget(target)) {
            if (referenced.isEmpty()) {
                aliases.remove(target);
            } else {
                recordAlias(target, node.getExpression(), referenced, node);
                aliases.put(target, copyProofs(referenced));
            }
        } else {
            for (CollectionProof proof : referenced) {
                if (proofs.get(target) == proof) {
                    continue;
                }
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        proof,
                        node,
                        "collection alias is assigned into unrelated field, static state, or array storage");
            }
        }
        return super.visitAssignment(node, unused);
    }

    @Override
    public Void visitReturn(ReturnTree node, Void unused) {
        if (node.getExpression() != null) {
            for (CollectionProof proof : referencedProofs(node.getExpression())) {
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        proof,
                        node,
                        "retained mutable collection escapes through return from " + executableContext);
            }
        }
        return super.visitReturn(node, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        ExecutableElement method = executableElement();
        ExpressionTree receiver = receiverExpression(node.getMethodSelect());
        Set<CollectionProof> receiverProofs = receiver == null
                ? new LinkedHashSet<CollectionProof>() : referencedProofs(receiver);
        CollectionTypeModel.Operation operation = receiverProofs.isEmpty()
                ? CollectionTypeModel.Operation.UNKNOWN : typeModel.operation(method);

        for (CollectionProof receiverProof : receiverProofs) {
            if (!hasExactCollectionContract(receiver, receiverProof)) {
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        receiverProof,
                        node,
                        contractFailureReason(receiver,
                                typeModel.signature(method) + " receiver"));
            }
            switch (operation) {
                case READ:
                    break;
                case MUTATOR:
                    validateMutationArguments(receiverProof, method, node);
                    recordMutation(receiverProof, method, node);
                    break;
                case CALLBACK_MUTATOR:
                    addFailure(
                            DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                            receiverProof,
                            node,
                            typeModel.signature(method)
                                    + " is callback-based; callback effects and escape behavior are not modeled");
                    break;
                case VIEW_OR_ITERATOR:
                    addFailure(
                            DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                            receiverProof,
                            node,
                            typeModel.signature(method)
                                    + " creates an iterator, stream, spliterator, or aliasing view of retained state");
                    break;
                case UNKNOWN:
                default:
                    addFailure(
                            DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                            receiverProof,
                            node,
                            typeModel.signature(method)
                                    + " is not an explicitly modeled collection operation; effects are unproven");
                    break;
            }
        }

        Set<CollectionProof> escapedArguments = new LinkedHashSet<CollectionProof>();
        for (ExpressionTree argument : node.getArguments()) {
            Set<CollectionProof> argumentProofs = referencedProofs(argument);
            boolean safelyModeledCollectionArgument = !receiverProofs.isEmpty()
                    && (operation == CollectionTypeModel.Operation.READ
                    || operation == CollectionTypeModel.Operation.MUTATOR);
            if (!safelyModeledCollectionArgument) {
                for (CollectionProof argumentProof : argumentProofs) {
                    if (!receiverProofs.contains(argumentProof)) {
                        escapedArguments.add(argumentProof);
                    }
                }
            }
        }
        for (CollectionProof escaped : escapedArguments) {
            addFailure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    escaped,
                    node,
                    "retained mutable collection is passed to " + typeModel.signature(method)
                            + "; callee mutation and retention effects are unproven");
        }
        return super.visitMethodInvocation(node, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
        Element constructor = trees.getElement(getCurrentPath());
        TypeElement implementation = constructor instanceof ExecutableElement
                && constructor.getEnclosingElement() instanceof TypeElement
                ? (TypeElement) constructor.getEnclosingElement()
                : null;
        if (node.getClassBody() == null && typeModel.isSupportedImplementation(implementation)) {
            return super.visitNewClass(node, unused);
        }
        Set<CollectionProof> escapedArguments = new LinkedHashSet<CollectionProof>();
        for (ExpressionTree argument : node.getArguments()) {
            escapedArguments.addAll(referencedProofs(argument));
        }
        for (CollectionProof proof : escapedArguments) {
            addFailure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    proof,
                    node,
                    "retained mutable collection is passed to an unmodeled constructor and may escape");
        }
        return super.visitNewClass(node, unused);
    }

    @Override
    public Void visitNewArray(NewArrayTree node, Void unused) {
        if (node.getInitializers() != null) {
            Set<CollectionProof> stored = new LinkedHashSet<CollectionProof>();
            for (ExpressionTree initializer : node.getInitializers()) {
                stored.addAll(referencedProofs(initializer));
            }
            for (CollectionProof proof : stored) {
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        proof,
                        node,
                        "retained mutable collection alias is stored into an array");
            }
        }
        return super.visitNewArray(node, unused);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree node, Void unused) {
        for (CollectionProof proof : referencedProofs(node.getQualifierExpression())) {
            addFailure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    proof,
                    node,
                    "method reference captures the retained mutable collection; deferred effects are unproven");
        }
        return super.visitMemberReference(node, unused);
    }

    private void recordMutation(
            CollectionProof proof,
            ExecutableElement method,
            MethodInvocationTree invocation) {
        if (isAllowedInitializationMutation(proof, invocation)) {
            return;
        }
        if (isApplicableInitializationPhase(proof)) {
            addFailure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    proof,
                    invocation,
                    typeModel.signature(method) + " in " + executableContext
                            + " occurs before exclusive fresh collection ownership is established");
            return;
        }
        String boundary = proof.isStaticState()
                ? "after class initialization" : "after instance construction";
        addFailure(
                proof.getMutationDiagnostic(),
                proof,
                invocation,
                typeModel.signature(method) + " in " + executableContext
                        + " structurally mutates retained collection state " + boundary);
    }

    private boolean isAllowedInitializationMutation(
            CollectionProof proof,
            MethodInvocationTree invocation) {
        CollectionProof.Origin origin = proof.getOrigin();
        if (origin == null) {
            return false;
        }
        if (!isApplicableInitializationPhase(proof)) {
            return false;
        }

        if (phase == Phase.CONSTRUCTOR
                && (origin.getContext() == CollectionProof.OriginContext.INSTANCE_FIELD_INITIALIZER
                || origin.getContext() == CollectionProof.OriginContext.INSTANCE_INITIALIZER)) {
            return true;
        }
        if (origin.getContext() == CollectionProof.OriginContext.CONSTRUCTOR
                && (phase != Phase.CONSTRUCTOR || !origin.getExecutable().equals(executable))) {
            return false;
        }
        return origin.getPosition() < sourcePosition(invocation);
    }

    private boolean isApplicableInitializationPhase(CollectionProof proof) {
        if (proof.isStaticState()) {
            return phase == Phase.STATIC_FIELD_INITIALIZER || phase == Phase.STATIC_INITIALIZER;
        }
        return phase == Phase.INSTANCE_FIELD_INITIALIZER
                || phase == Phase.INSTANCE_INITIALIZER
                || phase == Phase.CONSTRUCTOR;
    }

    private ExpressionTree receiverExpression(ExpressionTree methodSelect) {
        if (!(methodSelect instanceof MemberSelectTree)) {
            return null;
        }
        return ((MemberSelectTree) methodSelect).getExpression();
    }

    private Set<CollectionProof> referencedProofs(ExpressionTree expression) {
        LinkedHashSet<CollectionProof> result = new LinkedHashSet<CollectionProof>();
        ExpressionTree unwrapped = unwrap(expression);
        if (unwrapped instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) unwrapped;
            result.addAll(referencedProofs(conditional.getTrueExpression()));
            result.addAll(referencedProofs(conditional.getFalseExpression()));
            return result;
        }
        if (isModernSwitchExpression(unwrapped)) {
            return collectNestedProofs(unwrapped);
        }
        TreePath path = TreePath.getPath(getCurrentPath().getCompilationUnit(), unwrapped);
        if (path == null) {
            return result;
        }
        Element element = trees.getElement(path);
        CollectionProof proof = element instanceof VariableElement
                ? proofs.get(element) : null;
        if (proof != null) {
            result.add(proof);
        } else {
            Set<CollectionProof> aliased = aliases.get(element);
            if (aliased != null) {
                result.addAll(aliased);
            }
        }
        return result;
    }

    private Set<CollectionProof> collectNestedProofs(ExpressionTree expression) {
        final LinkedHashSet<CollectionProof> result = new LinkedHashSet<CollectionProof>();
        TreePath expressionPath = TreePath.getPath(
                getCurrentPath().getCompilationUnit(), expression);
        if (expressionPath == null) {
            return result;
        }
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                addElementProofs(trees.getElement(getCurrentPath()), result);
                return super.visitIdentifier(node, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                addElementProofs(trees.getElement(getCurrentPath()), result);
                return super.visitMemberSelect(node, unused);
            }
        }.scan(expressionPath, null);
        return result;
    }

    private void addElementProofs(Element element, Set<CollectionProof> target) {
        CollectionProof proof = element instanceof VariableElement
                ? proofs.get(element) : null;
        if (proof != null) {
            target.add(proof);
            return;
        }
        Set<CollectionProof> aliased = aliases.get(element);
        if (aliased != null) {
            target.addAll(aliased);
        }
    }

    private void recordVariableFlow(
            Element target,
            ExpressionTree initializer,
            Tree diagnosticTree) {
        Set<CollectionProof> referenced = referencedProofs(initializer);
        if (referenced.isEmpty()) {
            return;
        }
        if (isLocalAliasTarget(target)) {
            recordAlias(target, initializer, referenced, diagnosticTree);
            aliases.put(target, copyProofs(referenced));
            return;
        }
        for (CollectionProof proof : referenced) {
            if (proofs.get(target) == proof) {
                continue;
            }
            addFailure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    proof,
                    diagnosticTree,
                    "retained mutable collection alias is stored by a field declaration");
        }
    }

    private void recordAlias(
            Element target,
            ExpressionTree source,
            Set<CollectionProof> referenced,
            Tree diagnosticTree) {
        for (CollectionProof proof : referenced) {
            if (!(target instanceof VariableElement)
                    || !typeModel.hasExactRoleContract(
                    ((VariableElement) target).asType(), proof)
                    || !hasExactCollectionContract(source, proof)) {
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        proof,
                        diagnosticTree,
                        contractFailureReason(source, "collection alias"));
            }
        }
    }

    private boolean hasExactCollectionContract(
            ExpressionTree expression,
            CollectionProof proof) {
        if (expression == null || isModernSwitchFlow(expression)
                || referencesUncheckedCollectionAlias(expression)
                || !typeModel.hasExactRoleContract(typeOf(expression), proof)) {
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
        if (unwrapped instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) unwrapped;
            return branchContractProven(conditional.getTrueExpression(), proof)
                    && branchContractProven(conditional.getFalseExpression(), proof);
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
        if (expressionType != null && expressionType.getKind() == javax.lang.model.type.TypeKind.NULL) {
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
        ExpressionTree unwrapped = unwrap(expression);
        if (unwrapped instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) unwrapped;
            return referencesUncheckedCollectionAlias(conditional.getTrueExpression())
                    || referencesUncheckedCollectionAlias(conditional.getFalseExpression());
        }
        if (isModernSwitchExpression(unwrapped)) {
            final boolean[] found = new boolean[1];
            TreePath expressionPath = TreePath.getPath(
                    getCurrentPath().getCompilationUnit(), unwrapped);
            if (expressionPath != null) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitIdentifier(IdentifierTree node, Void unused) {
                        if (uncheckedCollectionAliases.contains(
                                trees.getElement(getCurrentPath()))) {
                            found[0] = true;
                        }
                        return found[0] ? null : super.visitIdentifier(node, unused);
                    }

                    @Override
                    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                        if (uncheckedCollectionAliases.contains(
                                trees.getElement(getCurrentPath()))) {
                            found[0] = true;
                        }
                        return found[0] ? null : super.visitMemberSelect(node, unused);
                    }
                }.scan(expressionPath, null);
            }
            return found[0];
        }
        TreePath path = TreePath.getPath(getCurrentPath().getCompilationUnit(), unwrapped);
        return path != null && uncheckedCollectionAliases.contains(trees.getElement(path));
    }

    private boolean branchContractProven(
            ExpressionTree expression,
            CollectionProof proof) {
        return !referencedProofs(expression).contains(proof)
                || hasExactCollectionContract(expression, proof);
    }

    private String contractFailureReason(ExpressionTree expression, String subject) {
        if (isModernSwitchFlow(expression)) {
            return subject + " uses a switch-expression collection flow whose possible results "
                    + "cannot be proven by the Java 8-compatible analysis";
        }
        return subject + " loses the retained collection's exact generic element/key/value contract "
                + "through a raw or unchecked type";
    }

    private void validateMutationArguments(
            CollectionProof proof,
            ExecutableElement method,
            MethodInvocationTree invocation) {
        List<CollectionTypeModel.MutationArgumentRole> roles =
                typeModel.mutationArgumentRoles(method);
        for (int index = 0; index < roles.size(); index++) {
            CollectionTypeModel.MutationArgumentRole role = roles.get(index);
            if (role == CollectionTypeModel.MutationArgumentRole.NONE) {
                continue;
            }
            ExpressionTree argument = invocation.getArguments().get(index);
            if (role == CollectionTypeModel.MutationArgumentRole.ELEMENT_SOURCE
                    || role == CollectionTypeModel.MutationArgumentRole.MAP_SOURCE) {
                if (!hasExactCollectionContract(argument, proof)) {
                    addFailure(
                            DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                            proof,
                            invocation,
                            typeModel.signature(method) + " source loses the retained collection's "
                                    + "exact generic element/key/value contract");
                }
                continue;
            }

            int retainedRoleIndex = role == CollectionTypeModel.MutationArgumentRole.VALUE ? 1 : 0;
            TypeMirror argumentType = typeOf(argument);
            if (!typeModel.isCompatibleItem(argumentType, proof, retainedRoleIndex)) {
                String roleName = role == CollectionTypeModel.MutationArgumentRole.ELEMENT
                        ? "element" : role.name().toLowerCase();
                addFailure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        proof,
                        invocation,
                        typeModel.signature(method) + " argument for the " + roleName
                                + " role has type " + String.valueOf(argumentType)
                                + "; it is not proven compatible with retained role "
                                + typeModel.retainedRole(proof, retainedRoleIndex));
            }
        }
    }

    private TypeMirror typeOf(ExpressionTree expression) {
        TreePath path = TreePath.getPath(
                getCurrentPath().getCompilationUnit(), expression);
        return path == null ? null : trees.getTypeMirror(path);
    }

    private boolean isModernSwitchFlow(Tree tree) {
        final boolean[] found = new boolean[1];
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree candidate, Void unused) {
                if (candidate == null || found[0]) {
                    return null;
                }
                if (isModernSwitchExpression(candidate)
                        || "YIELD".equals(candidate.getKind().name())) {
                    found[0] = true;
                    return null;
                }
                return super.scan(candidate, unused);
            }
        }.scan(tree, null);
        return found[0];
    }

    private static boolean isModernSwitchExpression(Tree tree) {
        return tree != null && "SWITCH_EXPRESSION".equals(tree.getKind().name());
    }

    private static Set<CollectionProof> copyProofs(Set<CollectionProof> proofs) {
        return new LinkedHashSet<CollectionProof>(proofs);
    }

    private ExecutableElement executableElement() {
        Element element = trees.getElement(getCurrentPath());
        return element instanceof ExecutableElement ? (ExecutableElement) element : null;
    }

    private boolean isOwnerField(Element element) {
        return element instanceof VariableElement
                && element.getKind() == ElementKind.FIELD
                && ownerType.equals(element.getEnclosingElement());
    }

    private static boolean isLocalAliasTarget(Element element) {
        if (!(element instanceof VariableElement)) {
            return false;
        }
        ElementKind kind = element.getKind();
        return kind == ElementKind.LOCAL_VARIABLE
                || kind == ElementKind.PARAMETER
                || kind == ElementKind.EXCEPTION_PARAMETER
                || kind == ElementKind.RESOURCE_VARIABLE;
    }

    private long sourcePosition(Tree tree) {
        long position = trees.getSourcePositions().getStartPosition(
                getCurrentPath().getCompilationUnit(), tree);
        return position < 0 ? Long.MAX_VALUE : position;
    }

    private void addFailure(DiagnosticId id, CollectionProof proof, Tree tree, String reason) {
        failures.add(ProofFailure.create(
                id,
                rootName,
                proof.getPath(),
                reason,
                tree,
                getCurrentPath().getCompilationUnit(),
                trees));
    }

    private String describeExecutable(ExecutableElement method) {
        if (method == null) {
            return "unresolved executable";
        }
        Element owner = method.getEnclosingElement();
        String ownerName = owner == null ? ownerType.getSimpleName().toString()
                : owner.getSimpleName().toString();
        if (method.getKind() == ElementKind.CONSTRUCTOR) {
            return ownerName + "()";
        }
        return ownerName + "." + method.getSimpleName() + "()";
    }

    private String describeNestedType(Element element) {
        if (element instanceof TypeElement) {
            return "nested type " + ((TypeElement) element).getSimpleName();
        }
        return "anonymous or local type";
    }

    private Void withExecutableContext(
            Phase nextPhase,
            ExecutableElement nextExecutable,
            String nextDescription,
            ScanAction action) {
        Phase previousPhase = phase;
        ExecutableElement previousExecutable = executable;
        String previousDescription = executableContext;
        Map<Element, Set<CollectionProof>> previousAliases = aliases;
        phase = nextPhase;
        executable = nextExecutable;
        executableContext = nextDescription;
        aliases = new LinkedHashMap<Element, Set<CollectionProof>>(previousAliases);
        try {
            return action.scan();
        } finally {
            phase = previousPhase;
            executable = previousExecutable;
            executableContext = previousDescription;
            aliases = previousAliases;
        }
    }

    private static ExpressionTree unwrap(ExpressionTree expression) {
        ExpressionTree current = expression;
        boolean changed;
        do {
            changed = false;
            if (current instanceof ParenthesizedTree) {
                current = ((ParenthesizedTree) current).getExpression();
                changed = true;
            } else if (current instanceof TypeCastTree) {
                current = ((TypeCastTree) current).getExpression();
                changed = true;
            }
        } while (changed);
        return current;
    }

    private interface ScanAction {
        Void scan();
    }
}
