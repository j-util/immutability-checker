# Immutability Checker — Project Invariants

> **INTERNAL DEVELOPMENT GUARD — MUST READ — READ-ONLY FOR AI AGENTS**
>
> This file defines the normative engineering and semantic constraints of the
> `immutability-checker` project.
>
> Every AI agent working on this repository **MUST read this file completely
> before modifying code, tests, documentation, diagnostics, build configuration,
> packaging, publication configuration, or public API**.
>
> **AI agents MUST NOT modify, rewrite, reorganize, delete, weaken, or
> “improve” this file.**
>
> `PROJECT_INVARIANTS.md` is read-only for AI agents. Only the human maintainer
> may change it.
>
> If an AI agent believes an invariant is incorrect, ambiguous, incomplete, or
> incompatible with a requested change, it must report the exact conflict
> instead of editing this file.
>
> If implementation, tests, documentation, issues, comments, generated
> artifacts, or existing behavior conflict with this file, **this file wins**.

---

## 1. Status and Audience

This file is an internal development guard.

It is not:

- consumer documentation;
- supported public API;
- a runtime contract file;
- a Maven artifact;
- a file that consumers must read;
- a replacement for the README or Javadocs.

The public consumer contract is communicated through:

- `README.md`;
- the Javadocs of `@Immutable`;
- `CHANGELOG.md`;
- release notes;
- Maven metadata;
- compiler diagnostics.

Those public materials must accurately reflect the implementation and must not
contradict this file.

Because the repository is public, repository visitors may read this file.
Visibility in the source repository does not make it supported consumer API.

This file must remain at the repository root and must not be packaged in:

- the annotation API JAR;
- the processor JAR;
- either source JAR;
- either Javadoc JAR;
- generated Maven metadata.

The README does not need to direct ordinary consumers to this file.

---

## 2. Project Identity

`immutability-checker` is a compile-time Java static-analysis library.

Its consumer-facing annotation is:

```java
@Immutable
```

The annotation requests verification that a supported Java type satisfies this
project’s immutability contract.

The annotation does not make a type immutable by declaration.

A type is verified only when:

1. the processor has actually run;
2. all required analysis has completed;
3. no relevant state or behavior remains unproven;
4. compilation has completed without an immutability error.

The checker must produce one of two semantic outcomes:

```text
proved immutable within the supported analysis model
```

or:

```text
rejected because immutability was violated or could not be proven
```

There is no successful intermediate result such as:

```text
probably immutable
```

The defining principle is:

> Do not infer immutability from how a type looks. Prove that its verified state
> cannot legally mutate after its applicable freeze boundary.

---

## 3. Artifact Architecture

The project uses an unpublished build aggregator and two published artifacts.

### 3.1 Build aggregator

```text
io.github.j-util:immutability-checker-build
```

The build aggregator:

- has `pom` packaging;
- builds the complete reactor;
- is not a consumer dependency;
- must not be published as a normal Maven Central artifact;
- must not be required as a parent POM by independently published modules.

### 3.2 Annotation API artifact

```text
io.github.j-util:immutability-checker
```

This artifact is the annotation API.

Its supported Java API consists only of:

```java
io.github.jutil.immutability.Immutable
```

The API JAR must contain:

```text
io/github/jutil/immutability/Immutable.class
META-INF/LICENSE
standard Maven metadata
manifest metadata
```

It must not contain:

- the annotation processor;
- internal analysis classes;
- processor service registration;
- `com.sun.source` integration;
- `tools.jar` configuration;
- runtime implementation code;
- `AGENTS.md`;
- `PROJECT_INVARIANTS.md`.

Its automatic module name is:

```text
io.github.jutil.immutability
```

### 3.3 Processor artifact

```text
io.github.j-util:immutability-checker-processor
```

This artifact is the compile-time analysis engine.

It contains:

- the JSR 269 processor service provider;
- recursive proof logic;
- field-write analysis;
- ownership analysis;
- alias and escape analysis;
- collection and array semantic models;
- record analysis;
- diagnostics;
- other internal compiler integration.

It must contain:

```text
META-INF/services/javax.annotation.processing.Processor
```

The provider must be:

```text
io.github.jutil.immutability.internal.processor.ImmutableProcessor
```

The processor artifact must not contain a duplicate copy of:

```text
io/github/jutil/immutability/Immutable.class
```

Its automatic module name is:

```text
io.github.jutil.immutability.processor
```

Everything under:

```text
io.github.jutil.immutability.internal
```

is unsupported implementation detail, even when Java service loading requires a
class to be technically public.

### 3.4 Dependency direction

The required conceptual dependency direction is:

```text
consumer source
    -> annotation API

compiler annotation-processor path
    -> processor
```

The annotation API must never depend on the processor.

The processor may use the annotation API as a build or test dependency where
needed, but must not create a runtime dependency for consumer applications.

### 3.5 Consumer setup

Maven consumers use:

```text
immutability-checker
    -> provided or compile-only annotation API

immutability-checker-processor
    -> annotation processor path
```

Gradle consumers use:

```text
compileOnly
    -> immutability-checker

annotationProcessor
    -> immutability-checker-processor
```

Neither artifact is intended to be an application runtime dependency.

Annotation presence without processor execution is not verification.

### 3.6 Version alignment

The API and processor artifacts must use the same release version.

Documentation must show matching versions.

The project must not silently support mixed versions unless an explicit
compatibility policy is later defined.

---

## 4. Java Compatibility

The published artifacts must remain Java 8 class-file compatible unless the
human maintainer explicitly changes the minimum Java version.

The class-file major version must remain:

```text
52
```

