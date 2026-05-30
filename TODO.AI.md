# TabSSH TODO

**Last Updated:** 2026-05-19
**Version:** 0.0.9 (pinned via `release.txt` — DO NOT MODIFY without coordinated bump in `app/build.gradle` + F-Droid metadata)

> **Usage rules for AI agents:**
> 1. Open this file at the start of every session that touches 2 or more tasks.
> 2. Update status as you go — mark items shipped the moment they land in a commit.
> 3. Add every bug fix, feature, and doc change to ✅ Recently Shipped with its commit hash.
> 4. Never let this file go more than one session stale.
>
> **Source of truth hierarchy:** `AI.md` (architecture) → `TODO.AI.md` (open work) → `CLAUDE.md` (operating rules). This file tracks what has shipped and what needs to be done.

---

## ✅ Recently Shipped

- **`875049147089`** 🐛 Multihost dashboard: `openOrReuseSession()` switched from `connectToServer()` to `connectForMonitoring()` — monitoring sessions no longer appear as "Connected to…" persistent SSH notifications in the notification shade. `SSHSessionManager.connectForMonitoring()` added (§5.1.3): reuses any live session but never starts `SSHConnectionService` or fires `onConnectionEstablished`.

- **`de6fffacf6ff`** 🔒 CI security validation: added `-a` (`--text`) flag to `grep -rE` in `android-ci.yml` to prevent binary-file false-positive on UTF-8 source files (em dash in `IdentitiesFragment.kt`). `VMConsoleActivity.kt` VNC auth log line refactored: password boolean extracted to `hasVncAuth` variable so neither the variable declaration nor the log call matches the `password.*=.*"[^"]{6,}"` pattern.

- **`0681df96de3a`** 🐛 VNC black screen on Proxmox/QEMU vncproxy: `RfbClient` now handles `ServerFence` (type 248) by echoing `ClientFence`, unblocking the server update queue. Also handles `ENC_FENCE` (-312) inline fences inside `FramebufferUpdate` rect lists. Fixed negative-encoding guard (`encoding > 0xFFFF` is always false for negative ints — widened to `|| encoding < 0`). Added `S2C_FENCE`, `C2S_CLIENT_FENCE` constants to `RfbConstants.kt`; corrected `ENC_FENCE` hex comment (0xFFFFFEB8 not 0xFFFFFEC8).

- **`b7952325aa60`** 🐛 Libvirt: stamp `last_connected` on successful SSH connect — every other hypervisor manager called `updateLastConnected()`; libvirt did not; "Last connected" always showed "Never connected".

- **`30ab65557db1`** 🐛 Seven bug fixes: SSH identity spinner beeping/not picking (wrong adapter layout + missing setText in both key spinners); libvirt SSH fallback opening ConnectionEditActivity for new VM profiles instead of auto-connecting with wrong credentials; ProxyJump host key not saving (JSch stored key under `localhost:ephemeralPort`, fixed with `session.setHostKeyAlias(profile.host)`); keyboard setLayout() preserving portraitRowCount and filling extra rows with defaults; notification Disconnect triggering reconnect dialog (added `markIntentionalClose()`/`closeConnectionIntentionally()`); RFB type 204 crashing VNC connection (log and continue instead of throw); VNC keyboard not appearing (added `vncView.requestFocus()` in onConnected).

