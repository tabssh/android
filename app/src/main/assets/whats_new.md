# What's New

## Wave 26 — Long press = menu, smooth scrolling

### Long press
- Long press on a **URL** → Open / Copy URL dialog (unchanged)
- Long press on **anything else** → terminal action menu

Copy and paste live on the dedicated clipboard key (📋) in the keyboard
bar — long press no longer starts text selection.

### Smooth scrolling (issue #8)
Terminal scrolling is significantly faster and smoother:

- The render loop previously called `drawText` **once per character** — up
  to ~2 000 GPU draw calls per frame on a standard terminal. Characters with
  the same colour and style are now batched into a single draw call per run,
  reducing GPU calls by up to 20×.
- Scroll invalidation changed from `postInvalidateOnAnimation` (next vsync)
  to `invalidate` (immediate), giving true 1:1 finger-to-content tracking
  instead of a one-frame lag.

---

## Wave 25 — Volume buttons can now scroll the terminal

A new **Volume Key Action** option in Settings → Terminal lets you choose what
the volume buttons do while a terminal session is focused:

- **Font size** (default — same as before)
- **Scroll** — Volume Up pages toward older history, Volume Down returns to
  the latest output
- **System volume** (off — pass the keys to Android)

---

## Wave 24 — Public key export fixed, scroll and spacing settings work

### Public key export (issue #7)
Generated SSH keys (Ed25519, RSA, DSA, ECDSA) now export in the correct
**OpenSSH `authorized_keys` wire format**. Previously all key types were
exported in X.509 SPKI/DER encoding, which `sshd` silently rejects — meaning
public-key authentication could never work with any key generated in TabSSH.
If you've been using public-key auth, please re-export your keys and update
your `~/.ssh/authorized_keys`.

### Vertical spacing setting now works (issue #9)
The **Vertical spacing** slider in Settings → Terminal now takes effect
immediately on all open tabs and persists across tab switches.

### Reverse scroll direction setting now works (issue #8)
Toggling **Reverse scroll direction** in Settings now takes effect as soon as
you return to the terminal — no more needing to restart the app.

---

## Wave 23 — Import/Export crash and password revoke fixed

- **Import/Export** no longer crashes if you tap a card in the first
  fraction of a second after opening the screen; a friendly "not ready"
  message is shown instead
- **Unsaving a password now actually removes it** — unchecking "Save
  Password" on an existing connection now clears the stored credential from
  the Keystore; previously the old password was silently reused even after
  you unchecked the option

---

## Wave 22 — Nine more bugs fixed

- **Cluster broadcast** dialog now shows which sessions you're broadcasting to
  (the session count was hidden by a dialog-builder conflict)
- **Pinch-to-zoom** no longer accidentally starts a text selection when the
  Select key was armed
- **Screen session list** now shows the correct attached/detached status and
  preserves session names that contain dots (e.g. `dev.backend.api`)
- **Split-pane** SSH session is fully released when the split is closed or the
  activity is destroyed — previously the SSH slot leaked and the notification
  persisted
- **Scroll fling** after `clear` no longer leaves a blank strip at the bottom
- **Sync monitoring cooldown** now applies correctly on the receiving device
- Two internal preference alias inconsistencies unified

---

## Wave 21 — Terminal menu tab switch fixed

Tapping a tab in the long-press menu after another tab had closed would
activate the wrong session. The row tap now looks up the live tab by ID
rather than using the position from when the sheet opened.

---

## Wave 20 — Sync and backup now cover everything

Five preference categories that were silently missing from both sync and
backup/restore are now fully covered:

- **Connection defaults** — default username, port, timeout, auto-reconnect,
  compression
- **Sync settings** — frequency, Wi-Fi only, per-category sync toggles
- **Multiplexer bindings** — gesture enable/type, per-type prefix keys (tmux,
  screen, zellij)
- **Accessibility** — high-contrast terminal, large touch targets
- **Proxy** — type, host, port, credentials, bypass list

Restore a backup or sync to a new device and every setting comes with it.

---

## Wave 19 — Bug batch: auth dialog, search, connect, ANR

