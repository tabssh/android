# PLAN — 2026-08-19 user batch of 10

Working plan for the ten-item user batch. `TODO.AI.md` holds the item list
and the locked decisions; this file holds the execution rules, ownership,
and definition of done for each step. Delete this file once all three
commits are pushed and green.

---

## Ground rules (apply to every step, main instance and agents alike)

1. **Spec first.** `SPEC.md` > `AI.md` > global rules. The `spec-guard`
   hook blocks edits until `AI.md`/`SPEC.md` have been Read this session —
   read the relevant PART slice (`grep -n "^# PART" AI.md`), not the whole
   file.
2. **Gate is `make check`**, not `make test` (SPEC.md override) — compile +
   lint + JVM unit tests, run in Docker, never on the host. It takes
   10–25 min: always `run_in_background`, poll with a bounded loop, and
   remember Bash `timeout` is in MILLISECONDS.
   **Only the main instance runs it, once per commit** (user instruction,
   2026-08-19): after all of that commit's work has landed, before
   writing `COMMIT_MESS`, fixing every failure it reports before the
   commit is made. **Agents never run `make` at all** — parallel Docker
   Gradle builds fight over the same build cache, and an agent's green
   run proves nothing about the tree once the other agents land. Agents
   verify by reading code and writing tests; the main instance verifies
   by building.
3. **Agents never commit** and never write `.git/COMMIT_MESS`. They edit,
   verify, and report. The main instance reviews the diff, writes the
   commit message, and runs `gitcommit --dir {project_dir} all`.
4. **Three commits total** (user instruction, overrides the usual
   one-commit-per-finding rule): A = bug fixes, B = containers,
   C = UI/UX + Settings. Nothing is committed until its whole group is
   done and `make check` is green.
5. **Routing rule** (user instruction, 2026-08-19): commit A is the
   catch-all. Every gap, bug, security issue, correctness defect and
   legacy item surfaced by any pass belongs to **A** unless it is
   container work (**B**) or UI/UX/Settings work (**C**). There is no
   "log it for later" tier for this batch (user instruction: *"lets not
   log and lets just fix"*) — a finding is **fixed in its owning commit**,
   full stop. The only thing that may be written down instead of fixed is
   a finding that is provably not a defect. Agents report findings; the
   main instance routes them to A, B or C and they get done.
6. **No partial code.** No `TODO`/`FIXME`/`HACK`, no commented-out code, no
   stubs. Comments go above the line, never inline.
6. **Every user-visible change updates `CHANGELOG.md`** in the same commit,
   written in plain user language (what broke / what is different), not
   implementation notes.
7. **Strings go through `strings.xml`.** No new hardcoded UI text.
8. **Secrets discipline.** Never log credentials, tickets, or full URLs
   carrying auth. Keystore stays the only home for secrets at runtime; the
   only exception is the explicitly-confirmed plaintext backup (A3).
9. **Scope discipline.** A bug found outside the current step is logged in
   `TODO.AI.md` immediately — not left in chat — and fixed in its owning
   step, unless it is app-breaking, which is fixed at once.
10. **Room schema changes ship a numbered migration.** Never destructive.
11. **Dark mode is default; themes come from attributes.** Never hardcode
    a color.
12. **No legacy compatibility** (user instruction, 2026-08-19). Only the
    rolling development build has ever shipped, so there is no released
    version to stay compatible with. Delete legacy code paths, deprecated
    pinned modes, versioned format shims and dead fallbacks instead of
    repairing them. A one-time forward migration is allowed where a
    dev-build user would otherwise lose stored data (passwords, keys) —
    but the legacy path itself is deleted, never kept as a fallback.
    Consequence: backup does **not** need to import archives written by
    older app versions; there is one current format.

---

## Commit A — bug fixes

Owner: `debugger` agent (A4) + `audit` agent (A1 enumeration) + main
instance (A1 fixes, A2, A3).

### A1. Sync parity with backup
- **Input:** the audit agent's entity matrix (every Room entity, prefs
  file, and Keystore alias family × backup / sync / tombstone coverage).
- **Do:** close every sync gap the matrix names. Keep the existing
  per-category toggles; a new category needs its own toggle row plus the
  backup-side "sync preferences" block, or it will silently reset on
  restore.
- **Done when:** every row of the matrix reads covered for backup, sync,
  and tombstone — or is listed here with a written reason it must not
  sync (e.g. device-local state).

