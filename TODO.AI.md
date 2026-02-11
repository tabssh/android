# TabSSH TODO - User-Facing Issues Wizard

**Last Updated:** 2026-02-11  
**Session:** New Issue Discovery

---

## 🔍 NEW ISSUES TO DISCUSS

These are issues we haven't created action plans for yet.

**Total Issues:** TBD (discovering as we go)  
**Discussed:** 0  
**Resolved:** 0

---

## WIZARD STATUS

**Current:** Issue #1 - Documented, discussing  
**Mode:** Interactive discovery

---

## 📋 ISSUES DISCOVERED

### 🐛 Issue #1: Navigation Menu Has Non-Functional Items
- **Status:** 🟡 In Discussion
- **Priority:** MEDIUM
- **Impact:** Users click menu items expecting functionality but get nothing or placeholder toasts
- **Discovery:** User screenshot of menu with many items

**Problem Details:**
- Navigation drawer (drawer_menu.xml) has 20+ menu items
- Some items fully work (Connections, Identities, SSH Keys, Import/Export, Settings)
- Some items show placeholder toasts (Proxmox/XCP-ng/VMware direct open)
- Some items open Activities but those Activities may be incomplete (Snippets, Port Forwarding, Groups, Cluster Commands)
- Some items work but switch to empty/placeholder tabs (Performance Monitor)

**Current Implementation (MainActivity.kt lines 268-341):**
✓ Working:
  - nav_connections → switches to tab
  - nav_identities → switches to tab
  - nav_manage_keys → KeyManagementActivity
  - nav_import_connections → file picker
  - nav_export_connections → file save
  - nav_import_ssh_config → import function
  - nav_settings → SettingsActivity
  - nav_help → help dialog
  - nav_about → about dialog

⚠️ Placeholder toasts:
  - nav_proxmox → "Select hypervisor first" toast
  - nav_xcpng → "Select hypervisor first" toast  
  - nav_vmware → "Select hypervisor first" toast

❓ Unknown functionality:
  - nav_snippets → SnippetManagerActivity (Activity exists?)
  - nav_port_forwarding → PortForwardingActivity (Activity exists?)
  - nav_manage_groups → GroupManagementActivity (Activity exists?)
  - nav_cluster_commands → ClusterCommandActivity (Activity exists?)
  - nav_performance → switches to tab 3 (Performance tab - empty?)
  - nav_hypervisors → switches to tab 4 (Hypervisors tab)

**Discussion Questions:**

1. **Menu cleanup approach** - Which option?
   A) Remove non-functional items from menu (clean but fewer features visible)
   B) Disable non-functional items (grayed out with "Coming Soon" subtitle)
   C) Keep all but improve toasts/feedback for incomplete features
   D) Finish implementing missing features (more work)

2. **Hypervisor features** - Keep or remove?
   - Proxmox/XCP-ng/VMware support seems partially done
   - Is this important for v1.0.0 or defer to later?

3. **Performance Monitor tab** - What should it show?
   - Currently just an empty placeholder
   - Remove from menu or implement basic stats?

4. **Priority order** - If we implement features, which first?
   - Snippets (quick command templates)
   - Port Forwarding management
   - Group Management
   - Cluster Commands
   - Performance Monitor

**User Decision:**
✅ **Option D - Implement all missing features** (menu must be fully functional)
✅ **Clean up and reorganize menu** for better UX
✅ **Hypervisor features are MUST-HAVE** for v1.0.0

**Investigation Results:**
✓ All Activities EXIST and are registered in AndroidManifest.xml
✓ Activities have substantial code (232-421 lines each):
  - SnippetManagerActivity.kt: 421 lines
  - GroupManagementActivity.kt: 377 lines  
  - ClusterCommandActivity.kt: 245 lines
  - PortForwardingActivity.kt: 232 lines
  - ProxmoxManagerActivity.kt: exists
  - XCPngManagerActivity.kt: exists
  - VMwareManagerActivity.kt: exists

✓ Fragments exist for tabs:
  - PerformanceFragment.kt: exists
  - HypervisorsFragment.kt: exists

**Action Items:**
- [ ] Test each Activity to identify what's broken/incomplete
- [ ] Fix SnippetManagerActivity functionality
- [ ] Fix PortForwardingActivity functionality
- [ ] Fix GroupManagementActivity functionality
- [ ] Fix ClusterCommandActivity functionality
- [ ] Fix PerformanceFragment (tab 3)
- [ ] Fix HypervisorsFragment (tab 4)
- [ ] Implement Proxmox/XCP-ng/VMware direct navigation (replace toasts with actual manager opening)
- [ ] Reorganize drawer_menu.xml for better grouping
- [ ] Test all hypervisor features (Proxmox/XCP-ng/VMware managers)

**Menu Reorganization Plan:**
1. **Core Features** (top)
   - Connections
   - Identities
   - SSH Keys
   - Snippets
   
2. **Advanced Features**
   - Port Forwarding
   - Groups
   - Cluster Commands
   
3. **Hypervisors** (dedicated section)
   - All Hypervisors
   - Proxmox
   - XCP-ng
   - VMware
   
4. **Tools**
   - Performance Monitor
   
5. **Data Management**
   - Import Connections
   - Export Connections
   - Import SSH Config
   
6. **Settings & Info**
   - Settings
   - Help
   - About

**Priority:** HIGH (affects overall app usability)
**Complexity:** MEDIUM (code exists, needs debugging/completion)
**Est. Time:** Multiple sessions (need to test and fix each feature)

---

### 🐛 Issue #2: Ed25519 SSH Key Import Fails
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** HIGH
- **Impact:** Cannot import Ed25519 keys (modern standard key type)
- **Discovery:** User attempted import, got parser error

**Problem Details:**
User tried to import Ed25519 OpenSSH private key format:
```
-----BEGIN OPENSSH PRIVATE KEY-----
openssh-key-v1...
-----END OPENSSH PRIVATE KEY-----
```

Error message:
```
Failed to parse OpenSSH key: encoded key spec not recognized: 
failed to construct sequence from byte[]: corrupted stream - 
out of bounds length found: 74 >= 32
```

**Root Cause:**
SSHKeyParser.kt lines 383-397: parseOpenSSHEd25519Key() bug
- OpenSSH stores RAW 32-byte public / 64-byte private key bytes  
- Parser incorrectly passes raw bytes to X509EncodedKeySpec/PKCS8EncodedKeySpec
- These specs expect ASN.1/DER encoded format, not raw bytes
- DerInputStream fails parsing raw bytes as DER structure

```kotlin
// Current buggy code:
val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))  // ❌
val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))  // ❌
```

**Solution:**
Use BouncyCastle Ed25519PublicKeyParameters/Ed25519PrivateKeyParameters
to handle raw key bytes properly, then convert to Java PublicKey/PrivateKey.

**Action Items:**
- [ ] Fix parseOpenSSHEd25519Key() to use BC Ed25519 parameters
- [ ] Test with user's Ed25519 key
- [ ] Verify Ed25519 public key parsing also works (ssh-ed25519 format)
- [ ] Test encrypted Ed25519 keys if applicable

**Files Involved:**
- app/src/main/java/io/github/tabssh/crypto/SSHKeyParser.kt (lines 383-397)

**Priority:** HIGH (Ed25519 is modern standard, should work)
**Complexity:** LOW (simple fix, ~10 lines changed)
**Est. Time:** 30 minutes

---

### 🐛 Issue #3: Cannot Add XCP-ng Host (Test Connection Always Fails)
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** HIGH
- **Impact:** Users cannot add XCP-ng hypervisors (critical feature)
- **Discovery:** User attempted to add XCP-ng host, test connection always failed

**Problem Details:**
When adding XCP-ng hypervisor in HypervisorEditActivity:
- User fills in all fields (name, host, port, username, password)
- Clicks "Test Connection" button
- Always fails with no helpful error message
- Cannot save hypervisor configuration

**Root Cause:**
HypervisorEditActivity.kt lines 174-176:
```kotlin
HypervisorType.XCPNG -> {
    // TODO: Implement XCP-ng test
    false  // ❌ ALWAYS RETURNS FALSE!
}
```

The XCP-ng test is just a placeholder that always returns false.

**Solution Available:**
✓ XenOrchestraApiClient.authenticate() is FULLY IMPLEMENTED (lines 151-202)
- POST /rest/v0/users/signin with email/password
- Returns true/false based on success
- Full error handling and logging

**The Fix:**
Replace TODO with actual client instantiation:
```kotlin
HypervisorType.XCPNG -> {
    val client = XenOrchestraApiClient(host, port, username, password, verifySsl)
    client.authenticate()
}
```

**Important UI Note:**
XCP-ng/Xen Orchestra uses EMAIL for authentication, not username!
- Current UI label: "Username"
- XO API expects: email (e.g., administrator@casjayvps.us)
- Need to update UI to clarify: "Username / Email"

**Test Credentials (User Provided):**
- Server: https://xcp.casjayvps.us
- XO Email: administrator@casjayvps.us
- Password: Xcp-ngP@sswd11

**Action Items:**
- [ ] Replace TODO with XenOrchestraApiClient.authenticate() call
- [ ] Update UI label from "Username" to "Username / Email" for XCP-ng
- [ ] Test with user's actual XCP-ng server
- [ ] Add better error messages (show API response)
- [ ] Implement VMware test connection too (currently also returns false)

**Files Involved:**
- app/src/main/java/io/github/tabssh/ui/activities/HypervisorEditActivity.kt (line 174-176)
- app/src/main/java/io/github/tabssh/hypervisor/xcpng/XenOrchestraApiClient.kt (already implemented)
- app/src/main/res/layout/activity_hypervisor_edit.xml (UI label update)

**Priority:** HIGH (hypervisor management is must-have feature)
**Complexity:** LOW (5-line fix + UI label)
**Est. Time:** 15 minutes

---

### 🐛 Issue #4: Log Viewing Problems (Multiple Issues)
- **Status:** 🟢 Discussed - Multiple Problems Identified
- **Priority:** HIGH
- **Impact:** Cannot effectively debug issues or view connection logs
- **Discovery:** User reported multiple log viewing issues

**Problem Details:**

**Sub-Issue 4A: No Copy to Clipboard Button** ❌
- Both LogViewerActivity and AuditLogViewerActivity have Export button
- No quick "Copy" button to copy logs to clipboard
- Users need quick copy for pasting into bug reports/support

**Sub-Issue 4B: Missing SSH Session/Host Logs** ❌
- Application logs show: DEBUG, INFO, WARN, ERROR from app
- Audit logs show: User actions (login, connection attempts, etc.)
- **Missing:** SSH session logs showing:
  - Connection establishment details
  - SSH handshake/auth steps
  - Commands executed on remote host
  - Server responses and errors
  - Connection failures with details

**Sub-Issue 4C: Unclear Log Type Labels** ⚠️
- LogViewerActivity title: "Application Logs" (generic)
- No prominent indication of current filter
- Filter dialog exists but not obvious
- Title should show: "Application Logs - ERROR" when filtered

**Sub-Issue 4D: Limited Log Access** ⚠️
- Logger.getRecentLogs() returns last 500 lines from file (line 114)
- Only logs if debug mode enabled (line 28)
- Users in production can't see older logs

**Root Causes:**

1. **Copy button:** Menu XMLs missing action_copy_all item
2. **Session logs:** No separate TranscriptViewerActivity integrated (file exists but not accessible)
3. **Labels:** Activity doesn't update title with filter type
4. **Limited logs:** Hard-coded 500 line limit, debug-only file logging

**Files Involved:**
- app/src/main/java/io/github/tabssh/ui/activities/LogViewerActivity.kt (270 lines)
- app/src/main/java/io/github/tabssh/ui/activities/AuditLogViewerActivity.kt (198 lines)
- app/src/main/java/io/github/tabssh/ui/activities/TranscriptViewerActivity.kt (exists, not accessible)
- app/src/main/res/menu/log_viewer_menu.xml (missing copy button)
- app/src/main/res/menu/audit_log_menu.xml (missing copy button)
- app/src/main/java/io/github/tabssh/utils/logging/Logger.kt (hardcoded limits)

**Solution Plan:**

**Phase 1 - Quick Wins (30 min):**
- [ ] Add "Copy All" button to log_viewer_menu.xml
- [ ] Add "Copy All" button to audit_log_menu.xml
- [ ] Implement copyLogsToClipboard() in both activities
- [ ] Update activity titles to show current filter: "Application Logs - ERROR"

