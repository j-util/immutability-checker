package io.github.jutil.immutability.internal.processor;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import javax.lang.model.SourceVersion;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Public compiler contract for the 0.2 bounded call/value-flow milestone. */
class ValueFlowVerificationTest {
    private static final String HEADER = "import io.github.jutil.immutability.Immutable; import java.util.*; ";
    private static final String OWNED = "@Immutable public final class Names { private List<String> values;"
            + "public Names(List<String> input) { initialize(input); }"
            + "private void initialize(List<String> input) { values = new ArrayList<>(input); addInitial(values); }"
            + "private static void addInitial(List<String> target) { target.add(\"initial\"); }"
            + "private static int count(List<String> target) { return target.size(); }"
            + "public int size() { return count(values); }"
            + "public List<String> values() { return new ArrayList<>(values); }";
    private static final String SNAPSHOT = "@Immutable public final class Names { private List<String> values;"
            + "public Names(List<String> input) { this.values = forward(snapshot(input)); }"
            + "private static List<String> snapshot(List<String> input) { return List.copyOf(input); }"
            + "private static List<String> forward(List<String> input) { return input; }"
            + "private static int count(List<String> input) { return input.size(); }"
            + "public List<String> values() { return forward(values); }"
            + "public int size() { return count(values); }";
    private final CompilerTestHarness compiler = new CompilerTestHarness();

