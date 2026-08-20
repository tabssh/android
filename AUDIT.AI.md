# Project Audit

Started: 2026-08-13

## Pass 1: Security
- [x] app/build.gradle:150,152 — release signing passwords fall back to plaintext `"tabssh123"`; development.yml repeats it (RED FLAG: changing signing identity is irreversible for shipped installs — needs user decision) — FIXED 2026-08-19: removed the fallback; signing identity untouched. `signingConfigs.release` now reads `KEYSTORE_PASSWORD`/`KEY_PASSWORD` from the environment only, and a `gradle.taskGraph.whenReady` check fails any assemble/bundle task that needs that signing config with a named-variable error if they're unset — `make check` (compile/lint/unit tests) is unaffected. `development.yml`'s ephemeral-keystore fallback now generates a random password each run instead of `"tabssh123"` (see Pass 5 item below)
- [x] storage/database/entities/HypervisorProfile.kt:44 — `password` was a plaintext DB column — FIXED 2026-08-19: the field and column are gone. `MIGRATION_13_14` recreates `hypervisors` without it (create/copy/drop/rename + index recreate — never a literal `DROP COLUMN`), first parking any non-empty value in a transient `hypervisor_password_carryover` table that `HypervisorPasswordStore.sweepLegacyPlaintext` drains into Keystore alias `hypervisor_{id}` at next startup, deleting each row only after a confirmed write, so no dev-build user loses a stored password and a Keystore fault cannot brick the database. Also closed a real leak: `SyncDataCollector.collectHypervisors()` never blanked the column, so un-swept rows were shipping plaintext in sync payloads. AI.md PART 0 rules 2/3 and PART 5 amended to sanction the table-recreate technique
- [x] backup/BackupManager.kt — backup archive can include secrets without a mandatory user password (AI.md PART 6 requires password-encrypted secret export) — RESOLVED 2026-08-19 by an explicit user decision (batch item A3): a plaintext archive does include secrets, but only behind a hard type-to-confirm dialog naming the exposure, and `BackupManager` refuses to open the output stream without that confirmation. IDEA.md's sync/backup section records the same decision
- [x] sync/encryption/SyncEncryptor.kt — PBKDF2-HMAC-SHA256. AI.md PART 6 prefers Argon2id "where available via BouncyCastle" (BC 1.79 is a dependency) — FIXED 2026-08-19: the KDF is now Argon2id (RFC 9106 v1.3) via BouncyCastle's `Argon2BytesGenerator` at 64 MiB / t=3 / p=1, matching the tuned pairing and app-lock cost profile. The 32-byte `TABSSH_SYNC_V3` header now carries a KDF identifier byte plus the memory/passes/lanes the file was written at, replacing the bare PBKDF2 iteration count, so the reader always derives with the file's own cost. No legacy read path: only a rolling development build ever wrote the PBKDF2 form, and those files are rejected with an unsupported-KDF error rather than parsed best-effort. CHANGELOG records the required re-export

## Pass 2: Code Quality
- [x] Inline (same-line) comments — FIXED 2026-08-19: ~840 relocated above their declarations or deleted across the whole `app/src/main/java/io/github/tabssh/` tree, including hoisting them above `@ColumnInfo`/`@Volatile` annotations rather than wedging them between annotation and property. Verified: a tree-wide scan for code-then-`//` now returns 0 (the only prior hit was a `https://` inside a string literal)
- [x] app/build.gradle — `BuildConfig.DEVEL` and `BuildConfig.BUILD_EPOCH` declared but read by nothing — PARTIALLY FIXED 2026-08-19: `grep -rn` across `app/src/` confirmed neither is read. `DEVEL` deleted (redundant with `BuildConfig.BUILD_TYPE`, which already identifies the devel channel). `BUILD_EPOCH` kept — AI.md PART 13 explicitly mandates it as a `BuildConfig` field for `fdroidRelease` reproducibility; deleting it would contradict spec text, not just clean up dead code. It is now genuinely read: `ReportIssueDialog` includes it in the bug-report environment block, so a report ties back to the exact build and to `version.txt`'s `build_epoch` line
- [ ] 40 app classes fully removed by R8 (see usage.txt) — unwired features vs genuine dead code triage
- [ ] ui/ hardcoded user-visible strings (~25 Toasts in SettingsActivity plus 10 other files) and hardcoded colors (TerminalView.kt:91,1029 etc.) — owned by the UI/UX + Settings commit of the 2026-08-19 batch, which rewrites these screens; fixing them here would collide with that work

## Pass 3: Logic

## Pass 4: Documentation