**Phase 2 - Session Logs (1 hour):**
- [ ] Add "Session Logs" menu item in MainActivity
- [ ] Make TranscriptViewerActivity accessible from menu
- [ ] Show list of SSH sessions with transcripts
- [ ] Allow viewing/copying individual session logs
- [ ] Add "Copy" button to TranscriptViewerActivity too

**Phase 3 - Improvements (30 min):**
- [ ] Remove 500 line limit, show all available logs
- [ ] Enable file logging in release builds (not just debug)
- [ ] Add log level badges/colors (red=ERROR, yellow=WARN, etc.)
- [ ] Add search functionality within logs

**Action Items Priority:**
1. **HIGH:** Add copy buttons (Phase 1)
2. **HIGH:** Make session logs accessible (Phase 2)
3. **MEDIUM:** Update title labels (Phase 1)
4. **LOW:** Improvements (Phase 3)

**Priority:** HIGH (affects debugging and support)
**Complexity:** MEDIUM (multiple activities to modify)
**Est. Time:** 
- Phase 1: 30 minutes
- Phase 2: 1 hour
- Phase 3: 30 minutes
- **Total:** 2 hours

---

### 🐛 Issue #5: Theme Selection Problems (Multiple Issues)
- **Status:** 🟢 Discussed - Root Causes Found
- **Priority:** MEDIUM
- **Impact:** Users cannot access all 23 terminal themes, confusion about theme types
- **Discovery:** User reported only seeing 3 themes, cannot import custom themes

**Problem Details:**

**CRITICAL DISCOVERY: Two Separate Theme Systems Exist!**

**System 1 - APP THEME** (Material Design 3)
- Location: Settings → General → App Theme
- Purpose: Controls app-wide UI (toolbars, FABs, backgrounds)
- Options: Dark, Light, System Default (3 themes) ✓ CORRECT
- File: preferences_general.xml, arrays.xml lines 4-13

**System 2 - TERMINAL THEME** (Terminal Colors)
- Location: Settings → Terminal → Theme / Colors
- Purpose: Controls terminal text/background colors
- **Should have:** 23 themes (from BuiltInThemes.kt)
- **Actually shows:** 8 themes (arrays.xml lines 50-69)
- **MISSING 15 THEMES!**

**Sub-Issue 5A: User Confusion - Looking at Wrong Setting** ⚠️
User went to "App Theme" (Material Design) expecting terminal color themes.
Need better labeling/organization.

**Sub-Issue 5B: Terminal Theme Array Out of Sync** ❌
arrays.xml terminal_theme_entries only has 8 themes:
- Dracula, Monokai, Solarized Dark/Light
- Gruvbox, Nord, One Dark, Tokyo Night

Missing 15 themes from dropdown:
- System Default, System Dark, System Light
- Gruvbox Light, Tomorrow Night, GitHub Light
- Atom One Dark, Material Dark, Tokyo Night Light
- Catppuccin Mocha, Rosé Pine, Everforest
- Kanagawa, Night Owl, Cobalt2

**Sub-Issue 5C: No Custom Theme Import UI** ❌
- ThemeManager has importTheme() and exportTheme() methods
- No button/menu item in Settings to trigger import
- Users cannot load custom JSON theme files
- Feature exists in code but not accessible

**Root Causes:**

1. **Naming confusion:** "App Theme" vs "Terminal Theme" not clear
2. **Array not updated:** When we added 11 themes to BuiltInThemes.kt, forgot to update arrays.xml
3. **No import button:** Terminal preferences screen has no "Import Theme" preference

**Files Involved:**
- app/src/main/res/values/arrays.xml (lines 50-69 - missing 15 themes)
- app/src/main/res/xml/preferences_terminal.xml (missing import button)
- app/src/main/java/io/github/tabssh/themes/definitions/BuiltInThemes.kt (23 themes defined)
- app/src/main/java/io/github/tabssh/themes/definitions/ThemeManager.kt (import/export methods exist)

**Solution:**

**Part 1 - Update Theme Array (15 min):**
- [ ] Add all 15 missing themes to terminal_theme_entries in arrays.xml
- [ ] Add corresponding values to terminal_theme_values
- [ ] Organize by category (System themes first, then alphabetical)

**Part 2 - Add Import/Export UI (30 min):**
- [ ] Add "Import Custom Theme" Preference to preferences_terminal.xml
- [ ] Add "Export Current Theme" Preference
- [ ] Implement file picker in SettingsActivity for import
- [ ] Implement file save dialog for export
- [ ] Show success/error toasts

**Part 3 - Improve Labeling (15 min):**
- [ ] Rename "App Theme" to "App Appearance" (Dark/Light UI)
- [ ] Keep "Terminal Theme" clear (Terminal Colors)
- [ ] Add descriptions: "Controls app interface" vs "Controls terminal colors"

**Action Items:**
- [ ] Update arrays.xml with all 23 themes
- [ ] Add import/export preferences to terminal settings
- [ ] Wire up file pickers in SettingsActivity
- [ ] Test theme import/export with JSON files
- [ ] Improve preference descriptions

**Priority:** MEDIUM (themes work, just not all accessible)
**Complexity:** LOW (mostly XML updates + simple file pickers)
**Est. Time:** 1 hour total
- Part 1: 15 min
- Part 2: 30 min
- Part 3: 15 min

---

## Template for Each Issue

When we discuss an issue, I'll fill in:

```markdown
### 🐛 Issue #N: [Title]
- **Status:** 🟡 In Discussion / 🟢 Discussed / ✅ Resolved
- **Priority:** CRITICAL / HIGH / MEDIUM / LOW
- **Impact:** [User-facing description]
- **Discovery:** [How/when found]

**Discussion Notes:**
[Your input and decisions]

**Action Items:**
- [ ] Task 1
- [ ] Task 2
- [ ] Task 3

**Files Involved:**
- file1.kt
- file2.xml

---
```

---

## NOTES

- This TODO file tracks only NEW issues discovered during wizard
- Old discussed issues are in session checkpoints
- Say "next" to move to next issue
- Say "done" to exit wizard

### 🐛 Issue #6: Cannot Enable Sync
- **Status:** 🟢 Discussed - Complex Requirements Found
- **Priority:** HIGH
- **Impact:** Users cannot enable sync (critical multi-device feature)
- **Discovery:** User reported cannot enable sync toggle

**Problem:** Sync toggle immediately returns to OFF when tapped.

**Root Cause:** Complex prerequisite checks that ALL must pass:
- Google Drive: OAuth authentication + encryption password
- WebDAV: Server config (URL/user/pass) + encryption password
- Auto: Detects backend availability, requires respective prerequisites

**User Experience Problems:**
- A: Confusing - switch won't turn on, unclear why ⚠️
- B: Password dialog closes on validation error ❌
- C: No setup wizard/guided flow ❌
- D: WebDAV config hidden in "auto" mode ⚠️

**Solution Options:**
- Option A: Full setup wizard (3 hours) - Best UX
- Option B: Improved inline UX (1.5 hours) - ⭐ Recommended
- Option C: Dialog fixes only (30 min) - Quick fix

**Recommended: Option B - Improved Inline UX**