    @Test void exampleA() { modern(); pass(SNAPSHOT + "}"); }
    @Test void exampleABorrowedVariant() {
        modern(); fail(SNAPSHOT.replace("forward(snapshot(input))", "forward(input)") + "}", "borrowed mutable state retained");
    }
    @Test void exampleB() { pass(OWNED + "}"); }
    @Test void exampleBResetVariant() {
        fail(OWNED + "public void reset(List<String> input) { initialize(input); }}", "[IC006]", "initialize()");
    }
    @Test void exampleBAddLaterVariant() {
        fail(OWNED + "public void addLater() { addInitial(values); }}", "[IC006]", "addInitial()");
    }
    @Test void directSnapshotAndNestedCount() {
        modern(); pass("@Immutable final class Names { private List<String> values;"
                + "Names(List<String> input) { values = List.copyOf(input); }"
                + "List<String> values() { return values; }"
                + "int size() { return count(List.copyOf(values)); }"
                + "static int count(List<String> a) { return a.size(); }}");
    }
    @Test void snapshotSharingAndThrowingMutator() {
        modern(); pass("@Immutable final class Names { public final List<String> values; private List<String> alias;"
                + "Names(List<String> input) { values = List.copyOf(input); alias = values; }"
                + "void attempt() { try { alias.add(\"x\"); } catch (UnsupportedOperationException expected) {} }}");
    }
    @Test void snapshotDoesNotThawOrFreezeBackingContainer() {
        modern(); pass("@Immutable final class Names { private List<String> values = new ArrayList<>(); private List<String> snap;"
                + "Names() { values.add(\"a\"); snap = List.copyOf(values); values.add(\"b\"); } List<String> get() { return snap; }}");
        fail("@Immutable final class Names { private List<String> values = new ArrayList<>();"
                + "List<String> get() { return List.copyOf(values); } void change() { values.clear(); }}", "[IC006]");
    }
    @Test void safeAndUnsafeBranchesIncludingEarlyReturns() {
        modern();
        String base = "@Immutable final class Names { private List<String> values; Names(List<String> input, boolean flag) { values = choose(input, flag); }"
                + "private static List<String> choose(List<String> a, boolean b) { if (b) return List.copyOf(a); return %s; } List<String> get() { return values; }}";
        pass(String.format(base, "List.copyOf(a)"));
        fail(String.format(base, "a"), "borrowed mutable state retained");
        pass(String.format(base, "b ? List.copyOf(a) : List.copyOf(a)"));
        fail(String.format(base, "b ? List.copyOf(a) : a"), "borrowed mutable state retained");
    }
    @Test void sourceElementProofIncludesStaticStateAndCycles() {
        modern();
        String root = "@Immutable final class Names { private List<Item> values; Names(List<Item> input) { values = List.copyOf(input); } List<Item> get() { return values; }}";
        pass(root + "final class Item { private String text; Item(String text) { this.text = text; } }");
        fail(root + "final class Item { private int state; void mutate() { state++; } }", "element -> Item.state");
        fail(root + "final class Item { private static int state; static void mutate() { state++; } }", "element -> Item.<static>.state");
        pass(root + "final class Item { private Item next; Item(Item next) { this.next = next; } }");
        fail(root + "final class Item { private Item next; private int state; void mutate() { state++; } }", "Item.state");
    }
    @Test void borrowedLookalikeCopyIsNotProof() {
        modern(); fail("@Immutable final class Names { private List<String> values; Names(List<String> a) { values = copyOf(a); }"
                + "static List<String> copyOf(Collection<String> a) { return (List<String>) a; }}", "borrowed mutable state retained");
    }
    @Test void sharedHelperIsInstantiatedPerCall() {
        modern(); pass(SNAPSHOT + "public List<String> unrelated(List<String> mutable) { return forward(mutable); }}");
        pass(SNAPSHOT + "public void later() { List<String> a = forward(values); a.clear(); }}");
    }
    @Test void freshHelpersAndIndependentCopies() {
        pass("@Immutable final class Names { private List<String> a; private List<String> b;"
                + "Names(List<String> input) { a = copy(input); b = copy(input); fill(a, b); }"
                + "static List<String> copy(List<String> input) { return new ArrayList<>(input); }"
                + "private static void fill(List<String> x, List<String> y) { x.add(\"x\"); y.addAll(x); }"
                + "List<String> get() { return copy(a); }}");
    }
    @Test void supportedSetsAndMapsThroughHelpers() {
        for (String impl : new String[]{"HashSet", "LinkedHashSet"}) {
            pass("@Immutable final class Names { private Set<String> v; Names(Set<String> a) { v = copy(a); fill(v); }"
                    + "private static Set<String> copy(Set<String> a) { return new " + impl + "<>(a); }"
                    + "private static void fill(Set<String> a) { a.add(\"x\"); } Set<String> get() { return copy(v); }}");
        }
        for (String impl : new String[]{"HashMap", "LinkedHashMap"}) {
            pass("@Immutable final class Names { private Map<String,String> v; Names(Map<String,String> a) { v = copy(a); fill(v); }"
                    + "private static Map<String,String> copy(Map<String,String> a) { return new " + impl + "<>(a); }"
                    + "private static void fill(Map<String,String> a) { a.put(\"k\", \"v\"); } Map<String,String> get() { return copy(v); }}");
        }
    }
    @Test void argumentAliasingAndReturnedAliasesRemainVisible() {
        pass(OWNED + "private static int both(List<String> a, List<String> b) { return a.size()+b.size(); } public int both() { return both(values, values); }}");
        fail(OWNED + "private static List<String> identity(List<String> a) { return a; } public void change() { identity(values).clear(); }}", "[IC006]");
        fail(OWNED + "private static List<String> identity(List<String> a) { return a; } public Object leak() { return identity(values); }}", "escapes through return");
    }
    @Test void helperLeaksBeforeAndAfterRetention() {
        fail(OWNED + "private static Object leak; private static void save(List<String> a) { leak = a; } public void expose() { save(values); }}", "alias is assigned", "save()");
        fail("@Immutable final class Names { private List<String> values; Names() { values = make(); }"
                + "static List<String> make() { List<String> a = new ArrayList<>(); External.value = a; return a; }}"
                + "class External { static Object value; }", "alias is assigned");
    }
    @Test void unrelatedArgumentsMayMutate() {
        pass(OWNED + "public void unrelated(List<String> input) { addInitial(input); }}");
    }
    @Test void objectAliasesAndCastsKeepOrigins() {
        pass(OWNED + "private static int read(Object x) { return ((List<String>) x).size(); } public int read() { Object alias = values; return read(alias); }}");
        fail(OWNED + "public void bad() { Object alias = values; ((List<String>) alias).clear(); }}", "[IC006]");
        fail("@Immutable final class Names { private List<String> v; Names(Object x) { v = (List<String>) x; }}", "borrowed mutable state retained");
    }
    @Test void rawFlowCannotLaunderElementProof() {
        modern(); fail("@Immutable final class Names { private List<String> v; Names(List raw) { v = snapshot(raw); }"
                + "private static List<String> snapshot(List<String> input) { return List.copyOf(input); }}", "raw or unchecked");
    }
    @Test void privateStaticInitializationAndLaterReachability() {
        String base = "@Immutable final class Names { private static List<String> v; static { initialize(); }"
                + "private static void initialize() { v = new ArrayList<>(); v.add(\"x\"); }";
        pass(base + "static int size() { return v.size(); }}");
        fail(base + "public static void reset() { initialize(); }}", "after class initialization");
        fail(base + "Names() { initialize(); }}", "after class initialization");
    }
    @Test void constructorChains() {
        pass(OWNED + "public Names() { this(new ArrayList<String>()); }}");
    }
    @Test void nestmateDeferredAndMethodReferenceReachability() {
        fail(OWNED + "static class Nested { static void reset(Names n, List<String> a) { n.initialize(a); } }}", "[IC006]", "initialize()");
        fail(OWNED.replace("addInitial(values);", "addInitial(values); Runnable r = () -> addInitial(values);") + "}", "[IC006]");
        fail(OWNED.replace("addInitial(values);", "addInitial(values); java.util.function.Consumer<List<String>> c = this::initialize;") + "}", "deferred effects are unproven");
    }
    @Test void exactFinalReceiverAndFinalMethod() {
        pass(OWNED + "public int read() { return new Reader().read(values); }} final class Reader { int read(List<String> a) { return a.size(); }}");
        pass(OWNED + "public int read() { return new Reader().read(values); }} class Reader { final int read(List<String> a) { return a.size(); }}");
        fail(OWNED + "public void read(Reader r) { r.read(values); }} class Reader { void read(List<String> a) {} }", "unresolved virtual dispatch");
    }
    @Test void separateSourceFileCallChain() {
        Map<String,String> sources = new LinkedHashMap<String,String>();
        sources.put("Names", HEADER + "@Immutable final class Names { private List<String> v; Names(List<String> a) { v = Helper.copy(a); } int size() { return Helper.count(v); }}");
        sources.put("Helper", "import java.util.*; final class Helper { static List<String> copy(List<String> a) { return next(a); } private static List<String> next(List<String> a) { return new ArrayList<>(a); } static int count(List<String> a) { return a.size(); }}");
        CompilerTestHarness.CompilationResult result = compiler.compile(sources);
        assertTrue(result.isSuccessful(), result.joinedErrors());
        Map<String,String> reversed = new LinkedHashMap<String,String>();
        reversed.put("Helper", sources.get("Helper"));
        reversed.put("Names", sources.get("Names"));
        result = compiler.compile(reversed);
        assertTrue(result.isSuccessful(), result.joinedErrors());
    }
    @Test void cyclesAndUnavailableBodiesRejectAccurately() {
        fail(OWNED + "private static int cycle(List<String> a) { return cycle(a); } public int read() { return cycle(values); }}", "recursive method-call cycle");
        fail(OWNED + "private static native void missing(List<String> a); public void read() { missing(values); }}", "unavailable");
    }
    @Test void exceptionAndLoopPathsKeepAliases() {
        modern(); fail("@Immutable final class Names { private List<String> v; Names(List<String> input) { try { v = List.copyOf(input); } catch (NullPointerException e) { v = input; } }}", "borrowed mutable state retained");
        fail(OWNED + "public void bad(boolean flag) { List<String> a = values; while (flag) { a = new ArrayList<>(); } a.clear(); }}", "[IC006]");
        fail(OWNED + "public void bad() { List<String> a = values; try { a = new ArrayList<>(); } finally { a.clear(); } }}", "[IC006]");
    }
    @Test void snapshotsDoNotHideOtherCallEffects() {
        modern(); fail(SNAPSHOT + "private static void inspect(List<String> safe, Names self) { self.values = safe; } public void bad() { inspect(values, this); }}", "[IC006]");
        fail(SNAPSHOT + "private static int state; private static void inspect(List<String> safe) { state++; } public void bad() { inspect(values); }}", "[IC006]");
    }
    @Test void noThawThroughAnotherInstance() {
        fail(OWNED + "public Names(Names other) { initialize(new ArrayList<String>()); other.values.clear(); }}", "[IC006]");
    }
    @Test void unsupportedFamiliesStayUnsupportedOnlyWhenRelevant() {
        modern();
        fail("@Immutable final class Names { private List<String> v = List.of(); }", "[IC005]");
        fail("@Immutable final class Names { private List<List<String>> v; Names(List<List<String>> a) { v = List.copyOf(a); }}", "nested inside collections");
        pass(OWNED + "public int local() { int[] a = new int[2]; a[0]++; return a[0]; }}");
    }
    @Test void diagnosticOrderAndProofIsolation() {
        String source = HEADER + OWNED + "public void bad() { addInitial(values); }}";
        String errors = compiler.compile("Names", source).joinedErrors();
        assertEquals(errors, compiler.compile("Names", source).joinedErrors());
        pass(OWNED + "}");
    }

