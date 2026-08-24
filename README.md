# Immutability Checker

[![CI](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml/badge.svg)](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml)

Immutability Checker is a conservative compile-time Java static-analysis
library. Its `@Immutable` annotation asks the processor to prove that neither
retained instance state nor declared static class state can change after its
applicable initialization boundary. Annotation presence alone is not proof: the
processor must run and compilation must complete without an immutability
diagnostic.

The project is currently an unreleased `1.0.0-SNAPSHOT`.

## Behavioral model

The checker uses two freeze boundaries. Direct writes to the object being
created are allowed in its instance field initializers, instance initializer
blocks, and constructors. Retained instance state freezes when construction
completes successfully.

Static fields are class state shared by every instance. Direct writes to a
class's declared static fields are allowed in that class's static field
initializers and static initializer blocks. Declared static state freezes when
class initialization completes successfully, conceptually at the end of the
JVM class initializer, `<clinit>`.

That model is intentionally semantic rather than stylistic. A private field
does not have to be `final` when no post-construction mutation path exists:

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

The same field fails verification when a later write is possible:

```java
@Immutable
public final class Currency {
    private String code;

    public Currency(String code) {
        this.code = code;
    }

    public void rename(String code) {
        this.code = code; // [IC006]: write occurs outside instance construction
    }
}
```

A private non-final static field can likewise be initialized directly during
class initialization:

```java
@Immutable
public final class Configuration {
    private static String mode;

    static {
        mode = "production";
    }

    public static String mode() {
        return mode;
    }
}
```

The same class state fails when an ordinary method can change it after class
initialization:

```java
@Immutable
public final class Configuration {
    private static String mode;

    public static void changeMode(String value) {
        mode = value; // [IC006]: write occurs after class initialization
    }
}
```

Successful verification does not establish safe publication under the Java
Memory Model, general method purity, or general thread safety. Reflection,
`Unsafe`, JNI, hostile agents, and similar mechanisms outside the supported
ordinary-Java static-analysis model are not part of the guarantee. The complete
semantic contract is in [PROJECT_INVARIANTS.md](PROJECT_INVARIANTS.md).

## Current development status

V1 targets ordinary Java classes with recursive retained-state analysis. The
current development slice follows source-available ordinary classes through
instance fields, declared static fields, and source-available superclasses.
Referenced source classes do not need `@Immutable`: the root annotation requests
verification of the reachable source graph, including each participating
source class's declared static state. Traversal is cycle-safe and a diagnostic
includes the complete deterministic path to the failing field or superclass.

The current proof capability supports:

- annotated top-level ordinary classes and static member classes;
- recursively referenced source classes when the declared reference type is
  `final`, so the exact runtime type is established by the declaration;
- recursively analyzed source superclasses, which do not need to be `final`;
- primitive instance and static fields; and
- exactly these modeled immutable JDK leaves: `Boolean`, `Byte`, `Short`,
  `Integer`, `Long`, `Character`, `Float`, `Double`, `String`, and `UUID`.

Records are intentionally deferred to V2. An annotated record is rejected with
`IC001`, even when all of its components use known leaves.

For every source class in the proof graph, the processor classifies each
declared field as instance or static, checks external writability, proves the
field type, and scans direct writes. A non-private, non-final instance or static
field fails because callers can write it directly after the applicable freeze
boundary. A private non-final instance field can pass when every direct write
targets the object currently being constructed and occurs in that object's
field initializer, instance initializer, or constructor. A private non-final
static field can pass when every direct write occurs in a static field
initializer or static initializer block of its declaring class.

Direct assignments, compound assignments, and prefix/postfix increment or
decrement are resolved through compiler symbols rather than source spelling.
Static writes therefore use the resolved field declaration, not capitalization
or receiver syntax. Static paths use `Type.<static>.FIELD`; superclass static
paths use `Child.<superclass> -> Parent.<static>.FIELD`.