## Pass 5: Spec Compliance
- [x] .github/workflows/development.yml — throwaway keystore generated per run when KEYSTORE_BASE64 is unset; daily devel APKs from forks cannot update each other — FIXED 2026-08-19: kept the ephemeral-keystore fallback (still produces an installable APK) but it now uses a fresh random password every run (never `"tabssh123"`) and the generated release notes carry an explicit "NOT an update — throwaway signing key" warning telling users to uninstall the previous devel build, instead of implying update compatibility that doesn't exist
- [x] metadata/io.github.tabssh.yml — fdroidRelease packages prebuilt `.so` (SPICE) and mosh binaries; F-Droid policy forbids prebuilt binaries — FIXED 2026-08-19: the F-Droid recipe's `prebuild:`/`build:` steps never actually invoked `scripts/fetch-{mosh,spice,tor}-binaries.sh`, so real F-Droid-infra builds were already binary-free by omission; that's now enforced at the build level too — `app/build.gradle`'s `packaging.jniLibs.excludes` drops `libmosh-client.so`/`libtabssh_native.so`/`libtor.so` whenever `fdroidBuild=true`, so a smoke build that already populated `jniLibs/` can't leak them into the flavor either. `metadata/io.github.tabssh.yml`'s Description now discloses the three excluded features and their runtime fallbacks (Termux/mosh handoff, VNC, external Tor proxy) instead of silently overclaiming full feature parity

## Pass 6: Code Flow Trace
- [ ] Verify the Fragment keep rule seeds all 8 settings fragments and that @Serializable classes outside the explicitly-kept packages survive R8 (needs a fresh assembleDevel usage.txt/seeds.txt)

