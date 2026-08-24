# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

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
