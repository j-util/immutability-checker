package io.github.jutil.immutability.internal.processor;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.jutil.immutability.internal.analysis.DirectStateVerifier;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSR 269 entry point for the immutability checker.
 *
 * <p>This service provider is public only for processor discovery. It and all
 * types beneath {@code io.github.jutil.immutability.internal} are internal
 * implementation details, not supported public API.</p>
 */
/*
 * javac does not advertise annotations on local declarations in a round's
 * annotation set. The wildcard makes the provider run so the tree collector
 * can reject those declarations. Collection and verification still select
 * only IMMUTABLE_ANNOTATION, and unrelated annotation sets are never claimed.
 */
@SupportedAnnotationTypes("*")
public final class ImmutableProcessor extends AbstractProcessor {

    static final String IMMUTABLE_ANNOTATION = "io.github.jutil.immutability.Immutable";

    private final Set<String> processedTypes = new HashSet<String>();
    private final Map<String, TypeElement> pendingTypes =
            new LinkedHashMap<String, TypeElement>();
    private Trees trees;
    private DirectStateVerifier verifier;
    private boolean deferUntilAnalyze;
    private SourceSnapshots snapshots;
    private final Set<String> awaitingAnalysis = new LinkedHashSet<String>();

    /**
     * Creates the service provider used by JSR 269 processor discovery.
     */
    public ImmutableProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        try {
            trees = Trees.instance(processingEnvironment);
            deferUntilAnalyze = SourceVersion.latestSupported() == SourceVersion.RELEASE_8;
            if (deferUntilAnalyze) {
                snapshots = new SourceSnapshots(trees);
            }
            verifier = new DirectStateVerifier(
                    snapshots == null ? trees : snapshots,
                    processingEnvironment.getElementUtils(),
                    processingEnvironment.getTypeUtils(),
                    processingEnvironment.getMessager());
            if (deferUntilAnalyze) {
                JavacTask.instance(processingEnvironment).addTaskListener(new TaskListener() {
                    @Override
                    public void started(TaskEvent event) {
                        // Verification needs the completed ANALYZE event on javac 8.
                    }

                    @Override
                    public void finished(TaskEvent event) {
                        handleTaskFinished(event);
                    }
                });
            }
        } catch (IllegalArgumentException unavailable) {
            trees = null;
            verifier = null;
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
        if (roundEnvironment.processingOver()) {
            return claimsOnlyImmutable(annotations);
        }

        if (deferUntilAnalyze) {
            for (Element element : roundEnvironment.getRootElements()) {
                if (element instanceof TypeElement) {
                    awaitingAnalysis.add(displayName(topLevelType((TypeElement) element)));
                }
            }
        }
        List<TypeElement> annotatedTypes = collectAnnotatedTypes(roundEnvironment);
        Collections.sort(annotatedTypes, new Comparator<TypeElement>() {
            @Override
            public int compare(TypeElement left, TypeElement right) {
                return compareProcessingOrder(left, right);
            }
        });

        for (TypeElement type : annotatedTypes) {
            String key = processingKey(type);
            if (!processedTypes.add(key)) {
                continue;
            }
            if (verifier == null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "[IC000] Immutability verification failed for " + displayName(type)
                                + ": compiler tree analysis is unavailable; immutability cannot be established",
                        type);
                continue;
            }
            if (deferUntilAnalyze) {
                pendingTypes.put(key, type);
            } else {
                verify(type);
            }
        }
        return claimsOnlyImmutable(annotations);
    }

    private void handleTaskFinished(TaskEvent event) {
        if (!deferUntilAnalyze || pendingTypes.isEmpty()) {
            return;
        }
        if (event.getKind() == TaskEvent.Kind.ANALYZE && event.getTypeElement() != null) {
            TypeElement top = topLevelType(event.getTypeElement());
            snapshots.capture(top);
            Set<TypeElement> refreshed = new LinkedHashSet<TypeElement>();
            new AnnotatedTypeCollector(trees, refreshed).scan(new TreePath(event.getCompilationUnit()), null);
            for (TypeElement current : refreshed) {
                for (Map.Entry<String, TypeElement> pending : pendingTypes.entrySet()) {
                    if (processingKey(current).equals(pending.getKey())) {
                        pending.setValue(current);
                    }
                }
            }
            awaitingAnalysis.remove(displayName(top));
            if (awaitingAnalysis.isEmpty()) {
                for (TypeElement pending : pendingTypes.values()) {
                    verify(pending);
                }
                pendingTypes.clear();
            }
        }
    }

    private static TypeElement topLevelType(TypeElement type) {
        TypeElement topLevel = type;
        for (Element enclosing = type.getEnclosingElement(); enclosing != null;
                enclosing = enclosing.getEnclosingElement()) {
            if (enclosing instanceof TypeElement) {
                topLevel = (TypeElement) enclosing;
            }
        }
        return topLevel;
    }

    private void verify(TypeElement type) {
        try {
            verifier.verify(type);
        } catch (RuntimeException unsupportedConstruct) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "[IC000] Immutability verification failed for " + displayName(type)
                            + ": source construct could not be analyzed; immutability cannot be established ("
                            + unsupportedConstruct.getClass().getSimpleName() + ")",
                    type);
        }
    }

    private List<TypeElement> collectAnnotatedTypes(RoundEnvironment roundEnvironment) {
        Set<TypeElement> result = new LinkedHashSet<TypeElement>();
        TypeElement annotationType = processingEnv.getElementUtils().getTypeElement(IMMUTABLE_ANNOTATION);
        if (annotationType != null) {
            for (Element element : roundEnvironment.getElementsAnnotatedWith(annotationType)) {
                if (element instanceof TypeElement) {
                    result.add((TypeElement) element);
                }
            }
        }

        if (trees != null) {
            AnnotatedTypeCollector collector = new AnnotatedTypeCollector(trees, result);
            for (Element root : roundEnvironment.getRootElements()) {
                TreePath path = trees.getPath(root);
                if (path != null) {
                    collector.scan(path, null);
                }
            }
        }
        return new ArrayList<TypeElement>(result);
    }

    private String processingKey(TypeElement type) {
        if (trees == null) {
            return displayName(type);
        }
        TreePath path = trees.getPath(type);
        if (path == null) {
            return displayName(type);
        }
        String sourceName = sourceName(path.getCompilationUnit());
        long position = trees.getSourcePositions().getStartPosition(
                path.getCompilationUnit(), path.getLeaf());
        return sourceName + ":" + position + ":" + displayName(type);
    }

    private int compareProcessingOrder(TypeElement left, TypeElement right) {
        TreePath leftPath = trees.getPath(left);
        TreePath rightPath = trees.getPath(right);
        if (leftPath == null || rightPath == null) {
            return processingKey(left).compareTo(processingKey(right));
        }
        String leftSource = sourceName(leftPath.getCompilationUnit());
        String rightSource = sourceName(rightPath.getCompilationUnit());
        int sourceComparison = leftSource.compareTo(rightSource);
        if (sourceComparison != 0) {
            return sourceComparison;
        }
        long leftPosition = trees.getSourcePositions().getStartPosition(
                leftPath.getCompilationUnit(), leftPath.getLeaf());
        long rightPosition = trees.getSourcePositions().getStartPosition(
                rightPath.getCompilationUnit(), rightPath.getLeaf());
        int positionComparison = Long.compare(leftPosition, rightPosition);
        return positionComparison != 0
                ? positionComparison
                : displayName(left).compareTo(displayName(right));
    }

    private static String sourceName(CompilationUnitTree compilationUnit) {
        return compilationUnit == null || compilationUnit.getSourceFile() == null
                ? ""
                : compilationUnit.getSourceFile().toUri().toString();
    }

    private static String displayName(TypeElement type) {
        String qualifiedName = type.getQualifiedName().toString();
        return qualifiedName.isEmpty() ? type.getSimpleName().toString() : qualifiedName;
    }

    private static boolean claimsOnlyImmutable(Set<? extends TypeElement> annotations) {
        boolean immutablePresent = false;
        for (TypeElement annotation : annotations) {
            if (annotation.getQualifiedName().contentEquals(IMMUTABLE_ANNOTATION)) {
                immutablePresent = true;
            } else {
                return false;
            }
        }
        return immutablePresent;
    }

    private static final class AnnotatedTypeCollector extends TreePathScanner<Void, Void> {

        private final Trees trees;
        private final Set<TypeElement> result;

        private AnnotatedTypeCollector(Trees trees, Set<TypeElement> result) {
            this.trees = trees;
            this.result = result;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof TypeElement && hasImmutableAnnotation(element)) {
                result.add((TypeElement) element);
            }
            return super.visitClass(node, unused);
        }

        private static boolean hasImmutableAnnotation(Element element) {
            for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                Element annotationElement = annotation.getAnnotationType().asElement();
                if (annotationElement instanceof TypeElement
                        && ((TypeElement) annotationElement).getQualifiedName()
                        .contentEquals(IMMUTABLE_ANNOTATION)) {
                    return true;
                }
            }
            return false;
        }
    }
}
