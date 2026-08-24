package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

/** Per-field facts shared by collection ownership and effect analysis. */
final class CollectionProof {

    enum OriginContext {
        INSTANCE_FIELD_INITIALIZER,
        INSTANCE_INITIALIZER,
        CONSTRUCTOR,
        STATIC_FIELD_INITIALIZER,
        STATIC_INITIALIZER
    }

    private final VariableElement field;
    private final CollectionTypeModel.Shape shape;
    private final String path;
    private final boolean staticState;
    private final Tree fieldTree;
    private final CompilationUnitTree compilationUnit;
    private final DiagnosticId mutationDiagnostic;
    private Origin origin;

    CollectionProof(
            VariableElement field,
            CollectionTypeModel.Shape shape,
            String path,
            boolean staticState,
            Tree fieldTree,
            CompilationUnitTree compilationUnit,
            DiagnosticId mutationDiagnostic) {
        this.field = field;
        this.shape = shape;
        this.path = path;
        this.staticState = staticState;
        this.fieldTree = fieldTree;
        this.compilationUnit = compilationUnit;
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

    CompilationUnitTree getCompilationUnit() {
        return compilationUnit;
    }

    DiagnosticId getMutationDiagnostic() {
        return mutationDiagnostic;
    }

    Origin getOrigin() {
        return origin;
    }

    void setOrigin(Origin origin) {
        this.origin = origin;
    }

    static final class Origin {
        private final OriginContext context;
        private final long position;
        private final ExecutableElement executable;

        Origin(OriginContext context, long position, ExecutableElement executable) {
            this.context = context;
            this.position = position;
            this.executable = executable;
        }

        OriginContext getContext() {
            return context;
        }

        long getPosition() {
            return position;
        }

        ExecutableElement getExecutable() {
            return executable;
        }
    }
}
