# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

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
