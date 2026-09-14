# Cross-API-Level Audit — tabssh/android

Confirmed baseline from `app/build.gradle`: `minSdk 24`, `targetSdk 34`, `compileSdk 35`
(`app/build.gradle:76,100,101`). Supported span: API 24–36.

Overall: the codebase is unusually well-guarded — PendingIntent immutability, receiver
flags, predictive back (dispatcher everywhere), POST_NOTIFICATIONS runtime request,
MediaProjection callback ordering, and scoped-storage tiers are all correct. The real
defects are below.

---

### [BLOCKER] [CONFIRMED] Bundled 64-bit native libraries are not 16 KB page-size compatible
- Where: `app/src/main/jniLibs/arm64-v8a/libmosh-client.so`, `app/src/main/jniLibs/arm64-v8a/libtor.so`, `app/src/main/jniLibs/arm64-v8a/libtabssh_native.so` (built out-of-tree by `deps/prereqs/spice/build-android.sh` and `scripts/fetch-{mosh,spice,tor}-*.sh`)
- Affected levels: API 35–36 devices booted with 16 KB pages (and every future device; Android 17 can make it fatal)
- What: `readelf -lW` shows LOAD segments with p_align `0x1000` (4 KB) in all three arm64
  libraries. 16 KB compatibility requires every LOAD segment aligned to `0x4000` (2**14).
  `libtabssh_native.so` has only its R+E segment at `0x4000`; its other three LOAD
  segments are `0x1000`. `libmosh-client.so` and `libtor.so` are entirely 4 KB-aligned.
  `libtabssh_native.so` is loaded via `System.loadLibrary` (SPICE JNI); mosh and tor are
  `exec()`'d from `nativeLibraryDir` (`MoshNativeClient.kt:55`, `TorNativeClient.kt:54`)
  — the kernel's ELF loader applies the same page-alignment requirement to exec'd
  binaries.
- Failure scenario: on a 16 KB-page Android 16 device, the app launches in "16 KB
  backcompat mode" with a user-visible warning dialog and reduced stability; without
  backcompat mode (Android 17 fatal enforcement, or user disables it) SPICE
  library load segfaults and mosh/tor process spawn fails. On Google Play, apps
  targeting API 35+ without 16 KB support cannot release updates after Feb 1, 2027 —
  and the targetSdk 35 bump itself is already forced by Play's target-API policy.
- Source: https://developer.android.com/guide/practices/page-sizes
- Fix: rebuild the three prebuilt libraries with 16 KB alignment — NDK r27+ builds it by
  default; for the out-of-tree autotools/CMake builds pass
  `-Wl,-z,max-page-size=16384` (and `-Wl,-z,common-page-size=16384`) in LDFLAGS in
  `deps/prereqs/spice/build-android.sh` and the mosh/tor build scripts, then re-run
  `scripts/fetch-*-libs.sh` and re-verify with `readelf -lW | grep LOAD` (all `0x4000`).
  Applies to arm64-v8a and x86_64 (64-bit ABIs).

---

### [MAJOR] [CONFIRMED] Boot-time port-forward auto-start FGS launch fails from background on API 31+
- Where: `app/src/main/java/io/github/tabssh/background/PortForwardStartupWorker.kt:31` → `app/src/main/java/io/github/tabssh/ssh/forwarding/PortForwardCoordinator.kt:261` → `app/src/main/java/io/github/tabssh/ssh/connection/SSHSessionManager.kt:196` → `SSHConnectionService.startService()` (`SSHConnectionService.kt:164`, `startForegroundService`)
- Affected levels: API 31–36 (today, targetSdk 34); becomes categorically prohibited at targetSdk 35
- What: after `BOOT_COMPLETED`, `PortForwardBootReceiver` only enqueues a WorkManager
  job with a `NetworkType.CONNECTED` constraint. The FGS start happens later, inside the
  deferred worker — outside the receiver's background-start exemption window. Apps
  targeting API 31+ may not start an FGS from the background;
  `startForegroundService()` throws `ForegroundServiceStartNotAllowedException`
  synchronously. The only reason this can ever work is exemption #13 (user granted
  battery-optimization exemption via the app's optional
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` flow) — a grant the user can decline.
  The throw lands inside `connectToServer`'s catch-all (`SSHSessionManager.kt:186`), so
  it is silently swallowed: `connectToServer` returns null after the SSH connection
  already succeeded, and the worker retries forever (`Result.retry()`,
  `PortForwardStartupWorker.kt:35`).
  Additionally, on targetSdk 35 the `BOOT_COMPLETED` path may not launch a `dataSync`
  FGS at all — the exemption no longer helps.
- Failure scenario: on API 31+, user reboots the device without having granted the
  battery-optimization exemption → saved forwards marked enabled + auto-start never come
  back up; no error surfaces (only a log line); the worker burns retries indefinitely.
- Source: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start and https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed
- Fix: don't route the boot path through `SSHConnectionService`. Either (a) run boot-time
  forwards without the FGS and post a normal notification prompting the user to open the
  app (which then legally promotes to FGS from foreground), or (b) wrap the
  `startForegroundService` call in a `ForegroundServiceStartNotAllowedException` catch
  with an explicit degraded-mode notification, and long-term move the service off the
  `dataSync` type (see next finding).

---

### [MAJOR] [CONFIRMED — latent until the forced targetSdk 35 bump] `dataSync` FGS type gets a 6-hour cap that kills persistent SSH/VNC sessions
- Where: `app/src/main/AndroidManifest.xml:553` (`SSHConnectionService`, `foregroundServiceType="dataSync"`), `app/src/main/AndroidManifest.xml:561` (`VncKeepAliveService`, same); no `Service.onTimeout()` override exists anywhere in the codebase
- Affected levels: API 35–36 once targetSdk is raised to 35 (Google Play already requires 35 for updates; F-Droid will follow)
- What: for apps targeting API 35+, all `dataSync` foreground services combined may run
  at most 6 hours per 24-hour period while the app is in background. At timeout the
  system calls `Service.onTimeout(int, int)`; a service that doesn't `stopSelf()` within
  a few seconds is killed with
  `RemoteServiceException: "A foreground service of type dataSync did not stop within
  its timeout"`. Neither service implements `onTimeout`, so the process crashes and all
  live SSH connections / parked VNC sessions die.
