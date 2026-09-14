# CHANGELOG truth audit — Unreleased section (pre-v1)

Scope: every entry under `## [Unreleased]` in CHANGELOG.md (L8-179 as of this run).
Total entries: 150 (Added 21, Changed 11, Security 8, Fixed 98, second Changed 2, second Added 10).
Verdicts: **147 TRUE · 1 FALSE · 2 MISLEADING · 0 UNVERIFIABLE**, plus 4 undocumented
user-visible changes (and 1 minor perf change).

Note: the two newest commits (3ea9c71, 205d41e) landed mid-audit. They inserted 7 new
Fixed entries (L62-68), reworded the dev-keystore Security entry, removed a stale
WHEEL_STEP_LINES entry, and implemented recording-pause — all previously-flagged items
were re-verdicted against TODAY's changelog text and TODAY's code. Line numbers below
are current.

## FALSE claims (these are bugs)

1. **CHANGELOG.md:21 — "On tablets the navigation drawer stays on screen as a permanent
   sidebar"** — FALSE. No docking code exists anywhere. The scaffold layout
   `app/src/main/res/layout/activity_tabssh_scaffold.xml` is a plain overlay
   `DrawerLayout` + `NavigationView` with no `-sw720dp`/`-sw600dp` layout variant; there
   is no `LOCK_MODE_LOCKED_OPEN` call in any activity. The only tablet-related artifact
   is an aspirational comment in `app/src/main/res/values-sw720dp/dimens.xml:4-12` that
   describes the intent but changes nothing about drawer behavior. On a tablet the drawer
   behaves exactly as on a phone: hidden until the hamburger is tapped, overlaying
   content when open. Fix: either implement a docked (permanently visible) drawer for
   `sw720dp` — e.g. a side-by-side layout variant of `activity_tabssh_scaffold.xml` with
   the `NavigationView` outside the `DrawerLayout`, or `LOCK_MODE_LOCKED_OPEN` +
   `scrimColor = 0` gated on a tablet resource qualifier — or delete the sentence from
   the entry.

## Misleading claims

1. **CHANGELOG.md:87 — SAF picker guidance described as a "one-time hint"** — the hint
   exists but is not one-time. `SFTPActivity.kt:1975` shows the toast inside
   `showChooseLocalStorageDialog()` (`:1951`) on **every** Browse tap; there is no
   "shown once" preference or flag anywhere in the flow (verified `:1960-1984`). The fix
   itself (hint before launching the SAF picker) is real; only the "one-time" wording is
   wrong.

2. **CHANGELOG.md:177 — port-forward fallback "or a remote socat/nc bridge when the
   server denies streamlocal forwarding"** — no socat/nc bridge exists; `grep -rn socat`
   over `app/src/main` returns zero matches. The actual fallback tier is a CLI
   `dial-stdio` bridge run over SSH exec (`<cli> system dial-stdio` —
   `SocketRelay.kt:43`, `:83`, `:91`). Every other part of the entry (direct streamlocal
   first, stdio bridge second) is accurate; the "socat/nc" naming describes a mechanism
   that was never shipped.

## Undocumented user-visible changes

1. **Recursive directory upload/download (SCP upload, SFTP download)** — commit "Add
   recursive directory support to SCP upload and SFTP download";
   `SFTPManager.kt:314` `uploadDirectory()`, recursion in `uploadDirectoryContents()`
   at `:365`/`:414`/`:442`. Directory transfers previously failed or flattened; this is
   a clear user-facing feature with no CHANGELOG entry.
2. **Soft keyboard could never be re-opened after being hidden once** — commit
   3fc0d0574ba0; `TerminalView.kt:1255-1258` switched `SHOW_IMPLICIT` (which silently
   no-ops after the IME was explicitly hidden anywhere in the process) to
   `InputMethodManager.SHOW_FORCED`. A user hitting the keyboard toggle after hiding the
   IME saw nothing happen. No CHANGELOG entry.
3. **Hypervisor console TLS pin persisted across reconnects** (Proxmox / XCP-ng / Xen
   Orchestra WebSocket consoles) — commit d4d575cdd2a6 touching
   `ConsoleWebSocketClient.kt` (`onPinCaptured`), `HypervisorConsoleManager.kt`
   (persist + reuse pin across reconnects), `VncDirectConnector.kt` (`connectWss`
   threading), `ProxmoxManagerActivity.kt`, `XCPngManagerActivity.kt`. The existing
   Fixed entries at L78/L90 cover only the OCI cloud pins, not this path. Users
   previously got repeat TOFU prompts on every console reconnect.
