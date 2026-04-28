# QR Pairing — Desktop → Mobile Setup

**Status:** Design doc — not yet implemented.
**Last Updated:** 2026-04-28
**Touches:** `tabssh/android` (this repo) + `tabssh/desktop` (Rust desktop app)

---

## Goal

Make it easy to add an existing TabSSH connection from a desktop install to a phone without retyping. The friction we're solving:

- New phone, no existing sync set up
- "Just got this server working on my laptop, want it on my phone for tomorrow's flight"
- Set up a colleague's phone for shared infra fast

## Flow

1. User opens TabSSH on **desktop** → menu → **Pair Phone…**
2. Desktop shows:
   - A modal with a checklist of current connection profiles
   - The user picks which ones to send
   - On confirm, desktop renders a QR + a **6-digit code** + a 60-second countdown
3. User opens TabSSH on **phone** → drawer menu → **Pair from desktop…**
4. Phone opens a camera preview, scans the QR
5. Phone prompts the user for the 6-digit code shown on the desktop
6. Phone shows a preview: *"Import N connections from {device_label}?"*
7. User confirms → connections appear in the phone's list

## Non-goals (v1)

- ❌ Bidirectional pairing (mobile → desktop). Direction matters because desktop has filesystem write access for `~/.ssh/authorized_keys`; phones don't.
- ❌ Continuous sync. Use SAF-based cloud sync for that — it's already shipped.
- ❌ Private key transfer. The phone generates its own keypair locally; the desktop side handles `authorized_keys` provisioning out-of-band (or the phone shows its public key and the user pastes it onto the server).
- ❌ Multi-frame animated QR. Single-frame only in v1; if the payload doesn't fit, the user pairs fewer at a time.

---

## Data model

The QR payload, after decryption, is a CBOR-encoded `PairingPayload`:

```
PairingPayload {
  version: u8                    // 1
  expires_at: u64                // unix epoch seconds, ~60s after generation
  device_label: Option<String>   // "Alice's Linux desktop" — shown in confirm dialog
  connections: [ConnectionProfile]
  groups: [Group]                // optional, only those referenced by connections
  identities: [Identity]         // optional, only those referenced by connections
}

ConnectionProfile {
  name: String
  host: String
  port: u16
  username: String
  protocol: enum { SSH, TELNET }
  auth_type: enum { PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE }
  // NO password, NO private key. Public-key fingerprint + comment only.
  ssh_key_public: Option<String>  // OpenSSH-format `ssh-ed25519 AAAA…` line
  ssh_key_fingerprint: Option<String>  // SHA-256 fingerprint for verification
  // Cosmetic + behavioral fields (all current ConnectionProfile non-secret fields):
  color_tag: Option<u32>
  group_name: Option<String>
  identity_name: Option<String>
  terminal_type: String
  compression: bool
  keep_alive: u32
  env_vars: Option<String>        // multi-line KEY=value
  post_connect_script: Option<String>
  use_mosh: bool
  agent_forwarding: bool
  x11_forwarding: bool
  // Jump host config (no jump-host secrets either):
  proxy_host: Option<String>
  proxy_port: Option<u16>
  proxy_username: Option<String>
  proxy_auth_type: Option<String>
}
```

**CBOR over JSON** — ~30% smaller, matters near the QR capacity ceiling. Use `ciborium` on the Rust side and `co.nstant.in.cbor` (or roll a minimal codec) on the Android side.

### Capacity reality

QR Code at error-correction level **L** (max capacity):
- **Alphanumeric mode**: 4,296 chars
- **Binary mode**: 2,953 bytes

Typical encrypted-payload sizes:

| Content | Bytes (after AES-GCM + base64) |
|---------|-------|
| 1 connection, no key | ~250 |
| 1 connection + Ed25519 public key | ~400 |
| 5 connections, no keys | ~700 |
| 5 connections + 5 Ed25519 public keys | ~1,400 |
| 10 connections + RSA-4096 public keys | ~2,800 (close to ceiling) |

Cap v1 at 10 connections per QR. Surface a clear error if the user tries to send more.

---

## Encryption

### Threat model

