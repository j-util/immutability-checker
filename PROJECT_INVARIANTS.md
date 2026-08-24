## Immutability Checker — Project Invariants

> **MUST READ — READ-ONLY FOR AI AGENTS**
>
> This file defines the normative semantic contract of the project.
>
> Every AI agent working on this repository **MUST read this file completely before modifying code, tests, documentation, build configuration, diagnostics, or public API**.
>
> **AI agents MUST NOT modify, rewrite, reorganize, delete, weaken, or “improve” this file.**
>
> `PROJECT_INVARIANTS.md` is read-only for AI agents. Only the human maintainer may change it.
>
> If an AI agent believes that an invariant is incorrect, ambiguous, incomplete, or incompatible with a requested change, it must report the conflict instead of editing this file.
>
> If implementation, tests, documentation, issues, comments, or existing behavior conflict with this file, **this file wins**.

---

## 1. Project Purpose

`immutability-checker` is a compile-time Java static-analysis library.

Its primary annotation is:

```java
@Immutable
```

The annotation requests verification that the annotated ordinary Java class satisfies this project’s immutability contract.

The annotation is a request for proof. Its presence is not proof by itself.

A type is considered verified only when:

1. the checker has actually run;
2. the analysis has completed successfully;
3. no relevant state or behavior remains unproven;
4. compilation has completed without an immutability diagnostic.

The checker must either:

```text
prove
```

or:

```text
reject
```

There is no intermediate result such as:

```text
probably immutable
```

---

## 2. Product Scope by Major Version

### V1: ordinary Java classes

V1 targets ordinary Java classes.

The V1 proof domain includes:

* instance state;
* declared static class state;
* recursively reachable custom objects;
* recursively reachable static objects;
* source superclasses;
* ownership;
* aliases;
* escapes;
* method effects;
* constructor and class-initializer reachability;
* supported containers and arrays;
* supported cross-module verification metadata;
* conservative polymorphic analysis.

V1 must not be released while its public documentation claims broader behavior than the implementation actually provides.

### V2: Java records

Records are intentionally deferred to V2.

An annotated record must fail with a clear unsupported-type diagnostic until record-specific verification is implemented.

Record support must account for:

* canonical constructors;
* compact constructors;
* generated component fields;
* generated component accessors;
* overridden accessors;
* automatic exposure of component references;
* record-specific ownership and escape behavior.

Adding records in V2 must not weaken the ordinary-class guarantee established by V1.

---

## 3. Verified State

For this project, verified state consists of two categories:

```text
instance state
+
class state
```

### 3.1 Instance state

Instance state consists of:

* every non-static field of the verified object;
* every object, array, container, element, key, value, or other state reachable from those fields;
* inherited instance state;
* state retained through aliases that participate in the object’s logical state.

### 3.2 Class state

Class state consists of:

* every declared static field of the verified class;
* every object, array, container, element, key, value, or other state reachable from those static fields;
* declared static state of recursively analyzed source classes;
* declared static state of recursively analyzed source superclasses.

Static fields are shared across instances, but they are still part of the verified type’s state under this project’s contract.

Therefore:

```text
@Immutable
```

does not mean only:

```text
instances cannot change
```

It also means:

```text
the verified class-state graph cannot change after class initialization
```

### 3.3 Proof graph

The complete proof graph may contain:

* instance-field edges;
* static-field edges;
* superclass edges;
* array-element edges;
* collection-element edges;
* map-key edges;
* map-value edges;
* ownership edges;
* alias edges;
* call edges;
* escape edges.

The graph may contain cycles.

It is not necessarily a tree.

---

## 4. Core Immutability Contract

A successful verification proves:

> After the applicable initialization boundary has completed successfully, no legal ordinary-Java execution path within the supported analysis model can mutate the verified state graph.

The checker verifies **mutation possibility**, not merely mutation currently observed in the repository.

This means that a mutation path is invalid even when no current caller uses it.

For example:

```java
void rename(String name) {
    this.name = name;
}
```

violates immutability even when `rename()` is never called anywhere in the current codebase.

The method creates a legal post-construction mutation path for present or future callers.

Similarly:

```java
List<String> values() {
    return values;
}
```

may violate immutability even when no current code mutates the returned list.

The returned reference exposes future mutation capability.

The checker must not certify code merely because current usage happens to be safe.

---

## 5. Freeze Boundaries

There are two distinct freeze boundaries.

### 5.1 Instance freeze boundary

Instance state is frozen after successful completion of construction of the verified object.

Conceptually:

