# What's New

## Wave 60 — Hardware keyboards, correct keys, mosh reliability

- **Full hardware-keyboard support (Bluetooth, USB, OTG)** — the
  terminal now handles physical keyboards properly. Numpad Enter
  submits the line (it used to do nothing), and Shift/Alt/Ctrl
  combined with the F1–F12 keys are sent through correctly, so
  vim and tmux see modified function keys from a real keyboard.
- **Alt sends the right escape for every key** — Alt combined
  with any printable character (not just letters and digits) now
  sends the standard terminal escape, so bash/readline shortcuts
  like Alt+. (insert last argument) and Alt+/ (complete), plus
  emacs and tmux Alt+punctuation bindings, work as expected on
  both the on-screen and hardware keyboards.
- **No more doubled characters when typing with predictive or
  CJK input** — glide typing, pinyin, and other predictive
  keyboards used to duplicate each character as the word was
  being composed. The terminal now waits for the input method
  to finalise the word before sending it, so what you type is
  what arrives.
- **Mosh no longer gives up on a working connection** — a short
  internal timer could abort a mosh session that was simply slow
  to draw its first screen (common on high-latency links),
  falling back to plain SSH for no reason. Mosh now falls back
  only on a genuine failure it actually reports — UDP blocked, a
  firewall in the way, or mosh-server not installed — and
  otherwise lets the session connect at its own pace.

## Wave 59 — Network resilience, VNC console, connection polish

- **Tabs recover on their own when the phone loses and regains
  network** — a dropped Wi-Fi or cellular connection used to
  leave every open tab stuck at a dead prompt. The app now
  watches for the network coming back and reopens the shell
  channel on the same SSH session automatically. Tabs running
  under tmux or screen re-attach to the live session; raw
  shells get a fresh prompt (the pre-drop scrollback is kept
  in the buffer).
- **A visible "Offline" banner appears on the Multi-Host
  Dashboard** — when the phone is offline, host reachability
  checks are paused. The dashboard now says so plainly at the
  top of the screen, so a wall of grey "unreachable" hosts is
  never mistaken for every server being down.
- **Long URLs that wrap across two terminal rows are now fully
  underlined and tappable** — before, only the first row of a
  wrapped URL showed the tap-me underline; the continuation
  row rendered as plain text even though tapping it did open
  the full URL. The renderer now scans wrap segments as one
  unit and underlines every continuation row.
- **The clipboard is no longer wiped when it did not come from
  TabSSH** — the auto-clear used to fire on any clipboard
  content while a session was in the background, so text you
  copied from another app could disappear. Auto-clear now
  runs only for clips that TabSSH itself wrote AND marked as
  sensitive (like a password copied via a secure-copy action);
  everything else is left alone.
- **The About dialog now shows the real version and the build
  commit** — devel, daily, and beta builds used to all display
  the same hardcoded "Version 1.0.0". The dialog now reads the
  version, build number, commit hash, and build type straight
  from the running binary, so you can tell at a glance which
  build you are on.

### VNC / graphical console

- **XCP-ng hosts get a graphical VNC console** — pick an
  XCP-ng / XenServer host, choose a running VM, and the built-in
  VNC viewer opens against the hypervisor's console proxy. No
  extra client needed.
- **Direct VNC-over-WebSocket (websockify / noVNC)** — profiles
  can now connect straight to a `wss://` VNC endpoint without a
  side-channel proxy, so noVNC deployments and Kubernetes
  ingress paths just work.
- **QEMU consoles no longer break when the phone rotates** —
  the client used to send a "resize the remote desktop"
  request on every rotation, which QEMU rejects, producing a
  black screen. The resize request is now suppressed for
  servers that do not support it.
- **Snappier VNC updates** — when the server supports it,
  ContinuousUpdates is enabled automatically so the display
  streams changes instead of round-tripping a request per
  frame. The VM's own desktop name is also shown in the tab
  title.

### Multiplexer / mosh / connect speed

- **tmux and screen are detected and offered on first connect**
  — the app now probes the server at login time for tmux/screen
  and asks whether you want to attach or start a new session,
  instead of dumping you at a raw prompt.
- **mosh sessions fail fast and fall back to SSH when UDP is
  blocked** — the old behaviour was to hang for a full minute
  on hotel or corporate Wi-Fi that blocks mosh's UDP port.
  A short watchdog now cuts the wait and rolls the connection
  to plain SSH automatically.
- **mosh-client no longer crashes on Android with a "locale
  not set" error** — a missing UTF-8 locale on the Android
  runtime is now set up before mosh-client launches.
