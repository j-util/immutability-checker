# Immutability Checker

[![CI](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml/badge.svg)](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml)

**0.2.0 is an unreleased technical preview under preparation.** The examples
below target the locally built 0.2.0 artifacts; they do not claim Central
availability. This is not the full intended 1.0.0 feature set.

Immutability Checker is a conservative compile-time Java static-analysis
library. Its `@Immutable` annotation asks the processor to prove that neither
retained instance state nor declared static class state can change after the
applicable initialization boundary. The 0.2.0 result is sound only within the
explicitly supported proof domain below. Unsupported state or behavior fails
closed: compilation is rejected rather than the behavior being assumed safe.

## Use it

The annotation API and processor are separate artifacts with no application
runtime dependencies. Use `immutability-checker` as a provided or compile-only
dependency and configure the matching `immutability-checker-processor` version
on the compiler's annotation-processor path. Annotation presence without the
processor running is not verification.

### Try the unreleased preview

From a checkout of this repository, build, verify, and install both 0.2.0
artifacts into your local Maven repository:

```bash
./mvnw --batch-mode --no-transfer-progress clean install
```

Then configure your application with one of the examples below. Maven can use
the locally installed artifacts directly; Gradle needs the `mavenLocal()`
repository shown below. Running `clean verify` alone checks the build but does
not install its artifacts for other projects to use.

### Maven

Add these dependencies and compiler settings to your application's `pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.j-util</groupId>
        <artifactId>immutability-checker</artifactId>
        <version>0.2.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.15.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.j-util</groupId>
                        <artifactId>immutability-checker-processor</artifactId>
                        <version>0.2.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Gradle

For an application using Gradle's Java plugin, add this to `build.gradle`:

```groovy
repositories {
    mavenLocal() // Resolve the locally installed 0.2.0 preview.
    mavenCentral()
}

dependencies {
    compileOnly 'io.github.j-util:immutability-checker:0.2.0'
    annotationProcessor 'io.github.j-util:immutability-checker-processor:0.2.0'
}
```

### A class that passes

```java
import io.github.jutil.immutability.Immutable;

@Immutable
public final class Currency {
    private String code;

    public Currency(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

`final` is neither required nor sufficient for a field. The example passes
because the direct write occurs during construction and no later supported
mutation path exists.

### A change that fails

Adding this method to `Currency` allows its state to change after construction:

```java
public void setCode(String code) {
    this.code = code;
}
```

Compilation fails with a diagnostic identifying the field and method:

```text
[IC006] ... Currency.code -> write in Currency.setCode() occurs outside instance construction ...
```

You can use this change to check your setup: the original class should compile,
and adding the setter should fail with `IC006`. If both compile successfully,
check that the processor is enabled, then run a clean compilation.

## 0.2.0 capability matrix

| What you can write | 0.2.0 behavior and limits |
| --- | --- |
| Ordinary classes | Checks instance and static fields, source-available superclasses, and referenced final classes whose source is available in the same compilation. |
| `List.copyOf(Collection)` | Supports field and static initializers, constructors, locals, method arguments, helpers, and returns. Elements must also pass recursive verification. |
| Local variables, parameters, casts, and returns | Tracks references to the same object through assignments and helper calls, including `Object` references and compatible casts. |
| Source helper methods | Follows non-recursive calls to static, private, or final methods, and resolved methods on a known final receiver type. Helpers may be in another source file in the same compilation. |
| Private initialization helpers | Can allocate and populate state during construction or static initialization. Paths that mutate that state later are rejected. |
| Owned mutable collections | Supports exact `ArrayList`, `HashSet`, `LinkedHashSet`, `HashMap`, and `LinkedHashMap` allocations, including supported copy constructors and helpers. Retained containers must remain private and have no mutable external aliases. |
| Collection accessors | Can return `List.copyOf` snapshots or independent copies of owned containers when elements, keys, and values pass verification. Returning a retained mutable container is rejected. |
| Branches, loops, exceptions, and assertions | Checks possible references across control flow, including enabled and disabled assertions. May reject safe code when the required proof is unavailable. |
| Java 8 / 17 / 26 | Tested on all three JDKs with the same Java 8-compatible artifacts. `List.copyOf` requires Java 10+ APIs. |
| Records, arrays, and nested containers | Not supported when they participate in verified state. Passing tracked state through implicit varargs arrays is also rejected. |
| Other factories, wrappers, callbacks, views, recursive calls, and compiled dependencies | Not proven when relevant to verified state; see [fail-closed boundaries](#fail-closed-boundaries). |

Verification coverage: [classes](immutability-checker-processor/src/test/java/io/github/jutil/immutability/internal/processor/ImmutableProcessorTest.java),
[static state](immutability-checker-processor/src/test/java/io/github/jutil/immutability/internal/processor/StaticStateVerificationTest.java),
[collections](immutability-checker-processor/src/test/java/io/github/jutil/immutability/internal/processor/CollectionVerificationTest.java),
[helper and snapshot flows](immutability-checker-processor/src/test/java/io/github/jutil/immutability/internal/processor/ValueFlowVerificationTest.java),
[varargs, construction, and assertions](immutability-checker-processor/src/test/java/io/github/jutil/immutability/internal/processor/ValueFlowSoundnessRegressionTest.java),
and [packaged artifacts and runtime isolation](immutability-checker-processor/src/test/java/io/github/jutil/immutability/integration/PackagedArtifactIT.java).

### Snapshots through helpers

```java
import io.github.jutil.immutability.Immutable;
import java.util.List;

