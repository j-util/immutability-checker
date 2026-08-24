## Immutability Checker — Project Invariants

> **MUST READ — READ-ONLY FOR AI AGENTS**
>
> This file defines the normative semantic contract of the project.
>
> Every AI agent working on this repository **MUST read this file in full before modifying code, tests, documentation, build configuration, or public API**.
>
> **AI agents MUST NOT modify, rewrite, reorganize, delete, weaken, or “improve” this file. This file is read-only for AI agents.**
>
> If an AI agent believes an invariant is incorrect, ambiguous, incomplete, or incompatible with a requested change, it must report the issue to the human maintainer instead of editing this file.
>
> Only a human maintainer may change `PROJECT_INVARIANTS.md`.
>
> If implementation, tests, documentation, issues, comments, or existing behavior conflict with this file, **this file wins**. The conflicting implementation or documentation must be corrected; the invariant must not be silently adapted to existing code.

---

## 1. Purpose

`immutability-checker` is a compile-time Java static-analysis library.

Its primary annotation is:

```java
@Immutable
```

The annotation requests verification of an object's actual immutability.

The project does **not** define immutability as:

* all fields being `final`;
* all referenced types being declared immutable;
* using records;
* having no setters;
* using immutable collection interfaces;
* following naming conventions;
* having an `@Immutable` annotation somewhere in the reference graph.

The checker must reason about **whether the instance and the state retained by that instance can actually change after construction**.

The central differentiator of this project is therefore:

> **Immutability is proven through mutation, alias, escape, ownership, call, and reachable-reference analysis rather than inferred primarily from field modifiers or type declarations.**

---

# 2. Core Immutability Contract

An instance successfully verified by `@Immutable` must satisfy the following property:

> After successful completion of its construction, neither the instance itself nor any mutable state retained as part of its reachable instance-state graph may be changed through ordinary Java execution.

Construction is the freeze boundary:

```text
construction
    |
    | mutation may occur
    v
constructor successfully returns
    |
    | state is frozen
    v
post-construction lifetime
```

The checker must prove this property or reject the annotated type.

There is no intermediate result such as "probably immutable".

If the checker cannot establish immutability with sufficient certainty:

> **verification fails.**

---

# 3. `final` Is Neither Required Nor Sufficient

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

If `code` cannot change after construction, the absence of `final` does not make the instance mutable.

Conversely:

```java
final class Names {
    private final List<String> values;
}
```

is not necessarily immutable.

`final` prevents reassignment of the `values` reference. It does not prevent mutation of the referenced object.

Therefore:

```text
final field
≠ immutable state

non-final field
≠ mutable state
```

Field modifiers may provide useful evidence during analysis, but they must never substitute for the actual immutability proof.

---

# 4. Mutable Types Are Allowed

A field having a mutable type must not automatically cause rejection.

For example:

```java
@Immutable
class Names {

    private List<String> names;

    Names(List<String> source) {
        this.names = new ArrayList<>(source);
    }

    int size() {
        return names.size();
    }
}
```

This may be immutable when all of the following are proven:

* the `ArrayList` is owned by the instance;
* no external mutable alias to it remains;
* it is not modified after construction;
* it is not exposed through an operation that permits mutation;
* nothing reachable from it can be externally mutated in a way that changes the retained state.

The checker therefore reasons about **instances and references**, not merely whether a Java type is theoretically mutable.

A mutable class can participate in an immutable object graph.

---

# 5. Reachable State Is a Graph

The checker must analyze the state reachable from the annotated instance recursively.

Conceptually:

```text
Order
 ├── customer
 │    └── address
 │         └── country
 │
 └── lines
      └── elements
           └── product
```

Every relevant reference must be followed.

The structure is a **graph**, not necessarily a tree.

Cycles are valid Java object structures:

```text
A -> B -> C
     ^    |
     |____|
```

The checker must therefore be cycle-safe and must never recurse infinitely when analyzing cyclic references.

A visited-state mechanism or equivalent graph-analysis strategy is required.

---

# 6. Deep Analysis Does Not Mean "Every Type Must Be Immutable"

The checker must not recursively demand that every referenced type satisfy a structural immutable-type definition.

