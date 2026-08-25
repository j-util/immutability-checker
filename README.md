# Immutability Checker

[![CI](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml/badge.svg)](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml)

Current release: **0.1.0**. This is a technical preview, not the full intended
1.0.0 feature set.

Immutability Checker is a conservative compile-time Java static-analysis
library. Its `@Immutable` annotation asks the processor to prove that neither
retained instance state nor declared static class state can change after the
applicable initialization boundary. The 0.1.0 result is sound only within the
explicitly supported proof domain below. Unsupported state or behavior fails
closed: compilation is rejected rather than the behavior being assumed safe.

## Use it

The annotation API and processor are separate artifacts with no application
runtime dependencies. Use `immutability-checker` as a provided or compile-only
dependency and configure the matching `immutability-checker-processor` version
on the compiler's annotation-processor path. Annotation presence without the
processor running is not verification.

Maven:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.j-util</groupId>
        <artifactId>immutability-checker</artifactId>
        <version>0.1.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.j-util</groupId>
                        <artifactId>immutability-checker-processor</artifactId>
                        <version>0.1.0</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Gradle:

```groovy
dependencies {
    compileOnly 'io.github.j-util:immutability-checker:0.1.0'
    annotationProcessor 'io.github.j-util:immutability-checker-processor:0.1.0'
}
```

Example:

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
mutation path exists. Adding a method that writes `code` after construction
fails with `IC006`.

## 0.1.0 capability matrix

| Area | 0.1.0 status |
| --- | --- |
| Primitive and explicitly modeled immutable-leaf fields | Supported |
| Recursive final source classes | Supported |
| Declared instance and static state | Supported |
| Owned supported collections | Supported |
| Recursive collection items, map keys, and map values | Supported |
| Java 8 and Java 26 compilers | Supported |
| Records | Not implemented in 0.1.0 |
| Arrays | Not implemented in 0.1.0 |
| Nested collection containers | Not implemented in 0.1.0 |
| Broader collection implementations | Not implemented in 0.1.0 |
| Immutable factories and wrappers | Not implemented in 0.1.0 |
| General interprocedural method and alias analysis | Not implemented in 0.1.0 |
| Compiled dependency proof | Not implemented in 0.1.0 |
| Cross-module verification metadata | Not implemented in 0.1.0 |
| Deferred low-level mutation mechanisms | Outside the 0.1.0 guarantee |

Record verification is intended for the eventual 1.0.0 product line. The same
processor artifact remains usable on Java 8: it contains Java 8-compatible
class files, while record-specific analysis will run only on a record-capable
compiler once that feature is implemented.

## Supported proof domain

The annotation is a request for proof, not proof by itself. The processor must
run, complete its analysis, and allow compilation to finish without an
immutability diagnostic.

The 0.1.0 technical preview supports:

- `@Immutable` on ordinary top-level classes and static member classes;
- direct verification of declared instance state and declared static class
  state, with separate construction and class-initialization freeze boundaries;
- recursive, cycle-safe verification of source-available final custom-object
  field types and source-available superclasses;
- primitives and these explicit JDK semantic leaves: `Boolean`, `Byte`,
  `Short`, `Integer`, `Long`, `Character`, `Float`, `Double`, `String`, and
  `UUID`;
- fields declared as `Collection<E>`, `List<E>`, `Set<E>`, or `Map<K,V>` when
  their container ownership has one exact supported fresh origin;
- exact fresh `ArrayList`, `HashSet`, `LinkedHashSet`, `HashMap`, and
  `LinkedHashMap` origins, including supported shallow copy constructors that
  preserve exact collection element or map key/value roles;
- recursive proof of collection elements and map keys and values;
- supported structural collection mutations after ownership is established and
  before the applicable instance-construction or class-initialization boundary;
- conservative collection alias joins across conditional expressions,
  short-circuit expressions, `if`/`else`, `switch`, loops, and
  `try`/`catch`/`finally`;
- collection-specific rejection of unsafe returns, arguments, field or array
  storage, callbacks, iterators, views, streams, raw types, unchecked flows, and
  unknown operations;
- deterministic `IC000` through `IC006` diagnostics;
- Java 8-compatible class files, complete build verification on Temurin Java 8
  and Java 26, and zero runtime dependencies.

Referenced source classes do not need their own `@Immutable` annotation. The
root annotation requests recursive proof; annotation presence on a referenced
type is never treated as proof. A reference declared as an interface or a
non-final source class fails closed when its exact runtime subtype is not proven.
Source-available superclasses are analyzed recursively and do not need to be
final.

The explicit collection model allows a supported allocation in an instance
field initializer, instance initializer, or constructor for instance state, or
in a static field initializer or static initializer for class state. A copy
constructor establishes a fresh container but is shallow, so every retained
element, key, and value is still proved recursively.

Supported non-callback structural operations cover the modeled ordinary
collection/list/map add, bulk-add, put, remove, replacement, retention, and
clear signatures. Read operations are also modeled explicitly. The checker
does not infer read-only behavior from a method name.

## Fail-closed boundaries

The 0.1.0 preview does not implement:

- records or arrays;
- collections nested directly inside collections;
- queue, deque, sorted, concurrent, weak, identity, custom, third-party, or
  otherwise unlisted collection implementations;
- collection factories, helper origins, builders, unmodifiable wrappers,
  deserialization, field-to-field ownership, arbitrary conditional or competing
  origins, or general exact-allocation analysis;
- raw collections, wildcard or unresolved type-variable arguments, unchecked
  collection flows, or arbitrary interprocedural collection alias analysis;
- general interprocedural alias or escape analysis, general method-effect
  analysis, constructor-only helper reachability, or static-initializer-only
  helper reachability;
- bytecode analysis, proof of compiled dependencies, cross-module verification
  metadata, or arbitrary external-library analysis.

An unknown reference, operation, origin, alias, escape, or effect is rejected;
it is never accepted because no mutation was observed. An unmodifiable-looking
wrapper, a `final` reference, or an `@Immutable` annotation on a reachable type
is not proof.

The guarantee also does not establish safe publication, general thread safety,
general method purity, or protection against reflection, `Unsafe`, `VarHandle`,
field-writing `MethodHandle` operations, atomic field updaters, JNI/native code,
serialization bypasses, debugger writes, bytecode instrumentation, or hostile
agents. These low-level and runtime-bypass mechanisms are outside the supported
ordinary-Java source model.

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

Current release claims are intentionally limited to the 0.1.0 proof domain
documented here and in the `@Immutable` Javadocs, changelog, release notes, and
compiler diagnostics.

## License

Licensed under the [Apache License 2.0](LICENSE).