- Failure scenario: (post-bump) user leaves an SSH session connected overnight → after
  6 hours backgrounded the app crashes with RemoteServiceException, dropping every
  connection — precisely the "persistent SSH connection" feature the service exists for.
- Source: https://developer.android.com/about/versions/15/behavior-changes-15 (Data sync FGS timeout section)
- Fix: migrate both services to `foregroundServiceType="specialUse"` with the
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` property `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="ssh-session-keepalive"/>`
  (the documented escape hatch for long-lived interactive network sessions that fit no
  other type), or implement `onTimeout()` with graceful session teardown + user
  notification as a stopgap. Do this together with the targetSdk 35 bump, not after.

---

### [MINOR] [CONFIRMED] API 29 devices get a permanent SAF fallback that `requestLegacyExternalStorage` was designed to avoid
- Where: `app/src/main/java/io/github/tabssh/utils/StorageAccessHelper.kt:57-59` (and the comment at line 31 claiming "no full-filesystem API exists on this one OS version")
- Affected levels: API 29 only
- What: apps targeting API 30+ still honor `android:requestLegacyExternalStorage="true"`
  when running on Android 10 devices (the flag is only ignored from Android 11 onward).
  Declaring it would give the local SFTP browser the same broad access on API 29 that
  legacy permissions give on ≤28 and MANAGE_EXTERNAL_STORAGE gives on ≥30. The current
  code (and its comment) treats API 29 as having no option.
- Failure scenario: on API 29, user opens the local file browser → forced into the SAF
  tree-picker flow while every other supported level gets direct filesystem access; the
  in-code comment misdocuments why.
- Source: https://developer.android.com/training/data-storage/use-cases#opt-out-in-production-app ("requestLegacyExternalStorage is ignored on Android 11... apps targeting Android 11 can still use it on Android 10 devices")
- Fix: add `android:requestLegacyExternalStorage="true"` to `<application>` and change
  `hasFullAccess`'s API 29 branch to check `Environment.isExternalStorageLegacy()` +
  the legacy runtime permissions; or, if the SAF fallback is a deliberate choice, fix
  the comment (the API does exist).

---

### [MINOR] [CONFIRMED] Stale `uses-sdk` override comment claims minSdk 21; the override itself is now a no-op
- Where: `app/src/main/AndroidManifest.xml:46-48`
- Affected levels: none at runtime (documentation defect)
- What: the comment says the `tools:overrideLibrary="com.termux.terminal,com.termux.view"`
  exists "to maintain our minSdk (21)". minSdk is 24 (`app/build.gradle:100`), which
  meets the Termux libraries' minSdk 24 — the override no longer suppresses anything and
  the "test on older devices" warning is obsolete.
- Failure scenario: none; misleads a future maintainer into believing API 21–23 devices
  are supported.
- Source: https://developer.android.com/build/manage-manifests#override_uses-sdk (overrideLibrary semantics)
- Fix: delete the `<uses-sdk tools:overrideLibrary=...>` element and its comment, or
  rewrite the comment to state it is retained only in case the Termux libs raise their
  minSdk again.

---

## Lint-suppression inventory (all sites, with verdicts)

| Site | Suppression | Verdict |
|---|---|---|
| `TabSSHApplication.kt:1010` | `DEPRECATION` (TRIM_MEMORY_* constants) | Justified — comment correctly documents the API 34 deprecation-without-replacement; best-effort handling kept deliberately |
| `ui/keyboard/MultiRowKeyboardView.kt:467` | `UNUSED_PARAMETER` | Justified — API-shape placeholder |
| `ui/keyboard/KeyboardRowView.kt:367` | `ClickableViewAccessibility` | Justified — touch-handling view; not an API-level issue |
| `services/SessionRecordingService.kt:165` | `DEPRECATION` (`getParcelableExtra`) | Justified — correctly gated to `< TIRAMISU` else-branch |
| `services/SessionRecordingService.kt:255` | `DEPRECATION` (`defaultDisplay.getRealSize`) | Justified — correctly gated to `< R` else-branch |
| `sftp/SFTPManager.kt:154,490,583,946` | `UNCHECKED_CAST` | Justified — JSch's untyped `Vector` API; not API-level |
| `ssh/config/SSHConfigParser.kt:373` | `UNUSED_PARAMETER` | Justified |
| `crypto/tls/HypervisorTrustManagerFactory.kt:125,224` | `SuppressLint("TrustAllX509TrustManager")` | Justified — only `checkClientTrusted` is empty (app is the TLS client); `checkServerTrusted` performs real SHA-256 TOFU pinning. Not hiding an API-level bug |
| `crypto/storage/SecurePasswordManager.kt:557` | `DEPRECATION` (`setUserAuthenticationValidityDurationSeconds`) | Justified — correctly gated to `< R`, semantics-equivalent per comment |
| `crypto/storage/SecurePasswordManager.kt:595` | `UNUSED_PARAMETER` | Justified — documented API-stability shim |
| `crypto/keys/KeyStorage.kt:300` | `UNREACHABLE_CODE` | Justified — compiler appeasement after infinite loop |
| `ssh/forwarding/PortForwardCoordinator.kt:35` | `unused` | Justified |
| `hypervisor/spice/SpiceClient.kt:274-354` (8×) | `unused` | Justified — JNI entry points invoked from native code |
| `ui/activities/TabTerminalActivity.kt:443` | `UnspecifiedRegisterReceiverFlag` | Justified — inside the correctly-guarded `< TIRAMISU` else-branch of a `RECEIVER_NOT_EXPORTED` registration |
| `ui/activities/TabTerminalActivity.kt:752` | `SuppressLint("ClickableViewAccessibility")` | Justified — terminal touch surface |
| `ui/activities/TabTerminalActivity.kt:3128` | `SuppressLint("RepeatOnLifecycleWrongUsage")` | Justified — documented single-collector-per-tab pattern with explicit job cancel |
| `ui/activities/SettingsActivity.kt:1058` | `UNREACHABLE_CODE` | Justified — debug-only test-crash preference |
| `utils/PowerLockHelper.kt:89` | `DEPRECATION` (`WIFI_MODE_FULL_HIGH_PERF`) | Justified — correctly gated to `< Q` |
| `AndroidManifest.xml:23` | `tools:ignore="ScopedStorage"` | Justified for the F-Droid channel per the documented AI.md PART 2/5 file-manager-class exception; note it remains a Play-policy review risk, not a runtime defect |

No suppression hides a real cross-API incompatibility.

## Checked and clean (no finding)

- PendingIntent mutability: every one of the 16 creation sites passes `FLAG_IMMUTABLE` (API 31+ requirement).
- `RECEIVER_NOT_EXPORTED` flag: guarded at `TIRAMISU` where used; the unflagged `registerReceiver` in `SSHConnectionService.kt:209` listens only to `ACTION_SCREEN_ON/OFF` — protected system broadcasts are exempt from the API 34 flag requirement.
- Predictive back: `enableOnBackInvokedCallback="true"` with 100% `OnBackPressedDispatcher` usage; no `onBackPressed()`/`KEYCODE_BACK` overrides remain.
- `POST_NOTIFICATIONS`: declared and runtime-requested (`MainActivity.kt:222`).
- MediaProjection: fresh consent per recording, `registerCallback` before `createVirtualDisplay` (API 34 requirement), `startForeground` before `getMediaProjection`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` declared.
- `android:exported`: explicit on every component; the three exported ones (MainActivity, LinkHandlerActivity, TaskerActionReceiver/LocaleEditActivity/LocaleFireReceiver) are intentional and the Tasker receiver is signature-permission-gated.
- Package visibility: `<queries>` declared for com.termux and the git/ftp/ftps/svn VIEW intents actually resolved.
- Exact alarms: none used (no AlarmManager anywhere).
- Non-SDK interfaces: no `Class.forName`/`setAccessible` reflection in app code.
- W^X (API 29+): mosh/tor are exec'd from `applicationInfo.nativeLibraryDir` with `useLegacyPackaging=true` (`app/build.gradle:250`) — the one exec location still permitted.
- Scoped storage tiers in `StorageAccessHelper` correctly branch ≤28 / 29 / ≥30 (modulo the API 29 MINOR above).