    @Test void laterMutationAfterConditionalSnapshotCannotBeHidden() {
        modern();
        fail(OWNED + "public void bad(boolean b) { List<String> a = b ? List.copyOf(values) : values; a.clear(); }}", "[IC006]");
        fail(OWNED + "public void bad(boolean b) { List<String> a = values; if (b) a = List.copyOf(a); a.clear(); }}", "[IC006]");
    }
    @Test void snapshotsMayBeRetainedByUnknownCodeButMutableContainersMayNot() {
        modern();
        pass(SNAPSHOT + "public void expose() { External.keep(values); }} class External { static native void keep(Object x); }");
        fail(OWNED + "public void expose() { External.keep(values); }} class External { static native void keep(Object x); }", "effects are unproven");
    }
    @Test void unrelatedRecursionDoesNotRejectVerifiedState() {
        pass(OWNED + "public int factorial(int n) { return n == 0 ? 1 : n * factorial(n - 1); }}");
    }
    @Test void snapshotStillRejectsUnmodeledOperationsAndIndependentEffects() {
        modern();
        fail(SNAPSHOT + "public Object[] bad() { return values.toArray(); }}", "unresolved effect");
        fail(OWNED + "private static List<String> alias(List<String> a) { return a; } public void bad() { List.copyOf(values).addAll(alias(values)); alias(values).clear(); }}", "[IC006]");
    }
    @Test void sourceLookalikeCannotHideLeakOrMutation() {
        fail(OWNED + "private static int countAndChange(List<String> a) { a.clear(); return a.size(); } public int bad() { return countAndChange(values); }}", "[IC006]");
    }
    @Test void staticSnapshotThroughHelperAndAlias() {
        modern();
        pass("@Immutable final class Names { private static List<String> a = make(); private static List<String> b; static { b = a; }"
                + "private static List<String> make() { return List.copyOf(new ArrayList<String>()); } static List<String> get() { return b; }}");
    }

