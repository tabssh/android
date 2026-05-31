# TabSSH TODO

**Last Updated:** 2026-05-31
**Version:** 0.9.1 (pinned via `release.txt` — DO NOT MODIFY without coordinated bump in `app/build.gradle` + F-Droid metadata)

> **Usage rules for AI agents:**
> 1. Open this file at the start of every session that touches 2 or more tasks.
> 2. Update status as you go — mark items shipped the moment they land in a commit.
> 3. Add every bug fix, feature, and doc change to ✅ Recently Shipped with its commit hash.
> 4. Never let this file go more than one session stale.
>
> **Source of truth hierarchy:** `AI.md` (architecture) → `TODO.AI.md` (open work) → `CLAUDE.md` (operating rules). This file tracks what has shipped and what needs to be done.

---

## ✅ Recently Shipped

- **`388f57f69a1e`** 🐛 Fix TOFU cert pin not persisting across sessions + PerformanceFragment host pref wipe — `ProxmoxManagerActivity`, `VMwareManagerActivity`, `XCPngManagerActivity` all now update their in-memory profile/hypervisors list with the captured SHA after persisting to DB, so VMConsoleActivity never receives a stale null pin; `PerformanceFragment.onItemSelected` no longer saves null to `perf_last_connection_id` when spinner resets to position 0.
- **`107d110b183f`** 🔧 CI false-positive security check — exclusion chain in `android-ci.yml` now filters `optString` in addition to `getString` so `CloudAccountManagerActivity`'s `optString("password")` call no longer triggers the hardcoded-password grep rule.
- **`8cd84dd91de1`** 🐛 OCI cloud account TLS cert pin persistence + Proxmox-style card layout — `OciCloudClient` now passes existing `tls_pin` from token JSON to `OciApiClient` and exposes `getCapturedCertSha256()`; `CloudAccountManagerActivity` adds `persistOciCloudPin()` called after every API operation to write updated pin back to Keystore; all hypervisor/cloud VM card buttons relabelled SSH/VNC; `XCPngManagerActivity` VMAdapter rewritten to show SSH + VNC buttons side-by-side for running VMs.
- **`4201ed1dae87`** 🐛 OCI cert pin persistence + MetricsCollector log noise — `OciApiClient` split into `identityClient` + `iaasClient` with separate `CapturedPin` holders; `pinnedCertSha256` stored as semicolon-delimited `"identity_sha;iaas_sha"` pair (backward-compatible); `getCapturedCertSha256()` returns merged set; `OciManagerActivity` persists pins after both `validateCredentials()` and `loadInstances()`; `collectPlatformInfo()` exception downgraded from `Logger.e` → `Logger.d`.
- **`375dad6465de`** ✨ Connection count tracking + cloud instance SSH credentials — `SSHConnectionService.onConnectionEstablished` increments `connection_count` + `last_connected` via atomic SQL (feeds "Frequently Used" sort); long-press on cloud instance row opens SSH credentials dialog (username/password/port/identity); creds stored in `SecurePasswordManager.ENCRYPTED` scoped to cloud account, never written to Room; `handleConnect()` builds transient in-memory `ConnectionProfile` from stored creds.
- **`27155abf89ba`** 🐛 Search on Connections tab fixed — `filterConnections()` now swaps adapter back to flat `adapter` before submitting results; prevents search submitting to detached `groupedAdapter`.
- **`64182c5fcd73`** 🐛 OCI credential persistence + import UX — `editor.apply()` → `editor.commit()` in `SecurePasswordManager`; `saveOrUpdateOciAccount` now checks `storePassword()` return and rolls back DB on Keystore failure; `ociConfigFilePicker`/`ociKeyFilePicker` re-show dialog on cancel/error instead of silently returning; `item_cloud_instance.xml` split into 2 rows of 2 buttons (row 3a: power+connect; row 3b: restart+force restart).
- **`1f994257bd17`** ✨ RequestTTY directive wired — `SSHConnection.kt` reads `advancedSettings["requestTTY"]`; PTY allocated for exec channels only when value is `"yes"` or `"force"`; shell channel always gets PTY. Semantics match OpenSSH.
- **`417d072`** ✨ Cloud Accounts Manager UI + Power Controls (A–H complete) — `CloudAccountManagerActivity`, `CloudInstanceAdapter`, 8 cloud client power actions, Start/Stop toggle, Restart/Force Restart, live instance state; OCI removed from Hypervisors spinner (stays in enum for DB compat); contextual connection failure toasts app-wide.

---

## 🔧 `advancedSettings` — SSH config directive status

| Directive | Parser | Stored | Applied |
|---|---|---|---|
| `ProxyJump` / `ProxyCommand` | ✅ | columns | ✅ |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | JSON | intentionally ignored (mobile keepalive + TOFU dialog own these) |
| `ForwardAgent` / `ForwardX11` / `compression` / `connectTimeout` | ✅ | JSON + columns | ✅ |
| `RequestTTY` | ✅ | JSON | ✅ — exec PTY gated on `"yes"`/`"force"`; shell always gets PTY |

---

## 🧹 Post-v1: Dead code removal

Do not do a broad dead code sweep before v1. The risk/reward is wrong — no user-visible gain, and one missed case (Room entity field, preference key, ProGuard keep rule, reflection target) causes a crash or silent data loss.

**After v1 ships:**
- Full dead code sweep — Android Studio "Inspect Code" + `grep -rn` cross-reference
- Never remove a symbol that appears in a ProGuard keep rule or is reachable via `findPreference("key")` string literals, even if static analysis shows zero references
- `HypervisorProfile.isXenOrchestra` — still actively used in `XCPngManagerActivity`; do not drop before API-type migration is done

---

## 📚 Reference

- `AI.md` — architecture, packages, DB schema, sync, crypto, hypervisors, QR pairing
- `CLAUDE.md` — operational runbook (build commands, commit policy, file locations)
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — F-Droid formatted app description
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)