| Threat | Likelihood |
|--------|-----------|
| QR photographed by anyone with line-of-sight to the desktop screen | **HIGH — assume QR is public** |
| Brute-force of the 6-digit code (1M possible values) | High if the QR is captured |
| Replay across sessions | Low — TTL caps risk |
| Camera shoulder-surf (the 6-digit code) | Medium — same threat as 2FA codes; accepted |
| MITM on the QR scan | Effectively zero — phone reads from screen directly |

### Scheme

1. Desktop generates:
   - A 6-digit numeric **code** (`000000` … `999999`)
   - A 16-byte random **salt**
   - A 12-byte random **nonce** (AES-GCM IV)
2. Desktop derives a 32-byte symmetric key:
   ```
   key = Argon2id(password=code, salt=salt, m=64MB, t=3, p=1)
   ```
3. Desktop encrypts the CBOR-encoded `PairingPayload`:
   ```
   ciphertext = AES-256-GCM(key, nonce, plaintext)
   ```
   (The auth tag is appended to the ciphertext per AES-GCM convention.)
4. Desktop builds a CBOR-encoded `QrPayload`:
   ```
   QrPayload {
     version: u8       // 1
     salt: [u8; 16]
     nonce: [u8; 12]
     ciphertext: bytes
   }
   ```
5. Desktop base64-encodes `QrPayload` and renders the QR in **byte mode**.
6. Desktop displays the 6-digit code separately as a label.

**Why base64 in byte mode rather than raw bytes:** ZXing's `ScanContract` returns the scanned content as a Kotlin `String`. Round-tripping arbitrary bytes through a `String` is fragile (encoding mangling). Standard base64 is ASCII-clean — survives the `String` boundary intact. The ~25 % capacity hit is fine since real payloads sit comfortably under the limit. The codec accepts both standard base64 and url-safe base64 with or without padding (see `QrPayloadCodec.base64Decode`).

### Argon2id parameters

`m=64 MB, t=3, p=1` chosen so:
- **Brute-force cost:** 1 M codes × ~1 second per derivation = ~12 days on a single core. With a 60-second TTL, a brute-forcer would need 1,200,000+ cores running concurrently to make a dent before the QR expires. Combined with the per-decrypt entropy of the AES-GCM auth tag, this is infeasible.
- **Legitimate cost on phone:** ~1 second on a mid-range Android device — noticeable but acceptable.

