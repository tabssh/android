# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

## UI/UX issues found during SSH error-dialog research (not yet fixed)

Found while researching the SSH error classifier fix (see chat/PR for that
work). Implementation for all of these is gated on that fix's commit landing
first — do not start until then.

1. **SSH error classifier misclassifies wrapped exceptions** —
   `SSHConnection.kt`'s `buildDetailedErrorInfo()` JSchException branch only
   substring-matches `.message` and never unwraps `.cause`, so a JSch-wrapped
   `SocketTimeoutException` (message: `"Session.connect: java.net.SocketTimeoutException: Read timed out"`)
   falls into the generic `else -> "SSH Error"` bucket instead of the
   already-implemented `SocketTimeoutException -> "Connection Timeout"`
   branch. Confirmed live via screenshot (pste.us/raw/G8UbRIBf). Fix: unwrap
   `error.cause` chain and re-dispatch through the same `when`; replace the
   likely-dead `"UnknownHostException"` substring check with cause-type
   inspection.
2. **"Enable debug logging" shown unconditionally** — 3 solution lines in
   `SSHConnection.kt` (JSchException-else, SSHException, top-level else
   branches) always tell the user to enable debug logging even when it's
   already on. Fix: gate at the single display site
   (`showSSHConnectionErrorDialog()` in `TabTerminalActivity.kt`) using
   `app.preferencesManager.isDebugLoggingEnabled()`, following the existing
   correct precedent in `Logger.kt:1005-1020`.
3. **SSH error dialog action-button row overflows on narrow screens** —
   `dialog_ssh_connection_error.xml:173-210`: 4 `MaterialButton`s (Copy
   Error, Edit Connection, Retry, Close) in one non-wrapping horizontal
   `LinearLayout`. On phone-width screens the row overflows: Retry's label
   is clipped to just its filled pill background, and Close is pushed
   entirely off-dialog and unreachable. Confirmed live via screenshot
   (pste.us/raw/G8UbRIBf). Fix: wrap the row (e.g. 2x2 grid or
   `FlexboxLayout`) or stack vertically below a width threshold.
4. **VNC/SPICE/Console have no structured error classification/dialog at
   all** — unlike SSH's `SSHConnectionErrorInfo`, `VncDirectConnector.kt`,
   `SpiceLoader.kt`, `RfbClient.kt`, `ConsoleStrategy.kt`, and
   `HypervisorConsoleManager.kt` surface failures via raw-message Toasts or
   generic catch blocks with no classification. Larger scope than items 1-3
   (new feature work: shared error-info struct + per-protocol taxonomy), not
   a simple classifier fix.
5. **Raw exception text/class names leak into user-facing UI** —
   `ConnectionHistoryActivity.kt:88` and `KeyboardCustomizationActivity.kt:433`
   show raw `e.message.toString()` in toasts; `TabTerminalActivity.kt:3744`
   (VNC reconnect toast) does the same. `?: e.javaClass.simpleName` fallback
   pattern (raw class name shown to user) appears in `ConsoleStrategy.kt:80`,
   `HypervisorConsoleManager.kt:345,868`, `RfbClient.kt:421-422`,
   `MoshHandoff.kt:178`, `BulkImportParser.kt:138`. Needs friendly
   user-facing messages instead.
   **PARTIALLY DONE** — `ConnectionHistoryActivity.kt`,
   `KeyboardCustomizationActivity.kt`, and `TabTerminalActivity.kt`'s VNC
   reconnect toast now route through the new `ThrowableMapper`
   (`utils/ThrowableMapper.kt`). `RfbClient.kt`, `HypervisorConsoleManager.kt`,
   `MoshHandoff.kt`, and `BulkImportParser.kt` are still unfixed — confirmed
   none of the four have an Android `Context` in scope at the fallback site
   (`ThrowableMapper`/`ConsoleErrorClassifier` both need `context.getString()`),
   so converting them needs a small `Context`-injection refactor first
   (constructor param or a context-free string-table variant of the mapper).
   `SpiceLoader.kt` has no user-facing error strings — nothing to do there.
   `MainActivity.kt:315,479,589` (`main_load_hypervisors_failed`,
   `main_quick_connect_save_failed` x2) also still splice raw `e.message`
   and were left alone as out of the batch's named scope — `ThrowableMapper`
   is already imported in that file, so wiring these three in is a small
   follow-up once picked up.
6. **Needs further investigation (not yet confirmed as bugs)**:
   - Whether `crypto/SSHKeyParser.kt:825` and `KeyStorage.kt:958,978,1010`
     error messages actually reach user-visible UI or stay internal-only.
     **RESOLVED — yes, they do; see item 13 below.**
   - Whether stale-vs-live display values exist elsewhere in the app (host
     lists, port-forward status, cluster broadcast targets).
     **RESOLVED — host lists, cluster targets, active sessions and cloud
     accounts are already correctly live-observed; one confirmed instance
     found in port-forward status; see item 44 (and item 34 for the
     already-documented theme case).**
   - Whether `TabTerminalActivity.kt:995-1005`'s `btn_close_tab`/
     `btn_disconnect_all` menu items gracefully no-op on zero tabs/
     connections or produce confusing behavior.
     **RESOLVED — they no-op silently but stay enabled; see item 21.**

