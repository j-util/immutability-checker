# Agent Instructions

## Mandatory First Step

Before making any change in this repository, read `PROJECT_INVARIANTS.md` completely.

`PROJECT_INVARIANTS.md` defines the normative semantic contract of the project and is authoritative over implementation, tests, documentation, and existing behavior.

It is **read-only for AI agents**.

AI agents must not modify, rewrite, reorganize, delete, or weaken `PROJECT_INVARIANTS.md`.

If a requested change conflicts with an invariant, report the conflict to the human maintainer instead of modifying the invariant.

## Working Rules

For every non-trivial change:

1. Read `PROJECT_INVARIANTS.md`.
2. Inspect the relevant implementation and tests.
3. Make the smallest coherent change that satisfies the request and all project invariants.
4. Add or update focused tests.
5. Run the relevant build and verification commands.
6. Review public API, compatibility, documentation, and Maven/repository impact where applicable.
7. Report what changed, what was verified, and any remaining limitations.

Do not:
- weaken tests merely to make an implementation pass;
- introduce behavior that contradicts `PROJECT_INVARIANTS.md`;
- expose unnecessary public API;
- add dependencies without clear justification;
- leave temporary code, debug output, stale documentation, or unexplained suppressions.

## Conflict Resolution

Use this precedence:

1. `PROJECT_INVARIANTS.md`
2. explicit human-maintainer instructions that do not conflict with the invariants
3. public API and compatibility requirements
4. tests
5. existing implementation

If implementation, tests, or documentation conflict with `PROJECT_INVARIANTS.md`, correct them rather than changing the invariant.

Only the human maintainer may change `PROJECT_INVARIANTS.md`.