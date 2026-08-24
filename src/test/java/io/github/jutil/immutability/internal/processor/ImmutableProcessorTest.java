package io.github.jutil.immutability.internal.processor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmutableProcessorTest {

    private static final String HEADER = "package example;\n"
            + "import io.github.jutil.immutability.Immutable;\n";

    private final CompilerTestHarness compiler = new CompilerTestHarness();

    @Test
    void ignoresUnannotatedMutableClass() {
        assertPasses("example.Mutable", "package example; class Mutable { int value; void set() { value++; } }");
    }

    @Test
    void acceptsAnnotatedEmptyClass() {
        assertPasses("example.Empty", HEADER + "@Immutable final class Empty {}\n");
    }

    @Test
    void acceptsPrivateNonFinalPrimitiveAssignedInConstructor() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private int value;\n"
                + "  Value(int value) { this.value = value; }\n"
                + "}\n");
    }

    @Test
    void acceptsPrivateNonFinalStringAssignedInConstructor() {
        assertPasses("example.Currency", HEADER
                + "@Immutable final class Currency {\n"
                + "  private String code;\n"
                + "  Currency(String code) { this.code = code; }\n"
                + "  String code() { return code; }\n"
                + "}\n");
    }

    @Test
    void acceptsSupportedFieldInitializer() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { private int value = 1; }\n");
    }

    @Test
    void acceptsSupportedInstanceInitializerAssignment() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { private int value; { value = 1; } }\n");
    }

    @Test
    void acceptsPublicFinalKnownLeaf() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { public final java.util.UUID value;\n"
                + "  Value(java.util.UUID value) { this.value = value; } }\n");
    }

    @Test
    void acceptsEveryExplicitKnownLeaf() {
        assertPasses("example.Leaves", HEADER
                + "@Immutable final class Leaves {\n"
                + "  private Boolean bool; private Byte b; private Short s; private Integer i;\n"
                + "  private Long l; private Character c; private Float f; private Double d;\n"
                + "  private String text; private java.math.BigInteger bigInteger;\n"
                + "  private java.math.BigDecimal bigDecimal; private java.util.UUID uuid;\n"
                + "}\n");
    }

    @Test
    void acceptsStaticMemberClass() {
        assertPasses("example.Outer", HEADER
                + "class Outer { @Immutable static final class Value { private int value;\n"
                + "  Value(int value) { this.value = value; } } }\n");
    }

    @Test
    void acceptsImplementedInterface() {
        assertPasses("example.Value", HEADER
                + "interface Named { String name(); }\n"
                + "@Immutable final class Value implements Named { private String name;\n"
                + "  Value(String name) { this.name = name; } public String name() { return name; } }\n");
    }

    @Test
    void acceptsPrivateVolatileFieldWithoutPostConstructionWrite() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { private volatile int value;\n"
                + "  Value(int value) { this.value = value; } }\n");
    }

    @Test
    void acceptsConstructorChaining() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { private int value; Value() { this(1); }\n"
                + "  Value(int value) { this.value = value; } }\n");
    }

    @Test
    void ignoresMutationOfFreshLocalTemporary() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { String render() {\n"
                + "  StringBuilder builder = new StringBuilder(); builder.append(1); return builder.toString();\n"
                + "} }\n");
    }

    @Test
    void ignoresMutationOfMethodParameter() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { void add(java.util.List<String> target) { target.add(\"x\"); } }\n");
    }

    @Test
    void ignoresMutationOfStaticState() {
        assertPasses("example.Value", HEADER
                + "@Immutable final class Value { static int count; static void increment() { count++; } }\n");
    }

    @Test
    void acceptsRecordWithKnownLeafComponentOnModernJdk() {
        assumeRecordsSupported();
        assertPasses("example.Identifier", HEADER
                + "@Immutable record Identifier(String value) {}\n");
    }

    @Test
    void rejectsInterface() {
        assertFails("example.Bad", HEADER + "@Immutable interface Bad {}\n", "[IC001]", "example.Bad");
    }

    @Test
    void rejectsAnnotationType() {
        assertFails("example.Bad", HEADER + "@Immutable @interface Bad {}\n", "[IC001]", "example.Bad");
    }

    @Test
    void rejectsEnum() {
        assertFails("example.Bad", HEADER + "@Immutable enum Bad { VALUE }\n", "[IC001]", "example.Bad");
    }

    @Test
    void rejectsNonStaticInnerClass() {
        assertFails("example.Outer", HEADER
                + "class Outer { @Immutable final class Inner {} }\n",
                "[IC002]", "Inner", "enclosing-instance state");
    }

    @Test
    void rejectsLocalClass() {
        assertFails("example.Outer", HEADER
                + "class Outer { void create() { @Immutable class Local {} } }\n",
                "[IC002]", "Local", "enclosing-instance state");
    }

    @Test
    void rejectsUnanalyzedSuperclass() {
        assertFails("example.Child", HEADER
                + "class Parent {} @Immutable final class Child extends Parent {}\n",
                "[IC003]", "Child.<superclass>", "example.Parent");
    }

    @Test
    void rejectsPublicNonFinalField() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { public int value; }\n",
                "[IC004]", "Value.value", "directly writable");
    }

    @Test
    void rejectsProtectedNonFinalField() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { protected int value; }\n",
                "[IC004]", "Value.value", "directly writable");
    }

    @Test
    void rejectsPackagePrivateNonFinalField() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { int value; }\n",
                "[IC004]", "Value.value", "directly writable");
    }

    @Test
    void rejectsSimpleAssignmentInInstanceMethod() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void reset() { value = 0; } }\n",
                "[IC006]", "Counter.value", "Counter.reset()");
    }

    @Test
    void rejectsCompoundAssignmentInInstanceMethod() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void add(int amount) { value += amount; } }\n",
                "[IC006]", "Counter.value", "Counter.add()");
    }

    @Test
    void rejectsPrefixIncrement() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void change() { ++value; } }\n",
                "[IC006]", "Counter.value");
    }

    @Test
    void rejectsPostfixIncrement() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void change() { value++; } }\n",
                "[IC006]", "Counter.value");
    }

    @Test
    void rejectsPrefixDecrement() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void change() { --value; } }\n",
                "[IC006]", "Counter.value");
    }

    @Test
    void rejectsPostfixDecrement() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void change() { value--; } }\n",
                "[IC006]", "Counter.value");
    }

    @Test
    void rejectsStaticMethodMutatingAnnotatedInstance() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value;\n"
                + "  static void reset(Counter counter) { counter.value = 0; } }\n",
                "[IC006]", "Counter.value", "Counter.reset()");
    }

    @Test
    void rejectsConstructorWritingAnotherInstance() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value;\n"
                + "  Counter(Counter other) { other.value = 0; } }\n",
                "[IC006]", "receiver not proven to be the object under construction");
    }

    @Test
    void rejectsNestedTypeMutatingOuterAnnotatedInstance() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value;\n"
                + "  final class Mutator { void reset() { Counter.this.value = 0; } } }\n",
                "[IC006]", "Counter.value", "Mutator.reset()");
    }

    @Test
    void rejectsEnclosingNestmateMutatingAnnotatedMemberType() {
        assertFails("example.Outer", HEADER
                + "class Outer { @Immutable static final class Value { private int value; }\n"
                + "  void reset(Value value) { value.value = 0; } }\n",
                "[IC006]", "Value.value", "Outer.reset()");
    }

    @Test
    void rejectsLambdaWriteDeclaredInConstructor() {
        assertFails("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; Counter() {\n"
                + "  Runnable deferred = () -> value++; } }\n",
                "[IC006]", "Counter.value", "lambda in Counter()");
    }

    @Test
    void rejectsConstructorOnlyHelperConservatively() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { private int value; Value(int value) { initialize(value); }\n"
                + "  private void initialize(int value) { this.value = value; } }\n",
                "[IC006]", "Value.value", "constructor-only helper reachability");
    }

    @Test
    void rejectsArrayFieldAsUnprovenReferenceState() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { private final byte[] value = new byte[0]; }\n",
                "[IC005]", "Value.value", "reachable-reference verification is not implemented");
    }

    @Test
    void rejectsListFieldAsUnprovenReferenceState() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names { private final java.util.List<String> values;\n"
                + "  Names(java.util.List<String> values) { this.values = values; } }\n",
                "[IC005]", "Names.values", "java.util.List<java.lang.String>");
    }

    @Test
    void rejectsUserDefinedReferenceField() {
        assertFails("example.Value", HEADER
                + "class State {} @Immutable final class Value { private final State state = new State(); }\n",
                "[IC005]", "Value.state", "example.State");
    }

    @Test
    void rejectsUserEnumField() {
        assertFails("example.Value", HEADER
                + "enum State { READY } @Immutable final class Value { private final State state = State.READY; }\n",
                "[IC005]", "Value.state", "example.State");
    }

    @Test
    void rejectsFinalUnknownReferenceField() {
        assertFails("example.Value", HEADER
                + "@Immutable final class Value { private final Object value = new Object(); }\n",
                "[IC005]", "Value.value", "java.lang.Object");
    }

    @Test
    void rejectsRecordWithListComponentOnModernJdk() {
        assumeRecordsSupported();
        assertFails("example.Names", HEADER
                + "@Immutable record Names(java.util.List<String> values) {}\n",
                "[IC005]", "Names.values", "reachable-reference verification is not implemented");
    }

    @Test
    void emitsDiagnosticsInSourceOrder() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Value", HEADER
                + "@Immutable final class Value {\n"
                + "  private final java.util.List<String> first = null;\n"
                + "  public int second;\n"
                + "  void mutate() { second = 1; }\n"
                + "}\n");
        assertFalse(result.isSuccessful());
        String diagnostics = result.joinedErrors();
        int reference = diagnostics.indexOf("[IC005]");
        int writable = diagnostics.indexOf("[IC004]");
        int write = diagnostics.indexOf("[IC006]");
        assertTrue(reference >= 0 && reference < writable && writable < write, diagnostics);
    }

    @Test
    void doesNotDuplicateViolationAcrossRounds() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Counter", HEADER
                + "@Immutable final class Counter { private int value; void mutate() { value++; } }\n");
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

    private static void assumeRecordsSupported() {
        String specificationVersion = System.getProperty("java.specification.version");
        int feature = specificationVersion.startsWith("1.")
                ? Integer.parseInt(specificationVersion.substring(2))
                : Integer.parseInt(specificationVersion);
        Assumptions.assumeTrue(feature >= 16, "Records require JDK 16 or later");
    }
}
