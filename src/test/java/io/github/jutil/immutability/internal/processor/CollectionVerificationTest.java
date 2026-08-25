package io.github.jutil.immutability.internal.processor;

import org.junit.jupiter.api.Test;

import javax.lang.model.SourceVersion;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionVerificationTest {

    private static final String HEADER = "package example;\n"
            + "import io.github.jutil.immutability.Immutable;\n"
            + "import java.util.*;\n";

    private final CompilerTestHarness compiler = new CompilerTestHarness();

    @Test
    void acceptsArrayListCreatedInFieldInitializer() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "}\n");
    }

    @Test
    void acceptsCollectionDeclarationWithArrayListAllocation() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private Collection<String> values = new ArrayList<>();\n"
                + "}\n");
    }

    @Test
    void acceptsListCopyConstructorInConstructor() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values;\n"
                + "  Names(List<String> source) { values = new ArrayList<>(source); }\n"
                + "}\n");
    }

    @Test
    void acceptsConstructionPhaseAdd() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Names(String value) { values.add(value); }\n"
                + "}\n");
    }

    @Test
    void acceptsConstructionPhaseAddAll() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Names(List<String> source) { values.addAll(source); }\n"
                + "}\n");
    }

    @Test
    void acceptsConstructionPhaseMutationThroughLocalAlias() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Names(String value) { List<String> alias = values; alias.add(value); }\n"
                + "}\n");
    }

    @Test
    void acceptsInstanceInitializerOwnershipAndMutation() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values;\n"
                + "  { values = new ArrayList<>(); values.add(\"main\"); }\n"
                + "}\n");
    }

    @Test
    void acceptsListOfRecursivelyImmutableCustomType() {
        assertPasses("example.Order", HEADER
                + "final class Line { private String symbol; Line(String symbol) { this.symbol = symbol; } }\n"
                + "@Immutable final class Order {\n"
                + "  private List<Line> lines;\n"
                + "  Order(List<Line> source) { lines = new ArrayList<>(source); }\n"
                + "}\n");
    }

    @Test
    void acceptsNestedCustomObjectContainingList() {
        assertPasses("example.Order", HEADER
                + "final class Line { private String symbol; Line(String symbol) { this.symbol = symbol; } }\n"
                + "final class Details { private List<Line> lines = new ArrayList<>(); }\n"
                + "@Immutable final class Order { private final Details details = new Details(); }\n");
    }

    @Test
    void acceptsHashSetAndLinkedHashSetOfCustomType() {
        assertPasses("example.Tags", HEADER
                + "final class Tag { private String value; Tag(String value) { this.value = value; } }\n"
                + "@Immutable final class Tags {\n"
                + "  private Set<Tag> first = new HashSet<>();\n"
                + "  private Set<Tag> second = new LinkedHashSet<>();\n"
                + "}\n");
    }

    @Test
    void acceptsHashMapAndLinkedHashMapWithRecursiveKeyAndValueProof() {
        assertPasses("example.Registry", HEADER
                + "final class Key { private String id; Key(String id) { this.id = id; } }\n"
                + "final class Entry { private String state; Entry(String state) { this.state = state; } }\n"
                + "@Immutable final class Registry {\n"
                + "  private Map<Key, Entry> first = new HashMap<>();\n"
                + "  private Map<Key, Entry> second = new LinkedHashMap<>();\n"
                + "}\n");
    }

    @Test
    void acceptsReturningImmutableListElement() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  String get(int index) { return values.get(index); }\n"
                + "}\n");
    }

    @Test
    void acceptsReturningImmutableMapValue() {
        assertPasses("example.Registry", HEADER
                + "@Immutable final class Registry {\n"
                + "  private Map<String, String> entries = new HashMap<>();\n"
                + "  String get(String key) { return entries.get(key); }\n"
                + "}\n");
    }

    @Test
    void acceptsStaticOwnedListFilledDuringClassInitialization() {
        assertPasses("example.Registry", HEADER
                + "@Immutable final class Registry {\n"
                + "  private static List<String> values = new ArrayList<>();\n"
                + "  static { values.add(\"main\"); }\n"
                + "}\n");
    }

    @Test
    void acceptsStaticListOfRecursivelyImmutableCustomType() {
        assertPasses("example.Registry", HEADER
                + "final class Line { private String symbol; Line(String symbol) { this.symbol = symbol; } }\n"
                + "@Immutable final class Registry {\n"
                + "  private static List<Line> values = new ArrayList<>();\n"
                + "  static { values.add(new Line(\"A\")); }\n"
                + "}\n");
    }

    @Test
    void acceptsEverySupportedFreshCopyImplementation() {
        assertPasses("example.Copies", HEADER
                + "@Immutable final class Copies {\n"
                + "  private Set<String> hash; private Set<String> linkedHash;\n"
                + "  private Map<String, String> hashMap; private Map<String, String> linkedHashMap;\n"
                + "  Copies(Set<String> set, Map<String, String> map) {\n"
                + "    hash = new HashSet<>(set); linkedHash = new LinkedHashSet<>(set);\n"
                + "    hashMap = new HashMap<>(map); linkedHashMap = new LinkedHashMap<>(map);\n"
                + "  }\n"
                + "}\n");
    }

    @Test
    void acceptsSupportedNonCallbackMutatorSignaturesDuringConstruction() {
        assertPasses("example.Aggregate", HEADER
                + "@Immutable final class Aggregate {\n"
                + "  private List<String> list; private Set<String> set;\n"
                + "  private Map<String, String> map;\n"
                + "  Aggregate(Collection<String> source, Map<String, String> sourceMap) {\n"
                + "    list = new ArrayList<>();\n"
                + "    list.add(\"a\"); list.add(0, \"b\"); list.addAll(source); list.addAll(0, source);\n"
                + "    list.set(0, \"c\"); list.remove(0); list.remove(\"c\");\n"
                + "    list.removeAll(source); list.retainAll(source); list.clear();\n"
                + "    set = new HashSet<>(); set.add(\"a\"); set.remove(\"a\"); set.clear();\n"
                + "    map = new HashMap<>(); map.put(\"a\", \"b\"); map.putAll(sourceMap);\n"
                + "    map.putIfAbsent(\"a\", \"b\"); map.remove(\"a\"); map.remove(\"a\", \"b\");\n"
                + "    map.replace(\"a\", \"b\"); map.replace(\"a\", \"b\", \"c\"); map.clear();\n"
                + "  }\n"
                + "}\n");
    }

    @Test
    void acceptsExplicitSupportedReadOperations() {
        assertPasses("example.Aggregate", HEADER
                + "@Immutable final class Aggregate {\n"
                + "  private List<String> list = new ArrayList<>();\n"
                + "  private Map<String, String> map = new HashMap<>();\n"
                + "  int listReads(Collection<String> other) {\n"
                + "    list.isEmpty(); list.contains(\"a\"); list.containsAll(other);\n"
                + "    list.get(0); list.indexOf(\"a\"); list.lastIndexOf(\"a\"); return list.size();\n"
                + "  }\n"
                + "  String mapReads(String key) {\n"
                + "    map.size(); map.isEmpty(); map.containsKey(key); map.containsValue(key);\n"
                + "    map.get(key); return map.getOrDefault(key, \"default\");\n"
                + "  }\n"
                + "}\n");
    }

    @Test
    void acceptsStaticInitializationMutationThroughLocalAlias() {
        assertPasses("example.Registry", HEADER
                + "@Immutable final class Registry {\n"
                + "  private static List<String> values = new ArrayList<>();\n"
                + "  static { List<String> alias = values; alias.add(\"main\"); }\n"
                + "}\n");
    }

    @Test
    void acceptsModeledCopyAndAddAllBetweenOwnedCollections() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> first = new ArrayList<>();\n"
                + "  private List<String> second;\n"
                + "  Names() { second = new ArrayList<>(first); second.addAll(first); }\n"
                + "}\n");
    }

    @Test
    void rejectsCollectionAliasStoredByNestedAnonymousAndLocalFieldDeclarations() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Names", HEADER
                + "@Immutable public final class Names {\n"
                + "  private final List<String> values = new ArrayList<>();\n"
                + "  public final class Leak {\n"
                + "    public final List<String> exposed = Names.this.values;\n"
                + "  }\n"
                + "  Object anonymousLeak() {\n"
                + "    return new Object() { final List<String> exposed = values; };\n"
                + "  }\n"
                + "  Object localLeak() {\n"
                + "    class LocalLeak { final List<String> exposed = values; }\n"
                + "    return new LocalLeak();\n"
                + "  }\n"
                + "}\n");
        assertFalse(result.isSuccessful(), "Expected compilation to fail");
        assertEquals(3, occurrences(result.getErrors(), "stored by a field declaration"),
                result.joinedErrors());
        assertEquals(3, occurrences(result.getErrors(), "Names.values"),
                result.joinedErrors());
    }

    @Test
    void rejectsEnclosingNestmateFieldInitializedFromAnnotatedNestedStaticCollection() {
        assertFails("example.Nest", HEADER
                + "final class Nest {\n"
                + "  static List<String> exposed = Value.values;\n"
                + "  @Immutable static final class Value {\n"
                + "    private static final List<String> values = new ArrayList<>();\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "Value.<static>.values", "stored by a field declaration");
    }

    @Test
    void rejectsExternallyRetainedMutableItemInsertedThroughRawAlias() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private final List<String> values = new ArrayList<>();\n"
                + "  Names(StringBuilder externalMutable) {\n"
                + "    List raw = values;\n"
                + "    raw.add(externalMutable);\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "Names.values", "raw or unchecked type",
                "argument for the element role has type java.lang.StringBuilder");
    }

    @Test
    void rejectsDirectRawReceiverAndUncheckedCollectionAlias() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private final List<String> values = new ArrayList<>();\n"
                + "  Names(StringBuilder externalMutable) {\n"
                + "    ((List) values).add(externalMutable);\n"
                + "    List<String> unchecked = (List<String>) (List) values;\n"
                + "    unchecked.set(0, externalMutable.toString());\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "java.util.List.add(java.lang.Object) receiver",
                "collection alias loses", "raw or unchecked type");
    }

    @Test
    void rejectsRawCopyConstructorAndBulkMutationSources() {
        assertFails("example.Aggregate", HEADER
                + "@Immutable final class Aggregate {\n"
                + "  private final List<String> list;\n"
                + "  private final Map<String, String> map = new HashMap<>();\n"
                + "  Aggregate(Collection rawItems, Map rawEntries) {\n"
                + "    list = new ArrayList(rawItems);\n"
                + "    list.addAll(rawItems);\n"
                + "    list.addAll(0, rawItems);\n"
                + "    map.putAll(rawEntries);\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "Aggregate.list", "copy-constructor source loses",
                "addAll(java.util.Collection) source loses",
                "putAll(java.util.Map) source loses");
    }

    @Test
    void rejectsRawSourceLaunderedThroughUncheckedParameterizedAliases() {
        assertFails("example.Aggregate", HEADER
                + "@Immutable final class Aggregate {\n"
                + "  private final List<String> copied;\n"
                + "  private final List<String> list = new ArrayList<>();\n"
                + "  private final Map<String, String> map = new HashMap<>();\n"
                + "  Aggregate(Collection rawItems, Map rawEntries) {\n"
                + "    List<String> uncheckedItems = (List<String>) (List) rawItems;\n"
                + "    Map<String, String> uncheckedEntries =\n"
                + "        (Map<String, String>) (Map) rawEntries;\n"
                + "    copied = new ArrayList<>(uncheckedItems);\n"
                + "    list.addAll(uncheckedItems);\n"
                + "    map.putAll(uncheckedEntries);\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "Aggregate.copied", "copy-constructor source loses",
                "Aggregate.list", "addAll(java.util.Collection) source loses",
                "Aggregate.map", "putAll(java.util.Map) source loses");
    }

    @Test
    void rejectsUnsafeArgumentsForEveryInsertionAndReplacementRole() {
        assertFails("example.Aggregate", HEADER
                + "@Immutable final class Aggregate {\n"
                + "  private final List<String> list = new ArrayList<>();\n"
                + "  private final Map<String, String> map = new HashMap<>();\n"
                + "  Aggregate(StringBuilder externalMutable) {\n"
                + "    ((List) list).add(externalMutable);\n"
                + "    ((List) list).add(0, externalMutable);\n"
                + "    ((List) list).set(0, externalMutable);\n"
                + "    ((Map) map).put(externalMutable, externalMutable);\n"
                + "    ((Map) map).putIfAbsent(externalMutable, externalMutable);\n"
                + "    ((Map) map).replace(externalMutable, externalMutable);\n"
                + "    ((Map) map).replace(externalMutable, externalMutable, externalMutable);\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "argument for the element role",
                "argument for the key role", "argument for the value role",
                "java.util.Map.putIfAbsent", "java.util.Map.replace");
    }

    @Test
    void conditionalMutationChecksEveryPossibleRetainedTarget() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Values", HEADER
                + "@Immutable final class Values {\n"
                + "  private final List<String> instanceValues = new ArrayList<>();\n"
                + "  private static final List<String> staticValues = new ArrayList<>();\n"
                + "  Values(boolean instance) {\n"
                + "    List<String> target = instance ? instanceValues : staticValues;\n"
                + "    target.add(\"x\");\n"
                + "    (instance ? instanceValues : staticValues).add(\"y\");\n"
                + "  }\n"
                + "}\n");
        assertFalse(result.isSuccessful(), "Expected compilation to fail");
        assertEquals(2, occurrences(result.getErrors(), "Values.<static>.staticValues"),
                result.joinedErrors());
        assertEquals(0, occurrences(result.getErrors(), "Values.instanceValues"),
                result.joinedErrors());
    }

    @Test
    void conditionalEscapeReportsEveryTargetOnceInBranchOrder() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Values", HEADER
                + "@Immutable final class Values {\n"
                + "  private final List<String> first = new ArrayList<>();\n"
                + "  private final List<String> second = new ArrayList<>();\n"
                + "  Object expose(boolean chooseFirst) {\n"
                + "    return chooseFirst ? first : second;\n"
                + "  }\n"
                + "}\n");
        assertFalse(result.isSuccessful(), "Expected compilation to fail");
        assertEquals(1, occurrences(result.getErrors(), "Values.first"), result.joinedErrors());
        assertEquals(1, occurrences(result.getErrors(), "Values.second"), result.joinedErrors());
        assertTrue(result.joinedErrors().indexOf("Values.first")
                < result.joinedErrors().indexOf("Values.second"), result.joinedErrors());
    }

    @Test
    void switchExpressionAndYieldCollectionFlowsFailClosedOnCapableJdk() {
        org.junit.jupiter.api.Assumptions.assumeTrue(supportsSwitchExpressions());
        assertFails("example.Values", HEADER
                + "@Immutable final class Values {\n"
                + "  private final List<String> values = new ArrayList<>();\n"
                + "  List<String> exposeArrow(int selector) {\n"
                + "    return switch (selector) {\n"
                + "      case 0 -> values;\n"
                + "      default -> new ArrayList<>();\n"
                + "    };\n"
                + "  }\n"
                + "  List<String> exposeYield(int selector) {\n"
                + "    return switch (selector) {\n"
                + "      case 0: yield values;\n"
                + "      default: yield new ArrayList<>();\n"
                + "    };\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "Values.values", "escapes through return");
    }

    @Test
    void acceptsQualifiedCurrentInstanceCollectionOrigin() {
        assertPasses("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values;\n"
                + "  Names() { Names.this.values = new ArrayList<>(); values.add(\"main\"); }\n"
                + "}\n");
    }

    @Test
    void rejectsDirectRetentionOfConstructorParameter() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values;\n"
                + "  Names(List<String> source) { values = source; }\n"
                + "}\n",
                "[IC005]", "Names.values", "parameter is retained directly",
                "external mutable container alias remains");
    }

    @Test
    void rejectsDirectRetentionOfExternalStaticCollection() {
        assertFails("example.Registry", HEADER
                + "final class External { static List<String> values = new ArrayList<>(); }\n"
                + "@Immutable final class Registry {\n"
                + "  private static List<String> values = External.values;\n"
                + "}\n",
                "[IC005]", "Registry.<static>.values", "field-to-field collection aliasing");
    }

    @Test
    void rejectsPostConstructionAdd() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void add(String value) { values.add(value); }\n"
                + "}\n",
                "[IC006]", "Names.values", "add(java.lang.Object)", "Names.add()",
                "after instance construction");
    }

    @Test
    void rejectsPostConstructionRemoveAndClear() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void remove(String value) { values.remove(value); }\n"
                + "  void clear() { values.clear(); }\n"
                + "}\n",
                "[IC006]", "remove(java.lang.Object)", "clear()",
                "after instance construction");
    }

    @Test
    void rejectsPostClassInitializationMutation() {
        assertFails("example.Registry", HEADER
                + "@Immutable final class Registry {\n"
                + "  private static List<String> values = new ArrayList<>();\n"
                + "  static void add(String value) { values.add(value); }\n"
                + "}\n",
                "[IC006]", "Registry.<static>.values", "Registry.add()",
                "after class initialization");
    }

    @Test
    void rejectsPostConstructionMutationThroughLocalAlias() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void mutate() { List<String> alias = values; alias.clear(); }\n"
                + "}\n",
                "[IC006]", "Names.values", "clear()", "Names.mutate()");
    }

    @Test
    void rejectsDirectCollectionReturnIncludingObjectCast() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Object values() { return (Object) values; }\n"
                + "}\n",
                "[IC005]", "Names.values", "escapes through return");
    }

    @Test
    void rejectsConditionalReturnAndLambdaCallbackResult() {
        assertFails("example.Names", HEADER
                + "import java.util.function.Supplier;\n"
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Object conditional(boolean flag) { return flag ? values : null; }\n"
                + "  Supplier<List<String>> callback() { return () -> values; }\n"
                + "}\n",
                "[IC005]", "Names.values", "escapes through return", "callback result");
    }

    @Test
    void rejectsPassingCollectionToUnknownMethod() {
        assertFails("example.Names", HEADER
                + "final class External { static void accept(Object value) {} }\n"
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void register() { External.accept(values); }\n"
                + "}\n",
                "[IC005]", "Names.values", "passed to", "callee mutation and retention effects are unproven");
    }

    @Test
    void rejectsPassingCollectionToCallback() {
        assertFails("example.Names", HEADER
                + "import java.util.function.Consumer;\n"
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void consume(Consumer<List<String>> consumer) { consumer.accept(values); }\n"
                + "}\n",
                "[IC005]", "Names.values", "Consumer.accept", "effects are unproven");
    }

    @Test
    void rejectsIteratorListIteratorAndSubListViews() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Iterator<String> iterator() { return values.iterator(); }\n"
                + "  ListIterator<String> listIterator() { return values.listIterator(); }\n"
                + "  List<String> subList() { return values.subList(0, 0); }\n"
                + "}\n",
                "[IC005]", "iterator()", "listIterator()", "subList(int,int)", "aliasing view");
    }

    @Test
    void rejectsMapKeyValueAndEntryViews() {
        assertFails("example.Registry", HEADER
                + "@Immutable final class Registry {\n"
                + "  private Map<String, String> entries = new HashMap<>();\n"
                + "  Set<String> keys() { return entries.keySet(); }\n"
                + "  Collection<String> values() { return entries.values(); }\n"
                + "  Set<Map.Entry<String, String>> entries() { return entries.entrySet(); }\n"
                + "}\n",
                "[IC005]", "keySet()", "values()", "entrySet()", "aliasing view");
    }

    @Test
    void rejectsPublicFinalInstanceCollectionField() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  public final List<String> values = new ArrayList<>();\n"
                + "}\n",
                "[IC005]", "Names.values", "final collection field exposes mutation-capable retained state");
    }

    @Test
    void rejectsPublicStaticFinalCollectionField() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  public static final List<String> VALUES = new ArrayList<>();\n"
                + "}\n",
                "[IC005]", "Names.<static>.VALUES", "final collection field exposes mutation-capable retained state");
    }

    @Test
    void rejectsPackageVisibleFinalCollectionField() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  final List<String> values = new ArrayList<>();\n"
                + "}\n",
                "[IC005]", "Names.values", "non-private final collection field");
    }

    @Test
    void rejectsMutableListElementWithCompletePath() {
        assertFails("example.Order", HEADER
                + "final class Line { private int price; void changePrice() { price++; } }\n"
                + "@Immutable final class Order { private List<Line> lines = new ArrayList<>(); }\n",
                "[IC005]", "Order.lines -> element -> Line.price", "Line.changePrice()",
                "outside instance construction");
    }

    @Test
    void rejectsDeeplyNestedMutableListElementWithCompletePath() {
        assertFails("example.Order", HEADER
                + "final class Price { private int amount; void change() { amount++; } }\n"
                + "final class Line { private final Price price = new Price(); }\n"
                + "@Immutable final class Order { private List<Line> lines = new ArrayList<>(); }\n",
                "[IC005]", "Order.lines -> element -> Line.price -> Price.amount", "Price.change()");
    }

    @Test
    void rejectsMutableMapKeyWithKeyPath() {
        assertFails("example.Registry", HEADER
                + "final class Key { private int id; void change() { id++; } }\n"
                + "@Immutable final class Registry { private Map<Key, String> entries = new HashMap<>(); }\n",
                "[IC005]", "Registry.entries -> key -> Key.id", "Key.change()");
    }

    @Test
    void rejectsMutableMapValueWithValuePath() {
        assertFails("example.Registry", HEADER
                + "final class Entry { private int state; void change() { state++; } }\n"
                + "@Immutable final class Registry { private Map<String, Entry> entries = new HashMap<>(); }\n",
                "[IC005]", "Registry.entries -> value -> Entry.state", "Entry.change()");
    }

    @Test
    void rejectsRawCollectionDeclaration() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List values = new ArrayList();\n"
                + "  Names() { values.add(\"x\"); }\n"
                + "}\n",
                "[IC005]", "Names.values", "raw collection declarations are unsupported");
    }

    @Test
    void rejectsWildcardCollectionArgument() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names { private List<?> values = new ArrayList<String>(); }\n",
                "[IC005]", "Names.values -> element", "wildcard collection arguments");
    }

    @Test
    void rejectsUnresolvedTypeVariableCollectionArgument() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names<T> { private List<T> values = new ArrayList<>(); }\n",
                "[IC005]", "Names.values -> element", "unresolved collection type variables");
    }

    @Test
    void rejectsUnsupportedCollectionDeclaration() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names { private Queue<String> values = new ArrayDeque<>(); }\n",
                "[IC005]", "Names.values", "outside the supported Collection, List, Set, and Map abstractions");
    }

    @Test
    void rejectsUnsupportedCollectionImplementation() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names { private List<String> values = new LinkedList<>(); }\n",
                "[IC005]", "Names.values", "java.util.LinkedList",
                "supported exact fresh collection implementations");
    }

    @Test
    void rejectsCollectionNestedInsideCollection() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names { private List<List<String>> values = new ArrayList<>(); }\n",
                "[IC005]", "Names.values -> element", "collections nested inside collections",
                "inner-container provenance is not implemented");
    }

    @Test
    void rejectsUnknownCollectionMethod() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Object[] array() { return values.toArray(); }\n"
                + "}\n",
                "[IC005]", "Names.values", "toArray()", "not an explicitly modeled collection operation");
    }

    @Test
    void rejectsUnmodifiableWrapperAsOwnershipProof() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values;\n"
                + "  Names(List<String> source) { values = Collections.unmodifiableList(source); }\n"
                + "}\n",
                "[IC005]", "Names.values", "factory method return values are not supported");
    }

    @Test
    void rejectsCallbackBasedMutatorDuringConstruction() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Names() { values.removeIf(value -> value.isEmpty()); }\n"
                + "}\n",
                "[IC005]", "Names.values", "removeIf", "callback effects and escape behavior are not modeled");
    }

    @Test
    void rejectsCallbackBasedMapMutatorsDuringConstruction() {
        assertFails("example.Registry", HEADER
                + "@Immutable final class Registry {\n"
                + "  private Map<String, String> values = new HashMap<>();\n"
                + "  Registry() {\n"
                + "    values.computeIfAbsent(\"a\", key -> key);\n"
                + "    values.merge(\"a\", \"b\", (left, right) -> right);\n"
                + "  }\n"
                + "}\n",
                "[IC005]", "computeIfAbsent", "merge", "callback effects and escape behavior are not modeled");
    }

    @Test
    void rejectsStreamsAndSpliterator() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Object stream() { return values.stream(); }\n"
                + "  Object parallel() { return values.parallelStream(); }\n"
                + "  Object spliterator() { return values.spliterator(); }\n"
                + "}\n",
                "[IC005]", "stream()", "parallelStream()", "spliterator()", "retained state");
    }

    @Test
    void rejectsLocalAliasReturnArrayStorageAndUnrelatedFieldStorage() {
        assertFails("example.Names", HEADER
                + "final class External { static Object state; }\n"
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Object expose() { List<String> alias = values; return alias; }\n"
                + "  Object[] array() { return new Object[] { values }; }\n"
                + "  void store() { External.state = values; }\n"
                + "}\n",
                "[IC005]", "escapes through return", "stored into an array",
                "assigned into unrelated field");
    }

    @Test
    void rejectsCapturedLocalAliasMutationAndMethodReference() {
        assertFails("example.Names", HEADER
                + "import java.util.function.Supplier;\n"
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void capture() {\n"
                + "    List<String> alias = values;\n"
                + "    Runnable mutation = () -> alias.clear();\n"
                + "    Supplier<Integer> read = alias::size;\n"
                + "  }\n"
                + "}\n",
                "[IC006]", "clear()", "lambda in Names.capture()",
                "[IC005]", "method reference captures");
    }

    @Test
    void rejectsMultipleCompetingFreshOrigins() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  Names() { values = new ArrayList<>(); }\n"
                + "}\n",
                "[IC005]", "Names.values", "multiple competing collection ownership origins");
    }

    @Test
    void rejectsConditionalAndHelperOwnershipOrigins() {
        assertFails("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> conditional;\n"
                + "  private List<String> helper = create();\n"
                + "  Names(boolean flag) { if (flag) conditional = new ArrayList<>(); }\n"
                + "  private static List<String> create() { return new ArrayList<>(); }\n"
                + "}\n",
                "[IC005]", "Names.conditional", "allocation is conditional",
                "Names.helper", "helper and factory method return values");
    }

    @Test
    void staticCollectionCycleContainingItemViolationTerminates() {
        assertFails("example.A", HEADER
                + "@Immutable final class A { private static List<B> values = new ArrayList<>(); }\n"
                + "final class B {\n"
                + "  private static final A A_VALUE = null;\n"
                + "  private int revision; void revise() { revision++; }\n"
                + "}\n",
                "[IC005]", "A.<static>.values -> element -> B.revision", "B.revise()");
    }

    @Test
    void collectionDiagnosticsRemainDeterministic() {
        String source = HEADER
                + "final class Key { private int id; void change() { id++; } }\n"
                + "final class Entry { private int state; void change() { state++; } }\n"
                + "@Immutable final class Registry {\n"
                + "  private Map<Key, Entry> entries = new HashMap<>();\n"
                + "  Map<Key, Entry> expose() { return entries; }\n"
                + "}\n";
        CompilerTestHarness.CompilationResult first = compiler.compile("example.Registry", source);
        CompilerTestHarness.CompilationResult second = compiler.compile("example.Registry", source);
        assertFalse(first.isSuccessful());
        assertFalse(second.isSuccessful());
        assertEquals(first.joinedErrors(), second.joinedErrors());
        assertTrue(first.joinedErrors().contains("Registry.entries -> key -> Key.id"), first.joinedErrors());
    }

    @Test
    void doesNotDuplicateCollectionViolationAcrossRounds() {
        CompilerTestHarness.CompilationResult result = compiler.compile("example.Names", HEADER
                + "@Immutable final class Names {\n"
                + "  private List<String> values = new ArrayList<>();\n"
                + "  void mutate() { values.add(\"x\"); }\n"
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

    private static boolean supportsSwitchExpressions() {
        String release = SourceVersion.latestSupported().name();
        int separator = release.lastIndexOf('_');
        return separator >= 0 && Integer.parseInt(release.substring(separator + 1)) >= 14;
    }
}
