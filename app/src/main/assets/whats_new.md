# What's New

## Wave 9 — Real Mosh via Termux
- The Mosh handoff dialog now detects **Termux** and offers a one-tap
  **"Open in Termux"** button. TabSSH dispatches `mosh-client` directly
  via Termux's RUN_COMMAND service — full UDP transport, real roaming,
  predictive echo. Requires Termux + `pkg install mosh` once + setting
  `allow-external-apps=true` in `~/.termux/termux.properties`.
- The dialog adapts to what's installed:
  - Termux not installed → "Install Termux" button (Play / F-Droid).
  - Termux without mosh → exact `pkg install mosh` instructions.
  - Both ready → one-tap launch.
- Future native bundling (mosh-client compiled into TabSSH itself) is
  scoped via `mosh/build-android.sh` — multi-session NDK cross-compile.

## Wave 8 — picking up deferred items
- **AWS EC2** inventory via SigV4 signing — paste an Access Key ID +
  Secret + Region as `AKID:SECRET:us-east-1` to import running instances.
- **GCP Compute Engine** — paste a service-account JSON; we sign a JWT
  locally and exchange it for a short-lived access token (compute.readonly).
- **Azure VMs** — paste `TENANT:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID`
  (service-principal client-credentials).
- **FIDO2 / U2F detection (Alpha, untested)** — Settings → "FIDO2 Hardware
  (Alpha)" detects connected YubiKeys / SoloKeys / Nitrokeys via USB +
  reports NFC availability. **SSH auth via these keys is NOT yet wired** —
  JSch doesn't support `sk-*` SSH key types. This dialog confirms the
  device sees the hardware; full auth is a separate project.
- **SFTP multi-connection tabs** — in the SFTP browser, a chip strip lets
  you open additional connections (must already have an active SSH tab).
  Click a chip to swap which connection's remote pane is shown.

## Wave 2.X — Mosh + X11 (honest scope)
- **X11 forwarding** is real now. Toggle "X11 forwarding" on a connection;
  remote `xclock` / `xeyes` / `xfce4-terminal` / etc. will route their X11
  traffic to `localhost:6000` via JSch's X11 channel forwarding. **You
  need an X server on the device** — install **XServer-XSDL** (free, on
  F-Droid + Play) and start it before connecting. With XServer-XSDL on
  the default port, that's it.
- **Mosh handoff** (NOT real Mosh): a new **"Mosh handoff…"** menu item
  in the terminal runs `mosh-server new` over the live SSH session,
  parses `MOSH CONNECT <port> <key>`, and shows the user a ready-to-copy
  `MOSH_KEY=… mosh -p PORT user@host` command. Closing your TabSSH tab
  does NOT kill mosh-server — Mosh detaches and keeps listening on UDP.
  TabSSH itself does not speak Mosh's UDP wire protocol; pair this with
  an actual Mosh client (Termux's `mosh`, the official iOS Mosh app,
  etc.) to get true roaming.

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
