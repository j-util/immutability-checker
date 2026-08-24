package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-root recursive proof graph and cycle state. */
final class RecursiveProofContext {

    private enum ProofState {
        UNSEEN,
        VISITING,
        PROVEN,
        FAILED
    }

    private enum StateKind {
        INSTANCE,
        STATIC
    }

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final ReferenceTypeProof referenceTypeProof;
    private final CollectionTypeModel collectionTypeModel;
    private final String rootName;
    private final List<ProofFailure> failures;
    private final Map<TypeElement, ProofNode> nodes = new LinkedHashMap<TypeElement, ProofNode>();

    RecursiveProofContext(
            Trees trees,
            Elements elements,
            Types types,
            ReferenceTypeProof referenceTypeProof,
            String rootName,
            List<ProofFailure> failures) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.referenceTypeProof = referenceTypeProof;
        this.collectionTypeModel = new CollectionTypeModel(elements, types);
        this.rootName = rootName;
        this.failures = failures;
    }

    void verify(TypeElement rootType, TreePath rootPath) {
        proveSourceType(rootType, rootPath, "", null);
        propagateFailures();
    }

    private void proveSourceType(
            TypeElement type,
            TreePath typePath,
            String incomingPath,
            DiagnosticId violationDiagnostic) {
        ProofNode node = node(type);
        if (node.state != ProofState.UNSEEN) {
            return;
        }

        node.state = ProofState.VISITING;
        if (!verifyNesting(type, typePath, incomingPath, violationDiagnostic)) {
            node.state = ProofState.FAILED;
            return;
        }

        boolean proven = verifySuperclass(
                node, type, typePath, incomingPath, violationDiagnostic);
        List<CollectionProof> collectionProofs = new ArrayList<CollectionProof>();
        for (StateField field : stateFields(type)) {
            if (!verifyField(
                    node,
                    type,
                    field,
                    typePath,
                    incomingPath,
                    violationDiagnostic,
                    collectionProofs)) {
                proven = false;
            }
        }

        int failureCountBeforeCollectionScan = failures.size();
        if (!collectionProofs.isEmpty()) {
            new CollectionOriginAnalyzer(
                    type,
                    rootName,
                    trees,
                    collectionTypeModel,
                    collectionProofs,
                    failures).analyze(typePath);
            new CollectionEffectScanner(
                    type,
                    rootName,
                    trees,
                    collectionTypeModel,
                    collectionProofs,
                    failures).scanEnclosingType(typePath);
        }
        if (failures.size() != failureCountBeforeCollectionScan) {
            proven = false;
        }

        int failureCountBeforeWriteScan = failures.size();
        new DirectFieldWriteScanner(
                type,
                rootName,
                incomingPath,
                trees,
                failures,
                effectiveDiagnostic(DiagnosticId.POST_FREEZE_WRITE, violationDiagnostic))
                .scanEnclosingType(typePath);
        if (failures.size() != failureCountBeforeWriteScan) {
            proven = false;
        }
        node.state = proven ? ProofState.PROVEN : ProofState.FAILED;
    }

    private boolean verifyNesting(
            TypeElement type,
            TreePath typePath,
            String incomingPath,
            DiagnosticId violationDiagnostic) {
        NestingKind nesting = type.getNestingKind();
        if ((nesting == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC))
                || nesting == NestingKind.LOCAL
                || nesting == NestingKind.ANONYMOUS) {
            failures.add(failure(
                    effectiveDiagnostic(DiagnosticId.ENCLOSING_STATE_UNPROVEN, violationDiagnostic),
                    appendPath(incomingPath, type.getSimpleName() + ".<enclosing>"),
                    "implicit or captured enclosing-instance state is not analyzed",
                    typePath.getLeaf(),
                    typePath.getCompilationUnit()));
            return false;
        }
        return true;
    }

    private boolean verifySuperclass(
            ProofNode node,
            TypeElement type,
            TreePath typePath,
            String incomingPath,
            DiagnosticId violationDiagnostic) {
        TypeMirror superclass = type.getSuperclass();
        TypeElement objectType = elements.getTypeElement("java.lang.Object");
        if (objectType != null
                && superclass.getKind() != TypeKind.NONE
                && types.isSameType(
                        types.erasure(superclass),
                        types.erasure(objectType.asType()))) {
            return true;
        }

        String superclassPath = appendPath(
                incomingPath,
                type.getSimpleName() + ".<superclass>");
        Element superclassElement = types.asElement(superclass);
        if (!(superclassElement instanceof TypeElement)) {
            failures.add(failure(
                    effectiveDiagnostic(DiagnosticId.INHERITED_STATE_UNPROVEN, violationDiagnostic),
                    superclassPath,
                    superclass + " -> inherited state and behavior cannot be established",
                    typePath.getLeaf(),
                    typePath.getCompilationUnit()));
            return false;
        }

        TypeElement superclassType = (TypeElement) superclassElement;
        TreePath superclassTreePath = trees.getPath(superclassType);
        if (superclassTreePath == null) {
            failures.add(failure(
                    effectiveDiagnostic(DiagnosticId.INHERITED_STATE_UNPROVEN, violationDiagnostic),
                    superclassPath,
                    superclass + " -> source is unavailable; inherited state and behavior cannot be established",
                    typePath.getLeaf(),
                    typePath.getCompilationUnit()));
            return false;
        }

        ProofNode superclassNode = node(superclassType);
        node.dependencies.add(superclassNode);
        proveSourceType(superclassType, superclassTreePath, superclassPath, violationDiagnostic);
        return superclassNode.state != ProofState.FAILED;
    }

    private boolean verifyField(
            ProofNode node,
            TypeElement owner,
            StateField stateField,
            TreePath ownerPath,
            String incomingPath,
            DiagnosticId violationDiagnostic,
            List<CollectionProof> collectionProofs) {
        VariableElement field = (VariableElement) stateField.element;
        TreePath fieldPath = trees.getPath(field);
        Tree tree = fieldPath == null ? ownerPath.getLeaf() : fieldPath.getLeaf();
        CompilationUnitTree unit = fieldPath == null
                ? ownerPath.getCompilationUnit()
                : fieldPath.getCompilationUnit();
        String retainedPath = appendPath(
                incomingPath,
                owner.getSimpleName()
                        + (stateField.kind == StateKind.STATIC ? ".<static>." : ".")
                        + field.getSimpleName());
        boolean proven = true;

        if (!field.getModifiers().contains(Modifier.PRIVATE)
                && !field.getModifiers().contains(Modifier.FINAL)) {
            failures.add(failure(
                    effectiveDiagnostic(DiagnosticId.EXTERNALLY_WRITABLE_FIELD, violationDiagnostic),
                    retainedPath,
                    stateField.kind == StateKind.STATIC
                            ? "non-private, non-final static field is directly writable after class initialization"
                            : "non-private, non-final instance field is directly writable after construction",
                    tree,
                    unit));
            proven = false;
        }

        CollectionTypeModel.Shape collectionShape = collectionTypeModel.shape(field.asType());
        if (collectionShape == CollectionTypeModel.Shape.UNSUPPORTED) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    retainedPath,
                    field.asType() + " -> declared collection type is outside the supported Collection, List, Set, and Map abstractions",
                    tree,
                    unit));
            return false;
        }
        if (collectionShape != CollectionTypeModel.Shape.NOT_COLLECTION) {
            if (field.getModifiers().contains(Modifier.FINAL)
                    && !field.getModifiers().contains(Modifier.PRIVATE)) {
                failures.add(failure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        retainedPath,
                        "non-private final collection field exposes mutation-capable retained state",
                        tree,
                        unit));
                proven = false;
            }
            CollectionProof collectionProof = new CollectionProof(
                    field,
                    collectionShape,
                    retainedPath,
                    stateField.kind == StateKind.STATIC,
                    tree,
                    unit,
                    effectiveDiagnostic(DiagnosticId.POST_FREEZE_WRITE, violationDiagnostic));
            collectionProofs.add(collectionProof);
            if (!proveCollectionArguments(
                    node, field.asType(), collectionProof, tree, unit)) {
                proven = false;
            }
            return proven;
        }

        if (!proveReference(
                node,
                field.asType(),
                retainedPath,
                tree,
                unit,
                violationDiagnostic)) {
            proven = false;
        }
        return proven;
    }

    private boolean proveCollectionArguments(
            ProofNode owner,
            TypeMirror collectionType,
            CollectionProof collectionProof,
            Tree tree,
            CompilationUnitTree unit) {
        List<? extends TypeMirror> arguments = collectionTypeModel.arguments(collectionType);
        int expected = collectionProof.getShape() == CollectionTypeModel.Shape.MAP ? 2 : 1;
        if (arguments.size() != expected) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    collectionProof.getPath(),
                    collectionType + " -> raw collection declarations are unsupported; exact generic roles are required",
                    tree,
                    unit));
            return false;
        }

        boolean proven = true;
        for (int index = 0; index < arguments.size(); index++) {
            TypeMirror argument = arguments.get(index);
            String role;
            if (collectionProof.getShape() == CollectionTypeModel.Shape.MAP) {
                role = index == 0 ? "key" : "value";
            } else {
                role = "element";
            }
            String argumentPath = appendPath(collectionProof.getPath(), role);
            if (argument.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) argument;
                failures.add(failure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        argumentPath,
                        wildcard + " -> wildcard collection arguments do not establish an exact runtime item type",
                        tree,
                        unit));
                proven = false;
            } else if (argument.getKind() == TypeKind.TYPEVAR) {
                TypeVariable variable = (TypeVariable) argument;
                failures.add(failure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        argumentPath,
                        variable + " -> unresolved collection type variables are unsupported",
                        tree,
                        unit));
                proven = false;
            } else if (collectionTypeModel.isCollectionLike(argument)) {
                failures.add(failure(
                        DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                        argumentPath,
                        argument + " -> collections nested inside collections are unsupported because inner-container provenance is not implemented",
                        tree,
                        unit));
                proven = false;
            } else if (!proveReference(
                    owner,
                    argument,
                    argumentPath,
                    tree,
                    unit,
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN)) {
                proven = false;
            }
        }
        return proven;
    }

    private boolean proveReference(
            ProofNode owner,
            TypeMirror referenceType,
            String retainedPath,
            Tree tree,
            CompilationUnitTree unit,
            DiagnosticId violationDiagnostic) {
        if (referenceTypeProof.isProvenImmutable(referenceType)) {
            return true;
        }
        if (referenceType.getKind() != TypeKind.DECLARED) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    retainedPath,
                    referenceType + " -> retained state of this type kind is outside the current recursive proof model; "
                            + "immutability cannot be established",
                    tree,
                    unit));
            return false;
        }

        Element referenceElement = types.asElement(referenceType);
        if (!(referenceElement instanceof TypeElement)) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    retainedPath,
                    referenceType + " -> declared type could not be resolved; immutability cannot be established",
                    tree,
                    unit));
            return false;
        }

        TypeElement referencedType = (TypeElement) referenceElement;
        String kindName = referencedType.getKind().name();
        if (referencedType.getKind() != ElementKind.CLASS) {
            String reason = "RECORD".equals(kindName)
                    ? referenceType + " -> referenced records are intentionally deferred to V2"
                    : referenceType + " -> unresolved runtime subtype analysis: declared "
                            + kindName.toLowerCase()
                            + " is not an exact ordinary-class runtime type";
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    retainedPath,
                    reason,
                    tree,
                    unit));
            return false;
        }

        if (!referencedType.getModifiers().contains(Modifier.FINAL)) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    retainedPath,
                    referenceType + " -> unresolved runtime subtype analysis: declared type is non-final and "
                            + "the exact runtime type cannot be established",
                    tree,
                    unit));
            return false;
        }

        TreePath referencedPath = trees.getPath(referencedType);
        if (referencedPath == null) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    retainedPath,
                    referenceType + " -> source is unavailable and no trusted semantic model exists; "
                            + "immutability cannot be established",
                    tree,
                    unit));
            return false;
        }

        ProofNode referencedNode = node(referencedType);
        owner.dependencies.add(referencedNode);
        proveSourceType(referencedType, referencedPath, retainedPath, violationDiagnostic);
        return referencedNode.state != ProofState.FAILED;
    }

    private static DiagnosticId effectiveDiagnostic(
            DiagnosticId normalDiagnostic,
            DiagnosticId violationDiagnostic) {
        return violationDiagnostic == null ? normalDiagnostic : violationDiagnostic;
    }

    private void propagateFailures() {
        boolean changed;
        do {
            changed = false;
            for (ProofNode node : nodes.values()) {
                if (node.state != ProofState.PROVEN) {
                    continue;
                }
                for (ProofNode dependency : node.dependencies) {
                    if (dependency.state == ProofState.FAILED) {
                        node.state = ProofState.FAILED;
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }

    private ProofNode node(TypeElement type) {
        ProofNode node = nodes.get(type);
        if (node == null) {
            node = new ProofNode();
            nodes.put(type, node);
        }
        return node;
    }

    private ProofFailure failure(
            DiagnosticId id,
            String path,
            String reason,
            Tree tree,
            CompilationUnitTree unit) {
        return ProofFailure.create(id, rootName, path, reason, tree, unit, trees);
    }

    private List<StateField> stateFields(TypeElement type) {
        List<StateField> fields = new ArrayList<StateField>();
        for (Element element : type.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD) {
                StateKind kind = element.getModifiers().contains(Modifier.STATIC)
                        ? StateKind.STATIC
                        : StateKind.INSTANCE;
                fields.add(new StateField(element, kind));
            }
        }
        Collections.sort(fields, new Comparator<StateField>() {
            @Override
            public int compare(StateField left, StateField right) {
                int positionComparison = Long.compare(
                        sourcePosition(left.element), sourcePosition(right.element));
                if (positionComparison != 0) {
                    return positionComparison;
                }
                return left.element.getSimpleName().toString()
                        .compareTo(right.element.getSimpleName().toString());
            }
        });
        return fields;
    }

    private long sourcePosition(Element element) {
        TreePath path = trees.getPath(element);
        if (path == null) {
            return Long.MAX_VALUE;
        }
        long position = trees.getSourcePositions().getStartPosition(
                path.getCompilationUnit(), path.getLeaf());
        return position < 0 ? Long.MAX_VALUE : position;
    }

    private static String appendPath(String prefix, CharSequence segment) {
        if (prefix.isEmpty()) {
            return segment.toString();
        }
        return prefix + " -> " + segment;
    }

    private static final class ProofNode {
        private ProofState state = ProofState.UNSEEN;
        private final Set<ProofNode> dependencies = new LinkedHashSet<ProofNode>();
    }

    private static final class StateField {
        private final Element element;
        private final StateKind kind;

        private StateField(Element element, StateKind kind) {
            this.element = element;
            this.kind = kind;
        }
    }
}
