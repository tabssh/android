# Project Audit

Started: 2026-08-13

## Pass 1: Security
- [ ] app/build.gradle:150,152 — release signing passwords fall back to plaintext `"tabssh123"`; development.yml repeats it (RED FLAG: changing signing identity is irreversible for shipped installs — needs user decision)
- [ ] storage/database/entities/HypervisorProfile.kt:44 — `password` is a plaintext DB column, violating AI.md PART 0/PART 6 ("DB columns never hold secrets"). Needs migration to `SecurePasswordManager` plus a Room migration that clears the column (RED FLAG: schema + credential-storage change with a user-visible re-entry step)
- [ ] backup/BackupManager.kt — backup archive can include secrets without a mandatory user password (AI.md PART 6 requires password-encrypted secret export)
- [ ] sync/encryption/SyncEncryptor.kt:123 — PBKDF2-HMAC-SHA256 100k. AI.md PART 6 prefers Argon2id "where available via BouncyCastle" (BC 1.79 is a dependency). Upgrading changes the `TABSSH_SYNC_V2` on-disk format and needs a v3 header with a v2 read path (RED FLAG: data-format contract change)

## Pass 2: Code Quality
- [ ] 183 inline (same-line) comments across 31 backend files; 191 more across ui/, terminal/, themes/ — spec requires comments above the code
- [ ] app/build.gradle — `BuildConfig.DEVEL` and `BuildConfig.BUILD_EPOCH` declared but read by nothing
- [ ] 40 app classes fully removed by R8 (see usage.txt) — unwired features vs genuine dead code triage
- [ ] ui/ hardcoded user-visible strings (~25 Toasts in SettingsActivity plus 10 other files) and hardcoded colors (TerminalView.kt:91,1029 etc.)

## Pass 3: Logic

## Pass 4: Documentation

## Pass 5: Spec Compliance
- [ ] .github/workflows/development.yml — throwaway keystore generated per run when KEYSTORE_BASE64 is unset; daily devel APKs from forks cannot update each other
- [ ] metadata/io.github.tabssh.yml — fdroidRelease packages prebuilt `.so` (SPICE) and mosh binaries; F-Droid policy forbids prebuilt binaries

## Pass 6: Code Flow Trace
- [ ] Verify the Fragment keep rule seeds all 8 settings fragments and that @Serializable classes outside the explicitly-kept packages survive R8 (needs a fresh assembleDevel usage.txt/seeds.txt)

## Carried over from the 2026-08-07 audit (still unresolved)
- [ ] Raw control bytes in string literals across TermuxBridge/MultiRowKeyboardView/VncConsoleChannel/TaskerWorker
- [ ] Redundant FQNs in TabTerminalActivity/SSHTab

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
