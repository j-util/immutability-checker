# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### 0.2.0 technical preview under preparation

- Added one shared may-origin value model for ordinary-class field retention,
  local/parameter aliases, casts, assignments, returns and bounded source calls.
- Added the resolved `java.util.List.copyOf(Collection)` snapshot model with
  separate recursive element proof, snapshot sharing and direct safe returns.
- Added acyclic static/private/final helper analysis, exact final receiver target
  resolution, helpers in another source file, and private initialization helpers
  checked against post-freeze, nestmate and deferred execution reachability.
- Extended the existing five owned collection implementations through helper
  allocations, construction mutation, read-only calls and defensive-copy returns.
- Corrected a false acceptance: another object's retained container must not be
  treated as owned by the currently executing constructor.
- Corrected two soundness regressions introduced in 0.2.0: variable-arity calls
  now check every argument packed into the implicit array, and qualified inner
  construction evaluates its enclosing expression before constructor arguments.
- Corrected an inherited 0.1.0 assertion defect: disabled assertions preserve
  aliases, and enabled/false detail effects participate in exceptional history
  without overwriting normal-continuation aliases.
- Added Java 8-syntax soundness regressions and safe controls for these rules.
  Arrays and array-element provenance remain outside the supported proof domain;
  tracked state flowing through implicit varargs arrays fails closed.
- Preserved conservative joins, raw/unchecked rejection, separate instance/static
  freeze boundaries, recursive state proof and diagnostic IDs IC000–IC006.
- Preserved attributed source trees on Java 8 so helper proof is independent of
  compilation order and consumers need no private compiler flags.
- Added Java 17 to the existing Java 8/26 CI matrix and full compiler/runtime
  consumer coverage. Published classes still target major version 52.
- No public Java API or runtime dependency change. Records, arrays, nested
  containers, other factory families, general recursive/dynamic method proof,
  callbacks/views and cross-module proof remain outside this preview.
- This version is unreleased; no publication date or Central availability is claimed.

## [0.1.0] - 2026-08-25

### Added

- Separated the source-retained annotation API as
  `io.github.j-util:immutability-checker:0.1.0` and the JSR 269 implementation as
  `io.github.j-util:immutability-checker-processor:0.1.0`, built by the
  unpublished `immutability-checker-build` reactor aggregator.
- Added the source-retained `@Immutable` annotation, JSR 269 processor
  discovery, and cycle-safe recursive verification of ordinary top-level and
  static member classes, their declared instance and static state, and
  source-available superclasses.
- Added explicit immutable JDK leaf models and deterministic complete paths
  through recursively verified source classes.
- Added owned `Collection`, `List`, `Set`, and `Map` fields backed by exact fresh
  `ArrayList`, `HashSet`, `LinkedHashSet`, `HashMap`, or `LinkedHashMap`
  allocations, including supported shallow copy constructors.
- Added recursive proof of collection elements and map keys and values, supported
  initialization-phase structural mutation, and collection-specific alias and
  escape checks.
- Added deterministic `IC000` through `IC006` diagnostics.

### Verification

- Added complete Temurin JDK 8 and JDK 26 build verification while producing
  Java 8-compatible class files with no runtime dependencies.
- Added packaged-artifact verification for the separate API and processor JAR
  boundaries, licenses, manifests, processor service registration and
  discovery, matching versions, Java 8 class-file versions, policy-file
  exclusion, and positive and negative compilation fixtures.

### Limitations

- This release is a technical preview whose proof is sound only within the
  documented domain; unsupported state or behavior fails closed.
- Records, arrays, nested collection containers, broader collection
  implementations and wrappers, general interprocedural method and alias
  analysis, arbitrary allocation analysis, bytecode analysis, compiled
  dependency proof, and cross-module metadata are not implemented in 0.1.0.
- Safe publication, general thread safety or purity, and low-level mutation
  through reflection, `Unsafe`, `VarHandle`, JNI, agents, or instrumentation are
  outside the guarantee.
