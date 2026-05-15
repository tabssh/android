# What's New

## Wave 11 — Multi-host Dashboard redesign + bug fixes

### Multi-host Dashboard v2
- Completely redesigned dashboard with sysadmin-first layout. Each host
  card now shows CPU / MEM / DISK progress bars with dynamic color coding
  (green < 65 %, orange 65–84 %, red ≥ 85 %), live load averages
  (1 / 5 / 15 min), uptime, per-interface network rx/tx rates, and
  process count — everything a sysadmin needs at a glance.
- **Dashboard groups** are now completely independent from connection
  groups. Create named groups just for the dashboard (stored in local
  prefs, not the database). Drag hosts between groups via long-press
  context menu. Groups collapse/expand with a chevron.
- FAB → New Group; per-group ⊕ Add, ✎ Rename, ✕ Delete buttons.
- Monitor bell button on each card opens per-host alert threshold
  configuration without leaving the dashboard.

### Keyboard fixes
- ENTER key now sends correct `\r` in all terminal modes (fixes commands
  not executing on servers with strict line-ending requirements).
- Removed phantom FN / MENU keys from the on-screen keyboard row that
  were sending unintended escape sequences.

### Mosh behavior
- Mosh exit-code handling corrected — non-zero exits no longer
  incorrectly show "connection lost" banners when the user explicitly
  quit the remote shell.

### Bug fixes
- **Performance monitor**: last-selected host now persists correctly
  across screen rotations and fragment re-creations (Spinner restore
  race fixed).
- **Debug log toggle**: Settings → Logging now shows the toggle as ON
  for debug/dev builds where logging is always active, instead of
  displaying "Off" even though the logger was running.
- **SSH key auth in connection editor**: editing a connection that uses
  SSH key auth now correctly shows the key name in the spinner instead
  of the password field (async key-load race fixed).
- **Proxmox serial console**: "unable to find serial interface" no
  longer appears as terminal garbage. A clear error dialog now explains
  exactly how to add a serial port in Proxmox Hardware settings.

## Wave 10 — Oracle Cloud Infrastructure (OCI) hypervisor support
- Fourth hypervisor target alongside Proxmox / XCP-ng / VMware. Path A
  onboarding only — import your existing `~/.oci/config` + `.pem`
  private key via the system file picker. Encrypted PEMs prompt for a
  passphrase; the imported key's MD5 fingerprint is round-tripped
  against the config's `fingerprint=` line before save so a
  mismatched key can never reach the API.
- Every request is signed with RSA-SHA256 per
  draft-cavage-http-signatures-08 (the variant Oracle's SDKs use). PEM
  + optional passphrase live in the Android Keystore under
  `oci_private_key_${id}` / `oci_passphrase_${id}` — never in the
  database.
- Manager screen lists Compute instances per tenancy: lifecycle state,
  shape, availability domain, public/private IP (resolved via the
  primary VNIC). Buttons: Start, Stop, Soft Stop, Reset, Reboot. No
  console — that needs OCI's bastion-over-SSH path which is its own
  thing.
- Region picker is seeded with the 34 current commercial regions
  (free-text always allowed; Oracle adds regions quarterly).
- Reject path: configs carrying `security_token_file=` are refused
  during import — those are 1-hour CLI-renewable session tokens with
  no upload renewal path.

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
