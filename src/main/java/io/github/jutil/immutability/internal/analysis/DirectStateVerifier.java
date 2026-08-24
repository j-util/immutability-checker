package io.github.jutil.immutability.internal.analysis;

import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/** Coordinates recursive retained-state verification for annotated ordinary classes. */
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
            failures.add(ProofFailure.create(
                    DiagnosticId.ANALYSIS_UNAVAILABLE,
                    rootName,
                    rootType.getSimpleName().toString(),
                    "source tree is unavailable; immutability cannot be established",
                    null,
                    null,
                    trees));
            emit(failures);
            return;
        }

        String kindName = rootType.getKind().name();
        boolean record = "RECORD".equals(kindName);
        if (rootType.getKind() != ElementKind.CLASS) {
            String reason = record
                    ? "annotated records are intentionally deferred to V2"
                    : "annotated " + kindName.toLowerCase()
                            + " types are outside the current ordinary-class proof domain";
            failures.add(ProofFailure.create(
                    DiagnosticId.UNSUPPORTED_ANNOTATED_TYPE,
                    rootName,
                    rootType.getSimpleName().toString(),
                    reason,
                    rootPath.getLeaf(),
                    rootPath.getCompilationUnit(),
                    trees));
            emit(failures);
            return;
        }

        new RecursiveProofContext(
                trees,
                elements,
                types,
                referenceTypeProof,
                rootName,
                failures).verify(rootType, rootPath);
        emit(failures);
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
