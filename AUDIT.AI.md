# TabSSH Android Audit

Started: 2026-06-01
Updated: 2026-06-02 — targeted deep-dive on SSH connection lifecycle, credential
storage, port forwarding (scope from session-2 audit prompt).

## Fixed in pass 1 (2026-06-01)

- [x] **SFTP upload stream leak** — `SFTPManager.performUpload()` did not wrap the stream returned by `ChannelSftp.put()` in `.use{}`. If `transferWithProgress()` or the inner `localFile.inputStream().use{}` threw, the SFTP put stream leaked (one per failed transfer). Fixed by wrapping `outputStream.use{}` around the upload body.
- [x] **SFTP download stream leak** — `SFTPManager.performDownload()` did not wrap `ChannelSftp.get()` result in `.use{}`. If `localFile.outputStream()`/`appendOutputStream()` threw, the SFTP input stream leaked. Fixed by wrapping `channel.get(...).use{}` around the download body.
- [x] **ClipboardHelper Activity context leak** — `ClipboardHelper.copy()` captured the caller's Activity context inside a `Handler.postDelayed` Runnable scheduled up to the user-configured timeout (potentially minutes). If the Activity finished within that window, it was retained until the timer fired. Fixed by switching to `context.applicationContext` for both the system service lookup and the delayed clear.

## Fixed in pass 2 (2026-06-02) — SSH lifecycle + credential storage

- [x] **Port forwarding bind exposed forwarded port on LAN** — `PortForwardingManager.startTunnel()` used the 3-arg `setPortForwardingL(lport, host, rport)` and the no-bind `setPortForwardingL(port.toString())` for SOCKS. JSch's default `bind_address` resolution is configuration-dependent on Android networking stacks; under some setups this binds the local port to all interfaces, letting any device on the same Wi-Fi/cellular hotspot LAN relay traffic into the SSH-target network. Fixed by passing an explicit `"127.0.0.1"` bind to all three forward types (local, remote-target, SOCKS).
- [x] **REMOTE_FORWARD bind tracking comment was wrong** — Tunnel data class stored `remoteHost = "0.0.0.0"` with the comment "Listen on all interfaces". `setPortForwardingR` bind on the remote side is controlled by the sshd's GatewayPorts policy, not this field. Replaced with a descriptive constant and made the call site pass `null` so the server's own default ("localhost") wins unless GatewayPorts=yes is explicitly set server-side.
- [x] **`applyAdvancedSettings` (SSH config import forwards) bound to wildcard** — same JSch `setPortForwardingL` shape used for forwards parsed from `~/.ssh/config` JSON. Same fix applied.
- [x] **Jump-host port forward bound to wildcard** — `setupJumpHost` used `jumpSession.setPortForwardingL(0, host, port)` for the tunnel that the next JSch session connects through. Without an explicit bind, this could let nearby LAN devices reach the target host via the jump-host tunnel without authenticating to the phone. Bound to `"127.0.0.1"`.
- [x] **`executeCommand` channel leak on exception** — `channel.disconnect()` was only on the happy path inside `try{}`. Any timeout, connect error, or read I/O exception left the JSch ChannelExec open on the Session. Moved disconnect into `finally{}`.
- [x] **SSH private key DER not zeroed after encryption** — `KeyStorage.storePrivateKey()` left the PKCS#8-encoded `privateKey.encoded` ByteArray in heap until GC; same for `decryptedKeyBytes` after reconstruction in `retrievePrivateKey()`. Added `fill(0)` after the bytes were no longer needed.
- [x] **JSch byte buffers leaked on auth path** — `SSHConnection.setupAuthentication` produced a plaintext OpenSSH/PEM `jschBytes` ByteArray, passed it to `jsch.addIdentity` or wrote it to a `cacheDir/temp_key_<id>` file, then never zeroed the heap buffer; the temp file was `delete()`-ed (inode unlink only, not overwrite). For a memory or filesystem dump, the key material lingered. Fixed by zeroing `jschBytes` and `passphraseBytes` in `finally{}`, plus overwriting the temp file with zeros before delete.
- [x] **Agent-forwarding identity load leaked plaintext key + passphrase** — `populateAgentIdentities` iterated every stored key, decrypted to plaintext bytes, added to JSch, and never zeroed. Fixed by tracking both buffers per iteration and `fill(0)` in `finally{}`.
- [x] **Jump-host key bytes not zeroed** — `setupJumpHost` PUBLIC_KEY path called `addIdentity` with `jschBytes` and never zeroed. Fixed.
- [x] **`KeyStorage.storePrivateKey` JSch-bytes cache step leaked plaintext** — `toJSchKeyBytes()` produces a fresh plaintext SSH key buffer that was passed to `storeJSchBytes()` (encryption) and discarded without zeroing. Same in the import path. Fixed both with tracked `var jschBytes: ByteArray?` and `finally{ jschBytes?.fill(0) }`.

