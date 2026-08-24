package io.github.jutil.immutability.internal.analysis;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class KnownImmutableLeafProof implements ReferenceTypeProof {

    private static final Set<String> KNOWN_LEAVES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "java.lang.Boolean",
                    "java.lang.Byte",
                    "java.lang.Short",
                    "java.lang.Integer",
                    "java.lang.Long",
                    "java.lang.Character",
                    "java.lang.Float",
                    "java.lang.Double",
                    "java.lang.String",
                    "java.math.BigInteger",
                    "java.math.BigDecimal",
                    "java.util.UUID")));

    private final Types types;

    KnownImmutableLeafProof(Types types) {
        this.types = types;
    }

    @Override
    public boolean isProvenImmutable(TypeMirror type) {
        TypeKind kind = type.getKind();
        if (kind.isPrimitive()) {
            return true;
        }
        if (kind != TypeKind.DECLARED) {
            return false;
        }
        Element element = types.asElement(type);
        return element instanceof TypeElement
                && KNOWN_LEAVES.contains(((TypeElement) element).getQualifiedName().toString());
    }
}