4. **Native crash handler for the SPICE JNI stack** — commit afa00e82362b. Minor /
   diagnostic (turns hard native crashes into logged aborts), but user-observable when
   SPICE misbehaves. No entry.

Minor (perf only, judgment call): batched SAF local-browser directory listing (commit
4b7ed9cff030) speeds up the local pane noticeably on large folders; arguably below the
CHANGELOG threshold.

## TRUE claims (verified)

### Added (CHANGELOG.md:12-32) — 20 of 21 TRUE (L21 is FALSE above)

- L12 JNLP handling — `JnlpFile.kt`; `LinkHandlerActivity.kt:116-126`, `:376-498`
- L13 Hosts tab 4 sub-tabs incl. Telnet CRUD + Active —
  `TelnetHostsFragment.kt`, `TelnetHost.kt`, `ActiveHostsFragment.kt:33-34`
- L14 Panes (grid multi-session) — `PaneGroupEditDialog.kt:44` (`MAX_WINDOWS = 6`);
  `PanesTab.kt:74-115`; DB v17 pane_groups `TabSSHDatabase.kt:826`;
  `PanesGridView.kt:47`, `:134-155`; OPEN TABS listing + narrow-width collapse
  `TabTerminalActivity.kt:822-849`; sync-input routing `PanesTab.kt:81-98`
  (`_syncInputEnabled`, `applySyncInputRouting`, `setSyncInputEnabled`);
  Disconnect-All vs Keep-Running `TabTerminalActivity.kt:5925-5926`, park/reclaim
  `:2359`, `:4446`
- L15 Stats moved to drawer — `drawer_menu.xml:60-63`
- L16 Connection-stats overhaul (reset + per-source counts) —
  MIGRATION_23_24 `TabSSHDatabase.kt:1156`; `FrequentConnectionsFragment.kt:100-120`;
  `ConnectionAdapter.kt:95-97`, `:135-136`
- L17 Container engines beyond Docker — `ContainerEngine.kt`; capability-gated sub-tabs
  `ContainerTabs.kt:59-69`
- L18 Container dashboard — `ContainerDashboardFragment.kt`; `ContainerTabs.kt:28-58`
- L19 Unreachable-host error card — `ContainerTransportMessages.kt:24-54`;
  `ContainerHostManagerActivity.kt:81`, `:118`
- L20 Drawer on every screen (scaffold) — `TabSSHActivity.kt:124-145`
  (`setContentView` wraps in `activity_tabssh_scaffold.xml`), `DrawerMode` `:43-55`,
  `applyDrawerMode` `:306-311`; terminal uses TOOLBAR_ONLY
  `TabTerminalActivity.kt:162`
- L22 About dialog native-component status — `TabSSHActivity.kt:408-463`
- L23 Nerd Font count — `TabSSHActivity.kt:408-463`; `FontManager.kt:23-30`
- L24 Domain/VPS tracker — MIGRATION_15_16 `TabSSHDatabase.kt:785`;
  `RenewalUrgency.kt:21`, `:51`, `:59`; `MonitoringBootReceiver.kt:51-53`;
  `DomainTrackerActivity.kt:53-67`
- L25 Home drawer item — `TabSSHActivity.kt:333` (`nav_home`)
- L26 Frequent-connections long-press — `FrequentConnectionsFragment.kt:142`
- L27 Per-host multiplexer prefix override — MIGRATION_25_26 `TabSSHDatabase.kt:1221`
- L28 Session video recorder incl. pause-on-swipe-away —
  `SessionRecordingService.kt:55-134` (record), `:231-238` (quality pref),
  `pauseRecording()` `:135`, stray-pause guard `:166-172`, `pauseCapture()`
  `:318-326` (`mediaRecorder?.pause()`, API-24 note `:316`); page-change wiring
  `TabTerminalActivity.kt:4810-4812`; auto-stop on tab close `:4901-4913`
  (re-verified after commit 3ea9c71 implemented pause — previously misleading)
- L29 Left-edge wheel scroll zone — `TerminalView.kt:313`, `:347-352`;
  `preferences_terminal.xml:108-110`, `:143-148`; `PreferenceManager.kt:82`,
  `:331-335`
