# {PROJECT_NAME} Android Application Specification

**Name**: {project_name}

**About this file:** `AI.md` is the complete, authoritative specification for this Android project.

**Note:** `{PROJECT_NAME}` and `{project_name}` in this file are reference tokens, not setup-time text replacements. Their values are resolved from `IDEA.md ## Project variables` while `AI.md` remains read-only.

---

# PROJECT DESCRIPTION

**See `IDEA.md` for project-specific details.**

---

# SOURCE OF TRUTH AND IDEA.md PRECEDENCE

**See `IDEA.md` for features, data models, and business rules.**

IDEA.md is the project PLAN. AI.md (this file) is the SOURCE OF TRUTH.

| File | Role | Update When |
|------|------|-------------|
| **AI.md** | SOURCE OF TRUTH - implementation rules (readonly) | No — use SPEC.md for project-specific rule overrides |
| **SPEC.md** | Project-specific rule overrides — created only when a rule must contradict this specification or global. May be empty. SPEC.md wins over AI.md. | When a project rule must differ from this specification or global |
| **IDEA.md** | PROJECT PLAN - must follow AI.md | Features change, project variables change |

**Rule hierarchy:** SPEC.md > AI.md > global CLAUDE.md. If SPEC.md and AI.md conflict, SPEC.md wins — that is its purpose.
**Rule:** If AI.md and IDEA.md conflict, AI.md wins. Fix IDEA.md.

## IDEA.md Required Layout

**Every IDEA.md MUST have exactly these three top-level sections, in this order. For the fillable IDEA.md template, see PART 14 → "IDEA.md REFERENCE".**

```markdown
## Project description

(Full project description — what the app is, who uses it, what problem it solves.)

## Project variables

(All project variables in `key: value` form. Required keys at minimum: `project_name`,
`project_org`, `internal_name`, `internal_org`. Android apps add `app_id`, `min_sdk`,
`ui_toolkit`, `store_targets`, and the `### Applicability` matrix — see PART 14.)

Example:

    project_name:  notes
    project_org:   casjay
    # FROZEN — set once at first-time setup, never edit
    internal_name: notes
    # FROZEN — set once at first-time setup, never edit
    internal_org:  casjay
    app_name:      Notes
    app_id:        io.github.casjay.notes
    min_sdk:       24

## Business logic

(Full business spec — the WHAT, not the HOW. Features, user flows, permission
justifications, trust boundaries, abuse cases, form factors, store targets,
and any exceptions.)
```

**Rules for `## Project variables`:**
- One variable per line: `key: value`
- Keys are **lower_snake_case** only
- The setup flow renders `{KEY_UPPER}` automatically by uppercasing the lowercase key
- Never guess values: use commands and existing files
- If a placeholder referenced by AI.md has no entry in `## Project variables`, setup MUST stop and ask instead of inventing a value

**Rules for `## Business logic`:**
- It MUST define the actual product scope for THIS app - not generic boilerplate
- It MUST state which form factors and store targets apply
- It MUST define user flows, stored data, trust boundaries, abuse cases, and platform constraints
- If a security-sensitive choice is intentionally allowed, the reason MUST be documented there

## Migrating Existing `CLAUDE.md` Into `IDEA.md`

**If a repository already has a pre-existing `CLAUDE.md` or `.claude/CLAUDE.md` with real project details, those project details MUST be migrated into `IDEA.md`.**

**What belongs in `IDEA.md`:**
- project description / elevator pitch
- project-specific terminology
- project variables that can be expressed as `key: value`
- business logic, roles, flows, constraints, trust boundaries, abuse cases, and security exceptions

**What does NOT belong in `IDEA.md`:**
- generic Claude/Copilot usage instructions
- loader boilerplate whose job is only to point at `AI.md`
- duplicated global implementation rules that already live in `AI.md`
- stale code snippets, one-off notes, or tool chatter with no business/spec value

**Migration rules:**
1. Read existing `CLAUDE.md` and `.claude/CLAUDE.md` first - never overwrite blindly
2. Extract valid project-specific content and reorganize it into the required `IDEA.md` layout
3. Normalize discovered variables into lower_snake_case `key: value` entries
4. If `internal_name` cannot be proven, initialize it to `project_name` on first migration and treat it as frozen after that. Do the same for `internal_org` ← `project_org`.
5. If statements from `CLAUDE.md` or `.claude/CLAUDE.md` conflict with `AI.md`, `AI.md` wins
6. After migration, keep root `CLAUDE.md` and/or `.claude/CLAUDE.md` only as short efficient loaders and keep the real plan/spec in `IDEA.md`
7. Never silently discard meaningful project-specific content; migrate it, trim it, or explicitly ask where it belongs

---

# 🆕 FIRST-TIME PROJECT SETUP

**`AI.md` is a read-only specification. Project-specific values live in `IDEA.md ## Project variables`, and the placeholders in this file are resolved from there.**

## Detecting Unconfigured Project Setup

```bash
# Project is not configured until IDEA.md exists and has required variables
[ ! -f IDEA.md ] && echo "SETUP NEEDED - IDEA.md missing"

have_name=$(grep -cE '^project_name:[[:space:]]*.+$' IDEA.md 2>/dev/null || echo 0)
have_org=$(grep -cE '^project_org:[[:space:]]*.+$' IDEA.md 2>/dev/null || echo 0)
have_internal=$(grep -cE '^internal_name:[[:space:]]*.+$' IDEA.md 2>/dev/null || echo 0)

[ "$have_name" -eq 0 ] || [ "$have_org" -eq 0 ] || [ "$have_internal" -eq 0 ] && \
  echo "SETUP NEEDED - IDEA.md project variables incomplete"
```

## Auto-Detecting Project Values

| Value | Primary Source | Fallback |
|-------|----------------|----------|
| `{project_name}` | IDEA.md `## Project variables` | Existing long-form `CLAUDE.md` / `.claude/CLAUDE.md` project details, then `basename "$PWD"` |
| `{project_org}` | IDEA.md `## Project variables` | Existing long-form `CLAUDE.md` / `.claude/CLAUDE.md` project details, then `basename "$(dirname "$PWD")"` |
| `{internal_name}` | IDEA.md `## Project variables` (set once at first run, never edited after) | First-time setup: copy from `{project_name}` |
| `{internal_org}` | IDEA.md `## Project variables` (set once at first run, never edited after) | First-time setup: copy from `{project_org}` |
| `{app_id}` | **Derived (not stored)**: `io.github.{internal_org}.{internal_name}` | Existing app: read `applicationId` from `app/build.gradle` and freeze it in IDEA.md |
| `{app_id_path}` | **Derived (not stored)**: `{app_id}` with dots replaced by slashes, e.g. `com/example/app` | Recompute whenever `{app_id}` changes; never store separately |

**Detection commands (use commands — never guess):**
```bash
project_name=$(basename "$PWD")
project_org=$(basename "$(dirname "$PWD")")
internal_name="$project_name"
internal_org="$project_org"
app_id="io.github.${internal_org}.${internal_name}"
```

**Why a separate `{internal_name}`:** if a project renames itself later, the new name applies to user-visible places (app label, docs, repo). But `{internal_name}` — and therefore `{app_id}` — stays fixed forever. **An Android `applicationId` can NEVER change after first release**: changing it publishes a different app, orphans every install, and breaks updates. `{app_id}` is the single most immutable value in an Android project.

**Existing-app rule:** when this spec is dropped into an app that already shipped, the real `applicationId` from `app/build.gradle` WINS over the derived value — record it in IDEA.md as `app_id:` and never derive again.

## First-Time Setup Flow