- **SSH connects noticeably faster** — the client pins a small
  set of fast key-exchange and host-key algorithms first, and
  each connect phase is timed so slow steps are visible in the
  Application Logs.
- **Connection failures now tell you what actually went wrong**
  — DNS failure, wrong port, refused connection, auth failure,
  and host-key mismatch each get their own message instead of
  the generic "socket error" that hid the real cause.

### Connection editor

- **Editing a connection is much more forgiving** — dozens of
  small bugs and layout issues in the connection editor are
  fixed: fields that reset while you were typing, spinners
  that flashed the wrong option, race conditions that dropped
  edits, accessibility labels missing on the auth pickers, and
  the "No Identity" option that used to hide the password
  field even though you needed it.
- **VNC profiles can now override the host password** — set
  a per-VM password without having to touch the parent host
  entry.
- **Shift (SFT) key added to the extra keyboard bar** — pair
  it with any other key for capitals, symbols, or shortcuts
  the on-screen keyboard cannot easily produce.
- **Monitor dashboard alert thresholds respect your global
  defaults** — new hosts used to inherit a 0% threshold,
  meaning every metric alerted immediately. New hosts now
  inherit the values you configured in Settings.

## Wave 58 — Editor / log viewer / PIN lock audit

- **Remote file editor no longer double-saves the same file** —
  tapping the Save action twice in quick succession used to fire
  two concurrent uploads against the same path, with whichever
  finished last silently clobbering the other. The Save menu
  item now disables itself while an upload is in flight, and a
  second tap shows "Save already in progress…" instead of
  racing.
- **Application Logs and Audit Logs no longer freeze the UI on
  large logs** — opening the Application Logs viewer used to read
  the entire `tabssh.log` file on the UI thread, which on slow
  storage could stutter or trigger an Application Not Responding
  warning. Both the log read and the export-to-file write now run
  in the background and stream the file line-by-line.
- **Exported Audit Log CSV files are now correctly quoted** —
  the CSV exporter wrote unquoted fields, so any command or
  output containing a comma, a newline, or a double-quote
  produced a corrupt file that no spreadsheet could open
  cleanly. Every field is now wrapped in quotes and internal
  quotes are doubled as the CSV standard requires.
- **PIN lock brute-force budget now survives force-stop** — the
  failed-attempt counter used to live in memory, so anyone who
  burned four wrong guesses could simply kill the app to reset
  the budget. The count is now saved to disk and restored when
  the lock screen reopens, and a successful unlock or a fresh
  PIN setup clears it.
- **Deleting a session transcript no longer briefly freezes the
  list** — transcript delete used to walk the filesystem on the
  UI thread; it now runs in the background and reports success
  or failure when it finishes.

## Wave 57 — Activities deep-dive audit

- **Keyboard Layout Editor now has a "Reset to default"
  action** — open the editor, tap the overflow menu in the
  top bar, choose "Reset to default", and the layout snaps
  back to the factory rows. The change is shown in the
  preview immediately but is not saved until you tap the
  Save button (the floppy-disk icon at the bottom-right) —
  same as every other change in this screen, so an
  accidental tap will not overwrite your real layout.

## Wave 56 — SFTP activity audit

- **"Select All Local" and "Select All Remote" now actually
  work** — the two SFTP menu items were stubs that did nothing
  when tapped. They now select every file in the current pane
  (folders are skipped) and confirm with a toast like "Selected
  12 local file(s)" so you can immediately upload or download
  the whole listing.
- **Tapping Upload / Download before the SFTP session has
  finished connecting no longer crashes the app** — instead you
  get a clean "SFTP not connected yet" message and the activity
  stays open.
- **Two SFTP menu entries that did nothing were removed** —
  "Transfer Settings" and "Bookmarks" were placeholders that
  led nowhere. They are no longer in the menu so the only
  options shown are ones that work.

## Wave 55 — Edit-activity deep-dive audit

- **Editing a connection no longer silently forgets its
  identity** — opening an SSH connection that was bound to a
  reusable identity used to reset the identity dropdown to
  "No Identity" because the form was restored while the identity
  list was still loading in the background. The editor now waits
  for the identity list to finish loading before restoring the
  selection, so identity-bound profiles open with the right
  identity already selected.
- **Connection editor asks before discarding your edits** — the
  Back button and the Cancel button now prompt "Discard changes?
  — Discard / Keep editing" when you have unsaved edits. A clean
  form (no edits made, or saved successfully) closes immediately
  without nagging.