The same processor artifact must:

```text
run on Java 8
    -> verify supported ordinary Java classes
    -> leave record-specific logic dormant

run on a record-capable JDK
    -> verify supported ordinary Java classes
    -> verify supported Java records
```

Record support must not require a separate artifact.

Java 8-compatible classes must not link directly to compiler model or tree APIs
that do not exist on Java 8, including direct references to types or constants
such as:

```text
RecordComponentElement
TypeElement.getRecordComponents()
ElementKind.RECORD
ElementKind.RECORD_COMPONENT
BindingPatternTree
SwitchExpressionTree
```

Newer language features may be detected through Java 8-compatible techniques,
including:

- tree or element kind names;
- ordinary Java 8 model interfaces;
- guarded reflection;
- isolated capability adapters.

The processor may use supported compiler-tree APIs:

```text
javax.annotation.processing
javax.lang.model
javax.tools
com.sun.source.tree
com.sun.source.util
```

It must not require consumers to export non-public `com.sun.tools.javac`
packages.

JDK 8 `tools.jar` support is a processor-build concern only.

`tools.jar` must never be:

- packaged;
- exposed by the annotation API;
- a runtime dependency;
- transitive to consumers.

---

## 5. Public API Discipline

The primary and only supported Java API is:

```java
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Immutable {
}
```

The annotation must remain:

- source-retained;
- type-targeted;
- non-inherited;
- free of runtime behavior;
- free of configuration properties unless a reviewed semantic necessity arises.

Do not add merely for convenience:

- `@Mutable`;
- ignore annotations;
- suppression annotations;
- trust annotations;
- ownership annotations;
- runtime enforcement APIs;
- configuration attributes on `@Immutable`.

A public escape hatch must never silently weaken the meaning of successful
verification.

Diagnostics should explain proof failures. Annotation properties should not be
used as substitutes for sound analysis.

---

## 6. Version and Release Scope

### 6.1 Technical-preview releases

Versions below `1.0.0` may expose a deliberately limited proof capability.

A technical preview may reject source that the intended final semantic model
would consider immutable.

Technical previews must:

- fail closed;
- document their implemented proof domain;
- document current limitations;
- avoid claiming the complete `1.0.0` feature set;
- preserve the meaning of successful verification.

### 6.2 V1 target

The intended `1.0.0` product covers:

- ordinary Java classes;
- Java records on record-capable compilers;
- instance state;
- declared static class state;
- record components;
- recursively reachable custom objects;
- supported arrays;
- supported collections and maps;
- nested supported containers;
- ownership;
- aliases;
- escapes;
- method effects;
- constructor and class-initializer reachability;
- inheritance;
- conservative polymorphism;
- supported cross-module proof metadata.

V1 does not need to support every JDK or third-party container implementation.

Every supported type family and operation must have explicit deterministic
semantics.

Unsupported state or behavior must fail closed.

### 6.3 Evolution rule

Analysis precision may improve.

A later release may prove source that an earlier release rejected.

A later release must not make successful `@Immutable` verification mean less.

The invariant is:

```text
the set of provable programs may grow
```

while:

```text
the immutability guarantee must not weaken
```

---

## 7. Core Immutability Contract

Successful verification proves:

> After the applicable initialization boundary has completed successfully, no
> legal execution path within the supported ordinary-Java analysis model can
> mutate the verified state graph.

The checker verifies **mutation possibility**, not only mutation currently
observed in the repository.

This fails even when no existing caller invokes it:

```java
void rename(String name) {
    this.name = name;
}
```

The method creates a legal future mutation path.

This may also fail even when no current caller mutates the returned value:

```java
List<String> values() {
    return values;
}
```

The returned alias creates legal future mutation capability.

The checker must not certify a type merely because current usage happens to be
safe.

The proof is open-world at externally callable boundaries.

---

## 8. Verified State

Verified state consists of:

```text
instance state
+
declared class state
```

### 8.1 Instance state

Instance state includes:

- every non-static field of an ordinary verified object;
- every component of a verified record;
- inherited instance state;
- every supported object reachable from those roots;
- arrays and array elements;
- collection containers and elements;
- map containers, keys, and values;
- nested views or wrappers when supported;
- aliases participating in the retained object graph.

### 8.2 Class state

Class state includes:

- every static field declared by the annotated root;
- every static field declared by a recursively participating class or record;
- static fields inherited through a participating source superclass;
- every supported object reachable from those static fields;
- static arrays;
- static containers;
- static keys, values, and elements.

Static fields are shared by every instance, but they are verified state under
this project’s contract.

Therefore:

```text
@Immutable
```

means both:

```text
verified instances cannot legally mutate after construction
```

and:

```text
verified declared class state cannot legally mutate after class initialization
```

### 8.3 Proof graph

The verified state graph may include:

- ordinary field edges;
- static field edges;
- record component edges;
- superclass edges;
- array-element edges;
- collection-element edges;
- map-key edges;
- map-value edges;
- ownership edges;
- alias edges;
- call edges;
- escape edges.

The graph may contain cycles.

It is not necessarily a tree.

---

## 9. Scope of Static-State Analysis

Every declared static field of every type participating in the recursive proof
must be considered.

Static state must not be ignored merely because the root reaches the declaring
type through an instance field or record component.

Example:

```java
final class Address {
    private static int revision;

    static void revise() {
        revision++;
    }
}

@Immutable
final class Person {
    private Address address;
}
```

`Person` must fail because recursive verification of `Address` includes
`Address.revision`.

The checker does not automatically verify every unrelated static field in the
entire application.

Unrelated global state enters the proof when it:

