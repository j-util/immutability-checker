package io.github.jutil.immutability.internal.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regressions for state flow through varargs, qualified creation and assertions. */
class ValueFlowSoundnessRegressionTest {
    private static final String HEADER = "import io.github.jutil.immutability.Immutable; import java.util.*; ";
    private static final String OWNED = "@Immutable final class Names { private List<String> values = new ArrayList<String>();"
            + "Names() { values.add(\"original\"); }";
    private static final String OUTER = "class Outer { Outer(int ignored) {} class Inner { Inner() {} Inner(Object ignored) {} } }";
    private final CompilerTestHarness compiler = new CompilerTestHarness();

    @Test void rejectsRetainedCollectionInVarargs() {
        reject("@Immutable final class Names { private List<String> values = new ArrayList<String>();"
                + "Names() { values.add(\"original\"); } public void mutate() { wipe(values); }"
                + "@SafeVarargs private static void wipe(List<String>... lists) { lists[0].clear(); }"
                + "public int size() { return values.size(); }}", "[IC005]");
    }

    @Test void rejectsQualifiedEnclosingWrites() {
        reject("@Immutable final class Names { private int state;"
                + "public void mutate() { new Outer(state++).new Inner(); }"
                + "public int value() { return state; }}"
                + "class Outer { Outer(int ignored) {} class Inner {} }", "[IC006]", "Names.state");
    }

    @Test void rejectsAliasReplacementWithAssertionsDisabled() {
        reject("@Immutable final class Names { private List<String> values = new ArrayList<String>();"
                + "Names() { values.add(\"original\"); } public void mutate() { List<String> alias = values;"
                + "assert (alias = new ArrayList<String>()) != null; alias.clear(); }"
                + "public int size() { return values.size(); }}", "[IC006]", "Names.values");
    }

    @Test void rejectsEveryExpandedVarargsElementWithFixedPrefix() {
        reject(OWNED + "public void mutate() { wipe(1, new ArrayList<String>(), values, new ArrayList<String>()); }"
                + "@SafeVarargs private static void wipe(int ignored, List<String>... lists) { lists[1].clear(); }}",
                "[IC005]", "variable-arity", "Names.values");
    }

    @Test void rejectsObjectVarargsAndExplicitArrayAliases() {
        reject(OWNED + "public void mutate() { Object alias = values; wipe(\"prefix\", alias); }"
                + "private static void wipe(Object... lists) { ((List<?>) lists[1]).clear(); }}", "[IC005]", "implicit varargs array");
        reject(OWNED + "public void mutate() { Object[] aliases = { values }; wipe(aliases); }"
                + "private static void wipe(Object... lists) { ((List<?>) lists[0]).clear(); }}", "[IC005]", "stored into an array");
    }

    @Test void rejectsFreshVarargsOriginsBeforeLaterRetention() {
        reject("@Immutable final class Names { private List<String> values; Names() {"
                + "List<String> first = new ArrayList<String>(); List<String> second = new ArrayList<String>();"
                + "keep(first, second); values = second; }"
                + "private static void keep(Object... lists) { External.alias = lists[1]; }}"
                + "class External { static Object alias; }", "[IC005]", "implicit varargs array", "Names.values");
        reject("@Immutable final class Names { private static List<String> values; static {"
                + "List<String> a = new ArrayList<String>(); keep(\"prefix\", a); values = a; }"
                + "private static void keep(Object... lists) {} }", "[IC005]", "Names.<static>.values");
    }

    @Test void preservesAllVarargsArgumentEffects() {
        reject(OWNED + "private int state; private Object update() { state++; return null; }"
                + "public void mutate() { wipe(values, update()); }"
                + "private static void wipe(Object... ignored) {} }", "[IC005]", "[IC006]", "Names.state", "update()");
    }

    @Test void checksVarargsInstanceAndStaticFreezeBoundaries() {
        reject(OWNED + "public void mutate() { wipe(values); }"
                + "private static void wipe(Object... lists) { ((List<?>) lists[0]).clear(); }}", "[IC005]");
        reject("@Immutable final class Names { private static List<String> values = new ArrayList<String>();"
                + "public static void mutate() { wipe(values); }"
                + "private static void wipe(Object... lists) { ((List<?>) lists[0]).clear(); }}", "[IC005]", "Names.<static>.values");
        reject("@Immutable final class Names { private List<String> values = new ArrayList<String>();"
                + "Names() { wipe(values); } private static void wipe(Object... ignored) {} }", "[IC005]", "implicit varargs array");
    }

