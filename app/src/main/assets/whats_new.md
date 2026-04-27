# What's New

## Wave 4 — speculative polish (in progress)
- True 24-bit color rendering (fix latent crash on `SGR 38;2;R;G;B`)
- Cluster command results stream live as each host completes
- **Voice typing**: works out of the box — tap the mic on any voice-capable
  IME (e.g. Gboard); spoken text streams into the active terminal exactly
  like typed input. Nothing to enable.

## Wave 3 — polish ✅ COMPLETE
- Per-host color tags on connections (visible strip on cards & rows)
- Connection history view (most-recent-first, tap to reconnect)
- This What's New screen
- PIN code app lock (alongside biometric, FLAG_SECURE)
- Run port forwards without a terminal session (background tunnels)
- Auto-detect HTTP on a forwarded port and offer to open browser
- Compact 48dp bottom nav bar (preference toggle)
- Bluetooth keyboard polish: AltGr passes through correctly on EU layouts

## Wave 2 — Tier 2 features
- Snippet variables: `{?name:default|hint}` with per-(snippet,var) recall
- OpenSSH user certificate auth (`*-cert.pub` attached to a stored key)
- **Telnet (RFC 854)** alongside SSH — for network gear / console servers
- In-app theme editor (clone a base, tweak colors, live preview)
- Workspaces — save current open tabs as a named set, reopen later
- Command palette (Ctrl+K) and Quick switcher (Ctrl+J)
- Broadcast input — type once, mirror to selected tabs
- Split view — vertical 2-pane per tab (tap to focus)
- Remote shell history palette (Ctrl+R)
- FIDO2/U2F SSH — deferred until hardware testing

## Wave 1 — parity catch-up
- Reconnect dialog instead of silent auto-close on disconnect
- Per-host environment variables
- Find-in-scrollback over the Termux buffer
- SSH agent forwarding (per-host opt-in)
- Bulk import (CSV / JSON / PuTTY .reg / Terraform .tf)
- In-app remote text-file editor over SFTP
- chmod editing in SFTP (rwx checkboxes + live octal)
- SCP fallback (device → server) for systems without SFTP

For full git history see https://github.com/tabssh/android/commits/main