Consider:

```java
class InternalState {

    private int value;

    void setValue(int value) {
        this.value = value;
    }

    int value() {
        return value;
    }
}
```

The existence of `setValue()` does not by itself make this illegal:

```java
@Immutable
class Example {

    private InternalState state;

    Example(int value) {
        state = new InternalState();
        state.setValue(value);
    }

    int value() {
        return state.value();
    }
}
```

If:

* `state` is freshly owned;
* mutation occurs only during construction;
* `state` never escapes;
* no post-construction execution path invokes `setValue()`;

then the containing object can still satisfy the immutability contract.

Therefore the checker must distinguish:

```text
"type can mutate"
```

from:

```text
"this retained instance can mutate after the freeze boundary"
```

This distinction is fundamental to the project.

---

# 7. Construction-Phase Mutation Is Allowed

Mutation necessary to construct the object is valid.

This includes mutation of:

* the root object;
* newly created owned objects;
* arrays;
* collections;
* internal builders;
* constructor-only helper state.

For example:

```java
@Immutable
class Example {

    private List<String> values;

    Example(List<String> input) {
        values = new ArrayList<>();
        values.addAll(input);
    }
}
```

The mutations occur before the freeze boundary and may therefore be valid.

The checker must reason about **when** mutation occurs, not merely whether mutation exists somewhere in the source code.

---

# 8. Constructor-Only Mutating Helpers May Be Valid

A mutating operation does not automatically invalidate the class merely because it exists outside the constructor body.

For example:

```java
@Immutable
class Example {

    private int value;

    Example(int value) {
        initialize(value);
    }

    private void initialize(int value) {
        this.value = value;
    }
}
```

`initialize()` mutates instance state.

However, if the checker proves that the method can execute only during construction, that mutation belongs to the construction phase.

It may therefore be accepted.

If any post-construction execution path can reach the same mutating method, verification must fail.

Consequently the checker must reason about call reachability, not simply scan method bodies for assignments.

---

# 9. Post-Construction Mutation of Retained State Is Forbidden

After construction has completed, mutation of the retained state graph is forbidden.

This must fail:

```java
@Immutable
class Counter {

    private int value;

    void increment() {
        value++;
    }
}
```

This must also fail:

```java
@Immutable
class Names {

    private List<String> names;

    void add(String name) {
        names.add(name);
    }
}
```

The second case is equally important.

The field reference itself did not change.

The object reachable through that field changed.

Both are mutations of instance state.

---

# 10. Internal Lazy Mutation Is Not Immutable

The initial project contract is intentionally strict.

Lazy caches, memoization fields, counters, lazy initialization, or similar internal state changes count as mutation.

Example:

```java
@Immutable
class Calculation {

    private String cached;

    String result() {
        if (cached == null) {
            cached = calculate();
        }

        return cached;
    }
}
```

This class does not satisfy the project definition of immutability.

Whether the mutation is invisible to the caller is irrelevant.

The state changed after construction.

The checker must reject it.

---

# 11. Ownership Must Be Proven

Mutable retained objects must have sufficiently strong ownership guarantees.

Consider:

```java
@Immutable
class Names {

    private List<String> names;

    Names(List<String> names) {
        this.names = names;
    }
}
```

This must not be accepted merely because `Names` itself never calls a mutating method.

The caller still possesses the reference:

```java
List<String> list = new ArrayList<>();

Names names = new Names(list);

list.add("Alice");
```

The retained state of `Names` has changed after construction.

Therefore storing an externally supplied mutable reference directly is unsafe unless immutability of the referenced object itself can be proven.

---

# 12. Defensive Copying Can Establish Ownership

This is fundamentally different:

```java
Names(List<String> names) {
    this.names = new ArrayList<>(names);
}
```

The newly created list may be exclusively owned by the new instance.

However, defensive copying must be analyzed recursively.

For:

```java
List<Person>
```

copying the list does not copy its elements.

Therefore:

```java
new ArrayList<>(people)
```

creates a new container but may preserve aliases to mutable `Person` instances.

The checker must not treat a shallow container copy as proof of deep ownership.

Every retained reference path remains relevant.