- **Editing a VNC host no longer kicks it out of its group** — the
  VNC host editor was rewriting `groupId = null` and overwriting
  the original creation time on every save. Group membership and
  creation time are now preserved across edits; only the fields
  you actually change get updated.

## Wave 54 — Fragments deep-dive audit

- **Performance screen no longer paints the 1-minute load colour
  twice per metrics tick** — a duplicated block was running the
  same green/orange/red color decision back-to-back. Removed the
  dead duplicate. No visible change other than slightly less work
  per refresh.
- **Hypervisor delete and "Refresh Status" tied to the visible
  screen** — both were running in the older Fragment-wide scope, so
  a slow delete or a slow status probe could still try to show a
  Toast against a screen you had already navigated away from.
  Both are now scoped to the visible view and capture the screen
  context up-front, so navigating away mid-operation is safe.
- **Bulk-edit identity dropdown no longer resets while you are
  using it** — the identity picker in the bulk-edit dialog was
  being rebuilt every time the identity table emitted a change
  (which could happen mid-edit if a background sync ran). It is
  now populated exactly once when the dialog opens.

## Wave 53 — Keyboard & tabs deep-dive audit

- **Friendlier tab-limit message** — opening a new SSH tab when you
  have already hit your max-tabs setting no longer flashes a generic
  "Failed to create terminal tab" before closing the screen. You now
  see "Tab limit reached (N tabs open). Close a tab before opening a
  new one." and the freshly-opened SSH connection is torn down
  cleanly so it does not linger in the background.
- **FN mode is robust against layout changes** — if the keyboard
  layout is updated while you have the FN row showing, the FN-swap
  snapshot is now cleared so the next FN toggle paints the new layout
  rather than the stale one.
- **Legacy `CustomKeyboardView` removed** — the multi-row keyboard
  view has been the only one wired up for several releases; the
  unused single-row sibling and its layout XML are gone. No visible
  change.
- **`KeyboardLayoutManager` slimmed down** — the unused single-row
  CSV save/load methods were removed; only the multi-row JSON
  helpers everything actually uses remain. No visible change.
- No regressions to keyboard input — all key sequences (arrows, F1-F12,
  HOME/END/PGUP/PGDN, FN swap) and DECCKM application-cursor mode
  continue to send the correct ESC-prefixed sequences.

## Wave 52 — Subsystems audit: orphan code removal

- **Removed the orphan `accessibility/` package** — `AccessibilityManager`,
  `HighContrastHelper`, `TalkBackHelper`, and `KeyboardNavigationHelper`
  were never invoked from any Activity, Fragment, or View. The real
  accessibility surface in TabSSH is the contentDescription / Material 3
  default screen-reader path through the layouts.
- **Removed orphan `ProxyManager`** — proxy support is genuinely wired
  through `SSHConnection.setupHttpSocksProxy()` driven by per-host
  profile fields; this parallel implementation had no callers.
- **Removed orphan `MultiplexerManager`** — tmux / screen / zellij
  auto-launch is the path through `SSHTab.buildMultiplexerCommand()` and
  the gesture mapper; this older sibling was unused.
- **Removed orphan `KeyboardHandler`, `PlatformManager`, and
  `ValidationHelper`** — zero callers anywhere in the app.
- **Dead theme parsers removed** — the iTerm scheme parser was a stub
  that always returned null; the VSCode and TerminalSexy parsers were
  unreachable. Only the JSON theme path remains, which is the one
  `ThemeManager` actually uses.
- No behaviour change visible to users — every removed file was
  unreferenced; `make check` still passes cleanly.

## Wave 51 — SFTP + Backup audit fixes

- **SFTP resume downloads no longer corrupt the file** — resuming a
  partially-downloaded file previously truncated the already-downloaded
  prefix to zero bytes and wrote only the tail of the stream after the
  resume offset, silently producing a corrupt file that looked like a
  successful "recovery". The resume path now appends after the existing
  bytes, matching how `ChannelSftp.RESUME` is supposed to work.
- **Encrypted backups are now detected as encrypted** — `isBackupEncrypted`
  used a base64 regex that never matched the raw AES-GCM binary actually
  produced by the v3 backup writer, so the UI skipped the passphrase
  prompt and then failed with a confusing JSON parser error. The check now
  reads the `TABSSH_SYNC_V2` magic header directly.
- **Backup validator no longer reports valid sections as invalid** — each
  per-section sub-result (connections / keys / preferences / themes) hard-
  coded its `isValid` flag to `false`, so any future code consuming the
  per-section flag would treat clean backups as broken.

