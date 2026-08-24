# Immutability Checker

[![CI](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml/badge.svg)](https://github.com/j-util/immutability-checker/actions/workflows/ci.yml)

Immutability Checker is a conservative compile-time Java static-analysis
library. Its `@Immutable` annotation asks the processor to prove that retained
instance state cannot change after successful construction. Annotation presence
alone is not proof: the processor must run and compilation must complete without
an immutability diagnostic.

The project is currently an unreleased `1.0.0-SNAPSHOT`.

## Behavioral model

Construction is the freeze boundary. Direct writes to the object being created
are allowed in its field initializers, instance initializers, and constructors.
Once a constructor returns successfully, retained instance state must not be
directly or indirectly mutable through ordinary Java execution.

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
        this.code = code; // [IC006]: write occurs outside construction
    }
}
```

Successful verification does not establish safe publication under the Java
Memory Model, general method purity, or general thread safety. Reflection,
`Unsafe`, JNI, hostile agents, and similar mechanisms outside the supported
ordinary-Java static-analysis model are not part of the guarantee. The complete
semantic contract is in [PROJECT_INVARIANTS.md](PROJECT_INVARIANTS.md).

## Milestone 1 proof capability

The final project contract covers the full reachable instance-state graph,
including ownership, aliases, escapes, calls, inheritance, and mutation. This
first milestone implements a deliberately narrower, sound vertical slice:

- ordinary classes whose direct superclass is exactly `Object`;
- records on record-capable JDKs;
- static member classes, but not non-static member or local classes;
- primitive instance fields; and
- exactly these modeled immutable JDK leaves: primitive wrappers, `String`,
  `BigInteger`, `BigDecimal`, and `UUID`.

The processor skips static fields. A non-private, non-final instance field fails
because callers can write it directly. A private non-final field can pass when
every direct write targets the object currently being constructed and occurs in
that object's field initializer, instance initializer, or constructor. Direct
assignments, compound assignments, and prefix/postfix increment or decrement are
resolved through compiler symbols rather than source spelling.

Constructor-only helper reachability is not analyzed yet. A direct field write
inside a helper method therefore fails conservatively even if current source
appears to call that helper only from a constructor.

### Fail-closed limitations

Milestone 1 does not yet implement recursive reference-graph verification,
collection or array ownership, defensive-copy recognition, alias analysis,
general escape analysis, method-call effects, constructor-helper call graphs,
superclass traversal, bytecode analysis, or cross-module proof metadata.

Consequently, fields containing arrays, `List`, other collections, or custom
reference types currently fail because those reference paths are unproven. They
are not categorically mutable or permanently forbidden by the final project
contract. A `final` modifier does not make an unproven reference pass. Unknown
always fails closed.

Diagnostics use stable identifiers:

| Identifier | Meaning |
| --- | --- |
| `IC000` | Analysis unavailable |
| `IC001` | Unsupported annotated type |
| `IC002` | Implicit or captured enclosing state unproven |
| `IC003` | Inherited state unproven |
| `IC004` | Externally writable instance field |
| `IC005` | Reachable reference state unproven |
| `IC006` | Post-construction field write |

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
JDK 8 and JDK 26. Records are analyzed only when the compiler supports them.

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