---

# 13. Reference Escape Must Be Analyzed

A retained mutable reference must not escape in a way that permits external mutation.

This fails:

```java
@Immutable
class Names {

    private List<String> names;

    List<String> names() {
        return names;
    }
}
```

There is no setter.

There is no mutation inside `Names`.

The class is nevertheless mutable from outside:

```java
names.names().add("Alice");
```

Therefore setter detection is insufficient.

The checker must perform escape analysis.

---

# 14. Escape Is Broader Than Getters

Method names have no semantic importance.

All of the following may expose state:

```java
List<String> values()
```

```java
Collection<String> view()
```

```java
Iterator<String> iterator()
```

```java
Spliterator<String> spliterator()
```

```java
Object state()
```

```java
void consume(Consumer<List<String>> consumer)
```

A method called `getValues()` is not inherently dangerous.

A method called `foo()` is not inherently safe.

The checker must reason about reference flow and mutation capability, never JavaBean naming conventions.

---

# 15. Returning Copies Is Different From Returning State

Returning a fresh object that does not retain aliases into internal mutable state may be safe.

For example:

```java
List<String> names() {
    return new ArrayList<>(names);
}
```

The returned `ArrayList` is mutable.

That alone is irrelevant.

Mutating the returned copy does not mutate the retained state of the original object.

The checker must distinguish:

```text
mutable returned object
```

from:

```text
mutable alias into retained state
```

These are not equivalent.

---

# 16. Elements and Nested Objects Matter

A container cannot be considered safe merely because the container itself cannot be mutated externally.

Example:

```java
private List<Person> people;
```

Even if the list cannot escape, a `Person` reference may escape.

For example:

```java
Person first() {
    return people.get(0);
}
```

If the returned `Person` participates in the retained state graph and is externally mutable, the root object is not immutable.

Reference analysis must therefore continue through:

* fields;
* arrays;
* collection elements;
* map keys;
* map values;
* nested containers;
* nested objects;
* other retained references.

---

# 17. Arrays Are Not Special-Cased as Automatically Invalid

Arrays are mutable, but mutable types are allowed when ownership and freezing can be proven.

This can potentially pass:

```java
@Immutable
class Bytes {

    private byte[] values;

    Bytes(byte[] source) {
        values = source.clone();
    }

    byte get(int index) {
        return values[index];
    }
}
```

This must fail:

```java
byte[] values() {
    return values;
}
```

The analysis principle is the same as for collections.

There must not be arbitrary type-level rules where reference-flow analysis can establish the actual property.

---

# 18. All Relevant Methods Must Be Considered

The checker must not inspect only public setters or obvious mutators.

Relevant behavior includes:

* public methods;
* protected methods where relevant;
* package-private methods where relevant;
* private methods;
* inherited methods;
* interface default methods;
* static methods capable of mutating an annotated instance;
* constructors;
* instance initializers;
* field initializers;
* language-generated behavior such as record accessors;
* nested/nestmate code where it has legal access to the instance state.

Example:

```java
@Immutable
class Value {

    private int value;

    static void reset(Value value) {
        value.value = 0;
    }
}
```

The instance can be mutated after construction through:

```java
Value.reset(instance);
```

Therefore restricting analysis to instance methods would be incorrect.

---

# 19. Mutation of Unrelated Objects Is Not an Immutability Violation

The checker verifies immutability of the annotated instance and its retained state graph.

It is not a general side-effect or purity checker.

This does not necessarily violate immutability:

```java
void process(List<String> destination) {
    destination.add("value");
}
```

provided `destination` is not part of the retained state graph.

Likewise, mutation of a fresh local temporary object is valid:

```java
String value() {
    StringBuilder builder = new StringBuilder();
    builder.append("A");
    builder.append("B");
    return builder.toString();
}
```

The checker must be state-sensitive rather than rejecting arbitrary mutation instructions.

---

# 20. Direct External Field Mutation Must Be Considered

Field visibility matters because Java code may mutate accessible fields directly.

For example:

```java
@Immutable
class Value {
    public int value;
}
```

cannot be immutable because:

```java
instance.value = 100;
```

changes the instance.

