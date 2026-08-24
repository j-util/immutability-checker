package io.github.jutil.immutability.internal.processor;

import com.sun.source.tree.ClassTree;
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
import java.util.LinkedHashSet;
import java.util.List;
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
    private Trees trees;
    private DirectStateVerifier verifier;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        try {
            trees = Trees.instance(processingEnvironment);
            verifier = new DirectStateVerifier(
                    trees,
                    processingEnvironment.getElementUtils(),
                    processingEnvironment.getTypeUtils(),
                    processingEnvironment.getMessager());
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

        List<TypeElement> annotatedTypes = collectAnnotatedTypes(roundEnvironment);
        Collections.sort(annotatedTypes, new Comparator<TypeElement>() {
            @Override
            public int compare(TypeElement left, TypeElement right) {
                return processingKey(left).compareTo(processingKey(right));
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
        return claimsOnlyImmutable(annotations);
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
        String sourceName = path.getCompilationUnit().getSourceFile() == null
                ? ""
                : path.getCompilationUnit().getSourceFile().toUri().toString();
        long position = trees.getSourcePositions().getStartPosition(
                path.getCompilationUnit(), path.getLeaf());
        return sourceName + ":" + position + ":" + displayName(type);
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