- is declared by a participating type;
- becomes reachable from verified state;
- aliases verified state;
- otherwise affects the verified state graph under a supported analysis rule.

The checker verifies the participating graph, not the entire JVM.

---

## 10. Freeze Boundaries

There are two principal freeze boundaries.

### 10.1 Instance freeze boundary

Instance state freezes after successful completion of construction.

Conceptually:

```text
allocation
    ↓
instance field initialization
    ↓
instance initializer blocks
    ↓
constructor chain
    ↓
successful construction completion
    ↓
INSTANCE STATE FROZEN
```

Mutation required to construct the object may be valid before this boundary.

### 10.2 Class-state freeze boundary

Declared class state freezes after successful class initialization.

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

### 10.3 Owned aggregate construction

A freshly allocated nested object, array, or container may remain mutable while
it is exclusively owned by a still-active construction context.

Example:

```java
private List<String> values = new ArrayList<>();

Names() {
    values.add("A");
    values.add("B");
}
```

The list may be mutated while the owning aggregate is under construction if
exclusive ownership and non-escape are proven.

The same applies during class initialization.

### 10.4 Overlapping state graphs

The same object may be reachable from several owners or roots.

A later construction context must not “thaw” state that has already frozen
elsewhere.

Example:

```java
this.shared = ExistingType.SHARED;
```

The new instance constructor must not mutate `SHARED` merely because the new
instance is under construction.

Unknown sharing must fail closed.

---

## 11. `final` Is Neither Required Nor Sufficient

A field is not required to be `final`.

This may pass:

```java
@Immutable
final class Currency {
    private String code;

    Currency(String code) {
        this.code = code;
    }
}
```

The absence of `final` does not create mutation when no legal post-freeze write
path exists.

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

Conversely:

```java
private final List<String> values;
```

does not prove immutability.

`final` prevents reassignment of the reference. It does not freeze the referenced
object.

Likewise:

```java
public static final List<String> VALUES = new ArrayList<>();
```

is not immutable merely because the reference is final.

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

Modifiers are proof evidence. They are not the definition of immutability.

---

## 12. External Field Writability

A non-private, non-final instance field normally fails because external code can
write it after construction.

This fails:

```java
@Immutable
final class Value {
    public int value;
}
```

A non-private, non-final static field normally fails because external code can
write it after class initialization.

This fails:

```java
@Immutable
final class Configuration {
    public static String mode;
}
```

The same principle applies to:

- public fields;
- protected fields;
- package-private fields.

A public or otherwise exposed final reference may pass only when its complete
reachable state is proven immutable.

This may pass:

```java
public static final int VERSION = 1;
```

This may fail:

```java
public final List<String> values = new ArrayList<>();
```

because external code can mutate the exposed list.

---

## 13. Mutable Implementation Types

A mutable type must not be rejected merely because its class exposes mutators.

This may pass:

```java
@Immutable
final class Names {
    private List<String> values;

    Names(List<String> source) {
        values = new ArrayList<>(source);
    }

    String get(int index) {
        return values.get(index);
    }
}
```

The relevant question is whether this particular retained list:

- is safely owned;
- has no unsafe mutable alias;
- cannot be structurally modified after freeze;
- does not escape in mutation-capable form;
- contains only recursively safe items.

The checker must distinguish:

```text
the type can mutate
```

from:

```text
this retained instance can legally mutate after freeze
```

That distinction is central to the project.

---

## 14. Recursive Verification

The annotation on a root requests verification of the complete supported
reachable graph.

Referenced source types do not need their own `@Immutable` annotation.

Example:

```text
Order.customer
    -> Customer.address
    -> Address.country
    -> Country.code
```

The checker must recursively inspect participating source classes and records.

Annotation presence on a referenced type is not proof.

### 14.1 Cycles

Cycles are valid:

```text
A -> B -> C
     ^    |
     |____|
```

The proof engine must be cycle-safe.

It must use explicit states or equivalent semantics, such as:

```text
UNSEEN
VISITING
PROVEN
FAILED
```

A back edge must terminate.

A cycle containing a real violation must still fail.

### 14.2 Shared types

A type may be reached through several paths.

The checker may use a deterministic canonical path for diagnostics, but it must
not lose a violation because a type was previously visited through another path.

---

## 15. Ordinary Java Classes

Ordinary classes may participate as:

- annotated roots;
- recursively referenced fields;
- array elements;
- collection elements;
- map keys;
- map values;
- source superclasses.

The checker must consider:

- declared instance fields;
- declared static fields;
- inherited state;
- constructor behavior;
- initializer behavior;
- methods capable of mutation or escape;
- nested and nestmate code with access to private state.

Ordinary non-static inner classes contain implicit enclosing-instance state.

They may pass only when that state is included and proven.

If enclosing or captured state cannot be analyzed, verification fails closed.

Local and anonymous classes require the same conservative treatment.

Static member classes do not have an enclosing-instance reference and may be
analyzed as ordinary types.

---

## 16. Java Records

Records are part of the intended V1 product on record-capable compilers.

Records are not automatically immutable.

This record is only shallowly final:

```java
record Names(List<String> values) {
}
```

The generated accessor exposes the retained list:

```java
names.values().add("Alice");
```

Therefore:

```text
record
≠ automatically immutable
```

### 16.1 Java 8 compatibility

The same Java 8-compatible processor artifact must support records when running
on a capable compiler.

On Java 8:

- record source cannot be declared;
- ordinary-class verification remains available;
- record-specific logic remains dormant.

Record support must not make the published processor unusable on Java 8.

### 16.2 Record components

Every record component is verified instance state.