A private non-final field can be safer than a public non-final field.

Again, `final` itself is not the invariant.

The invariant is whether a post-construction mutation path exists.

---

# 21. Records Are Not Automatically Immutable

A Java record is not inherently immutable under this project's definition.

Example:

```java
record Names(List<String> values) {}
```

The generated accessor:

```java
values()
```

returns the component reference.

Therefore:

```java
names.values().add("Alice");
```

may mutate the state retained by the record.

The fact that the record component field is final does not solve this.

Under this project's model, an ordinary class may be more strongly immutable than a record because an ordinary class can completely encapsulate mutable implementation state.

Therefore:

```text
record
≠ immutable
```

Record components and generated accessors must be analyzed using the same semantic rules as ordinary Java code.

---

# 22. Inheritance Must Not Create an Unchecked Hole

State and behavior inherited from a superclass are part of the actual object and must therefore be considered.

An annotated class cannot be verified while ignoring mutable state or mutating behavior inherited from its superclass.

If required superclass behavior cannot be analyzed or trusted, verification must fail.

However:

> `@Immutable` on a class does not automatically certify its subclasses.

The annotation describes the verified type.

A subclass must be verified independently if it wants the same guarantee.

The checker must not treat the annotation as an inherited immutability certificate merely because Java subtype relationships exist.

---

# 23. Polymorphism Must Be Conservative

Where a call may dispatch to multiple runtime implementations, every possible state-relevant target must be accounted for.

The checker must not silently choose the most convenient implementation.

For example:

```java
interface Data {
    int size();
}
```

If a retained `Data` reference may point to multiple implementations and the checker cannot establish the effect of the actual possible targets, immutability has not been proven.

Unknown dynamic dispatch must fail closed when it can affect the proof.

---

# 24. External Dependencies Are a Proof Boundary

Source code for arbitrary dependency classes may not be available to the compiler.

A method signature alone does not establish whether a method mutates state.

These two methods have the same signature:

```java
BigDecimal total()
```

but one implementation may simply read:

```java
return total;
```

while another may mutate:

```java
total = recalculate();
return total;
```

The checker must never infer purity from the method name, return type, or signature.

Acceptable proof sources may include:

* source currently available for analysis;
* semantics explicitly modeled by this project;
* types previously verified through trustworthy project-generated verification metadata;
* bytecode analysis if the project later implements it;
* other explicitly defined trusted mechanisms.

The mere presence of some third-party `@Immutable` annotation is not sufficient proof unless this project explicitly defines that annotation as trusted.

---

# 25. Unknown Means Unproven, Not Immutable

This is a core safety invariant.

When analysis reaches state-relevant behavior that cannot be understood sufficiently:

```text
unknown
```

must mean:

```text
cannot prove immutability
```

and therefore:

```text
verification failure
```

It must never mean:

```text
assume safe
```

The checker is intentionally fail-closed.

False rejection is preferable to falsely certifying a mutable type as immutable.

---

# 26. Known Types May Have Explicit Semantic Models

Certain platform types can have well-understood semantics.

Examples may include types such as:

```text
String
Integer
Long
UUID
BigInteger
BigDecimal
```

Likewise, selected JDK collection operations may eventually have explicit mutation/escape semantics.

Such knowledge must be explicit and deterministic.

The checker must not rely on method-name heuristics such as:

```text
get*  => read-only
set*  => mutating
size  => safe
copy  => fresh
```

Names are not proofs.

---

# 27. Annotation Presence Is a Request for Verification, Not Proof by Itself

The following source code:

```java
@Immutable
class Example {
}
```

does not magically make `Example` immutable.

The annotation means:

> verify this type against the project's immutability contract.

A compiled class carrying an annotation must not automatically be trusted merely because the annotation is present.

The checker must distinguish between:

```text
declared immutable
```

and:

```text
successfully verified immutable
```

This distinction is particularly important across modules and external dependencies.

---

# 28. Diagnostics Must Explain the Proof Failure

Compilation errors must be actionable.

A failure should identify, where possible:

1. the annotated root type;
2. the reference path;
3. the operation or alias that violates the invariant;
4. the mutation or escape site;
5. why the checker cannot prove safety.

