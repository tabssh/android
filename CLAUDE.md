# TabSSH Android

> **Read `AI.md` (THE HOW) and `IDEA.md` (THE WHAT) before writing any code.**
> Navigation table, build commands, artifact locations, and all dev rules: see `AI.md §17`.

## Changelog hygiene — required on every commit

Every commit that changes user-visible behaviour **MUST** update BOTH files in
the same commit (never stale, never a separate follow-up):

| File | Format | Audience |
|------|--------|----------|
| `CHANGELOG.md` | Keep-a-Changelog, `[Unreleased]` section | Developers, release notes |
| `app/src/main/assets/whats_new.md` | Wave-numbered prose, user-facing | In-app "What's New" screen |

Rules:
- `CHANGELOG.md [Unreleased]` gets one bullet per logical change under
  **Added / Changed / Fixed**.
- `whats_new.md` gets a new **Wave N** section (increment from the previous
  highest wave number) covering the most user-visible features in plain language.
  Skip internal refactors and CI changes.
- Both files must be staged in the COMMIT_MESS diff. If the diff touches only
  code and not these files, the commit is incomplete.
