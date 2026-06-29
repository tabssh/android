# TabSSH Android — Project-Specific Overrides

This file lists rules where TabSSH Android **deliberately diverges** from the
global rules in `~/.claude/CLAUDE.md`. Per the spec hierarchy
(`SPEC.md > AI.md > global CLAUDE.md`), the overrides below win for this
project.

Only true conflicts belong here. Project-specific knowledge that does not
contradict a global rule belongs in `AI.md`.

---

## Pre-commit gate: `make check`, not `make test`

**Global rule** (`~/.claude/CLAUDE.md` → Commit Workflow → Test gate):
> `make test` must pass before every commit — no exceptions.

**Project override:**
The mandatory pre-commit gate for TabSSH Android is **`make check`**
(compile + lint), not `make test`.

**Reason:**
Android instrumented tests (`make test`, `./gradlew connectedAndroidTest`)
require a connected device or running emulator. The build host generally has
neither. Forcing `make test` on every commit would either block all commits
or push developers to bypass the gate entirely.

**Policy:**
1. `make check` is the **mandatory** gate before every `gitcommit` — never
   commit with compile errors or lint violations.
2. `make test` is **required when a device/emulator is available** — run it
   before tagging a release, before merging a PR that touches SSH transport,
   crypto, storage, or backup/restore, and whenever the developer has a
   device attached.
3. Unit tests that do not require a device (`./gradlew test`) run as part of
   `make check` and must always pass.
