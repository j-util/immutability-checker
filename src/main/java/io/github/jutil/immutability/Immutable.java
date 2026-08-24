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
 * <p>The current V1 collection model supports fields declared as
 * {@link java.util.Collection}, {@link java.util.List}, {@link java.util.Set},
 * or {@link java.util.Map} when the retained container comes directly from a
 * fresh {@link java.util.ArrayList}, {@link java.util.HashSet},
 * {@link java.util.LinkedHashSet}, {@link java.util.HashMap}, or
 * {@link java.util.LinkedHashMap} allocation in the applicable initialization
 * phase. Copy constructors establish fresh container ownership but do not copy
 * their items. Collection elements, and map keys and values, are recursively
 * verified as part of the retained state graph.</p>
 *
 * <p>Supported structural mutation is allowed only after fresh ownership has
 * been established and before the owning instance or class-state boundary
 * freezes. The collection-specific analysis rejects direct container aliases,
 * simple local-alias mutation after freeze, container returns, passing the
 * container to unmodeled code, mutation-capable non-private fields, and
 * iterator or view exposure. It models only an explicit set of collection read
 * and mutation signatures; unknown operations fail closed.</p>
 *
 * <p>Collections nested directly inside collections, raw or wildcard
 * collection arguments, unresolved collection type variables, custom or other
 * collection implementations, unmodifiable wrappers, callback-based
 * collection mutation, streams, spliterators, iterators, and collection views
 * are not proven by this milestone. Arrays, records, cross-module proof
 * metadata, and general interprocedural escape analysis also remain
 * unsupported.</p>
 *
 * <p>A successful verification does not establish safe publication under the
 * Java Memory Model and does not imply general method purity or thread safety.
 * It also does not protect against reflection, {@code Unsafe},
 * {@code VarHandle}, field-writing {@code MethodHandle} operations, atomic
 * field updaters, JNI or other native code, instrumentation, hostile agents,
 * or other runtime-bypass mechanisms outside the supported ordinary-Java
 * analysis model.</p>
 *
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Immutable {
}