    @Test void exactFinalReceiverUsesItsOverride() {
        fail(OWNED + "public void bad() { Reader r = new Evil(); r.read(values); }}"
                + "class Reader { void read(List<String> a) {} } final class Evil extends Reader { @Override void read(List<String> a) { a.clear(); }}", "[IC006]", "Evil.read()");
    }

    @Test void nestedControlFlowPreservesExceptionalAliasHistory() {
        for (String control : new String[]{"if (flag) {}", "while (flag) {}", "try {} finally {}"}) {
            fail(OWNED + "public void bad(boolean flag) { List<String> a = new ArrayList<>(); try { a = values; External.maybe(); a = new ArrayList<>(); "
                    + control + " } catch (RuntimeException e) { a.clear(); } }} class External { static native void maybe(); }", "[IC006]");
        }
    }

    @Test void constructionCannotThawAnExistingFieldInitializedOwner() {
        fail("@Immutable final class Names { private List<String> values = new ArrayList<>(); Names(Names other) { other.values.clear(); }}", "[IC006]");
    }
    @Test void earlierSafeCallDoesNotCertifyLaterBorrowedArgument() {
        modern();
        fail("@Immutable final class Names { private List<String> values; Names(List<String> input) { forward(List.copyOf(input)); values = forward(input); }"
                + "private static List<String> forward(List<String> a) { return a; }}", "borrowed mutable state retained");
    }

