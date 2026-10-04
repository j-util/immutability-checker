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
 * <p>This annotation API does not bundle or discover the checker by itself.
 * Configure the matching {@code immutability-checker-processor} artifact on
 * the compiler's annotation-processor path. The annotation API and processor
 * versions must match.</p>
 *
 * <p>Version 0.2 is an unreleased technical preview under preparation for ordinary top-level and static
 * member classes. Its proof is sound only within the explicitly supported
 * domain described below; it is not the full intended 1.0.0 feature set.</p>
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
 * <p>The 0.2 technical-preview collection model supports fields declared as
 * {@link java.util.Collection}, {@link java.util.List}, {@link java.util.Set},
 * or {@link java.util.Map} when the retained container comes from a supported
 * fresh {@link java.util.ArrayList}, {@link java.util.HashSet},
 * {@link java.util.LinkedHashSet}, {@link java.util.HashMap}, or
 * {@link java.util.LinkedHashMap} allocation in the applicable initialization
 * phase, including supported source helpers. Copy constructors establish fresh
 * container ownership but do not copy their items. Collection elements, and map keys and values, are recursively
 * verified as part of the retained state graph.</p>
 *
 * <p>The resolved JDK {@code List.copyOf(Collection)} method establishes a
 * structurally unmodifiable snapshot; its elements are still proved recursively.
 * The result may be shared or returned directly, and need not be freshly
 * allocated. It preserves element references and does not transfer ownership
 * or freeze the input. Analyzed source requires Java 10 or newer APIs for this
 * factory; both checker artifacts remain usable on Java 8.</p>
 *
 * <p>Value origins are preserved through supported parameters, local aliases,
 * assignments, casts, returns and acyclic source calls. Supported targets are
 * static, private or final methods, or resolved methods on exact final receivers.
 * Private initialization helpers may assign or mutate owned state only when
 * post-freeze entry paths, nestmates and deferred execution cannot expose that
 * capability. Source helpers may reside in another compilation source file.</p>
 *
 * <p>Owned mutable containers permit supported construction-phase mutation and
 * non-retaining reads after freeze. Returning a supported independent shallow
 * copy is permitted when its items are safe; returning a mutable retained alias
 * is rejected. Modeled snapshot mutators that always throw do not establish a
 * successful mutation, but argument evaluation and other effects still matter.</p>
 *
 * <p>Records, arrays, nested containers, additional factory families and
 * collection implementations, arbitrary wrappers, unresolved raw or generic
 * flows, recursive call cycles, unresolved virtual dispatch, complex callbacks,
 * streams, iterators, views, bytecode proof and cross-module metadata remain
 * unsupported when relevant to verified state. Control-flow and exception joins
 * are conservative and may reject safe programs. Unknown state-relevant effects
 * fail closed. Unrelated argument mutation and local computation are permitted.</p>
 *
 * <p>A successful verification does not establish safe publication under the
 * Java Memory Model and does not imply general method purity or thread safety.
 * It also does not protect against reflection, {@code Unsafe},
 * {@code VarHandle}, field-writing {@code MethodHandle} operations, atomic
 * field updaters, JNI or other native code, instrumentation, hostile agents,
 * or other runtime-bypass mechanisms outside the supported ordinary-Java
 * analysis model.</p>
 *
 * @since 0.1
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Immutable {
}
