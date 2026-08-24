package io.github.jutil.immutability.internal.processor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticStateVerificationTest {

    private static final String HEADER = "package example;\n"
            + "import io.github.jutil.immutability.Immutable;\n";

    private final CompilerTestHarness compiler = new CompilerTestHarness();

    @Test
    void acceptsPrivateNonFinalStaticPrimitiveWithoutWrites() {
        assertPasses("example.Configuration", HEADER
                + "@Immutable final class Configuration { private static int version; }\n");
    }

    @Test
    void acceptsPrivateNonFinalStaticPrimitiveFieldInitializer() {
        assertPasses("example.Configuration", HEADER
                + "@Immutable final class Configuration { private static int version = 1; }\n");
    }

    @Test
    void acceptsBlankStaticFieldAssignedInStaticInitializer() {
        assertPasses("example.Configuration", HEADER
                + "@Immutable final class Configuration {\n"
                + "  private static int version;\n"
                + "  static { Configuration.version = 1; }\n"
                + "}\n");
    }

    @Test
    void acceptsInstanceQualifiedStaticFieldWriteInStaticInitializer() {
        assertPasses("example.Configuration", HEADER
                + "@Immutable final class Configuration {\n"
                + "  private static int version;\n"
                + "  static { Configuration instance = new Configuration(); instance.version = 1; }\n"
                + "}\n");
    }

    @Test
    void acceptsPublicStaticFinalPrimitive() {
        assertPasses("example.Constants", HEADER
                + "@Immutable final class Constants { public static final int VERSION = 1; }\n");
    }

    @Test
    void acceptsPublicStaticFinalKnownLeaf() {
        assertPasses("example.Constants", HEADER
                + "@Immutable final class Constants { public static final String NAME = \"checker\"; }\n");
    }

    @Test
    void acceptsPrivateStaticFinalRecursivelyImmutableCustomObject() {
        assertPasses("example.Registry", HEADER
                + "final class Metadata {\n"
                + "  private String name; Metadata(String name) { this.name = name; }\n"
                + "}\n"
                + "@Immutable final class Registry {\n"
                + "  private static final Metadata METADATA = new Metadata(\"registry\");\n"
                + "}\n");
    }

    @Test
    void acceptsStaticStateInRecursivelyReferencedSourceClass() {
        assertPasses("example.Root", HEADER
                + "final class State {\n"
                + "  private static int revision = 1;\n"
                + "  private String value; State(String value) { this.value = value; }\n"
                + "}\n"
                + "@Immutable final class Root { private final State state = new State(\"value\"); }\n");
    }

    @Test
    void acceptsStaticStateInSourceSuperclass() {
        assertPasses("example.Child", HEADER
                + "class Parent { private static int revision = 1; }\n"
                + "@Immutable final class Child extends Parent {}\n");
    }

    @Test
    void acceptsStaticReferenceCycle() {
        assertPasses("example.A", HEADER
                + "@Immutable final class A { private static final B B_VALUE = null; }\n"
                + "final class B { private static final A A_VALUE = null; }\n");
    }

    @Test
    void mutationOfUnrelatedExternalStaticStateRemainsOutsideProofGraph() {
        assertPasses("example.Value", HEADER
                + "final class GlobalState { static int count; }\n"
                + "@Immutable final class Value {\n"
                + "  static void incrementGlobal() { GlobalState.count++; }\n"
                + "}\n");
    }

    @Test
    void rejectsPublicNonFinalStaticField() {
        assertFails("example.Configuration", HEADER
                + "@Immutable final class Configuration { public static int version; }\n",
                "[IC004]", "Configuration.<static>.version", "non-private, non-final static field",
                "after class initialization");
    }

    @Test
    void rejectsProtectedNonFinalStaticField() {
        assertFails("example.Configuration", HEADER
                + "@Immutable final class Configuration { protected static int version; }\n",
                "[IC004]", "Configuration.<static>.version", "directly writable after class initialization");
    }

    @Test
    void rejectsPackagePrivateNonFinalStaticField() {
        assertFails("example.Configuration", HEADER
                + "@Immutable final class Configuration { static int version; }\n",
                "[IC004]", "Configuration.<static>.version", "directly writable after class initialization");
    }

    @Test
    void rejectsStaticFieldWriteInStaticMethod() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count; static void increment() { count++; }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "Value.increment()",
                "after class initialization");
    }

    @Test
    void rejectsStaticFieldWriteInInstanceMethod() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count; void increment() { count++; }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "Value.increment()",
                "after class initialization");
    }

    @Test
    void rejectsThisQualifiedStaticFieldWriteInConstructor() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count; Value() { this.count++; }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "Value()", "after class initialization");
    }

    @Test
    void rejectsStaticFieldWriteFromNestedType() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count;\n"
                + "  static final class Mutator { void mutate() { Value.count++; } }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "Mutator.mutate()",
                "after class initialization");
    }

    @Test
    void rejectsStaticFieldWriteFromEnclosingNestmate() {
        assertFails("example.Outer", HEADER
                + "class Outer {\n"
                + "  @Immutable static final class Value { private static int count; }\n"
                + "  void mutate() { Value.count++; }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "Outer.mutate()",
                "after class initialization");
    }

    @Test
    void rejectsDeferredLambdaStaticWriteDeclaredInStaticInitializer() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count;\n"
                + "  static { Runnable deferred = () -> count++; }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "lambda in static initializer of Value",
                "after class initialization");
    }

    @Test
    void rejectsStaticInitializerHelperConservatively() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count;\n"
                + "  static { initialize(); }\n"
                + "  private static void initialize() { count = 1; }\n"
                + "}\n",
                "[IC006]", "Value.<static>.count", "Value.initialize()",
                "static-initialization-only helper reachability");
    }

    @Test
    void rejectsInstanceFieldWriteFromStaticInitializer() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private int state;\n"
                + "  static { Value value = new Value(); value.state = 1; }\n"
                + "}\n",
                "[IC006]", "Value.state", "static initializer of Value",
                "outside instance construction");
    }

    @Test
    void rejectsStaticArrayFieldAsUnproven() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { private static final byte[] DATA = new byte[0]; }\n",
                "[IC005]", "Value.<static>.DATA", "outside the current recursive proof model");
    }

    @Test
    void rejectsStaticCollectionFieldAsUnproven() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static final java.util.List<String> VALUES = null;\n"
                + "}\n",
                "[IC005]", "Value.<static>.VALUES", "java.util.List<java.lang.String>",
                "unresolved runtime subtype analysis");
    }

    @Test
    void rejectsStaticUnavailableExternalReference() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { private static final java.time.Instant NOW = null; }\n",
                "[IC005]", "Value.<static>.NOW", "java.time.Instant", "source is unavailable",
                "no trusted semantic model");
    }

    @Test
    void rejectsStaticReferenceToCustomClassWithInstanceMutator() {
        assertFails("example.Registry", HEADER
                + "final class Metadata {\n"
                + "  private String name; void rename(String name) { this.name = name; }\n"
                + "}\n"
                + "@Immutable final class Registry {\n"
                + "  private static final Metadata METADATA = new Metadata();\n"
                + "}\n",
                "[IC006]", "Registry.<static>.METADATA -> Metadata.name", "Metadata.rename()",
                "outside instance construction");
    }

    @Test
    void rejectsStaticReferenceToCustomClassWithStaticMutator() {
        assertFails("example.Registry", HEADER
                + "final class Metadata {\n"
                + "  private static int revision; static void revise() { revision++; }\n"
                + "}\n"
                + "@Immutable final class Registry {\n"
                + "  private static final Metadata METADATA = new Metadata();\n"
                + "}\n",
                "[IC006]", "Registry.<static>.METADATA -> Metadata.<static>.revision",
                "Metadata.revise()", "after class initialization");
    }

    @Test
    void rejectsStaticSuperclassMutationWithCompletePath() {
        assertFails("example.Child", HEADER
                + "class Parent {\n"
                + "  private static int counter; static void increment() { counter++; }\n"
                + "}\n"
                + "@Immutable final class Child extends Parent {}\n",
                "[IC006]", "Child.<superclass> -> Parent.<static>.counter",
                "Parent.increment()", "after class initialization");
    }

    @Test
    void staticCycleContainingViolationTerminatesAndReportsIt() {
        assertFails("example.A", HEADER
                + "@Immutable final class A { private static final B B_VALUE = null; }\n"
                + "final class B {\n"
                + "  private static final A A_VALUE = null;\n"
                + "  private static int revision; static void revise() { revision++; }\n"
                + "}\n",
                "[IC006]", "A.<static>.B_VALUE -> B.<static>.revision", "B.revise()",
                "after class initialization");
    }

    @Test
    void diagnosticsDistinguishBothFreezeBoundaries() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private int value; private static int count;\n"
                + "  void mutate() { value++; count++; }\n"
                + "}\n");
        assertFalse(result.isSuccessful());
        String diagnostics = result.joinedErrors();
        assertTrue(diagnostics.contains("Value.value"), diagnostics);
        assertTrue(diagnostics.contains("outside instance construction"), diagnostics);
        assertTrue(diagnostics.contains("Value.<static>.count"), diagnostics);
        assertTrue(diagnostics.contains("after class initialization"), diagnostics);
    }

    @Test
    void staticDiagnosticOrderingRemainsDeterministic() {
        String source = HEADER
                + "@Immutable final class Value {\n"
                + "  private static final byte[] first = new byte[0];\n"
                + "  public static int second;\n"
                + "  static void mutate() { second++; }\n"
                + "}\n";
        CompilerTestHarness.CompilationResult first = compiler.compile("example.Value", source);
        CompilerTestHarness.CompilationResult second = compiler.compile("example.Value", source);
        assertFalse(first.isSuccessful());
        assertFalse(second.isSuccessful());
        assertEquals(first.joinedErrors(), second.joinedErrors());
        String diagnostics = first.joinedErrors();
        int firstPath = diagnostics.indexOf("Value.<static>.first");
        int writable = diagnostics.indexOf("[IC004]");
        int write = diagnostics.indexOf("[IC006]");
        assertTrue(firstPath >= 0 && firstPath < writable && writable < write, diagnostics);
    }

    @Test
    void doesNotDuplicateStaticViolationAcrossRounds() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private static int count; static void increment() { count++; }\n"
                + "}\n");
        assertFalse(result.isSuccessful());
        assertEquals(1, occurrences(result.getErrors(), "[IC006]"), result.joinedErrors());
    }

    private void assertPasses(String className, String source) {
        CompilerTestHarness.CompilationResult result = compiler.compile(className, source);
        assertTrue(result.isSuccessful(), result.joinedErrors());
        assertTrue(result.getErrors().isEmpty(), result.joinedErrors());
    }

    private void assertFails(String className, String source, String... fragments) {
        CompilerTestHarness.CompilationResult result = compiler.compile(className, source);
        assertFalse(result.isSuccessful(), "Expected compilation to fail");
        String diagnostics = result.joinedErrors();
        for (String fragment : fragments) {
            assertTrue(diagnostics.contains(fragment),
                    "Expected diagnostic fragment <" + fragment + "> in:\n" + diagnostics);
        }
    }

    private static int occurrences(List<String> messages, String fragment) {
        int count = 0;
        for (String message : messages) {
            int from = 0;
            while ((from = message.indexOf(fragment, from)) >= 0) {
                count++;
                from += fragment.length();
            }
        }
        return count;
    }
}
