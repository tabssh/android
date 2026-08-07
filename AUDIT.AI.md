# Project Audit

Started: 2026-08-07

Scope: the three recently shipped commits (`aed92a064908` snapshots,
`9f4e2a132a26` ASK-mode multiplexer picker, `dc1e3e4cb997` Tasker/Locale
plugin), the new exported IPC surface, doc consistency, and a general
health sweep. Fixes are applied in the working tree, uncommitted, for
user review.

The previous audit's `AUDIT.AI.md` (2026-07-31) was fully resolved but
never deleted — its content is superseded by this file.

## Pass 1: Security

- [x] `automation` + `storage/preferences`: **Tasker integration defaulted to
  ON** (`PreferenceManager.isTaskerEnabled` default `true`,
  `preferences_tasker.xml` `defaultValue="true"`) while the allowlist defaults
  to empty (= all connections) and require-unlock defaults to off. With
  `LocaleFireReceiver` now exported without a permission, a fresh install
  granted every app on the device the ability to drive SSH sessions with no
  user action — the defense the manifest comment and receiver kdoc describe
  did not exist by default. — FIXED: default flipped to `false` in both
  places. Existing installs keep their persisted value.
- [x] `automation/LocalePlugin.kt`: **`isBundleValid` accepted a bundle
  identified by connection *name* alone.** `TaskerWorker.resolveProfile` falls
  back to name lookup, so a hostile app only had to guess a profile name
  ("prod", "home") to target it. — FIXED: a non-empty connection ID is now
  mandatory. IDs are opaque UUIDs only handed out by `LocaleEditActivity`.
- [x] `automation/LocalePlugin.kt`: bundle reads were unguarded. A host app
  controls the payload; unparcelling a class this process cannot load throws,
  and an uncaught throw in `onReceive`/`onCreate` is a crash any installed app
  could trigger. — FIXED: validation wrapped, defaults to reject.
- [ ] `automation/TaskerWorker.kt:269-276`: **`broadcastCommandResult` puts
  `termuxBridge.getScreenContent()` into an implicit, unprotected
  `sendBroadcast`.** Every app on the device can register for
  `io.github.tabssh.event.COMMAND_RESULT` and read terminal output —
  credentials, key material, whatever is on screen. Chained with the fire
  receiver this is exfiltration, not just leakage. NOT FIXED: the `event.*`
  broadcasts are a documented public contract that existing Tasker tasks
  depend on, so restricting them (custom permission, or dropping the screen
  content in favour of a result the host must pull) is a user-decision.

## Pass 2: Code Quality

- [ ] whole tree: raw control bytes (`0x1b` and friends) are embedded
  literally in string literals across `terminal/TermuxBridge.kt`,
  `ui/keyboard/MultiRowKeyboardView.kt`,
  `hypervisor/vnc/console/VncConsoleChannel.kt`, and
  `automation/TaskerWorker.kt`. They are correct today but invisible in
  diffs and review, and a formatter or `sed` pass can silently eat them.
  NOT FIXED: converting to `\u001b` escapes is a ~50-site mechanical change
  across terminal-critical code, outside this audit's working set.
- [ ] `ui/activities/{Proxmox,VMware,Libvirt}ManagerActivity.kt`: snapshot
  `AlertDialog`s are shown without retaining a reference and no `onDestroy`
  dismisses them — rotating with one open leaks the window. NOT FIXED: this
  is the pre-existing dialog pattern throughout all three activities, not
  specific to the audited commits; fixing it properly is a per-activity
  pattern change.
- [ ] `ui/activities/TabTerminalActivity.kt`, `ui/tabs/SSHTab.kt`: redundant
  fully-qualified names (`io.github.tabssh.ui.tabs.SSHTab`,
  `kotlinx.coroutines.flow.StateFlow`) where the file already imports the
  symbol. NOT FIXED: cosmetic, and the pattern predates these commits.

## Pass 3: Logic and Correctness

- [x] `hypervisor/libvirt/LibvirtApiClient.kt`: virsh success/failure was
  `output.contains("error:") || output.contains("failed")` at nine sites, but
  virsh echoes the object's own name back on success — a domain or snapshot
  named `failed-boot` made every successful start/destroy/shutdown/reboot/
  reset throw, and permanently broke `listSnapshots` for that domain. —
  FIXED: new `isVirshError` anchors on virsh's `error:` line prefix.
- [x] `ui/tabs/SSHTab.kt`: `buildMultiplexerCommand`/`buildAttachCommand`
  stripped `'` from session names rather than escaping. Injection-safe, but
  silently mangled legitimate names (`dev'box` → attach fails). — FIXED:
  added `shQuote` (POSIX `'\''`), tests updated plus a metacharacter
  containment test for remote-supplied names.