## Carried over from the 2026-08-07 audit (still unresolved)
- [x] Raw control bytes in string literals across TermuxBridge/MultiRowKeyboardView/VncConsoleChannel/TaskerWorker — FIXED 2026-08-19: all 45 raw bytes replaced with `\uXXXX` escapes (ESC, CTRL_C, CTRL_D, CTRL_Z); verified byte-for-byte against the originals and re-scanned clean
- [x] Redundant FQNs in TabTerminalActivity/SSHTab — FIXED 2026-08-19: ~314 sites simplified to imports; `com.google.android.material.R` stays fully qualified in TabTerminalActivity (collides with the app's own `R`)

## Completed
- app/src/test/.../ANSIParserTest.kt, TerminalBufferTest.kt — updated the two CUP assertions for the new origin-mode-aware setter and added regression tests for IRM insert, DECOM clamping, DECTCEM, and split multi-byte UTF-8
- app/src/**/*.kt (31 files) — doc comments referenced a `PLAN.AI.md` that no longer exists (32 dangling references); stripped the step/phase citations, keeping the prose
- terminal/emulator/ANSIParser.kt — `processInput` decoded each socket chunk with `String(data, UTF_8)`, so any multi-byte character split across a read boundary rendered as U+FFFD; now a stateful CharsetDecoder carries the incomplete trailing bytes into the next chunk
- terminal/emulator/{ANSIParser,TerminalBuffer}.kt — DECTCEM (`ESC[?25h/l`) was logged as an unhandled DEC private mode; cursor visibility is now buffer state with a getter
- terminal/emulator/TerminalBuffer.kt — `insertMode` (IRM) was accepted by `ESC[4h` and then ignored, so printing overwrote instead of shifting the line right
- terminal/emulator/{ANSIParser,TerminalBuffer}.kt — `originMode` (DECOM) was stored but never read; CUP/HVP now positions relative to the DECSTBM region and clamps to it
- hypervisor/libvirt/LibvirtApiClient.kt — a deadline exit returned the partial buffer as a successful result, so callers parsed truncated virsh output into VM state; a timeout now throws LibvirtException
- ssh/connection/SSHSessionManager.kt — `cleanup()`'s bare `runBlocking { closeAllConnections() }` could block the caller indefinitely on an unresponsive server; now bounded by CLEANUP_TIMEOUT_MS with `scope.cancel()` as the backstop
- ui/tabs/SSHTab.kt — the verbose `zellij list-sessions` fallback lists dead sessions as `(EXITED - attach to resurrect)`; those were offered in the attach picker even though `zellij attach` fails on them
- ui/activities/LibvirtManagerActivity.kt — the snapshot dialog was never tracked or dismissed in `onDestroy` (Proxmox/VMware were already fixed), leaking its window
- docker/docker-compose.yml — `GRADLE_USER_HOME=/root/.gradle` contradicted AI.md PART 4's project-scoped `/workspace/.gradle`; repointed the named volume and env var, and added the PART 4 wrapper-seed step so `./gradlew` never re-downloads Gradle
- app/build.gradle + docker/Dockerfile.build — `buildToolsVersion` was unpinned while the Dockerfile explicitly installed only build-tools 34.0.0; pinned to 35.0.0 and added it to the image's sdkmanager list so an AGP bump cannot trigger a lazy mid-build SDK download
- README.md — workflow inventory omitted build-toolchain.yml and spice-libs.yml
- Makefile:9 — VERSION grep matched the explanatory comment above defaultConfig and carried backticks recipes ran as command substitution; anchored to the declaration
- terminal/emulator/TerminalBuffer.kt — `setScrollRegion` threw on `ESC[999;5r` (remote-triggerable crash); scroll region now clamped and honored by scrollUp/advanceLine/insertLine/deleteLine; alternate-screen and non-full-height regions no longer leak rows into scrollback
- terminal/emulator/ANSIParser.kt — CSI A/B/C/D passed cursor deltas on the wrong axis; CSI L/M scrolled the whole screen instead of insert/deleteLine at the cursor
- terminal/emulator/TerminalEmulator.kt — reconnect race nulled out the newer readJob
- ui/views/TerminalView.kt — same-instance bridge rebind leaked a duplicate listener each time
- hypervisor/console/rfb/PixelFormat.kt — `skipBytes(3)` could short-skip and desync the whole RFB session
- hypervisor/xcpng/XenOrchestraApiClient.kt — an already-closed Response was returned to every caller when re-auth failed; webSocket/eventListener/isWebSocketConnected made @Volatile
- sftp/SFTPManager.kt — unchecked `skip()` corrupted resumed transfers (now `skipFully`); `disconnect()` tore the channel down outside `channelMutex`; CancellationException was reported as TransferResult.Error
- cluster/ClusterCommandExecutor.kt — generic catch swallowed CancellationException, defeating `cancelAll()`
- cloud/OciCloudClient.kt, network/portknock/PortKnocker.kt — cancellation reported as an action/knock failure
- ssh/connection/TelnetConnection.kt — EscapingOutputStream writes bypassed `writeLock`, so keystrokes could splice into an IAC negotiation packet
- docker/transport/RemoteExecOps.kt, performance/MetricsCollector.kt — cross-thread caches made @Volatile
- pairing/QrPayloadCodec.kt — CBOR decoder allocated `ArrayList(n)`/`LinkedHashMap(n)` from an unvalidated 64-bit length (OOM from a 5-byte QR) and had no recursion bound
- ui/activities/PinLockActivity.kt — verify mode with a missing hash waved the caller through while leaving `app_lock_enabled` set; now clears the flag so the UI stops claiming the app is locked
- ui/activities/MainActivity.kt — "Copy Debug Logs" pointed release users at a Settings category that is hidden in release builds
- utils/logging/Logger.kt — export header and log banners still said "Debug Mode" after the DEBUG_MODE→DEBUG_LOG rename
- app/build.gradle — removed commented-out `applicationIdSuffix`, replaced the dead `isProductionRelease` computation with a literal `false` (AI.md PART 4: only `devel` enables the Debug Log), and dropped the `postprocessing` block that conflicted with `minifyEnabled`/`proguardFiles`/`shrinkResources`
- build.gradle — deprecated eager `task clean(type: Delete)` on `rootProject.buildDir` → `tasks.register` on `layout.buildDirectory`
- .github/workflows/{release,beta}.yml — `${{ secrets.KEYSTORE_BASE64 }}` was spliced into the shell script body; now passed via `env:` with an emptiness guard
- .github/workflows/{release,beta,development}.yml — SBOM `find` `-o` precedence let an unrelated bom.json win; parenthesised and made a missing SBOM fail the job
- .github/workflows/release.yml — `dependencyCheckAnalyze` scanned the dependency-free root project so `failBuildOnCVSS` could never fire; now `dependencyCheckAggregate`
- .github/workflows/ci.yml — stale release-artifact names in the summary
- docker/docker-compose.yml — all three services referenced `dockerfile: Dockerfile`, which does not exist (`Dockerfile.build`)
- Makefile — `_ensure-image` added to `.PHONY`
- metadata/io.github.tabssh.yml — dead `ndk: r26b` pin and `ndk.dir` prebuild line (no native toolchain runs during the APK build)
- scripts/clean-build.sh, scripts/install-to-device.sh — no `set -uo pipefail`, and relative `rm -rf`/APK paths resolved against the caller's CWD
- scripts/notify-release.sh — commented-out Matrix/Mastodon curl blocks replaced with real implementations (MATRIX_ROOM/MATRIX_HOMESERVER, MASTODON_HOST); fixed temp path → `mktemp -d`
- scripts/prepare-fdroid-submission.sh — predictable temp path that was then `rm -rf`'d → `mktemp -d`
- scripts/android-emulator.sh — error message hardcoded `/tmp/${name}.log` while the log actually goes to `${TMPDIR:-/tmp}/tabssh-android/`
- README.md — minSdk claimed API 21 (actual 24); told contributors to run `./gradlew test` on the host; stale stats (Kotlin files, LOC, activities, fragments, services, themes, Room version); nonexistent docker-compose service `build`; `Dockerfile` vs `Dockerfile.build`
- storage/database/entities/Identity.kt — stale comment referencing minSdk 21

## Evaluated and rejected as non-issues
- VMwareApiClient — no `ContinueRetrievePropertiesEx` handling, but every `RetrievePropertiesEx` call targets a single explicit MoRef with no traversal spec (0 `selectSet` in the file), so a continuation token is unreachable
- ANSIParser SGR 39/49 → palette indices 7/0 — these are exactly `CharacterAttributes`' own defaults and the fill value used by every `TerminalChar` blank, so the mapping is self-consistent; the buffer has no theme concept to defer to
- ui/tabs/TerminalPagerAdapter.onViewRecycled; sha256/sha512 checksum "asymmetry" (symmetric on inspection); development.yml ephemeral keystore (documented fork fallback)