- L30 Tracker sort toolbar — `DomainTrackerActivity.kt:53-67`; `ic_toolbar_sort`
  drawable present
- L31 VPS billing cycles — `VpsMarkdownImportExport.kt:29-31`, `:135-143`;
  `RenewalUrgency.kt`
- L32 CSV import header synonyms — `DomainCsvImportExport.kt:20-52`

### Changed (CHANGELOG.md:36-47) — 11 of 11 TRUE

- L36 Palette design system — `dialog_palette.xml:12`; `item_palette_row.xml:5`
- L37 Dashboard edit UI — `dialog_dashboard_monitor_config.xml`,
  `item_dashboard_empty.xml`; pane focus border palette `colors.xml:133`, `:136`,
  `values-night/colors.xml:65`
- L38 Keys tab renamed Auth — `AuthFragment.kt:16-17`, `:57-79`
- L39 Registries sub-tab under Auth — `AuthFragment.kt:57-79`
- L40 "Connections" header label removed — `fragment_ssh_hosts.xml:19-24`
- L41 Screenshots renamed — `metadata/en-US/images/phoneScreenshots/` (files present)
- L43 Release channels — `.github/workflows/development.yml`, `beta.yml`,
  `release.yml`
- L44 Settings reorganised — `SettingsActivity.kt:302-314`
- L45 Same toolbar/drawer everywhere — `TabSSHActivity.kt:124-145` (scaffold)
- L46 Byte/duration formatting — `Format.kt:51`, `:79`, `:125`
- L47 DEBUG_LOG devel variant — `app/build.gradle:191-202` (initWith release,
  `DEBUG_LOG=true`, not debuggable)

### Security (CHANGELOG.md:51-58) — 8 of 8 TRUE

- L51 Hypervisor passwords out of DB — MIGRATION_13_14 `TabSSHDatabase.kt:535`
  (carryover staging table, column dropped); `HypervisorPasswordStore.kt:245-300`
  (`drainCarryover` with leave-row-for-retry `:278`, `:287`, `:296`); invoked
  `TabSSHApplication.kt:315`
- L52 Sync export Argon2id — `SyncEncryptor.kt:10-43` (RFC 9106), `:124`
  (64 MiB memory), `:382-393` (params in header), `:403-430` (unsupported-file
  rejection)
- L53 No fallback signing password — `app/build.gradle:154-160`, GradleException
  `:396-403`
- L54 Dev builds no longer signed with throwaway keystore; missing secret fails the
  workflow — `development.yml:96-121` hard-fails without
  `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`, comment explicitly rejects an ephemeral
  keystore (entry was reworded by commit 205d41e; re-verified TRUE against current
  text)
- L55 F-Droid native-lib excludes — `app/build.gradle:266-270`
  (`libmosh-client.so`, `libtabssh_native.so`, `libtor.so` when `isFdroidBuild`);
  documented `metadata/io.github.tabssh.yml:24-35`
- L56 Tasker integration default OFF — `preferences_tasker.xml:10-13`
  (`defaultValue="false"`)
- L57 COMMAND_RESULT broadcast output opt-in — `TaskerWorker.kt:219-232` (output only
  when include-output enabled), `:276-289` (result mirrors status otherwise);
  `PreferenceManager.kt:840` default false
- L58 Locale bundle requires connection ID, exception-guarded —
  `LocalePlugin.kt:74-94` (mandatory ID `:79-83`, `runCatching`);
  `LocaleFireReceiver.kt:27-31` (`isBundleValid` gate)

### Fixed (CHANGELOG.md:62-160) — 97 of 98 TRUE (L87 misleading wording above; fix itself real)

New entries from commits 3ea9c71/205d41e:

- L62 Sync no longer applies strictly-older rows — `SyncDataApplier.kt:80-92`
  (`remoteIsStale` = local newer than remote), applied per table (`:231` keys,
  `:414` VNC hosts, `:521-572` container tables)
- L63 Hypervisor accounts merged by natural identity — `SyncDataApplier.kt:382-399`
  (`associateBy(TombstoneRecorder.naturalKey)`, insert with `copy(id = 0L)`),
  id remap `:151`, `:392`, `:396`; secret aliases remapped `:596`;
  `TombstoneRecorder.kt:87` (`naturalKey`)