```text
instance allocation
    ↓
field initialization
    ↓
instance initializers
    ↓
constructor chain
    ↓
successful construction completion
    ↓
INSTANCE STATE FROZEN
```

Mutation required to construct the instance may be valid before this boundary.

If construction throws, no successfully verified instance has been produced.

### 5.2 Class-state freeze boundary

Declared static class state is frozen after successful completion of class initialization.

Conceptually:

```text
JVM default static values
    ↓
static field initialization
    ↓
static initializer blocks
    ↓
successful <clinit> completion
    ↓
CLASS STATE FROZEN
```

Mutation required to construct class state may be valid before this boundary.

If class initialization fails, no successfully initialized class state has been produced.

### 5.3 Owned aggregate construction

A freshly allocated object that becomes exclusively owned by an instance-construction or class-initialization context may remain mutable until the owning aggregate’s freeze boundary.

For example:

```java
private static final List<String> VALUES;

static {
    VALUES = new ArrayList<>();
    VALUES.add("A");
    VALUES.add("B");
}
```

may eventually be valid if the checker proves that:

* the list is freshly allocated;
* class initialization owns it exclusively;
* no external mutable alias escapes;
* no post-class-initialization mutation path exists.

Likewise:

```java
Names(List<String> source) {
    values = new ArrayList<>();
    values.addAll(source);
}
```

may eventually be valid when the object under construction exclusively owns the list.

The constructor of the nested object completing does not necessarily freeze the nested object immediately when it is still being built as part of a larger exclusively owned aggregate.

### 5.4 Overlapping graphs

The same object may be reachable from multiple state roots.

The checker must not use a later initialization context to “thaw” state already frozen elsewhere.

If an instance references already initialized static state:

```java
this.shared = TYPE_STATIC_VALUE;
```

the instance constructor must not mutate that shared static object.

If a reference is shared across multiple owners, the checker must account for every alias and every applicable freeze boundary.

Unknown sharing must fail closed.

---

## 6. `final` Is Neither Required Nor Sufficient

A field is not required to be `final`.

This may be immutable:

```java
@Immutable
final class Currency {

    private String code;

    Currency(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }
}
```

The absence of `final` is not itself a violation when the checker proves that no post-freeze write is possible.

The same applies to static fields:

```java
@Immutable
final class Configuration {

    private static String mode;

    static {
        mode = "production";
    }
}
```

The absence of `final` is not itself a violation if `mode` cannot change after successful class initialization.

Conversely:

```java
private final List<String> values;
```

is not necessarily immutable.

`final` prevents reassignment of the reference. It does not prevent mutation of the referenced list.

Likewise:

```java
public static final List<String> VALUES = new ArrayList<>();
```

is not immutable merely because the static reference is final.

Therefore:

```text
final reference
≠ immutable reachable state
```

and:

```text
non-final field
≠ mutable state
```

Field modifiers are evidence used in a proof. They are not the definition of immutability.

---

## 7. Mutable Implementation Types Are Allowed

A mutable Java type must not be rejected solely because its class provides mutating operations.

This may eventually pass:

```java
@Immutable
final class Names {

    private List<String> values;

    Names(List<String> source) {
        values = new ArrayList<>(source);
    }

    int size() {
        return values.size();
    }

    String get(int index) {
        return values.get(index);
    }
}
```

`ArrayList` is mutable as a type.

The relevant question is whether this particular retained list instance:

* is owned safely;
* has no unsafe external alias;
* is not mutated after the instance freeze boundary;
* does not escape in mutation-capable form.

The same principle applies to static class state:

```java
@Immutable
final class Registry {

    private static List<String> names;

    static {
        names = new ArrayList<>();
        names.add("main");
    }
}
```

A mutable implementation object can belong to immutable class state if the checker proves that the object is safely constructed, owned, frozen, and not exposed.

The project must distinguish:

```text
type can mutate
```

from:

```text
this retained object can mutate after its applicable freeze boundary
```

This distinction is fundamental.

---

## 8. Recursive Verification

The checker must recursively analyze the complete supported state graph.

Example:

```text
Order
 ├── instance: customer
 │    └── Customer.address
 │         └── Address.country
 │              └── Country.code
 │
 └── static: DEFAULT_POLICY
      └── Policy.rules
           └── rule elements
```

Referenced classes do not need their own `@Immutable` annotation.

The annotation on the root requests verification of the complete reachable graph.

Annotation presence on a referenced class must never be treated as proof by itself.

### 8.1 Cycles

Cycles are valid:

```text
A -> B -> C
     ^    |
     |____|
```

Static cycles are also valid:

```text
A.<static>.B_VALUE -> B
B.<static>.A_VALUE -> A
```