@Immutable
public final class Names {
    private List<String> values;

    public Names(List<String> input) {
        values = forward(snapshot(input));
    }

    private static List<String> snapshot(List<String> input) {
        return List.copyOf(input);
    }

    private static List<String> forward(List<String> input) {
        return input;
    }

    public List<String> values() {
        return forward(values);
    }
}
```

`List.copyOf` creates a structurally unmodifiable snapshot, independent of later
structural changes to the input. It preserves element references, iteration
order and duplicates, rejects null inputs/elements, and may reuse a suitable
immutable list. The checker follows the [JDK contract](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html#copyOf(java.util.Collection)); it injects no copies or runtime guards.
Every retained element still requires recursive proof, including participating
static state. Replacing `forward(snapshot(input))` with `forward(input)` fails
because the caller can retain a mutable alias. Method names do not establish
copying, ownership or read-only behavior.

### Owned containers on Java 8

```java
import io.github.jutil.immutability.Immutable;
import java.util.ArrayList;
import java.util.List;

@Immutable
public final class OwnedNames {
    private List<String> values;

    public OwnedNames(List<String> input) {
        initialize(input);
    }

    private void initialize(List<String> input) {
        values = new ArrayList<>(input);
        addInitial(values);
    }

    private static void addInitial(List<String> target) {
        target.add("initial");
    }

    private static int count(List<String> target) {
        return target.size();
    }

    public int size() {
        return count(values);
    }