- **`8b98f60be8a3`** 🐛 Five bug fixes: Proxmox VNC "Pipe closed" after SetDesktopSize rejection (`canRequestResize=false` in both vncproxy paths); libvirt ProxyJump "invalid private key" (PKCS#8 DER → OpenSSH PEM via `getJSchBytesWithFallback()`); PerformanceFragment stats last-host not persisting (`selectedConnection=null` on `onDestroyView()`); status indicator dots always grey (connected=green, disconnected=red); OCI identity: blank region in account list, `.OCI/CONFIG`/`IMPORT FILE` button labels, auto-fill name from config section header, test connection works before saving profile.

- **`1e20b80a0ba8`** 🐛 Keyboard layout editor: preview keys now use `width=0/weight=1` matching `KeyboardRowView` so the preview is dynamically accurate; `refreshAvailableKeys()` subtracts all in-use key IDs from the palette and is called on every `rebuildSurface()` so placed keys disappear from the picker instantly; `addKey()` guard upgraded to global.
- **`730c9bf472d5`** 🎨 Hypervisor manager cards: inline compact action buttons replace tap-for-dialog across all four managers; VM state shown as colored plain text (Running/Stopped/Paused/Restarting); Hard Reset retains confirmation dialog; Libvirt running row adds SSH button calling `directSshToVm()`.
- **`6f27a0d3e0a8`** 🐛 Libvirt SSH fallback: `launchSshToVm()` now populates ProxyJump fields (`proxyType="SSH"`, host/port/username from `HypervisorProfile`) so the VM's private bridge IP is reachable via tunnel; key-auth hypervisors set `proxyKeyId`; password-auth hypervisors cache the hypervisor password `SESSION_ONLY` in `SecurePasswordManager` for the VM profile so `setupJumpHost()` can retrieve it.
- **`142d42804892`** 🎨 Hypervisor manager UI normalization — all four managers (Proxmox, VMware, OCI, QEMU/libvirt) unified: single `EXTRA_HYPERVISOR_ID` pattern (no spinner), shared `item_hypervisor_vm.xml` card, tap-for-action dialogs, consistent `showProgress`/`hideProgress`/`showError` helpers, `MaterialButton` throughout, toolbar title from profile name; VMware fixed from wrong layout; Proxmox/VMware/OCI "Add Server" dialogs removed (HypervisorEditActivity handles that); QEMU/libvirt SSH fallback when VNC not configured — `LibvirtApiClient.getVmIpAddress()` via `virsh domifaddr`, `offerSshFallback()` dialog; QEMU/libvirt edit UI hides password field when SSH key selected (both on interaction and on `loadHypervisor()` pre-population).

- **`7841256b841a`** ✨ Find-in-scrollback overlay bar — floating search bar replaces AlertDialog stub; prev/next nav, live match counter, amber/orange highlights in terminal, scroll-to-match, case-sensitive toggle, Ctrl+Shift+F shortcut, Back key to dismiss; `ScrollbackSearchController`, `TerminalView.SearchMatch` + `drawSearchHighlights()`.
- **`e6586a3350e0`** 🐛 Notification text format + backup preference coverage — CONNECTED notification now always shows `host:port-title` (port was absent when title set); connection-type fallback (mosh/telnet/ssh) when no title; `BackupExporter`/`BackupImporter` `preferences.json` now includes `notifications` + `monitoring` categories; backward-compatible with old backups; `AI.md §10` updated.
- **`34d0a4363bb4`** ✨ Notification system overhaul — SSH sessions grouped under `tabssh_ssh_sessions` parent summary; monitoring alerts grouped under `tabssh_monitoring`; CONNECTED per-host notifications now include "Disconnect" action that launches `ConfirmDisconnectActivity` (transparent dialog, confirms before closing); `SSHConnectionService` private `"ssh_connections"` channel removed and consolidated into `NotificationHelper.CHANNEL_SERVICE`; `postSshGroupSummary()` called on every 30s heartbeat and sweep; `AI.md §13.1` updated.
- **`61dadd4b8f6f`** 🐛 QEMU/libvirt SSH key identity not loaded for generated keys — `generateKeyPair()` never stored JSch bytes; `retrieveJSchBytes()` returned null; key silently not used; now fixed with `getJSchBytesWithFallback()` on `KeyStorage` (shared by libvirt + `SSHConnection`) and `storeJSchBytes()` called at generate time.
- **`ae68921ee89d`** 🐛 QEMU/libvirt auth failure — `autoDeleteOnFailure` in `SecurePasswordManager` silently wiped Keystore credentials on any decryption error; `LibvirtApiClient.connect()` now fails fast with a helpful message instead of opaque SSH auth failure; `LibvirtManagerActivity` shows "Open Settings" dialog on credential miss; `validateFields()` no longer requires password when SSH key identity is selected for LIBVIRT; `saveHypervisor()` skips `store()` call when password is blank (key-only auth path).
- **`0c4af7d4be32`** 📝 Translation drift — 10 missing keys added to `values-es/`, `values-fr/`, `values-de/` (`cluster_progress`, `navigation_drawer_open/close`, `select_connection`, `sync_password_*`, `widget_*_description`).
- **`0c4af7d4be32`** 🔧 ProxyJump verified already wired — `SSHConfigParser.kt:299-302` populates proxy columns at parse time; `SSHConnection.setupJumpHost()` reads them. Stale TODO entry closed.
- **`0c4af7d4be32`** 🔒 Cached SSH credential zeroing — `SSHConnection.clearCachedCredentials()` + `SSHSessionManager.clearCachedCredentials()` called from `TabSSHApplication.onActivityStopped()` when whole app backgrounds. Prevents in-memory password survival across biometric-lock events.
- **`0c4af7d4be32`** ✨ Group badges on flat connection lists — `ConnectionAdapter` shows `"• GroupName"` badge on search result cards; `ClusterCommandActivity.ConnectionSelectionAdapter` shows group badges in multi-select picker. `item_connection.xml` and `item_cluster_connection.xml` updated.
- **`0c4af7d4be32`** ✨ X11 forwarding proxy — `X11Proxy.kt` (`ssh/forwarding/`) binds a `ServerSocket` on port 0 and relays JSch X11 channels to Termux:X11 (Unix socket) or XServer XSDL (TCP :6000). `SSHConnection.applyForwardingFlags()` now passes the dynamic port via `session.setX11Port(proxy.port)`. Non-fatal `X11NoServerException` shown as a Snackbar in `TabTerminalActivity` via `SSHConnection.warnings` `SharedFlow`. Proxy stopped in `disconnect()`.
- **`1d40e3f2`** 🐛 Remove spurious "Serial console unavailable" AlertDialog — VNC fallback is transparent; dialog was confusing noise. Only surface error if VNC itself fails.
- **`1d40e3f2`** 🐛 Fix `%s` showing literally in Settings → Monitoring → Alert cooldown — removed broken `android:summary="%s…"` from XML, added programmatic `SummaryProvider` in `MonitoringSettingsFragment` producing e.g. "1 hour between repeated 'still down' notifications".
- **`1d40e3f2`** ✨ QEMU/libvirt SSH key auth — `HypervisorProfile.sshIdentityId` (DB v34→35, `MIGRATION_34_35`), `LibvirtApiClient.connect()` loads key via `KeyStorage.retrieveJSchBytes()` + `jsch.addIdentity()`, `HypervisorEditActivity` adds SSH Key dropdown (LIBVIRT-only).
- **`b318a560c6aa`** 🐛 Landscape dialog clipping — 11 layout files wrapped in `NestedScrollView`; `dialog_backup_runs.xml` RecyclerView fixed from 0dp+weight (invisible) to 200dp.
- **`a8d50c81c0bd`** 🐛 RFB ExtendedDesktopSize constant wrong — `ENC_EXTENDED_DESKTOP_SIZE` corrected from `-479` to `-308` (0xFFFFFECC), fixing "Pipe closed" VNC crash on resize.
- **`a8d50c81c0bd`** 🐛 `%s` literals in Logging + Sync preferences — `paste_microbin_url`, `paste_lenpaste_url`, `paste_stikked_url`, `sync_frequency` all fixed with `app:useSimpleSummaryProvider="true"`.
- **`3085f504ebc5`** 🐛 Proxmox serial console fallback — `ConsoleWebSocketClient` detects serial-unavailable frame and calls back to `HypervisorConsoleManager` which retries with vncproxy transparently.
- **`2596eeb7`** ✨ Multi-host Dashboard v2 — sysadmin-grade host cards, dashboard groups (independent from connection groups), DiffUtil metric updates, group CRUD, per-host monitor bell.
- **`55386d5b`** 🐛 OCI SSH Connect ephemeral — persistent SSH config dialog backed by `ConnectionProfile.ociInstanceId`; DB v30→v31.
- **`bfa72c87`** 🐛 Proxmox console fails for VMs without serial interface — API-level detection + automatic vncproxy fallback.
- **`(this batch)`** ✨ OCI hypervisor support — all 7 phases shipped; DB v28→v29; `OciApiClient`, `OciSigner`, `OciManagerActivity`, importer, routing.
- **`ea4f687f`** ✨ QR Pairing mobile side — `ImportFromQrActivity`, `PairingDecryptor` (Argon2id + AES-256-GCM), `QrPayloadCodec` (hand-written CBOR), `PairingImporter`.
- **`d714a7b4`** 🔧 `advancedSettings` local/remote/dynamic forwards wired at connect.
- **`05b7dac1`** 🐛 SSH config `RemoteCommand` + `SendEnv` end-to-end; DB v23→v24.
- **`bed586fe`** 🐛 ANR on app update — `initializeCoreComponents()` moved to background scope; log rotation bounded.

---

## 🔶 Cloud Accounts — Manager UI + Power Controls + Edit (active work)

**Goal:** Every cloud account tap opens a full manager screen showing live instance state with
Start / Stop / Restart / Force Restart / Connect actions — identical UX to OciManagerActivity.
Accounts must also be editable after creation.

**Dependency order:** A → B+C (parallel) → D → E+F (parallel) → G → H

---

### A: Data model + interface (do first — everything else depends on this) ☐

**A1** — Create `CloudInstanceState` data class
File: `app/src/main/java/io/github/tabssh/cloud/CloudInstanceState.kt` (NEW)

Fields: `id: String`, `name: String`, `ip: String?`, `privateIp: String?`,
`status: String` (normalized: "running"|"stopped"|"starting"|"stopping"|"rebooting"|"unknown"),
`rawStatus: String` (provider-verbatim, shown in badge),
`region: String?`, `metadata: Map<String,String>` (zone, shape, etc.)

Normalization map (apply in each client):
- running/active/RUNNING → "running"
- off/offline/stopped/STOPPED/TERMINATED → "stopped"
- booting/starting/STARTING/pending → "starting"
- shutting_down/stopping/STOPPING → "stopping"
- rebooting/REBOOTING/rebooting → "rebooting"
- anything else → "unknown"

**A2** — Extend `CloudProvider` interface
File: `app/src/main/java/io/github/tabssh/cloud/CloudProvider.kt`

Add 5 new methods to the existing interface:
```
suspend fun fetchLiveInstances(bearerToken: String): List<CloudInstanceState>
suspend fun startInstance(bearerToken: String, instanceId: String): Boolean
suspend fun stopInstance(bearerToken: String, instanceId: String): Boolean
suspend fun restartInstance(bearerToken: String, instanceId: String): Boolean
suspend fun forceRestartInstance(bearerToken: String, instanceId: String): Boolean
```

**A3** — Add provider factory extension to `CloudProvider.kt`
```kotlin
fun CloudProviderType.newClient(): CloudProvider = when (this) {
    DIGITALOCEAN -> DigitalOceanClient(); HETZNER -> HetznerClient(); LINODE -> LinodeClient()
    VULTR -> VultrClient(); AWS -> AwsEc2Client(); GCP -> GcpComputeClient()
    AZURE -> AzureVmClient(); OCI -> OciCloudClient()
}
```
Replace all 3 existing `when` switches (CloudAccountsFragment, CloudAccountsActivity,
and the new CloudAccountManagerActivity) with `CloudProviderType.fromTag(...)?.newClient()`.

---

### B: Power actions in all 8 clients (parallel, each independent) ☐

Implement `fetchLiveInstances` + the 4 power methods in each client.
All use the same auth scheme already in `fetchInventory` for that client.

**B1 — DigitalOceanClient** (bearer token)
- `fetchLiveInstances`: GET `/v2/droplets?per_page=200`; map `status` (new→starting, active→running, off/archive→stopped)
- `startInstance`: POST `/v2/droplets/{id}/actions` body `{"type":"power_on"}`
- `stopInstance`: POST `/v2/droplets/{id}/actions` body `{"type":"shutdown"}`
- `restartInstance`: POST `/v2/droplets/{id}/actions` body `{"type":"reboot"}`
- `forceRestartInstance`: POST `/v2/droplets/{id}/actions` body `{"type":"power_cycle"}`
- Success: HTTP 201; check `"action"."status" != "errored"`

**B2 — HetznerClient** (bearer token)
- `fetchLiveInstances`: GET `/v1/servers?per_page=200`; map `status` (running→running, off→stopped, starting→starting, stopping→stopping, restarting→rebooting)
- `startInstance`: POST `/v1/servers/{id}/actions/power_on`
- `stopInstance`: POST `/v1/servers/{id}/actions/power_off`
- `restartInstance`: POST `/v1/servers/{id}/actions/reboot`
- `forceRestartInstance`: POST `/v1/servers/{id}/actions/reset`
- Success: HTTP 201

**B3 — LinodeClient** (bearer token)
- `fetchLiveInstances`: GET `/v4/linode/instances?page_size=200`; map `status` (running→running, offline→stopped, booting→starting, shutting_down→stopping, rebooting→rebooting)
- `startInstance`: POST `/v4/linode/instances/{id}/boot`
- `stopInstance`: POST `/v4/linode/instances/{id}/shutdown`
- `restartInstance`: POST `/v4/linode/instances/{id}/reboot`
- `forceRestartInstance`: POST `/v4/linode/instances/{id}/reboot` (Linode has no hard reset — same as restart; note this in a comment)
- Success: HTTP 200

**B4 — VultrClient** (bearer token)
- `fetchLiveInstances`: GET `/v2/instances?per_page=200`; use `power_status` (running→running, stopped→stopped); check `server_status` as fallback
- `startInstance`: POST `/v2/instances/{id}/start`
- `stopInstance`: POST `/v2/instances/{id}/halt`
- `restartInstance`: POST `/v2/instances/{id}/reboot`
- `forceRestartInstance`: POST `/v2/instances/{id}/reboot` (Vultr has no hard reset)
- Success: HTTP 204

**B5 — AwsEc2Client** (AKID:SECRET:REGION packed string, SigV4)
- `fetchLiveInstances`: existing `DescribeInstances` XML call but include all states (remove RUNNING-only filter); map `instanceState.name` (running→running, stopped→stopped, pending→starting, stopping→stopping, shutting-down→stopping)
- **Important:** `CloudInstanceState.id` must carry the EC2 instance ID (`i-xxxx`), not the DNS name
- `startInstance`: GET `?Action=StartInstances&InstanceId.1={id}`
- `stopInstance`: GET `?Action=StopInstances&InstanceId.1={id}`
- `restartInstance`: GET `?Action=RebootInstances&InstanceId.1={id}`
- `forceRestartInstance`: GET `?Action=RebootInstances&InstanceId.1={id}` (EC2 has no separate force restart — same as reboot; comment this)
- Success: HTTP 200 with `<return>true</return>`

**B6 — GcpComputeClient** (service-account JSON, JWT auth)
- `fetchLiveInstances`: existing `/aggregated/instances` call but include all states; map `status` (RUNNING→running, TERMINATED/STOPPED→stopped, STOPPING→stopping, STAGING/PROVISIONING→starting)
- **Important:** store `zone` (last segment of `zone` field) in `metadata["zone"]` and instance `name` in `id` (GCP power URLs use name + zone, not numeric ID)
- Access token: re-request for each call (existing pattern)
- `startInstance(name)`: POST `/compute/v1/projects/{proj}/zones/{meta["zone"]}/instances/{name}/start`
- `stopInstance(name)`: POST same base + `/stop`
- `restartInstance(name)`: POST same base + `/reset` (GCP reset = hard power cycle; graceful restart requires SSH to OS — note this in comment)
- `forceRestartInstance(name)`: same as restartInstance for GCP
- Success: HTTP 200

**B7 — AzureVmClient** (TENANT:CLIENT_ID:CLIENT_SECRET:SUBSCRIPTION_ID, OAuth2)
- `fetchLiveInstances`: existing list-VMs call + `?$expand=instanceView`; parse power state from `properties.instanceView.statuses[]` where `code` starts with `"PowerState/"`; map "running"→running, "stopped/deallocated"→stopped, "starting"→starting, "stopping"→stopping
- Store VM name (last segment of `id`) in `CloudInstanceState.id`; store resource group in `metadata["resourceGroup"]`
- Access token: re-request (existing pattern)
- `startInstance`: POST `/subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachines/{name}/start?api-version=2023-03-01`
- `stopInstance`: POST same base + `/deallocate` (deallocate = no billing vs powerOff which still bills)
- `restartInstance`: POST same base + `/restart`
- `forceRestartInstance`: POST same base + `/restart?skipShutdown=true`
- Success: HTTP 200 or 202 (accept both; 202 = async accepted)

**B8 — OciCloudClient** (JSON-packed credentials → OciApiClient)
- `fetchLiveInstances`: `apiClient.listInstances(compartment)` — already returns all states; map `lifecycleState` (RUNNING→running, STOPPED→stopped, STARTING→starting, STOPPING/TERMINATING→stopping)
- Only call `getInstancePublicIp` for RUNNING instances
- `startInstance(id)`: `apiClient.instanceAction(id, OciInstanceAction.START)`
- `stopInstance(id)`: `apiClient.instanceAction(id, OciInstanceAction.SOFTSTOP)`
- `restartInstance(id)`: `apiClient.instanceAction(id, OciInstanceAction.SOFTRESET)`
- `forceRestartInstance(id)`: `apiClient.instanceAction(id, OciInstanceAction.RESET)`

---

### C: New layout files ☐

**C1** — `app/src/main/res/layout/activity_cloud_manager.xml` (NEW)
Mirror `activity_oci_manager.xml`:
- `AppBarLayout` + `MaterialToolbar` id=`toolbar`
- Horizontal row: `MaterialButton` "Refresh" id=`btn_refresh`
- `ProgressBar` horizontal indeterminate id=`progress_bar` visibility=gone
- `TextView` id=`status_text` centered visibility=gone
- `RecyclerView` id=`instance_recycler` weight=1 clipToPadding=false paddingBottom=72dp

**C2** — `app/src/main/res/layout/item_cloud_instance.xml` (NEW)
`MaterialCardView` (margin 8dp, elevation 2dp):
```
┌─────────────────────────────────────────────────────────┐
│  [STATE BADGE]  Instance Name                  Region   │
│                 192.0.2.1                               │
├─────────────────────────────────────────────────────────┤
│  [Connect]  [Start]  [Stop]  [Restart]  [Force Restart] │
└─────────────────────────────────────────────────────────┘
```
IDs: `tv_instance_name`, `tv_instance_ip` (gone when null), `tv_instance_region` (gone when null),
`tv_state_badge` (colored pill — tint from status), `btn_connect`, `btn_start`, `btn_stop`,
`btn_restart`, `btn_force_restart`

State badge tints (use `MaterialCardView` background + `TextView` foreground):
- running → green (`@color/state_running` or similar)
- stopped → neutral grey
- starting/stopping/rebooting → amber
- unknown → surface

---

### D: `CloudAccountManagerActivity` ☐

**File:** `app/src/main/java/io/github/tabssh/ui/activities/CloudAccountManagerActivity.kt` (NEW)

Pattern: `OciManagerActivity` generalized for any `CloudProvider`.

```kotlin
class CloudAccountManagerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACCOUNT_ID = "cloud_account_id"
    }
}
```

**Startup flow:**
1. `intent.getStringExtra(EXTRA_ACCOUNT_ID)` → finish with error if null
2. Load `CloudAccount` from Room on IO
3. Load token: `securePasswordManager.retrievePassword("cloud_token_${account.id}")`
4. Build provider: `CloudProviderType.fromTag(account.provider)?.newClient()` (from A3)
5. Toolbar title = `account.name` + provider display name
6. Call `loadInstances()`

**`loadInstances()`:** show progress → `provider.fetchLiveInstances(token)` on IO → update adapter → hide progress

**`performAction(inst, action: CloudAction)` where `CloudAction = enum {START,STOP,RESTART,FORCE_RESTART}`:**
- show progress
- call matching provider method on IO
- Toast result
- `delay(2000)` then `loadInstances()`

**Button visibility in adapter (from `CloudInstanceState.status`):**
- `"running"`: connect (if ip != null) + stop + restart + forceRestart visible; start gone
- `"stopped"`: start visible; all others gone
- transitional states: all buttons gone (show spinner or "in progress" label)
- `"unknown"`: all buttons gone

**`handleSshConnect(inst: CloudInstanceState)`:**
- If `inst.ip == null`: Toast "No IP — start instance first", return
- Look up existing `ConnectionProfile` by `host == inst.ip` and `port == 22`
- Show SSH config dialog: username (provider default — see defaults table below), port 22,
  auth method (PASSWORD/PUBLIC_KEY), key picker when PUBLIC_KEY
- On connect: upsert `ConnectionProfile` (groupId = SystemGroupHelper "cloud" group),
  start `TabTerminalActivity.createIntent(profile, autoConnect=true)`

**Default SSH usernames by provider:**
- DigitalOcean, Hetzner, Linode, Vultr → `root`
- AWS → `ec2-user`
- GCP → `""` (empty — show hint "varies by OS image")
- Azure → `azureuser`
- OCI → `opc`

**Register in `AndroidManifest.xml`:**
Add `<activity android:name=".ui.activities.CloudAccountManagerActivity" />` alongside other managers.

---

### E: Edit cloud account ☐

**E1** — Add edit button to `item_cloud_account.xml`
Check the layout; add an edit icon button (pencil) id=`btn_edit` beside the existing refresh/delete buttons.

**E2** — `showEditAccountDialog(account: CloudAccount)` in `CloudAccountsFragment.kt`
- Load token: `app.securePasswordManager.retrievePassword("cloud_token_${account.id}")`
- For non-OCI: reuse `dialog_add_cloud_account` layout; pre-fill name + token; lock provider spinner (disabled — type can't change after creation); on save: upsert + re-store token + update `modifiedAt`
- For OCI: call `showOciCredentialsDialog(account.name)` pre-populated: parse stored JSON → fill all 7 fields

**E3** — Wire `onEdit` callback in `CloudAccountAdapter`
Add `onEdit: (CloudAccount) -> Unit` param; bind `b.btnEdit.setOnClickListener { onEdit(account) }`;
pass `{ showEditAccountDialog(it) }` from `onViewCreated`.

---

### F: OCI credentials — file import ☐

**F1** — Add "📂 Import .oci/config" button to `showOciCredentialsDialog`
In `CloudAccountsFragment.kt`:
- Register `ActivityResultLauncher<Intent>` in `onCreate`/`onAttach` for `ACTION_OPEN_DOCUMENT`
- Button tap: launch file picker with MIME `*/*`
- On result: read file bytes → `OciConfigParser.parse(text)` (already exists at `io.github.tabssh.hypervisor.oci.OciConfigParser`)
- Pre-fill: tenancy, user, fingerprint, region from first parsed profile
- PEM key: `key_file` path from config — if accessible via SAF read it; otherwise prompt user to select the key file separately with a second picker

---

### G: Wire CloudAccountsFragment to launch manager activity ☐

**File:** `app/src/main/java/io/github/tabssh/ui/fragments/CloudAccountsFragment.kt`

In `onViewCreated`, change the `onItemClick` lambda:
```kotlin
onItemClick = { account ->
    startActivity(
        Intent(requireContext(), CloudAccountManagerActivity::class.java)
            .putExtra(CloudAccountManagerActivity.EXTRA_ACCOUNT_ID, account.id)
    )
}
```
Keep the old `showAccountHosts()` method — it's still called from `CloudAccountsActivity`.

---

### H: Cleanup ☐

**H1** — Fix `switchEnabled` in `CloudAccountsFragment`
Currently hardcoded to `true` with no listener. Either wire it to toggle `account.enabled`
+ `cloudAccountDao().update(...)`, OR remove the switch from `item_cloud_account.xml` entirely
if per-account enable/disable is not needed for v1.

**H2** — Mark `CloudAccountsActivity` as legacy
Add `@Deprecated("Use CloudAccountsFragment inside InfraFragment")` kdoc.
The class can stay as-is otherwise — it's still reachable from an edge path.

---

### Default SSH usernames reference (for D and E)

| Provider | Default username |
|---|---|
| DigitalOcean | `root` |
| Hetzner | `root` |
| Linode | `root` |
| Vultr | `root` |
| AWS EC2 | `ec2-user` |
| GCP Compute | `""` (empty — varies by image) |
| Azure | `azureuser` |
| OCI | `opc` |

---

## ✅ Recently Shipped (this session)

- **`3905de0f8f63`** 🐛 Long-press terminal menu: trimmed 9→7 items; toggle labels now computed live inside `post{}` ("Show/Hide system keyboard", "Show/Hide key bar"); Font size + Close tab removed (both live in ☰ palette). OCI added to Cloud Accounts: `CloudProviderType.OCI`, `OciCloudClient` (JSON-packed creds → OciApiClient), multi-field credentials dialog in `CloudAccountsFragment`, token field hides when OCI selected, `HypervisorEditActivity` OCI entry labeled legacy.

---

## 📋 Documented but not yet implemented

These are in the codebase or spec but **not** working end-to-end. Post-v1 roadmap only.

| Item | AI.md | Effort | Notes |
|---|---|---|---|
| **FIDO2 SSH signing** | §7.1, §16 | ~80h, likely indefinite | `Fido2SshIdentity.kt` throws `JSchException("not yet implemented")`. JSch doesn't support `sk-ecdsa-sha2-nistp256` / `sk-ssh-ed25519` key types. Needs JSch fork or alternate SSH library. |
| **Chinese / Japanese translations** | §16 | ~4h per language | No translators assigned. English fallback works. Post-v1 when translators are available. |
| **QR Pairing — desktop side** | §18 | other repo | Mobile decoder is live (`ea4f687f`). Desktop encoder WIP in `tabssh/desktop`. Wire format + interop test vectors in `AI.md §18` / `QR_PAIRING.md`. |

---

## 📐 QR Pairing — Desktop side

- **Status:** 🔧 In progress (other instance — see `../desktop/.git/COMMIT_MESS`)
- **Priority:** MEDIUM
- **Spec:** [`AI.md §18`](AI.md#18-qr-pairing--desktop--mobile-setup) / `tabssh/desktop/QR_PAIRING.md`

Mobile decoder is in place and waiting for the desktop encoder and interop test vectors. Wire format, encryption parameters, payload schema, and CBOR field names in the spec.

---

## 🔧 `advancedSettings` — remaining SSH config directives

Local/Remote/Dynamic forwards now apply at connect (`d714a7b4`). Status:

| Directive | Parser | Stored | Applied |
|---|---|---|---|
| `ProxyJump` / `ProxyCommand` | ✅ | columns | ✅ — `SSHConfigParser.kt:299-302` populates `proxy_host`/`proxy_port`/`proxy_username` at parse time. `ProxyCommand` has no JSch equivalent. |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | JSON | intentionally ignored (mobile keepalive + TOFU dialog own these) |
| `ForwardAgent` / `ForwardX11` / `compression` / `connectTimeout` | ✅ | JSON + columns | ✅ |
| `RequestTTY` | ✅ | JSON | 🔴 — never read by `applyAdvancedSettings()`; PTY is unconditionally allocated regardless of value |

---

## 🧹 Post-v1: Dead code removal

**Recommendation:** Do not do a broad dead code sweep before v1. The risk/reward is wrong — no user-visible gain, and one missed case (Room entity field, preference key, ProGuard keep rule, reflection target) causes a crash or silent data loss.

**Safe to do now (zero crash risk):**
- Unused string resources and drawable references — R8/lint flags them; missing one doesn't crash
- `HypervisorProfile.isXenOrchestra` — still actively used in 10+ call sites in `XCPngManagerActivity`; the migration to `apiTypeOverride` is incomplete. Do NOT drop before that migration is finished and v1 schema is locked.

**After v1 ships:**
- Full dead code sweep — by then the migration chain is locked, you have a stable test device matrix, and ProGuard keep rules are proven
- Scope: unreferenced classes, orphan layout IDs, dead `when()` branches, unused DAO methods
- Tool: Android Studio "Inspect Code" + `grep -rn` cross-reference before touching anything
- Rule: never remove a symbol that appears in a ProGuard keep rule or is reachable via `findPreference("key")` string literals, even if static analysis shows zero references

**For dead comments in source** (the same caution applies — but lower risk than dead code):
- Safe to remove inline now: comments that describe what a bug *was* before a fix (the fix is the record, not the comment), and `// TODO:` comments in committed code that reference shipped work
- Keep: comments that explain *why* a non-obvious choice was made (architectural context, not fix history)

---

## 📚 Reference

- `AI.md` — architecture, packages, DB schema, sync, crypto, hypervisors, QR pairing
- `CLAUDE.md` — operational runbook (build commands, commit policy, file locations)
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — F-Droid formatted app description
- `AI.md §18` — QR pairing wire format (desktop copy at `tabssh/desktop/QR_PAIRING.md`)
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)
