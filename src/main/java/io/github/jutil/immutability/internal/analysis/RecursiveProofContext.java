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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final ReferenceTypeProof referenceTypeProof;
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
        this.rootName = rootName;
        this.failures = failures;
    }

    void verify(TypeElement rootType, TreePath rootPath) {
        proveSourceType(rootType, rootPath, "");
        propagateFailures();
    }

    private void proveSourceType(TypeElement type, TreePath typePath, String incomingPath) {
        ProofNode node = node(type);
        if (node.state != ProofState.UNSEEN) {
            return;
        }

        node.state = ProofState.VISITING;
        if (!verifyNesting(type, typePath, incomingPath)) {
            node.state = ProofState.FAILED;
            return;
        }

        boolean proven = verifySuperclass(node, type, typePath, incomingPath);
        for (Element field : retainedFields(type)) {
            if (!verifyField(node, type, field, typePath, incomingPath)) {
                proven = false;
            }
        }

        int failureCountBeforeWriteScan = failures.size();
        new DirectFieldWriteScanner(type, rootName, incomingPath, trees, failures)
                .scanEnclosingType(typePath);
        if (failures.size() != failureCountBeforeWriteScan) {
            proven = false;
        }
        node.state = proven ? ProofState.PROVEN : ProofState.FAILED;
    }

    private boolean verifyNesting(TypeElement type, TreePath typePath, String incomingPath) {
        NestingKind nesting = type.getNestingKind();
        if ((nesting == NestingKind.MEMBER && !type.getModifiers().contains(Modifier.STATIC))
                || nesting == NestingKind.LOCAL
                || nesting == NestingKind.ANONYMOUS) {
            failures.add(failure(
                    DiagnosticId.ENCLOSING_STATE_UNPROVEN,
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
            String incomingPath) {
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
                    DiagnosticId.INHERITED_STATE_UNPROVEN,
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
                    DiagnosticId.INHERITED_STATE_UNPROVEN,
                    superclassPath,
                    superclass + " -> source is unavailable; inherited state and behavior cannot be established",
                    typePath.getLeaf(),
                    typePath.getCompilationUnit()));
            return false;
        }

        ProofNode superclassNode = node(superclassType);
        node.dependencies.add(superclassNode);
        proveSourceType(superclassType, superclassTreePath, superclassPath);
        return superclassNode.state != ProofState.FAILED;
    }

    private boolean verifyField(
            ProofNode node,
            TypeElement owner,
            Element field,
            TreePath ownerPath,
            String incomingPath) {
        TreePath fieldPath = trees.getPath(field);
        Tree tree = fieldPath == null ? ownerPath.getLeaf() : fieldPath.getLeaf();
        CompilationUnitTree unit = fieldPath == null
                ? ownerPath.getCompilationUnit()
                : fieldPath.getCompilationUnit();
        String retainedPath = appendPath(
                incomingPath,
                owner.getSimpleName() + "." + field.getSimpleName());
        boolean proven = true;

        if (!field.getModifiers().contains(Modifier.PRIVATE)
                && !field.getModifiers().contains(Modifier.FINAL)) {
            failures.add(failure(
                    DiagnosticId.EXTERNALLY_WRITABLE_FIELD,
                    retainedPath,
                    "non-private, non-final instance field is directly writable after construction",
                    tree,
                    unit));
            proven = false;
        }

        if (!proveReference(node, field.asType(), retainedPath, tree, unit)) {
            proven = false;
        }
        return proven;
    }

    private boolean proveReference(
            ProofNode owner,
            TypeMirror referenceType,
            String retainedPath,
            Tree tree,
            CompilationUnitTree unit) {
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
        proveSourceType(referencedType, referencedPath, retainedPath);
        return referencedNode.state != ProofState.FAILED;
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

    private List<Element> retainedFields(TypeElement type) {
        List<Element> fields = new ArrayList<Element>();
        for (Element element : type.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD
                    && !element.getModifiers().contains(Modifier.STATIC)) {
                fields.add(element);
            }
        }
        Collections.sort(fields, new Comparator<Element>() {
            @Override
            public int compare(Element left, Element right) {
                int positionComparison = Long.compare(sourcePosition(left), sourcePosition(right));
                if (positionComparison != 0) {
                    return positionComparison;
                }
                return left.getSimpleName().toString().compareTo(right.getSimpleName().toString());
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
}