The checker must recursively verify component types through the same graph model
used for ordinary fields.

Component paths use:

```text
RecordType.component
```

A component may be:

- a primitive;
- an explicit semantic leaf;
- an ordinary custom object;
- another record;
- an array;
- a supported collection;
- a supported map;
- another supported reachable structure.

A component element and its synthetic backing field must not be counted twice.

### 16.3 Exact runtime type

Records are final and therefore have an exact runtime type.

This removes subclass uncertainty for the record itself.

It does not prove:

- component immutability;
- component ownership;
- constructor safety;
- accessor safety;
- static-state safety.

### 16.4 Generated canonical constructor

A generated canonical constructor retains component arguments directly.

This may pass:

```java
@Immutable
record Identifier(String value) {
}
```

`String` is an explicit semantic leaf.

This fails for an externally owned mutable container:

```java
@Immutable
record Names(List<String> values) {
}
```

The generated constructor retains the caller’s mutable list alias.

### 16.5 Explicit canonical constructor

An explicit canonical constructor must be analyzed as source.

This is unsafe:

```java
record Names(List<String> values) {
    Names(List<String> values) {
        this.values = values;
    }
}
```

The caller retains a mutable alias.

This may establish ownership:

```java
record Names(List<String> values) {
    Names(List<String> values) {
        this.values = new ArrayList<>(values);
    }
}
```

The accessor must still be analyzed separately.

### 16.6 Compact constructors

A compact constructor implicitly assigns each final component parameter to its
component field after the constructor body.

The checker must model that implicit assignment.

This may establish fresh ownership:

```java
record Names(List<String> values) {
    Names {
        values = new ArrayList<>(values);
    }
}
```

The checker must prove:

- the final component value;
- fresh ownership where required;
- generic role preservation;
- absence of unsafe aliases;
- absence of escape;
- deterministic assignment on every path.

Conditional, ambiguous, raw, unchecked, helper-produced, or unproven component
origins fail closed.

### 16.7 Auxiliary constructors

Auxiliary record constructors delegate to canonical construction.

They may pass when their delegation and ownership effects are proven.

Unknown constructor delegation or ownership flow fails closed.

### 16.8 Generated accessors

A generated component accessor returns the exact retained component.

It is safe for a recursively immutable value.

It is unsafe for a retained mutable container, array, view, or other
mutation-capable object.

A generated mutable-component accessor is an escape path.

### 16.9 Explicit accessors

An explicit accessor is analyzed as ordinary source.

This fails:

```java
public List<String> values() {
    return values;
}
```

This may pass:

```java
public List<String> values() {
    return new ArrayList<>(values);
}
```

only when the returned object is a fresh independent copy whose nested retained
references are safe.

### 16.10 Generated record methods

Generated `equals`, `hashCode`, and `toString` may be handled by an explicit
language semantic model.

User-written overrides must be analyzed as ordinary methods.

The generated-method model must not hide mutation or escape introduced by
source code.

### 16.11 Record static state

Static fields declared by a record are verified under the same class-state
freeze boundary as ordinary classes.

### 16.12 Recursive records

Records may participate anywhere in the proof graph:

```text
ordinary class -> record
record -> ordinary class
record -> record
collection -> record element
map -> record key/value
array -> record element
```

Cycles must remain safe and deterministic.

---

## 17. Ownership

Mutable retained objects require an ownership proof.

### 17.1 External constructor aliases

This is unsafe:

```java
Names(List<String> values) {
    this.values = values;
}
```

The caller retains a mutable alias.

### 17.2 Static aliases

This may be unsafe:

```java
private static final List<String> VALUES =
        ExternalRegistry.mutableValues();
```

Another party may retain a mutable alias after class initialization.

### 17.3 Fresh allocation

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

Freshness is necessary but not always sufficient.

The checker must also prove:

- the object does not escape;
- no competing owner remains;
- every applicable initialization path is safe;
- nested retained state is safe.

### 17.4 Ownership transfer

Ownership may be transferred only when the checker proves that no external
mutation-capable alias remains.

Unknown ownership transfer fails closed.

### 17.5 Local aliases

A local alias to verified state carries the same ownership and freeze status as
the original reference.

Aliases must be conservatively joined across control flow.

A possible target must not be discarded merely because another syntactic branch
was scanned later.

---

## 18. Defensive Copying

Defensive copying may establish ownership.

Example:

```java
values = new ArrayList<>(source);
```

This creates a fresh container.

It does not necessarily create fresh elements.

For:

```java
List<Person>
```

this:

```java
new ArrayList<>(people)
```

preserves aliases to every `Person`.

Therefore:

```text
fresh container
≠ deep copy of the retained graph
```

The checker must verify:

- container ownership;
- element immutability or ownership;
- nested container ownership;
- map-key ownership;
- map-value ownership;
- remaining external aliases.

Returning a defensive copy is similarly safe only when it does not preserve
unsafe aliases into verified state.

---

## 19. Collections and Maps

A collection field is immutable only when both are proven:

```text
container safety
+
item safety
```

A map requires:

```text
container safety
+
key safety
+
value safety
```

### 19.1 Explicit semantic models

Collection behavior must be modeled by resolved type and method signature.

Do not classify operations by method name alone.

Every supported collection implementation must define:

- ownership origins;
- construction-phase mutators;
- post-freeze mutators;
- read operations;
- aliasing views;
- iterators;
- streams;
- callback effects;
- copy behavior;
- generic item roles.

Unsupported implementations and operations fail closed.

### 19.2 Mutable collection types

A mutable implementation such as `ArrayList` may participate in immutable state
when ownership and freezing are proven.

A field must not pass merely because:

