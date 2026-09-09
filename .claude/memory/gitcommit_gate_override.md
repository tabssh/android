---
name: gitcommit_gate_override
description: Standing authorization to bypass the test/lint gate marker on gitcommit for this project, whenever make check/make test already passed.
type: project
---

# Test/Lint Gate Override — Standing Authorization

`enforce-test-lint-gate.sh`'s marker sometimes never fires for this
project even after a genuinely passing `make check` / `make test`
(upstream Claude Code bug `anthropics/claude-code#6305`, open — the
`PostToolUse`/`Bash` marker hook and its transcript-path fallback both
intermittently miss a real 0-exit run). This has recurred repeatedly
in this project specifically.

**Standing authorization (given by the user, applies to this project
for its entire lifetime, not just one session):** when `gitcommit`
is blocked by this gate and `make check` or `make test` has already
been run and exited 0 in the current session, run
`TEST_LINT_GATE_OVERRIDE=1 gitcommit --dir {dir} all` without asking
first.

This does **not** relax anything else:
- `make check`/`make test` must actually have been run and have
  actually exited 0 first — never override without that real,
  verified pass.
- Never use this to skip a failing or not-yet-run test/lint pass.
- Every other confirm-before-destructive-op rule still applies
  unchanged.