- L64 Keep-remote now upserts + clears tombstone — `ConflictResolver.kt:89-92`
  (`adoptRemote`: write + `syncTombstoneDao().clear`), KDoc `:78-88` documents the
  zero-row `@Update` bug; call sites `:108-114`, `:147-153`, `:186-192`, `:225-231`
- L65 Failed sync download reported, upload suppressed — `SAFSyncManager.kt:298-317`;
  sealed `SyncDownload` `:497` with `Failed`; KDoc: Failed must never be followed by
  an upload
- L66 Compose modifiedAt vs status-cache updatedAt — `ComposeStack.kt:50` (updatedAt),
  `:53-55` (modifiedAt, "Distinct from [updatedAt]"); suppression keys on modifiedAt
  `SyncDataApplier.kt:548`, `:560`, `:572`
- L67 SFTP concurrent-transfer cap — `SFTPManager.kt:65`
  (`transferSlots = Semaphore(MAX_CONCURRENT_TRANSFERS)`), `:77` (`= 3`)
- L68 File-list selection pruned on refresh — `FileAdapter.kt:42`/`:48` (selection
  sets), `:69-85` (`pruneSelection` retains only present keys; size/mtime changes
  keep selection)

Pre-existing entries:

- L69 Restored session rows/cols — `SessionPersistenceManager.kt:466-471`
  (`resize(cols, rows)` before replay)
- L70 UTF-8 leftover discarded on disconnect — `TermuxBridge.kt:1534-1539`
- L71 Terminal writes serialized — `TermuxBridge.kt:385-403` (`emulatorLock`)
- L72 Transfer cancel treated as cancel — `SFTPManager.kt:753-760`
  (`TransferResult.Cancelled`); `RemoteFileEditorActivity.kt:286`, `:345`;
  `RemoteFileOpener.kt:132`, `:256`
- L73 OSC 8 BEL terminator — `TermuxBridge.kt:136-150`
- L74 DB purge + VACUUM scheduling — `SSHConnectionService.kt:147-151`, `:925`,
  `:958-961`; `TabSSHDatabase.kt:1289`
- L75 Host registry rebuild in one transaction — `ConnectableHostRegistry.kt:52`,
  `:186-190`
- L76 Scrollback trim under memory pressure — `TabManager.kt:642-668`
  (`trimTranscriptsToBudget`, LRU, protectTabId); `TabSSHApplication.kt:1011`
  (`onTrimMemory`)
- L77 Emulator feed single-threaded — `TermuxBridge.kt:385-403`, `:598`, `:1405`,
  `:1444`
- L78 OCI identity pin persisted even on failure — `ConnectableHostRegistry.kt:167-172`
  (persist in `finally`), `:202-210`
- L79 Panes wizard keyboard handling — `PaneGroupEditDialog.kt:100`, `:126-133`
  (close keyboard before step advance), `:293-351` (wait for window focus)
- L80 Tab restore is_active — `SessionPersistenceManager.kt:305-329`
- L81 VNC Security Type pinning — `RfbClient.kt:554-570` (non-auto pin),
  `:734-743` (RFB 3.3 pinned error), `:814` (preference order)
- L82 VNC bracket keys synthetic Shift — `VncView.kt:579-592`, `:600`
  (`KEYCODE_NUMPAD_ENTER`)
- L83 SPICE synthetic shift release-exact — `SpiceView.kt:574-597`
  (`syntheticShiftKeys`), `:695`
- L84 Lock keys → keysyms — `VncView.kt:636-641`; `RfbConstants.kt:306-307`
- L85 SFTP quick-picks removed API 30+ — `SFTPActivity.kt:105`, `:1932-1951`;
  `strings.xml:2751`
- L86 SFTP Up button vs disconnect — `SFTPActivity.kt:86`
  (`NavigationAffordance.UP`); `TabSSHActivity.kt:218-220`; disconnect only via
  `:345`, `:2080`
- L87 SAF picker hint — hint exists `SFTPActivity.kt:1964-1976` +
  `strings.xml:2752`; "one-time" wording misleading (see above)
- L88 Tracker toolbar icon tint — `ic_toolbar_import.xml:6` (`?attr/colorOnPrimary`);
  export/sort drawables present
- L89 Built-in Tor route dedicated type — `NetworkRoute.kt:183`
  (`TOR("Tor (built-in)")`), `:130`; `NetworkRouteAdapter.kt:88`; flag-based
  fallback retained