- it is final;
- its declared type is an interface;
- it is wrapped in an unmodifiable-looking object;
- no current caller mutates it;
- an item type carries some unrelated immutability annotation.

### 19.3 Construction mutation

Supported mutation may occur before the applicable freeze boundary.

Example:

```java
private List<String> values = new ArrayList<>();

Names() {
    values.add("A");
}
```

The same principle applies during static initialization.

### 19.4 Post-freeze mutation

This fails:

```java
void add(String value) {
    values.add(value);
}
```

This also fails for static retained state after class initialization.

### 19.5 Elements, keys, and values

Every retained item participates in the recursive state graph.

Paths use:

```text
Order.lines -> element -> Line.price
Registry.entries -> key -> Key.id
Registry.entries -> value -> Entry.state
```

### 19.6 Nested containers

Nested containers are semantically valid only when ownership and safety are
proven at every level.

Example:

```java
List<List<Line>>
```

requires proof of:

- outer-list ownership;
- every inner-list ownership;
- every `Line`;
- every alias and escape at both container levels.

A current release may reject nested containers until that proof is implemented.

### 19.7 Raw and unchecked flows

Raw types, unchecked casts, heap-polluted aliases, and erased insertion paths
must not bypass item verification.

Visible raw or unchecked flows affecting retained state must be modeled or
rejected.

Separately compiled external heap pollution may remain outside what source-only
analysis can observe and must be described accurately in public limitations.

### 19.8 Views and traversal objects

Objects such as these may retain aliases:

```text
Iterator
ListIterator
Spliterator
Stream
subList
keySet
values
entrySet
```

They are safe only when their capabilities and escape behavior are explicitly
modeled.

Unknown view or traversal semantics fail closed.

### 19.9 Callbacks

Callback operations require proof of:

- mutation effects;
- retained arguments;
- returned aliases;
- deferred execution;
- callback escape.

Unknown callback effects fail closed.

---

## 20. Arrays

Arrays are mutable containers and must not be rejected merely because their
types are mutable.

### 20.1 Primitive arrays

This may pass:

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

The checker must prove:

- fresh array ownership;
- no retained external alias;
- no post-freeze array write;
- no mutation-capable escape.

### 20.2 Object arrays

For:

```java
Line[] lines
```

the checker must prove:

- array-container ownership;
- every retained `Line`;
- element aliases;
- array escape;
- element escape.

A shallow array clone does not clone its elements.

### 20.3 Array mutation

Array writes may occur during an applicable owned initialization phase.

Post-freeze writes fail:

```java
values[index] = newValue;
```

### 20.4 Array escape

Returning the retained array normally fails:

```java
byte[] values() {
    return values;
}
```

Returning a safe fresh copy may pass:

```java
byte[] values() {
    return values.clone();
}
```

### 20.5 Nested arrays and container combinations

Arrays may participate recursively:

```text
array of records
array of ordinary objects
list of arrays
array of lists
map values containing arrays
```

Every container level requires independent ownership and escape proof.

Current releases may reject shapes not yet modeled.

---

## 21. Reference Escape

A mutable retained reference must not escape in mutation-capable form.

Escape includes more than getters.

Possible escape paths include:

- direct returns;
- public or protected fields;
- unrelated field storage;
- static storage;
- array storage;
- constructor arguments;
- ordinary method arguments;
- callbacks;
- iterators;
- views;
- streams;
- spliterators;
- method references;
- lambdas;
- generated record accessors.

Method names have no semantic authority.

These signatures may all expose state:

```java
List<String> values()
Collection<String> view()
Iterator<String> iterator()
Object state()
void consume(Consumer<List<String>> consumer)
```

Returning a fresh independent copy is different from returning retained state.

The checker must distinguish:

```text
mutable returned object
```

from:

```text
mutable alias into verified state
```

---

## 22. Method and Call Analysis

The checker analyzes mutation capability, not merely field declarations.

Relevant execution includes:

- field initializers;
- initializer blocks;
- constructors;
- compact record constructors;
- canonical record constructors;
- public methods;
- protected methods;
- package-private methods;
- private methods;
- static methods;
- inherited methods;
- interface default methods;
- lambdas;
- method references;
- nested classes;
- local classes;
- anonymous classes;
- callbacks;
- generated language behavior.

### 22.1 Externally callable mutators

A legal externally callable post-freeze mutation path fails even when there is no
current call site.

### 22.2 Private helpers

A private mutating helper is not automatically invalid.

It may pass when the checker proves that it is reachable only during:

- instance construction; or
- class initialization.

If the helper can execute after freeze, it fails.

If reachability cannot be proven, it fails closed.

### 22.3 Constructor-only helpers

This may eventually pass:

```java
Value(int value) {
    initialize(value);
}

private void initialize(int value) {
    this.value = value;
}
```

only when `initialize` is proven unreachable after construction.

### 22.4 Class-initializer-only helpers

The same principle applies to static initialization helpers.

### 22.5 Deferred execution

Code created during initialization is not automatically initialization-phase
execution.

This fails unless complete execution and non-escape are proven:

```java
Value() {
    Runnable task = () -> value++;
}
```

Execution timing matters, not only lexical location.

### 22.6 Unknown calls

Passing verified state to unknown code fails when mutation, retention, or escape
cannot be excluded.

Method signatures and names do not prove effects.

---

## 23. Mutation of Unrelated State

The checker is not a general side-effect checker.

Mutation of unrelated local or argument state is not automatically an
immutability violation.

This may be valid:

```java
void copyInto(List<String> destination) {
    destination.add(value);
}
```

