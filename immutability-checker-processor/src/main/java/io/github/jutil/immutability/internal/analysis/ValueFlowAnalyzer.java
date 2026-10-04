package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.lang.model.util.Elements;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.jutil.immutability.internal.analysis.ValueFacts.Kind;
import static io.github.jutil.immutability.internal.analysis.ValueFacts.Origin;

/**
 * Bounded abstract execution. Source bodies are reusable relational summaries:
 * each call binds actual origins, executes effects and returns those same origins.
 * No proof result is cached by method alone. Recursive calls fail closed, while
 * loops converge over finite site identities and monotone may-origin unions.
 */
final class ValueFlowAnalyzer {
    private final TypeElement owner;
    private final String rootName;
    private final String incoming;
    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final CollectionTypeModel model;
    private final ReferenceTypeProof leaves;
    private final List<ProofFailure> failures;
    private final DiagnosticId writeDiagnostic;
    private final Map<Element, CollectionProof> proofs = new LinkedHashMap<Element, CollectionProof>();
    private final Map<Tree, TreePath> paths = new IdentityHashMap<Tree, TreePath>();
    private final Map<String, Origin> sites = new LinkedHashMap<String, Origin>();
    private final Set<String> reported = new LinkedHashSet<String>();
    private final List<ExecutableElement> calls = new ArrayList<ExecutableElement>();
    private final Object staticInitialization = new Object();
    private Map<Element, ValueFacts> frozen = new LinkedHashMap<Element, ValueFacts>();
    private ClassTree ownerTree;
    private TreePath outerPath;
    private Frame frame;
    private int entry;
    private final List<Tree> callSites = new ArrayList<Tree>();
    private final Map<Tree, Integer> treeIds = new IdentityHashMap<Tree, Integer>();

    private static final class Frame {
        Map<Element, ValueFacts> values;
        Map<Element, ValueFacts> observed;
        ValueFacts receiver;
        ValueFacts returned = new ValueFacts();
        Object initialization;
        boolean staticPhase;
        boolean externalReturn;
        String context;

        Frame copy() {
            Frame result = new Frame();
            result.values = new LinkedHashMap<Element, ValueFacts>(values);
            result.observed = new LinkedHashMap<Element, ValueFacts>(observed);
            result.receiver = receiver;
            result.returned = returned;
            result.initialization = initialization;
            result.staticPhase = staticPhase;
            result.externalReturn = externalReturn;
            result.context = context;
            return result;
        }
    }

    ValueFlowAnalyzer(TypeElement owner, String rootName, String incoming, Trees trees,
                      Types types, Elements elements, CollectionTypeModel model, ReferenceTypeProof leaves,
                      List<CollectionProof> proofs, List<ProofFailure> failures,
                      DiagnosticId writeDiagnostic) {
        this.owner = owner;
        this.rootName = rootName;
        this.incoming = incoming;
        this.trees = trees;
        this.types = types;
        this.elements = elements;
        this.model = model;
        this.leaves = leaves;
        this.failures = failures;
        this.writeDiagnostic = writeDiagnostic;
        for (CollectionProof proof : proofs) {
            this.proofs.put(proof.getField(), proof);
        }
    }