## Wave 50 — Settings audit cleanup

- **"Show connection notifications" and "Vibrate" toggles work again** — both
  switches in Settings → General → Notifications were saved but no
  notification code path consulted them. The global vibrate switch now
  suppresses haptics across every per-profile alert mode, and connection
  notifications are gated by the matching toggle.
- **Duplicate SSH Agent Forwarding switch removed** — the same setting
  appeared in both Settings → Connection and Settings → Security. Removed
  the duplicate from Security; Connection is the canonical home.
- **Settings numeric inputs no longer crash on bad input** — Connection
  Timeout previously threw an exception on an empty or non-numeric entry;
  Keepalive, Max Tabs, Tasker Command Timeout, Audit Log Size, and Audit
  Retention had no bounds at all. All six now reject invalid input with a
  clear message and enforce sane minimum/maximum bounds.

## Wave 49 — SSH bytes-transferred counter actually counts

The per-connection `bytesTransferred` figure shown in connection stats and
session snapshots was always `0 B` regardless of how much traffic flowed
through the session. The counter is now wired into the shell stream
accessors and increments on every read/write — connection dashboards and
session persistence finally show real numbers.

## Wave 48 — Settings that were silently broken now work

Four settings that previously appeared to do nothing have been wired up to
their actual feature paths:

- **Confirm on exit** — Settings → General → "Confirm on exit" now actually
  shows an "Exit TabSSH?" prompt when you press Back
- **SSH Agent Forwarding (default)** — the Security setting now controls the
  default for new connections; previously the toggle saved but the SSH
  connection layer read a different key, so the switch had no effect
- **Debug Log Level** — the verbosity dropdown in Settings → Logging now
  filters log output in real time; Verbose / Debug / Info / Warning / Error
- **Max host log size** — the per-host log SeekBar in Settings → Logging now
  governs the rotation cap (1–10 MB); previously host logs kept the same
  hard-coded 1 MB cap regardless of the slider position

## Wave 47 — Reliability fixes

- **Tapping wrapped URLs now works** — links that span multiple terminal rows open correctly instead of being cut off
- **Tab switching no longer freezes the terminal** — switching tabs then back no longer left the terminal unresponsive
- **Swipe lock during text selection** — horizontal swipes can no longer accidentally switch tabs while selecting text
- **Removed SEL key** — text selection is now entered via "Select Text…" in the clipboard menu (📋)
- **`Ctrl+` shortcut notation** — the `+` separator is now accepted alongside `Ctrl-`, `C-`, and `^` in custom key bindings

## Wave 46 — Your data is safe across updates

Connections, SSH keys, themes, snippets, and all other saved data now survive
app updates. A long-standing bug caused the app to wipe and recreate its
database whenever it detected a version mismatch — instead of migrating your
data forward. That bug is fixed; going forward updates will never erase your
configuration.

Several lower-level data safety improvements also landed in this wave:

- **WAL journal mode** — the database now uses SQLite's Write-Ahead Logging;
  writes are atomic even if the device loses power mid-write, eliminating the
  class of corruption that the old journal mode could produce
- **SSH key delete is now atomic** — the deletion order was reversed so a
  crash or power-off during delete leaves the key fully intact rather than
  partially deleted; credentials are flushed synchronously to disk before the
  database record is removed

## Wave 45 — Paste into vim actually works

Pasting text into the terminal now behaves correctly in vim, nano, and any
other editor. Previously, pasting a multi-line block could trigger
auto-indent, run commands, or corrupt the text. That's fixed.

- **Bracketed paste** — when vim (or any program) tells the terminal it
  wants paste mode, the app now wraps your clipboard content in the correct
  markers so the editor receives it as a plain insert, not a stream of
  individual keystrokes
- **Large paste support** — configs, scripts, SQL dumps, and other big
  blocks of text are sent in chunks so the connection stays responsive while
  pasting; line endings are automatically normalised

## Wave 44 — Hyperlinks light up and open with a tap

URLs and hyperlinks in the terminal are now visually underlined in blue, and
links produced by OSC 8-aware tools (such as modern versions of `ls`, `git`,
and `grep`) open the correct URL when you long-press the anchor text — no more
guessing from the surrounding characters.

- **Underlined links** — every URL (detected by pattern or embedded as an OSC 8
  hyperlink) gets a thin blue underline as it renders, so links are visible at a
  glance without having to tap first