If pure-Rust Argon2id is unavailable on Android (it isn't bundled), use `org.bouncycastle:bcprov-jdk18on` which is already a dependency.

---

## QR rendering

### Desktop side

- Crate: `qrcodegen` (pure Rust, no deps)
- Render to a 256×256 monochrome bitmap, then upscale to whatever the dialog needs
- Use ECC level **L** (low) to maximise data capacity
- Use **alphanumeric** encoding mode (base64 fits in alphanumeric set)

### Mobile side

- Library: **ZXing** (`com.journeyapps:zxing-android-embedded:4.3.0` + `com.google.zxing:core:3.5.2`)
- **Why ZXing not ML Kit:** ML Kit Barcode pulls in `com.google.android.gms:play-services-base` even in its "bundled" variant. TabSSH targets de-Googled ROMs as a first-class platform, so we cannot have any Google Play Services dependency. ZXing is pure Java with zero Google deps and has been the de-facto open-source QR library for over a decade.
- API: `ScanContract` + `ScanOptions` from `com.journeyapps.barcodescanner` — integrates with the Android Activity Result API, returns scanned text as a `String` via `ScanIntentResult.getContents()`.
- Detect single QR codes only (no aggregation needed since v1 is single-frame)

---

## UX

### Desktop (Rust + egui)

**Trigger:** menu → File → Pair Phone…

**Dialog state machine:**

```
[Idle] → user clicks Pair
[Selecting] → checkbox list of connections, "Pair" button
[Generating] → "Generating QR…" (~100 ms)
[Active] → QR displayed, 6-digit code, 60 s countdown, "Cancel" / "Done"
[Expired] → QR fades, "Generate new code" button
```

**Layout when active:**

```
┌─────────────────────────────────┐
│ Pair Phone                      │
├─────────────────────────────────┤
│                                 │
│  [QR code — 256×256]            │
│                                 │
│  Code: 4 8 2 7 1 9              │
│  Expires in 47s                 │
│                                 │
│  Sending: 3 connections         │
│  • prod-web-1                   │
│  • prod-db                      │
│  • lab-jumphost                 │
│                                 │
│  [Generate new]  [Cancel]       │
└─────────────────────────────────┘
```

### Mobile (Kotlin + Android)

**Trigger:** drawer menu → Pair from desktop…

**Activity:** new `ImportFromQrActivity`

**State machine:**

```
[CheckingPermissions] → request CAMERA if not granted
[Scanning] → CameraX preview + ML Kit barcode detection
[CodeEntry] → "Enter the 6-digit code from your desktop" + numeric keypad
[Decrypting] → "Decrypting…" (~1 second for Argon2)
[Confirming] → preview of incoming connections + "Import" / "Cancel"
[Importing] → "Importing…"
[Success] → "Imported N connections from {device_label}" → finish, return to main
[Failure] → "Wrong code" / "QR expired" / "Wrong version" → retry or abort
```

**Failure modes the UI must handle:**

| Failure | UI text |
|---------|---------|
| `version > 1` in QrPayload | "This QR was created by a newer TabSSH. Update your phone app." |
| `expires_at < now` after decrypt | "This QR has expired. Ask the desktop to generate a new one." |
| AES-GCM auth tag mismatch | "Wrong code. Try again. (3 attempts left.)" with retry counter |
| 3 wrong code attempts | Abort with "Pairing cancelled. Generate a new QR." |
| CBOR decode error | "Couldn't read pairing data. Make sure both apps are up to date." |
| No connections in payload | "This QR has no connections to import." |
| Camera permission denied | "Camera access is required to scan QRs." with a settings shortcut |

**3-attempt limit:** important — without it, a 6-digit code with no rate-limit is brute-forceable in seconds locally.

---

## Security review

| Threat | Mitigation |
|--------|-----------|
| Photographed QR | Encrypted with AES-256-GCM under a key derived from a code communicated out-of-band |
| Brute-force the code | Argon2id (m=64MB, t=3) makes 1M attempts take days even with a botnet; 60-second TTL slams the window shut; 3-attempt local rate limit on the phone |
| Replay attack | TTL via `expires_at` inside the encrypted payload; nonce is random per-payload |
| Code shoulder-surfing | Same threat model as 2FA codes — accepted; the QR-on-screen is the second factor |
| Compromised device on either side | Out of scope — assume both endpoints are healthy |
| MITM on the QR scan | None — the QR is rendered on a screen the user trusts and the camera reads it directly |
| Tampered QR (someone re-renders a QR with a different payload) | Won't decrypt — wrong key |

**What this scheme does NOT protect:**

- Confidentiality if the user types the code into a malicious clone of TabSSH on the phone. (Same threat as any other secret entry.)
- Confidentiality if the desktop is compromised — the cleartext payload is on disk in `~/.config/tabssh/` anyway.

---

## Implementation tasks

### Desktop (Rust)

- [ ] Add deps: `qrcodegen`, `ciborium`, `argon2`, `aes-gcm`, `rand`, `base64`
- [ ] `src/pairing/payload.rs` — `PairingPayload`, `ConnectionProfile` (subset, no secrets), serde encode
- [ ] `src/pairing/encrypt.rs` — generate code/salt/nonce; Argon2id; AES-GCM encrypt; serialise QrPayload
- [ ] `src/pairing/qr.rs` — render `QrPayload` (base64url) → QR bitmap via `qrcodegen`
- [ ] `src/ui/pairing_dialog.rs` — egui dialog: profile checklist + state machine + QR display + countdown + generate-new-code button
- [ ] Wire menu entry: File → Pair Phone…
- [ ] Tests: round-trip encrypt/decrypt with known test vectors (commit them — mobile side reuses)
- [ ] **Estimate: ~2 days**

### Mobile (Kotlin / Android) — ✅ implemented 2026-04-28

- [x] Add deps: ZXing (`com.google.zxing:core:3.5.2` + `com.journeyapps:zxing-android-embedded:4.3.0`) — `app/build.gradle`
- [x] BouncyCastle Argon2 — already had `org.bouncycastle:bcprov-jdk18on:1.77`
- [x] `app/src/main/java/io/github/tabssh/pairing/PairingPayload.kt` — data classes + sealed result hierarchy
- [x] `app/src/main/java/io/github/tabssh/pairing/QrPayloadCodec.kt` — minimal hand-written CBOR decoder (~120 lines, no library) + base64 unwrap with android.util.Base64 (API 21+ compatible)
- [x] `app/src/main/java/io/github/tabssh/pairing/PairingDecryptor.kt` — Argon2id (m=64MiB, t=3, p=1) + AES-256-GCM via javax.crypto + TTL/version validation
- [x] `app/src/main/java/io/github/tabssh/ui/activities/ImportFromQrActivity.kt` — full state machine, dialog-driven UI, 3-attempt code retry
- [x] `app/src/main/res/layout/activity_import_from_qr.xml` — minimal shell (camera lives in ZXing's CaptureActivity)
- [x] `app/src/main/java/io/github/tabssh/pairing/PairingImporter.kt` — name-based group/identity dedupe, fresh-UUID connection inserts (never overwrite)
- [x] Drawer menu entry — `drawer_menu.xml` "Pair from Desktop (QR)" in Import / Export group
- [x] Camera permission — `AndroidManifest.xml` + runtime `RequestPermission` ActivityResultContract
- [x] Activity registration in `AndroidManifest.xml`
- [x] MainActivity drawer click handler (`R.id.nav_pair_from_desktop`)
- [ ] Tests with desktop-generated vectors (waiting on desktop side — see desktop/CLAUDE.md)

### Shared

- [ ] Pin CBOR field tags in a small `pairing-spec` markdown table — both sides reference it
- [ ] Commit at least 3 test vectors (different connection counts) so cross-platform regressions surface immediately
- [ ] Decide v2 forward-compat rules (the `version` byte negotiates)
- [ ] **Estimate: ~½ day**

**Total v1 estimate: ~5–6 days**

---

## Open questions

1. **Public keys ride along, or generate-on-phone?** Recommended: ride along (so the user has a working OpenSSH-format public key after import) but **only public**. Phone never sees private. If the user wants the phone to authenticate with the same key, they re-import the private half via the existing key-management flow.
2. **Snippets / themes / workspaces too, or v1 connections-only?** Recommended: connections + groups + identities in v1. Snippets and themes can wait — they're independent of the connection profiles and add payload weight.
3. **Should the desktop write the phone's public key to `authorized_keys` automatically?** Out of scope for v1. Possibly v2 — desktop would need an SSH client active to do this safely, and we'd need a confirmation step.
4. **Verify-on-desktop step?** Phone shows a 4-digit checksum the user reads back to the desktop, desktop confirms. Adds friction; reduces "wrong device scanned the QR" risk. Defer to v2.

---

## Alternatives considered

| Option | Why rejected for v1 |
|--------|---------------------|
| **Local-network handshake** (mDNS + HTTP) | Both devices must be on the same wifi. Corporate networks, hotel APs with client isolation, mismatched SSIDs all break it. QR works zero-network. |
| **NFC tap** | Only Android phones with NFC. Most desktops have no NFC reader/writer. |
| **`tabssh://` URL via clipboard / SMS / Signal** | Copy-paste between devices is awkward; messaging apps mangle URLs; cleartext URL on third-party servers is worse than an encrypted QR on a local screen. |
| **SAF cloud sync (existing)** | Right tool for "I want my data on all my devices forever". Wrong tool for "I just want this one connection on my phone, today". |
| **USB tether + adb push** | Works for developers; useless for users. |
| **Bluetooth pairing** | Pairing prompts on both sides, drivers, "is my Bluetooth on?" — way more friction than a camera. |

---

## References

- `qrcodegen` Rust crate — pure Rust QR encoding
- `ciborium` Rust crate — CBOR codec
- `argon2` Rust crate — RFC 9106 Argon2id
- `aes-gcm` Rust crate — RustCrypto AES-GCM
- ML Kit Barcode Scanning (Android, bundled-model variant — works without Google Play Services)
- CameraX (`androidx.camera`) — Android Jetpack camera
- BouncyCastle `bcprov-jdk18on` — already in TabSSH Android deps; provides Argon2id
- ISO/IEC 18004:2015 — QR Code spec
- RFC 9106 — Argon2 KDF parameters
