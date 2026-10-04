package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.Tree;

import javax.lang.model.element.VariableElement;

/** Per-field facts shared by collection ownership and effect analysis. */
final class CollectionProof {

    private final VariableElement field;
    private final CollectionTypeModel.Shape shape;
    private final String path;
    private final boolean staticState;
    private final Tree fieldTree;
    private final DiagnosticId mutationDiagnostic;

    CollectionProof(
            VariableElement field,
            CollectionTypeModel.Shape shape,
            String path,
            boolean staticState,
            Tree fieldTree,
            DiagnosticId mutationDiagnostic) {
        this.field = field;
        this.shape = shape;
        this.path = path;
        this.staticState = staticState;
        this.fieldTree = fieldTree;
        this.mutationDiagnostic = mutationDiagnostic;
    }

    VariableElement getField() {
        return field;
    }

    CollectionTypeModel.Shape getShape() {
        return shape;
    }

    String getPath() {
        return path;
    }

    boolean isStaticState() {
        return staticState;
    }

    Tree getFieldTree() {
        return fieldTree;
    }

    DiagnosticId getMutationDiagnostic() {
        return mutationDiagnostic;
    }

}