- **OSC 8 support** — programs that emit `\e]8;;url\e\\anchor\e]8;;\e\\`
  sequences now have their URLs recognised and opened exactly; this covers
  `ls --hyperlink`, `git log` with hyperlinks enabled, `man`, `delta`, and
  anything else that follows the OSC 8 spec
- **Accurate long-press** — when you long-press an OSC 8 anchor the embedded URL
  is returned directly; regex detection is only used as a fallback when no OSC 8
  URL is present

---

## Wave 43 — URL detection improvements

Long-pressing a URL in the terminal is now more accurate:

- **More schemes** — `ftp://`, `ssh://`, `git://`, `svn://`, and `file://` are
  now recognised alongside the existing `http://` and `https://`
- **No more trailing punctuation** — a URL at the end of a sentence like
  `https://example.com.` no longer includes the period in the copied link
- **Word-wrapped URLs** — a URL that splits across two display rows is only
  joined when the row actually word-wrapped; the fix prevents an unrelated
  line from being accidentally glued onto the URL

---

## Wave 42 — Copy text now handles word-wrapped lines correctly

When you copy the screen content from a terminal session, long lines that
wrapped visually at the edge of the screen are now joined back into a single
logical line instead of being split at the column boundary. This affects both
SSH sessions and VM console sessions (Proxmox serial / Libvirt console).

---

## Wave 41 — VM power actions now always work

The Stop button used to silently do nothing on Proxmox, OCI, and Libvirt / KVM
hosts when the virtual machine's guest agent was absent or unresponsive. All
three have been switched to hard power-off, which cuts power immediately and
never gets stuck waiting for the guest OS:

- **Proxmox** — Stop now uses the hard stop API instead of the graceful ACPI
  shutdown that requires the QEMU guest agent to be installed
- **OCI** — Stop now uses the hard `STOP` action instead of `SOFTSTOP`; a
  secondary fix corrects a TLS pin storage bug that caused every Stop/Start/
  Restart action to show a certificate confirmation dialog (which timed out
  and rejected the request if not answered within 30 s)
- **Libvirt / KVM** — Stop now calls `virsh destroy` (immediate power-off)
  instead of `virsh shutdown` (graceful, guest-agent-dependent)

---

## Wave 40 — Identities tab clean-up

The Identities screen has been reorganised and polished:

- **New order** — Host Identities → SSH Keys → VM Credentials → VNC Identities;
  the most-used sections are now at the top
- **VM Credentials** — the former "Virtualization Identities" section is renamed
  and trimmed to Proxmox / VMware / XCP-ng only; OCI API-key credentials live
  exclusively in the OCI wizard now
- **VNC identity dialog** — the add/edit form now uses the same Material text
  fields as the rest of the app, with a proper password visibility toggle

---

## Wave 39 — Stability fix: database crash on first launch after update

Fixes a crash on startup ("Room cannot verify the data integrity") that
affected devices which had installed an intermediate build. The internal
database version has been bumped to force a clean rebuild of all tables.
**Note: all saved connections and identities will be wiped on this update.**
Back up your connections via Settings → Export before updating if you haven't
already.

---

## Wave 38 — Connection editor and bulk-edit polish

Several UX fixes across the Connections screen:

- **Identity picker works again** — tapping an identity in Connections → Edit
  host → Identity now correctly shows the selected name in the field.
- **Bulk edit all hosts in a group** — long-press any group header and choose
  "Bulk edit all hosts in this group" to open the full bulk-edit sheet
  pre-filled for that group.
- **Create Paste sheet fits your screen** — the debug-log / paste upload form
  now opens fully expanded and scrolls on small phones instead of being
  clipped off the bottom.

---

## Wave 37 — Mosh auto mode

Mosh now has three modes: **Off**, **Auto** (the new default), and **On**.

In **Auto** mode TabSSH silently tries to start `mosh-server` when you
connect. If it's installed you get Mosh — roaming, UDP, the works. If
it's not installed you get a normal SSH session with no error and no
fuss. You only need to set it to **Off** if you never want Mosh, or
**On** if you want a warning when Mosh isn't available.

This also fixes a long-standing issue where Mosh connections missed the
login banner (`Last login:`, MOTD). The old code briefly opened an SSH
shell to show that output, then wiped it when mosh-client took over.
The new code skips the SSH shell entirely and lets `mosh-server`'s own
login shell print the banner — which is what you actually see.

---

## Wave 36 — Export private key

