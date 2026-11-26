# Project Audit

Started: 2026-11-24

Scope: UX/UI consistency, correctness, wiring, dead code, performance, process/thread placement, and database behavior. Security review was excluded as requested.

## Pass 2: Code Quality

- [x] Container updates documentation: the interval dialog fix changed visible behavior; the changelog and in-app What's New now mention invalid interval handling and concurrent setting saves.
- [x] `HypervisorAuthPrompts.kt` and `TabTerminalActivity.kt`: moved user-visible prompts, validation errors, and toasts into string resources, with German, Spanish, and French translations.
- [x] `fragment_auth_registries.xml` and `AuthRegistriesFragment.kt`: replaced the nested `ListView` with a virtualized `RecyclerView` that owns its scrolling.

## Pass 3: Logic

- [x] Container Updates interval dialog: an invalid interval displayed an error but the positive button still dismissed the dialog. The button now dismisses only after a valid value is committed.
- [x] Container Updates host settings: concurrent setting edits each read and rewrote the full host row, allowing one save to erase another field's newer value. Host edits are now serialized across the read-modify-write operation.
- [x] Container Updates refresh: when listing all containers failed, the page retained the previous all-container snapshot and could display stale pending rows. The snapshot is now cleared on each refresh before applying the latest result.
- [x] `app/src/main/java/io/github/tabssh/background/SessionPersistenceManager.kt` and `app/src/main/java/io/github/tabssh/storage/database/dao/TabSessionDao.kt`: session rows are now prepared before mutation and the active snapshot is replaced in one Room transaction. A row-capture or insert failure preserves the previous snapshot instead of reporting a partial save.

## Pass 4: Documentation

- [x] IDEA.md compatibility: it identified Room schema v32 as current, while Android's Room database and exported schema are v33. Updated the compatibility note and documented v33's device-local `paste_records` entity, which remains excluded from sync and backup.

## Pass 5: Spec Compliance

- [x] Room migration coverage: `MigrationTest.migrateChain3ToCurrent` includes the manual migrations and Room-generated auto-migrations through v33; `MigrationTestHelper` passed on the emulator.

## Pass 6: Code Flow Trace

The `make test` UI phase previously called `ui-test.sh` without installing the freshly built universal APK; it now passes the APK path. Updated stale UI test expectations to cover the current tabs and drawer destinations. The inspected blocking waits and sleeps are on SSH/IO, executor, or protocol worker threads; the sampled UI database reads use suspend DAOs or `Dispatchers.IO`.