provided `destination` is not part of the verified graph and does not become an
alias into it.

This may also be valid:

```java
String render() {
    StringBuilder builder = new StringBuilder();
    builder.append(value);
    return builder.toString();
}
```

The central question is:

> Does this execution mutate or expose verified state?

---

## 24. Inheritance

Inherited state and behavior are part of the actual object.

Verification must account for:

- superclass instance fields;
- superclass static fields;
- superclass constructors;
- inherited instance methods;
- inherited static methods;
- inherited mutation and escape paths.

Unavailable or untrusted superclass behavior fails closed.

`@Immutable` on a superclass does not automatically certify its subclasses.

A subclass may introduce mutable state or behavior.

Every subtype claiming the guarantee requires an appropriate proof.

Records use the explicit language model for `java.lang.Record`; absence of
`java.lang.Record` source must not by itself cause failure.

---

## 25. Polymorphism

A declared type does not necessarily identify the runtime type.

Example:

```java
private Base state;
```

The field may contain a mutable subtype.

The checker must not certify it merely because `Base` appears immutable.

Safe runtime-type proofs may include:

- a final declared type;
- a final record type;
- an exact fresh allocation;
- a proven constructor assignment;
- an exhaustively verified sealed hierarchy;
- trusted verification metadata;
- another explicit deterministic model.

Unknown runtime subtype behavior fails closed.

`final` is useful evidence in this proof. It remains neither a complete
definition nor a general requirement for the root type.

---

## 26. Generics

Generic type arguments participate in the proof.

The checker must account for type substitution through:

- parameterized fields;
- generic custom classes;
- generic superclasses;
- record components;
- collection elements;
- map keys and values;
- nested generic structures.

Raw types, wildcards, type variables, captures, and intersections may pass only
when the relevant runtime and retained-state types are proven.

Unknown substitution fails closed.

The checker must not rely on compiler warnings alone to preserve immutability.

---

## 27. External Dependencies and Module Boundaries

Source code for dependency classes may be unavailable.

A signature does not prove whether an implementation:

- mutates its receiver;
- mutates arguments;
- stores aliases;
- returns retained state;
- invokes callbacks;
- mutates class state.

Acceptable proof sources may include:

- source available to the current compilation;
- explicit built-in semantic models;
- trusted checker-generated metadata;
- supported bytecode analysis;
- other reviewed deterministic mechanisms.

The presence of an unrelated third-party `@Immutable` annotation is not proof.

### 27.1 Cross-module verification metadata

V1 should support trustworthy metadata for classes and records previously
verified by this checker.

The metadata must:

- identify the verified type;
- identify the checker contract version;
- distinguish successful proof from annotation presence;
- identify relevant supported semantic capabilities;
- be deterministic;
- prevent incompatible or stale proof reuse;
- fail closed when absent, invalid, or incompatible.

The annotation API must remain independent of this metadata implementation.

### 27.2 Generated source and incremental compilation

Generated types and partial compilation may affect source availability.

The checker must either:

- delay verification until required source models are available;
- consume trusted proof metadata; or
- fail closed accurately.

It must not silently assume missing generated or incrementally compiled behavior
is safe.

---

## 28. Explicit Semantic Leaves

Selected platform types may be modeled as atomic immutable semantic leaves.

Examples may include:

```text
String
Boolean
Integer
Long
UUID
```

A semantic leaf model means:

- the type is explicitly named and reviewed;
- the checker does not recursively inspect implementation internals;
- internal platform caches may be abstracted away;
- subclassing and runtime-type behavior have been considered;
- the model is deterministic and documented.

This exception does not permit post-freeze caches in ordinary user classes.

The checker must not trust a type merely because it is:

- final;
- in `java.*`;
- documented as immutable;
- named like a value;
- a record;
- marked by an unrelated annotation.

Subclassable value classes require additional care because a declared field may
contain a mutable subtype.

---

## 29. Internal Caches and Lazy State

The ordinary user-type contract is strict.

This fails:

```java
String value() {
    if (cached == null) {
        cached = calculate();
    }
    return cached;
}
```

It also fails for static lazy caches after class initialization.

Whether the mutation is logically invisible is irrelevant.

The physical verified state changed after freeze.

Explicit semantic leaf models are the only deliberate abstraction over such
internal platform behavior.

---

## 30. Unknown Means Unproven

This is a core safety invariant.

When state-relevant behavior is unknown:

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

False rejection is preferable to false certification.

New features should reduce false rejections by adding stronger proofs, not by
weakening the guarantee.

---

## 31. Supported Ordinary Mutation Mechanisms

Within the supported ordinary-Java model, the checker must model or fail closed
for relevant mechanisms such as:

- field assignments;
- compound assignments;
- increments and decrements;
- array writes;
- collection and map mutators;
- aliases;
- returns;
- argument passing;
- field storage;
- array storage;
- callbacks;
- iterators and views;
- ordinary method effects;
- record constructor assignment;
- generated record accessor exposure;
- mutations through nested objects;
- mutations through container elements, keys, and values.

For mechanisms within the declared supported model:

```text
not recognized
```

must never mean:

```text
safe
```

---

## 32. Deferred Low-Level and Runtime-Bypass Mechanisms

The initial V1 guarantee does not include mutation performed through low-level,
reflective, native, or runtime-instrumentation mechanisms such as:

- Java reflection;
- `Unsafe`;
- `VarHandle`;
- field-writing `MethodHandle` operations;
- `AtomicIntegerFieldUpdater`;
- `AtomicLongFieldUpdater`;
- `AtomicReferenceFieldUpdater`;
- JNI or other native code;
- bytecode instrumentation;
- debugger memory modification;
- hostile JVM agents;
- unsupported serialization or deserialization bypasses.