The checker must be cycle-safe and deterministic.

It must use explicit proof states or equivalent semantics, such as:

```text
UNSEEN
VISITING
PROVEN
FAILED
```

A cycle must not cause infinite recursion.

A cycle containing a real violation must still fail and report the violation.

---

## 9. Instance and Static Field Rules

### 9.1 Instance fields

A non-private, non-final instance field is externally writable and must fail unless a stronger language rule makes external mutation impossible and the checker explicitly proves that fact.

Example:

```java
@Immutable
final class Value {
    public int value;
}
```

must fail because external code can execute:

```java
instance.value = 10;
```

### 9.2 Static fields

A non-private, non-final static field is externally writable after class initialization and must fail.

Example:

```java
@Immutable
final class Configuration {
    public static String mode;
}
```

must fail because external code can execute:

```java
Configuration.mode = "test";
```

The same applies to:

* protected static fields;
* package-private static fields;
* public static fields.

### 9.3 Public final fields

A public final field may pass only if the complete reachable state behind it is proven immutable.

This may pass:

```java
public static final int VERSION = 1;
```

This may fail:

```java
public static final List<String> VALUES = new ArrayList<>();
```

because external code can mutate the retained list.

### 9.4 Static state of recursive classes

When a source class participates in the recursive proof graph, its declared static state also participates.

Example:

```java
final class Address {

    private static int revision;
    private String city;

    static void incrementRevision() {
        revision++;
    }
}

@Immutable
final class Person {
    private final Address address;
}
```

`Person` must fail because verification of `Address` includes `Address.revision`, and `incrementRevision()` creates a post-class-initialization mutation path.

Static state is not ignored merely because the root reaches the class through an instance field.

---

## 10. Initialization-Phase Mutation

### 10.1 Direct instance initialization

Direct mutation of instance state may be allowed in:

* instance field initializers;
* instance initializer blocks;
* constructors of the object being constructed.

Example:

```java
@Immutable
final class Value {

    private int value;

    Value(int value) {
        this.value = value;
    }
}
```

### 10.2 Direct static initialization

Direct mutation of declared static state may be allowed in:

* static field initializers;
* static initializer blocks of the declaring class.

Example:

```java
@Immutable
final class Configuration {

    private static int version;

    static {
        version = 1;
    }
}
```

### 10.3 Constructor-only helpers

A mutating helper may be valid when the checker proves that it is reachable only during construction.

Example:

```java
Value(int value) {
    initialize(value);
}

private void initialize(int value) {
    this.value = value;
}
```

This may pass only when the checker proves that `initialize()` cannot be reached after construction.

The checker must not merely scan for assignments and reject the helper without considering reachability once call-graph support exists.

If constructor-only reachability cannot be proven, verification must fail closed.

### 10.4 Class-initializer-only helpers

The same principle applies to static class initialization:

```java
static {
    initialize();
}

private static void initialize() {
    version = 1;
}
```

This may pass only when the checker proves that `initialize()` is reachable exclusively during class initialization.

If the method can be called after class initialization, or if exclusivity cannot be proven, verification must fail.

### 10.5 Deferred execution

Code declared during initialization is not automatically initialization-phase execution.

Example:

```java
static {
    Runnable deferred = () -> version++;
}
```

The lambda may execute after class initialization and must therefore fail unless the checker proves that it is invoked completely during initialization and cannot escape.

Likewise:

```java
Value() {
    Runnable deferred = () -> value++;
}
```

must not be accepted merely because the lambda was created in a constructor.

Execution timing matters, not lexical location alone.

---

## 11. Post-Freeze Mutation Is Forbidden

After the applicable freeze boundary, mutation of verified state is forbidden.

### 11.1 Direct instance mutation

This must fail:

```java
void increment() {
    value++;
}
```

### 11.2 Direct static mutation

This must fail:

```java
static void incrementVersion() {
    version++;
}
```

It must also fail when performed from an instance method:

```java
void incrementVersion() {
    version++;
}
```

or from a constructor:

```java
Value() {
    version++;
}
```

Class initialization occurs before ordinary object construction. A constructor must not mutate already frozen class state.

### 11.3 Reachable-object mutation

This must fail:

```java
void add(String value) {
    values.add(value);
}
```

The field reference did not change, but reachable retained state changed.

### 11.4 Static reachable-object mutation

This must fail:

```java
static void addDefault(String value) {
    DEFAULT_VALUES.add(value);
}
```

The static reference may be final, but its retained object changed after class initialization.

### 11.5 Lazy caches

Lazy initialization, memoization, counters, statistics, and caches are post-freeze mutation.