- L90 OCI pin persisted after instance action — `OciManagerActivity.kt:346-361`
- L91 Cloud instance as container-host connection — `ConnectableHost.kt:22`,
  `:79-80` (`"cloud:{accountId}:{instanceId}"` id format);
  registry refresh `ConnectableHostRegistry.kt:157-194`
- L92 Containers tab empty until sub-tab switch — `ContainerHostsFragment.kt:191`
  (`requestLayout()` on every emission)
- L93 Stack detail screen — `ui/activities/StackDetailActivity.kt` (exists, member
  containers + actions)
- L94 Update-now confirm once + live progress — `UpdateApplyDialog.kt:24-98`
  (`UpdateApplier.ApplyEvent` stream)
- L95 VPS renewal date rollover UTC — `VpsMarkdownImportExport.kt:135-143`;
  `RenewalUrgency.kt:51-59`
- L96 New Tab picker cross-source, cache-first — `TabTerminalActivity.kt:5531-5560`
  (`connectableHostDao`, background `refreshAll`)
- L97 Tor status live + Test button — `TorManager.kt:31-62` (`StateFlow<TorStatus>`,
  `Bootstrapping(percent)`); `NetworkRouteEditActivity.kt:112`, `:163`
- L98 Pane tap focuses without keyboard toggle — `TerminalView.kt:2906-2924`
  (`onPaneTapped` returns alreadyFocused)
- L99 Main-thread blocking removed — `MultiHostDashboardActivity.kt:414-425`;
  `SettingsActivity.kt:1194-1201` (`Dispatchers.IO`)
- L100 Connection edit off main thread — `ConnectionEditActivity.kt:181`
- L101 VNC focus after reconnect — `TabTerminalActivity.kt:3885-3893`, `:3042`,
  `:6091`
- L102 Reattach prompt checks liveness — `ConnectionLauncher.kt:40-51`
  (`isConnected()` + pooledAlive)
- L103 Disconnect bounded by timeout — `SSHConnection.kt:2187`, `:2205`
  (`withTimeout(5_000)`)
- L104 Pastebin server configurable — `PasteProvider.kt:146`
- L105 PRE-key reconnect re-reads profile — `TabTerminalActivity.kt:3588-3594`,
  `:3628`, `:3664`
- L106 Pickers list cloud/container sources — `TabTerminalActivity.kt:5531-5560`
  (connectable-host registry covers all source types)
- L107 VM manager stable IDs — `VMwareManagerActivity.kt:400`;
  `XCPngManagerActivity.kt:474-495`; `LibvirtManagerActivity.kt:534`
- L108 openssh-key-v1 bcrypt_pbkdf import — `SSHKeyParser.kt:676`, `:706-714`
  (`com.jcraft.jsch.jbcrypt.BCrypt().pbkdf`)
- L109 Mosh swipe scroll arrows — `TerminalView.kt:2770-2810` (mouse-tracking branch
  `:2735`)
- L110 Mosh resize SIGWINCH — `TermuxBridge.kt:1428-1439`, `:1787` (signal 28 on
  every resize)
- L111 Double connection-count increment — `SSHConnectionService.kt:672-680`
- L112 Sync counters local-only — `MergeEngine.kt:255-267` (connectionCount),
  `:438-488` (usageCount); `SyncDataApplier.kt:228-259`
- L113 Bracketed-paste mode split across reads — `TermuxBridge.kt:102-106`, `:583`
  (`pendingEscTail`)
- L114 Paste single write with markers — `TermuxBridge.kt:1183-1204`;
  `TerminalEmulator.kt:181-199`
- L115 Tor preset chip visibility — `NetworkRouteEditActivity.kt:299-304`
  (only PROXY_SOCKS5/TOR + `TorNativeClient.isAvailable`)
- L116 Deferred wrap (VT100 pending-wrap) — `TerminalBuffer.kt:68` (reset sites
  through `:641`)
- L117 Tor binary fetch verified in workflows — `development.yml:173`, verify gate
  `:182-196` (exit 1 if any ABI missing libtor.so); `beta.yml:154`, `:166`;
  `release.yml:176`, `:189`
- L118 Long link rejoin across soft-wrap — `TerminalView.kt:2152-2185`
  (wrap-aware reconstruction; full-width rows rejoined)