    public List<String> values() {
        return new ArrayList<>(values);
    }
}
```

Adding `public void reset(List<String> input) { initialize(input); }` or
`public void addLater() { addInitial(values); }` fails. The helper must be
unreachable after the appropriate freeze boundary, including through nestmates,
lambdas and method references. Public methods are checked with caller-owned
arguments under open-world conditions; mutating an unrelated argument is allowed.
Returning the retained mutable list fails; returning the independent shallow copy
above is safe because its elements are proven immutable.

## Supported proof domain

The annotation is a request for proof, not proof by itself. The processor must
run, complete its analysis, and allow compilation to finish without an
immutability diagnostic.

The 0.2.0 technical preview supports:

- `@Immutable` on ordinary top-level classes and static member classes;
- direct verification of declared instance state and declared static class
  state, with separate construction and class-initialization freeze boundaries;
- recursive, cycle-safe verification of source-available final custom-object
  field types and source-available superclasses;
- primitives and these explicit JDK semantic leaves: `Boolean`, `Byte`,
  `Short`, `Integer`, `Long`, `Character`, `Float`, `Double`, `String`, and
  `UUID`;
- fields declared as `Collection<E>`, `List<E>`, `Set<E>`, or `Map<K,V>` when
  their value has a supported ownership or immutable-snapshot proof;
- exact fresh `ArrayList`, `HashSet`, `LinkedHashSet`, `HashMap`, and
  `LinkedHashMap` origins, including supported shallow copy constructors that
  preserve exact collection element or map key/value roles;
- recursive proof of collection elements and map keys and values;
- supported structural collection mutations after ownership is established and
  before the applicable instance-construction or class-initialization boundary;
- conservative collection alias joins across conditional expressions,
  short-circuit expressions, `if`/`else`, `switch`, loops, and
  `try`/`catch`/`finally`;
- value-flow rejection of unsafe returns, arguments, field or array
  storage, callbacks, iterators, views, streams, raw types, unchecked flows, and
  unknown operations;
- deterministic `IC000` through `IC006` diagnostics;
- Java 8-compatible class files, a Java 8/17/26 CI matrix, and zero runtime dependencies.

Referenced source classes do not need their own `@Immutable` annotation. The
root annotation requests recursive proof; annotation presence on a referenced
type is never treated as proof. A reference declared as an interface or a
non-final source class fails closed when its exact runtime subtype is not proven.
Source-available superclasses are analyzed recursively and do not need to be
final.

The explicit collection model allows a supported allocation or helper-produced
value in an instance field initializer, instance initializer, or constructor for
instance state, or
in a static field initializer or static initializer for class state. A copy
constructor establishes a fresh container but is shallow, so every retained
element, key, and value is still proved recursively.

Supported non-callback structural operations cover the modeled ordinary
collection/list/map add, bulk-add, put, remove, replacement, retention, and
clear signatures. Read operations are also modeled explicitly. The checker
does not infer read-only behavior from a method name.

## Fail-closed boundaries

The 0.2.0 preview does not implement:

- records or arrays;
- array-element provenance, including tracked state packed into implicit varargs
  arrays; such flows are rejected even through Object-typed varargs or before
  later field retention, while unrelated varargs computations remain permitted;
- collections nested directly inside collections;
- queue, deque, sorted, concurrent, weak, identity, custom, third-party, or
  otherwise unlisted collection implementations;
- factories other than `List.copyOf`, builders, unmodifiable wrappers,
  deserialization, or general ownership transfer between mutable owners;
- raw collections, wildcard or unresolved user type-variable arguments, unchecked
  collection flows, or general generic/inheritance expansion;
- recursive call cycles, unresolved virtual dispatch, source-unavailable helper
  effects, complex callback execution, streams, iterators and views when relevant
  to verified state;
- precise path feasibility, exception-type filtering, or arbitrary modern
  switch/pattern provenance; joins may conservatively reject safe programs;
- bytecode analysis, proof of compiled dependencies, cross-module verification
  metadata, or arbitrary external-library analysis.

An unknown state-relevant reference, operation, origin, alias, escape, or effect is rejected;
it is never accepted because no mutation was observed. An unmodifiable-looking
wrapper, a `final` reference, or an `@Immutable` annotation on a reachable type
is not proof.

The guarantee also does not establish safe publication, general thread safety,
general method purity, or protection against reflection, `Unsafe`, `VarHandle`,
field-writing `MethodHandle` operations, atomic field updaters, JNI/native code,
serialization bypasses, debugger writes, bytecode instrumentation, or hostile
agents. These low-level and runtime-bypass mechanisms are outside the supported
ordinary-Java source model.

Snapshot mutators explicitly modeled as guaranteed to throw are not reported as
successful structural mutations. Argument evaluation and separate effects are
still checked. Unmodeled operations are not automatically harmless just because
the receiver is a snapshot. Creating a snapshot neither freezes nor transfers
ownership of its mutable input.

See [CHANGELOG.md](CHANGELOG.md) for version history and analysis corrections.

## Diagnostics

| Identifier | Meaning |
| --- | --- |
| `IC000` | Analysis unavailable |
| `IC001` | Unsupported annotated type, including records |
| `IC002` | Implicit or captured enclosing state unproven |
| `IC003` | Inherited state or behavior unproven |
| `IC004` | Externally writable instance or static field |
| `IC005` | Reachable reference, ownership, item proof, alias, escape, operation, source, or runtime subtype unproven |
| `IC006` | Field write or structural collection mutation after the applicable freeze boundary |

Diagnostics include deterministic paths such as
`Order.lines -> element -> Line.price` and
`Registry.<static>.entries -> value -> Entry.state`.

## Build and compatibility

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

The build creates main, source, and Javadoc JARs for both published artifacts
and runs a packaged-artifact integration test. That test verifies that the API
JAR contains only `Immutable.class`, cannot discover a processor on its own,
and has no processor service entry. It also verifies that the processor JAR has
the service provider and internal engine without a duplicate annotation class.
Both artifact versions, automatic module names, licenses, Java 8 class-file
major version 52, policy-file exclusion, and positive and negative fixture
compilation are checked.

On Temurin JDK 8, Maven activates one build-only profile for the JDK's own
`tools.jar`. It is optional and system-scoped, is not packaged, and is not a
runtime or transitive consumer dependency. Modular JDKs do not activate those
profiles.

Preview claims are intentionally limited to the 0.2.0 proof domain
documented here and in the `@Immutable` Javadocs, changelog, release notes, and
compiler diagnostics.

Maintainers preparing both release artifacts should follow
[RELEASING.md](RELEASING.md).

## License

Licensed under the [Apache License 2.0](LICENSE).
