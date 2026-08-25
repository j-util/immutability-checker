package io.github.jutil.immutability.internal.analysis;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    enum MutationArgumentRole {
        NONE,
        ELEMENT,
        KEY,
        VALUE,
        ELEMENT_SOURCE,
        MAP_SOURCE
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

    List<MutationArgumentRole> mutationArgumentRoles(ExecutableElement method) {
        if (method == null || !(method.getEnclosingElement() instanceof TypeElement)) {
            return Collections.emptyList();
        }
        TypeMirror owner = ((TypeElement) method.getEnclosingElement()).asType();
        boolean mapMethod = isAssignable(types.erasure(owner), mapErasure);
        boolean collectionMethod = isAssignable(types.erasure(owner), collectionErasure);
        String name = method.getSimpleName().toString();
        String parameters = erasedParameters(method);
        if (mapMethod) {
            if (("put".equals(name) || "putIfAbsent".equals(name))
                    && "java.lang.Object,java.lang.Object".equals(parameters)) {
                return Arrays.asList(MutationArgumentRole.KEY, MutationArgumentRole.VALUE);
            }
            if ("putAll".equals(name) && "java.util.Map".equals(parameters)) {
                return Collections.singletonList(MutationArgumentRole.MAP_SOURCE);
            }
            if ("replace".equals(name)
                    && "java.lang.Object,java.lang.Object".equals(parameters)) {
                return Arrays.asList(MutationArgumentRole.KEY, MutationArgumentRole.VALUE);
            }
            if ("replace".equals(name)
                    && "java.lang.Object,java.lang.Object,java.lang.Object".equals(parameters)) {
                return Arrays.asList(
                        MutationArgumentRole.KEY,
                        MutationArgumentRole.VALUE,
                        MutationArgumentRole.VALUE);
            }
        }
        if (collectionMethod) {
            if ("add".equals(name) && "java.lang.Object".equals(parameters)) {
                return Collections.singletonList(MutationArgumentRole.ELEMENT);
            }
            if ("add".equals(name) && "int,java.lang.Object".equals(parameters)) {
                return Arrays.asList(MutationArgumentRole.NONE, MutationArgumentRole.ELEMENT);
            }
            if ("addAll".equals(name) && "java.util.Collection".equals(parameters)) {
                return Collections.singletonList(MutationArgumentRole.ELEMENT_SOURCE);
            }
            if ("addAll".equals(name) && "int,java.util.Collection".equals(parameters)) {
                return Arrays.asList(MutationArgumentRole.NONE, MutationArgumentRole.ELEMENT_SOURCE);
            }
            if ("set".equals(name) && "int,java.lang.Object".equals(parameters)) {
                return Arrays.asList(MutationArgumentRole.NONE, MutationArgumentRole.ELEMENT);
            }
        }
        return Collections.emptyList();
    }

    int copySourceArgumentIndex(ExecutableElement constructor) {
        if (constructor == null || constructor.getParameters().size() != 1) {
            return -1;
        }
        TypeMirror parameter = types.erasure(constructor.getParameters().get(0).asType());
        return isAssignable(parameter, collectionErasure) || isAssignable(parameter, mapErasure)
                ? 0 : -1;
    }

    boolean hasExactRoleContract(TypeMirror candidate, CollectionProof proof) {
        return hasExactRoleContract(candidate, proof.getField().asType());
    }

    boolean hasExactRoleContract(TypeMirror candidate, TypeMirror retainedContract) {
        boolean mapRoles = isMapLike(retainedContract);
        List<? extends TypeMirror> candidateRoles = genericRoles(candidate, mapRoles);
        List<? extends TypeMirror> retainedRoles = genericRoles(retainedContract, mapRoles);
        int expectedRoles = mapRoles ? 2 : 1;
        if (retainedRoles.size() != expectedRoles
                || candidateRoles.size() != retainedRoles.size()) {
            return false;
        }
        for (int index = 0; index < retainedRoles.size(); index++) {
            if (!types.isSameType(candidateRoles.get(index), retainedRoles.get(index))) {
                return false;
            }
        }
        return true;
    }

    boolean hasCompleteRoleContract(TypeMirror type) {
        boolean mapRoles = isMapLike(type);
        return genericRoles(type, mapRoles).size() == (mapRoles ? 2 : 1);
    }

    boolean isCompatibleItem(TypeMirror candidate, CollectionProof proof, int roleIndex) {
        if (candidate == null || candidate.getKind() == TypeKind.ERROR) {
            return false;
        }
        List<? extends TypeMirror> retainedRoles = arguments(proof.getField().asType());
        if (roleIndex < 0 || roleIndex >= retainedRoles.size()) {
            return false;
        }
        if (candidate.getKind() == TypeKind.NULL) {
            return true;
        }
        TypeMirror assignableCandidate = candidate;
        if (candidate.getKind().isPrimitive()) {
            assignableCandidate = types.boxedClass((PrimitiveType) candidate).asType();
        }
        TypeMirror retainedRole = retainedRoles.get(roleIndex);
        return types.isAssignable(assignableCandidate, retainedRole);
    }

    TypeMirror retainedRole(CollectionProof proof, int roleIndex) {
        List<? extends TypeMirror> roles = arguments(proof.getField().asType());
        return roleIndex < 0 || roleIndex >= roles.size() ? null : roles.get(roleIndex);
    }

    private List<? extends TypeMirror> genericRoles(TypeMirror type, boolean mapRoles) {
        TypeMirror target = mapRoles ? mapErasure : collectionErasure;
        TypeMirror view = genericView(type, target, new LinkedHashSet<String>());
        if (view == null || view.getKind() != TypeKind.DECLARED) {
            return Collections.emptyList();
        }
        return ((DeclaredType) view).getTypeArguments();
    }

    private boolean isMapLike(TypeMirror type) {
        return type != null && type.getKind() == TypeKind.DECLARED
                && isAssignable(types.erasure(type), mapErasure);
    }

    private TypeMirror genericView(TypeMirror type, TypeMirror target, Set<String> visited) {
        if (type == null || type.getKind() != TypeKind.DECLARED || target == null) {
            return null;
        }
        String key = type.toString();
        if (!visited.add(key)) {
            return null;
        }
        if (types.isSameType(types.erasure(type), target)) {
            return type;
        }
        for (TypeMirror supertype : types.directSupertypes(type)) {
            TypeMirror view = genericView(supertype, target, visited);
            if (view != null) {
                return view;
            }
        }
        return null;
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