### A2. Sync conflict resolution
- **Do:** detect per-row conflicts, pause sync, present a picker (keep
  local / keep remote / keep both) with last-write-wins preselected.
  Entities lacking an `updatedAt` need one via additive migration before
  last-write-wins is meaningful.
- **Sync Log:** a dedicated, viewable log — what changed, which device,
  when, how it resolved. Conflicts go **only** there. The app/debug log
  keeps carrying genuine app errors, including faults in the sync code
  itself; a data conflict is not an app error.
- **Done when:** a two-device conflict is reproducible in a unit test,
  each resolution branch is tested, and no conflict text reaches the
  app/debug log.

### A3. Backup covers everything, encrypted and plaintext
- **Do:** same completeness bar as A1. Password set → encrypted archive
  (one current format — legacy import shims are deleted, see ground rule
  12). No password →
  plaintext archive that **includes** secrets, behind a hard
  type-to-confirm dialog naming exactly what is exposed and that the file
  is readable by anyone who obtains it.
- **Spec:** IDEA.md's "portable encrypted archive" wording must be updated
  to describe both modes — user approved this change.
- **Done when:** round-trip tests pass for both modes and the plaintext
  path cannot be reached without the explicit confirmation.

### A5. Remove every legacy code path
- **Do:** inventory and delete legacy/deprecated/back-compat code across
  the whole app — versioned format shims, deprecated pinned transport
  modes, dead fallbacks, superseded preference keys and alias families,
  legacy read paths kept "just in case". Forward-migrate stored data once
  where a dev-build user would otherwise lose it, then delete the path.
- **Done when:** the inventory is fully triaged — each entry deleted, or
  listed with a concrete reason it must stay.

Triaged 2026-08-19. Deleted in this commit: the `allNetworks`
pre-API-23 fallback, `packageInfo.versionCode` in `BackupManager` and
`Logger` (with the `SDK_INT >= P` guard around it), the stale
`Project.buildDir` / legacy `exec { }` build comments, every
`@Suppress("DEPRECATION")` with a working API-24+ replacement, the
backup v1 and v2 read paths, and the `LEGACY_MODE_API_SOCAT` /
`isAutoOrLegacy` special case — the last one is replaced by the general
rule that *any* stored transport mode outside the supported tiers falls
back to full detection, which covers a corrupt pin as well as the dead
one. Kept, with reason:

- **Room migrations 3→12** (`TabSSHDatabase.kt`) — these *are* the
  forward migration ground rule 12 permits; deleting them wipes a
  dev-build user's database.
- **The three one-time startup migrations** — legacy PRE-key preference,
  inline `proxy_*` → `network_routes`, and the plaintext hypervisor
  password sweep. Same reason: a dev-build user who has not launched
  since the change would lose stored data. Each is guarded by its own
  run-once flag; the legacy *read* path behind each is deleted, only the
  migration itself stays.
- **`useLegacyPackaging = true`** (`app/build.gradle`) — pending proof
  the mosh launch path does not need an extracted on-disk binary.
  Removed only if that proof arrives in this pass.

### A4. Per-host dashboard never loads
- **Evidence:** build c7e21db1 session log has zero Docker-tag lines — one
  `SshExecRunner: run: exit=0 cmdLen=37`, then 34 s of silence.
- **Do:** find the real stall (evidence with file:line — no guessing), fix
  it, and close the diagnosability gap: the dashboard/transport path must
  log its chosen transport tier, each probe, and every failure.
- **Done when:** the dashboard loads against a real engine and the log
  narrates the load; a unit test covers the root cause if it is
  unit-testable.

---

## Commit B — containers

Owner: main instance, with agents for isolated sub-areas. **Do not start
until commit A is pushed and CI is green** — B rewrites files A touches.

Engines: **Docker, Incus, Podman, LXC/LXD** (dropdown order exactly that,
Docker preselected).

### B1–B2. Rename and add-host flow
- Infra ▸ Docker becomes Infra ▸ **Containers** — section, navigation,
  strings, and IDEA.md wording.
- The add-host form mirrors the **hypervisor** add UI/UX (add a new host
  or pick an existing connection) but uses **SSH auth only**, never
  hypervisor auth.
- Existing Docker hosts migrate in place with `engine = docker`; no user
  re-entry, no data loss.

### B3–B5. Engine-adaptive UI, tab order, dedup
- Tabs and actions light up per engine; **Stacks hides** for engines with
  no compose concept.