- L119 VNC cursor pseudo-encoding 4-byte header — `RfbClient.kt:1401`, `:1744-1756`
  (CursorWithAlpha leading U32)
- L120 App-lock PIN once per app start (incl. cold launch into a deep-linked
  activity) — `TabSSHApplication.kt:776-808`, `:819-821`
- L121 Docker screens cancel previous load — `ContainerPageFragment.kt:45-54`;
  `SingleFlightLoader.kt:24-27`
- L122 VNC resize 2-bytes-short + stale update region —
  `RfbClient.kt:1103-1108` (SetDesktopSize length fix),
  `rearmContinuousUpdates` `:1147-1154`, `:1336`, `:1374`, `:1806`
- L123 i18n dispatch by row index / hardcoded strings —
  `SettingsActivity.kt:302-314`; `SshHostsFragment.kt:379-385`;
  `SFTPActivity.kt:1275`
- L124 Light-theme app bars — `themes.xml:114-123`; `ic_settings.xml:6`
- L125 PIN screen Material styling — `PinLockActivity.kt:166-213`
- L126 Scrollback setting honored, 50,000 cap — `PreferenceManager.kt:77-78`,
  `:313-316`
- L127 SFTP auto-opens a session — `SFTPActivity.kt:243-268`, `:358-381`
- L128 TigerVNC anon-TLS handshake — `RfbClient.kt:542-560` (anon-DH check +
  fallback log `:560`), `:843-850` (anon suites enabled for TLS_NONE)
- L129 Black console tab (adapter counted only SSH tabs) —
  `TabTerminalActivity.kt:688-702`; `RfbClient.kt:430`, `:450`
- L130 RemoteCommand skips Mosh — `TabTerminalActivity.kt:2666-2670`
- L131 Console paste newlines → Enter — `VncConsoleChannel.kt:367-382`;
  `TerminalPagerAdapter.kt:594-607`
- L132 Docker session scaling — `ContainerSessionManager.kt:49` (LRU cap 16),
  `:52` (10-min idle sweep), `:74` (per-host Mutex), `:291-331`
- L133 OkHttp connection non-reuse over SSH relay — `EngineApiTransport.kt:62-73`
  (`ConnectionPool(0, 1s)` + `Connection: close`), `:532`
- L134 MinAPIVersion honored — `DockerApiParsers.kt:73`, `:111`;
  `ContainerModels.kt:108`; `EngineApiTransport.kt:111`
- L135 Relay binds IPv4 loopback explicitly — `SocketRelay.kt:408`
- L136 Exec timeout returns buffered output — `SshExecRunner.kt:86-102`
- L137 Monitoring-only connections kept alive correctly —
  `ContainerSessionManager.kt:151-158`; `SSHSessionManager.kt:240-263`, `:407`
- L138 cli_exec transport re-detected — `TransportCapabilityDetector.kt:30-31`,
  `:63-66` (cli_exec never pinned)
- L139 Multiplexer names POSIX-escaped — `SSHTab.kt:1348-1358`
  (single-quote escape helper), `:1369` (tmux attach uses `$safe`)
- L140 virsh success anchored to `error:` line prefix —
  `LibvirtApiClient.kt:271-279` (`lineSequence().any { startsWith("error:") }`,
  KDoc documents the name-containing-"failed" bug)
- L141 Proxmox snapshot path percent-encoded — `ProxmoxApiClient.kt:70-77`
  (`encodePathSegment`), rollback `:447-449`, delete `:466-468`
- L142 Proxmox snapshot-delete error body unwrapped —
  `ProxmoxApiClient.kt:885-886` (`proxmoxErrorDetail`), helper `:901`
- L143 VMware Tools hint only on tools faults — `VMwareApiClient.kt:659-667`
  (`toolsFault` gate)
- L144 ASK-mode picker dialog lifecycle — `TabTerminalActivity.kt:3156`,
  `:3165-3188` (dialog tracked, dismissed on tab switch/destroy, `isShowing`
  guard); request kept for re-prompt `SSHTab.kt:255`, `:1284-1285`
  (`dismissMultiplexerAsk` only on explicit dismissal)
- L145 Snapshot name validation — `ProxmoxManagerActivity.kt:812-821` (config-ID
  regex up front); `VMwareManagerActivity.kt:790-791` (trim + reject empty)
- L146 Ephemeral sessions skip the background saver —
  `SessionPersistenceManager.kt:291-297` (FK-safe skip, matching tab manager path)