```
AI reads AI.md for the first time
│
├─► Check: Does IDEA.md exist with required `## Project variables` entries?
│   │
│   ├─► NO (setup needed)
│   │   ├─► 1. Read IDEA.md / existing CLAUDE.md for values; fall back to
│   │   │      directory-structure commands — never guess
│   │   ├─► 2. Existing app? Read applicationId, versionName, minSdk from
│   │   │      app/build.gradle — the shipped values always win
│   │   ├─► 3. Confirm with user: "Project: {project_name}, Org: {project_org},
│   │   │      App ID: {app_id} - correct?"
│   │   ├─► 4. Create/complete IDEA.md `## Project variables`
│   │   └─► 5. Proceed with the task using resolved values
│   │
│   └─► YES: resolve placeholders from IDEA.md and proceed
```

---

# 📑 PART INDEX

Load PARTs on demand with `grep -n "^# PART N" AI.md` — never read this file end to end.

| PART | Title | ~Line |
|------|-------|-------|
| 0 | CRITICAL RULES - READ FIRST | 194 |
| 1 | PROJECT FILES & GOVERNANCE | 317 |
| 2 | ANDROID APPLICATION MODEL | 423 |
| 3 | PROJECT STRUCTURE | 481 |
| 4 | TOOLCHAIN, BUILD & DOCKER | 543 |
| 5 | STORAGE & DATABASE | 640 |
| 6 | SECURITY & CRYPTO | 674 |
| 7 | UI, THEMING, ACCESSIBILITY, I18N | 703 |
| 8 | NOTIFICATIONS, SERVICES, BACKGROUND WORK | 758 |
| 9 | NETWORK & CONNECTIVITY | 791 |
| 10 | BACKUP, RESTORE & SYNC | 822 |
| 11 | TESTING & EMULATORS | 843 |
| 12 | CI/CD WORKFLOWS | 871 |
| 13 | RELEASE, SIGNING & F-DROID | 912 |
| 14 | IDEA.md REFERENCE | 977 |

---

# PART 0: CRITICAL RULES - READ FIRST

## THIS IS A STRICT SPECIFICATION - NOT GUIDELINES

- Every item in this specification MUST be followed exactly unless explicitly marked optional
- This is not a suggestion document
- There are no silent exceptions
- If the spec says X, do X - not "improved X"
- If something seems wrong, follow it and flag it; do not silently rewrite intent

## Attribution

**AI operates on behalf of the user in a Senior Developer / UI-UX Designer capacity.**

## ⚠️ CRITICAL: AI.md is the Source of Truth

- `AI.md` is read-only during routine work
- `IDEA.md` is where project-specific values and product rules live
- Loader files (`CLAUDE.md`, `.claude/CLAUDE.md`) stay short and point back to `AI.md`
- If a loader file and `AI.md` disagree, `AI.md` wins

## ⚠️ CRITICAL: Immutable Application Identity

`{app_id}` (`applicationId`) can NEVER change after first release — changing it publishes a different app, orphans every install, and breaks updates. For a shipped app, the value in `app/build.gradle` wins over the derived default and is frozen in IDEA.md.

## ⚠️ CRITICAL: No Host Toolchain

The Android SDK, Gradle, and JDK MUST NOT run on the host machine.

- Never invoke `sdkmanager`, `gradle`, `./gradlew`, `javac`, or lint tooling directly on the host
- Every build, test, and lint executes inside Docker using `casjaysdev/android:latest` (PART 4)
- The host's role is limited to editing source files, version control, and orchestrating Docker
- If a contributor's environment cannot run Docker, they cannot build this project — that is intentional, not a bug

## ⚠️ CRITICAL: Keep Documentation in Sync

Update these when their subject changes:
- `IDEA.md` when features or variables change
- `README.md` when install, usage, or packaging changes
- `CHANGELOG.md` (and the in-app what's-new asset if present) in the same commit as any user-visible change
- `LICENSE.md` when dependencies or attribution changes
- `metadata/` (F-Droid) when releases or store descriptions change

## Identity

| Item | Value |
|------|-------|
| App ID | `{app_id}` — immutable after first release |
| License | MIT (or as set in IDEA.md) |
| Language | Kotlin (official code style) |
| UI toolkit | `{ui_toolkit}` — **compose** (default for new apps) or **views** (existing apps keep theirs); never mix per screen without an IDEA.md migration plan |
| Min SDK | `{min_sdk}` — default **24** (documented community default); existing apps keep their shipped value; Wear/TV form factors may differ (PART 7) |
| Target / Compile SDK | Current stable at bootstrap; record in IDEA.md; keep target within store requirements |
| JVM target | 17 |
| Store targets | `{store_targets}` — default **fdroid + provider releases** (GitHub/GitLab/Gitea/Forgejo releases per git remote); **Play is opt-in** via IDEA.md (PART 13) |
| Module layout | Single `app/` Gradle module unless IDEA.md declares otherwise (Wear/TV companions get their own module) |

## ALWAYS DO

- **ALWAYS resolve placeholders from `IDEA.md ## Project variables`** — `AI.md` is read-only; never edit placeholders in place
- **ALWAYS load PARTs by slice** — `grep -n "^# PART N" AI.md`, read only that slice; cross-refs inside a slice: finish the slice first, then follow
- **ALWAYS check the IDEA.md `## Applicability` matrix before applying a conditional PART** (5 database, 8 notifications/services/background, 9 network, 10 backup/sync) — an app that doesn't use the capability skips the PART entirely
- **ALWAYS treat `{app_id}` as immutable** — never change `applicationId` in an app that has shipped
- **ALWAYS build inside Docker** using `casjaysdev/android:latest` (PART 4) — never install the Android SDK, Gradle, or a JDK on the host
- **ALWAYS ship a Room migration with any schema change** (PART 5) — never destructive-migrate
- **ALWAYS keep credentials out of the database** — Android Keystore–backed storage only (PART 6)
- **ALWAYS keep `minSdk` working** — new dependencies must respect the project's `minSdk` or be guarded by `Build.VERSION.SDK_INT` checks
- **ALWAYS keep the F-Droid flavor reproducible** (PART 13) — no non-deterministic codegen, no proprietary services, no network-fetching Gradle plugins
- **ALWAYS use `Flow`/`StateFlow` for new reactive code** — never introduce LiveData, RxJava, or callback chains
- **ALWAYS run `make check` before every commit** — compile + lint + JVM unit tests; never commit with errors, violations, or failing unit tests
- **ALWAYS follow the commit workflow** in PART 1 — `gitcommit --dir {dir} all` is the only commit path
- **ALWAYS update `CHANGELOG.md` in the same commit** as any user-visible behavior change

## NEVER DO

- **NEVER add `TODO`/`FIXME`/`HACK` or commented-out code** to committed files
- **NEVER write inline comments** — comments go above the code, single line, ≤180 chars; tool-required same-line directives (`@Suppress`, `// noinspection`) are the only exception
- **NEVER commit secrets** — no keystores with production keys, no tokens, no API keys; a dev `keystore.jks` is permitted only if generated locally and documented as dev-only
- **NEVER pull in Google Play Services** unless IDEA.md explicitly requires it — default target includes de-Googled ROMs; prefer pure-JVM/AOSP alternatives (e.g. ZXing over ML Kit)
- **NEVER create a `docker/Dockerfile.build` by default** — `casjaysdev/android:latest` covers virtually every need; escape hatch in PART 4
- **NEVER volume-mount over `/opt/android-sdk`** in the build container — it overlays the baked SDK
- **NEVER reimplement what a chosen library owns** — compose existing libraries; don't fork the wheel
- **NEVER add AI attribution** — no `Co-Authored-By:` or "Generated with" trailers anywhere

## Non-negotiables