**Correction to item 1:** the `"UnknownHostException"` substring check is
**not dead**. `SSHConnection.kt:2419` sits inside the `is JSchException ->`
branch (`:2360-2480`), where JSch's wrapped message genuinely contains the
wrapped class name — it is the one working compensator for the missing
`.cause` unwrap. The rest of item 1 stands: that branch substring-matches
only four strings (`"Auth fail"`, `"Connection refused"`,
`"UnknownHostException"`, `"Algorithm"`), which is exactly why a wrapped
`SocketTimeoutException` falls through to `else`. Fix by unwrapping
`.cause` and re-dispatching, then delete the substring compensator rather
than adding more of them.

### Findings 7-41 — full-app UI/UX + code-quality audit

Same gate as items 1-6: do not start until the SSH error-classifier fix
lands. Findings verified by reading the cited code; line numbers are exact.

7. **Six more unconditional `finish()` calls destroy every open tab** —
   confirms and widens item 4. `TabTerminalActivity.kt:1726` (the "Edit
   Connection" button) has the same defect as Close and the cancel
   listener, and the pattern repeats at `:2495` (user cancels the inline
   password prompt), `:2694` (tab limit reached — ironically only
   reachable *because* other tabs are open), `:2729` (SSH returned null
   with no detailed error), `:2788`/`:2809` (telnet tab-create and connect
   failures) and `:2342` (notification-tap path). The correct guard already
   exists in the same file at `:3465,3498,3506,3524`
   (`if (!isFinishing && !isDestroyed && tabManager.getTabCount() == 0)`).
   Fix: apply that guard at all seven sites. Copy Error (`:1689-1715`) and
   Retry (`:1733-1750`) are correct and need no change.
8. **Error-dialog action buttons live inside the ScrollView** —
   `dialog_ssh_connection_error.xml` is a `ScrollView` root (line 2) with
   the button row at `:172-210`, i.e. inside the scrolling region. On a
   short viewport (landscape, split-screen, large font scale) the buttons
   scroll off the bottom and the user must scroll a wall of technical text
   to reach Retry/Close — compounding item 3's horizontal overflow. Fix
   together with item 3: move the action row into a fixed footer outside
   the scroll region (or hand the actions to `AlertDialog`'s own button
   slots, which handle stacking automatically).
9. **"Show technical details" toggle is not a real control** —
   `dialog_ssh_connection_error.xml:108-118`: a `TextView` with
   `clickable="true"` but no `?attr/selectableItemBackground` ripple, no
   button role for TalkBack (announced as static text), no
   expanded/collapsed state announcement, and 13sp text with only
   `paddingVertical="@dimen/space_sm"` — well under the 48dp touch target
   AI.md PART 7 requires. Fix: swap for a `MaterialButton` with
   `style="?attr/borderlessButtonStyle"` and an expand/collapse icon, or
   add ripple + `AccessibilityDelegate` button role + 48dp min height.
10. **Crash-report button row overflows on narrow screens** —
    `activity_crash_report.xml:58-84`: three `MaterialButton`s
    ("Paste / Issue", "Copy", "Restart"), all `wrap_content`, in a plain
    horizontal `LinearLayout` with **no** `layout_weight`. Same bug class
    as item 3, and it is the one screen a user reaches only after a crash.
    Every other 3+-button row in the app already uses weights
    (`activity_connection_edit.xml:1031`, `activity_sftp.xml:238`,
    `activity_tab_terminal.xml:176`, and 9 more). Fix: `layout_width="0dp"`
    + `layout_weight="1"` on all three.
11. **Raw exception text is spliced into user-facing strings app-wide** —
    the dominant pattern is `getString(R.string.x_failed_fmt, e.message)`
    fed to a Toast/dialog: it satisfies the i18n rule while still showing
    the user a raw JSch/JDK/HTTP exception message, which AI.md PART 2
    ("Error surfaces") forbids outright. ~240-260 sites on a direct path to
    UI across ~80 files; worst offenders `ImportExportActivity.kt`
    (`:197,258,318,347,406,518,707,914,919`),
    `ConnectionEditActivity.kt` (`:1381,1455,1489,1536,1925,2080,2134,2190,2239,2270`),
    `XCPngManagerActivity.kt` (`:256,510,699,763,862,1081,1116,1143,1274,1621`),
    plus `VMwareManagerActivity`, `LibvirtManagerActivity`,
    `AuthKeysFragment`. Item 5 is the tip of this; the real fix is one
    shared `ThrowableMapper` that maps exception types to specific
    `R.string` templates, with the raw text going only to `Logger` and a
    "Copy details" action.
12. **Blocking failures shown as Toast instead of a dialog, inconsistently**
    — AI.md PART 2 requires a Material dialog for blocking errors. The
    hypervisor manager activities do this correctly via `showError(...)`,
    but every CRUD-style edit screen uses a bare Toast for save/delete/auth
    failures: `VpsHostEditActivity.kt:183,211`,
    `DomainEditActivity.kt:191,219`,
    `CloudAccountsActivity.kt:234,264,356`,
    `CloudAccountsFragment.kt:543,568,606,644,650,768`,
    `MainActivity.kt:516` (a VNC *connection* failure as a Toast). Fix:
    route all of these through the existing `showError()` helper in
    `ActivityExtensions.kt` and add a retry action where one makes sense.
13. **Key-import errors show BouncyCastle class names in a dialog, and the
    app branches on that English text** — RESOLVES item 6's first open
    question. `KeyStorage.kt:958,978,1010` build messages containing
    `${pemObject.javaClass.simpleName}` and
    `Error: ${e.javaClass.simpleName}: ${e.message}`; these travel
    `ParseResult.Error` → `ImportResult.Error` → `showKeyImportErrorDialog()`
    at `ConnectionEditActivity.kt:2185,2234` and `showError(...)` at
    `AuthKeysFragment.kt:660,711`. Worse, `KeyStorage.kt:247` and
    `AuthKeysFragment.kt:655-657` make *control-flow* decisions by
    `contains("passphrase")`/`contains("encrypted")` on that same message —
    so the encrypted-key passphrase prompt silently stops appearing the
    moment the message is reworded or translated. Fix: give `ParseResult`
    /`ImportResult` a typed error enum (`ENCRYPTED_NEEDS_PASSPHRASE`,
    `UNSUPPORTED_FORMAT`, `CORRUPT`, …) and branch on that, not on text.
14. **SSH classifier has no branch for the most common real failures** —
    beyond the wrapped-cause bug in item 1, `SSHConnection.kt:2360-2480`
    matches only four substrings, so host-key mismatch/changed, "Too many
    authentication failures", "No route to host" / network unreachable,
    "session is down" / server closed the connection, wrong key passphrase,
    unsupported auth method, and jump-host/proxy failures all land in the
    generic `else` at `:2459` or the top-level `else` at `:2505`. Fix: add
    typed branches for these once `.cause` unwrapping is in place, since
    several are distinguishable only from the wrapped cause.
15. **Retry has no backoff or attempt limit** —
    `TabTerminalActivity.kt:1733-1750` dismisses the dialog and calls
    `connectToProfile(profile, forceNew = true)` immediately with no
    counter, delay, or in-flight guard, so a user hammering Retry against a
    down host spawns repeated connect attempts. Fix: disable the button
    while a retry is in flight and apply a short escalating delay after the
    second consecutive failure.
16. **Light mode gets a dark tab strip** — `values/colors.xml:79-81`
    defines the *day* palette as `tab_background=#FF2C2C2C` with
    `tab_text=#FFFFFFFF`, while `surface=#FFFFFFFF` (`:90`). Under
    `Theme.Material3.DayNight` the toolbar and content are light but the
    tab bar stays near-black, so the tab strip looks visually detached in
    light mode. `values-night/colors.xml:30-31` is nearly identical
    (`#FF1E1E1E`). Fix: make the day values genuinely light and let
    `values-night` carry the dark variant, or repoint
    `Widget.TabSSH.TabLayout` (`themes.xml:157-164`) at
    `?attr/colorSurfaceVariant` / `?attr/colorOnSurfaceVariant`.
17. **Overflow menus and the nav header hardcode the wrong theme mode** —
    `activity_tab_terminal.xml:24`, `include_app_bar.xml:20` and
    `activity_multi_host_dashboard.xml:20` set a **Light** `popupTheme`, so
    the toolbar overflow menu pops white in dark mode; conversely
    `activity_tab_terminal.xml:14` (`ThemeOverlay.AppCompat.Dark.ActionBar`)
    and `nav_header.xml:9` (`ThemeOverlay.AppCompat.Dark`) hardcode dark, so
    they are wrong in light mode. These are also AppCompat overlays inside a
    Material 3 app (7 sites total, incl. `Widget.AppCompat.ProgressBar` in
    5 manager layouts). Fix: drop the explicit overlays and let the DayNight
    theme drive both, migrating the AppCompat widget styles to Material 3.
18. **Two parallel semantic color families, only one of them dark-aware** —
    `values-night/colors.xml` overrides 27 of 105 colors, including the
    `status_success/warning/error/info(+_container)` family; the older
    `connected`, `connecting`, `disconnected`, `connection_error`, `success`,
    `warning`, `error` family has **no** night override yet is still used by
    12 drawables (`state_dot_*.xml`, `connection_status_*.xml`, `ic_error`,
    `ic_connected`, `ic_disconnect`, `ic_download`, …) and by
    `MultiHostDashboardActivity.kt:1227-1229`,
    `ContainerListAdapter.kt:147-149`, `TabTerminalActivity.kt:834,886`.
    This is exactly the "never invent a second color palette" violation in
    AI.md PART 7. Fix: collapse the old family onto the `status_*` tokens
    and delete the duplicates; `error_dark` and `focus_indicator` have zero
    references and should go too.
19. **High-contrast mode never touches the app chrome** —
    `Theme.TabSSH.HighContrast` (`themes.xml:118-126`) is referenced
    **nowhere**: there is not a single `setTheme(R.style...)` call in the
    codebase and the manifest only ever uses `Theme.TabSSH` and
    `Theme.TabSSH.Transparent`. `isHighContrastMode()` is read only by
    `ThemeManager.kt:365` (terminal colors) plus backup/sync serialization,
    so enabling the accessibility toggle leaves settings, lists and dialogs
    unchanged — AI.md PART 7 requires it applied as a palette overlay.
    `Theme.TabSSH.Dark`, `.Fullscreen` and `.Settings` are likewise dead
    (0 references). Note `.HighContrast` also hardcodes a *light* surface,
    so applying it as written would invert dark mode.
20. **Zero accessibility state announcements app-wide** —
    `announceForAccessibility` appears 0 times across all 429 Kotlin files,
    and `accessibilityLiveRegion` only twice
    (`activity_container_host_manager.xml:109`,
    `fragment_container_dashboard.xml:217`). AI.md PART 7 mandates "state
    announcements for async operations": a TalkBack user gets no spoken
    feedback when a connection succeeds or fails, a transfer completes, or a
    key import finishes. Fix: announce terminal/transfer/import state
    transitions, at minimum on the connect and SFTP paths.
21. **"Disconnect all" has no confirmation and stays enabled with zero
    tabs** — RESOLVES item 6's third open question.
    `TabTerminalActivity.kt:5622-5626` calls `tabManager.closeAllTabs()`
    with no dialog and no undo, reached from `btn_disconnect_all`
    (`:1002-1006`) and the menu (`:3768`); neither affordance is ever
    hidden or disabled when the tab count is 0, so the app offers a live
    "destroy everything" action with nothing to destroy (it no-ops
    silently). Fix: confirm with the live tab count in the message, and
    disable/hide both affordances when there are no tabs.
22. **Closing a tab confirms for Panes but not for SSH/VNC/Console** —
    `TabTerminalActivity.kt:5585-5597`: the `Tab.Panes` branch routes to
    `showPanesCloseDialog()` (`:5606`) while the SSH, VNC and Console
    branches close immediately, so the identical "close tab" affordance
    drops a live shell mid-command without warning in three of four cases.
    Also `TabTerminalActivity.kt:4007-4030` deletes a saved workspace on a
    single `setItems()` pick with no secondary confirm. Fix: one consistent
    close-confirmation policy across tab types, keyed on whether the session
    is live rather than on tab class.
23. **Destructive confirmations put the safe choice on the styled button** —
    `TabTerminalActivity.kt:5610-5614`: "Disconnect All" is the *negative*
    button while "Keep Running" is the *positive* one, and neither carries
    error-color styling, so the destructive path has no visual danger cue.
    App-wide there is also not one Snackbar-undo path, including for
    trivially recoverable clears (sync log, app logs, completed transfers).
    Fix: style whichever button destroys data with `?attr/colorError`, and
    swap the low-risk clears from confirm-dialog to act-plus-undo.
24. **"Clear completed transfers" acts with no confirmation** —
    `SFTPActivity.kt:1310-1331` (menu `action_clear_transfers`, `:1302-1305`)
    wipes the transfer list immediately and only Toasts the count
    afterwards. Low blast radius, but it is an unannounced irreversible
    list wipe; best fixed as the first Snackbar-undo (item 23).
25. **DONE** — `MultiHostDashboardActivity`, `LogViewerActivity`,
    `AuditLogViewerActivity`, `ContainerHostsFragment`,
    `HypervisorsFragment`, and the five hypervisor managers
    (`ProxmoxManagerActivity`, `VMwareManagerActivity`,
    `LibvirtManagerActivity`, `XCPngManagerActivity`, `OciManagerActivity`)
    now render a distinct error state (color + retry) instead of reusing
    the empty-state view. `ContainerListFragment` was already correct from
    an earlier batch — verified, no change needed.
26. **DONE** — `SyncLogActivity.loadSyncLog()` now wraps the DB read in
    try/catch, logs the exception, and shows a distinct error state with
    tap-to-retry.
27. **DONE** — `WidgetConfigActivity` now checks `isEmpty()` and shows an
    icon+title+hint+CTA empty state wired to `ConnectionEditActivity`'s
    add-connection flow; the four identity fragments
    (`fragment_auth_ssh.xml`, `fragment_auth_keys.xml`,
    `fragment_auth_vnc.xml`, `fragment_auth_vms.xml`) each gained a CTA
    button in their empty state wired to that screen's own add-identity
    dialog. `activity_audit_log_viewer.xml`, `activity_sync_log.xml`, and
    `activity_transcript_viewer.xml` were deliberately left without a CTA —
    see item 46.
28. **Search results flicker on every keystroke** —
    `ConnectionsFragment.kt:253-255` implements `submitList()` with
    `notifyDataSetChanged()` on the app's highest-frequency update path;
    `VncIdentityAdapter.kt:19-21` and `PaneGroupAdapter.kt:19-21` do the
    same on every add/edit/delete. ~10 sibling adapters already use
    `ListAdapter` + `DiffUtil.ItemCallback` correctly. Fix: convert these
    three to match, which also restores item animations and scroll position.
29. **Eight unbounded RecyclerViews inside NestedScrollViews** — recycling
    is disabled, so every row is inflated at once:
    `fragment_auth_ssh.xml:41-45`, `fragment_auth_keys.xml:41-45`,
    `fragment_auth_vnc.xml:41-45`, `fragment_auth_vms.xml:41-45`,
    `fragment_panes.xml:41-45`, `activity_cloud_accounts.xml:14-73`,
    `activity_port_forwarding.xml:121-125` and `:204-208`, and worst
    `activity_cluster_command.xml:214-220` (per-host command output, truly
    unbounded — while a sibling list in the same file at `:94-102` correctly
    caps at `maxHeight=300dp`). Fix: `layout_height="0dp"` + weight outside
    the NestedScrollView, or a `maxHeight` cap like the sibling.
30. **Only one of ten edit screens guards unsaved changes** —
    `ConnectionEditActivity.kt:877-893` has a proper `OnBackPressedCallback`
    + discard prompt keyed on `hasUnsavedChanges` (`:852`). Nine others
    discard silently on Back or Cancel: `HypervisorEditActivity.kt:664,772`,
    `ContainerHostEditActivity.kt:272,539`, `DomainEditActivity.kt:93`,
    `NetworkRouteEditActivity.kt:232`, `PortForwardEditActivity.kt:215`,
    `VncHostEditActivity.kt:128`, `VpsHostEditActivity.kt:94`,
    `KeyboardCustomizationActivity.kt:430`, `ThemeEditorActivity.kt:362`.
    Fix: hoist the `ConnectionEditActivity` pattern into `TabSSHActivity`
    and adopt it in all nine.
31. **No activity survives a rotation with unsaved form data** — there is
    not a single `onSaveInstanceState` or `ViewModel` under
    `ui/activities/` (the only `onSaveInstanceState` in the app is in
    `ui/dialogs/ReportIssueDialog.kt`), and only `TabTerminalActivity`
    declares `android:configChanges` (`AndroidManifest.xml:134-137`). So
    rotating while filling in `ConnectionEditActivity`
    (`AndroidManifest.xml:143-146`) or `HypervisorEditActivity` (`:231`)
    loses everything typed. Fix: at minimum add save/restore to the two
    long edit forms.
32. **Field errors bypass the Material inline-error style** —
    `ConnectionEditActivity.kt:1823,1835-1838,1845` set `.error` on the
    child `TextInputEditText`, which renders the legacy floating tooltip
    rather than the `TextInputLayout` box-color + caption; the fields are
    wrapped in `TextInputLayout` (`activity_connection_edit.xml:8-26`), and
    `NetworkRouteEditActivity.kt:301-307` /
    `PortForwardEditActivity.kt:341-373` already do it correctly. Same
    anti-pattern in `HypervisorEditActivity.kt:887-926`,
    `VncHostEditActivity.kt:211-231`, `VpsHostEditActivity.kt:128-133`.
    Worse, `ContainerHostEditActivity.kt:398-497` shows a full AlertDialog
    instead of inline errors and silently `coerceIn(1, 65535)`s an
    out-of-range port at `:436-437` rather than rejecting it. Fix: move all
    `.error` assignments to the `TextInputLayout`, and reject rather than
    silently clamp the port.
33. **`parentActivityName` is dead metadata; edit screens show a hamburger,
    not an up-arrow** — `TabSSHActivity.kt:123-141` forces every subclass's
    toolbar to `ic_menu` opening the drawer (`:137,139`), and
    `setDisplayHomeAsUpEnabled` appears nowhere else, so the 44
    `parentActivityName` declarations in `AndroidManifest.xml` never fire.
    Consistent, but it means detail/edit screens have no Material up
    affordance and the toolbar can never trigger item 30's discard guard.
    Fix: opt edit/detail activities into an up-arrow that routes through the
    same `OnBackPressedDispatcher` as system Back.
34. **Theme changes from restore/sync never reach the running UI** —
    `ThemeManager` is the single source of truth (`_currentTheme`
    `StateFlow` at `ThemeManager.kt:31-32`, listeners notified in
    `applyTheme()` at `:172-183`), but `BackupImporter.kt:763` and
    `SyncDataApplier.kt:1492` write the preference directly via
    `setTerminalTheme()`, bypassing `applyTheme()` entirely — so after a
    backup restore or a sync apply the StateFlow and listeners never fire
    and open terminals keep the old theme until restart. Compounding it,
    `PreferenceManager.kt:247-248` (`getTheme`/`setTheme`) and `:596-597`
    (`getTerminalTheme`/`setTerminalTheme`) are two names for the same
    `terminal_theme` key — the exact "two names for one value" bug AI.md
    PART 7 calls out. Fix: delete the alias pair and route all writes
    through `ThemeManager.applyTheme()`.
35. **de/es/fr are 5% translated but all four locales are offered** —
    `values/strings.xml` has 3318 strings; `values-de`, `values-es` and
    `values-fr` have 173 each. `arrays.xml:87-98` still lists Spanish,
    French and German in the picker
    (`preferences_general.xml:18-25`), so choosing one yields a ~95%
    English UI. AI.md PART 7 says the picker lists only locales with real
    translation files. Fix: either gate the picker on a translation-
    completeness threshold or drop the three stub locales until they land.
36. **Every settings dropdown stays English in all locales** —
    `values/arrays.xml` holds 48 `string-array`s / 266 `<item>`s and **zero**
    of them reference `@string/`, and there is no `values-*/arrays.xml`
    override in any locale. So `theme_entries` (`:75-79`),
    `language_entries` (`:87-92`), `lock_timeout_entries` (`:101-107`),
    `terminal_theme_entries` (`:158-186`) and 44 more render as hardcoded
    English regardless of language. The timeout arrays also hardcode
    English plurals ("1 minute" / "5 minutes") instead of `R.plurals`,
    against AI.md PART 7's Human-Readable Values rules. Fix: convert every
    `*_entries` item to a `@string`/`@plurals` reference (the `*_values`
    arrays are machine keys and correctly stay literal), and list languages
    by endonym ("Deutsch", not "German").
37. **55 hardcoded `android:title` strings in `res/menu/`** — layouts are
    clean (0 hardcoded `android:text`/`hint`/`contentDescription`), but the
    menus were missed: `main_menu.xml` (18, e.g. `:7` "Quick Connect",
    `:13` "Search", `:20` "Sort"), `terminal_menu.xml` (20),
    `log_viewer_menu.xml` (4), `audit_log_menu.xml` (4), `sftp_menu.xml` (3),
    `menu_connections.xml` (2), plus one each in
    `menu_keyboard_customization.xml`, `menu_hypervisors.xml`,
    `menu_dashboard.xml`, `connection_edit_menu.xml`. Fix: extract to
    `strings.xml` — these are the app's most-seen strings.
38. **45 duplicated string values bloat `strings.xml` and the translation
    cost** — e.g. "Username" under 7 keys (`username_hint`,
    `connection_username_hint`, `port_forward_ssh_username_hint`, …),
    "Password" under 7, "Delete failed: %1$s" under 6
    (`vnc_host_delete_failed_fmt`, `telnet_host_delete_failed_fmt`,
    `domain_delete_failed_fmt`, …), "Cancel" under 5, "Error" under 4
    (`status_error`, `hypervisor_error_title`, `dialog_title_error`,
    `transferrow_status_error`). AI.md PART 7 § Variables & Constants
    forbids exactly this. Fix: collapse to one key per distinct value; it
    also shrinks the 3318-string translation surface behind item 35. While
    collapsing, replace the generic "Error" titles with operation-specific
    ones ("Connection Failed", "Save Failed") per item 12.
39. **24 sub-48dp touch targets and 6 undescribed icons** —
    `ImageButton`s below the 48dp minimum with no compensating padding:
    `activity_sftp.xml:76,165` (40dp),
    `item_dashboard_group_header.xml:29,77,87,97,107` (36dp),
    `item_transcript.xml:36,44,52` (32dp),
    `item_hypervisor_account.xml:33,41`, `item_vnc_identity.xml:33,41`,
    `item_pane_group.xml:36,44` (40dp), `item_identity.xml:30,38`,
    `item_dashboard_host_card.xml:57`, `widget_2x1.xml:51` (32dp),
    `item_cluster_result.xml:49,60`, `overlay_search_bar.xml:65,75,98`
    (36dp). `item_container.xml:114-158` already uses
    `@dimen/min_touch_target` and is the pattern to copy. Separately,
    `activity_import_export.xml:43,94,145,196,247,298` are six 32dp
    `ImageView`s with neither `contentDescription` nor
    `importantForAccessibility="no"`.
40. **711 raw dp and 389 raw sp literals across 80% of layouts** —
    `values/dimens.xml` exists with 86 tokens (and `values-land`,
    `values-sw600dp`, `values-sw720dp` overrides), but 129 of 161 layouts
    still mix raw literals with token refs, so the responsive overrides only
    partially apply. Worst: `activity_sync_settings.xml` (51),
    `bottom_sheet_terminal_menu.xml` (37), `activity_sftp.xml` (32),
    `activity_import_export.xml` (30), `item_dashboard_host_card.xml` (24).
    AI.md PART 7 § Layout rules requires dimension resources. Fix: sweep
    into `dimens.xml`, starting with the touch-target and icon sizes
    (`min_touch_target`, `icon_size_medium` already exist). Related smaller
    bug: `activity_tab_terminal.xml:176-199`'s `bottom_action_bar` is
    pinned to `48dp` while its children are `drawableTop` buttons with both
    icon and label — the label clips. Also `activity_multi_host_dashboard.xml:55`,
    `fragment_performance.xml:457` and `activity_cloud_accounts.xml:91`
    hardcode `app:tint="@android:color/white"` on FAB icons, and
    `bg_bottom_sheet_handle.xml:12` hardcodes `#33808080`.
41. **Code-quality issues worth folding into the same pass** — (a)
    `TabTerminalActivity.kt:820-823` performs four unchecked casts on a
    `View.tag` (`as Triple<*,*,*>`, then `as ImageView`/`as TextView`) with
    no `as?` guard — a stale tag is a `ClassCastException`; (b)
    `AuthKeysFragment.kt:303` creates a
    `CoroutineScope(Dispatchers.IO + SupervisorJob())` *inside* a
    `lifecycleScope.launch`, never stores or cancels it, so an SSH connect
    in flight outlives the fragment; (c) `TabSSHApplication.kt:868` is a
    bare `catch (_: Exception) {}` with no logging around shutdown logic,
    and `:867,884` call `runBlocking` on the main thread; (d)
    `HostKeyVerifier.kt` wraps every host-key DB read in `runBlocking`
    (`:90,131,163,202,264,288,324,348,369`) — convert to real `suspend`
    functions; (e) `VpsMarkdownImportExport.kt:158,169,212` repeat
    `ThreadLocal.get()!!` three times — collapse to one accessor; (f)
    `TerminalPagerAdapter.kt:458,702` never cancel `holderScope` in
    `unbind()`; (g) duplicated WakeLock/WifiLock lifecycles in
    `SSHConnectionService.kt:876,908,933,956` vs
    `VncKeepAliveService.kt:189,203,216,235`, and the
    `(application as TabSSHApplication)` cast repeated in 10+ activities —
    both want a shared helper per AI.md PART 7 § Reuse Before Creating; (h)
    `HypervisorAccountAdapter.kt:64-65` is the only row adapter whose
    `itemView` has no click listener, so tapping the row does nothing; (i)
    `ConnectionEditActivity.kt:1973`/`:2254` is the last remaining
    `startActivityForResult`/`onActivityResult` pair (the rest of the app
    already uses `registerForActivityResult`, including `:143` in the same
    file); (j) `SSHConnection.kt:596-602`, `TabTerminalActivity.kt:3138-3140`
    and `:3999` are the only three hardcoded Toast literals left out of 502
    call sites — otherwise the i18n-in-code rule is met.
42. **Intermittent "eats one character to the left of cursor" bug — likely
    root-caused, needs on-device confirmation before fixing** — user-reported
    repro: type a long command (e.g. `tmux-new ai --dir ~/Projects/github/dfprivate`),
    hit Enter, type a new command (e.g. `mycommand`), hit Enter again — the
    last character of the new command is occasionally missing
    (`mycomman`), not consistently. `TerminalView.kt`'s `InputConnection`
    implementation always returns `""` from `getTextBeforeCursor()`/
    `getTextAfterCursor()` (:3683,3685) and `setComposingRegion()` is a
    hardcoded no-op returning `false` (:3729), so Gboard (and any IME that
    tracks composing state via those calls) has no accurate view of what's
    actually on screen. Gboard's autocorrect/predictive-text engine resets
    its internal composing state on Enter/submit; when its stale internal
    model disagrees with the (always-empty) surrounding text we report, it
    can issue a stray `deleteSurroundingText(1, 0)` — which `deleteBefore()`
    (:3711-3721) forwards as a real DEL byte (0x7F) to the shell, eating one
    already-committed character. This matches the bug's "only periodically"
    character exactly (fires only when Gboard's autocorrect state happens to
    desync, not every keystroke) and is consistent with it being an
    IME-interaction issue rather than a `TerminalView` logic bug per se —
    but the app's fake/empty `InputConnection` surface is what allows Gboard
    to get into that state. Needs confirmation via `adb shell dumpsys
    input_method` + Gboard logcat during a live repro before deciding
    between (a) a targeted mitigation (e.g. briefly ignore a
    `deleteSurroundingText` call that arrives within one input-event of a
    `finishComposingText`/Enter submit, since real backspaces come through
    `sendKeyEvent`/`commitText` instead), or (b) reporting upstream to
    Gboard if it reproduces with other terminal apps using the same
    minimal-`InputConnection` pattern (Termux uses an equivalent stub).
43. **"Active Sessions" strip on Hosts→SSH duplicates the new Hosts→Active
    sub-tab, and the Active sub-tab itself needs UI/UX work** — screenshot
    (pste.us/raw/c3OqWJmu) shows Hosts→Active already listing every
    connected session with state dot, protocol/state text, and elapsed
    time; `SshHostsFragment.kt`'s separate "Active Sessions" strip above the
    SSH connection list (`setupActiveSessionsStrip()` :237,
    `stub_active_sessions`/`recycler_active_sessions` :122,279-298, "see
    all" dialog :307-352, tagged "Issue #165 + #175" in comments at :65,229)
    is now redundant now that Hosts has its own dedicated Active sub-tab —
    remove the strip, its stub layout, `ActiveSessionAdapter`,
    `AllActiveSessionsAdapter`, and `dialog_active_sessions_list.xml` if
    nothing else references them (grep before deleting). Separately, the
    Active sub-tab's own rows need work: session names are hard-truncated
    mid-word with no ellipsis ("code.casjay." / "uptime.servi" in the
    screenshot — the full names are longer and just get cut off, not
    `TextUtils.TruncateAt.END`-marked), rows show no host/user/port detail
    (only a device-connection label + elapsed time, no way to tell *which*
    host without tapping in), and there's no swipe-to-disconnect or
    long-press action parity with the main connection list rows.
44. **Port-forward "running" status can go stale and drift from the real
    tunnel** — RESOLVES item 6's second open question (the other named
    areas — host lists, cluster broadcast targets, active sessions, cloud
    accounts — were checked and are already correctly live-observed via
    Flow/StateFlow; see investigation notes). `PortForwardCoordinator.kt`'s
    `running: ConcurrentHashMap<String, Running>` (`:39-40`) is written only
    by `start()`/`stop()`/`stopAll()` (`:47-98,105-123,140-144`) — there is
    no callback from the underlying SSH session/tunnel closing on its own
    (network loss, remote closes, `SSHConnectionService` killed), so a dead
    tunnel keeps `isRunning(pfId)` (`:126`) returning `true` indefinitely.
    Compounding it, `PortForwardingActivity.kt`'s `observeForwards()`
    (`:232-247`) collects the `portForwardDao().getAll()` Flow correctly for
    the row *list*, but only recomputes the running overlay
    (`refreshRunningState()`, `:249-254`, calling `isRunning()`) when that DB
    Flow emits (i.e. a rule is added/edited/deleted) or when the user
    explicitly taps `toggle()` (`:266-288`) — never on the tunnel's actual
    state changing. So a forward that silently died shows "forwarding" until
    the user manually toggles it or an unrelated DB write happens to
    refresh the screen. Fix: give `PortForwardCoordinator` a
    `StateFlow<Set<String>>` (or per-id `StateFlow<Boolean>`) of running ids
    that the tunnel/session's close/failure path updates in addition to
    `start`/`stop`, and have `PortForwardingActivity` `combine()` that Flow
    with the DAO Flow instead of polling `isRunning()` on the wrong trigger.

45. **Two loose ends found while fixing items 16–19 (color/theme resources)**
    — noted but left alone since neither is what those items named. (a)
    `values/themes.xml`'s `Theme.TabSSH` sets `colorErrorContainer` to
    `@color/error_dark`, a flat hex with no `values-night` override —
    unlike every other Material3 role in that style (`colorSurface`,
    `colorOutline`, …) it does not adapt between light/dark, so the
    "error container" background is the same strong red in both modes
    instead of a subtle day tint / dark tint pair like
    `status_error_container` already provides. Consider repointing it at
    `@color/status_error_container` instead of `@color/error_dark`. (b)
    `drawable/high_contrast_background.xml` (which draws
    `@color/high_contrast_background`/`@color/high_contrast_foreground`)
    has zero references anywhere in `res/layout` or `java` — predates this
    batch, not created by it. Confirm it's genuinely unused and delete it
    (and the two colors, if nothing else picks them up) or wire it in
    wherever it was meant to be used.

46. **Read-only log/transcript empty states left without an "add" CTA on
    purpose** — found while doing item 27. `activity_audit_log_viewer.xml`,
    `activity_sync_log.xml`, and `activity_transcript_viewer.xml` show a
    label-only empty state with no CTA button, unlike the identity
    fragments and `WidgetConfigActivity`. Not fixed: these are
    auto-populated, read-only historical logs (audit trail, sync history,
    session transcripts) with no user-initiated "add" action — item 27's
    icon+title+hint+CTA pattern does not apply since there is nothing for a
    button to create. Left as bare label-only empty states; revisit only if
    a genuine action becomes relevant (e.g. "Export" or "Generate test
    entry").
47. **`SyncLogActivity.copyLogToClipboard()` also lacks try/catch around
    `syncLogDao().getRecent()`** — found while fixing item 26, which named
    only `loadSyncLog()`. Same DB-failure crash risk on the copy-to-
    clipboard path. Fix: wrap in try/catch, log the exception, and Toast a
    failure message.
48. **`AuditLogViewerActivity`'s empty branch never calls
    `adapter.updateLogs(logs)`** — found while fixing item 25's error-state
    parity for this file. Only the non-empty branch refreshes the adapter,
    so if the log list transitions from non-empty to empty the RecyclerView
    (hidden, but still backing the adapter) keeps stale data. Fix: call
    `adapter.updateLogs(logs)` (with the empty list) in both branches.
49. **~220 additional duplicated string values remain in `strings.xml`
    beyond the 45 consolidated for item 38** — found while implementing
    item 38. The full scan found ~265 groups of identical string values;
    only the clearest 45 (exact-duplicate action/dialog labels, format-
    string templates, and `_hint` suffixed fields with an unambiguous
    canonical name) were consolidated in that pass. The remaining ~220
    groups were deliberately left alone because they are short generic
    words/phrases (e.g. single words, numbers, punctuation-only strings)
    or symbols where two strings share text incidentally rather than
    semantically, and forcing them onto one shared key risks an
    unrelated screen's copy changing when only one caller's wording
    should. Fix: re-run the item-38 duplicate scan, review the remaining
    groups case by case, and consolidate only the ones that are
    genuinely the same semantic string (not just coincidentally equal
    text) referenced from multiple places.
50. **Item 40's raw dp/sp literal sweep is only partially complete** — the
    five worst-offender layouts (`activity_sync_settings.xml`,
    `bottom_sheet_terminal_menu.xml`, `activity_sftp.xml`,
    `activity_import_export.xml`, `item_dashboard_host_card.xml`) plus a
    broad mechanical pass for four highly-recurrent patterns
    (`iconSize="20dp"`, `textSize="11sp"`, `minHeight="48dp"`,
    `iconPadding="12dp"`) were converted to `@dimen` references, and 12 new
    tokens were added to `dimens.xml`. Recurring `8dp`/`13sp` literals were
    found across roughly 27-28 more layout files but only fixed in
    `activity_import_export.xml`. Fix: re-run the item-40 sweep across the
    remaining `app/src/main/res/layout/*.xml` files not yet covered,
    reusing the tokens already in `dimens.xml` before adding new ones.

