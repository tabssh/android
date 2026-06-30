# TabSSH Android

> **Read `AI.md` (THE HOW) and `IDEA.md` (THE WHAT) before writing any code.**
> Navigation table, build commands, artifact locations, and all dev rules: see `AI.md §17`.

## Honesty over agreement

Do not just agree. If a request is impossible, partially possible, or carries non-obvious caveats, say so plainly **before** writing code.

- If a feature cannot be built on Android (missing API, permission denied, sandbox boundary, root required, hardware unavailable): say "this is not possible because X" and stop. Do not invent a workaround that pretends to deliver the feature.
- If a feature is possible but only under specific conditions (e.g. autodetection that only works at login time, not mid-session): state the conditions and the gaps up front. Let the user decide whether the partial solution is worth shipping.
- If the user's proposed approach has a fatal flaw (race, security hole, contradicts an existing invariant in the code): push back with the specific reason. "Yes, doing it now" when the answer should be "no, that breaks Y" wastes both of our time.
- Never silently scope-down a request to something easier and call it done. If you cannot deliver the full ask, say which part you skipped and why.
- "Doable" and "doable well" are different — distinguish them. A fragile heuristic that works 70% of the time is not a fix unless the user accepts that ceiling.