**Action Items (Option B):**
- [ ] Add "Setup Sync" button above toggle
- [ ] Create setup dialog with requirements checklist
- [ ] Show status: ✓ Configured / ✗ Not configured / Configure
- [ ] Make password dialog persistent (don't close on error)
- [ ] Add inline error messages in dialog
- [ ] Enable toggle only after all requirements met
- [ ] Improve toast messages for clarity

**Files Involved:**
- app/src/main/java/io/github/tabssh/ui/fragments/SyncSettingsFragment.kt (708 lines)
  - Lines 198-280: setupSyncToggle() with gating logic
  - Lines 472-496: showAuthenticationDialog()
  - Lines 502-556: showPasswordSetupDialog()
- app/src/main/res/xml/preferences_sync.xml

**Priority:** HIGH
**Complexity:** MEDIUM
**Est. Time:** 1.5 hours (Option B)

---


### 🐛 Issue #7: Connection Edit Shows Wrong Title
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** MEDIUM
- **Impact:** Confusing UI - can't tell which connection being edited
- **Discovery:** User reported seeing "New Connection" + ID instead of "Edit Connection" + name

**Problem:** When editing an existing connection, toolbar shows wrong text:
- Shows: "Edit Connection" (generic)
- Should show: "Edit [Connection Name]" (specific)
- User reports also seeing ID somewhere (possibly meant generic title)

**Root Cause:**

Code flow in ConnectionEditActivity.kt:
1. onCreate() → setupToolbar() → Sets title immediately (line 82)
2. onCreate() → loadConnection() → Loads profile async (line 354)
3. loadConnection() → populateFields() → Fills form fields (line 369)
4. **populateFields() NEVER updates toolbar titleall 'Updated the README'* ❌

```kotlin
// Line 82 - Sets generic title
title = if (isEditMode) "Edit Connection" else "New Connection"

// Line 370 - Fills name field but NOT title
binding.editName.setText(profile.name)
```

**The Bug:**
- Toolbar title set once during setupToolbar() with generic text
- loadConnection() runs async and loads connection data
- populateFields() fills all form fields with profile data
- **Title never updated with actual connection name**

**Solution:**

Add title update after loadConnection() completes:

```kotlin
private fun loadConnection(connectionId: String) {
    lifecycleScope.launch {
        try {
            existingProfile = app.database.connectionDao().getConnectionById(connectionId)
            existingProfile?.let { profile ->
                populateFields(profile)
                // ✅ ADD THIS:
                supportActionBar?.title = "Edit ${profile.name}"
            }
        } catch (e: Exception) {
            // ... existing error handling
        }
    }
}
```

**Action Items:**
- [ ] Add title update in loadConnection() after populateFields()
- [ ] Update title to "Edit [Connection Name]" format
- [ ] Test with different connection names
- [ ] Verify title shows correctly on back navigation

**Files Involved:**
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Line 354-366: loadConnection() method
  - Line 82: setupToolbar() initial title

**Priority:** MEDIUM (UX issue, not critical functionality)
**Complexity:** TRIVIAL (1-line fix)
**Est. Time:** 5 minutes

---


### 🐛 Issue #8: Must Exit App for Changes to Show
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** HIGH
- **Impact:** Core UX broken - changes invisible until app restart
- **Discovery:** User must exit app after adding/editing connections or groups to see updates

**Problem:** After adding new connection, editing existing connection, or managing groups:
- Changes don't appear in list until app is completely exited and reopened
- Expected: Changes appear immediately (Room Flow should auto-update)
- Reality: List stays stale until app restart

**Root Cause:**

**NESTED FLOW COLLECTORS** in ConnectionsFragment.kt lines 239-250:

```kotlin
// ❌ BROKEN CODE
private fun loadAllConnections() {
    lifecycleScope.launch {
        app.database.connectionDao().getAllConnections().collect { connections ->  // Outer Flow
            allConnections = connections
            
            if (useGroupedView) {
                // ❌ NESTED collect() - This BLOCKS indefinitely!
                app.database.connectionGroupDao().getAllGroups().collect { groups ->  // Inner Flow
                    allGroups = groups
                    applyGroupedView()
                }
                // ↑ Inner collect() is infinite, never returns
                // ↓ Code below never executes when new data arrives
            } else {
                applySortAndFilter()
            }
        }
    }
}
```

**Why This Breaks:**

1. **Infinite nested collectors**: Outer Flow collects connections, inner Flow collects groups
2. **Inner collect() blocks forever**: Room Flows are infinite streams - collect() never returns
3. **Outer Flow can't iterate**: When outer Flow emits new data, inner collector still suspended
4. **Deadlock/race condition**: Both collectors waiting, UI never updates
5. **Works on app restart**: Fresh start = fresh collectors, gets initial data once

**The Fix - Two Solutions:**

**Solution A: Use `first()` instead of `collect()` for inner Flow (QUICK FIX):**

```kotlin
private fun loadAllConnections() {
    lifecycleScope.launch {
        app.database.connectionDao().getAllConnections().collect { connections ->
            allConnections = connections
            
            if (useGroupedView) {
                // ✅ Use first() to get one-time snapshot
                allGroups = app.database.connectionGroupDao().getAllGroups().first()
                applyGroupedView()
            } else {
                applySortAndFilter()
            }
        }
    }
}
```

**Solution B: Use `combine()` to merge both Flows (BETTER - reacts to both):**

```kotlin
private fun loadAllConnections() {
    lifecycleScope.launch {
        combine(
            app.database.connectionDao().getAllConnections(),
            app.database.connectionGroupDao().getAllGroups()
        ) { connections, groups ->
            Pair(connections, groups)
        }.collect { (connections, groups) ->
            allConnections = connections
            allGroups = groups
            
            if (useGroupedView) {
                applyGroupedView()
            } else {
                applySortAndFilter()
            }
        }
    }
}
```

**Which Solution?**

- **Solution A (first())**: Faster to implement, but groups won't auto-update if changed
- **Solution B (combine())**: Proper reactive solution, both connections AND groups auto-update

**Recommendation:** Solution B (combine()) for full reactivity

**Action Items:**
- [ ] Refactor loadAllConnections() to use combine() instead of nested collect()
- [ ] Add import: `import kotlinx.coroutines.flow.combine`
- [ ] Test adding/editing connections - should show immediately
- [ ] Test creating/editing groups - should show immediately
- [ ] Test deleting connections/groups - should update immediately
- [ ] Verify no memory leaks or uncancelled collectors

**Files Involved:**
- app/src/main/java/io/github/tabssh/ui/fragments/ConnectionsFragment.kt
  - Lines 235-258: loadAllConnections() method with nested collectors
  - Need import: kotlinx.coroutines.flow.combine

**Similar Issues to Check:**
- IdentitiesFragment.kt - May have same pattern
- HypervisorsFragment.kt - May have same pattern  
- PerformanceFragment.kt - May have same pattern
- Any other fragments with nested Flow collectors

**Priority:** HIGH (breaks core functionality - adding/editing doesn't work properly)
**Complexity:** EASY (refactor nested collectors to combine())
**Est. Time:** 15 minutes (plus testing other fragments if affected)

---


### 🐛 Issue #9: Cannot Expand/Collapse Groups
- **Status:** 🟢 Discussed - Root Cause Found (DUPLICATE OF ISSUE #8)
- **Priority:** HIGH
- **Impact:** Groups unusable - cannot toggle visibility of connections
- **Discovery:** User cannot click to expand/collapse connection groups

**Problem:** When clicking on group headers in grouped view:
- Click should toggle group expansion (show/hide connections)
- Database is updated correctly
- UI never refreshes to show the change
- Group stays in same state (expanded or collapsed)

**Root Cause:**

**IDENTICAL TO ISSUE #8** - NESTED FLOW COLLECTORS!

Code flow in ConnectionsFragment.kt:

```kotlin
// Lines 235-250: loadAllConnections()
app.database.connectionDao().getAllConnections().collect { connections ->  // Outer
    allConnections = connections
    
    if (useGroupedView) {
        // ❌ NESTED collect() - BLOCKS forever!
        app.database.connectionGroupDao().getAllGroups().collect { groups ->  // Inner
            allGroups = groups
            applyGroupedView()
        }
    }
}

// Lines 333-343: toggleGroupExpanded()
private fun toggleGroupExpanded(groupHeader: ConnectionListItem.GroupHeader) {
    lifecycleScope.launch {
        val newCollapsed = !groupHeader.isExpanded
        app.database.connectionGroupDao().updateGroupCollapsedState(groupHeader.group.id, newCollapsed)
        // ✅ Database updated successfully
        // ❌ But Flow collector never receives the update!
    }
}
```

**Why It Fails:**

1. ✅ User clicks group header → onClick fires
2. ✅ toggleGroupExpanded() called → updates database
3. ✅ updateGroupCollapsedState() executes → group.isCollapsed changed in DB
4. ✅ Groups Flow EMITS new data with updated collapsed state
5. ❌ But inner `collect()` is BLOCKED waiting for next emission
6. ❌ Outer `collect()` hasn't continued to next iteration
7. ❌ Race condition: inner collector suspended, outer collector waiting
8. ❌ UI never updates with new collapsed state

**This is the EXACT SAME nested Flow bug as Issue #8!**

**Solution:**

**FIX ISSUE #8 FIRST** - This will automatically fix Issue #9!

When we refactor loadAllConnections() to use `combine()` instead of nested `collect()`:

```kotlin
// ✅ FIXED CODE (from Issue #8 solution)
private fun loadAllConnections() {
    lifecycleScope.launch {
        combine(
            app.database.connectionDao().getAllConnections(),
            app.database.connectionGroupDao().getAllGroups()
        ) { connections, groups ->
            Pair(connections, groups)
        }.collect { (connections, groups) ->
            allConnections = connections
            allGroups = groups
            
            if (useGroupedView) {
                applyGroupedView()  // ✅ Will receive updates from BOTH Flows!
            } else {
                applySortAndFilter()
            }
        }
    }
}
```

With combine():
- Both connections AND groups Flows are observed
- When group.isCollapsed changes, groups Flow emits
- combine() receives new groups data
- applyGroupedView() rebuilds UI with correct expanded/collapsed state
- Group expand/collapse works!

**Action Items:**
- [ ] **NO SEPARATE FIX NEEDED** - Issue #8 fix will resolve this automatically
- [ ] After fixing Issue #8, test group expand/collapse functionality
- [ ] Verify UI updates immediately when clicking group headers
- [ ] Test with multiple groups
- [ ] Test with nested groups (if supported)

**Files Involved:**
- Same as Issue #8:
  - app/src/main/java/io/github/tabssh/ui/fragments/ConnectionsFragment.kt
    - Lines 235-258: loadAllConnections() - needs combine() refactor
    - Lines 333-343: toggleGroupExpanded() - works correctly, just needs Flow fix
  - app/src/main/java/io/github/tabssh/storage/database/dao/ConnectionGroupDao.kt
    - Line 71: updateGroupCollapsedState() - works correctly

**Priority:** HIGH (groups completely non-functional without expand/collapse)
**Complexity:** TRIVIAL (fixed by Issue #8 solution)
**Est. Time:** 0 minutes (included in Issue #8's 15 minutes)

**Note:** This is a **duplicate/related bug** to Issue #8. Fixing the nested Flow collectors in Issue #8 will automatically fix this issue. No separate code changes needed.

---


### 🐛 Issue #10: Identity and Authentication Sections Both Visible (Confusing UX)
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** MEDIUM
- **Impact:** Confusing UX - unclear which authentication method is used
- **Discovery:** User reports confusion when Identity selected but Authentication section still shows

**Problem:** When user selects an Identity for a connection:
- Identity provides: username, auth type, SSH key reference
- But Authentication section remains visible
- User must configure BOTH Identity AND Authentication
- Unclear which takes precedence
- **User quote:** "When using an Identity, we don't need Authentication as the selected Identity IS the authentication. It makes UX confusing."

**What is an Identity?**

Identity = Reusable credential set (like SSH config Host entry):
```kotlin
// Identity entity fields:
- name: "work-admin"
- username: "admin"
- authType: PUBLIC_KEY or PASSWORD or KEYBOARD_INTERACTIVE
- keyId: "abc123" (reference to SSH key)
```

**Purpose:** One Identity used for multiple connections
- Example: "work-admin" identity → 20 different servers
- Avoids duplicating username/key across connections

**Current Behavior (CONFUSING):**

1. User creates connection
2. Selects Identity: "work-admin" (has username + SSH key)
3. Authentication section STILL visible below Identity
4. User sees Auth Type dropdown, SSH Key selector, Password fields
5. Unclear: Does Identity auth override? Or manual auth?

**Root Cause:**

**ConnectionEditActivity.kt lines 242-249:**
```kotlin
binding.spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
    if (position > 0) {
        val identity = identities[position - 1]
        // Apply identity to connection
        binding.editUsername.setText(identity.username)
        // TODO: Apply key or password from identity
        // ❌ Doesn't hide Authentication card!
    }
}
```

**activity_connection_edit.xml lines 190-313:**
```xml
<!--AuthenticationSettings-->
<com.google.android.material.card.MaterialCardView
    <!--❌NOID!Can'treferenceincodetohide/show-->
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
    
    <LinearLayout>
        <TextView android:text="Authentication" />
        <AutoCompleteTextView android:id="@+id/spinner_auth_type" />
        <!--...password/keylayouts...-->
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

**Why It's Confusing:**

- **Identity selected** → User expects NO manual auth config needed
- **But Authentication visible** → User thinks they MUST configure it
- **No indication** which takes precedence
- **Wasted effort** configuring redundant auth

**Solution:**

**Phase 1: Hide Authentication When Identity Selected (15 min)**

1. Add ID to Authentication card in XML:
```xml
<com.google.android.material.card.MaterialCardView
    android:id="@+id/card_authentication"  <!--ADDTHIS-->
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
```

2. Update setupIdentitySpinner() to show/hide card:
```kotlin
private fun setupIdentitySpinner() {
    lifecycleScope.launch {
        val identities = app.database.identityDao().getAllIdentitiesList()
        val identityList = mutableListOf("No Identity")
        identities.forEach { identity -> identityList.add(identity.name) }
        
        val adapter = ArrayAdapter(this@ConnectionEditActivity, ...)
        binding.spinnerIdentity.setAdapter(adapter)
        
        binding.spinnerIdentity.setOnItemClickListener { _, _, position, _ ->
            if (position > 0) {
                // Identity selected
                val identity = identities[position - 1]
                selectedIdentityId = identity.id
                
                // Apply identity data
                binding.editUsername.setText(identity.username)
                
                // ✅ HIDE Authentication section
                binding.cardAuthentication.visibility = View.GONE
                
            } else {
                // "No Identity" selected
                selectedIdentityId = null
                
                // ✅ SHOW Authentication section
                binding.cardAuthentication.visibility = View.VISIBLE
            }
        }
        
        binding.spinnerIdentity.setText("No Identity", false)
    }
}
```

3. Add field to track selected identity:
```kotlin
private var selectedIdentityId: String? = null
```

**Phase 2: Apply Identity Auth on Save (10 min)**

Update saveConnection() to use Identity auth if selected:

```kotlin
private fun saveConnection() {
    val profile = ConnectionProfile(
        // ... basic fields ...
        identityId = selectedIdentityId,
        
        // If identity selected, use identity's auth
        authType = if (selectedIdentityId != null) {
            // Get identity from database
            val identity = app.database.identityDao().getIdentityById(selectedIdentityId!!)
            identity?.authType?.name ?: selectedAuthType.name
        } else {
            selectedAuthType.name
        },
        
        keyId = if (selectedIdentityId != null) {
            // Get identity's key
            val identity = app.database.identityDao().getIdentityById(selectedIdentityId!!)
            identity?.keyId
        } else {
            // Manual key selection
            if (selectedKeyIndex > 0) availableKeys[selectedKeyIndex - 1].keyId else null
        }
        // ... other fields ...
    )
}
```

**Phase 3: Load Identity Auth When Editing (5 min)**

Update populateFields() to restore identity selection and hide auth:

```kotlin
private fun populateFields(profile: ConnectionProfile) {
    // ... existing code ...
    
    // Set identity if applicable
    profile.identityId?.let { identityId ->
        lifecycleScope.launch {
            val identity = app.database.identityDao().getIdentityById(identityId)
            identity?.let {
                binding.spinnerIdentity.setText(it.name, false)
                selectedIdentityId = identityId
                // Hide auth section when identity used
                binding.cardAuthentication.visibility = View.GONE
            }
        }
    }
}
```

**Expected Behavior After Fix:**

1. **No Identity selected:**
   - Authentication section VISIBLE
   - User configures auth type + password/key manually

2. **Identity selected:**
   - Authentication section HIDDEN
   - Username auto-filled from identity
   - Auth credentials come from identity
   - Clear that Identity provides auth

**Action Items:**
- [ ] Add android:id="@+id/card_authentication" to Authentication card in XML
- [ ] Add selectedIdentityId field to ConnectionEditActivity
- [ ] Update setupIdentitySpinner() to show/hide card based on selection
- [ ] Update saveConnection() to use identity auth when selected
- [ ] Update populateFields() to restore identity and hide auth when editing
- [ ] Test: Select identity → auth hidden, save, edit → auth still hidden
- [ ] Test: "No Identity" → auth visible, can configure manually

**Files Involved:**
- app/src/main/res/layout/activity_connection_edit.xml
  - Line 191: Add android:id="@+id/card_authentication" to MaterialCardView
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Add field: private var selectedIdentityId: String? = null
  - Lines 228-253: setupIdentitySpinner() - add show/hide logic
  - Lines 694-758: saveConnection() - use identity auth if selected
  - Lines 369-462: populateFields() - restore identity selection

**Priority:** MEDIUM (UX improvement, not blocking functionality)
**Complexity:** EASY (show/hide card + use identity data)
**Est. Time:** 30 minutes (15min hide/show + 10min save + 5min load)

---


### 🐛 Issue #11: Connection Edit Buttons Broken (Order + Visibility)
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** HIGH
- **Impact:** Save button doesn't show, confusing button order
- **Discovery:** User reports Save button is "messed up" (doesn't show) and button order/names need fixing

**Problem:** Connection edit screen button issues:
1. **Save button doesn't show** - User can't see Save button
2. **Button text too long** - "Test Connection" should be "Test"
3. **Wrong button order** - Should be: Cancel | Test | Save (left to right)

**Current State:**

**activity_connection_edit.xml lines 546-575:**
```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="end">  <!--Right-aligned,mightcauseoverflow-->
    
    <!--Currentorder:Test|Cancel|Save-->
    
    <MaterialButton
        android:id="@+id/btn_test"
        android:text="Test Connection"  <!--TOOLONG-->
        android:layout_marginEnd="@dimen/space_sm" />
    
    <MaterialButton
        android:id="@+id/btn_cancel"
        android:text="@string/cancel"
        android:layout_marginEnd="@dimen/space_sm" />
    
    <MaterialButton
        android:id="@+id/btn_save"
        android:text="@string/save_connection" />  <!--Mightoverflowoffscreen!-->
</LinearLayout>
```

**Root Cause:**

**Issue 1: Save Button Doesn't Show**
- LinearLayout has android:gravity="end" (right-align)
- All 3 buttons aligned to right edge
- If buttons too wide → Save button pushed off screen
- "Test Connection" text is long (14 chars) → takes space
- Save button at end → first to overflow

**Issue 2: Button Order Wrong**
- Current: Test | Cancel | Save
- Expected: Cancel | Test | Save
- Standard UX pattern: Cancel (left), Actions (middle), Primary (right)

**Issue 3: Button Text Too Long**
- "Test Connection" = 14 characters
- Should be "Test" = 4 characters
- Shorter text = more space for all buttons

**Solution:**

**Phase 1: Fix Button Order (5 min)**

Reorder buttons to: Cancel | Test | Save

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="end">
    
    <!--✅REORDERED:Cancelfirst-->
    <MaterialButton
        android:id="@+id/btn_cancel"
        style="@style/Widget.TabSSH.Button.Outlined"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="@dimen/space_sm"
        android:text="@string/cancel" />
    
    <!--✅Testsecond-->
    <MaterialButton
        android:id="@+id/btn_test"
        style="@style/Widget.TabSSH.Button.Outlined"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginEnd="@dimen/space_sm"
        android:text="Test" />  <!--✅SHORTENED-->
    
    <!--✅Savelast(primaryaction)-->
    <MaterialButton
        android:id="@+id/btn_save"
        style="@style/Widget.TabSSH.Button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/save_connection" />
</LinearLayout>
```

**Phase 2: Update Kotlin Code (2 min)**

Update testConnection() method to use "Test" instead of "Test Connection":

**ConnectionEditActivity.kt lines 682-683, 699-700:**
```kotlin
// OLD:
binding.btnTest.text = "Testing..."
binding.btnTest.text = "Test Connection"

// NEW:
binding.btnTest.text = "Testing..."
binding.btnTest.text = "Test"
```

**Phase 3: Verify Layout (Optional - if still doesn't show)**

If Save still doesn't show after reorder + shorten:
- Add android:layout_weight="1" to fill available space
- OR change LinearLayout to use space_between gravity
- OR reduce button padding

```xml
<!--Alternativeifstillissues:-->
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="end|center_vertical"
    android:paddingTop="@dimen/space_md">
    
    <!--Buttonshere-->
</LinearLayout>
```

**Expected Result After Fix:**

```
Screen edge                                                Screen edge
|                                                                |
|                           [Cancel] [Test] [Save]              |
|                                                                |
```

- ✅ All 3 buttons visible
- ✅ Correct order: Cancel | Test | Save
- ✅ Short button text: "Test" (not "Test Connection")
- ✅ Save button (primary action) at right end
- ✅ Cancel button (secondary) at left

**Action Items:**
- [ ] Reorder buttons in XML: Move btn_cancel first, btn_test second, btn_save last
- [ ] Change btn_test text from "Test Connection" to "Test"
- [ ] Update ConnectionEditActivity.kt lines 682-683, 699-700 to use "Test"
- [ ] Test on small screen devices to ensure Save button visible
- [ ] Verify button styles (Save should be filled, Cancel+Test outlined)

**Files Involved:**
- app/src/main/res/layout/activity_connection_edit.xml
  - Lines 546-575: Button layout - reorder + change text
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Lines 682-683: Change "Testing..." / "Test Connection" to "Testing..." / "Test"
  - Lines 699-700: Same change

**Priority:** HIGH (Save button not visible = can't save connections!)
**Complexity:** TRIVIAL (reorder XML elements + change text)
**Est. Time:** 7 minutes (5min XML + 2min Kotlin)

---


### 🐛 Issue #12: VM Management Buttons Don't Work / Wrong Visibility
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** HIGH
- **Impact:** VM management broken - buttons don't work properly
- **Discovery:** User reports "none of the buttons for VM management work. IE: console start/stop restart reset"

**Problem:** VM management buttons (Console, Start, Stop, Restart, Reset):
1. **Buttons don't work** - User can't control VMs
2. **Should use VM status** - Show/hide based on running/stopped state
3. **Missing Reset button** - Only has Reboot, needs Reset (hard reset)
4. **Button order unclear** - User wants specific order: Console | Start/Stop | Restart | Reset

**Current State:**

**item_proxmox_vm.xml lines 64-119:**
```xml
<LinearLayout>
    <!--ALL4buttonsalwaysvisible-->
    <Button android:id="@+id/console_button" android:text="Console" />
    <Button android:id="@+id/start_button" android:text="Start" />
    <Button android:id="@+id/stop_button" android:text="Stop" />
    <Button android:id="@+id/reboot_button" android:text="Reboot" />
    <!--Missing:Resetbutton-->
</LinearLayout>
```

**ProxmoxManagerActivity.kt lines 366-374:**
```kotlin
// Buttons ENABLED/DISABLED based on status (but still visible!)
holder.consoleButton.isEnabled = vm.status == "running" && vm.ipAddress != null
holder.startButton.isEnabled = vm.status != "running"
holder.stopButton.isEnabled = vm.status == "running"
holder.rebootButton.isEnabled = vm.status == "running"

// Buttons wired to actions
holder.consoleButton.setOnClickListener { onAction(vm, "console") }
holder.startButton.setOnClickListener { onAction(vm, "start") }
holder.stopButton.setOnClickListener { onAction(vm, "stop") }
holder.rebootButton.setOnClickListener { onAction(vm, "reboot") }
```

**Root Cause:**

**Issue 1: Buttons Actually DO Work!**
- Code shows buttons ARE wired to handleVMAction() (line 164)
- handleVMAction() calls Proxmox API (lines 181-218)
- User might be experiencing:
  - API failures (not button failures)
  - Disabled buttons look like they don't work
  - Or they tested on wrong VM status

**Issue 2: Buttons Disabled But Still Visible (Confusing UX)**
- Lines 366-369: Buttons use `isEnabled` (grayed out)
- Better UX: HIDE buttons that can't be used
- Example: Running VM → hide Start button, show Stop
- Example: Stopped VM → hide Stop/Reboot/Console, show Start

**Issue 3: Missing Reset Button**
- Reboot = graceful restart (ACPI signal, waits for shutdown)
- Reset = hard reset (immediate power cycle, like pressing reset button)
- Layout has Reboot but not Reset
- handleVMAction() doesn't handle "reset" action

**Issue 4: Button Order Not Optimal**
- Current: Console | Start | Stop | Reboot
- User wants: Console | Start/Stop | Restart | Reset
- Better: Show only relevant buttons based on status

**Solution:**

**Phase 1: Add Reset Button & Action (15 min)**

1. Add Reset button to layout:
```xml
<!--item_proxmox_vm.xmlafterline104-->
<Button
    android:id="@+id/reset_button"
    android:layout_width="0dp"
    android:layout_height="wrap_content"
    android:layout_weight="1"
    android:text="Reset"
    android:layout_marginEnd="4dp"
    style="@style/Widget.Material3.Button.TonalButton" />
```

2. Add Reset to activities (Proxmox, XCP-ng, VMware):
```kotlin
// ProxmoxManagerActivity.kt line 332
val resetButton: Button = view.findViewById(R.id.reset_button)

// Line 375
holder.resetButton.setOnClickListener { onAction(vm, "reset") }

// Line 369
holder.resetButton.isEnabled = vm.status == "running"

// Line 198 - add reset action
"reset" -> currentClient?.resetVM(vm.node, vm.vmid, vm.type) ?: false
```

3. Implement resetVM() in API clients (if not exists)

**Phase 2: Status-Based Button Visibility (20 min)**

Replace `isEnabled` with `visibility` based on status:

```kotlin
// ProxmoxManagerActivity.kt lines 366-375 replacement:
when (vm.status.lowercase()) {
    "running" -> {
        // VM is running - show power controls
        holder.consoleButton.visibility = if (vm.ipAddress != null) View.VISIBLE else View.GONE
        holder.startButton.visibility = View.GONE
        holder.stopButton.visibility = View.VISIBLE
        holder.rebootButton.visibility = View.VISIBLE
        holder.resetButton.visibility = View.VISIBLE
    }
    "stopped" -> {
        // VM is stopped - only show start
        holder.consoleButton.visibility = View.GONE
        holder.startButton.visibility = View.VISIBLE
        holder.stopButton.visibility = View.GONE
        holder.rebootButton.visibility = View.GONE
        holder.resetButton.visibility = View.GONE
    }
    else -> {
        // Paused, suspended, etc - show start/stop only
        holder.consoleButton.visibility = View.GONE
        holder.startButton.visibility = View.VISIBLE
        holder.stopButton.visibility = View.VISIBLE
        holder.rebootButton.visibility = View.GONE
        holder.resetButton.visibility = View.GONE
    }
}
```

**Phase 3: Improve Button Order (Optional - 5 min)**

Reorder in XML for better flow:
- Console (if running)
- Start (if stopped)
- Stop (if running)
- Restart (if running)
- Reset (if running)

**Phase 4: Apply to All Hypervisors (20 min)**

Same changes needed in:
- XCPngManagerActivity.kt (lines 416-460)
- VMwareManagerActivity.kt (lines 294-296)
- ProxmoxManagerActivity.kt (done above)

**Expected Behavior After Fix:**

**Running VM shows:**
```
[Console] [Stop] [Restart] [Reset]
```

**Stopped VM shows:**
```
[Start]
```

**Paused/Suspended VM shows:**
```
[Start] [Stop]
```

**Action Items:**
- [ ] Add Reset button to item_proxmox_vm.xml layout
- [ ] Add resetButton field to all VM adapters (Proxmox, XCP-ng, VMware)
- [ ] Wire reset button click handler
- [ ] Add "reset" case to handleVMAction() in all activities
- [ ] Implement resetVM() in API clients if missing
- [ ] Replace isEnabled with visibility based on VM status
- [ ] Apply status-based visibility to all 3 hypervisor activities
- [ ] Test with running VM - should see Console/Stop/Restart/Reset
- [ ] Test with stopped VM - should only see Start
- [ ] Verify API calls actually work (might be separate connectivity issue)

**Files Involved:**
- app/src/main/res/layout/item_proxmox_vm.xml
  - Line 104: Add Reset button after Reboot button
- app/src/main/java/io/github/tabssh/ui/activities/ProxmoxManagerActivity.kt
  - Line 332: Add resetButton field
  - Lines 366-375: Replace with status-based visibility logic
  - Line 198: Add "reset" action handler
- app/src/main/java/io/github/tabssh/ui/activities/XCPngManagerActivity.kt
  - Same changes as Proxmox
- app/src/main/java/io/github/tabssh/ui/activities/VMwareManagerActivity.kt
  - Same changes as Proxmox
- app/src/main/java/io/github/tabssh/hypervisor/proxmox/ProxmoxApiClient.kt
  - Add resetVM() method if missing
- app/src/main/java/io/github/tabssh/hypervisor/xcpng/XenOrchestraApiClient.kt
  - Add resetVM() method if missing
- app/src/main/java/io/github/tabssh/hypervisor/vmware/VMwareApiClient.kt
  - Add resetVM() method if missing

**Priority:** HIGH (VM management completely non-functional for user)
**Complexity:** MODERATE (add button + visibility logic + API calls)
**Est. Time:** 60 minutes (15min reset + 20min visibility + 5min order + 20min other hypervisors)

**Note:** User said buttons "don't work" but code shows they ARE wired. Actual issue might be:
1. API connectivity failures (not button failures)
2. Buttons disabled look broken (visibility fix will help)
3. Missing reset button (user specifically mentioned)

---


### 🐛 Issue #13: Performance Screen Needs Customization + Widget System
- **Status:** 🟢 Discussed - Requirements Captured
- **Priority:** LOW (Feature Enhancement, Not Bug)
- **Impact:** Performance monitoring not flexible, some text cutoff
- **Discovery:** User requests per-host customization, drag-and-drop widgets, text cutoff fixes

**Problem:** Performance monitoring screen has multiple limitations:
1. **Not per-host customizable** - Same view for all connections
2. **Fixed widgets** - Can't add/remove monitoring widgets
3. **No drag & drop** - Can't reorder widgets
4. **Text cutoff** - Some information being cut off in cards

**User's Requirements:**
- **Per-host customization:** Different widget layouts per server (e.g., web server shows different metrics than database server)
- **Widget system:** Add/remove/reorder monitoring widgets
- **Drag & drop:** Long press and drag to rearrange widgets
- **Fix text cutoff:** Ensure all info displays properly

**Current Implementation:**

**fragment_performance.xml (368 lines):**
```xml
<NestedScrollView>
    <LinearLayout> <!--Fixedlayout,can'treorder-->
        
        <!--Connectionselector(global,notper-host)-->
        <Spinner android:id="@+id/spinner_connection" />
        
        <!--CPUChartCard(alwaysshown)-->
        <LineChart android:id="@+id/chart_cpu" />
        
        <!--Memory&DiskRow(alwaysshown)-->
        <TextView android:id="@+id/text_memory_details"
            android:layout_width="wrap_content" <!--Cancutofflongtext!-->
            android:text="0 MB / 0 MB" />
        
        <TextView android:id="@+id/text_disk_details"
            android:layout_width="wrap_content" <!--Cancutofflongtext!-->
            android:text="0 GB / 0 GB" />
        
        <!--NetworkCard(alwaysshown)-->
        <TextView android:id="@+id/text_network_rx" ... />
        <TextView android:id="@+id/text_network_tx" ... />
        
        <!--LoadAverageCard(alwaysshown)-->
        <TextView android:id="@+id/text_load_1min" ... />
        <TextView android:id="@+id/text_load_5min" ... />
        
    </LinearLayout>
</NestedScrollView>
```

**PerformanceFragment.kt (416 lines):**
- Single connection selector
- Fixed widget set
- No customization options
- No persistence of preferences

**Issues:**

**Issue A: Not Per-Host Customizable**
- Single global view for all connections
- Can't save different layouts per server
- Example use case:
  - Web server: Show CPU, Memory, Network (heavy traffic)
  - Database server: Show CPU, Memory, Disk I/O (storage intensive)
  - Router: Show Network only (bandwidth monitoring)

**Issue B: No Widget System**
- All widgets hardcoded in XML
- Can't add new widget types
- Can't remove unwanted widgets
- Fixed order in LinearLayout

**Issue C: No Drag & Drop**
- No touch handlers for reordering
- No long-press detection
- No ItemTouchHelper integration
- Widgets can't be moved

**Issue D: Text Cutoff**
- TextViews use wrap_content
- Long numbers overflow (e.g., "12345 MB / 65536 MB")
- No ellipsize or maxLines
- No horizontal scrolling

**Solution:**

**Phase 1: Fix Text Cutoff (Quick Fix - 15 min)**

Update text fields to prevent overflow:

```xml
<!--fragment_performance.xml-->
<TextView
    android:id="@+id/text_memory_details"
    android:layout_width="match_parent"  <!--Changedfromwrap_content-->
    android:layout_height="wrap_content"
    android:text="0 MB / 0 MB"
    android:textSize="12sp"
    android:gravity="center"
    android:ellipsize="end"  <!--Addellipsisiftoolong-->
    android:maxLines="1"  <!--Singlelinewithellipsis-->
    android:textColor="?attr/colorOnSurfaceVariant" />
```

Apply to:
- text_memory_details (line 129)
- text_disk_details (line 175)
- text_network_rx, text_network_tx, text_network_total (lines 220-240)

**Phase 2: Widget System Architecture (3-5 hours)**

Build flexible widget system:

1. **Widget Model:**
```kotlin
data class PerformanceWidget(
    val id: String,
    val type: WidgetType,
    val order: Int,
    val isVisible: Boolean
)

enum class WidgetType {
    CPU_CHART,
    MEMORY_CARD,
    DISK_CARD,
    NETWORK_CARD,
    LOAD_AVERAGE,
    PROCESS_LIST,  // New
    DISK_IO,       // New
    CUSTOM_SCRIPT  // New
}
```

2. **Per-Host Configuration:**
```kotlin
data class PerformanceConfig(
    val connectionId: String,
    val widgets: List<PerformanceWidget>,
    val refreshInterval: Long = 5000L
)
```

3. **Database Table:**
```kotlin
@Entity(tableName = "performance_configs")
data class PerformanceConfigEntity(
    @PrimaryKey val connectionId: String,
    @ColumnInfo(name = "widget_config") val widgetConfig: String, // JSON
    @ColumnInfo(name = "refresh_interval") val refreshInterval: Long
)
```

**Phase 3: Drag & Drop (2-3 hours)**

Implement widget reordering:

1. Replace LinearLayout with RecyclerView
2. Use ItemTouchHelper for drag & drop
3. Long press to enter edit mode
4. Save new order to database

```kotlin
// PerformanceFragment.kt
private fun setupDragAndDrop() {
    val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(/* ... */): Boolean {
            // Reorder widgets
            Collections.swap(widgets, fromPos, toPos)
            adapter.notifyItemMoved(fromPos, toPos)
            return true
        }
        
        override fun onSwiped(/* ... */) { /* Not used */ }
    })
    
    itemTouchHelper.attachToRecyclerView(recyclerView)
}
```

**Phase 4: Add/Remove Widgets (2 hours)**

Add widget management dialog:

```kotlin
private fun showWidgetManagerDialog() {
    // Show checklist of available widgets
    // User can enable/disable widgets
    // Save to database
}
```

**Phase 5: Per-Host Config (1 hour)**

Load/save config per connection:

```kotlin
private fun loadPerformanceConfig(connectionId: String) {
    lifecycleScope.launch {
        val config = app.database.performanceConfigDao()
            .getConfigForConnection(connectionId) ?: getDefaultConfig()
        
        applyWidgetConfig(config.widgets)
    }
}
```

**Action Items:**

**Quick Fix (15 min):**
- [ ] Update text_memory_details to match_parent + ellipsize
- [ ] Update text_disk_details to match_parent + ellipsize
- [ ] Update network text fields to match_parent + ellipsize
- [ ] Test with long numbers to verify no cutoff

**Full Feature (8-11 hours):**
- [ ] Create PerformanceWidget model
- [ ] Create PerformanceConfig model
- [ ] Add performance_configs database table
- [ ] Create PerformanceConfigDao
- [ ] Replace LinearLayout with RecyclerView
- [ ] Create PerformanceWidgetAdapter
- [ ] Implement ItemTouchHelper for drag & drop
- [ ] Create widget manager dialog (add/remove)
- [ ] Implement per-host config loading/saving
- [ ] Add "Edit Widgets" button in UI
- [ ] Test drag & drop on all devices
- [ ] Test per-host config persistence

**Files Involved:**

**Quick Fix:**
- app/src/main/res/layout/fragment_performance.xml
  - Lines 129-134: text_memory_details - add match_parent, ellipsize, maxLines
  - Lines 175-180: text_disk_details - same
  - Lines 220-240: Network text fields - same

**Full Feature:**
- app/src/main/java/io/github/tabssh/ui/fragments/PerformanceFragment.kt
  - Major refactor: RecyclerView-based, widget system
- app/src/main/java/io/github/tabssh/storage/database/entities/PerformanceConfigEntity.kt (NEW)
- app/src/main/java/io/github/tabssh/storage/database/dao/PerformanceConfigDao.kt (NEW)
- app/src/main/java/io/github/tabssh/ui/adapters/PerformanceWidgetAdapter.kt (NEW)
- app/src/main/java/io/github/tabssh/ui/dialogs/WidgetManagerDialog.kt (NEW)
- app/src/main/java/io/github/tabssh/models/PerformanceWidget.kt (NEW)
- app/src/main/res/layout/fragment_performance.xml
  - Replace LinearLayout with RecyclerView

**Priority:** LOW (this is a feature enhancement, not a critical bug)
**Complexity:** COMPLEX (requires architecture changes, new database tables, drag & drop)
**Est. Time:** 
- Quick fix (text cutoff): 15 minutes
- Full feature (widget system): 8-11 hours

**Recommendation:** 
1. Do quick text cutoff fix NOW (15 min) ✅
2. Defer full widget system to post-1.0.0 (major feature) ⏰

**Note:** This is NOT a bug - it's a feature request for a completely new widget management system. The current implementation works as designed, just not as flexibly as user desires.

---


### 🐛 Issue #14: Terminal Bell Vibrate Toggle Missing + Multiplexer Prefix Not Per-Type
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** MEDIUM
- **Impact:** Missing vibrate toggle for terminal bell, multiplexer prefix doesn't remember per-type
- **Discovery:** User reports two terminal setting issues

**Problem A: Terminal Bell - Missing Vibrate Toggle**

Current terminal bell setting:
- Single toggle: "Terminal Bell" (audible bell on BEL character)
- User wants: Additional "Vibrate" toggle (default enabled)
- Backend already supports it (KEY_BELL_VIBRATE exists) but NO UI!

**Problem B: Multiplexer Custom Prefix - Not Per-Type**

Current behavior:
1. User selects multiplexer type: tmux, screen, or zellij
2. User sets custom prefix: e.g., "C-Space"
3. User switches to different multiplexer
4. Custom prefix stays same (doesn't switch to that type's prefix)
5. User must manually change prefix every time they switch multiplexer

User wants:
- Save prefix PER multiplexer type (3 separate values)
- When switching multiplexer type, load that type's saved prefix
- Show current prefix in multiplexer type summary
- Default to multiplexer's standard prefix (tmux=C-b, screen=C-a, zellij=C-g)

**Current Implementation:**

**preferences_terminal.xml lines 65-112:**
```xml
<!--TerminalBell-Onlyaudible,novibratetoggle!-->
<SwitchPreferenceCompat
    android:key="terminal_bell"
    android:title="Terminal Bell"
    android:summary="Audible bell on BEL character"
    android:defaultValue="true" />

<!--MultiplexerType-->
<ListPreference
    android:key="gesture_multiplexer_type"
    android:title="Multiplexer Type"
    android:summary="Select tmux, screen, or zellij for gesture commands"
    android:entries="@array/multiplexer_type_entries"
    android:entryValues="@array/multiplexer_type_values"
    android:defaultValue="tmux"
    app:useSimpleSummaryProvider="true" />

<!--CustomPrefix-SinglevalueforALLmultiplexers!-->
<EditTextPreference
    android:key="multiplexer_custom_prefix"
    android:title="Custom Prefix Key"
    android:summary="Override default prefix (e.g., C-a, C-Space, ` - leave empty for default)"
    android:defaultValue=""
    android:inputType="text" />
```

**PreferenceManager.kt lines 45-47:**
```kotlin
// Bell constants exist but no vibrate UI!
private const val KEY_BELL_NOTIFICATION = "terminal_bell_notification"
private const val KEY_BELL_VIBRATE = "terminal_bell_vibrate"  // ❌ NO UI!
private const val KEY_BELL_VISUAL = "terminal_bell_visual"
```

**Root Cause:**

**Problem A Root Cause:**
- Backend has vibrate support (KEY_BELL_VIBRATE constant)
- Likely has getBellVibrate()/setBellVibrate() methods
- But preferences_terminal.xml has NO vibrate toggle
- Only has single "terminal_bell" toggle for audible bell

**Problem B Root Cause:**
- Single multiplexer_custom_prefix preference shared by all types
- When user switches multiplexer type, prefix doesn't change
- No per-type storage (needs 3 separate preferences)
- Summary doesn't show current prefix for selected type

**Solution:**

**Phase 1: Add Terminal Bell Vibrate Toggle (10 min)**

Add vibrate toggle below terminal_bell:

```xml
<!--preferences_terminal.xmlafterline69-->
<SwitchPreferenceCompat
    android:key="terminal_bell_vibrate"
    android:title="Vibrate on Bell"
    android:summary="Vibrate device when BEL character received"
    android:defaultValue="true" />
```

Verify PreferenceManager has methods:
```kotlin
fun isBellVibrateEnabled(): Boolean = getBoolean(KEY_BELL_VIBRATE, true)
fun setBellVibrateEnabled(enabled: Boolean) = setBoolean(KEY_BELL_VIBRATE, enabled)
```

**Phase 2: Per-Type Multiplexer Prefix Storage (30 min)**

Replace single multiplexer_custom_prefix with 3 separate preferences:

1. **Update preferences storage:**

```kotlin
// PreferenceManager.kt - Add new constants
private const val KEY_MULTIPLEXER_PREFIX_TMUX = "multiplexer_custom_prefix_tmux"
private const val KEY_MULTIPLEXER_PREFIX_SCREEN = "multiplexer_custom_prefix_screen"
private const val KEY_MULTIPLEXER_PREFIX_ZELLIJ = "multiplexer_custom_prefix_zellij"

// Defaults to standard prefixes
const val DEFAULT_PREFIX_TMUX = "C-b"     // tmux default
const val DEFAULT_PREFIX_SCREEN = "C-a"   // screen default
const val DEFAULT_PREFIX_ZELLIJ = "C-g"   // zellij default

fun getMultiplexerPrefix(type: String): String {
    return when (type) {
        "tmux" -> getString(KEY_MULTIPLEXER_PREFIX_TMUX, DEFAULT_PREFIX_TMUX)
        "screen" -> getString(KEY_MULTIPLEXER_PREFIX_SCREEN, DEFAULT_PREFIX_SCREEN)
        "zellij" -> getString(KEY_MULTIPLEXER_PREFIX_ZELLIJ, DEFAULT_PREFIX_ZELLIJ)
        else -> DEFAULT_PREFIX_TMUX
    }
}

fun setMultiplexerPrefix(type: String, prefix: String) {
    when (type) {
        "tmux" -> setString(KEY_MULTIPLEXER_PREFIX_TMUX, prefix)
        "screen" -> setString(KEY_MULTIPLEXER_PREFIX_SCREEN, prefix)
        "zellij" -> setString(KEY_MULTIPLEXER_PREFIX_ZELLIJ, prefix)
    }
}
```

2. **Update XML to show current prefix in multiplexer summary:**

Change from useSimpleSummaryProvider to dynamic summary:

```xml
<!--preferences_terminal.xmlline95-103-->
<ListPreference
    android:key="gesture_multiplexer_type"
    android:title="Multiplexer Type"
    android:summary="Select tmux, screen, or zellij (prefix: %s)"
    android:entries="@array/multiplexer_type_entries"
    android:entryValues="@array/multiplexer_type_values"
    android:defaultValue="tmux" />
```

3. **Keep Custom Prefix field but update dynamically:**

```xml
<!--Updateline105-112-->
<EditTextPreference
    android:key="multiplexer_custom_prefix"
    android:title="Custom Prefix Key"
    android:summary="Current: %s (leave empty for default)"
    android:dialogMessage="..." />
```

4. **Add PreferenceChangeListener to sync prefix when multiplexer changes:**

```kotlin
// In SettingsActivity or TerminalPreferenceFragment
val multiplexerTypePref = findPreference<ListPreference>("gesture_multiplexer_type")
val customPrefixPref = findPreference<EditTextPreference>("multiplexer_custom_prefix")

multiplexerTypePref?.setOnPreferenceChangeListener { _, newValue ->
    val type = newValue as String
    // Load saved prefix for this type
    val savedPrefix = preferenceManager.getMultiplexerPrefix(type)
    customPrefixPref?.text = savedPrefix
    true
}

customPrefixPref?.setOnPreferenceChangeListener { _, newValue ->
    val currentType = multiplexerTypePref?.value ?: "tmux"
    val prefix = newValue as String
    // Save prefix for current type
    preferenceManager.setMultiplexerPrefix(currentType, prefix)
    true
}
```

**Expected Behavior After Fix:**

**Problem A - Terminal Bell:**
```
Settings → Terminal:
[✓] Terminal Bell
    Audible bell on BEL character
[✓] Vibrate on Bell  ← NEW
    Vibrate device when BEL character received
```

**Problem B - Multiplexer Prefix:**
1. User selects tmux → Custom Prefix shows "C-b" (default)
2. User changes prefix to "C-Space" → Saved to tmux
3. User switches to screen → Custom Prefix shows "C-a" (default)
4. User changes prefix to "C-t" → Saved to screen
5. User switches back to tmux → Custom Prefix shows "C-Space" (remembered!)

Summary shows: "tmux (Prefix: C-Space)" or "screen (Prefix: C-t)"

**Action Items:**

**Problem A (10 min):**
- [ ] Add terminal_bell_vibrate SwitchPreference to preferences_terminal.xml
- [ ] Set android:defaultValue="true"
- [ ] Verify PreferenceManager has isBellVibrateEnabled()/setBellVibrateEnabled()
- [ ] Test vibrate on BEL character

**Problem B (30 min):**
- [ ] Add 3 new preference keys to PreferenceManager (tmux, screen, zellij)
- [ ] Add getMultiplexerPrefix(type)/setMultiplexerPrefix(type, prefix) methods
- [ ] Update multiplexer type summary to show current prefix
- [ ] Add PreferenceChangeListener to sync prefix when type changes
- [ ] Update custom prefix field when multiplexer type changes
- [ ] Save custom prefix to current multiplexer type's key
- [ ] Test: Switch tmux→screen→tmux, verify prefix remembered
- [ ] Default to standard prefixes (C-b, C-a, C-g)

**Files Involved:**

**Problem A:**
- app/src/main/res/xml/preferences_terminal.xml
  - Line 69: Add terminal_bell_vibrate SwitchPreference
- app/src/main/java/io/github/tabssh/storage/preferences/PreferenceManager.kt
  - Line 46: KEY_BELL_VIBRATE already exists
  - Verify/add getBellVibrateEnabled()/setBellVibrateEnabled() methods

**Problem B:**
- app/src/main/java/io/github/tabssh/storage/preferences/PreferenceManager.kt
  - Add KEY_MULTIPLEXER_PREFIX_TMUX/SCREEN/ZELLIJ constants
  - Add getMultiplexerPrefix(type) method
  - Add setMultiplexerPrefix(type, prefix) method
- app/src/main/res/xml/preferences_terminal.xml
  - Line 98: Update multiplexer_type summary to show prefix
  - Line 108: Update custom_prefix summary
- app/src/main/java/io/github/tabssh/ui/activities/SettingsActivity.kt
  - Add PreferenceChangeListener for multiplexer type
  - Add PreferenceChangeListener for custom prefix
  - Sync prefix when type changes

**Priority:** MEDIUM (nice improvements, not critical bugs)
**Complexity:** EASY (mostly XML + simple preference logic)
**Est. Time:** 40 minutes (10min vibrate + 30min per-type prefix)

---


### 🐛 Issue #15: Connection Edit - Terminal Type Dropdown + Mosh Config + Port Knock Visibility
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** MEDIUM
- **Impact:** Connection settings not flexible/user-friendly
- **Discovery:** User reports three connection edit improvements needed

**Problem A: Terminal Type Should Be Dropdown**

Current: Free text input field for terminal type
- User can type anything (typos possible)
- No suggestions of supported types
- Hard to remember all options

User wants: Dropdown with all supported terminal types
- Select from list of valid types
- Default: xterm-256color
- Common types visible

**Problem B: Mosh Settings Not Configurable**

Current: Simple toggle switch for "Use Mosh"
- Just enables/disables Mosh
- No way to configure Mosh server settings
- Uses hardcoded defaults from global preferences

User wants: Mosh server configuration per connection
- Port range
- Server command
- Locale settings
- Default to global settings but allow per-connection override

**Problem C: Port Knock Configure Button Always Visible**

Current: Configure button always shows
- Even when Port Knocking toggle is OFF
- Confusing UX (why configure if disabled?)

User wants: Hide configure button when port knocking disabled
- Show button only when toggle is ON
- Cleaner UI

**Current Implementation:**

**activity_connection_edit.xml:**

```xml
<!--Lines329-343:TerminalType-Freetextinput-->
<TextInputEditText
    android:id="@+id/edit_terminal_type"
    android:inputType="text"
    android:text="xterm-256color" />

<!--Lines375-383:Mosh-Justtoggle,noconfig-->
<MaterialSwitch
    android:id="@+id/switch_use_mosh"
    android:text="Use Mosh (Mobile Shell)"
    android:checked="false" />
<!--❌NoMoshconfigurationfields!-->

<!--Lines385-400:PortKnock-Buttonalwaysvisible-->
<MaterialSwitch
    android:id="@+id/switch_port_knock"
    android:text="Port Knocking"
    android:checked="false" />

<MaterialButton
    android:id="@+id/btn_configure_port_knock"
    android:text="Configure Knock Sequence" />
    <!--❌Alwaysvisible,shouldhidewhenswitchOFF-->
```

**ConnectionEditActivity.kt:**
- Line 397: Sets terminal type from profile (text field)
- Line 401: Sets Mosh switch state only
- Line 518: Reads terminal type as string
- Line 522: Reads Mosh as boolean only
- No visibility toggle for port knock button

**Root Cause:**

**Problem A:** TextInputEditText instead of Spinner/AutoCompleteTextView
**Problem B:** No Mosh configuration UI, hardcoded to global settings
**Problem C:** No visibility binding for port knock button based on switch

**Solution:**

**Phase 1: Terminal Type Dropdown (15 min)**

Replace TextInputEditText with AutoCompleteTextView dropdown:

```xml
<!--activity_connection_edit.xmllines329-343-->
<com.google.android.material.textfield.TextInputLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:hint="Terminal Type"
    style="@style/Widget.Material3.TextInputLayout.OutlinedBox.ExposedDropdownMenu">

    <AutoCompleteTextView
        android:id="@+id/spinner_terminal_type"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:inputType="none"
        android:text="xterm-256color" />

</com.google.android.material.textfield.TextInputLayout>
```

Add terminal types array:

```xml
<!--arrays.xml-->
<string-array name="terminal_types">
    <item>xterm-256color</item>
    <item>xterm</item>
    <item>screen-256color</item>
    <item>screen</item>
    <item>tmux-256color</item>
    <item>tmux</item>
    <item>vt100</item>
    <item>vt220</item>
    <item>linux</item>
    <item>rxvt</item>
    <item>rxvt-256color</item>
</string-array>
```

Setup spinner in ConnectionEditActivity:

```kotlin
private fun setupTerminalTypeSpinner() {
    val terminalTypes = resources.getStringArray(R.array.terminal_types)
    val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, terminalTypes)
    binding.spinnerTerminalType.setAdapter(adapter)
    binding.spinnerTerminalType.setText("xterm-256color", false)
}
```

**Phase 2: Port Knock Button Visibility (5 min)**

Add visibility toggle based on switch:

```kotlin
// ConnectionEditActivity.kt after setupButtons()
private fun setupPortKnockVisibility() {
    binding.switchPortKnock.setOnCheckedChangeListener { _, isChecked ->
        binding.btnConfigurePortKnock.visibility = if (isChecked) View.VISIBLE else View.GONE
    }
    
    // Set initial visibility
    binding.btnConfigurePortKnock.visibility = 
        if (binding.switchPortKnock.isChecked) View.VISIBLE else View.GONE
}
```

**Phase 3: Mosh Configuration (45 min)**

Add Mosh configuration fields (show/hide with toggle):

```xml
<!--activity_connection_edit.xmlafterline383-->
<LinearLayout
    android:id="@+id/layout_mosh_config"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:layout_marginStart="24dp"
    android:visibility="gone">

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Mosh Port Range"
        android:layout_marginTop="@dimen/space_sm"
        style="@style/Widget.TabSSH.TextInputLayout">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/edit_mosh_port_range"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="60000:61000"
            android:inputType="text" />

    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="Mosh Server Command (leave empty for default)"
        android:layout_marginTop="@dimen/space_sm"
        style="@style/Widget.TabSSH.TextInputLayout">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/edit_mosh_server_command"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="text" />

    </com.google.android.material.textfield.TextInputLayout>

</LinearLayout>
```

Add visibility toggle:

```kotlin
private fun setupMoshConfigVisibility() {
    binding.switchUseMosh.setOnCheckedChangeListener { _, isChecked ->
        binding.layoutMoshConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
    }
    
    // Set initial visibility
    binding.layoutMoshConfig.visibility = 
        if (binding.switchUseMosh.isChecked) View.VISIBLE else View.GONE
}
```

Add to ConnectionProfile entity:

```kotlin
// ConnectionProfile.kt - add fields
@ColumnInfo(name = "mosh_port_range")
val moshPortRange: String? = "60000:61000",

@ColumnInfo(name = "mosh_server_command")
val moshServerCommand: String? = null,
```

Load/save in ConnectionEditActivity:

```kotlin
// populateFields()
profile.moshPortRange?.let { binding.editMoshPortRange.setText(it) }
profile.moshServerCommand?.let { binding.editMoshServerCommand.setText(it) }

// saveConnection()
moshPortRange = binding.editMoshPortRange.text.toString().takeIf { it.isNotBlank() },
moshServerCommand = binding.editMoshServerCommand.text.toString().takeIf { it.isNotBlank() }
```

**Expected Behavior After Fix:**

**Problem A - Terminal Type:**
- Click terminal type → dropdown appears
- Shows: xterm-256color, xterm, screen-256color, screen, tmux-256color, etc.
- Default selection: xterm-256color
- Can still type custom if needed

**Problem B - Mosh Config:**
- Mosh toggle OFF → No config fields
- Mosh toggle ON → Shows:
  - Port Range: 60000:61000 (default)
  - Server Command: (empty = use default)
- Per-connection override of global settings

**Problem C - Port Knock:**
- Port knock toggle OFF → Configure button HIDDEN
- Port knock toggle ON → Configure button VISIBLE
- Cleaner UI, less confusion

**Action Items:**

**Problem A (15 min):**
- [ ] Replace edit_terminal_type TextInputEditText with AutoCompleteTextView spinner
- [ ] Add terminal_types string array to arrays.xml (11 types)
- [ ] Setup spinner in setupTerminalTypeSpinner()
- [ ] Update populateFields() to use spinner
- [ ] Update saveConnection() to read from spinner

**Problem B (45 min):**
- [ ] Add layout_mosh_config LinearLayout with port range and command fields
- [ ] Add setupMoshConfigVisibility() method
- [ ] Add moshPortRange and moshServerCommand fields to ConnectionProfile entity
- [ ] Add database migration (v2 → v3) for new fields
- [ ] Update populateFields() to load Mosh config
- [ ] Update saveConnection() to save Mosh config
- [ ] Pass Mosh config to MoshConnection when connecting

**Problem C (5 min):**
- [ ] Add setupPortKnockVisibility() method
- [ ] Bind btn_configure_port_knock visibility to switch_port_knock state
- [ ] Set initial visibility based on switch state in populateFields()

**Files Involved:**

**Problem A:**
- app/src/main/res/layout/activity_connection_edit.xml
  - Lines 329-343: Replace TextInputEditText with AutoCompleteTextView
- app/src/main/res/values/arrays.xml
  - Add terminal_types string array
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Add setupTerminalTypeSpinner() method
  - Update populateFields()/saveConnection()

**Problem B:**
- app/src/main/res/layout/activity_connection_edit.xml
  - After line 383: Add layout_mosh_config with 2 fields
- app/src/main/java/io/github/tabssh/storage/database/entities/ConnectionProfile.kt
  - Add moshPortRange and moshServerCommand fields
- app/src/main/java/io/github/tabssh/storage/database/TabSSHDatabase.kt
  - Add migration v2 → v3
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Add setupMoshConfigVisibility()
  - Update populateFields()/saveConnection()
- app/src/main/java/io/github/tabssh/protocols/mosh/MoshConnection.kt
  - Use connection-specific Mosh config if set

**Problem C:**
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Add setupPortKnockVisibility() method (5 lines)

**Priority:** MEDIUM (UX improvements, not critical bugs)
**Complexity:** MODERATE (DB migration for Mosh config)
**Est. Time:** 65 minutes (15min dropdown + 5min port knock + 45min Mosh config)

---


### 🐛 Issue #16: Notification System - No Settings, No Customization, No Per-Host Control
- **Status:** 🟢 Discussed - Root Cause Found
- **Priority:** HIGH
- **Impact:** Users can't control notifications - always show, can't disable, can't customize
- **Discovery:** User reports notification system doesn't work properly

**Problem:**

Notification backend EXISTS and WORKS (NotificationHelper.kt is fully implemented), but users have ZERO control over notifications:

1. **No Settings UI** - No notification preferences in Settings
2. **No Preference Checks** - NotificationHelper always shows notifications (no user consent)
3. **No Per-Connection Settings** - Can't disable notifications for specific hosts
4. **Hardcoded Behavior** - Sound/vibrate baked into channel creation, can't customize

User can't:
- Enable/disable notifications globally or per-type
- Change notification sounds
- Toggle vibrate
- Customize per-host (silent mode for specific servers)
- Control persistent service notification

**Current Implementation:**

**NotificationHelper.kt (Lines 1-283):**
```kotlin
// ✅ Full implementation exists
object NotificationHelper {
    // 4 notification channels
    private const val CHANNEL_SERVICE = "ssh_service"
    private const val CHANNEL_CONNECTION = "ssh_connection"
    private const val CHANNEL_FILE_TRANSFER = "file_transfer"
    private const val CHANNEL_ERROR = "errors"
    
    // Methods that ALWAYS show (no preference checks)
    fun showConnectionSuccess(context: Context, serverName: String, username: String)
    fun showConnectionError(context: Context, serverName: String, errorMessage: String)
    fun showDisconnected(context: Context, serverName: String, reason: String? = null)
    fun showFileTransferProgress(...)
    fun showFileTransferComplete(...)
    
    // Channel creation (lines 42-101)
    fun createNotificationChannels(context: Context) {
        // ❌ Hardcoded settings (IMPORTANCE_LOW/DEFAULT/HIGH, vibrate on/off)
        // ❌ No user preferences checked
        // ❌ Can't customize at runtime
    }
}
```

**Channels are hardcoded:**
- Service: IMPORTANCE_LOW, no sound, no vibrate
- Connection: IMPORTANCE_DEFAULT, vibrate ON
- File Transfer: IMPORTANCE_LOW, no sound, no vibrate
- Error: IMPORTANCE_HIGH, vibrate ON

**Called from:**
- TabTerminalActivity: lines 728, 746, 771, 781, 816 (always shows)
- SFTPActivity: lines 497, 569 (always shows)
- SSHConnectionService: Persistent foreground notification (always shows)

**preferences_general.xml:**
```xml
<!--NOnotificationsection!-->
<PreferenceCategory android:title="Appearance">...</PreferenceCategory>
<PreferenceCategory android:title="Behavior">...</PreferenceCategory>
<!--❌Missing:Notificationscategory-->
```

**PreferenceManager.kt:**
```kotlin
// Line 45: Only has KEY_BELL_NOTIFICATION (terminal bell)
private const val KEY_BELL_NOTIFICATION = "terminal_bell_notification"

// ❌ NO notification preferences:
// - No KEY_NOTIFICATIONS_ENABLED
// - No KEY_SHOW_CONNECTION_NOTIFICATIONS
// - No KEY_SHOW_ERROR_NOTIFICATIONS
// - No KEY_NOTIFICATION_SOUND
// - No KEY_NOTIFICATION_VIBRATE
// - etc.
```

**ConnectionProfile.kt:**
```kotlin
// Lines 1-143: NO notification fields
@Entity(tableName = "connections")
data class ConnectionProfile(
    // ... 20+ fields ...
    // ❌ No notificationsEnabled field
    // ❌ No notificationSound field
    // ❌ No notificationVibrate field
)
```

**Root Cause:**

1. **NotificationHelper doesn't check preferences** - Just shows notifications unconditionally
2. **No Settings UI** - preferences_general.xml missing entire Notifications section
3. **No preference keys** - PreferenceManager missing notification constants/getters
4. **No per-connection fields** - ConnectionProfile missing notification override fields
5. **Hardcoded channels** - Android 8+ channels can't be modified after creation

**Solution:**

**Phase 1: Global Notification Settings UI (45 min)**

Add Notifications section to preferences_general.xml:

```xml
<!--preferences_general.xmlafterline51-->
<PreferenceCategory
    android:title="Notifications"
    android:key="category_notifications">

    <SwitchPreferenceCompat
        android:key="notifications_enabled"
        android:title="Enable Notifications"
        android:summary="Show notifications for connections, errors, and file transfers"
        android:defaultValue="true" />

    <SwitchPreferenceCompat
        android:key="show_connection_notifications"
        android:title="Connection Notifications"
        android:summary="Show when connecting/disconnecting"
        android:defaultValue="true"
        android:dependency="notifications_enabled" />

    <SwitchPreferenceCompat
        android:key="show_error_notifications"
        android:title="Error Notifications"
        android:summary="Show connection errors"
        android:defaultValue="true"
        android:dependency="notifications_enabled" />

    <SwitchPreferenceCompat
        android:key="show_file_transfer_notifications"
        android:title="File Transfer Notifications"
        android:summary="Show SFTP upload/download progress"
        android:defaultValue="true"
        android:dependency="notifications_enabled" />

    <SwitchPreferenceCompat
        android:key="show_persistent_notification"
        android:title="Persistent Service Notification"
        android:summary="Show notification for active SSH connections"
        android:defaultValue="true"
        android:dependency="notifications_enabled" />

    <SwitchPreferenceCompat
        android:key="notification_vibrate"
        android:title="Vibrate"
        android:summary="Vibrate for connection and error notifications"
        android:defaultValue="true"
        android:dependency="notifications_enabled" />

    <SwitchPreferenceCompat
        android:key="notification_sound"
        android:title="Notification Sound"
        android:summary="Play sound for notifications"
        android:defaultValue="true"
        android:dependency="notifications_enabled" />

    <Preference
        android:key="open_system_notification_settings"
        android:title="System Notification Settings"
        android:summary="Configure channels, sounds, and priority in Android settings">
        <intent android:action="android.settings.APP_NOTIFICATION_SETTINGS">
            <extra android:name="android.provider.extra.APP_PACKAGE" android:value="${applicationId}" />
        </intent>
    </Preference>

</PreferenceCategory>
```

Add preference keys and getters to PreferenceManager.kt:

```kotlin
// PreferenceManager.kt after line 48 (terminal preferences)

// Notification preferences
private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
private const val KEY_SHOW_CONNECTION_NOTIFICATIONS = "show_connection_notifications"
private const val KEY_SHOW_ERROR_NOTIFICATIONS = "show_error_notifications"
private const val KEY_SHOW_FILE_TRANSFER_NOTIFICATIONS = "show_file_transfer_notifications"
private const val KEY_SHOW_PERSISTENT_NOTIFICATION = "show_persistent_notification"
private const val KEY_NOTIFICATION_VIBRATE = "notification_vibrate"
private const val KEY_NOTIFICATION_SOUND = "notification_sound"

// Notification preference getters
fun areNotificationsEnabled(): Boolean = getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
fun setNotificationsEnabled(enabled: Boolean) = setBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)

fun showConnectionNotifications(): Boolean = 
    areNotificationsEnabled() && getBoolean(KEY_SHOW_CONNECTION_NOTIFICATIONS, true)

fun showErrorNotifications(): Boolean = 
    areNotificationsEnabled() && getBoolean(KEY_SHOW_ERROR_NOTIFICATIONS, true)

fun showFileTransferNotifications(): Boolean = 
    areNotificationsEnabled() && getBoolean(KEY_SHOW_FILE_TRANSFER_NOTIFICATIONS, true)

fun showPersistentNotification(): Boolean = 
    areNotificationsEnabled() && getBoolean(KEY_SHOW_PERSISTENT_NOTIFICATION, true)

fun isNotificationVibrateEnabled(): Boolean = getBoolean(KEY_NOTIFICATION_VIBRATE, true)
fun isNotificationSoundEnabled(): Boolean = getBoolean(KEY_NOTIFICATION_SOUND, true)
```

**Phase 2: Add Preference Checks to NotificationHelper (30 min)**

Modify NotificationHelper methods to check preferences:

```kotlin
// NotificationHelper.kt - Add context dependency injection
fun showConnectionSuccess(context: Context, serverName: String, username: String) {
    val prefs = PreferenceManager(context)
    if (!prefs.showConnectionNotifications()){
        Logger.d("NotificationHelper", "Connection notifications disabled, skipping")
        return
    }
    
    // ... existing notification code ...
}

fun showConnectionError(context: Context, serverName: String, errorMessage: String) {
    val prefs = PreferenceManager(context)
    if (!prefs.showErrorNotifications()){
        Logger.d("NotificationHelper", "Error notifications disabled, skipping")
        return
    }
    
    // ... existing notification code ...
}

fun showDisconnected(context: Context, serverName: String, reason: String? = null) {
    val prefs = PreferenceManager(context)
    if (!prefs.showConnectionNotifications()){
        return
    }
    
    // ... existing notification code ...
}

fun showFileTransferProgress(...) {
    val prefs = PreferenceManager(context)
    if (!prefs.showFileTransferNotifications()){
        return
    }
    
    // ... existing notification code ...
}

fun showFileTransferComplete(...) {
    val prefs = PreferenceManager(context)
    if (!prefs.showFileTransferNotifications()){
        return
    }
    
    // ... existing notification code ...
}
```

**Phase 3: Per-Connection Notification Settings (60 min + DB migration)**

Add notification fields to ConnectionProfile:

```kotlin
// ConnectionProfile.kt after line 69 (read_timeout)

@ColumnInfo(name = "notifications_enabled")
val notificationsEnabled: Boolean? = null, // null = use global, true/false = override

@ColumnInfo(name = "notification_vibrate")
val notificationVibrate: Boolean? = null, // null = use global, true/false = override

@ColumnInfo(name = "notification_sound")
val notificationSound: Boolean? = null, // null = use global, true/false = override

@ColumnInfo(name = "notification_silent")
val notificationSilent: Boolean = false, // true = completely silent for this host
```

Add to TabSSHDatabase migration (v2 → v3):

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add notification fields to connections table
        database.execSQL("ALTER TABLE connections ADD COLUMN notifications_enabled INTEGER")
        database.execSQL("ALTER TABLE connections ADD COLUMN notification_vibrate INTEGER")
        database.execSQL("ALTER TABLE connections ADD COLUMN notification_sound INTEGER")
        database.execSQL("ALTER TABLE connections ADD COLUMN notification_silent INTEGER NOT NULL DEFAULT 0")
    }
}
```

Add notification section to activity_connection_edit.xml:

```xml
<!--activity_connection_edit.xmlafterPortKnockingsection-->

<!--NotificationsSection-->
<com.google.android.material.card.MaterialCardView
    android:id="@+id/card_notifications"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="@dimen/space_md"
    style="@style/Widget.Material3.CardView.Outlined">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="@dimen/space_md">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Notifications"
            android:textAppearance="@style/TextAppearance.Material3.TitleMedium"
            android:textColor="?attr/colorPrimary"
            android:drawableStart="@drawable/ic_notifications"
            android:drawablePadding="@dimen/space_sm"
            android:layout_marginBottom="@dimen/space_sm" />

        <MaterialSwitch
            android:id="@+id/switch_override_notifications"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Override Global Settings"
            android:checked="false" />

        <LinearLayout
            android:id="@+id/layout_notification_overrides"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:layout_marginStart="24dp"
            android:visibility="gone">

            <MaterialSwitch
                android:id="@+id/switch_connection_notifications"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Enable Notifications"
                android:layout_marginTop="@dimen/space_sm"
                android:checked="true" />

            <MaterialSwitch
                android:id="@+id/switch_notification_vibrate"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Vibrate"
                android:layout_marginTop="@dimen/space_sm"
                android:checked="true" />

            <MaterialSwitch
                android:id="@+id/switch_notification_sound"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Sound"
                android:layout_marginTop="@dimen/space_sm"
                android:checked="true" />

            <MaterialSwitch
                android:id="@+id/switch_notification_silent"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Silent Mode (No Notifications)"
                android:layout_marginTop="@dimen/space_sm"
                android:checked="false" />

        </LinearLayout>

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
```

Add visibility toggle in ConnectionEditActivity:

```kotlin
// ConnectionEditActivity.kt
private fun setupNotificationSettings() {
    binding.switchOverrideNotifications.setOnCheckedChangeListener { _, isChecked ->
        binding.layoutNotificationOverrides.visibility = 
            if (isChecked) View.VISIBLE else View.GONE
    }
    
    // Silent mode disables other switches
    binding.switchNotificationSilent.setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            binding.switchConnectionNotifications.isEnabled = false
            binding.switchNotificationVibrate.isEnabled = false
            binding.switchNotificationSound.isEnabled = false
        } else {
            binding.switchConnectionNotifications.isEnabled = true
            binding.switchNotificationVibrate.isEnabled = true
            binding.switchNotificationSound.isEnabled = true
        }
    }
}

private fun populateNotificationFields(profile: ConnectionProfile) {
    val hasOverride = profile.notificationsEnabled != null ||
                      profile.notificationVibrate != null ||
                      profile.notificationSound != null ||
                      profile.notificationSilent
    
    binding.switchOverrideNotifications.isChecked = hasOverride
    binding.layoutNotificationOverrides.visibility = 
        if (hasOverride) View.VISIBLE else View.GONE
    
    profile.notificationsEnabled?.let { 
        binding.switchConnectionNotifications.isChecked = it 
    }
    profile.notificationVibrate?.let { 
        binding.switchNotificationVibrate.isChecked = it 
    }
    profile.notificationSound?.let { 
        binding.switchNotificationSound.isChecked = it 
    }
    binding.switchNotificationSilent.isChecked = profile.notificationSilent
}

private fun saveNotificationSettings(): ConnectionProfile {
    return profile.copy(
        notificationsEnabled = if (binding.switchOverrideNotifications.isChecked)
            binding.switchConnectionNotifications.isChecked else null,
        notificationVibrate = if (binding.switchOverrideNotifications.isChecked)
            binding.switchNotificationVibrate.isChecked else null,
        notificationSound = if (binding.switchOverrideNotifications.isChecked)
            binding.switchNotificationSound.isChecked else null,
        notificationSilent = binding.switchNotificationSilent.isChecked
    )
}
```

**Phase 4: Check Per-Connection Settings in NotificationHelper (15 min)**

Modify NotificationHelper to check connection-specific settings:

```kotlin
// NotificationHelper.kt - Add profile parameter
fun showConnectionSuccess(
    context: Context, 
    serverName: String, 
    username: String,
    profile: ConnectionProfile? = null
) {
    val prefs = PreferenceManager(context)
    
    // Check profile-specific silent mode
    if (profile?.notificationSilent == true) {
        Logger.d("NotificationHelper", "Connection $serverName is in silent mode")
        return
    }
    
    // Check profile-specific or global settings
    val notificationsEnabled = profile?.notificationsEnabled 
        ?: prefs.showConnectionNotifications()
    
    if (!notificationsEnabled){
        Logger.d("NotificationHelper", "Connection notifications disabled")
        return
    }
    
    // ... existing notification code ...
}

// Similar changes for showConnectionError, showDisconnected, etc.
```

Update callers to pass ConnectionProfile:

```kotlin
// TabTerminalActivity.kt line 728
io.github.tabssh.utils.NotificationHelper.showConnectionSuccess(
    this,
    profile.getDisplayName(),
    profile.username,
    profile // Pass profile for per-connection settings
)
```

**Phase 5: Persistent Service Notification Control (20 min)**

Modify SSHConnectionService to check preferences:

```kotlin
// SSHConnectionService.kt line 98
private fun startForegroundService() {
    val prefs = PreferenceManager(this)
    
    if (!prefs.showPersistentNotification()){
        Logger.d("SSHConnectionService", "Persistent notification disabled")
        // Still need to call startForeground for service, but make it invisible
        val notification = createMinimalNotification()
        startForeground(NOTIFICATION_ID, notification)
        return
    }
    
    val notification = createNotification()
    startForeground(NOTIFICATION_ID, notification)
    
    // ... rest of method ...
}

private fun createMinimalNotification(): Notification {
    // Create low-priority, invisible notification (required for foreground service)
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("TabSSH")
        .setContentText("Running")
        .setSmallIcon(R.drawable.ic_ssh)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .build()
}
```

**Expected Behavior After Fix:**

**Global Settings:**
- Settings → General → Notifications section
- Master toggle: Disable all notifications
- Per-type toggles: Connection, Errors, File Transfer, Persistent
- Vibrate/sound toggles
- Link to Android system settings for advanced control

**Per-Connection Settings:**
- Connection edit → Notifications card
- "Override Global Settings" toggle
- Individual toggles for notifications, vibrate, sound
- "Silent Mode" switch to completely disable for this host

**Notification Behavior:**
- Connection success: Only shows if enabled globally + not silent
- Connection error: Only shows if enabled globally + not silent
- File transfer: Only shows if enabled globally
- Persistent service: Only shows if enabled globally
- All respect per-connection overrides

**Action Items:**

**Phase 1 - Global Settings UI (45 min):**
- [ ] Add Notifications category to preferences_general.xml (8 preferences)
- [ ] Add 7 notification preference keys to PreferenceManager.kt
- [ ] Add 8 getter methods with proper dependency logic
- [ ] Test Settings UI shows correctly
- [ ] Test preferences save/load correctly

**Phase 2 - Preference Checks (30 min):**
- [ ] Modify NotificationHelper.showConnectionSuccess() to check prefs
- [ ] Modify NotificationHelper.showConnectionError() to check prefs
- [ ] Modify NotificationHelper.showDisconnected() to check prefs
- [ ] Modify NotificationHelper.showFileTransferProgress() to check prefs
- [ ] Modify NotificationHelper.showFileTransferComplete() to check prefs
- [ ] Test notifications respect global settings

**Phase 3 - Per-Connection UI (60 min + DB migration):**
- [ ] Add 4 notification fields to ConnectionProfile entity
- [ ] Create database migration v2 → v3
- [ ] Add Notifications card to activity_connection_edit.xml
- [ ] Add setupNotificationSettings() method to ConnectionEditActivity
- [ ] Add populateNotificationFields() method
- [ ] Add saveNotificationSettings() method
- [ ] Test connection-specific settings save/load

**Phase 4 - Per-Connection Logic (15 min):**
- [ ] Add profile parameter to NotificationHelper methods
- [ ] Check profile.notificationSilent first (early return)
- [ ] Check profile-specific settings before global
- [ ] Update all callers to pass ConnectionProfile
- [ ] Test per-connection overrides work

**Phase 5 - Persistent Service (20 min):**
- [ ] Add showPersistentNotification() check to SSHConnectionService
- [ ] Create createMinimalNotification() for when disabled
- [ ] Test persistent notification respects settings
- [ ] Test service still runs when notification hidden

**Files Involved:**

**Phase 1:**
- app/src/main/res/xml/preferences_general.xml
  - Add Notifications category with 8 preferences
- app/src/main/java/io/github/tabssh/storage/preferences/PreferenceManager.kt
  - Add 7 constants and 8 getter/setter methods

**Phase 2:**
- app/src/main/java/io/github/tabssh/utils/NotificationHelper.kt
  - Lines 106-127: showConnectionSuccess() - add prefs check
  - Lines 133-156: showConnectionError() - add prefs check
  - Lines 161-189: showDisconnected() - add prefs check
  - Lines 194-222: showFileTransferProgress() - add prefs check
  - Lines 239-266: showFileTransferComplete() - add prefs check

**Phase 3:**
- app/src/main/java/io/github/tabssh/storage/database/entities/ConnectionProfile.kt
  - Add 4 notification fields after line 69
- app/src/main/java/io/github/tabssh/storage/database/TabSSHDatabase.kt
  - Add MIGRATION_2_3 for new fields
- app/src/main/res/layout/activity_connection_edit.xml
  - Add Notifications card after Port Knocking section
- app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt
  - Add setupNotificationSettings() method
  - Add populateNotificationFields() method
  - Add saveNotificationSettings() method

**Phase 4:**
- app/src/main/java/io/github/tabssh/utils/NotificationHelper.kt
  - Add profile: ConnectionProfile? parameter to all methods
  - Add per-connection logic
- app/src/main/java/io/github/tabssh/ui/activities/TabTerminalActivity.kt
  - Lines 728, 746, 771, 781, 816: Pass profile parameter
- app/src/main/java/io/github/tabssh/ui/activities/SFTPActivity.kt
  - Lines 497, 569: Pass profile parameter

**Phase 5:**
- app/src/main/java/io/github/tabssh/services/SSHConnectionService.kt
  - Lines 98-108: Add preference check in startForegroundService()
  - Add createMinimalNotification() method

**Priority:** HIGH (Core UX issue - users can't control notifications)
**Complexity:** MODERATE (Settings UI + DB migration + logic changes)
**Est. Time:** 2 hours 50 minutes (170 min)
- Phase 1: 45 min (Settings UI)
- Phase 2: 30 min (Preference checks)
- Phase 3: 60 min (Per-connection UI + DB migration)
- Phase 4: 15 min (Per-connection logic)
- Phase 5: 20 min (Persistent service)

---