1. **`applicationId` never changes** after first release (see ⚠️ CRITICAL above).
2. **Room schema changes always ship a numbered migration** (PART 5). SQLite < 3.35 has no `DROP COLUMN` — rename by adding a column, migrating data, and leaving the old column. A column that must genuinely disappear (rule 3) is removed by recreating the table: create the new table, copy every retained column, drop the old table, rename, recreate its indices — never a literal `DROP COLUMN`.
3. **Credentials never touch the database.** A secret-bearing column is removed outright by the table-recreate migration in rule 2; where one still exists for schema-compat reasons it is always an empty string. Storage: Android Keystore AES-GCM (PART 6).
4. **All builds run in Docker** (PART 4). The host has no SDK, no Gradle, no JDK. Never `sdkmanager`/`gradle` on the host.
5. **First run works with zero config.** No mandatory sign-in, no required server, no feature gating. Telemetry is opt-in only — default OFF.
6. **Dark mode is the default**; support dark/light/auto. Never hardcode colors — Material theme attributes and a central theme definition only.
7. **Threading discipline:** `lifecycleScope.launch {}` defaults to `Dispatchers.Main` — never call Keystore, database, or filesystem ops in a bare launch. Room suspend DAOs switch dispatchers automatically; direct Keystore/cipher calls do not — wrap in `withContext(Dispatchers.IO)`. Guard `withContext(Dispatchers.Main)` blocks in fragments with `if (!isAdded) return@withContext`.
8. **Activity composition over inheritance.** New screens are activities/fragments hosted by container patterns — never subclasses of existing screens.
9. **Reuse existing notification channels** (PART 8) — never create a channel for a one-off event.
10. **Every text file ends with a single trailing newline.** Indentation: 4 spaces for Kotlin/Gradle (ecosystem standard), 2 for XML/YAML/JSON.

## Device access reality

The development device, when one exists, is typically remote or absent. **Assume adb/USB is unavailable**: prefer the emulator path (PART 11) or treat the build artifact (APK) as the deliverable. Never block a task waiting for a physical device.

## Licensing & Attribution

- License: **MIT** by default, stored at `LICENSE.md`. IDEA.md may override.
- Third-party licenses: every bundled dependency's license must be compatible with the project license and listed in the app's About/Licenses screen (Gradle license-report plugin or a maintained static `NOTICE` section).
- Bundled assets (fonts, icons, sounds) must carry redistribution-compatible licenses (OFL, Apache-2.0, CC0); record source + license per asset in IDEA.md.
- No copyleft (GPL) dependencies in a non-GPL app unless IDEA.md explicitly accepts relicensing.
- Free & open source: no paid tiers, no feature gating, no activation gates, no telemetry-based licensing enforcement.

## Reading discipline

This file cannot be fully read in one pass and must not be. Navigate with the PART Index, load the single PART the task needs, and follow cross-references only after finishing the slice you set out to read.

## Precedence

1. `SPEC.md` — project-specific rule overrides (highest; only where it exists)
2. `IDEA.md` — project variables and business decisions
3. `AI.md` (this file) — architecture and engineering rules; wins over IDEA.md on HOW conflicts
4. `CLAUDE.md` (project) — short loader only; never spec content
5. Global `~/.claude/` rules — apply where this file is silent

---

# PART 1: PROJECT FILES & GOVERNANCE

## Project Files

| File | Purpose | Update When |
|------|---------|-------------|
| **AI.md** | Implementation spec (HOW) - SOURCE OF TRUTH, readonly | No — use SPEC.md for project-specific rule overrides |
| **SPEC.md** | Project-specific rule overrides (optional, may be empty) | When a project rule must contradict this specification or global |
| **IDEA.md** | Project plan (WHAT) | Features or variables change |
| **TODO.AI.md** | Task tracking (AI-owned) | Tasks added/completed |
| **TODO.md** | Task tracking (human-owned) | AI may mark done; never delete/empty |
| **PLAN.AI.md** | Implementation plan (AI-owned) | Planning new work |
| **PLAN.md** | Implementation plan (human-owned) | AI may mark done; never rewrite wholesale |
| **README.md** | User-facing install/usage docs | Usage changes |
| **LICENSE.md** | Project + dependency licenses | Dependency set changes |
| **CHANGELOG.md** | Keep-a-Changelog format; release-notes source (PART 13) | Every user-visible change, same commit |

## Mandatory Compliance Schedule

| When | Action | Purpose |
|------|--------|---------|
| Before each task | Read only the spec parts relevant to what you are about to implement — do not pre-load speculatively | Prevent token waste |
| Every 3-5 changes | Stop and verify against spec | Catch drift early |
| Before task completion | Full compliance check | Ensure correctness |
| When uncertain about a spec requirement | Read that specific section — never guess, never rely on prior-session memory | Accuracy without waste |

## Self-Validation Loop

**AI MUST verify its own work with real tools before reporting a task as done. Do not rely on "the code looks right."**

**This rule applies to EVERY change type covered by this specification — Kotlin logic, UI, storage, services, build, Docker, CI/CD, configuration, documentation, security — not only one category.** Whatever you touched, you verify.

Getting code correct on the first try is much harder than iterating with feedback. Close the loop every time. All execution goes through the project's containerised targets — never a host SDK/Gradle/JDK.

