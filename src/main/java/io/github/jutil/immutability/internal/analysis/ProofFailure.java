package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.Trees;

import java.util.Comparator;

final class ProofFailure {

    static final Comparator<ProofFailure> SOURCE_ORDER = new Comparator<ProofFailure>() {
        @Override
        public int compare(ProofFailure left, ProofFailure right) {
            int sourceComparison = left.sourceName.compareTo(right.sourceName);
            if (sourceComparison != 0) {
                return sourceComparison;
            }
            int positionComparison = Long.compare(left.position, right.position);
            if (positionComparison != 0) {
                return positionComparison;
            }
            return left.message.compareTo(right.message);
        }
    };

    private final String message;
    private final Tree tree;
    private final CompilationUnitTree compilationUnit;
    private final String sourceName;
    private final long position;

    private ProofFailure(
            String message,
            Tree tree,
            CompilationUnitTree compilationUnit,
            String sourceName,
            long position) {
        this.message = message;
        this.tree = tree;
        this.compilationUnit = compilationUnit;
        this.sourceName = sourceName;
        this.position = position;
    }

    static ProofFailure create(
            DiagnosticId id,
            String rootName,
            String path,
            String reason,
            Tree tree,
            CompilationUnitTree compilationUnit,
            Trees trees) {
        String sourceName = compilationUnit == null || compilationUnit.getSourceFile() == null
                ? ""
                : compilationUnit.getSourceFile().toUri().toString();
        long position = tree == null || compilationUnit == null
                ? Long.MAX_VALUE
                : trees.getSourcePositions().getStartPosition(compilationUnit, tree);
        if (position < 0) {
            position = Long.MAX_VALUE;
        }
        String message = id.prefix() + " Immutability verification failed for " + rootName
                + ": " + path + " -> " + reason;
        return new ProofFailure(message, tree, compilationUnit, sourceName, position);
    }

    String getMessage() {
        return message;
    }

    Tree getTree() {
        return tree;
    }

    CompilationUnitTree getCompilationUnit() {
        return compilationUnit;
    }
}