This fails:

```java
String value() {
    if (cached == null) {
        cached = calculate();
    }
    return cached;
}
```

This also fails for static caches:

```java
static String value() {
    if (cached == null) {
        cached = calculate();
    }
    return cached;
}
```

Whether the mutation is externally observable is irrelevant under the strict user-class contract.

The state changed after its freeze boundary.

---

## 12. Explicit Semantic Leaf Models

Selected platform types may be treated as atomic immutable semantic leaves.

Examples may include:

```text
String
Boolean
Integer
Long
UUID
```

A semantic leaf model means:

* the checker trusts the type according to an explicit project-defined rule;
* the checker does not recursively inspect that type’s internal implementation;
* internal platform caches may be abstracted away by that semantic model;
* the model must be deterministic and documented.

This exception applies only to explicitly modeled types.

It does not permit lazy caches or internal mutation in ordinary user classes annotated or recursively verified by this project.

The checker must not trust a type merely because it is:

* final;
* in `java.*`;
* documented as immutable;
* named like a value type;
* annotated by an unrelated library.

Subclassable types require special care because a field declared as that type may contain a mutable subtype.

Unknown polymorphism must fail closed.

---

## 13. Ownership

Mutable retained objects require an ownership proof.

### 13.1 Constructor aliases

This must not pass merely because the owner does not mutate the field:

```java
Names(List<String> values) {
    this.values = values;
}
```

The caller retains an alias:

```java
List<String> input = new ArrayList<>();
Names names = new Names(input);
input.add("A");
```

The retained state changes after construction.

### 13.2 Static aliases

Static class state has the same ownership requirement.

This may be unsafe:

```java
private static final List<String> VALUES =
        ExternalRegistry.obtainMutableList();
```

If another party retains a mutable alias, class state can change after class initialization.

The checker must prove ownership or immutability of the returned value.

### 13.3 Fresh allocation

Fresh allocation may establish ownership:

```java
this.values = new ArrayList<>();
```

or:

```java
static {
    values = new ArrayList<>();
}
```

Freshness alone is not sufficient.

The checker must also prove that the object does not escape and is not shared unsafely.

### 13.4 Ownership transfer

Ownership may be transferred into an instance or class-state graph only when the checker can prove that no external mutation-capable alias remains.

Unknown ownership transfer must fail closed.

---

## 14. Defensive Copying

Defensive copying can establish ownership.

Example:

```java
Names(List<String> source) {
    values = new ArrayList<>(source);
}
```

The new container may be privately owned.

However, copying must be analyzed recursively.

For:

```java
List<Person>
```

this:

```java
new ArrayList<>(people)
```

copies the container but preserves aliases to the same `Person` elements.

Therefore:

```text
container copy
≠ deep object-graph copy
```

The checker must verify:

* container ownership;
* element immutability or element ownership;
* nested container ownership;
* key and value ownership for maps;
* any remaining aliases.

The same rule applies to static state created through static field initializers or static initializer blocks.

---

## 15. Containers, Arrays, and Elements

Arrays and collections are mutable implementation structures.

They are not automatically forbidden.

### 15.1 Arrays

This may eventually pass:

```java
@Immutable
final class Bytes {

    private byte[] values;

    Bytes(byte[] source) {
        values = source.clone();
    }

    byte valueAt(int index) {
        return values[index];
    }
}
```

It may pass only when:

* the array is owned;
* no external alias remains;
* the array is not modified after construction;
* the array does not escape.

Static arrays follow the same rule, using the class-initialization freeze boundary.

### 15.2 Lists of known leaves

A retained `List<String>` may pass when:

* the list container is owned;
* no mutation path exists after freeze;
* no mutable view or iterator escapes;
* all elements are proven leaves.

### 15.3 Lists of custom objects

A retained `List<Line>` may pass only when:

* the list container is owned or otherwise immutable;
* each retained `Line` is recursively proven immutable or safely owned;
* mutable element aliases do not remain outside;
* no list or element reference escapes unsafely;
* no post-freeze mutator can alter the list or its elements.

### 15.4 Maps

For maps, both keys and values participate in the state graph.

The checker must analyze:

* map ownership;
* key immutability;
* value immutability;
* entry views;
* key-set views;
* values views;
* iterators;
* replacement and removal operations.

### 15.5 Streams and views

A stream is not automatically safe.

A stream, iterator, spliterator, sublist, map view, array slice, or wrapper may retain access to mutable state.

The checker must reason about whether the returned object provides mutation capability or leaks retained references.

---

## 16. Reference Escape

A mutable retained reference must not escape in mutation-capable form.