| Change type | How to verify |
|-------------|---------------|
| Kotlin / library logic | Run `make test` inside the container; compare output against expected |
| Behavior-preserving refactor | Diff outputs of old vs. new path on representative inputs (don't trust that the diff "looks right") |
| UI change | Build, run on an emulator (PART 11), capture a screenshot; compare against the canonical flow in IDEA.md |
| Room schema change | Run the migration test (`MigrationTestHelper` against committed schemas) — never ship an untested migration |
| Bug fix | Reproduce the bug FIRST so you have a failing signal, then verify the fix makes it disappear; add a regression test where feasible |
| Configuration / preferences | Exercise defaults on first run; verify validation rejects bad input with a useful error |
| Build / Gradle / Docker change | `make check` + `make build`; confirm APKs land in `./binaries/` |
| CI/CD workflow | `act --list -W {file}` passes; run on a branch where possible; verify each job's exit status, not just YAML validity |
| Security-sensitive change (crypto, Keystore, input validation, exported components) | Test both the success path AND attempted bypass paths; never assume a guard works without exercising it |
| Documentation / README | Render markdown; verify links, code samples, and example commands actually work |

**Iteration rules:**
- A failed check is data, not failure — adjust and re-run until green
- Never report "done" while any verification is still red
- If a check reveals the change is wrong in a way that can't be patched, revert and re-plan; do not paper over a failing check
- When verification is genuinely impossible in this environment (no emulator, no device, no network): say so explicitly. List what was checked and what could not be, so the user knows where to look

**Reference:** based on published guidance about AI coding agent self-validation (Eivind Kjosbakken, Towards Data Science, 2026) — when an AI agent is given verification tools (output diffing, emulators, test runners) and allowed to iterate, one-shot success rate, run length, and task complexity all improve substantially.

## Loader Files

| Tool | Primary Loader | Alternate Loader | Personal Override |
|------|----------------|------------------|------------------|
| Claude Code | `CLAUDE.md` | `.claude/CLAUDE.md` | `CLAUDE.local.md` |

**Loader rule:** loader files stay short. Long-form product content belongs in `IDEA.md`; long-form implementation policy belongs in `AI.md`.

## Build commands

| Goal | Command |
|------|---------|
| Compile + lint + JVM unit-test gate | `make check` |
| Debug APKs → `./binaries/` | `make build` |
| Release APKs → `./releases/` (local verification only) | `make release` |
| Unit tests | `make test` |
| Install universal APK to device | `make install` |
| Clean | `make clean` |

`make release` builds release APKs locally for verification only — production releases are still published by the channel workflows (PART 12/13), never from a local `make release` run.

## Code editing rules

1. **Comments above, never inline**; single line, ≤180 chars; no TODO/FIXME/HACK; no commented-out code.
2. **Don't reimplement what's there** — compose the chosen libraries.
3. **Schema change → migration protocol** (PART 5), same commit.
4. **Crypto stays at the boundary** — the single Keystore-backed store (PART 6); never ad-hoc crypto or credential storage.
5. **Threading discipline** per PART 0 → Non-negotiables rule 7.
6. **Sync surface is opinionated** — new persisted entities update collector/applier/matrix (PART 10) or document the exclusion.
7. **Keep `minSdk` working**; guard newer APIs with `Build.VERSION.SDK_INT`.
8. **F-Droid flavor stays reproducible** (PART 13).
9. **`Flow`/`StateFlow` only** for new reactive code.
10. **AI.md is architecture, CLAUDE.md is a loader** — architecture changes update AI.md/IDEA.md; never add spec content to CLAUDE.md.

## Commit workflow (required on every commit)

1. `git status --porcelain` + `git diff --stat` — see exactly what changed.
2. **Run `make check`** — compile + lint + device-free JVM unit tests; the mandatory pre-commit gate (instrumented tests need a device/emulator the build host generally lacks — that is why the gate is `check`, not `test`). Run `make test` when an emulator/device is reachable and the change touches security-critical code (crypto, storage, transport, exported components) — and always before tagging a release.
3. **Changelog gate** — user-visible change ⇒ `CHANGELOG.md` (and the in-app what's-new asset if present) staged in the same commit.
4. Write `.git/COMMIT_MESS` from the diff — every changed file described; never from memory.
5. Re-read `COMMIT_MESS` against the diff; rewrite if anything is missing.
6. `gitcommit --dir {dir} all` — the only commit path; never bare `git commit`, never `-m`.

**Format:** `{emoji} Title (≤64 chars) {emoji}` + blank line + body + `- path: change` bullets. Emoji map: ✨ feat · 🐛 fix · 📝 docs · 🎨 style · ♻️ refactor · ⚡ perf · ✅ test · 🔧 chore · 🔒 security · 🗑️ remove · 🚀 deploy · 📦 deps. No bare `@` handles; no attribution trailers; one logical change per commit. **Findings-based work (audits, reviews, numbered fix-lists) defaults to one commit per finding — never batch distinct findings into one commit just because they share a file or session. Feature work is the opposite — one commit for the whole feature, never split per part. Unrelated bugs found mid-feature go to `TODO.AI.md`, except app-breaking bugs, which must be fixed immediately.**

## Terminology

Maintain a terminology table in IDEA.md for the app's domain nouns (what a "session", "tab", "profile", etc. mean in THIS app) and use those terms consistently in code, comments, commits, and bug reports — never substitute synonyms.

---

# PART 2: ANDROID APPLICATION MODEL

This specification targets a **single Kotlin Android application**: one `app/` module by default, form factors per IDEA.md (PART 7), zero-config first run, and no hosted services — the app may consume remote services but never hosts any.

## Application class

A single `Application` subclass at the package root: initializes logging, theme manager, notification channels, and any DB/preference singletons. Keep `onCreate()` fast — defer heavy work to lazy init or WorkManager.

## Screens

Screen architecture follows `{ui_toolkit}`:

**compose (default for new apps):**
- Single-activity; screens are composables in a `NavHost`, typed routes (kotlinx-serialization route objects, not string-building).
- State: `StateFlow` from view-models collected with `collectAsStateWithLifecycle()`; composables are stateless — state hoisted, events up.
- Theming via `MaterialTheme` (PART 7); no view-based `PreferenceFragmentCompat` — settings are composable screens backed by the same preference wrapper (PART 5).

**views (existing apps):**
- Activities for top-level destinations; fragments for settings pages and embedded panels.
- Settings use `PreferenceFragmentCompat` with one XML per category under `res/xml/`, hosted by a single `SettingsActivity` container.
- Navigation: explicit intents with typed extras (`EXTRA_*` constants); never implicit intents for internal navigation.
- State: `StateFlow` exposed from managers/view-models, collected with `repeatOnLifecycle(Lifecycle.State.STARTED)`.

Both: never mix toolkits within a screen; a views→compose migration is incremental (one screen per commit) and declared in IDEA.md.

## Dependency injection

- Default: **manual DI** — constructor injection with an application-scoped container on the `Application` subclass; view-models via a factory.
- Koin is an acceptable IDEA.md opt-in for larger graphs.
- Hilt only if IDEA.md explicitly declares it (heavier Google/KSP tooling; complicates F-Droid reproducibility checks).
- Whatever the choice: no service locators sprinkled in code, no static singletons holding `Context` (use the application context inside the container only).

## Runtime permissions

- Request **in context** — at the moment the feature needs it, never at app start.
- Show rationale UI (`shouldShowRequestPermissionRationale`) before re-asking; after permanent denial, offer a settings deep link — never loop the prompt.
- Every feature has a **graceful denial path**: the app stays usable, the dependent feature is disabled with a clear message.
- Every permission in the manifest is justified in IDEA.md; remove permissions when the feature is removed. Never request a broad permission when a narrow API exists (SAF over storage, photo picker over `READ_MEDIA_*`).

## Crash reporting

- **Opt-in only, default OFF** — same rule as all telemetry.
- Default mechanism: on-device crash log (`Thread.setDefaultUncaughtExceptionHandler` → sanitized log through the project `Logger`) with a user-triggered "export/share crash report" action; ACRA-style self-hosted endpoint is the IDEA.md opt-in.
- Never Crashlytics/Firebase — conflicts with the no-Play-Services rule.
- Crash reports pass credential masking (PART 6) before write or send.

## Canonical user-flow documentation

Every non-trivial user flow (onboarding, primary task, import/export) is documented in IDEA.md as a numbered step list and kept current — flows are the ground truth for UI tests (PART 11).

## Error surfaces

- Recoverable errors → Snackbar with action where possible.
- Blocking errors → Material dialog with a specific, actionable message; never raw exception text.
- All errors logged through the project `Logger` with credential masking (PART 6).

---

# PART 3: PROJECT STRUCTURE

```
{project_dir}/
├── app/                          # single Gradle module
│   ├── build.gradle
│   ├── proguard-rules.pro        # release keep rules
│   ├── proguard-fdroid.pro       # extra rules for reproducible F-Droid flavor
│   ├── schemas/                  # exported Room schema JSON (committed)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/{app_id_path}/   # Kotlin sources (package = {app_id})
│       │   ├── res/                  # layouts, values, xml prefs, menus
│       │   └── assets/               # fonts, in-app docs (whats_new.md)
│       ├── test/                     # JVM unit tests
│       └── androidTest/              # instrumented/UI tests
├── docker/
│   ├── Dockerfile                # optional runtime/CI helpers — NOT a toolchain image
│   └── docker-compose.yml        # optional test-service containers
├── scripts/                      # build/install/emulator/release helpers
├── metadata/                     # F-Droid metadata ({app_id}.yml)
├── binaries/                     # debug APK output (gitignored)
├── releases/                     # release APK output (gitignored)
├── Makefile
├── AI.md                         # this spec (read-only)
├── IDEA.md                       # project variables + business decisions
├── CLAUDE.md                     # short loader
├── CHANGELOG.md                  # Keep-a-Changelog format
├── README.md
└── LICENSE.md
```

## Package layout (under `{app_id}`)

Organize by responsibility, using plural package names (this project's convention):

| Package | Responsibility |
|---|---|
| (root) | `Application` subclass |
| `ui.activities` / `ui.fragments` / `ui.adapters` / `ui.views` | Screens and widgets |
| `storage.database` (+ `.dao`, `.entities`) | Room DB, DAOs, entities |
| `storage.preferences` | Typed preference access |
| `crypto` / `crypto.storage` | Key handling, Keystore-backed secret storage |
| `services` | Foreground services, exported intent services |
| `background` | WorkManager workers, boot receivers |
| `sync` | Optional SAF/cloud sync engine (PART 10) |
| `backup` | Export/import (PART 10) |
| `accessibility` | TalkBack, contrast, keyboard-nav helpers |
| `themes` | Theme model, manager, validator |
| `utils` | `Logger`, `NotificationHelper`, small helpers |
| `platform` | SDK-version shims |

Feature-specific packages (the app's actual domain) sit alongside these; document them in IDEA.md, not here.

## Root-file rules

- `keystore.jks` in the repo is dev-only, generated by `scripts/generate-keystore.sh`; production signing comes from CI secrets (PART 13).
- Temp/output paths: all temp files under `$TMPDIR/{project_org}/{internal_name}-XXXXXX/`; never in the project tree.

---

# PART 4: TOOLCHAIN, BUILD & DOCKER

## Versions

Resolve **current stable versions at bootstrap** (fetch, never guess) and record them in IDEA.md. Known-good baseline class:

| Component | Baseline |
|---|---|
| Kotlin | 2.x current stable |
| Android Gradle Plugin | 8.x current stable |
| Gradle | wrapper pinned to the toolchain image's `GRADLE_VERSION` (compatible with the AGP version) |
| JDK | 17 (temurin — `JAVA_HOME=/opt/jdk-17` in the toolchain image) |
| compileSdk / targetSdk | current stable platform (a platform the toolchain image ships) |
| minSdk | `{min_sdk}` (default 24) |
| CMake / NDK (native projects only) | the versions baked into the toolchain image (`$ANDROID_CMAKE_VERSION` / `$ANDROID_NDK_VERSION`) — pin the same in `app/build.gradle` |

Version bumps are their own commits, verified with `make check` before anything else changes.

## Toolchain image — `casjaysdev/android:latest`

All Android CI jobs and containerized builds use this maintained image by default. Built from `dockersrc/android` on the CasjaysDev debian base, it ships: Temurin JDK 17 (`JAVA_HOME=/opt/jdk-17`), the Android SDK at `/opt/android-sdk` (`ANDROID_HOME`/`ANDROID_SDK_ROOT`) with cmdline-tools, platform-tools, and pinned platforms, build-tools, `cmake`, and NDK baked in, a pre-warmed Gradle wrapper distribution (unpacked under `/root/.gradle`), the GitHub CLI (`gh`) for release-managing jobs, and `git`/`curl`/`wget`/`unzip`/`gnupg` — `gradle`, build-tools, `cmake`, and the NDK LLVM toolchain are all on `PATH`, and CI jobs run inside the container and must never inline-install tools (PART 12). Tool versions are pinned in the image and exported as env vars — discover them at runtime (`$GRADLE_VERSION`, `$ANDROID_BUILD_TOOLS_VERSION`, `$ANDROID_CMAKE_VERSION`, `$ANDROID_NDK_VERSION`; installed platforms via `sdkmanager --list_installed`) instead of hardcoding, and align the project's Gradle wrapper, `compileSdk`, and NDK/CMake pins to what the image ships. Selection precedence (first match):

1. Image declared by the project in IDEA.md/SPEC.md/AI.md
2. Project `docker/Dockerfile.build` if it exists (escape hatch below)
3. `casjaysdev/android:latest`

**Android defaults to NO `docker/Dockerfile.build` or `build-toolchain.yml`.** A `Dockerfile.build` is allowed only for a genuine custom need the maintained image cannot satisfy — NDK-heavy native builds pinned to an exotic NDK, a proprietary vendor SDK — and then it MUST be `FROM casjaysdev/android:latest` (extend, never replace). Document the reason in a comment at the top of `Dockerfile.build`.

This picks the toolchain image only; any runtime/service image the project ships is a separate decision.

## Build types and flavors

| Variant | Purpose |
|---|---|
| `debug` | Local dev; debuggable; `.debug` applicationId suffix optional |
| `release` | Shipped build; R8 minify + shrinkResources; signed |
| `devel` | Development-channel build (PART 13): `release` configuration plus devel/debug features and the Debug Log enabled via `BuildConfig` flags (`DEVEL`, `DEBUG_LOG`); not debuggable; signed; Logger sanitation (PART 6) applies here too — the Debug Log is never exempt |
| `fdroidRelease` | Reproducible flavor for F-Droid (PART 13) — only if the app targets F-Droid |

## APK splits and naming

ABI splits ON for release. Rename outputs with simplified arch tags:

| ABI | Artifact |
|---|---|
| `arm64-v8a` | `{project_name}-android-arm64.apk` |
| `armeabi-v7a` | `{project_name}-android-arm.apk` |
| `x86_64` | `{project_name}-android-amd64.apk` |
| `x86` | `{project_name}-android-x86.apk` |
| universal | `{project_name}-android-universal.apk` |

## Dependency rules

- Every dependency respects `minSdk` or is version-guarded.
- No Google Play Services (see PART 0 NEVER list) unless IDEA.md opts in.
- No snapshot/dynamic versions (`+`); pin exact versions; Renovate keeps them current.
- Room schema export ON (`app/schemas/` committed).
- OWASP DependencyCheck runs in release CI; CVSS ≥ 7.0 fails the build (PART 12).

## Make targets (canonical set)

| Target | Effect | Output |
|---|---|---|
| `help` | list targets | stdout |
| `check` | compile + lint + JVM unit tests inside Docker (fast, device-free gate) | stdout |
| `build` | debug APKs inside Docker | `./binaries/` |
| `release` | release APKs inside Docker (local verification only — real releases are CI) | `./releases/` |
| `test` | everything in `check` plus instrumented/UI tests when an emulator/device is reachable | report |
| `install` | `adb install -r` universal APK (device path — skip when adb absent) | device |
| `clean` | remove `.gradle/`, `app/build`, `binaries/` | — |

## Docker run pattern

```make
DOCKER_IMAGE ?= casjaysdev/android:latest
DOCKER_MEM   ?= 4g
DOCKER_CPUS  ?= 2

build:
	docker run --rm --name {project_name}-$$(tr -dc 'a-z0-9' </dev/urandom | head -c8) \
	  --memory=$(DOCKER_MEM) --cpus=$(DOCKER_CPUS) \
	  -v $(PWD):/workspace -w /workspace \
	  -e GRADLE_USER_HOME=/workspace/.gradle \
	  $(DOCKER_IMAGE) ./gradlew assembleDebug
```

Rules:
- Source tree → `/workspace`; Gradle cache → `GRADLE_USER_HOME=/workspace/.gradle` (project-scoped, safe for concurrent projects).
- That override bypasses the image's pre-warmed wrapper dist at `/root/.gradle` — seed it once so `./gradlew` never re-downloads Gradle: `[ -d /workspace/.gradle/wrapper ] || cp -a /root/.gradle/wrapper /workspace/.gradle/` as the first step of the containerized command.
- **Never volume-mount `/opt/android-sdk`** — it overlays the baked SDK. `ANDROID_HOME` is preset in the image.
- `--rm --name {project_name}-XXXXXX` on every run; resource limits always set; never `-it` for batch commands.
- Native (JNI/NDK) projects: `cmake`/`ndk` versions are pinned in `app/build.gradle` AND pre-baked into the toolchain image at those same versions — Gradle must never lazily download SDK components mid-build (nondeterministic, and corrupt mid-build `sdkmanager` downloads are a known CI flake).
- In Makefiles use `$(PWD)`; in direct shell commands use `$PWD` (never `$(pwd)`).
- Test-service containers (a test sshd, a mock API) run on an isolated named network `{project_name}-test-net`, torn down after tests.

---

# PART 5: STORAGE & DATABASE

The Room/database sections apply only if the IDEA.md `## Applicability` matrix declares `database: yes`. The Preferences and Files sections apply to every app.

## Room

- One `RoomDatabase` subclass; version constant is the single source of truth.
- `exportSchema = true`; `app/schemas/` JSON committed with every version bump.
- DAOs are suspend-first; no `allowMainThreadQueries()`.

## Migration protocol (every schema change)

1. Bump the `version` constant.
2. Add a `Migration(N, N+1)` object for the step.
3. Register it in the `databaseBuilder` migration chain.
4. `make check` to trigger schema export.
5. Commit the updated `app/schemas/` JSON in the same commit.

**Never destructive-migrate.** No `fallbackToDestructiveMigration()` in any variant. SQLite < 3.35: no `DROP COLUMN` — add the new column, copy data, leave the old column in place. When a column must actually be removed (a secret-bearing column, PART 0 rule 3), recreate the table instead: create the replacement, copy every retained column, drop the original, rename, recreate its indices.

## Preferences

- A single typed preference wrapper owns all keys and defaults — no raw store access outside it.
- **New apps:** Preferences DataStore behind that wrapper (Flow-based reads, suspend writes). **Existing apps:** keep `SharedPreferences` — migrate only as a declared IDEA.md task, one-way via DataStore's `SharedPreferencesMigration`; never read the same key from both stores.
- Preference keys are stable identifiers: never rename a shipped key; migrate values if semantics change.
- Runtime-only state (open tabs, per-device caches) never goes in synced/backed-up storage.

## Files

- App-private storage by default; SAF (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`) for anything user-visible — never request broad storage permissions.
- Exported files use documented, versioned formats (JSON with a `version` field, or ZIP with a manifest).

---

# PART 6: SECURITY & CRYPTO

## Secret storage

- All credentials (passwords, tokens, passphrases, private keys) live in a Keystore-backed store: AES-256-GCM with keys generated in the **Android Keystore** (`KeyGenParameterSpec`, `setUserAuthenticationRequired` where the UX allows).
- One `SecurePasswordManager`-style class owns encrypt/decrypt/store/retrieve; no ad-hoc crypto anywhere else.
- Key aliases are namespaced per credential type: `{type}_{entityId}`.
- **DB columns never hold secrets** (PART 0). Backups/exports that include secrets encrypt them with a user-supplied password (PBKDF: Argon2id where available via BouncyCastle, else PBKDF2-HMAC-SHA256 with high iteration count) — the user decides whether to include them at all.

## Storage levels

Offer per-credential persistence levels where secrets are cached:
`NEVER` (always prompt) · `SESSION_ONLY` (cleared on process death) · `ENCRYPTED` (Keystore-persisted, optional biometric gate + TTL).

## Hardening defaults

- `FLAG_SECURE` on screens showing secrets (toggleable).
- Clipboard auto-clear timeout after copying sensitive values.
- Certificate/host trust: TOFU with explicit user confirmation on change; never silent trust-all. TLS bypass, if offered, is per-entity opt-in with a warning.
- Constant-time comparison for any secret verification.
- Logger sanitizes: regex-masks anything matching credential patterns; never log raw tokens.
- Exported components (`exported="true"`) are the exception, individually justified, and validate every incoming extra.

## Biometric

`BiometricPrompt` with device-credential fallback; biometric gates unlock the Keystore-backed store — biometrics never replace encryption.

---

# PART 7: UI, THEMING, ACCESSIBILITY, I18N

## Theming

- Material 3, dark mode default, `dark`/`light`/`auto` selectable.
- Never hardcode colors: theme attributes + a central theme definition. If the app has user-selectable themes, model them as a data class (name, isDark, semantic colors, optional palette) with a `ThemeManager` exposing the current theme as `StateFlow`.
- Contrast validation for user-created/custom themes: WCAG 2.1 AA (4.5:1) minimum, AAA (7:1) advisory; surface issues in the theme editor in real time.

## Accessibility (required, not optional)

- Full TalkBack support: content descriptions everywhere, state announcements for async operations, markup stripped before screen-reader text.
- Keyboard navigation: Tab/arrow/Enter/Escape traversal, visible focus indicators, hardware-keyboard shortcuts for primary actions.
- High-contrast mode toggle applied as a palette overlay.
- Touch targets ≥ 48×48dp; a large-touch-target preference where the UI is dense.

## I18N

- All user-visible strings in `res/values/strings.xml` — zero hardcoded UI strings in code or layouts.
- Locale folders (`values-es/`, `values-fr/`, …) added as translations arrive; the language picker lists only locales with real translation files.
- Dates/numbers via `java.text`/ICU formatting, never string concatenation.

## Human-Readable Values (User-Facing Output)

**Every value shown on a user-facing surface — Compose/View text, notifications, toasts — MUST be human-readable. Raw machine values belong to JSON/API payloads and logs only.**

| Kind | Rule | Examples |
|------|------|----------|
| **Durations** | Largest fitting unit, at most two units, correct singular/plural: <60 s → seconds · ≥60 s → minutes · ≥60 min → hours · ≥24 h → days | `1 second` · `45 seconds` · `3 minutes` · `2 minutes 5 seconds` · `2 hours` · `1 hour 30 minutes` · `3 days 4 hours` |
| **Sizes** | 1024 boundaries, full unit names, singular/plural, at most one decimal (drop `.0`): bytes → kilobytes → megabytes → gigabytes → terabytes | `1 byte` · `512 bytes` · `1 kilobyte` · `2.5 megabytes` · `5 gigabytes` · `1.2 terabytes` |
| **Counts** | Locale-aware thousands separators | `12,847` |
| **Timestamps** | Locale-aware via `java.text`/ICU formatting per the I18N rules above — never raw epoch values in visible text | `January 05, 2026 at 14:03:07 UTC` |

| Rule | Detail |
|------|--------|
| **Shared helpers** | One implementation: `Format.duration()` / `Format.size()` / `Format.count()` in a shared util — never per-screen ad-hoc formatting |
| **i18n** | Unit names go through plural string resources (`R.plurals.*`) with per-language plural rules — never hardcoded English unit strings |
| **Raw value preserved** | UI MAY carry the machine value in a tooltip/`contentDescription` where useful; the visible text is always the human form |
| **Machine surfaces unchanged** | JSON/API fields and log files keep raw base units (seconds, bytes) — formatting is a presentation concern only |

## Layout rules

- Mobile-first, responsive to tablets and foldables (test via emulator types, PART 11).
- **views:** one layout per screen; shared row items as `item_*.xml`; dialogs as `dialog_*.xml`.
- **compose:** one screen-level composable per destination; shared row items as reusable composables; dialogs as `*Dialog` composables.
- No pixel literals for spacing — dimension resources (views) or a central spacing/dimension token object (compose).

## Form factors

- Default: `form_factors: phone` (covers tablets/foldables via responsive layout).
- Additional targets declared in IDEA.md: `wear` · `tv` · `auto` · `widget`.
- Each extra factor gets its own Gradle module (PART 0 → Identity); note the differences: wear (min SDK 26+, rotary/ambient input), tv (leanback/D-pad navigation, no touch assumption), auto (templated UI only), widget (Glance for compose apps, RemoteViews for views apps).
- Never gate the phone app's features on a companion factor being installed.

---

# PART 8: NOTIFICATIONS, SERVICES, BACKGROUND WORK

Apply only the sections the IDEA.md `## Applicability` matrix declares (`notifications: yes`, `background_work: yes`, `media: yes`); an app with none skips this PART.

## Channels

- All channels created in one `NotificationHelper` at app start — no component creates private channels.
- Channel IDs are versioned (`{purpose}_v1`) — importance can't be changed after creation; a behavior change means a new versioned channel.
- Per-category user toggles in settings; a master switch.
- Android 7+ grouping: related per-entity notifications share a group key with a maintained summary notification.

## Foreground services

- Only when genuinely required (live connections, active transfers, playback); correct `foregroundServiceType`; `START_NOT_STICKY` unless resurrection is a feature.
- Declared type must match the actual work — each type is justified in IDEA.md:

| `foregroundServiceType` | Use case | Notes |
|---|---|---|
| `dataSync` | Active transfers, live connections | Auto-stop after last unit of work |
| `mediaPlayback` | Audio/video playback | Media3 ExoPlayer + `MediaSessionService`; media-style notification driven by the session — never a hand-built one |
| `location` | Active tracking the user started | Visible indicator; stop control always present |
| `camera` / `microphone` | Active capture | While-in-use permission rules apply |
- Auto-stop within a short grace period after the last unit of work completes.
- Every ongoing notification carries a direct action (stop/disconnect/cancel) — confirmation via a transparent dialog activity if destructive.

## Background work

- WorkManager for deferrable/periodic work; constraints declared (network, battery); a boot receiver reschedules periodic work.
- Respect battery saver: user-visible toggle for "run in battery saver" on monitoring-style workers.
- Exported automation surfaces (Tasker-style intents), if offered: explicit allowlist of actions, optional require-unlock, per-action logging.

---

# PART 9: NETWORK & CONNECTIVITY

Include only if the IDEA.md `## Applicability` matrix declares `network: yes`.

## HTTP client

- **One client for the whole app** — OkHttp (+ Retrofit for typed APIs) or Ktor Client; pick once, record in IDEA.md, never mix.
- Single configured instance (DI container, PART 2); no ad-hoc `URL.openConnection()` anywhere.
- Explicit connect/read/write timeouts — never library defaults.
- User-Agent: `{project_name}/{version}`.

## TLS & trust

- TLS trust per PART 6: TOFU with explicit user confirmation on change; never trust-all.
- Cleartext traffic forbidden: `networkSecurityConfig` with `cleartextTrafficPermitted="false"`; a per-host dev exception is debug-variant only.
- Certificate pinning is optional and per-host, with a documented rotation plan (backup pin) — never pin without one.

## Offline-first

- The local store (PART 5) is the source of truth for user-visible data; network refreshes it — screens render from the DB, not from responses.
- HTTP caching (ETag/Cache-Control) enabled on the client for read-only endpoints.
- Failed writes queue for retry (WorkManager, PART 8) rather than erroring into data loss.

## Connectivity & threading

- Connectivity state via `ConnectivityManager.NetworkCallback` exposed as `StateFlow` — never polling.
- All network calls are `suspend` on `Dispatchers.IO`; never on Main (PART 0 threading discipline).
- Every network error surfaces through the PART 2 error surfaces with a retry path; no raw exceptions to the user.

---

# PART 10: BACKUP, RESTORE & SYNC

Include only if the IDEA.md `## Applicability` matrix declares `backup_sync: yes`.

## Backup/export

- Format: ZIP with `manifest.json` (format version, app version, timestamp, content list) + one JSON per data domain.
- Secrets in a separate `secrets.json`, included only with explicit user choice, encrypted per PART 6.
- Restore path validates the manifest first (`validateBackup(uri)`), supports at least one prior format version, and reports per-domain restored counts.
- Runtime-only tables are excluded (PART 5).

## SAF-based device sync (optional)

- Transport: user-picked SAF folder (any cloud provider's documents tree) — no vendor SDKs.
- Wire format: versioned, AES-GCM-encrypted package keyed from a user sync password.
- Merge: 3-way (base/local/remote) with per-item conflict records surfaced to a resolution UI — never silent last-write-wins for user-authored data.
- A `SyncDataCollector`/`SyncDataApplier` pair defines the sync surface explicitly; a coverage matrix in IDEA.md documents what syncs, what doesn't, and why. **New entity checklist:** decide sync inclusion, update collector + applier + matrix in the same commit.
- Debounced sync-on-change observer + periodic WorkManager job.

---

# PART 11: TESTING & EMULATORS

## Test layers

| Layer | Location | Runs |
|---|---|---|
| Unit (JVM) | `app/src/test/` | every `make check` — and therefore every commit and every CI run |
| Instrumented/UI | `app/src/androidTest/` | on emulator/device when reachable; release CI |
| Migration tests | `app/src/androidTest/` + committed schemas | every Room version bump |

- New behavior ships with a test that fails before and passes after.
- Room migrations are tested with `MigrationTestHelper` against the committed schema JSON.
- Instrumented tests are **required** — not best-effort — before tagging a release and for changes touching crypto, storage, transport, or exported components whenever an emulator/device is reachable (PART 1 commit workflow).

## Emulator management

A `scripts/android-emulator.sh` helper manages headless test emulators:
- One AVD per (type, size); one running emulator at a time.
- Subcommands `start` / `stop` / `delete` / `clean` / `list`; types `phone` / `tablet` / `fold` / `tv` / `wear` / `auto`, optional `small` / `large`.
- Pin `-port` and address the instance as `adb -s emulator-{port}` so boot-waits can't attach to a stale instance.
- Auto-install missing SDK pieces via `sdkmanager` **inside the container/emulator host**, never the dev host.

## Device-less discipline

adb/USB is assumed unavailable (PART 0). UI verification without a device = emulator screenshots; downscale large screenshots before reading them (Android captures are 1080×2400+).

---

# PART 12: CI/CD WORKFLOWS

Provider-specific file locations and syntax: the matching `*_conventions.md` global memory file. Gates below apply on every provider.

## Workflow set

| Workflow | Trigger | Job |
|---|---|---|
| `ci.yml` | push + PR to main | `make check`-equivalent: compile, lint, unit tests, structure/security validation |
| `development.yml` | daily schedule + push to main | canonical release flow (PART 13) on the `devel` variant → rolling `development` prerelease |
| `beta.yml` | tag `*beta` | canonical release flow (PART 13) on the `release` variant → prerelease |
| `release.yml` | tag `v*` | tests + DependencyCheck + coverage → canonical release flow (PART 13) on the `release` variant (+ `assembleFdroidRelease` smoke build if F-Droid flavor exists; + `bundleRelease` AAB only if `store_targets` includes `play`) → provider release |

Creation order: security-only workflows first, `ci.yml` and the channel workflows (`release.yml`, `beta.yml`, `development.yml`) last; every staged workflow passes `act --list -W {file}` before commit.

## Rules

- All jobs run in `casjaysdev/android:latest` (or the project-declared image) — **no inline tool install** (`sdkmanager`, `apt-get`, `curl | sh` in steps).
- Third-party actions pinned to full commit SHAs, never tags.
- Signing keystore decoded from a `KEYSTORE_BASE64` secret at job time — never committed.
- Gradle cache keyed on `hashFiles('**/*.gradle*', '**/gradle-wrapper.properties')`.
- TruffleHog secret scan on every push/PR.
- OWASP DependencyCheck in `release.yml`; CVSS ≥ 7.0 fails; suppressions live in `config/dependency-check-suppressions.xml` with a reason comment per entry.
- Custom security greps (e.g. hardcoded-password patterns) maintain their exclusion list in the workflow with a documented reason per exclusion — never delete an exclusion without checking why it exists.
- Release-managing jobs that run inside the toolchain container (the `development.yml` rolling delete + recreate, asset uploads) use the provider CLI (`gh`/`glab`/`tea`) shipped in the image — never inline-installed in a step.
- Renovate for dependency updates — never Dependabot.

## Post-Push CI Verification

`act --list -W {file}` and a local `make check`-equivalent pass only prove the workflow's syntax/job graph is valid and the code works in the local environment — they are not the real CI build. Every push (normal feature-branch push or an emergency direct push to the default branch) triggers a real CI run on the provider's infrastructure with real secrets, real matrix jobs, and the real `casjaysdev/android:latest` toolchain image; any of those can fail even when every local check passed. Treating "local checks passed" as equivalent to "the build is green" is itself a bug.

After every push, check the triggered run's status:
- **GitHub**: `gh run list --branch {branch} --commit {sha} --limit 1` then `gh run watch {run-id}` (or `gh run view {run-id} --json status,conclusion`)
- **GitLab**: `curl -qsSf -H "PRIVATE-TOKEN: $GITLAB_TOKEN" ".../repository/commits/{sha}/statuses"`
- **Gitea / Forgejo**: `curl -qsSf -H "Authorization: token $TOKEN" ".../commits/{sha}/status"`
- **Jenkins**: poll the job's `lastBuild/api/json` for `result`

Build failed → this is a bug, not a note for later; diagnose the root cause and fix it with a follow-up commit — never leave the default branch red. Build pending/running → the task is not done yet; wait and re-check. No CI config in the project → this step is a no-op.

---

# PART 13: RELEASE, SIGNING & F-DROID

## Store targets

Driven by `store_targets:` in IDEA.md. **Default: `fdroid, provider-releases`** — Play is opt-in.

- **provider-releases (always):** versioned APK splits (PART 4 naming) + checksums attached to a GitHub/GitLab/Gitea/Forgejo release, provider detected from `git remote get-url origin`.
- **fdroid (default):** F-Droid section below.
- **play (opt-in only):** adds `bundleRelease` AAB to `release.yml`, requires meeting Play's current target-SDK policy, and a data-safety form kept accurate in the repo (`metadata/play/`); Play inclusion never justifies adding Play Services to the app (PART 0).

## Release channels — one canonical flow

Every channel runs the same skeleton — only the context differs (trigger, tag, version identity, build variant, prerelease flag):

build (`BUILD_EPOCH` captured once) → stage APK splits (PART 4 naming) + `mapping.txt` → `version.txt` (version, commit id, build epoch) → source archive (`git archive` → `{project_name}-{version}-source.tar.gz`) → SBOM (CycloneDX Gradle plugin → `{project_name}-sbom.cdx.json`) → aggregate `sha256.txt` + `sha512.txt` over every staged asset, computed LAST — never per-artifact `.sha256` sidecars → provenance attestation of every asset (GitHub only) → publish provider release.

| Channel | Workflow | Trigger | Tag | Version identity | Variant | Prerelease |
|---|---|---|---|---|---|---|
| stable | `release.yml` | tag push | `vX.Y.Z` | tag without `v` | `release` | no |
| beta | `beta.yml` | tag push | `*beta` | tag as-is | `release` | yes |
| development | `development.yml` | daily schedule + push to main | rolling `development` — release and tag deleted + recreated on every run | short commit id | `devel` | yes |

- **No docker-image publishing in any channel** — this is an Android app; there is no runtime image to release. Builds still execute inside `casjaysdev/android:latest` (PART 4): that is the toolchain, not a release artifact.
- The `devel` variant (PART 4) enables devel/debug features and the Debug Log; Logger sanitation (PART 6) applies in every variant.
- Asset names are identical across channels — channel identity lives in the tag, the release object, and `version.txt`, never in mutated filenames.

## Versioning

- Semver tags `vX.Y.Z`; `versionCode` monotonically increases (derive from semver: `X*10000 + Y*100 + Z` or maintain manually — pick once, record in IDEA.md).
- Beta and development builds keep the in-tree `versionCode`/`versionName` — channel identity lives in the tag and `version.txt`, never in a mutated `versionCode`.
- **`BUILD_EPOCH` is embedded in every build, local and CI** — captured once per build (`date -u +%s`) and exposed as a `BuildConfig` field; the `fdroidRelease` flavor pins it to the last-commit epoch (`git log -1 --format=%ct`) to stay reproducible.
- Release notes generated from `CHANGELOG.md [Unreleased]`, which moves to a versioned section at tag time.

## Signing

- Debug/dev: repo-local dev keystore (documented dev-only).
- Release: CI-injected keystore (`KEYSTORE_BASE64` + passwords as secrets). The production keystore file never exists in the repo or on dev machines.
- Losing the production keystore is unrecoverable for updates — IDEA.md records where it is escrowed.

## R8 / ProGuard

- Release builds: minify + shrinkResources ON; keep rules per reflective dependency (Room, serialization, crypto providers) in `proguard-rules.pro`, each rule commented with why.
- `mapping.txt` uploaded as a release asset for crash de-obfuscation.

## Play Protect & sideload warnings

Sideloaded provider-release APKs can trigger a Play Protect "unsafe app blocked / app not verified" warning even when the app is completely clean: the heuristic flags R8-obfuscated APKs signed by a certificate Google has never seen with a low install base. This is inherent to sideloading a new app, not a defect to fix in code.

- The remedy is certificate-identity consistency + time: sign every release with the same escrowed production keystore forever; the warning fades as the certificate accrues installs.
- **Never disable R8/minification to appease the heuristic** — that trades a cosmetic warning for a real regression; the R8 rules above stay mandatory.
- `mapping.txt` stays a release asset so obfuscated crash reports remain diagnosable.
- F-Droid installs don't hit this path — F-Droid signs with its own key and users install through the F-Droid client.

## F-Droid (default target; skip only if removed from `store_targets`)

- Dedicated `fdroidRelease` flavor: reproducible — deterministic R8 (`proguard-fdroid.pro` exports seeds/usage/mapping), no proprietary deps, no network at build time beyond declared Gradle deps.
- `metadata/{app_id}.yml`: categories, license, source/issue/changelog URLs, `Builds:` entry per released tag.
- CI builds the flavor as a smoke test only — F-Droid signs and publishes its own APKs; never upload self-built fdroid APKs.

## In-app changelog

If the app shows a "What's New" screen, its asset (`app/src/main/assets/whats_new.md`) is updated in the same commit as the user-visible change, alongside `CHANGELOG.md` (PART 1 changelog gate).

---

# PART 14: IDEA.md REFERENCE

`IDEA.md` holds everything project-specific this specification deliberately leaves open:

```markdown
## Project description
{Brief description of what the app does, its primary users, and what problem it
solves. Free-form prose, 1–3 paragraphs.}

## Project variables
project_name: ...
project_org: ...
internal_name: ...        # frozen at first setup
internal_org: ...         # frozen at first setup
app_id: ...               # frozen forever; from shipped applicationId if the app exists
min_sdk: 24               # or the shipped value
license: MIT
ui_toolkit: compose       # compose (new apps) or views (existing apps)
di: manual                # manual (default), koin, or hilt (declared need only)
store_targets: fdroid, provider-releases   # play is opt-in
form_factors: phone       # add wear/tv/auto/widget as needed

### Applicability
database: yes|no
network: yes|no
notifications: yes|no
background_work: yes|no
backup_sync: yes|no
media: yes|no

### Toolchain
# Only if overriding casjaysdev/android:latest — document why
build_image: casjaysdev/android:latest
kotlin: ...
agp: ...
gradle: ...
compile_sdk: ...
target_sdk: ...
version_code_scheme: ...  # semver-derived or manual

## Business logic

### Features
# App domain: feature list, package map additions, canonical user flows,
# permission justifications (PART 2), sync coverage matrix (if PART 10 applies),
# notification channel table + FG service type justifications (PART 8),
# HTTP client choice (PART 9), theme list, supported locales

### Release
# Keystore escrow location (never the keystore itself), release cadence,
# crash-reporting endpoint if the ACRA-style opt-in is used (PART 2)
```

**Bootstrap order for a new app:** IDEA.md variables → PART 3 skeleton → PART 4 toolchain + `make check` green in Docker → PART 12 security workflows → first feature → `ci.yml` and the channel workflows last.