- [x] `hypervisor/proxmox/ProxmoxApiClient.kt`: snapshot name interpolated
  un-encoded into the REST **path** in `rollbackSnapshot`/`deleteSnapshot`. —
  FIXED: `encodePathSegment` applied to node and snapshot name.
- [x] `ui/activities/TabTerminalActivity.kt`: ASK-picker dialog leaked on
  destroy, survived tab switches while capturing the old tab, collected
  without `repeatOnLifecycle` (could `show()` on a stopped activity), and
  destroyed the pending request on a name-validation typo. — FIXED all four.
- [x] `ui/activities/{Proxmox,VMware}ManagerActivity.kt`: no snapshot-name
  validation (Proxmox requires config-ID format; VMware did not even trim). —
  FIXED: both now validate, matching libvirt.
- [x] `hypervisor/vmware/VMwareApiClient.kt`: `shutdownVM`/`rebootGuest`
  rewrote *any* `IOException` as "needs VMware Tools". — FIXED: hint added
  only for tools/power-state faults.
- [x] `hypervisor/proxmox/ProxmoxApiClient.kt`: `apiDelete` discarded the
  error body. — FIXED: shared `proxmoxErrorDetail` used by both verbs.
- [ ] `hypervisor/vmware/VMwareApiClient.kt` `listSnapshots`:
  `RetrievePropertiesEx` result is used without checking for a continuation
  token (`ContinueRetrievePropertiesEx`). Harmless for one VM's snapshot list
  today; silently truncates if it ever paginates. NOT FIXED: needs a live
  vCenter to verify.
- [ ] `ui/tabs/SSHTab.kt:1216-1220`: zellij's decorated `list-sessions`
  fallback output includes `(EXITED - attach to resurrect)` entries, which are
  parsed as attachable and offered in the picker. NOT FIXED: needs a zellij
  host to confirm the exact output shape across versions.

## Pass 4: Documentation Completeness

- [x] `CHANGELOG.md`: no entries for the fixes above. — FIXED: Security and
  Fixed entries added under `[Unreleased]`.
- [x] `app/src/test/.../LocalePluginBlurbTest.kt`: kdoc claimed the bundle
  paths "need an Android runtime and are exercised on-device", but Robolectric
  4.14.1 is already a test dependency. — FIXED: claim corrected and
  `LocalePluginBundleTest` added under Robolectric.
- README.md / IDEA.md / TODO.AI.md checked for stale claims (Tasker
  "disabled pending redesign", SPICE libs unpublished): none found, all three
  already reflect the shipped state.

## Pass 5: Spec and Rules Compliance

- [x] `AUDIT.AI.md`: the 2026-07-31 audit file had every item resolved but
  was never deleted, contrary to the "delete when all resolved, do not empty"
  rule. — FIXED: superseded by this file.
- AI.md PART 0 non-negotiables re-checked against the audited commits:
  Keystore-only credentials, Room migration discipline, no LiveData, no
  hardcoded colors, 4-space Kotlin / 2-space XML, trailing newlines, comments
  above-line ≤180 chars, no TODO/FIXME/HACK, no AI attribution — all clean in
  the three commits.
- PART 6 "exported components are individually justified and validate every
  incoming extra": `LocaleEditActivity` and `LocaleFireReceiver` are both
  justified in the manifest and both validate — satisfied (and strengthened
  by the Pass 1 fixes).

## Pass 6: Code Flow Trace

- Proxmox CSRF: `CSRFPreventionToken` verified present on both `apiPost` and
  the new `apiDelete` — no gap.
- VMware SOAP: snapshot tree walk does recurse into `childSnapshotList`;
  `xmlEscape` escapes `&` first and is applied to every interpolated value.
- libvirt: `shQuote` is correct POSIX escaping and is applied to every
  user-controlled value in all four snapshot commands.
- ASK picker: `connectionScope` is `Dispatchers.IO` and
  `SSHConnection.executeCommand` re-wraps in `withContext(Dispatchers.IO)` —
  no main-thread client or DB calls. Locale `LocaleEditActivity` profile load
  uses suspend Room DAOs, which switch dispatchers themselves.

## Completed

- Tasker integration default flipped to opt-in; Locale bundles require a
  connection ID; bundle validation exception-guarded.
- libvirt virsh error detection anchored; multiplexer session names escaped
  rather than stripped; Proxmox snapshot path segments encoded.
- ASK-picker dialog lifecycle, Proxmox/VMware snapshot-name validation,
  VMware guest-op error text, Proxmox delete error body.
- CHANGELOG entries and `LocalePluginBundleTest` added.
