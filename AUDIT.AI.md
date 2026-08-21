# Project Audit

Started: 2026-08-13

## Pass 2: Code Quality
- [ ] 40 app classes fully removed by R8 (see usage.txt) — unwired features vs genuine dead code triage

## Pass 6: Code Flow Trace
- [ ] Verify the Fragment keep rule seeds all 8 settings fragments and that @Serializable classes outside the explicitly-kept packages survive R8 (needs a fresh assembleDevel usage.txt/seeds.txt)

## Evaluated and rejected as non-issues
- VMwareApiClient — no `ContinueRetrievePropertiesEx` handling, but every `RetrievePropertiesEx` call targets a single explicit MoRef with no traversal spec (0 `selectSet` in the file), so a continuation token is unreachable
- ANSIParser SGR 39/49 → palette indices 7/0 — these are exactly `CharacterAttributes`' own defaults and the fill value used by every `TerminalChar` blank, so the mapping is self-consistent; the buffer has no theme concept to defer to
- ui/tabs/TerminalPagerAdapter.onViewRecycled; sha256/sha512 checksum "asymmetry" (symmetric on inspection); development.yml ephemeral keystore (documented fork fallback)