    void analyze(TreePath ownerPath) {
        ownerTree = (ClassTree) ownerPath.getLeaf();
        outerPath = ownerPath;
        for (TreePath p = ownerPath; p != null; p = p.getParentPath()) {
            if (p.getLeaf() instanceof ClassTree) {
                outerPath = p;
            }
        }
        index(outerPath);
        frame = initialFrame(staticInitialization, true, "static initializer of " + owner.getSimpleName());
        initialize(true);
        checkInitialized(true);
        frozen = fieldValues(frame.values);

        Map<Element, ValueFacts> instances = new LinkedHashMap<Element, ValueFacts>();
        for (Element element : owner.getEnclosedElements()) {
            if (element.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            entry++;
            frame = initialFrame(new Object(), false, owner.getSimpleName() + "()");
            frame.values.putAll(frozen);
            ExecutableElement constructor = (ExecutableElement) element;
            runConstructor(constructor, externalArguments(constructor));
            checkInitialized(false);
            instances = ValueFacts.join(instances, fieldValues(frame.values));
        }
        frozen = ValueFacts.join(frozen, instances);
        // Every externally callable owner method is an open-world frozen entry.
        // Nestmates, including their private methods/initializers, are conservative
        // frozen entries too: they may reach private initialization helpers later.
        scanEntries(outerPath);
    }

    private void index(TreePath path) {
        new TreePathScanner<Void, Void>() {
            @Override public Void scan(Tree tree, Void unused) {
                if (tree != null) {
                    TreePath p = getCurrentPath();
                    paths.put(tree, p == null ? null : new TreePath(p, tree));
                    if (!treeIds.containsKey(tree)) { treeIds.put(tree, treeIds.size()); }
                }
                return super.scan(tree, unused);
            }
        }.scan(path, null);
        paths.put(path.getLeaf(), path);
    }

    private Frame initialFrame(Object initialization, boolean staticPhase, String context) {
        Frame result = new Frame();
        result.values = new LinkedHashMap<Element, ValueFacts>();
        result.observed = new LinkedHashMap<Element, ValueFacts>();
        result.initialization = initialization;
        result.staticPhase = staticPhase;
        result.context = context;
        result.receiver = value(Kind.RECEIVER, owner.asType(), initialization, "this:" + entry);
        return result;
    }

    private void initialize(boolean staticState) {
        for (Tree member : ownerTree.getMembers()) {
            if (member instanceof VariableTree) {
                Element field = element(member);
                VariableTree variable = (VariableTree) member;
                if (field != null && field.getModifiers().contains(Modifier.STATIC) == staticState) {
                    ValueFacts value = variable.getInitializer() == null
                            ? value(Kind.NULL, field.asType(), null, "default:" + field)
                            : eval(variable.getInitializer());
                    put(field, value);
                    if (variable.getInitializer() != null) {
                        retain(field, value, variable);
                    }
                }
            } else if (member instanceof BlockTree && ((BlockTree) member).isStatic() == staticState) {
                execute(member);
            }
        }
    }

    private void runConstructor(ExecutableElement method, List<ValueFacts> arguments) {
        TreePath path = trees.getPath(method);
        if (path == null || ! (path.getLeaf() instanceof MethodTree)) {
            return; // javac's implicit empty constructor has no state effects.
        }
        index(path);
        MethodTree body = (MethodTree) path.getLeaf();
        if (!enter(method, body)) {
            return;
        }
        bind(method, arguments);
        boolean delegates = false;
        if (body.getBody() != null && !body.getBody().getStatements().isEmpty()) {
            Tree first = body.getBody().getStatements().get(0);
            if (first instanceof ExpressionStatementTree) {
                ExpressionTree expression = ((ExpressionStatementTree) first).getExpression();
                if (expression instanceof MethodInvocationTree) {
                    MethodInvocationTree call = (MethodInvocationTree) expression;
                    delegates = "this".equals(call.getMethodSelect().toString());
                }
            }
        }
        if (!delegates) {
            initialize(false);
        }
        execute(body.getBody());
        calls.remove(calls.size() - 1);
        callSites.remove(callSites.size() - 1);
    }

    private void scanEntries(TreePath path) {
        new TreePathScanner<Void, Void>() {
            @Override public Void visitMethod(MethodTree node, Void unused) {
                Element resolved = trees.getElement(getCurrentPath());
                if (!(resolved instanceof ExecutableElement)) {
                    return null;
                }
                ExecutableElement method = (ExecutableElement) resolved;
                boolean own = owner.equals(method.getEnclosingElement());
                if (own && (method.getKind() == ElementKind.CONSTRUCTOR
                        || method.getModifiers().contains(Modifier.PRIVATE))) {
                    return null;
                }
                entry++;
                frame = initialFrame(null, false, describe(method));
                frame.values.putAll(frozen);
                frame.externalReturn = true;
                if (!own) {
                    frame.receiver = external(method.getEnclosingElement().asType(), node);
                }
                bind(method, externalArguments(method));
                calls.add(method);
                callSites.add(node);
                execute(node.getBody());
                calls.remove(calls.size() - 1);
                callSites.remove(callSites.size() - 1);
                return null;
            }
            @Override public Void visitVariable(VariableTree node, Void unused) {
                Element field = trees.getElement(getCurrentPath());
                if (field != null && field.getKind() == ElementKind.FIELD
                        && !owner.equals(field.getEnclosingElement()) && node.getInitializer() != null) {
                    frame = initialFrame(null, false, "nested field declaration");
                    frame.values.putAll(frozen);
                    escape(eval(node.getInitializer()), node, "retained mutable collection alias is stored by a field declaration");
                }
                return null;
            }
            @Override public Void visitBlock(BlockTree node, Void unused) {
                if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree
                        && !owner.equals(trees.getElement(getCurrentPath().getParentPath()))) {
                    frame = initialFrame(null, false, "nested initializer");
                    frame.values.putAll(frozen);
                    execute(node);
                }
                return null;
            }
        }.scan(path, null);
    }