Preferred diagnostic shape:

```text
Immutability verification failed for Order

Order.customer
  -> Customer.address
  -> Address.lines
  -> reference escapes through Address.lines()
```

or:

```text
Immutability verification failed for Portfolio

Portfolio.positions
  -> element
  -> Position.metadata
  -> Map.put(...)
  -> post-construction mutation
```

or:

```text
Immutability verification failed for Pricing

Pricing.engine
  -> ExternalPricingEngine.calculate()
  -> method body/effect unavailable
  -> immutability cannot be established
```

Diagnostics should expose the reasoning path instead of only reporting:

```text
type is not immutable
```

Explainability is a project requirement.

---

# 29. Verification Must Be Deterministic

Given:

* the same source;
* the same dependency versions;
* the same checker configuration;
* the same supported compiler environment;

the result must not depend on:

* traversal order;
* hash iteration order;
* timing;
* network access;
* machine-specific state;
* nondeterministic heuristics.

A type must deterministically pass or fail.

---

# 30. The Checker Must Not Modify User Code

`immutability-checker` is a checker.

It is not Lombok.

It must not silently:

* add `final`;
* generate defensive copies;
* replace collections;
* rewrite methods;
* inject guards;
* generate setters/getters;
* change constructors;
* alter bytecode to enforce immutability.

Its responsibility is:

```text
analyze
→ prove or reject
→ explain
```

not:

```text
rewrite code until it becomes immutable
```

---

# 31. No Runtime Dependency Should Be Required for Enforcement

The immutability guarantee is established at compile time.

The checker must not depend on runtime agents, proxies, interception, or runtime mutation tracking to make an otherwise mutable object immutable.

Runtime support may only be introduced for a clearly separate purpose and must never replace compile-time verification.

---

# 32. Safe Publication Is Not Part of the Immutability Guarantee

Because this project intentionally does not require final fields, it must not claim Java Memory Model guarantees that depend on final-field semantics.

For example, the checker does **not** prove that an object published incorrectly between threads is safely published.

Therefore:

```text
verified immutable
≠ automatically safely published
```

and:

```text
verified immutable
≠ substitute for Java Memory Model rules
```

Users remain responsible for correct publication and concurrency semantics.

This distinction must be reflected accurately in documentation.

---

# 33. Immutability Is Not General Method Purity

An immutable object's methods may still:

* perform I/O;
* allocate objects;
* log;
* read external state;
* mutate objects supplied as method parameters;
* interact with external services;
* modify unrelated global state.

Those behaviors may be undesirable for other reasons, but they are not automatically violations of this project's instance-immutability contract.

The checker asks:

> Can this operation change the annotated object's retained state graph?

It does not ask:

> Is this method mathematically pure?

These concepts must not be conflated.

---

# 34. Unsupported Mutation Mechanisms Are Outside the Guarantee

The checker operates over ordinary Java semantics that it can statically analyze.

The immutability guarantee does not attempt to defend against arbitrary mutation through mechanisms such as:

* reflection deliberately bypassing encapsulation;
* `Unsafe`;
* JNI/native code;
* bytecode instrumentation;
* debugger memory modification;
* hostile JVM agents;
* unsupported deserialization tricks.

These are outside the normal static-analysis model.

The documentation must not claim security against mechanisms outside that model.

---

# 35. No Special Treatment Based on Style

The checker must not equate coding style with semantic immutability.

None of these alone proves immutability:

```text
private fields
final fields
records
no setters
getter-only API
unmodifiable-looking names
immutable-looking class names
builder pattern
value-object naming
sealed classes
```

Likewise, none of these alone disproves immutability:

```text
non-final private fields
ArrayList
HashMap
arrays
mutable implementation classes
constructor helper mutators
```

The analysis result must follow actual state-flow semantics.

---

# 36. Core Pass Example

The following represents an important use case the project is intended to support:

```java
@Immutable
public class Names {

    private List<String> values;

    public Names(List<String> source) {
        this.values = new ArrayList<>(source);
    }

    public int size() {
        return values.size();
    }

    public String get(int index) {
        return values.get(index);
    }
}
```