Nine correctness bugs fixed in one batch:

- **Password prompt** now shows the "Password required for user@host" message
  above the input field — it was silently dropped by a dialog-builder conflict
- **Find in Scrollback** no longer shows "No active session" when invoked from
  the terminal menu — the controller now initialises lazily on first use
- **Clean-exit tab close** no longer triggers a double-`finish()` race that
  could cause duplicate navigation or toast spam
- **Connect timing** replaced a fragile 200 ms sleep with a proper main-looper
  yield; first bytes from the server are no longer dropped on loaded devices
- **Blank screen after connect failure** — the activity now closes itself when
  tab creation fails instead of leaving a useless empty screen
- **Disconnect no longer risks ANR** — JSch socket teardown moved off the main
  thread to a background IO coroutine
- **Long-press while scrolled** now detects URLs and shows the context menu for
  the correct line (scroll offset was computed differently from the render path)
- **Multiplexer probe** skipped when the profile has multiplexers disabled
- **tmux sessions named `foo:bar`** now parse correctly (separator changed from
  `:` to `|` in the tmux format string)

---

## Wave 18 — Scrollback fixed

Scrolling into terminal history no longer blanks the screen. A one-line arithmetic
error caused the canvas to be shifted by the full scroll offset instead of just
the sub-row fractional pixel remainder, making all content disappear the instant
you scrolled. Fixed.

---

## Wave 17 — Redesigned terminal menu

The long-press bottom sheet has been fully redesigned with Material Design 3
polish:

- **Drag handle** at the top — swipe down anywhere to close
- **"New Tab…"** is now a prominent outlined button — one tap opens a new
  connection without hunting through the menu
- **Tab list** shows every open session with a colour-coded connection dot
  (green = connected, amber = connecting, red = error, grey = disconnected);
  the active tab is shown in bold with a green check on the right; tapping any
  row switches to it immediately
- **Terminal section** — Toggle System Keyboard, Toggle Key Bar (label shows
  current state), Find in Scrollback, Select Text, Copy Screen, Snippets
- **Session section** — Broadcast to All Tabs, Port Forwarding, Share Session,
  Close Current Tab, Disconnect All
- **Settings** at the bottom, always one tap away
- Paste removed from this menu — it lives on the key bar where it's faster
  to reach

---

## Wave 16 — Terminal menu, settings cleanup

### Terminal long-press menu
The long-press menu now has everything you need without opening the command
palette. New items:
- **Toggle System Keyboard** — show or hide the on-screen keyboard
- **Toggle Key Bar** — show or hide the custom function-key bar (label
  updates to reflect current state)
- **Find in Scrollback…** — search the current tab's history
- **Paste** — paste clipboard contents into the terminal

### Settings cleanup
- Mosh command is now configured per-connection only (in the connection editor);
  the leftover "Global Mosh Server Command (legacy)" field in Settings →
  Connection is removed

---

## Wave 15 — PRE key shows your prefix, settings tidied

### PRE key label
The **PRE** key in the keyboard bar now displays the prefix shorthand you've
configured rather than always showing `PRE`. If you use tmux with the default
`C-b` prefix the key reads **^B**; change it to `C-Space` and you'll see
**^Sp**; set a custom `M-b` and it shows **M-b**. Reverts to `PRE` when no
multiplexer is active.

### Prefix field examples
Each multiplexer prefix setting (tmux, screen, zellij) in **Settings →
Connection → Multiplexer** now shows an example dialog when you tap to edit,
explaining every supported notation: `C-b`, `^b`, `C-Space`, `M-b`, `Alt-b`,
`0x02`, and bare literal characters.

### Settings reorganised
Multiplexer settings have moved from **Settings → Terminal** to **Settings →
Connection → Multiplexer** where they belong. Terminal settings now only
contains terminal display and input options.

---

## Wave 14 — PRE key multiplexer picker fixed

Tapping the **PRE** key when no multiplexer was detected would open a "Select
multiplexer" dialog but show nothing to tap — a dialog-builder conflict caused
the option list to be hidden behind the description text. The three choices
(tmux, screen, zellij) now render correctly, so you can set your multiplexer
type with a single tap and the PRE key will send the right prefix immediately.