This fails:

```java
List<String> values() {
    return values;
}
```

The same applies to static state:

```java
static List<String> values() {
    return VALUES;
}
```

### 16.1 Escape is broader than getters

Method names have no semantic importance.

All of these may expose state:

```java
List<String> values()
Collection<String> view()
Iterator<String> iterator()
Spliterator<String> spliterator()
Stream<String> stream()
Object state()
void consume(Consumer<List<String>> consumer)
```

The checker must analyze reference flow and capabilities, not naming conventions.

### 16.2 Public fields

A public final field can also expose mutable retained state:

```java
public final List<String> values;
```

or:

```java
public static final List<String> VALUES;
```

Field exposure is an escape path.

### 16.3 Returning copies

Returning a fresh independent copy may be safe:

```java
List<String> values() {
    return new ArrayList<>(values);
}
```

The returned object may be mutable.

That alone is irrelevant if mutating it cannot affect retained verified state.

The checker must distinguish:

```text
mutable returned value
```

from:

```text
mutable alias into verified state
```

---

## 17. Method and Call Analysis

The checker must analyze behavior, not merely declarations.

Relevant executable behavior includes:

* constructors;
* static initializers;
* instance initializers;
* field initializers;
* public methods;
* protected methods;
* package-private methods;
* private methods;
* static methods;
* inherited methods;
* interface default methods;
* lambdas;
* anonymous and local executable bodies;
* nested classes;
* nestmate code;
* method references;
* callbacks;
* reachable external calls.

### 17.1 Publicly callable mutators

A public, protected, or package-visible mutator must fail even when no current call site exists.

It creates a legal future mutation path.

### 17.2 Private methods

Private mutating methods require reachability analysis.

A private method may be acceptable when proven reachable only during:

* instance construction; or
* class initialization.

If it is reachable from any post-freeze entry point, verification must fail.

### 17.3 Unreachable methods

The checker should not reject a private mutating method merely because it exists when it can prove the method is unreachable after the relevant freeze boundary.

However, if reachability cannot be established safely, verification must fail closed.

### 17.4 Calls into unknown code

If verified state or an alias to it is passed into unknown code, and the checker cannot prove that the call is read-only and non-escaping, verification must fail.

---

## 18. Mutation of Unrelated State

The checker is not a general side-effect checker.

Mutation of an unrelated object is not automatically an immutability violation.

Example:

```java
void copyInto(List<String> destination) {
    destination.add(value);
}
```

may be valid if `destination` is not part of the verified state graph and does not become an alias into it.

Likewise:

```java
String render() {
    StringBuilder builder = new StringBuilder();
    builder.append(value);
    return builder.toString();
}
```

may be valid because the temporary builder is unrelated local state.

### 18.1 Unrelated global state

The checker does not automatically verify every static field in the entire application.

Static fields are included when they are declared by classes participating in the proof graph.

Unrelated external class state is outside the graph unless:

* verified state references it;
* verified state aliases it;
* verified methods mutate it in a way that affects the verified graph;
* it is otherwise incorporated into the proof.

This project verifies the state of the annotated class and recursively participating classes, not the entire JVM.

---

## 19. Inheritance

Inherited state and behavior are part of the actual object.

A verified class cannot ignore:

* superclass instance fields;
* superclass static fields;
* superclass constructors;
* superclass instance methods;
* superclass static methods;
* inherited mutation paths.

Source-available superclasses must be recursively analyzed.

Unavailable or untrusted superclass behavior must fail closed.

`@Immutable` on a superclass does not automatically certify subclasses.

A subclass may add mutable state or behavior.

Each subtype requires its own valid proof when it wants the guarantee.

---

## 20. Polymorphism

Declared types do not necessarily identify exact runtime types.

Example:

```java
private Base state;
```

The field may contain a mutable subclass.

The checker must not certify the reference merely because `Base` appears immutable.

Safe proofs may eventually include:

* a final declared type;
* an exact fresh allocation;
* an exact constructor assignment;
* an exhaustively verified sealed hierarchy;
* trusted verification metadata;
* another explicit deterministic proof.

Interfaces and abstract types require conservative runtime-target analysis.

Unknown dynamic dispatch must fail closed when it can affect verified state.

---

## 21. External Dependencies and Module Boundaries

Source for an external dependency may be unavailable.

A method signature alone does not establish whether the implementation:

* mutates its receiver;
* mutates arguments;
* stores aliases;
* returns retained state;
* invokes callbacks;
* mutates static state.

Acceptable proof sources may include:

