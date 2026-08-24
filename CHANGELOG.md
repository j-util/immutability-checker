# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

- Added recursive verification of declared static state for annotated roots,
  recursively analyzed source classes, and source superclasses.
- Added the class-initialization freeze boundary, allowing direct static writes
  only in static field initializers and static initializer blocks.
- Added static-field diagnostic paths and regression coverage for external
  writability, post-initialization writes, recursion, cycles, and inheritance.
- Documented the conservative limitation that static-initialization-only helper
  reachability is not yet analyzed.
- Added cycle-safe recursive retained-state verification for source-available
  ordinary classes, their source superclasses, and further source field types.
- Added deterministic complete reference paths and fail-closed diagnostics for
  unavailable source, unsupported references, and unresolved runtime subtypes.
- Deferred record verification to V2 and rejected annotated records with `IC001`.
- Added the initial single-module Maven project and Java 8-compatible artifact.
- Added the source-retained `@Immutable` annotation and JSR 269 processor discovery.
- Added sound direct-state verification with the construction freeze boundary.
- Added fail-closed reference-state and inherited-state proof boundaries.
- Added deterministic `IC000` through `IC006` diagnostics.
- Added JDK 8 and JDK 26 continuous integration.
- Added packaged-artifact verification for class-file versions, manifest metadata,
  license and service entries, processor discovery, and accepting/rejecting fixtures.
- Removed subclassable `BigInteger` and `BigDecimal` from the trusted immutable
  leaf set.
- Added symbol-resolved `RootType.this.field` writes to supported direct
  construction contexts.
- Clarified the direct source-level write model and its indirect-mutation proof
  boundary.
- Added regressions for unproven numeric references, qualified root `this`, and
  conservatively rejected deferred, foreign, casted, and aliased receivers.