Cycle states are explicit: unseen source types become visiting while their
outgoing field and superclass edges are checked, then proven or failed. A back
edge to a visiting type closes the cycle without recursion; failures are
propagated through the proof graph before verification completes.

The known final JDK leaves listed above are explicit atomic semantic models.
Their internal implementation details, including implementation caches, are not
recursively analyzed. This does not permit lazy mutation inside a user class;
such direct post-construction state changes remain forbidden.

Polymorphic retained references fail closed in this development slice. A
declared interface, or a non-final declared source class whose exact allocation
is not established, produces `IC005` for unresolved runtime subtype analysis.
This is a limitation of the current analysis, not a rule that immutable classes
must be final. Exact-allocation and ownership analysis are planned for a later
milestone.

Constructor-only and static-initialization-only helper reachability are not
analyzed yet. A direct field write inside a helper method therefore fails
conservatively even if current source appears to call that helper only from a
constructor or static initializer.

### Fail-closed limitations

The current implementation does not yet model ownership, collections, arrays,
defensive copies, aliases, escapes, method-call effects, constructor-helper or
static-initializer-helper call graphs, exact allocations, bytecode, or
cross-module proof metadata. A referenced compiled or external type without an
explicit semantic model fails with `IC005`; an unavailable non-`Object`
superclass fails with `IC003`.

Fields containing arrays, `List`, or other collections therefore remain
unproven. They are not categorically mutable or permanently forbidden by the
project contract. A `final` field modifier and an `@Immutable` annotation on a
referenced type are not proof. Unknown always fails closed.

Static fields are included only when declared by the annotated root or another
source class already participating in its recursive proof graph. The checker
does not expand into unrelated application-wide global state merely because a
verified method mentions or mutates it.

The direct-write scanner does not detect or model indirect field mutation
performed through `VarHandle`, `AtomicIntegerFieldUpdater`,
`AtomicLongFieldUpdater`, `AtomicReferenceFieldUpdater`, `MethodHandle` field
setters, reflection, `Unsafe`, JNI/native code, or bytecode instrumentation.
Those mechanisms are outside the current supported static-analysis model.

Diagnostics use stable identifiers:

| Identifier | Meaning |
| --- | --- |
| `IC000` | Analysis unavailable |
| `IC001` | Unsupported annotated type, including records deferred to V2 |
| `IC002` | Implicit or captured enclosing state unproven |
| `IC003` | Inherited state or behavior unproven |
| `IC004` | Externally writable instance or static field |
| `IC005` | Reachable reference, source, or runtime subtype unproven |
| `IC006` | Field write after the applicable freeze boundary |

## Compiler setup

The artifact has no runtime dependencies. It must be available both to compile
source using `@Immutable` and to the annotation-processing toolchain.

Maven:

```xml
<dependencies>
    <dependency>
        <groupId>io.github.j-util</groupId>
        <artifactId>immutability-checker</artifactId>
        <version>1.0.0-SNAPSHOT</version>
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
                        <artifactId>immutability-checker</artifactId>
                        <version>1.0.0-SNAPSHOT</version>
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
    compileOnly 'io.github.j-util:immutability-checker:1.0.0-SNAPSHOT'
    annotationProcessor 'io.github.j-util:immutability-checker:1.0.0-SNAPSHOT'
}
```

The processor and annotation produce Java 8-compatible class files and use only
supported `javax.annotation.processing`, `javax.lang.model`, `javax.tools`, and
`com.sun.source` compiler APIs. The CI matrix runs the complete build on Temurin
JDK 8 and JDK 26. On record-capable compilers, annotated records are rejected as
intentionally deferred to V2.

## Build

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

The build runs processor tests through `JavaCompiler`, creates source and
Javadoc JARs, and runs an integration test against the packaged JAR to verify
class-file version 52, service discovery, manifest metadata, license inclusion,
and both accepting and rejecting fixture compilations.

## License

Licensed under the [Apache License 2.0](LICENSE).
