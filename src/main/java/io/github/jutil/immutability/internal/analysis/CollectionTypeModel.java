package io.github.jutil.immutability.internal.analysis;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact, deliberately small semantic model for the V1 collection milestone. */
final class CollectionTypeModel {

    enum Shape {
        COLLECTION,
        LIST,
        SET,
        MAP,
        UNSUPPORTED,
        NOT_COLLECTION
    }

    enum Operation {
        READ,
        MUTATOR,
        CALLBACK_MUTATOR,
        VIEW_OR_ITERATOR,
        UNKNOWN
    }

    private static final Set<String> SUPPORTED_IMPLEMENTATIONS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "java.util.ArrayList",
                    "java.util.HashSet",
                    "java.util.LinkedHashSet",
                    "java.util.HashMap",
                    "java.util.LinkedHashMap")));

    private final Types types;
    private final TypeMirror collectionErasure;
    private final TypeMirror mapErasure;

    CollectionTypeModel(Elements elements, Types types) {
        this.types = types;
        this.collectionErasure = erasure(elements, types, "java.util.Collection");
        this.mapErasure = erasure(elements, types, "java.util.Map");
    }

    Shape shape(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return Shape.NOT_COLLECTION;
        }
        String name = qualifiedName(type);
        if ("java.util.Collection".equals(name)) {
            return Shape.COLLECTION;
        }
        if ("java.util.List".equals(name)) {
            return Shape.LIST;
        }
        if ("java.util.Set".equals(name)) {
            return Shape.SET;
        }
        if ("java.util.Map".equals(name)) {
            return Shape.MAP;
        }
        return isCollectionLike(type) ? Shape.UNSUPPORTED : Shape.NOT_COLLECTION;
    }

    List<? extends TypeMirror> arguments(TypeMirror type) {
        return ((DeclaredType) type).getTypeArguments();
    }

    boolean isCollectionLike(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeMirror erased = types.erasure(type);
        return isAssignable(erased, collectionErasure) || isAssignable(erased, mapErasure);
    }

    boolean isSupportedImplementation(TypeElement type) {
        return type != null && SUPPORTED_IMPLEMENTATIONS.contains(type.getQualifiedName().toString());
    }

    Operation operation(ExecutableElement method) {
        if (method == null || !(method.getEnclosingElement() instanceof TypeElement)) {
            return Operation.UNKNOWN;
        }
        TypeMirror owner = ((TypeElement) method.getEnclosingElement()).asType();
        boolean mapMethod = isAssignable(types.erasure(owner), mapErasure);
        boolean collectionMethod = isAssignable(types.erasure(owner), collectionErasure);
        if (!mapMethod && !collectionMethod) {
            return Operation.UNKNOWN;
        }

        String name = method.getSimpleName().toString();
        String parameters = erasedParameters(method);
        if (mapMethod) {
            return mapOperation(name, parameters);
        }
        return collectionOperation(name, parameters);
    }

    String signature(ExecutableElement method) {
        if (method == null) {
            return "unresolved method";
        }
        Element owner = method.getEnclosingElement();
        String ownerName = owner instanceof TypeElement
                ? ((TypeElement) owner).getQualifiedName().toString()
                : String.valueOf(owner);
        return ownerName + "." + method.getSimpleName() + "(" + erasedParameters(method) + ")";
    }

    private Operation mapOperation(String name, String parameters) {
        if (("size".equals(name) || "isEmpty".equals(name)) && parameters.isEmpty()) {
            return Operation.READ;
        }
        if (("containsKey".equals(name) || "containsValue".equals(name) || "get".equals(name))
                && "java.lang.Object".equals(parameters)) {
            return Operation.READ;
        }
        if ("getOrDefault".equals(name)
                && "java.lang.Object,java.lang.Object".equals(parameters)) {
            return Operation.READ;
        }
        if ("clear".equals(name) && parameters.isEmpty()) {
            return Operation.MUTATOR;
        }
        if (("put".equals(name) || "putIfAbsent".equals(name))
                && "java.lang.Object,java.lang.Object".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if ("putAll".equals(name) && "java.util.Map".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if ("remove".equals(name)
                && ("java.lang.Object".equals(parameters)
                || "java.lang.Object,java.lang.Object".equals(parameters))) {
            return Operation.MUTATOR;
        }
        if ("replace".equals(name)
                && ("java.lang.Object,java.lang.Object".equals(parameters)
                || "java.lang.Object,java.lang.Object,java.lang.Object".equals(parameters))) {
            return Operation.MUTATOR;
        }
        if (("replaceAll".equals(name)
                && "java.util.function.BiFunction".equals(parameters))
                || ("compute".equals(name)
                && "java.lang.Object,java.util.function.BiFunction".equals(parameters))
                || ("computeIfAbsent".equals(name)
                && "java.lang.Object,java.util.function.Function".equals(parameters))
                || ("computeIfPresent".equals(name)
                && "java.lang.Object,java.util.function.BiFunction".equals(parameters))
                || ("merge".equals(name)
                && "java.lang.Object,java.lang.Object,java.util.function.BiFunction".equals(parameters))) {
            return Operation.CALLBACK_MUTATOR;
        }
        if (("keySet".equals(name) || "values".equals(name) || "entrySet".equals(name))
                && parameters.isEmpty()) {
            return Operation.VIEW_OR_ITERATOR;
        }
        return Operation.UNKNOWN;
    }

    private Operation collectionOperation(String name, String parameters) {
        if (("size".equals(name) || "isEmpty".equals(name)) && parameters.isEmpty()) {
            return Operation.READ;
        }
        if (("contains".equals(name) || "indexOf".equals(name) || "lastIndexOf".equals(name))
                && "java.lang.Object".equals(parameters)) {
            return Operation.READ;
        }
        if ("containsAll".equals(name) && "java.util.Collection".equals(parameters)) {
            return Operation.READ;
        }
        if ("get".equals(name) && "int".equals(parameters)) {
            return Operation.READ;
        }
        if ("clear".equals(name) && parameters.isEmpty()) {
            return Operation.MUTATOR;
        }
        if (("add".equals(name) || "remove".equals(name))
                && "java.lang.Object".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if ("remove".equals(name) && "int".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if (("addAll".equals(name) || "removeAll".equals(name) || "retainAll".equals(name))
                && "java.util.Collection".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if ("add".equals(name) && "int,java.lang.Object".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if ("addAll".equals(name) && "int,java.util.Collection".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if ("set".equals(name) && "int,java.lang.Object".equals(parameters)) {
            return Operation.MUTATOR;
        }
        if (("removeIf".equals(name) && "java.util.function.Predicate".equals(parameters))
                || ("replaceAll".equals(name)
                && "java.util.function.UnaryOperator".equals(parameters))
                || ("sort".equals(name) && "java.util.Comparator".equals(parameters))) {
            return Operation.CALLBACK_MUTATOR;
        }
        if (("iterator".equals(name)
                || "stream".equals(name)
                || "parallelStream".equals(name)
                || "spliterator".equals(name)) && parameters.isEmpty()) {
            return Operation.VIEW_OR_ITERATOR;
        }
        if ("listIterator".equals(name)
                && (parameters.isEmpty() || "int".equals(parameters))) {
            return Operation.VIEW_OR_ITERATOR;
        }
        if ("subList".equals(name) && "int,int".equals(parameters)) {
            return Operation.VIEW_OR_ITERATOR;
        }
        return Operation.UNKNOWN;
    }

    private String erasedParameters(ExecutableElement method) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(types.erasure(method.getParameters().get(index).asType()));
        }
        return result.toString();
    }

    private boolean isAssignable(TypeMirror type, TypeMirror target) {
        return target != null && types.isAssignable(type, target);
    }

    private String qualifiedName(TypeMirror type) {
        Element element = types.asElement(type);
        return element instanceof TypeElement
                ? ((TypeElement) element).getQualifiedName().toString()
                : "";
    }

    private static TypeMirror erasure(Elements elements, Types types, String name) {
        TypeElement element = elements.getTypeElement(name);
        return element == null ? null : types.erasure(element.asType());
    }
}
