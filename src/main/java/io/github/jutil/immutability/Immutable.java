package io.github.jutil.immutability;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requests compile-time verification that instances of the annotated type are
 * immutable after successful construction.
 *
 * <p>The annotation is only a verification request. Its presence is not proof:
 * the immutability processor must run and complete successfully. Verification
 * fails closed when the processor cannot prove a state or behavior safe.</p>
 *
 * <p>A successful verification concerns mutation of retained instance state
 * under the checker's supported ordinary-Java analysis model. It does not
 * establish safe publication under the Java Memory Model and does not imply
 * general method purity or thread safety.</p>
 *
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Immutable {
}
