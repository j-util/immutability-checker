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
import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates the deliberately narrow direct-state proof implemented in Milestone 1. */
public final class DirectStateVerifier {

    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final Messager messager;
    private final ReferenceTypeProof referenceTypeProof;

    /**
     * Creates an internal verifier for one processing environment.
     *
     * @param trees compiler tree access
     * @param elements language element utilities
     * @param types language type utilities
     * @param messager compiler diagnostic sink
     */
    public DirectStateVerifier(Trees trees, Elements elements, Types types, Messager messager) {
        this.trees = trees;
        this.elements = elements;
        this.types = types;
        this.messager = messager;
        this.referenceTypeProof = new KnownImmutableLeafProof(types);
    }

    /**
     * Verifies one annotated type and reports every deterministic proof failure.
     *
     * @param rootType annotated type
     */
    public void verify(TypeElement rootType) {
        List<ProofFailure> failures = new ArrayList<ProofFailure>();
        TreePath rootPath = trees.getPath(rootType);
        String rootName = displayName(rootType);
        if (rootPath == null) {
            failures.add(failure(
                    DiagnosticId.ANALYSIS_UNAVAILABLE,
                    rootName,
                    rootType.getSimpleName().toString(),
                    "source tree is unavailable; immutability cannot be established",
                    null,
                    null));
            emit(failures);
            return;
        }

        String kindName = rootType.getKind().name();
        boolean record = "RECORD".equals(kindName);
        if (rootType.getKind() != ElementKind.CLASS && !record) {
            failures.add(failure(
                    DiagnosticId.UNSUPPORTED_ANNOTATED_TYPE,
                    rootName,
                    rootType.getSimpleName().toString(),
                    "annotated " + kindName.toLowerCase()
                            + " types are outside the Milestone 1 proof domain",
                    rootPath.getLeaf(),
                    rootPath.getCompilationUnit()));
            emit(failures);
            return;
        }

        NestingKind nesting = rootType.getNestingKind();
        if ((nesting == NestingKind.MEMBER && !rootType.getModifiers().contains(Modifier.STATIC))
                || nesting == NestingKind.LOCAL
                || nesting == NestingKind.ANONYMOUS) {
            failures.add(failure(
                    DiagnosticId.ENCLOSING_STATE_UNPROVEN,
                    rootName,
                    rootType.getSimpleName().toString(),
                    "implicit or captured enclosing-instance state is not analyzed",
                    rootPath.getLeaf(),
                    rootPath.getCompilationUnit()));
            emit(failures);
            return;
        }

        TypeMirror superclass = rootType.getSuperclass();
        String expectedSuperclass = record ? "java.lang.Record" : "java.lang.Object";
        TypeElement expectedType = elements.getTypeElement(expectedSuperclass);
        if (superclass.getKind() == TypeKind.NONE
                || expectedType == null
                || !types.isSameType(types.erasure(superclass), types.erasure(expectedType.asType()))) {
            failures.add(failure(
                    DiagnosticId.INHERITED_STATE_UNPROVEN,
                    rootName,
                    rootType.getSimpleName() + ".<superclass>",
                    superclass + " -> inherited state and behavior are not analyzed",
                    rootPath.getLeaf(),
                    rootPath.getCompilationUnit()));
        }

        for (Element stateElement : retainedStateElements(rootType, record)) {
            verifyStateElement(rootType, rootName, stateElement, failures, rootPath);
        }

        new DirectFieldWriteScanner(rootType, rootName, trees, failures).scanEnclosingType(rootPath);
        failures.sort(ProofFailure.SOURCE_ORDER);
        emit(failures);
    }

    private void verifyStateElement(
            TypeElement rootType,
            String rootName,
            Element stateElement,
            List<ProofFailure> failures,
            TreePath rootPath) {
        TreePath statePath = trees.getPath(stateElement);
        Tree tree = statePath == null ? rootPath.getLeaf() : statePath.getLeaf();
        CompilationUnitTree unit = statePath == null
                ? rootPath.getCompilationUnit()
                : statePath.getCompilationUnit();
        String fieldPath = rootType.getSimpleName() + "." + stateElement.getSimpleName();

        if (stateElement.getKind() == ElementKind.FIELD
                && !stateElement.getModifiers().contains(Modifier.PRIVATE)
                && !stateElement.getModifiers().contains(Modifier.FINAL)) {
            failures.add(failure(
                    DiagnosticId.EXTERNALLY_WRITABLE_FIELD,
                    rootName,
                    fieldPath,
                    "non-private, non-final instance field is directly writable after construction",
                    tree,
                    unit));
        }

        if (!referenceTypeProof.isProvenImmutable(stateElement.asType())) {
            failures.add(failure(
                    DiagnosticId.REACHABLE_REFERENCE_UNPROVEN,
                    rootName,
                    fieldPath,
                    stateElement.asType()
                            + " -> reachable-reference verification is not implemented; immutability cannot be established",
                    tree,
                    unit));
        }
    }

    private static List<Element> retainedStateElements(TypeElement rootType, boolean record) {
        Map<String, Element> stateByName = new LinkedHashMap<String, Element>();
        for (Element element : rootType.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD
                    && !element.getModifiers().contains(Modifier.STATIC)) {
                stateByName.put(element.getSimpleName().toString(), element);
            }
        }
        if (record) {
            for (Element element : rootType.getEnclosedElements()) {
                if ("RECORD_COMPONENT".equals(element.getKind().name())
                        && !stateByName.containsKey(element.getSimpleName().toString())) {
                    stateByName.put(element.getSimpleName().toString(), element);
                }
            }
        }
        return new ArrayList<Element>(stateByName.values());
    }

    private ProofFailure failure(
            DiagnosticId id,
            String rootName,
            String path,
            String reason,
            Tree tree,
            CompilationUnitTree unit) {
        return ProofFailure.create(id, rootName, path, reason, tree, unit, trees);
    }

    private void emit(List<ProofFailure> failures) {
        failures.sort(ProofFailure.SOURCE_ORDER);
        for (ProofFailure failure : failures) {
            if (failure.getTree() != null && failure.getCompilationUnit() != null) {
                trees.printMessage(
                        Diagnostic.Kind.ERROR,
                        failure.getMessage(),
                        failure.getTree(),
                        failure.getCompilationUnit());
            } else {
                messager.printMessage(Diagnostic.Kind.ERROR, failure.getMessage());
            }
        }
    }

    private static String displayName(TypeElement type) {
        String qualifiedName = type.getQualifiedName().toString();
        return qualifiedName.isEmpty() ? type.getSimpleName().toString() : qualifiedName;
    }
}