Assuming the checker establishes the required ownership and escape properties, this class should be eligible to pass even though:

* `values` is not final;
* `List` is mutable;
* `ArrayList` is mutable.

Rejecting this solely because of those facts would violate the central design of the project.

---

# 37. Core Failure Examples

## External alias retained

```java
@Immutable
class Names {

    private List<String> values;

    Names(List<String> values) {
        this.values = values;
    }
}
```

Must fail when the referenced state is mutable because an external alias remains.

## Internal reference escapes

```java
List<String> values() {
    return values;
}
```

Must fail when the returned reference permits mutation of retained state.

## Post-construction mutation

```java
void add(String value) {
    values.add(value);
}
```

Must fail.

## Lazy state

```java
int hash() {
    if (cachedHash == 0) {
        cachedHash = calculateHash();
    }

    return cachedHash;
}
```

Must fail under the strict immutability contract.

## Mutable record component exposure

```java
@Immutable
record Names(List<String> values) {}
```

Must fail unless the referenced value itself is proven safe under the complete immutability contract.

---

# 38. Evolution Rule

Future features may improve the checker's ability to prove valid programs.

Examples include:

* stronger ownership analysis;
* bytecode analysis;
* richer JDK semantic models;
* inter-module verification metadata;
* more precise polymorphic analysis;
* improved escape analysis.

Such improvements may turn previously unprovable code into provably immutable code.

They must **not weaken the meaning of a successful verification**.

The invariant is:

```text
analysis capability may become more precise
```

but:

```text
@Immutable must not gradually mean less
```

Backward evolution should primarily reduce false rejections, not permit genuine mutation.

---

# 39. Conservative Changes Are Required

When adding a new analysis rule, ask:

> Does this rule help us prove that no post-construction mutation path exists?

Do not ask merely:

> Does this make more classes pass?

Permissiveness is not itself a project goal.

Correct certification is the goal.

When choosing between:

```text
reject because proof is incomplete
```

and:

```text
accept based on an assumption
```

the checker must reject.

---

# 40. Public Documentation Must Match the Actual Guarantee

README, Javadocs, examples, Maven metadata, release notes, website copy, and issue descriptions must not claim stronger behavior than the implementation provides.

In particular, avoid unsupported claims such as:

```text
proves all Java objects immutable
```

```text
guarantees thread safety
```

```text
guarantees safe publication
```

```text
detects every possible mutation mechanism
```

```text
works through arbitrary native/reflection code
```

Claims must describe the supported static-analysis model precisely.

---

# 41. Tests Are Evidence, Not the Definition

Tests must implement and protect these invariants.

Tests do not define the invariants.

If an existing test expects behavior contrary to this file, the test is wrong.

An AI agent must never resolve such a conflict by changing this file.

It must:

```text
1. keep PROJECT_INVARIANTS.md unchanged;
2. report the conflict;
3. change implementation/tests/docs as appropriate.
```

---

# 42. AI-Agent Compliance Rules

Every AI coding or reviewing agent working on this repository must follow these rules.

Before doing repository work, the agent MUST:

1. read `PROJECT_INVARIANTS.md` completely;
2. treat it as normative;
3. check proposed implementation decisions against it.

The agent MUST NOT:

* modify this file;
* remove inconvenient invariants;
* weaken an invariant to make tests pass;
* reinterpret "unknown" as "safe";
* replace semantic analysis with simple final-field checking;
* reject mutable field types solely because the types are mutable;
* assume records are immutable;
* assume getters are safe;
* assume defensive copying is deep;
* silently narrow reference analysis to the root class;
* sacrifice correctness merely to increase acceptance rate.

If a requested implementation conflicts with an invariant, the agent must explicitly identify the conflict to the human maintainer.

The agent may propose a change to an invariant in discussion, but **must not edit this file itself**.

---

# 43. Project Identity

The project's identity can be summarized as:

> **A conservative compile-time Java immutability checker that follows references, ownership, aliases, escapes, calls, and mutations to determine whether retained object state can change after construction.**

The defining principle is:

```text
Do not ask whether the fields look immutable.

Prove that the object cannot mutate.
```