    @Test void preservesFixedArgumentsAndHarmlessVarargs() {
        String helper = "private static void fill(List<String> target, int... ignored) { target.add(\"initial\"); }";
        accept("@Immutable final class Names { private List<String> values = new ArrayList<String>();"
                + "Names() { fill(values, 1, 2); }" + helper + "}");
        reject(OWNED + helper + "public void mutate() { fill(values, 1, 2); }}", "[IC006]");
        accept(OWNED + "public void unrelated(List<String> input) { wipe(input, new ArrayList<String>()); }"
                + "@SafeVarargs private static void wipe(List<String>... lists) { lists[0].clear(); }"
                + "public void local() { take(); take(\"a\", 1); take(new Object[] {\"a\"}); take((Object[]) null); }"
                + "private static void take(Object... ignored) {} }");
    }

    @Test void rejectsQualifiedEnclosingHelperEffects() {
        reject(OWNED + "private Outer enclosing() { values.clear(); return new Outer(0); }"
                + "public void mutate() { enclosing().new Inner(); }}" + OUTER, "[IC006]", "enclosing()");
        reject("@Immutable final class Names { private static int state;"
                + "public static void mutate() { new Outer(state++).new Inner(); }}" + OUTER,
                "[IC006]", "Names.<static>.state");
    }

    @Test void evaluatesQualifiedEnclosingAliasesBeforeArguments() {
        accept(OWNED + "public void local() { List<String> alias = values;"
                + "new Outer((alias = new ArrayList<String>()).size()).new Inner(alias.size()); alias.clear(); }}" + OUTER);
        reject(OWNED + "public void leak() { List<String> alias = new ArrayList<String>();"
                + "new Outer((alias = values).size()).new Inner(alias); }}" + OUTER, "[IC005]", "unmodeled constructor");
    }

    @Test void checksQualifiedReceiverEscapeAndInitialization() {
        reject("@Immutable final class Names { Names() { this.new Inner(); } class Inner {} }",
                "[IC005]", "receiver escapes before initialization", "enclosing instance");
        accept("@Immutable final class Names { private int state; Names() { new Outer(state++).new Inner(); }"
                + "public int value() { return state; } public void local() { new Outer(state).new Inner(); }}" + OUTER);
    }

    @Test void rejectsAssertionDetailAliasReplacement() {
        reject(OWNED + "public void mutate(boolean enabled) { List<String> alias = values;"
                + "assert enabled : (alias = new ArrayList<String>()); alias.clear(); }}", "[IC006]", "Names.values");
    }

    @Test void checksEffectsInEnabledAssertionPaths() {
        reject("@Immutable final class Names { private int state; public void mutate() { assert ++state > 0; }}",
                "[IC006]", "Names.state");
        reject(OWNED + "private int clear() { values.clear(); return 0; }"
                + "public void mutate(boolean enabled) { assert enabled : clear(); }}", "[IC006]", "clear()");
        reject("@Immutable final class Names { private static int state;"
                + "public static void mutate(boolean enabled) { assert enabled : ++state; }}", "[IC006]", "Names.<static>.state");
    }

    @Test void preservesAssertionAliasesThroughCatchAndFinally() {
        for (String assertion : new String[]{"assert (alias = values) == null;", "assert false : (alias = values);"}) {
            reject(OWNED + "public void mutate() { List<String> alias = new ArrayList<String>(); try {"
                    + assertion + "} catch (AssertionError expected) { alias.clear(); } }}", "[IC006]", "Names.values");
            reject(OWNED + "public void mutate() { List<String> alias = new ArrayList<String>(); try {"
                    + assertion + "} finally { alias.clear(); } }}", "[IC006]", "Names.values");
        }
    }

    @Test void preservesSafeAssertionNormalContinuation() {
        accept(OWNED + "public void local(boolean enabled) { List<String> alias = new ArrayList<String>();"
                + "assert enabled : (alias = values); alias.clear(); }"
                + "private int clear() { values.clear(); return 0; } public void harmless() { assert true : clear(); }}");
        accept("@Immutable final class Names { private int state; private static int setting;"
                + "static { assert (setting = 1) == 1; } Names() { assert (state = 1) == 1; }"
                + "public int value() { assert state >= 0 : setting; return state; }}");
        reject("@Immutable final class Names { private List<String> values;"
                + "Names() { assert (values = new ArrayList<String>()) != null; }}", "[IC005]", "allocation is conditional or absent");
    }

    private void accept(String body) {
        CompilerTestHarness.CompilationResult result = compiler.compile("Names", HEADER + body);
        assertTrue(result.isSuccessful(), result.joinedErrors());
    }

    private void reject(String body, String... fragments) {
        CompilerTestHarness.CompilationResult result = compiler.compile("Names", HEADER + body);
        assertFalse(result.isSuccessful(), "Expected checker rejection: " + body);
        assertFalse(result.getErrors().isEmpty(), "Expected checker diagnostics");
        for (String error : result.getErrors()) {
            assertTrue(error.startsWith("[IC"), "Fixture has a Java compilation error: " + error);
            assertFalse(error.startsWith("[IC000]"), "Expected a modeled rejection: " + error);
        }
        for (String fragment : fragments) {
            assertTrue(result.joinedErrors().contains(fragment), result.joinedErrors());
        }
    }
}
