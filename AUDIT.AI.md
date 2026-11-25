# Project Audit

Started: 2026-11-24

Scope: UX/UI consistency, correctness, wiring, dead code, performance, process/thread placement, and database behavior. Security review was excluded as requested.

## Pass 2: Code Quality

- [x] Container updates documentation: the interval dialog fix changed visible behavior; the changelog and in-app What's New now mention invalid interval handling and concurrent setting saves.
- [P2] `app/src/main/java/io/github/tabssh/ui/dialogs/HypervisorAuthPrompts.kt:20-56,108-120` and `app/src/main/java/io/github/tabssh/ui/activities/TabTerminalActivity.kt:2795-2806,6225,6409`: user-visible prompt labels, validation errors, and toast messages are hardcoded in English. German, Spanish, and French users receive English on these flows, contrary to AI.md PART 7's string-resource requirement.
- [P2] `app/src/main/res/layout/fragment_auth_registries.xml:10-18,52-63` and `app/src/main/java/io/github/tabssh/ui/fragments/AuthRegistriesFragment.kt:61-90`: the registry list is a `ListView` with `wrap_content` inside a `NestedScrollView`. This nested scrolling arrangement defeats list virtualization and can make long registry lists measure/render poorly; migrate the list to a `RecyclerView` or another virtualized component that owns scrolling.

## Pass 3: Logic

- [x] Container Updates interval dialog: an invalid interval displayed an error but the positive button still dismissed the dialog. The button now dismisses only after a valid value is committed.
- [x] Container Updates host settings: concurrent setting edits each read and rewrote the full host row, allowing one save to erase another field's newer value. Host edits are now serialized across the read-modify-write operation.
- [x] Container Updates refresh: when listing all containers failed, the page retained the previous all-container snapshot and could display stale pending rows. The snapshot is now cleared on each refresh before applying the latest result.
- [x] `app/src/main/java/io/github/tabssh/background/SessionPersistenceManager.kt` and `app/src/main/java/io/github/tabssh/storage/database/dao/TabSessionDao.kt`: session rows are now prepared before mutation and the active snapshot is replaced in one Room transaction. A row-capture or insert failure preserves the previous snapshot instead of reporting a partial save.

## Pass 4: Documentation

- [x] IDEA.md compatibility: it identified Room schema v32 as current, while Android's Room database and exported schema are v33. Updated the compatibility note and documented v33's device-local `paste_records` entity, which remains excluded from sync and backup.

## Pass 5: Spec Compliance

- [ ] Room migration coverage: `MigrationTest.migrateChain3ToCurrent` requests validation through v33 and includes explicit v28→31 migrations. Its migration list ends at v31 while Room declares auto-migrations through v33; `make test` skipped instrumented tests because no device was reachable. Emulator startup segfaulted in the Android toolchain image with both SwiftShader and `-gpu off`, so MigrationTestHelper's handling of generated migrations through v33 remains unverified.

## Pass 6: Code Flow Trace

No additional confirmed findings in the bounded pass. The inspected blocking waits and sleeps are on SSH/IO, executor, or protocol worker threads; the sampled UI database reads use suspend DAOs or `Dispatchers.IO`.
