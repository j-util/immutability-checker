package io.github.jutil.immutability.internal.analysis;

import javax.lang.model.type.TypeMirror;

interface ReferenceTypeProof {

    boolean isProvenImmutable(TypeMirror type);
}