The checker is not required to detect or reject these mechanisms in the initial
V1 release.

Their effects are outside the verified guarantee, even when such APIs appear
explicitly in analyzed source.

Public documentation must state this boundary accurately.

Future versions may add models or rejection rules for selected mechanisms.

---

## 33. Immutability Is Not Purity

Successful verification does not prove mathematical purity.

A verified method may:

- allocate temporary objects;
- log;
- perform I/O;
- read external state;
- call services;
- mutate unrelated arguments;
- produce different outputs from external conditions.

Those behaviors are not automatically mutations of verified state.

---

## 34. Immutability Is Not Thread Safety

Successful verification does not automatically prove:

- safe publication;
- absence of unrelated data races;
- atomicity;
- linearizability;
- lock correctness;
- method-level thread safety;
- safe iteration under concurrent access.

Because non-final fields may pass, the checker must not claim Java Memory Model
guarantees that depend on final-field semantics.

Therefore:

```text
verified immutable
≠ automatically safely published
```

and:

```text
verified immutable
≠ general thread safety
```

Users remain responsible for publication and concurrency architecture.

---

## 35. No Source or Bytecode Rewriting

The checker must not silently:

- add `final`;
- generate defensive copies;
- replace collections;
- rewrite accessors;
- change constructors;
- inject guards;
- rewrite static initializers;
- transform user bytecode;
- make invalid source valid.

Its responsibility is:

```text
analyze
→ prove or reject
→ explain
```

not:

```text
rewrite code until it satisfies the checker
```

---

## 36. No Runtime Enforcement Requirement

The guarantee is established at compile time.

The checker must not require:

- runtime agents;
- proxies;
- interception;
- runtime mutation tracking;
- runtime guards;
- generated runtime subclasses.

Runtime support may be introduced only for a separate reviewed purpose and must
not replace compile-time proof.

---

## 37. Diagnostics

Diagnostics are part of the product.

A useful diagnostic should identify, where possible:

1. the annotated root;
2. the complete reference path;
3. whether the path enters instance or static state;
4. the relevant executable or initialization context;
5. the mutation, alias, or escape;
6. why proof failed.

Preferred ordinary path:

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
  -> write occurs after class initialization
```

Preferred collection path:

```text
Order.lines
  -> element
  -> Line.price
  -> mutation in Line.changePrice()
```

Preferred record path:

```text
Order.customer
  -> CustomerRecord.address
  -> generated accessor exposes mutable retained state
```

Avoid generic messages such as:

```text
type is not immutable
```

when a precise proof path is available.

### 37.1 Path conventions

Use deterministic notation.

Instance field or record component:

```text
Type.field
Type.component
```

Static field:

```text
Type.<static>.FIELD
```

Superclass:

```text
Child.<superclass> -> Parent.field
```

Collection element:

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

Array element:

```text
Order.lines -> array-element -> Line.price
```

### 37.2 Diagnostic identifiers

Stable identifiers such as `IC000` through `IC006` may be maintained as product
behavior without becoming Java public API.

Identifiers must not be repurposed incompatibly without a reviewed decision.

---

## 38. Determinism

Given the same:

- source;
- dependency versions;
- checker version;
- compiler version;
- configuration;

the result must not depend on:

- hash iteration order;
- traversal timing;
- network access;
- machine-specific state;
- thread scheduling;
- nondeterministic heuristics.

Diagnostics must be emitted in deterministic order.

Analysis must not require network access.

---

## 39. Documentation Accuracy

README, Javadocs, Maven metadata, website copy, changelog, release notes, GitHub
description, issues, and examples must describe implemented behavior accurately.

The internal target contract may be broader than a technical preview.

Public documentation must clearly distinguish:

```text
the intended V1 semantic model
```

from:

```text
the capability implemented by the current release
```

Do not claim support for a feature until it is implemented and tested.

Do not claim:

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
analyzes arbitrary external bytecode
```

```text
protects against reflection or native mutation
```

`PROJECT_INVARIANTS.md` is not the primary consumer document and does not need a
prominent README link.

---

## 40. Packaging and Publication Invariants

Every published module must have complete Maven Central metadata.

Both published artifacts must include:

- project name;
- accurate description;
- project URL;
- organization;
- Apache 2.0 license metadata;
- developer metadata;
- issue management;
- SCM connection;
- SCM developer connection;
- SCM URL;
- exact release tag;
- UTF-8 source and reporting encodings;
- source JAR;
- Javadoc JAR;
- signatures for real publication;
- deterministic artifact contents where practical.

The API artifact must contain only its intended annotation class.

The processor artifact must contain the processor and internal engine but not a
duplicate API class.

Neither artifact may contain:

- tests;
- JUnit;
- Surefire or Failsafe internals;
- `tools.jar`;
- credentials;
- local filesystem paths;
- `AGENTS.md`;
- `PROJECT_INVARIANTS.md`.

No published artifact may depend on the unpublished build aggregator.

No application runtime dependency may be introduced without an explicit human
decision.

---

## 41. Test Requirements

Tests are evidence for these invariants.

Tests do not define the contract.

If a test conflicts with this file, the test is wrong.

If implementation conflicts with this file, the implementation is wrong.

If documentation conflicts with this file, the documentation is wrong.

Every significant semantic rule should have:

```text
a focused case that must pass
+
a corresponding focused case that must fail
```

### 41.1 Ordinary class tests

Include:

- non-final state that freezes correctly;
- direct mutation;
- recursive objects;
- cycles;
- inheritance;
- static state;
- enclosing and captured state;
- polymorphism;
- deterministic diagnostics.