* source available to the current compiler analysis;
* explicit built-in semantic models;
* trusted project-generated verification metadata;
* supported bytecode analysis;
* other explicitly documented deterministic mechanisms.

The mere presence of an unrelated third-party `@Immutable` annotation is not proof.

### 21.1 Cross-module metadata

Before V1 is complete, the project should support trustworthy verification metadata for previously verified classes and class state.

That metadata must:

* identify the checker contract version;
* identify the verified type;
* distinguish successful verification from mere annotation presence;
* be deterministic;
* resist accidental stale reuse;
* preserve fail-closed behavior when incompatible or unavailable.

Unknown compiled classes must fail closed.

---

## 22. Unknown Means Unproven

This is a core safety invariant.

When analysis reaches state-relevant behavior it cannot understand sufficiently:

```text
unknown
```

must mean:

```text
immutability cannot be proven
```

and therefore:

```text
verification failure
```

It must never mean:

```text
assume safe
```

False rejection is preferable to falsely certifying mutable state as immutable.

Improving the checker should primarily reduce false rejections by adding stronger proofs.

It must not reduce the meaning of successful verification.

---


## 23. Supported Mutation Mechanisms

The V1 proof obligation includes mutation and escape mechanisms expressed through supported ordinary Java source constructs.

The checker must model or fail closed for supported operations such as:

* ordinary field assignments;
* compound assignments;
* increments and decrements;
* array writes once array support is implemented;
* collection and map mutators;
* iterator and mutable-view operations;
* aliases to verified state;
* returning verified state;
* passing verified state to ordinary method calls;
* ordinary method effects on verified state;
* mutations reachable through nested custom objects;
* mutations reachable through collection elements, map keys, and map values.

For mechanisms inside the supported proof model:

```text
not recognized
```

must never mean:

```text
safe
```

The checker must either model the relevant effect or reject the program as unproven.

Support may be introduced incrementally. Current-status documentation must identify which ordinary Java mechanisms are implemented and which remain fail-closed.

---

## 24. Deferred Low-Level, Reflective, and Runtime-Bypass Mechanisms

The initial V1 guarantee does not include mutation performed through low-level, reflective, native, or runtime-instrumentation mechanisms such as:

* Java reflection;
* `Unsafe`;
* `VarHandle`;
* field-writing `MethodHandle` operations;
* `AtomicIntegerFieldUpdater`;
* `AtomicLongFieldUpdater`;
* `AtomicReferenceFieldUpdater`;
* JNI or other native code;
* bytecode instrumentation;
* debugger memory modification;
* hostile JVM agents;
* unsupported serialization or deserialization bypasses.

The checker is not required to detect or reject these mechanisms in the initial V1 release.

Their effects are outside the verified guarantee even when such APIs appear explicitly in analyzed source.

Public documentation must state this boundary clearly and must not claim protection against these mechanisms.

Future versions may add explicit models or rejection rules for some deferred mechanisms. Such additions strengthen the proof capability but are not required for the initial V1 contract.

---

## 25. Immutability Is Not Purity

The checker verifies state immutability.

It does not generally prove mathematical purity.

An immutable object’s methods may:

* perform I/O;
* log;
* allocate temporary objects;
* read external state;
* mutate unrelated method arguments;
* call external services;
* produce different results based on external conditions.

Those behaviors may be undesirable for other reasons, but they are not automatically mutations of the verified state graph.

The central question remains:

> Can this execution mutate verified instance or class state?

---

## 26. Immutability Is Not Thread Safety

Successful verification does not automatically prove:

* safe publication of ordinary instances;
* absence of data races in unrelated state;
* atomicity of compound operations;
* method-level thread safety;
* lock correctness;
* linearizability;
* safe iteration;
* correct synchronization.

Because the project does not require instance fields to be final, it must not claim Java Memory Model guarantees that depend on final-field semantics.

Therefore:

```text
verified immutable
≠ automatically safely published
```

and:

```text
verified immutable
≠ general thread-safe design
```

Users remain responsible for correct publication and concurrency architecture.

---

## 27. Records Are Not Automatically Immutable

A record is not immutable merely because its component references are final.

Example:

```java
record Names(List<String> values) {}
```

The generated accessor exposes the list:

```java
names.values().add("Alice");
```

Therefore:

```text
record
≠ immutable
```

An ordinary class may encapsulate mutable implementation state more strongly than a record.

Records remain unsupported in V1 and must be handled explicitly in V2.

---

## 28. Diagnostics

Diagnostics are part of the product.

A failure should identify, where possible:

1. the annotated root type;
2. whether the path enters instance or static state;
3. the complete reference path;
4. the relevant method or initialization context;
5. the mutation or escape operation;
6. why proof failed.