    @Test void unrelatedArrayAssignmentStillEvaluatesItsIndexEffects() {
        fail(OWNED + "private int clearAndIndex() { values.clear(); return 0; }"
                + "public void bad(int[] output) { output[clearAndIndex()] = 1; }}", "[IC006]", "clearAndIndex()");
        fail("@Immutable final class Names { private List<String> values = new ArrayList<>();"
                + "Names(Names other) { Names target = other; target.values = (target = this).values; }}",
                "[IC006]", "receiver not proven to be the object under construction");
    }

    @Test void unsupportedSwitchExpressionStillChecksScalarUpdates() {
        Assumptions.assumeTrue(Integer.parseInt(SourceVersion.latestSupported().name()
                .substring("RELEASE_".length())) >= 14, "Switch expressions require Java 14+");
        for (String update : new String[]{"++state", "state += 1"}) {
            fail("@Immutable final class Names { private int state;"
                    + "int bad(int x) { return switch (x) { case 0 -> " + update
                    + "; default -> 0; }; }}", "[IC006]", "Names.state");
        }
        fail(OWNED + "private static List<String> identity(List<String> a) { return a; }"
                + "public List<String> bad(int x) { return switch(x) { default -> identity(values); }; }}", "[IC005]");
        fail(OWNED + "public List<String> bad(Names other, int x) { return switch(x) { default -> other.values; }; }}", "[IC005]");
        fail("@Immutable final class Names { private static int state; static { initialize(); }"
                + "private static void initialize() { state++; }"
                + "Runnable bad(int x) { return switch(x) { default -> Names::initialize; }; }}", "[IC006]");
        fail(OWNED.replace("addInitial(values);", "addInitial(values); Runnable r = switch (input.size()) { default -> () -> addInitial(values); };")
                + "}", "[IC006]");
        pass(OWNED + "public int unrelated(int x) { return switch(x) { case 0 -> 1; default -> 2; }; }}");
    }

    private static void modern() { Assumptions.assumeTrue(Integer.parseInt(SourceVersion.latestSupported().name().substring("RELEASE_".length())) >= 10, "List.copyOf requires Java 10+ APIs"); }
    private void pass(String body) {
        CompilerTestHarness.CompilationResult result = compiler.compile("Names", HEADER + body);
        assertTrue(result.isSuccessful(), result.joinedErrors());
    }
    private void fail(String body, String... fragments) {
        CompilerTestHarness.CompilationResult result = compiler.compile("Names", HEADER + body);
        assertFalse(result.isSuccessful(), "Expected checker rejection: " + body);
        for (String error : result.getErrors()) {
            assertTrue(error.startsWith("[IC"), "Fixture has a Java compilation error: " + error);
            assertFalse(error.startsWith("[IC000]"), "Expected a modeled rejection, not an internal exception: " + error);
        }
        for (String fragment : fragments) { assertTrue(result.joinedErrors().contains(fragment), result.joinedErrors()); }
    }
}