---

## Wave 13 — Smoother scrolling, key polish, notification fixes

### Natural scroll direction
Scrolling is now standard: **swipe UP to see older terminal output** — the same
convention as JuiceSSH, Termux, and every other terminal on Android. If you
preferred the old direction (swipe down to see older), flip the toggle in
**Settings → Terminal → Reverse scroll direction**.

### Smooth terminal scrolling
Scrollback now glides continuously instead of snapping one full row at a time.
The underlying scroll position is tracked at sub-pixel precision and the canvas
is offset fractionally, so slow drags and flings both feel natural.

### Keyboard key polish
- **CTL and ALT** keys now show a solid green fill with dark text when latched —
  the old dim-alpha change was easy to miss; the green fill is unmistakable
- **CTL / TAB / ENT / ESC** reduced from 2× to 1.5× wide, and text is slightly
  larger, so the label fills the key without floating in empty space

### Notification disconnect fixed
Two bugs squashed:
- Tapping **"Disconnect"** in the notification now actually disconnects, even
  if the session dropped and reconnected since the notification appeared
- The notification now disappears (or shows "Disconnected" then auto-clears in
  30 s) reliably when a session ends — previously it could stick forever

---

## Wave 12 — Multiplexer PREFIX key, SSH config import, text selection

### PRE key for tmux / screen / zellij
The custom keyboard bar now has a dedicated **PRE** key (row 3, under ENT).
It sends the right prefix byte automatically:
- **Green** = multiplexer detected → tap to send C-b / C-a / C-g
- **Dim** = no multiplexer detected → tap to pick which one is running

The app detects your multiplexer by probing `$TMUX`, `$STY`, and
`$ZELLIJ_SESSION_NAME` on a background channel 2 seconds after you connect,
then re-checks every 30 seconds. Attach or detach tmux in your session and
the key updates automatically — no reconnect needed.

Per-type prefixes are now configurable in **Settings → Connection →
Multiplexer Prefixes** (defaults: tmux C-b, screen C-a, zellij C-g).

### SSH config import improvements
- **Keys resolve automatically** — import your `~/.ssh/config` after adding
  your SSH key to the Identities tab. TabSSH matches the `IdentityFile`
  filename against your stored key alias (e.g. `id_ed25519`) and links them
  without any manual editing.
- The import dialog warns clearly when a key couldn't be resolved and offers
  a direct "Identities" shortcut to import it.
- **Smart key naming** — when you import an SSH private key the Name field
  defaults to the comment embedded in the key file (e.g. `user@host`), and the
  Alias field defaults to the SSH naming convention (`id_ed25519`, `id_rsa_001`,
  etc.). Both are editable before import.
- `ServerAliveInterval` in your config is now per-connection and honoured at
  connect time instead of being ignored.

### Text selection & copy fixed
- Long-press context menu now reliably shows the Copy / Select All / Paste bar
  in all multi-tab configurations.
- Drag-to-select no longer jumps or clears when your finger touches the handle
  area below the highlighted text — the grab radius is now forgiving.

### Connection notifications
Notifications now show your connection **name** ("prod server") instead of the
raw IP address, making the notification tray useful when you have many servers.

### Mosh command presets
The connection editor now has a **Mosh server command** dropdown with common
presets (port range, IPv4/IPv6-only, custom path) plus a Custom option. The old
global setting that was silently ignored has been replaced.

---

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
- **Proxmox VNC console stability** — three protocol bugs fixed that
  caused connections to fail immediately or corrupt the image stream:
  the vncproxy API call now includes `websocket=1` so Proxmox sets up
  WebSocket transport (without it the internal port only accepts raw
  TCP); VNC sessions now open in shared mode (ClientInit shared=1)
  instead of exclusive mode which could cause Proxmox to reject the
  session; corrupt or misaligned compressed rectangle data now triggers
  a clean disconnect instead of crashing with OutOfMemoryError.

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