## Verified clean / acceptable (pass 2)

- `android:allowBackup="false"` and `android:fullBackupContent="false"` in AndroidManifest.xml — Keystore-backed SharedPreferences will not be included in any auto-backup.
- AES-GCM IV reuse: `Cipher.init(ENCRYPT_MODE, key)` with no IV argument is correct; AndroidKeyStore generates a fresh random IV per call (the `setRandomizedEncryptionRequired(true)` builder flag enforces this) and `cipher.iv` returns the freshly-generated IV. No fixed/repeated IV.
- Android Keystore key spec: `BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE` + `setRandomizedEncryptionRequired(true)`. Biometric variant adds `setUserAuthenticationRequired(true)` + `AUTH_BIOMETRIC_STRONG`. Correct for the documented threat model.
- Hypervisor credential plaintext column has a lazy-migration path (`HypervisorPasswordStore.retrieve`) — on first read after upgrade, plaintext moves to Keystore and the DB column is blanked. New rows never carry plaintext.
- OCI account credentials follow the same `oci_private_key_account_*` / `oci_passphrase_account_*` Keystore-only model with lazy migration from per-profile legacy aliases.
- `SecurePasswordManager.clearPassword` is suspend + `Dispatchers.IO` for the Keystore HAL round-trip — no ANR risk.
- `NetworkAwareReconnector` cancels all its jobs in `cancel()` and is invoked from `SSHConnection.disconnect()`. No timer leak.
- `SSHSessionManager.connectToServer` catches `CancellationException` and calls `closeConnection` so a cancelled connect doesn't leave an orphan authenticated session.
- `SSHConnection.disconnect()` is the single tear-down path: cancels connectJob/reconnector, closes every channel from `openChannels`, sftpChannel, x11Proxy, session, jumpHostSession, scrubs cachedPassword/cachedPassphrase. No exit path left dangling.
- `setupAuthentication` does not log password/passphrase/key values — only sizes and presence booleans.
- Passwords held as Kotlin `String` (immutable in JVM heap until GC) — flagged as a known JVM limitation rather than a defect; rewriting the password-handling chain to CharArray-everywhere is out of scope for this pass and would require a UI-layer rewrite of `AuthenticationDialog`, `BiometricPrompt` flow, and SharedPreferences decode paths.

## Needs Human Review (architectural / device-test required)

These cannot be conclusively validated from code review alone:

- [ ] **Android Keystore key invalidation behaviour** — `setUserAuthenticationRequired(true)` keys are invalidated by enrolling a new biometric. `SecurePasswordManager.retrieveEncryptedPassword` catches the exception and returns null (with a comment explaining why it deliberately does NOT auto-delete the ciphertext). This is the correct behaviour but should be device-tested on real Android 12+/13/14 hardware before declaring it solved.
- [ ] **JSch bind_address semantics across JSch versions** — the fix here passes `"127.0.0.1"` explicitly. The 4-arg `setPortForwardingL(String bind_address, int lport, String host, int rport)` signature is present in JSch ≥0.1.55 (verified against the version pulled by the project's Gradle dependencies on next clean build); should be re-checked if the JSch dependency is bumped.
- [ ] **Temp key file in `context.cacheDir`** — even with the secure-overwrite added, the cacheDir can be mounted on a flash-translation-layer that wear-levels writes; a forensic image could still recover prior key bytes. The proper fix is the byte-array `addIdentity` variant on every path (no temp file), but the existing comment notes "byte-array variant has Linux quirks". Worth a follow-up to test the byte-array path on Android 13+ and remove the temp-file path entirely if it works.
- [ ] **Multi-tab Session sharing** — `SSHConnection.openChannels` is `Collections.synchronizedSet`; the `openChannels.toList().firstOrNull()` in `closeChannel` is correct under contention. Worth a stress test (10+ rapid tab open/close on the same profile) on device to confirm no orphan channels accumulate.

## Recommended follow-up (pass-1 items still open)

- [ ] **TabTerminalActivity** (3807 lines) — 25+ `lifecycleScope.launch` sites; full per-launch correctness review not done.
- [ ] **TermuxBridge** (919 lines) — terminal byte pump, PTY resize, MoSH pivot. Resource lifecycle around `moshSession` and `updateSize` not fully traced.
- [ ] **TermuxBridge moshSession leak risk** — early-return paths not all verified.
- [ ] **Hypervisor manager activities** — full input-validation and state-machine review not done.
- [ ] **Room DAO query performance** — N+1 patterns / missing indices not reviewed.
- [ ] **RecyclerView adapter correctness** — `notifyDataSetChanged()` vs DiffUtil survey not done.
- [ ] **Bitmap handling** — VNC/RFB framebuffer rendering path not surveyed.

Delete this file once all items above are addressed or the user confirms they are out of scope.