Preferred instance path:

```text
Person.address
  -> Address.country
  -> Country.code
  -> write in Country.rename() occurs after construction
```

Preferred static path:

```text
Registry.<static>.METADATA
  -> Metadata.name
  -> write in Metadata.rename() occurs after class initialization
```

Preferred superclass path:

```text
Customer.<superclass>
  -> Party.<static>.revision
  -> write in Party.incrementRevision() occurs after class initialization
```

Diagnostics should distinguish:

```text
outside instance construction
```

from:

```text
after class initialization
```

Avoid generic messages such as:

```text
type is not immutable
```

when a precise proof path is available.

---

## 29. Diagnostic Path Conventions

Use consistent path notation.

Instance field:

```text
Type.field
```

Static field:

```text
Type.<static>.FIELD
```

Superclass edge:

```text
Child.<superclass> -> Parent.field
```

Static superclass state:

```text
Child.<superclass> -> Parent.<static>.FIELD
```

Container element:

```text
Order.lines -> element -> Line.price
```

Map key:

```text
Registry.entries -> key -> Key.id
```

Map value:

```text
Registry.entries -> value -> Entry.state
```

Path rendering must be deterministic.

---

## 30. Determinism

Given the same:

* source;
* dependencies;
* compiler version;
* checker version;
* configuration;

the verification result must not depend on:

* hash iteration order;
* traversal timing;
* machine-specific state;
* network access;
* nondeterministic heuristics;
* thread scheduling.

Diagnostics must have deterministic ordering.

Shared graph nodes may use a canonical deterministic path, but the checker must never lose the fact that a violation exists.

---

## 31. The Checker Must Not Modify User Code

`immutability-checker` is a checker.

It is not a source transformation framework.

It must not silently:

* add `final`;
* generate defensive copies;
* replace collections;
* rewrite methods;
* inject guards;
* generate accessors;
* change constructors;
* rewrite static initializers;
* alter bytecode to enforce immutability.

Its responsibility is:

```text
analyze
→ prove or reject
→ explain
```

not:

```text
rewrite code until it passes
```

---

## 32. No Runtime Enforcement Dependency

The guarantee is established at compile time.

The checker must not depend on:

* runtime agents;
* proxies;
* interception;
* mutation tracking;
* runtime guards;

to make a mutable object appear immutable.

Runtime support may be added only for a clearly separate purpose and must not replace compile-time verification.

---

## 33. Public API Discipline

The supported public API must remain minimal.

The primary supported API is:

```java
io.github.jutil.immutability.Immutable
```

Internal analysis types are not supported public API even when Java service-loading requirements make a processor class technically public.

Do not add:

* annotation properties;
* trust annotations;
* suppression annotations;
* `@Mutable`;
* ignore annotations;
* runtime configuration APIs;

without a concrete, reviewed semantic requirement.

An escape hatch must never silently weaken the meaning of successful `@Immutable` verification.

---

## 34. Documentation Accuracy

README, Javadocs, Maven metadata, website copy, changelog, release notes, examples, issues, and social descriptions must match the implementation.

Do not claim that the current implementation supports:

* ownership;
* collections;
* arrays;
* aliases;
* escape analysis;
* bytecode analysis;
* cross-module metadata;
* records;
* method effects;
* static state;

until those capabilities are actually implemented and verified.

Avoid unsupported claims such as:

```text
proves every Java object immutable
```

```text
guarantees thread safety
```

```text
guarantees safe publication
```

```text
analyzes arbitrary native or reflective behavior
```

The normative contract may describe the project’s target semantics, but current-status documentation must clearly state the implemented proof capability.

---

## 35. Evolution Rule

The checker’s analysis may become more precise.

Future versions may add:

* stronger ownership analysis;
* exact-allocation analysis;
* collection and array models;
* call graphs;
* escape analysis;
* bytecode analysis;
* proof metadata;
* record support;
* sealed-hierarchy analysis;
* richer diagnostics.

Such improvements may make previously rejected code pass.

They must not weaken the meaning of successful verification.

The invariant is:

```text
proof capability may grow
```

but:

```text
@Immutable must not gradually mean less
```

---

## 36. Tests Are Evidence, Not the Contract

Tests protect these invariants.

Tests do not define them.

If a test contradicts this file, the test is wrong.

If implementation contradicts this file, the implementation is wrong.

If documentation contradicts this file, the documentation is wrong.

An AI agent must never resolve a conflict by changing this file.

It must:

```text
1. keep PROJECT_INVARIANTS.md unchanged;
2. report the conflict;
3. correct implementation, tests, or documentation as appropriate.
```

