# TODO.AI.md

## Main-thread sweep (2026-09-09) — logged, not fixed

- `app/src/main/java/io/github/tabssh/ui/activities/WhatsNewActivity.kt:76` —
  `assets.open(ASSET).bufferedReader().use { it.readText() }` runs directly in
  `onCreate()` on the main thread. Left as-is: `whats_new.md` is a tiny
  hand-curated asset opened only when the user explicitly navigates to this
  screen (no on-upgrade auto-pop, per the file's own kdoc), so the ANR risk is
  negligible — genuinely ambiguous whether this is worth the added
  `lifecycleScope.launch(Dispatchers.IO)` complexity for a one-screen,
  user-initiated, sub-millisecond read. Revisit if the asset ever grows large
  or gains network-backed content.
