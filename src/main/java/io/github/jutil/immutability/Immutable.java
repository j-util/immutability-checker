package io.github.jutil.immutability;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requests compile-time verification that the annotated type's retained
 * instance state and declared static class state are immutable after their
 * respective initialization boundaries.
 *
 * <p>The annotation is only a verification request. Its presence is not proof:
 * the immutability processor must run and complete successfully. Verification
 * fails closed when the processor cannot prove a state or behavior safe.</p>
 *
 * <p>Instance state may be initialized by instance field initializers,
 * instance initializer blocks, and constructors of the object being created;
 * it freezes after successful construction. Declared static state may be
 * initialized by static field initializers and static initializer blocks of
 * its declaring class; it freezes after successful class initialization.</p>
 *
 * <p>Verification includes declared static state of source classes and source
 * superclasses that participate recursively in the proof graph. It does not
 * analyze unrelated global state merely because a method mentions it.</p>
 *
 * <p>A successful verification does not establish safe publication under the
 * Java Memory Model and does not imply general method purity or thread safety.
 * It also does not protect against reflection, {@code Unsafe}, native code, or
 * other mechanisms outside the supported ordinary-Java analysis model.</p>
 *
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Immutable {
}