- Tab order: **Dashboard, Containers, Stacks, Images, Volumes, Networks.**
- Dashboard shows stack count, container count, network count, host +
  engine info, disk usage.
- **Dedup rule:** containers belonging to a stack are hidden from the
  Containers list but still counted on the dashboard. User's worked
  example — 3 standalone + 2 stacks × 2 containers → stacks **2**,
  containers **7**. Write the test to that example.

### B6–B7. Fail early, socket location
- Probe the engine **once** at host open. On failure: one blocking error
  card naming the reason (not installed / not running / socket permission
  denied) with a **Retest** action; the tabs do not load.
- Socket path auto-detected **per engine**, overridable per host; the
  override also accepts `tcp://` and `ssh://`. The override applies to
  both the API forward and the CLI path.

### B8. Incus / LXC / LXD
- **Parity where the concept exists:** instances (list, start, stop,
  restart, delete, rename), exec shell, live logs and stats, images,
  storage volumes, networks, snapshots — **plus profiles and projects**
  as their own tabs.
- **Transport mirrors the Docker hybrid model:** REST API over a forwarded
  unix socket when sshd permits, CLI over SSH exec as fallback. Every
  feature must work on CLI-only hosts, with documented degradation.
- **Done when:** each engine is exercised against a real daemon where one
  can be stood up locally, and unsupported tabs are provably hidden rather
  than empty.

---

## Commit C — UI/UX + Settings

Owner: `designer` agent, reviewed by the main instance. **Last, because it
restyles screens A and B touch.**

### C1. Unified Settings
- One place to change everything, categories organised so a user can guess
  where a setting lives. The existing main UI and Settings UI are the
  design base — this is a reorganisation, not a new visual language.
- No setting disappears; anything moved keeps its stored key so upgrades
  do not reset preferences.

### C2. Nav drawer
- Hamburger toggle on **every** screen **except** terminal / VNC / SPICE
  session tabs, which reach the drawer from the toolbar button only —
  edge gestures stay with the session.

### C3. App-wide uniformity
- One back-button / toolbar / menu pattern everywhere. The user's example
  of today's inconsistency: groups and snippets screens versus the routing
  screens.
- **Done when:** every activity and fragment is enumerated and each one
  either matches the pattern or is listed with a reason it cannot.

---

## Verification ledger

Each step records here, before its commit: the ground-truth check
performed and its actual result. "Looks right" is not a check.

| Step | Check | Result |
|------|-------|--------|
| A1 | `make check` incl. the sync entity/tombstone suites; entity matrix re-walked against `SyncDataCollector`/`TombstoneRecorder` diffs | 792 tests pass, 0 failures; every matrix row covered or reasoned |
| A2 | `ConflictResolverKeepBothTest` (keep local/remote/both + auto-merged), `ConflictPreselectionTest`, `SyncLoggerSourceScanTest` (static scan: no conflict text reaches Logger) | all pass; scan reports 0 violations |
| A3 | `BackupRoundTripTest` — encrypted round-trip, wrong/absent password refused, plaintext refused without confirmation then round-trips, non-current format and junk refused | passes; archive magic asserted as `TABSSH_SYNC_V3` |
| A4 | `ConcurrentLoadTest` (parallel, not summed — 3000ms not 9000ms of virtual time; stall bounded by timeout) and `SingleFlightLoaderTest` (superseded load cannot overwrite a newer one) | both pass |
| A5 | `grep -rn 'LEGACY_MODE_API_SOCAT\|isAutoOrLegacy\|allNetworks' app/src/main` after the sweep | 0 matches; kept items listed with reasons above |
| B  | `make check` after the storage/transport/UI/add-host/parity passes merged; exported `15.json` diffed against the `container_hosts` entity; `grep -rn 'ENGINE_REST_UNSUPPORTED'`; duplicate scan over the four-agent `strings.xml` merge | BUILD SUCCESSFUL, 913 tests, 0 failures, 10 skipped. `15.json` is version 15 with no `docker_hosts` table and `engine`/`engine_cli_path` present. 0 matches for the dead constant; 0 duplicate string names. Five gate runs: fixed `instance.name` (field is `names`), `kotlin.test` assert argument order, a renamed test still referencing `DockerHostEditActivity`, `FakeTransport` missing the 10 new members, and unsorted `JSONObject` keys reordering device/port lists |
| C  | | |