### 41.2 Collection and map tests

Include:

- fresh ownership;
- copy constructors;
- external aliases;
- item/key/value recursion;
- construction mutation;
- post-freeze mutation;
- raw and unchecked flows;
- views;
- iterators;
- callbacks;
- control-flow joins;
- nested containers;
- static containers.

### 41.3 Array tests

Include:

- primitive arrays;
- object arrays;
- cloning;
- copying;
- element recursion;
- construction writes;
- post-freeze writes;
- direct escape;
- fresh-copy return;
- nested arrays and containers;
- static arrays.

### 41.4 Record tests

Include:

- generated canonical constructors;
- explicit canonical constructors;
- compact constructors;
- auxiliary constructors;
- primitive components;
- semantic-leaf components;
- ordinary custom-object components;
- record-to-record recursion;
- collection and array components;
- generated accessor exposure;
- defensive-copy accessors;
- generated method models;
- explicit overrides;
- static record state;
- Java 8 dormant behavior;
- record-capable compiler behavior.

### 41.5 Artifact-boundary tests

Verify:

- API JAR contains only `Immutable.class`;
- API JAR contains no processor service;
- processor JAR contains no duplicate annotation class;
- service discovery works from the processor JAR;
- processor discovery does not occur from the API JAR alone;
- API and processor versions match;
- both artifacts use class-file major version 52;
- neither JAR contains policy files;
- neither JAR has unintended runtime dependencies;
- external Maven consumers compile successfully.

### 41.6 Compiler matrix

The complete reactor must be tested on:

- Java 8;
- a record-capable LTS JDK when record support is implemented;
- the newest supported JDK.

---

## 42. AI-Agent Compliance

Every AI coding or reviewing agent must:

1. read this file completely;
2. leave it unchanged;
3. preserve fail-closed behavior;
4. distinguish instance state from class state;
5. apply the correct freeze boundary;
6. preserve the API/processor artifact split;
7. keep the supported public API minimal;
8. analyze recursively rather than using superficial style rules;
9. avoid treating `final` as proof;
10. avoid treating records as automatically immutable;
11. avoid treating annotation presence as proof;
12. avoid treating absent current callers as proof of safety;
13. avoid ignoring static state;
14. avoid assuming defensive copies are deep;
15. avoid assuming method names prove effects;
16. avoid increasing acceptance by weakening the guarantee.

An AI agent must not:

- modify this file;
- package this file;
- duplicate the annotation into the processor JAR;
- move processor implementation into the API JAR;
- introduce a runtime dependency without approval;
- silently assume unknown behavior is safe;
- weaken tests to make implementation pass;
- rewrite public claims beyond implemented capability.

When a requested change conflicts with this file, the agent must report the exact
conflict to the human maintainer.

---

## 43. Core Pass Examples

### 43.1 Ordinary class with non-final state

```java
@Immutable
final class Currency {
    private String code;

    Currency(String code) {
        this.code = code;
    }
}
```

### 43.2 Static state initialized once

```java
@Immutable
final class Configuration {
    private static String mode;

    static {
        mode = "production";
    }
}
```

### 43.3 Recursive custom object

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

This passes only when alias and runtime-type safety are proven.

### 43.4 Owned collection

```java
@Immutable
final class Names {
    private List<String> values;

    Names(List<String> source) {
        values = new ArrayList<>(source);
    }

    String get(int index) {
        return values.get(index);
    }
}
```

### 43.5 Immutable record value

```java
@Immutable
record Identifier(String value) {
}
```

### 43.6 Record with owned collection and defensive accessor

```java
@Immutable
record Names(List<String> values) {
    Names {
        values = new ArrayList<>(values);
    }

    @Override
    public List<String> values() {
        return new ArrayList<>(values);
    }
}
```

This passes only when constructor ownership, item proof, and accessor copying are
all established.

---

## 44. Core Failure Examples

### Post-construction mutation

```java
void rename(String code) {
    this.code = code;
}
```

### Post-class-initialization mutation

```java
static void changeMode(String mode) {
    Configuration.mode = mode;
}
```

### Externally writable static state

```java
public static String mode;
```

### Constructor alias retained

```java
Names(List<String> values) {
    this.values = values;
}
```

### Mutable collection escapes

```java
List<String> values() {
    return values;
}
```

### Mutable static collection escapes

```java
static List<String> values() {
    return VALUES;
}
```

### Mutable array escapes

```java
byte[] values() {
    return values;
}
```

### Generated record accessor exposes mutable state

```java
@Immutable
record Names(List<String> values) {
}
```

### Deep nested mutation

```text
Order.lines
    -> element
    -> Line.price
    -> mutation in Line.changePrice()
```

### Static state of a recursively referenced type mutates

```text
Person.address
    -> Address.<static>.revision
    -> mutation in Address.revise()
```

---

## 45. Final Project Identity

The project identity is:

> A conservative compile-time Java checker that proves that verified instance
> state, record component state, and declared static class state cannot legally
> mutate after their respective initialization boundaries.

Its consumer experience is intentionally small:

```text
one source-retained annotation
+
one configured annotation processor
+
actionable compiler diagnostics
```

Its internal implementation may be large because the complexity belongs in the
checker, not in consumer code.

The final principles are:

```text
Do not ask whether the fields look immutable.

Prove that the verified state cannot mutate.
```

```text
Do not check only the root object.

Follow the complete supported state graph.
```

```text
Do not ignore declared static state.

Class state is verified state.
```

```text
Do not expose compiler implementation as consumer API.

Clients interact with @Immutable.
```