---

## 37. Required Test Categories

Significant semantic behavior requires focused positive and negative tests.

The suite must eventually cover at least:

### Instance state

* direct constructor writes;
* post-construction writes;
* constructor-only helpers;
* instance escape;
* instance ownership;
* defensive copies;
* nested custom objects;
* nested containers;
* cyclic graphs;
* inheritance;
* polymorphism.

### Static class state

* static field initialization;
* static initializer blocks;
* post-class-initialization writes;
* static-initializer-only helpers;
* static reference ownership;
* static escape;
* static containers;
* static nested objects;
* static cycles;
* static superclass state;
* recursively referenced class state.

### Boundaries

* unknown external types;
* generated sources;
* incremental compilation;
* cross-module metadata;
* deterministic diagnostics;
* packaged service discovery;
* supported JDK versions;
* class-file compatibility.

Every important rule should have:

```text
one case that must pass
+
one corresponding case that must fail
```

---

## 38. AI-Agent Compliance Rules

Every AI coding or reviewing agent must:

1. read this file completely before repository work;
2. treat it as normative;
3. leave it unchanged;
4. preserve fail-closed behavior;
5. distinguish instance state from class state;
6. apply the correct freeze boundary;
7. analyze recursively rather than using field-style heuristics;
8. avoid treating `final` as proof;
9. avoid treating annotation presence as proof;
10. avoid treating records as automatically immutable;
11. avoid treating static fields as irrelevant;
12. avoid treating no current caller as proof of safety;
13. avoid weakening diagnostics or tests to make code pass.

An AI agent must not:

* modify this file;
* remove inconvenient invariants;
* reinterpret unknown behavior as safe;
* ignore declared static state;
* ignore nested reachable state;
* assume collections are immutable because references are final;
* assume a defensive copy is deep;
* assume a method is read-only from its name;
* sacrifice correctness merely to increase acceptance rate.

If a requested implementation conflicts with this file, the agent must report the exact conflict to the human maintainer.

---

## 39. Core Pass Examples

### Ordinary class with non-final instance state

```java
@Immutable
final class Currency {

    private String code;

    Currency(String code) {
        this.code = code;
    }

    String code() {
        return code;
    }
}
```

### Class with non-final static state initialized once

```java
@Immutable
final class Configuration {

    private static String mode;

    static {
        mode = "production";
    }

    static String mode() {
        return mode;
    }
}
```

### Recursive custom object

```java
final class Address {

    private String city;

    Address(String city) {
        this.city = city;
    }
}

@Immutable
final class Person {

    private Address address;

    Person(Address address) {
        this.address = address;
    }
}
```

This may pass only when aliases and polymorphism are proven safe.

### Owned list of custom objects

```java
final class Line {

    private String product;

    Line(String product) {
        this.product = product;
    }
}

@Immutable
final class Order {

    private List<Line> lines;

    Order(List<Line> source) {
        this.lines = new ArrayList<>(source);
    }
}
```

This may pass only when:

* the list container is safely owned;
* each `Line` is immutable or safely owned;
* no unsafe aliases remain;
* no list or element escape exists;
* no post-freeze mutation path exists.

---

## 40. Core Failure Examples

### Post-construction instance mutation

```java
void rename(String code) {
    this.code = code;
}
```

### Post-class-initialization static mutation

```java
static void changeMode(String mode) {
    Configuration.mode = mode;
}
```

### External static writability

```java
public static String mode;
```

### Mutable static object exposed

```java
public static final List<String> VALUES = new ArrayList<>();
```

### Constructor alias retained

```java
Names(List<String> values) {
    this.values = values;
}
```

### Static external alias retained

```java
private static final List<String> VALUES =
        ExternalSource.mutableValues();
```

### Instance reference escapes

```java
List<String> values() {
    return values;
}
```

### Static reference escapes

```java
static List<String> values() {
    return VALUES;
}
```

### Deep nested mutation

```java
Person.address
    -> Address.country
    -> Country.rename(...)
```

### Static state in referenced class mutates

```java
Person.address
    -> Address.<static>.revision
    -> Address.incrementRevision()
```

---

## 41. Project Identity

The project’s identity is:

> A conservative compile-time Java immutability checker that proves that neither verified instance state nor verified declared static class state can mutate after their respective initialization boundaries.

The defining principles are:

```text
Do not ask whether the fields look immutable.

Prove that the state cannot mutate.
```

and:

```text
Do not check only the root object.

Follow the complete verified state graph.
```

and:

```text
Static class state is verified state.
```