You can now export any of your SSH private keys directly from the app.
Long-press a key → "⬇️ Export private key…", optionally enter a
passphrase, and save the file wherever you like. The exported file is in
standard OpenSSH PEM format — compatible with `ssh`, `ssh-keygen`, and
any other SSH client. Without a passphrase the key is unencrypted;
with one it's protected using AES-256-CBC.

---

## Wave 35 — Install SSH key on server

You can now push your public key to a server directly from the app — no
more manual copying and pasting. Long-press any SSH key → "⬆️ Install on
server…", pick one of your saved connections, and the app connects and
adds your key to `~/.ssh/authorized_keys` automatically. If the key is
already there it skips the duplicate. The `~/.ssh` directory and
permissions are created correctly if they don't exist yet.

---

## Wave 34 — SSH key import fixed

Importing an Ed25519 private key no longer shows garbled binary as the
key name. The parser was reading the wrong bytes as the comment field —
the same bug also meant the stored private key was unusable for auth.
Both are now fixed. Keys already in your library display correctly too.

---

## Wave 33 — Sessions survive backgrounding

TabSSH now saves your open terminal sessions automatically. When you
switch to another app or lock your screen, the current scrollback and
tab state are saved. If you come back within 24 hours, your tabs are
restored exactly where you left them — no reconnecting needed.

Security policies also run on background: auto-lock triggers if you
have it enabled, and the clipboard clears on schedule if you have
auto-clear configured.

---

## Wave 32 — Audit logging + connection groups

**Audit log** — When you enable audit logging in Settings → Logging →
Audit, the app now actually records what you'd expect: session connect
and disconnect, authentication results, SFTP file operations, and port
forward open/close events. The audit viewer was already there — the
recording was just never wired in.

**Connection groups** — Connections assigned to a group now appear
grouped in the Connections tab list instead of rendering as a flat list.

---

## Wave 31 — File handle and socket leak fixes

More under-the-hood reliability improvements:

- **SSH key import** no longer leaks a file descriptor per import — the
  stream was not being properly closed after reading
- **Telnet connect** no longer leaks a socket file descriptor on
  connection failures (timeout, refused, unreachable)
- **Session recording** start and stop are now leak-proof — a storage
  failure during recording init or shutdown can no longer leave a file
  handle open until the app exits

---

## Wave 30 — Deep reliability fixes (port forwards, tab cleanup, VNC)

A second round of under-the-hood fixes targeting resource leaks and crashes
that were only observable in edge cases:

- **Port forwards** are now properly torn down when you disconnect — a race
  meant the cleanup coroutine was cancelled before it ran, leaving tunnels
  "open" in JSch until the session fully dropped
- **Tab close** now fully cleans up background jobs and the terminal bridge —
  previously only the SSH session was torn down, leaving a memory leak per
  closed tab
- **VNC connect failure** no longer leaks a file descriptor — the socket is
  now always closed if the connection attempt fails
- **Keyboard shortcuts** (`Ctrl+Tab` / `Ctrl+Shift+Tab`) no longer crash if
  all tabs have been closed

---

## Wave 29 — Long press restored to terminal menu

Long pressing the terminal now opens the action menu again (copy/paste,
send text, font size, etc.) as originally designed. A regression introduced
when rewiring URL detection had quietly swapped it for a text-selection
overlay instead. Text selection is still available via the **SEL** key in
the keyboard bar.

---

## Wave 28 — Reliability: SSH session leaks, metrics, sync race

Under-the-hood fixes that improve stability, especially when running cluster
commands or viewing live performance metrics:

- **Cluster commands** now always clean up their SSH connection and background
  job — previously a connect failure could leave a "ghost" session open in the
  background
- **Performance monitor** SSH connection no longer leaks per reconnect attempt
- **Sync** — a rare race condition that could crash the sync logger on
  multi-core devices is resolved
- **Metrics collection** — fixed a crash reading network stats on devices with
  non-standard `/proc/net/dev` formatting

---

## Wave 27 — Bug batch: key export, proxy, IPv6, scroll render

- **SSH key export on older devices** — Ed25519 keys now export correctly on
  devices that fall back to ECDSA P-256 (API < 33 without full BouncyCastle
  JCE support)
- **Proxy bypass hosts** now survive a backup/restore or sync round-trip without
  splitting into extra entries
- **Port forwarding** with IPv6 bind addresses (`[::1]:port`) now works for
  DynamicForward, LocalForward, and RemoteForward
- **Terminal rendering** — fixed a column-position glitch for characters that
  follow a wide (CJK/emoji) glyph in the same row

---

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