    private void checkInitialized(boolean staticState) {
        for (CollectionProof proof : proofs.values()) {
            if (proof.isStaticState() != staticState) {
                continue;
            }
            ValueFacts value = frame.values.get(proof.getField());
            if (value == null || value.has(Kind.NULL)) {
                fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), proof.getFieldTree(),
                        proof.getField().asType() + " -> no direct supported fresh collection allocation or snapshot is proven across every constructor or initialization path; allocation is conditional or absent");
            }
        }
    }

    private void retain(Element field, ValueFacts value, Tree at) {
        CollectionProof proof = proofs.get(field);
        if (proof == null) {
            escape(value, at, "collection alias is assigned into unrelated field, static state, or array storage");
            return;
        }
        for (Origin origin : value.origins) {
            if (origin.kind == Kind.NULL) {
                continue;
            }
            if (origin.kind != Kind.FRESH && origin.kind != Kind.SNAPSHOT) {
                fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), at,
                        origin.type + " -> " + (origin.description == null
                                ? "unknown collection origin; ownership cannot be established"
                                : origin.description));
                continue;
            }
            if (origin.unchecked || !model.hasExactRoleContract(origin.type, proof)) {
                fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), at,
                        "value loses the retained collection's exact generic element/key/value contract through a raw or unchecked type");
            }
            if (origin.kind == Kind.SNAPSHOT) {
                origin.retained.add(proof);
                for (Map.Entry<Tree, String> hazard : origin.hazards.entrySet()) {
                    fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), hazard.getKey(), hazard.getValue());
                }
            }
            if (origin.kind == Kind.FRESH) {
                if (origin.initialization != frame.initialization || frame.initialization == null
                        || !origin.retained.isEmpty() && !origin.retained.contains(proof)) {
                    fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), at,
                            "field-to-field collection aliasing or ownership transfer does not establish exclusive container ownership");
                }
                origin.retained.add(proof);
                for (Map.Entry<Tree, String> hazard : origin.hazards.entrySet()) {
                    fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), hazard.getKey(), hazard.getValue());
                }
                if (!field.getModifiers().contains(Modifier.PRIVATE)) {
                    fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), at,
                            "non-private final collection field exposes mutation-capable retained state");
                }
            }
        }
    }

    private void execute(Tree tree) {
        if (tree == null) {
            return;
        }
        if (tree instanceof BlockTree) {
            for (StatementTree statement : ((BlockTree) tree).getStatements()) {
                execute(statement);
            }
        } else if (tree instanceof VariableTree) {
            VariableTree variable = (VariableTree) tree;
            Element target = element(tree);
            ValueFacts value = variable.getInitializer() == null
                    ? external(target == null ? null : target.asType(), tree) : eval(variable.getInitializer());
            assignLocal(target, value, tree);
        } else if (tree instanceof ExpressionStatementTree) {
            eval(((ExpressionStatementTree) tree).getExpression());
        } else if (tree instanceof ReturnTree) {
            ValueFacts result = eval(((ReturnTree) tree).getExpression());
            frame.returned = frame.returned.union(result);
            if (frame.externalReturn) {
                escape(result, tree, "retained mutable collection escapes through return from " + frame.context);
            }
        } else if (tree instanceof IfTree) {
            IfTree branch = (IfTree) tree;
            eval(branch.getCondition());
            Frame start = frame.copy();
            execute(branch.getThenStatement());
            Frame left = frame;
            frame = start.copy();
            execute(branch.getElseStatement());
            merge(left, frame);
        } else if (tree instanceof TryTree) {
            executeTry((TryTree) tree);
        } else if (tree instanceof ForLoopTree) {
            ForLoopTree loop = (ForLoopTree) tree;
            for (StatementTree init : loop.getInitializer()) { execute(init); }
            loop(new Runnable() { public void run() {
                eval(loop.getCondition()); execute(loop.getStatement());
                for (ExpressionStatementTree update : loop.getUpdate()) { execute(update); }
            }});
        } else if (tree instanceof WhileLoopTree) {
            WhileLoopTree loop = (WhileLoopTree) tree;
            loop(new Runnable() { public void run() {
                eval(loop.getCondition()); execute(loop.getStatement());
            }});
        } else if (tree instanceof DoWhileLoopTree) {
            DoWhileLoopTree loop = (DoWhileLoopTree) tree;
            loop(new Runnable() { public void run() {
                execute(loop.getStatement()); eval(loop.getCondition());
            }});
        } else if (tree instanceof EnhancedForLoopTree) {
            EnhancedForLoopTree loop = (EnhancedForLoopTree) tree;
            ValueFacts source = eval(loop.getExpression());
            hazard(source, tree, "iterator traversal of retained state is unsupported");
            loop(new Runnable() { public void run() {
                execute(loop.getVariable()); execute(loop.getStatement());
            }});
        } else if (tree instanceof SwitchTree) {
            SwitchTree switchTree = (SwitchTree) tree;
            ValueFacts selector = eval(switchTree.getExpression());
            Frame incoming = frame.copy();
            Frame joined = incoming.copy();
            for (CaseTree branch : switchTree.getCases()) {
                merge(incoming, frame);
                if (branch.getStatements() != null) {
                    for (StatementTree statement : branch.getStatements()) { execute(statement); }
                } else {
                    hazard(selector, tree, "binding pattern may alias retained state; switch-expression flow is unsupported");
                    scanUnsupported(branch);
                }
                merge(joined, frame);
                joined = frame.copy();
            }
        } else if (tree instanceof ThrowTree) {
            escape(eval(((ThrowTree) tree).getExpression()), tree, "verified reference escapes through thrown exception");
        } else if (tree instanceof SynchronizedTree) {
            eval(((SynchronizedTree) tree).getExpression());
            execute(((SynchronizedTree) tree).getBlock());
        } else if (tree instanceof LabeledStatementTree) {
            execute(((LabeledStatementTree) tree).getStatement());
        } else if (tree instanceof AssertTree) {
            eval(((AssertTree) tree).getCondition()); eval(((AssertTree) tree).getDetail());
        } else if (tree instanceof ClassTree) {
            deferredClass((ClassTree) tree);
        } else if (!(tree instanceof BreakTree) && !(tree instanceof ContinueTree)
                && !(tree instanceof EmptyStatementTree)) {
            scanUnsupported(tree);
        }
    }

    private void executeTry(TryTree tree) {
        Frame before = frame.copy();
        for (Tree resource : tree.getResources()) { execute(resource); }
        execute(tree.getBlock());
        Frame normal = frame.copy();
        Frame exceptional = before.copy();
        exceptional.values = ValueFacts.join(before.values, frame.observed);
        for (CatchTree catcher : tree.getCatches()) {
            frame = exceptional.copy();
            execute(catcher.getParameter()); execute(catcher.getBlock());
            merge(normal, frame);
            normal = frame.copy();
        }
        frame = normal;
        if (tree.getFinallyBlock() != null) {
            // finally also executes for exceptions and early returns at any point.
            frame.values = ValueFacts.join(frame.values, exceptional.values);
            execute(tree.getFinallyBlock());
        }
    }

    private void loop(Runnable body) {
        Map<Element, ValueFacts> history = new LinkedHashMap<Element, ValueFacts>(frame.observed);
        Map<Element, ValueFacts> header = new LinkedHashMap<Element, ValueFacts>(frame.values);
        do {
            frame.values = new LinkedHashMap<Element, ValueFacts>(header);
            frame.observed = new LinkedHashMap<Element, ValueFacts>(header);
            body.run();
            Map<Element, ValueFacts> next = ValueFacts.join(header, frame.observed);
            if (next.equals(header)) {
                frame.values = next;
                frame.observed = ValueFacts.join(history, frame.observed);
                observe();
                return;
            }
            header = next;
        } while (true);
    }

    private ValueFacts eval(ExpressionTree expression) {
        if (expression == null) { return new ValueFacts(); }
        if (expression instanceof ParenthesizedTree) {
            return eval(((ParenthesizedTree) expression).getExpression());
        }
        if (expression instanceof LiteralTree) {
            return value(expression.getKind() == Tree.Kind.NULL_LITERAL ? Kind.NULL : Kind.VALUE,
                    type(expression), null, site(expression));
        }
        if (expression instanceof IdentifierTree || expression instanceof MemberSelectTree) {
            if ("this".equals(expression.toString()) || expression.toString().endsWith(".this")) {
                return frame.receiver;
            }
            Element resolved = element(expression);
            ValueFacts receiver = expression instanceof MemberSelectTree
                    ? eval(((MemberSelectTree) expression).getExpression()) : frame.receiver;
            if (resolved != null && resolved.getKind() == ElementKind.FIELD
                    && owner.equals(resolved.getEnclosingElement())
                    && !resolved.getModifiers().contains(Modifier.STATIC)
                    && frame.initialization != null && !currentReceiver(receiver)) {
                CollectionProof proof = proofs.get(resolved);
                if (proof != null) {
                    // A different object's state has already frozen. Never substitute
                    // the active constructor's field value merely by field symbol.
                    ValueFacts other = value(Kind.FRESH, resolved.asType(), null, "other:" + site(expression));
                    for (Origin origin : other.origins) { origin.retained.add(proof); }
                    return other;
                }
                return external(resolved.asType(), expression);
            }
            ValueFacts known = frame.values.get(resolved);
            if (known != null) { return known; }
            if (resolved != null && resolved.getKind() == ElementKind.FIELD && owner.equals(resolved.getEnclosingElement())) {
                return external(resolved.asType(), expression);
            }
            return external(type(expression), expression);
        }
        if (expression instanceof TypeCastTree) {
            ValueFacts value = eval(((TypeCastTree) expression).getExpression());
            checkConversion(value, type(expression), expression);
            return value;
        }
        if (expression instanceof AssignmentTree) {
            AssignmentTree assignment = (AssignmentTree) expression;
            ValueFacts receiver = assignmentReceiver(assignment.getVariable());
            ValueFacts value = eval(assignment.getExpression());
            write(assignment.getVariable(), receiver, value, expression);
            return value;
        }
        if (expression instanceof CompoundAssignmentTree) {
            CompoundAssignmentTree assignment = (CompoundAssignmentTree) expression;
            ValueFacts receiver = assignmentReceiver(assignment.getVariable());
            eval(assignment.getExpression());
            ValueFacts value = external(type(expression), expression);
            write(assignment.getVariable(), receiver, value, expression);
            return value;
        }
        if (expression instanceof UnaryTree) {
            UnaryTree unary = (UnaryTree) expression;
            String kind = unary.getKind().name();
            if (kind.endsWith("INCREMENT") || kind.endsWith("DECREMENT")) {
                ValueFacts receiver = assignmentReceiver(unary.getExpression());
                write(unary.getExpression(), receiver, external(type(expression), expression), expression);
            } else {
                eval(unary.getExpression());
            }
            return external(type(expression), expression);
        }
        if (expression instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) expression;
            eval(conditional.getCondition());
            Frame start = frame.copy();
            ValueFacts left = eval(conditional.getTrueExpression());
            Frame leftFrame = frame;
            frame = start.copy();
            ValueFacts right = eval(conditional.getFalseExpression());
            merge(leftFrame, frame);
            return left.union(right);
        }
        if (expression instanceof BinaryTree) {
            BinaryTree binary = (BinaryTree) expression;
            eval(binary.getLeftOperand());
            Frame beforeRight = frame.copy();
            eval(binary.getRightOperand());
            if (binary.getKind() == Tree.Kind.CONDITIONAL_AND || binary.getKind() == Tree.Kind.CONDITIONAL_OR) {
                merge(beforeRight, frame);
            }
            return external(type(expression), expression);
        }
        if (expression instanceof MethodInvocationTree) { return invoke((MethodInvocationTree) expression); }
        if (expression instanceof NewClassTree) { return allocate((NewClassTree) expression); }
        if (expression instanceof LambdaExpressionTree) {
            LambdaExpressionTree lambda = (LambdaExpressionTree) expression;
            Frame saved = frame;
            frame = frame.copy(); frame.initialization = null; frame.externalReturn = true;
            frame.context = "lambda in " + saved.context;
            if (lambda.getBody() instanceof ExpressionTree) {
                escape(eval((ExpressionTree) lambda.getBody()), expression, "retained mutable collection escapes through a callback result");
            } else { execute(lambda.getBody()); }
            frame = saved;
            return external(type(expression), expression);
        }
        if (expression instanceof MemberReferenceTree) {
            MemberReferenceTree reference = (MemberReferenceTree) expression;
            ValueFacts receiver = eval(reference.getQualifierExpression());
            hazard(receiver, expression, "method reference captures retained state; deferred effects are unproven");
            Element target = element(expression);
            if (target instanceof ExecutableElement && trees.getPath(target) != null) {
                Frame saved = frame;
                frame = frame.copy(); frame.initialization = null;
                call((ExecutableElement) target, receiver, externalArguments((ExecutableElement) target), expression);
                frame = saved;
            }
            return external(type(expression), expression);
        }
        if (expression instanceof NewArrayTree) {
            NewArrayTree array = (NewArrayTree) expression;
            for (ExpressionTree dimension : array.getDimensions()) { eval(dimension); }
            if (array.getInitializers() != null) {
                for (ExpressionTree initializer : array.getInitializers()) {
                    escape(eval(initializer), expression, "retained mutable collection alias is stored into an array");
                }
            }
            return external(type(expression), expression);
        }
        if (expression instanceof ArrayAccessTree) {
            eval(((ArrayAccessTree) expression).getExpression()); eval(((ArrayAccessTree) expression).getIndex());
            return external(type(expression), expression);
        }
        if (expression instanceof InstanceOfTree) {
            ValueFacts value = eval(((InstanceOfTree) expression).getExpression());
            // Binding-pattern APIs are deliberately not linked on Java 8.
            if (hasTreeKind(expression, "BINDING_PATTERN")) {
                hazard(value, expression, "binding pattern may alias retained mutable collection state; pattern provenance is unsupported");
            }
            return external(type(expression), expression);
        }
        scanUnsupported(expression);
        return value(Kind.UNKNOWN, type(expression), null, site(expression));
    }

    private ValueFacts invoke(MethodInvocationTree invocation) {
        Element target = element(invocation);
        ExecutableElement method = target instanceof ExecutableElement ? (ExecutableElement) target : null;
        ExpressionTree select = invocation.getMethodSelect();
        ValueFacts receiver = select instanceof MemberSelectTree
                ? eval(((MemberSelectTree) select).getExpression()) : frame.receiver;
        List<ValueFacts> arguments = new ArrayList<ValueFacts>();
        for (ExpressionTree argument : invocation.getArguments()) { arguments.add(eval(argument)); }
        if (method == null && "super".equals(select.toString())
                && "java.lang.Object".equals(owner.getSuperclass().toString()) && arguments.isEmpty()) {
            return new ValueFacts();
        }
        if (method != null && method.getKind() == ElementKind.CONSTRUCTOR) {
            if (owner.equals(method.getEnclosingElement()) && "this".equals(select.toString())) {
                runConstructor(method, arguments);
            } else {
                for (ValueFacts argument : arguments) {
                    escape(argument, invocation, "verified reference passed to superclass constructor; retention effects are unproven");
                }
            }
            return new ValueFacts();
        }
        if (model.isListCopyOf(method)) {
            ValueFacts result = value(Kind.SNAPSHOT, type(invocation), null, site(invocation));
            for (Origin snapshot : result.origins) {
                if (arguments.size() != 1 || !exactRoles(arguments.get(0), snapshot.type)) {
                    snapshot.unchecked = true;
                }
            }
            return result;
        }
        CollectionTypeModel.Operation operation = model.operation(method);
        if (hasContainer(receiver) && operation != CollectionTypeModel.Operation.UNKNOWN) {
            collectionCall(receiver, arguments, method, operation, invocation);
            return external(type(invocation), invocation);
        }
        ExecutableElement actualTarget = exactTarget(method, receiver);
        if (actualTarget != null && trees.getPath(actualTarget) != null) {
            return call(actualTarget, receiver, arguments, invocation);
        }
        String reason = method == null ? "unsupported call target" : trees.getPath(method) == null
                ? "unavailable body or unresolved effect" : "unsupported call target: unresolved virtual dispatch";
        String description = reason + " for " + model.signature(method) + "; effects are unproven";
        if (hasContainer(receiver)) { description += "; not an explicitly modeled collection operation"; }
        if (method == null || !method.getModifiers().contains(Modifier.STATIC)) {
            hazard(receiver, invocation, description);
        }
        for (ValueFacts argument : arguments) { escape(argument, invocation, "retained mutable collection is passed to " + description); }
        if (method != null && owner.equals(method.getEnclosingElement())) {
            fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, owner.getSimpleName().toString(), invocation, description);
        }
        return unknown(type(invocation), invocation, description
                + "; factory method return values are not supported without a source or explicit semantic proof");
    }

    private ValueFacts call(ExecutableElement method, ValueFacts receiver,
                            List<ValueFacts> arguments, Tree at) {
        TreePath path = trees.getPath(method);
        if (path == null || !(path.getLeaf() instanceof MethodTree)
                || ((MethodTree) path.getLeaf()).getBody() == null) {
            hazard(receiver, at, "unavailable body or unresolved effect for " + model.signature(method));
            for (ValueFacts argument : arguments) { escape(argument, at, "retained mutable collection is passed to an unavailable helper body; callee mutation and retention effects are unproven and it may retain a mutable alias"); }
            return unknown(type(at), at, "unavailable body or unresolved effect for " + model.signature(method));
        }
        index(path);
        if (calls.contains(method) && !relevantCycle(method, arguments)) {
            return external(method.getReturnType(), at);
        }
        if (!enter(method, at)) { return value(Kind.UNKNOWN, type(at), null, site(at)); }
        Frame caller = frame;
        frame = caller.copy();
        frame.receiver = receiver;
        frame.externalReturn = false;
        frame.returned = new ValueFacts();
        frame.context = caller.context + " -> " + describe(method);
        bind(method, arguments);
        execute(((MethodTree) path.getLeaf()).getBody());
        ValueFacts returned = frame.returned;
        Map<Element, ValueFacts> changedFields = fieldValues(frame.values);
        Map<Element, ValueFacts> observedFields = fieldValues(frame.observed);
        frame = caller;
        frame.values.putAll(changedFields);
        frame.observed = ValueFacts.join(frame.observed, observedFields);
        observe();
        calls.remove(calls.size() - 1);
        callSites.remove(callSites.size() - 1);
        return returned.origins.isEmpty() && method.getReturnType().getKind() != TypeKind.VOID
                ? value(Kind.UNKNOWN, method.getReturnType(), null, site(at)) : returned;
    }

    private boolean relevantCycle(ExecutableElement method, List<ValueFacts> arguments) {
        if (model.isCollectionLike(method.getReturnType())) { return true; }
        for (ValueFacts argument : arguments) {
            for (Origin origin : argument.origins) {
                if (origin.kind == Kind.FRESH || origin.kind == Kind.SNAPSHOT || origin.kind == Kind.RECEIVER) {
                    return true;
                }
            }
        }
        final boolean[] relevant = { false };
        for (ExecutableElement active : calls) {
            TreePath body = trees.getPath(active);
            if (body == null) { return true; }
            new TreePathScanner<Void, Void>() {
                @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
                    inspect(trees.getElement(getCurrentPath()));
                    return super.visitIdentifier(node, unused);
                }
                @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                    inspect(trees.getElement(getCurrentPath()));
                    return super.visitMemberSelect(node, unused);
                }
                private void inspect(Element element) {
                    if (element != null && element.getKind() == ElementKind.FIELD
                            && owner.equals(element.getEnclosingElement())) { relevant[0] = true; }
                }
            }.scan(body, null);
        }
        return relevant[0];
    }

    private boolean enter(ExecutableElement method, Tree at) {
        if (calls.contains(method)) {
            fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, owner.getSimpleName().toString(), at,
                    "recursive method-call cycle has unresolved effects: " + describe(method));
            return false;
        }
        calls.add(method);
        callSites.add(at);
        return true;
    }

    private ExecutableElement exactTarget(ExecutableElement method, ValueFacts receiver) {
        if (method == null) { return null; }
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)
                || modifiers.contains(Modifier.FINAL)) { return method; }
        ExecutableElement resolved = null;
        for (Origin origin : receiver.origins) {
            if (origin.kind == Kind.NULL) { continue; }
            Element type = origin.type == null ? null : types.asElement(origin.type);
            if (!(type instanceof TypeElement) || !type.getModifiers().contains(Modifier.FINAL)) { return null; }
            TypeElement runtime = (TypeElement) type;
            ExecutableElement target = method;
            for (Element member : elements.getAllMembers(runtime)) {
                if (member instanceof ExecutableElement
                        && elements.overrides((ExecutableElement) member, method, runtime)) {
                    target = (ExecutableElement) member;
                    break;
                }
            }
            if (resolved != null && !resolved.equals(target)) { return null; }
            resolved = target;
        }
        return resolved;
    }

    private ValueFacts allocate(NewClassTree allocation) {
        Element target = element(allocation);
        ExecutableElement constructor = target instanceof ExecutableElement ? (ExecutableElement) target : null;
        TypeElement implementation = constructor != null && constructor.getEnclosingElement() instanceof TypeElement
                ? (TypeElement) constructor.getEnclosingElement() : null;
        List<ValueFacts> arguments = new ArrayList<ValueFacts>();
        for (ExpressionTree argument : allocation.getArguments()) { arguments.add(eval(argument)); }
        if (allocation.getClassBody() == null && model.isSupportedImplementation(implementation)) {
            ValueFacts fresh = value(Kind.FRESH, type(allocation), frame.initialization, site(allocation));
            int source = model.copySourceArgumentIndex(constructor);
            if (source >= 0 && !exactRoles(arguments.get(source), type(allocation))) {
                for (Origin origin : fresh.origins) {
                    origin.unchecked = true;
                    origin.hazards.put(allocation, "copy-constructor source loses the retained collection's exact generic element/key/value contract");
                }
            }
            return fresh;
        }
        for (ValueFacts argument : arguments) {
            escape(argument, allocation, "retained mutable collection is passed to an unmodeled constructor and may escape");
        }
        if (allocation.getClassBody() != null) { deferredClass(allocation.getClassBody()); }
        return external(type(allocation), allocation);
    }

    private void collectionCall(ValueFacts receiver, List<ValueFacts> arguments, ExecutableElement method,
                                CollectionTypeModel.Operation operation, MethodInvocationTree at) {
        for (Origin origin : receiver.origins) {
            if (origin.kind == Kind.NULL) { continue; }
            if (origin.kind == Kind.SNAPSHOT && operation == CollectionTypeModel.Operation.MUTATOR) {
                // List.copyOf lists throw before structural changes. Arguments have
                // already been evaluated, so their independent effects still count.
                continue;
            }
            if (origin.unchecked) { hazard(ValueFacts.of(origin), at, model.signature(method) + " receiver loses exact generic element/key/value contract through a raw or unchecked type"); }
            if (operation == CollectionTypeModel.Operation.MUTATOR) {
                validateItems(origin, arguments, method, at);
                for (CollectionProof proof : origin.retained) {
                    if (frame.initialization == null || origin.initialization != frame.initialization
                            || proof.isStaticState() != frame.staticPhase) {
                        fail(proof.getMutationDiagnostic(), proof.getPath(), at,
                                model.signature(method) + " structurally mutates retained collection state "
                                        + (proof.isStaticState() ? "after class initialization" : "after instance construction"));
                    }
                }
            } else if (operation == CollectionTypeModel.Operation.CALLBACK_MUTATOR) {
                hazard(ValueFacts.of(origin), at, model.signature(method)
                        + " is callback-based; callback effects and escape behavior are not modeled");
            } else if (operation == CollectionTypeModel.Operation.VIEW_OR_ITERATOR) {
                hazard(ValueFacts.of(origin), at, model.signature(method)
                        + " creates an iterator, stream, spliterator, or aliasing view of retained state");
            }
        }
        // Borrowed collections may override every operation and retain its arguments.
        // Even known containers can retain an argument supplied as an element/key/value.
        if (operation == CollectionTypeModel.Operation.MUTATOR && onlySnapshots(receiver)) { return; }
        List<CollectionTypeModel.MutationArgumentRole> roles = model.mutationArgumentRoles(method);
        for (int i = 0; i < arguments.size(); i++) {
            boolean source = i < roles.size() && (roles.get(i) == CollectionTypeModel.MutationArgumentRole.ELEMENT_SOURCE
                    || roles.get(i) == CollectionTypeModel.MutationArgumentRole.MAP_SOURCE);
            boolean insertion = i < roles.size() && roles.get(i) != CollectionTypeModel.MutationArgumentRole.NONE && !source;
            if (receiver.has(Kind.BORROWED) || receiver.has(Kind.UNKNOWN) || insertion) {
                escape(arguments.get(i), at, "mutable alias passed to a receiver with unproven retention effects");
            }
        }
    }

    private void validateItems(Origin container, List<ValueFacts> arguments,
                               ExecutableElement method, Tree at) {
        List<CollectionTypeModel.MutationArgumentRole> roles = model.mutationArgumentRoles(method);
        for (int i = 0; i < roles.size(); i++) {
            CollectionTypeModel.MutationArgumentRole role = roles.get(i);
            if (role == CollectionTypeModel.MutationArgumentRole.NONE) { continue; }
            boolean valid;
            if (role == CollectionTypeModel.MutationArgumentRole.ELEMENT_SOURCE
                    || role == CollectionTypeModel.MutationArgumentRole.MAP_SOURCE) {
                valid = exactRoles(arguments.get(i), container.type);
            } else {
                valid = true;
                for (Origin argument : arguments.get(i).origins) {
                    if (!model.isCompatibleItem(argument.type, container.type,
                            role == CollectionTypeModel.MutationArgumentRole.VALUE ? 1 : 0)) { valid = false; }
                }
            }
            if (!valid) {
                container.unchecked = true;
                boolean bulk = role == CollectionTypeModel.MutationArgumentRole.ELEMENT_SOURCE
                        || role == CollectionTypeModel.MutationArgumentRole.MAP_SOURCE;
                String argumentType = arguments.get(i).origins.isEmpty() ? "unknown"
                        : String.valueOf(arguments.get(i).origins.iterator().next().type);
                hazard(ValueFacts.of(container), at, model.signature(method) + (bulk
                        ? " source loses exact generic element/key/value contract"
                        : " argument for the " + role.name().toLowerCase() + " role has type " + argumentType
                        + "; not proven compatible with retained role"));
            }
        }
    }

    private boolean exactRoles(ValueFacts value, TypeMirror contract) {
        if (value.origins.isEmpty()) { return false; }
        for (Origin origin : value.origins) {
            if (origin.kind == Kind.NULL) { continue; }
            if (origin.unchecked || origin.type == null || !model.hasExactRoleContract(origin.type, contract)) { return false; }
        }
        return true;
    }

    private void bind(ExecutableElement method, List<ValueFacts> arguments) {
        for (int i = 0; i < method.getParameters().size(); i++) {
            VariableElement parameter = method.getParameters().get(i);
            ValueFacts argument = i < arguments.size() ? arguments.get(i) : external(parameter.asType(), trees.getTree(parameter));
            assignLocal(parameter, argument, trees.getTree(method));
        }
    }

    private List<ValueFacts> externalArguments(ExecutableElement method) {
        List<ValueFacts> result = new ArrayList<ValueFacts>();
        for (VariableElement parameter : method.getParameters()) {
            result.add(external(parameter.asType(), trees.getTree(parameter)));
        }
        return result;
    }

    private ValueFacts assignmentReceiver(ExpressionTree target) {
        if (target instanceof ParenthesizedTree) {
            return assignmentReceiver(((ParenthesizedTree) target).getExpression());
        }
        if (target instanceof MemberSelectTree) {
            return eval(((MemberSelectTree) target).getExpression());
        }
        if (target instanceof ArrayAccessTree) {
            ArrayAccessTree array = (ArrayAccessTree) target;
            ValueFacts receiver = eval(array.getExpression());
            eval(array.getIndex());
            return receiver;
        }
        return frame.receiver;
    }

    private void write(ExpressionTree target, ValueFacts receiver, ValueFacts value, Tree at) {
        while (target instanceof ParenthesizedTree) {
            target = ((ParenthesizedTree) target).getExpression();
        }
        Element field = element(target);
        if (field != null && field.getKind() != ElementKind.FIELD) {
            assignLocal(field, value, at);
            return;
        }
        if (field != null && owner.equals(field.getEnclosingElement())) {
            boolean staticField = field.getModifiers().contains(Modifier.STATIC);
            boolean allowed = staticField ? frame.staticPhase && frame.initialization == staticInitialization
                    : !frame.staticPhase && currentReceiver(receiver);
            if (!allowed) {
                fail(writeDiagnostic, fieldPath(field), at, "write in " + frame.context
                        + (staticField ? " occurs after class initialization" : " occurs outside instance construction")
                        + (frame.initialization != null && !staticField
                        ? "; receiver not proven to be the object under construction" : ""));
            }
            retain(field, value, at);
            put(field, value);
        } else {
            escape(value, at, "collection alias is assigned into unrelated field, static state, or array storage");
        }
    }

    private boolean currentReceiver(ValueFacts value) {
        if (frame.initialization == null || value.origins.isEmpty()) { return false; }
        for (Origin origin : value.origins) {
            if (origin.kind != Kind.RECEIVER || origin.initialization != frame.initialization) { return false; }
        }
        return true;
    }

    private void assignLocal(Element target, ValueFacts value, Tree at) {
        if (target == null) { return; }
        checkConversion(value, target.asType(), at);
        put(target, value);
    }

    private void checkConversion(ValueFacts value, TypeMirror target, Tree at) {
        if (target == null || !model.isCollectionLike(target)) { return; }
        for (Origin origin : value.origins) {
            if (origin.kind != Kind.NULL && (origin.type == null || !model.hasExactRoleContract(origin.type, target))) {
                origin.unchecked = true;
                hazard(ValueFacts.of(origin), at, "collection alias loses exact generic element/key/value contract through a raw or unchecked type");
            }
        }
    }

    private void escape(ValueFacts value, Tree at, String reason) {
        for (Origin origin : value.origins) {
            if (origin.kind == Kind.FRESH) { hazard(ValueFacts.of(origin), at, reason); }
            if (origin.kind == Kind.RECEIVER && origin.initialization != null) {
                fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, owner.getSimpleName().toString(), at,
                        "receiver escapes before initialization completes: " + reason);
            }
        }
    }

    private void hazard(ValueFacts value, Tree at, String reason) {
        for (Origin origin : value.origins) {
            if (origin.kind == Kind.FRESH || origin.kind == Kind.SNAPSHOT) {
                origin.hazards.put(at, reason + " in " + frame.context);
                for (CollectionProof proof : origin.retained) {
                    fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, proof.getPath(), at, reason);
                }
            } else if (origin.kind == Kind.RECEIVER) {
                fail(DiagnosticId.REACHABLE_REFERENCE_UNPROVEN, owner.getSimpleName().toString(), at, reason);
            }
        }
    }

    private void deferredClass(ClassTree tree) {
        Frame saved = frame;
        frame = frame.copy(); frame.initialization = null; frame.externalReturn = true;
        frame.context = "nested type in " + saved.context;
        for (Tree member : tree.getMembers()) {
            if (member instanceof VariableTree) {
                escape(eval(((VariableTree) member).getInitializer()), member,
                        "retained mutable collection alias is stored by a field declaration");
            } else if (member instanceof MethodTree) {
                execute(((MethodTree) member).getBody());
            } else if (member instanceof BlockTree) { execute(member); }
        }
        frame = saved;
    }

    private void scanUnsupported(Tree tree) {
        TreePath path = path(tree);
        if (path == null) { return; }
        new TreePathScanner<Void, Void>() {
            private Void inspect(ExpressionTree expression) {
                hazard(eval(expression), tree,
                        "unsupported source construct: switch-expression collection flow may escape; effects are unproven");
                return null;
            }
            @Override public Void visitIdentifier(IdentifierTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitAssignment(AssignmentTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitUnary(UnaryTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                return inspect(node);
            }
            @Override public Void visitNewClass(NewClassTree node, Void unused) {
                return inspect(node);
            }
        }.scan(path, null);
    }

    private boolean hasTreeKind(Tree tree, final String kind) {
        final boolean[] found = { false };
        new com.sun.source.util.TreeScanner<Void, Void>() {
            @Override public Void scan(Tree candidate, Void unused) {
                if (candidate != null && kind.equals(candidate.getKind().name())) { found[0] = true; }
                return found[0] ? null : super.scan(candidate, unused);
            }
        }.scan(tree, null);
        return found[0];
    }

    private boolean onlySnapshots(ValueFacts value) {
        for (Origin origin : value.origins) {
            if (origin.kind != Kind.SNAPSHOT && origin.kind != Kind.NULL) { return false; }
        }
        return !value.origins.isEmpty();
    }

    private boolean hasContainer(ValueFacts value) {
        for (Origin origin : value.origins) {
            if (origin.type != null && model.isCollectionLike(origin.type)) { return true; }
        }
        return false;
    }

    private ValueFacts external(TypeMirror type, Tree at) {
        Kind kind = type != null && leaves.isProvenImmutable(type) ? Kind.VALUE : Kind.BORROWED;
        ValueFacts result = value(kind, type, null, "external:" + site(at));
        String description = "borrowed mutable state retained; external mutable container alias remains";
        Element source = element(at);
        if (source != null && source.getKind() == ElementKind.PARAMETER) {
            description += "; parameter is retained directly";
        } else if (source != null && source.getKind() == ElementKind.FIELD) {
            description += "; field-to-field collection aliasing does not establish ownership";
        } else if (at instanceof NewClassTree && type != null && model.isCollectionLike(type)) {
            description = type + " is not one of the supported exact fresh collection implementations";
        }
        for (Origin origin : result.origins) { origin.description = description; }
        return result;
    }

    private ValueFacts unknown(TypeMirror type, Tree at, String description) {
        ValueFacts value = value(Kind.UNKNOWN, type, null, site(at));
        for (Origin origin : value.origins) { origin.description = description + " in " + frame.context; }
        return value;
    }

    private ValueFacts value(Kind kind, TypeMirror type, Object initialization, String key) {
        String fullKey = kind + ":" + key + ":" + String.valueOf(type);
        Origin origin = sites.get(fullKey);
        if (origin == null) {
            origin = new Origin(kind, type, initialization);
            sites.put(fullKey, origin);
        }
        return ValueFacts.of(origin);
    }

    private String site(Tree tree) {
        TreePath path = path(tree);
        String source = path == null ? "" : path.getCompilationUnit().getSourceFile().toUri().toString();
        long position = path == null ? -1 : trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), tree);
        StringBuilder context = new StringBuilder();
        for (Tree callSite : callSites) { context.append(":").append(treeIds.get(callSite)); }
        return entry + ":" + source + ":" + position + context;
    }

    private TreePath path(Tree tree) { return tree == null ? null : paths.get(tree); }
    private Element element(Tree tree) { TreePath path = path(tree); return path == null ? null : trees.getElement(path); }
    private TypeMirror type(Tree tree) { TreePath path = path(tree); return path == null ? null : trees.getTypeMirror(path); }

    private void put(Element element, ValueFacts value) {
        frame.values.put(element, value); observe();
    }
    private void observe() { frame.observed = ValueFacts.join(frame.observed, frame.values); }
    private void merge(Frame left, Frame right) {
        frame = left.copy();
        frame.values = ValueFacts.join(left.values, right.values);
        frame.observed = ValueFacts.join(left.observed, right.observed);
        frame.returned = left.returned.union(right.returned);
        observe();
    }
    private Map<Element, ValueFacts> fieldValues(Map<Element, ValueFacts> values) {
        Map<Element, ValueFacts> result = new LinkedHashMap<Element, ValueFacts>();
        for (Map.Entry<Element, ValueFacts> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getKey().getKind() == ElementKind.FIELD) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
    private String fieldPath(Element field) {
        String local = owner.getSimpleName() + (field.getModifiers().contains(Modifier.STATIC) ? ".<static>." : ".") + field.getSimpleName();
        return incoming.isEmpty() ? local : incoming + " -> " + local;
    }
    private String describe(ExecutableElement method) {
        return method.getEnclosingElement().getSimpleName() + "." + method.getSimpleName() + "()";
    }
    private void fail(DiagnosticId id, String statePath, Tree tree, String reason) {
        TreePath path = path(tree);
        String message = reason + " in " + frame.context;
        String key = id + ":" + statePath + ":" + site(tree) + ":" + reason;
        if (reported.add(key)) {
            failures.add(ProofFailure.create(id, rootName, statePath, message, tree,
                    path == null ? outerPath.getCompilationUnit() : path.getCompilationUnit(), trees));
        }
    }
}