- L147 Console tabs take keyboard input — keysym path `VncView.kt:549-550`
  (`androidKeyToKeysym`), `:129`; scancode path `SpiceView.kt:31`, `:105`, `:130`
- L148 Grid recompute funnel + top clip — `TerminalView.kt` all inputs funnel
  through `updateGridSize()` (`:766`, `:1153`, `:1174`, `:1187`, `:1319`)
- L149 Blank band removed, grid bottom-aligned — `TerminalView.kt:1332-1356`
  (visual slack computation, last row flush)
- L150 Console close policy (orderly vs abrupt) — `RfbClient.kt:208` (EOF at
  message boundary = orderly), `:1232` (orderly EOF vs mid-message drop)
- L151 Console long-press menu — `VncView.kt:56` (extended hold), `:159`, `:184`
  (`onLongPress`; plain long-press still right-clicks)
- L153 Mosh alt-screen swipe gated on DECCKM — `TerminalView.kt:2770-2810`
  (gate `:2786` swallows `:2789`; SS3 `ESC O A/B` `:2800-2801`)
- L154 Gesture-nav exclusion rects — `TerminalView.kt:3578-3589`
  (`updateSystemGestureExclusion`: scrollbar strip + left wheel zone)
- L155 Wheel gearing removed, 1:1 scrolling — `TerminalView.kt:2803`;
  `WHEEL_STEP_LINES` absent repo-wide (the stale contradicting Changed entry was
  removed from the CHANGELOG by commit 205d41e)
- L156 invalidate() after grid resize — `TerminalView.kt:1391`
- L157 Infra sub-tab label "Docker Hosts" — `strings.xml:473`
  (`infra_tab_container_hosts`)
- L158 Tracker empty import/export warning toast — `strings.xml:2345`, `:2347`,
  `:2384`, `:2386`; `DomainTrackerActivity.kt:283`
- L159 Editor validation focuses erroring field —
  `PortForwardEditActivity.kt:371-399` (`requestFocus` on every branch)
- L160 Network-route delete records tombstone — `PortForwardingActivity.kt:188`
  (`TombstoneRecorder.NETWORK_ROUTE`)

### Second Changed (CHANGELOG.md:164-165) — 2 of 2 TRUE

- L164 Docker UI rework (tab order, action strip, bottom sheets, inline
  error + Retry) — `ContainerTabs.kt:28-69`; `ContainerHostManagerActivity.kt`
- L165 Persistent terminal scrollbar — `TerminalView.kt:3604-3638` (track alpha
  0.12, thumb 0.40/0.70, no fade timers)

### Second Added (CHANGELOG.md:169-178) — 10 of 10 TRUE (L177 socat wording misleading, see above)

- L169 Starter snippets seeded once — seed-once guard verified in snippet store
- L170 Per-host container update settings (max 2 concurrent) —
  `ContainerUpdateCheckWorker.kt:82`, `:84-85`, `:184`
- L171 Custom SSH endpoint Docker hosts (Keystore password, optional name, excluded
  from Active Sessions) — container-host editor + `ConnectableHostRegistry`
- L172 Active Sessions VNC/console chips — Active Sessions strip adapters
- L173 VM snapshots on all hypervisors — `ProxmoxApiClient` /
  `XCPngApiClient` / `LibvirtApiClient` / `VMwareApiClient` snapshot ops (see
  L141-145 citations)
- L174 ASK multiplexer picker — `SSHTab.kt:255`, `:1284-1285`;
  `TabTerminalActivity.kt:3156-3188`
- L175 Tasker/Locale plugin — `TaskerWorker.kt`; `LocalePlugin.kt:74-94`;
  `LocaleFireReceiver.kt:27-31` (see S56-S58)
- L176 VMware guest shutdown/restart — `VMwareApiClient.kt:612` (ShutdownGuest),
  `:634` (RebootGuest)
- L177 Port forwarding for container hosts (streamlocal first, stdio bridge
  fallback) — `SocketRelay.kt:43`, `:83`, `:91` (`dial-stdio` tier); "socat/nc"
  wording misleading (see above)
- L178 Container image update checks (12h worker, digest compare, auto-recreate
  with rollback, open-sessions-only, master toggle) —
  `ContainerUpdateCheckWorker.kt:82-184`; `UpdateApplier.kt:21-24`, `:59-60`
