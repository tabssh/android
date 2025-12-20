# TabSSH AI Implementation Todo List

**Last Updated:** 2025-12-19
**Current Status:** 100% Feature Complete → Enhancing Mobile UX & Organization
**Goal:** Mobile-first SSH client with superior organization and productivity features

---

## Summary

TabSSH has achieved **100% core feature completion** and now exceeds the capabilities of legacy SSH clients. The focus is now on **mobile-friendly UX improvements** and **productivity enhancements** to create the ultimate Android SSH experience.

**Analysis Complete:** ✅
- Comprehensive feature audit completed
- Gap analysis identified 13 high-value enhancements
- Prioritized by user impact and implementation effort

---

## Critical Priority (Must Have) - Week 1

### 1. Connection Groups/Folders ⭐⭐⭐
**Status:** 🔴 Not Started
**Priority:** CRITICAL
**Effort:** 8-12 hours
**Impact:** Essential for users managing 10+ servers

**Why:** Users with many servers need organization. Folders/groups are table stakes for modern SSH clients.

**Implementation:**
- [ ] Add `group_id` and `group_name` fields to ConnectionProfile entity
- [ ] Create ConnectionGroup entity (id, name, color, icon, sort_order)
- [ ] Create ConnectionGroupDao with CRUD operations
- [ ] Update ConnectionDao to support filtering by group
- [ ] Create GroupManagementActivity (create, edit, delete, reorder groups)
- [ ] Update MainActivity to show connections grouped by folder
- [ ] Add Material Design 3 expandable cards for each group
- [ ] Implement drag-to-reorder groups
- [ ] Add color coding for visual distinction
- [ ] Update connection edit screen with group selector
- [ ] Migrate existing connections to "Default" group
- [ ] Database migration v2 → v3

**Files to Create:**
- `app/src/main/java/io/github/tabssh/storage/database/entities/ConnectionGroup.kt`
- `app/src/main/java/io/github/tabssh/storage/database/dao/ConnectionGroupDao.kt`
- `app/src/main/java/io/github/tabssh/ui/activities/GroupManagementActivity.kt`
- `app/src/main/res/layout/activity_group_management.xml`
- `app/src/main/res/layout/item_connection_group.xml`

**Files to Modify:**
- `ConnectionProfile.kt` - Add group_id field
- `TabSSHDatabase.kt` - Add ConnectionGroupDao, migration v2→v3
- `MainActivity.kt` - Group connections by folder
- `ConnectionEditActivity.kt` - Add group selector
- `activity_main.xml` - Update layout for grouped display

---

### 2. Snippets Library ⭐⭐⭐
**Status:** 🔴 Not Started
**Priority:** CRITICAL
**Effort:** 6-8 hours
**Impact:** Huge productivity boost for frequent commands

**Why:** Power users run the same commands repeatedly (docker ps, systemctl status, tail -f logs). Quick access saves significant time.

**Implementation:**
- [ ] Create Snippet entity (id, name, command, description, category, global/connection-specific)
- [ ] Create SnippetDao with CRUD operations
- [ ] Create SnippetManagerActivity for managing snippets
- [ ] Add bottom sheet in TabTerminalActivity for snippet selection
- [ ] Implement snippet insertion at cursor position
- [ ] Add snippet categories (System, Docker, Git, Network, Custom)
- [ ] Support variables in snippets ({{username}}, {{hostname}})
- [ ] Add "Auto-run on connect" option for connection-specific snippets
- [ ] Import/export snippets with backup system
- [ ] Add default snippet library (common commands)

**Files to Create:**
- `app/src/main/java/io/github/tabssh/storage/database/entities/Snippet.kt`
- `app/src/main/java/io/github/tabssh/storage/database/dao/SnippetDao.kt`
- `app/src/main/java/io/github/tabssh/ui/activities/SnippetManagerActivity.kt`
- `app/src/main/java/io/github/tabssh/ui/dialogs/SnippetPickerBottomSheet.kt`
- `app/src/main/res/layout/activity_snippet_manager.xml`
- `app/src/main/res/layout/bottom_sheet_snippet_picker.xml`
- `app/src/main/res/layout/item_snippet.xml`

**Files to Modify:**
- `TabSSHDatabase.kt` - Add SnippetDao, migration
- `TabTerminalActivity.kt` - Add snippet picker button, implement insertion
- `ConnectionProfile.kt` - Add auto_run_snippet_id field (optional)

---

## High Priority (Important) - Week 2

### 3. Volume Keys Font Size Control ⭐⭐
**Status:** 🔴 Not Started
**Priority:** HIGH
**Effort:** 2-3 hours
**Impact:** Convenient for mobile users, unique feature

**Why:** Mobile users often need to adjust font size on the fly. Volume keys are natural and accessible.

**Implementation:**
- [ ] Override `onKeyDown()` in TabTerminalActivity
- [ ] Detect volume up/down key presses
- [ ] Increase/decrease terminal font size by 2sp increments
- [ ] Add visual toast showing current font size
- [ ] Respect min (8sp) and max (32sp) bounds
- [ ] Save font size to preferences
- [ ] Add toggle in settings to enable/disable feature
- [ ] Handle volume key conflicts with media playback

**Files to Modify:**
- `TabTerminalActivity.kt` - Add key handler (lines ~200-250)
- `TerminalView.kt` - Add setFontSize() method if not exists
- `preferences_terminal.xml` - Add "Volume keys control font" toggle

---

### 4. Show Frequently Used Section ⭐
**Status:** ⚠️ Partial (Backend complete, UI hidden)
**Priority:** HIGH
**Effort:** 1 hour
**Impact:** Quick access to most-used servers

**Why:** The feature is fully implemented, just needs to be visible.

**Implementation:**
- [x] Backend implemented (getFrequentlyUsedConnections())
- [x] RecyclerView exists in activity_main.xml
- [ ] Remove `android:visibility="gone"` from card_frequently_used
- [ ] Verify updateFrequentlyUsedVisibility() is called on load
- [ ] Test that connections show correctly
- [ ] Add header text "Frequently Used" or "⭐ Favorites"

**Files to Modify:**
- `activity_main.xml` - Line 133: Change visibility="gone" → visibility="visible"
- Verify `MainActivity.kt` lines 269-275 work correctly

---

### 5. Search Connections ⭐⭐
**Status:** 🔴 Not Started
**Priority:** HIGH
**Effort:** 3-4 hours
**Impact:** Essential when managing 20+ connections

**Why:** Users with many servers need quick filtering by name/host/tag.

**Implementation:**
- [ ] Add SearchView to MainActivity toolbar (menu item)
- [ ] Implement TextWatcher for real-time filtering
- [ ] Filter connections by name, hostname, username, tags
- [ ] Highlight search terms in results
- [ ] Show search results count
- [ ] Preserve search state on configuration change
- [ ] Add "Clear search" button
- [ ] Support connection group filtering in search results

**Files to Modify:**
- `MainActivity.kt` - Add search functionality (~50 lines)
- `ConnectionAdapter.kt` - Add filter() method
- `res/menu/main_menu.xml` - Add search action item
- `activity_main.xml` - Add SearchView (or use toolbar action)

---

### 6. Click URLs to Open Browser ⭐⭐
**Status:** 🔴 Not Started
**Priority:** HIGH
**Effort:** 4-5 hours
**Impact:** Huge convenience for web developers and sysadmins

**Why:** SSH sessions often display URLs (logs, error messages, documentation). Making them clickable saves time.

**Implementation:**
- [ ] Add URL detection regex to TerminalRenderer
- [ ] Implement Linkify pattern matching for http://, https://, www.
- [ ] Add long-press detection on terminal text
- [ ] Show context menu: "Open URL", "Copy URL"
- [ ] Launch Intent.ACTION_VIEW to open in browser
- [ ] Highlight URLs with underline or color
- [ ] Add setting to enable/disable URL detection
- [ ] Handle URL wrapping across lines

**Files to Modify:**
- `TerminalRenderer.kt` - Add URL detection and rendering
- `TerminalView.kt` - Add touch listener for URL clicks
- `TabTerminalActivity.kt` - Handle URL opening intent
- `preferences_terminal.xml` - Add "Detect URLs" toggle

---

### 7. Swipe Between Sessions ⭐⭐
**Status:** 🔴 Not Started
**Priority:** HIGH
**Effort:** 4-6 hours
**Impact:** Mobile-friendly navigation, natural gesture

**Why:** Tabs are great, but swipe gestures are more natural on mobile. Matches user expectations from browsers/messaging apps.

**Implementation:**
- [ ] Replace or wrap TabTerminalActivity layout with ViewPager2
- [ ] Implement FragmentStateAdapter for SSH session fragments
- [ ] Add swipe gesture detector (left/right only, not up/down)
- [ ] Sync ViewPager position with TabLayout selection
- [ ] Add haptic feedback on page change
- [ ] Prevent swipe conflicts with terminal scrolling
- [ ] Add setting to enable/disable swipe navigation
- [ ] Handle tab addition/removal during swipe

**Files to Modify:**
- `TabTerminalActivity.kt` - Major refactor to use ViewPager2 (~100 lines)
- `activity_tab_terminal.xml` - Wrap content in ViewPager2
- `SSHTab.kt` - Convert to Fragment if needed
- `preferences_general.xml` - Add "Swipe between tabs" toggle

---

### 8. Android Widget ⭐⭐
**Status:** 🔴 Not Started
**Priority:** HIGH
**Effort:** 8-10 hours
**Impact:** Quick access from home screen, professional feature

**Why:** Power users want one-tap access to frequently used servers directly from the home screen.

**Implementation:**
- [ ] Create ConnectionWidgetProvider (AppWidgetProvider)
- [ ] Create widget layout (res/layout/widget_connection.xml)
- [ ] Implement widget configuration activity (select connection)
- [ ] Handle widget tap → launch connection
- [ ] Support multiple widget instances (different connections)
- [ ] Update widget display with connection status
- [ ] Add Material Design 3 theming to widget
- [ ] Support different widget sizes (1x1, 2x1, 4x2)
- [ ] Add widget preview for widget picker
- [ ] Handle widget deletion cleanup

**Files to Create:**
- `app/src/main/java/io/github/tabssh/ui/widgets/ConnectionWidgetProvider.kt`
- `app/src/main/java/io/github/tabssh/ui/widgets/WidgetConfigActivity.kt`
- `app/src/main/res/layout/widget_connection_small.xml` (1x1)
- `app/src/main/res/layout/widget_connection_medium.xml` (2x1)
- `app/src/main/res/layout/widget_connection_large.xml` (4x2)
- `app/src/main/res/xml/widget_info.xml`

**Files to Modify:**
- `AndroidManifest.xml` - Register widget provider and config activity

---

### 9. Proxy/Jump Host Support ⭐⭐
**Status:** 🔴 Not Started
**Priority:** HIGH
**Effort:** 6-8 hours
**Impact:** Essential for corporate/enterprise environments

**Why:** Many servers are only accessible through bastion/jump hosts. This is a common enterprise requirement.

**Implementation:**
- [ ] Add `proxy_connection_id` field to ConnectionProfile
- [ ] Add UI in ConnectionEditActivity for selecting jump host
- [ ] Implement SSH ProxyJump logic in SSHConnection
- [ ] Support chained jump hosts (A → B → C)
- [ ] Add visual indicator in connection list (chain icon)
- [ ] Test with real jump host scenarios
- [ ] Handle authentication for jump host
- [ ] Add error handling for jump host failures

**Files to Modify:**
- `ConnectionProfile.kt` - Add proxy_connection_id field
- `ConnectionEditActivity.kt` - Add jump host selector
- `SSHConnection.kt` - Implement ProxyJump connection logic
- `ConnectionAdapter.kt` - Add visual indicator for proxied connections
- `TabSSHDatabase.kt` - Migration for new field

---

## Medium Priority (Nice to Have) - Week 3

### 10. Custom Gestures for tmux/screen ⭐
**Status:** 🔴 Not Started
**Priority:** MEDIUM
**Effort:** 6-8 hours
**Impact:** Power user feature for terminal multiplexers

**Why:** tmux and screen users frequently use keyboard shortcuts. Custom gestures can speed up workflow.

**Implementation:**
- [ ] Add gesture detector to TerminalView
- [ ] Create gesture configuration UI (swipe up/down/left/right → command)
- [ ] Support common tmux gestures (split pane, switch window, detach)
- [ ] Support common screen gestures (create window, switch, detach)
- [ ] Add haptic feedback for gesture recognition
- [ ] Save gesture mappings to preferences
- [ ] Add preset configurations for tmux/screen/irssi/weechat
- [ ] Allow custom gesture-to-command mapping

**Files to Create:**
- `app/src/main/java/io/github/tabssh/ui/gestures/GestureManager.kt`
- `app/src/main/java/io/github/tabssh/ui/activities/GestureConfigActivity.kt`
- `app/src/main/res/layout/activity_gesture_config.xml`

**Files to Modify:**
- `TerminalView.kt` - Add GestureDetector integration
- `preferences_terminal.xml` - Add link to gesture configuration

---

### 11. Sort Connections ⭐
**Status:** 🔴 Not Started
**Priority:** MEDIUM
**Effort:** 2-3 hours
**Impact:** Organizational convenience

**Why:** Users want different sort orders: alphabetical, recently used, most frequent, custom.

**Implementation:**
- [ ] Add sort menu in MainActivity toolbar
- [ ] Implement sort options: Name (A-Z), Recent, Frequency, Custom
- [ ] Save sort preference to SharedPreferences
- [ ] Update ConnectionDao queries to support ORDER BY
- [ ] Add manual sort (drag-to-reorder) with sort_order field
- [ ] Sync sort order across devices if cloud sync enabled

**Files to Modify:**
- `MainActivity.kt` - Add sort menu and logic (~40 lines)
- `ConnectionDao.kt` - Add sorted query methods
- `ConnectionProfile.kt` - Add sort_order field
- `res/menu/main_menu.xml` - Add sort submenu

---

### 12. Save SSH Transcripts ⭐
**Status:** 🔴 Not Started
**Priority:** MEDIUM
**Effort:** 4-5 hours
**Impact:** Debugging and logging use cases

**Why:** Useful for recording sessions, debugging, compliance logging.

**Implementation:**
- [ ] Add transcript recording toggle in TabTerminalActivity
- [ ] Implement transcript writer to save terminal output
- [ ] Save transcripts to app-specific storage (Android/data/...)
- [ ] Add transcript viewer activity (read-only terminal display)
- [ ] Support export transcript as .txt or .log
- [ ] Add automatic transcript cleanup (keep last 30 days)
- [ ] Add setting for auto-record all sessions
- [ ] Implement transcript search functionality

**Files to Create:**
- `app/src/main/java/io/github/tabssh/terminal/TranscriptRecorder.kt`
- `app/src/main/java/io/github/tabssh/ui/activities/TranscriptViewerActivity.kt`
- `app/src/main/res/layout/activity_transcript_viewer.xml`

**Files to Modify:**
- `TabTerminalActivity.kt` - Add recording toggle
- `TerminalEmulator.kt` - Hook transcript recording
- `preferences_terminal.xml` - Add "Auto-record sessions" toggle

---

### 13. Identity Abstraction ⭐
**Status:** 🔴 Not Started
**Priority:** MEDIUM
**Effort:** 6-8 hours
**Impact:** Reusable identities, cleaner organization

**Why:** Same username/key often used across multiple servers. Separate identity management reduces duplication.

**Implementation:**
- [ ] Create Identity entity (id, name, username, key_id, password_encrypted)
- [ ] Create IdentityDao with CRUD operations
- [ ] Create IdentityManagerActivity for managing identities
- [ ] Link ConnectionProfile to Identity (identity_id field)
- [ ] Update ConnectionEditActivity to select identity instead of inline credentials
- [ ] Migrate existing connections to auto-created identities
- [ ] Support identity-level key management
- [ ] Add identity sync to cloud backup

**Files to Create:**
- `app/src/main/java/io/github/tabssh/storage/database/entities/Identity.kt`
- `app/src/main/java/io/github/tabssh/storage/database/dao/IdentityDao.kt`
- `app/src/main/java/io/github/tabssh/ui/activities/IdentityManagerActivity.kt`
- `app/src/main/res/layout/activity_identity_manager.xml`
- `app/src/main/res/layout/item_identity.xml`

**Files to Modify:**
- `ConnectionProfile.kt` - Add identity_id field (replace username/key_id)
- `TabSSHDatabase.kt` - Add IdentityDao, migration
- `ConnectionEditActivity.kt` - Replace credential inputs with identity selector

---

### 14. Performance Monitor ⭐
**Status:** 🔴 Not Started
**Priority:** MEDIUM
**Effort:** 8-12 hours
**Impact:** Built-in equivalent to plugin, sysadmin tool

**Why:** Sysadmins often want to monitor server performance (CPU, RAM, disk, network) while connected.

**Implementation:**
- [ ] Create PerformanceMonitorService (background SSH session)
- [ ] Run periodic commands: top, free, df, ifconfig/ip
- [ ] Parse command output to extract metrics
- [ ] Create real-time graph UI (CPU%, memory%, disk I/O, network)
- [ ] Add PerformanceMonitorActivity with charts
- [ ] Support custom monitoring commands
- [ ] Add monitoring interval setting (1s, 5s, 10s, 30s)
- [ ] Save historical data for trends
- [ ] Add threshold alerts (CPU > 90%, disk > 95%)

**Files to Create:**
- `app/src/main/java/io/github/tabssh/monitoring/PerformanceMonitorService.kt`
- `app/src/main/java/io/github/tabssh/monitoring/MetricsParser.kt`
- `app/src/main/java/io/github/tabssh/ui/activities/PerformanceMonitorActivity.kt`
- `app/src/main/res/layout/activity_performance_monitor.xml`

**Dependencies:**
- Add MPAndroidChart library for real-time graphs

**Files to Modify:**
- `build.gradle` - Add charting library dependency
- `TabTerminalActivity.kt` - Add menu item to launch performance monitor

---

## Low Priority (Optional) - Future

### 15. Share SSH Transcripts
**Status:** 🔴 Not Started
**Priority:** LOW
**Effort:** 2-3 hours
**Requires:** Task 12 (Save SSH Transcripts)

**Implementation:**
- [ ] Add "Share" button in TranscriptViewerActivity
- [ ] Use Intent.ACTION_SEND to share .txt file
- [ ] Support sharing via email, messaging, cloud storage

---

### 16. Port Knocker
**Status:** 🔴 Not Started
**Priority:** LOW
**Effort:** 4-6 hours
**Why Low:** Security through obscurity, niche use case

**Implementation:**
- [ ] Add port knock sequence configuration to ConnectionProfile
- [ ] Implement UDP/TCP knock sender
- [ ] Send knock sequence before SSH connection
- [ ] Add UI for configuring knock ports and protocol
- [ ] Support variable knock delays

---

### 17. Tasker Integration
**Status:** 🔴 Not Started
**Priority:** LOW
**Effort:** 6-8 hours
**Why Low:** Niche automation use case

**Implementation:**
- [ ] Create Tasker plugin activity
- [ ] Support Tasker actions: Connect, Disconnect, Send Command, Run Snippet
- [ ] Support Tasker states: Connected, Disconnected
- [ ] Support Tasker events: Connection Established, Connection Lost

---

### 18. Local Shell / Telnet
**Status:** 🔴 Not Started
**Priority:** LOW (Not Recommended)
**Why Low:** Telnet is insecure, local shell covered by other apps (Termux)

**No implementation planned** - Out of scope for SSH client

---

## Mobile-Friendly UI Enhancements - Ongoing

### UI/UX Improvements
- [ ] Increase touch targets to minimum 48dp x 48dp
- [ ] Implement bottom sheet menus for connection actions (replace context menu)
- [ ] Add swipe-to-delete on connection items
- [ ] Add swipe-to-edit on connection items
- [ ] Implement pull-to-refresh on connection list
- [ ] Add Material Design 3 motion/transitions
- [ ] Create onboarding tutorial for gestures
- [ ] Optimize layouts for one-handed operation
- [ ] Add landscape mode optimization
- [ ] Implement foldable device support
- [ ] Add tablet-optimized layouts (two-pane master-detail)
- [ ] Improve FAB positioning and auto-hide on scroll

### Accessibility
- [ ] Add content descriptions to all interactive elements
- [ ] Test with TalkBack and fix announced labels
- [ ] Ensure all colors meet WCAG 2.1 contrast ratios
- [ ] Add focus indicators for keyboard navigation
- [ ] Support larger text sizes (up to 200%)

---

## Database Migrations

### Migration v2 → v3 (Connection Groups)
- Add ConnectionGroup table
- Add group_id to ConnectionProfile
- Create default "Ungrouped" group
- Migrate existing connections to default group

### Migration v3 → v4 (Snippets)
- Add Snippet table
- Seed default snippet library

### Migration v4 → v5 (Identity Abstraction)
- Add Identity table
- Add identity_id to ConnectionProfile
- Migrate existing connections to auto-created identities
- Preserve backward compatibility

---

## Testing Requirements

### Per Feature
- [ ] Unit tests for data layer (DAO, Repository)
- [ ] Integration tests for sync (if applicable)
- [ ] UI tests for critical workflows (Espresso)
- [ ] Manual testing on physical device (Android 8, 10, 13, 14)
- [ ] Accessibility testing (TalkBack, large text)
- [ ] Performance testing (100+ connections)

### Before Release
- [ ] Full regression test suite
- [ ] Test all migrations v1→v2→v3→v4→v5
- [ ] Test backup/restore compatibility
- [ ] Test cloud sync across devices
- [ ] Load testing with 500+ connections
- [ ] Security audit (credentials storage, encryption)

---

## Progress Tracking

**Week 1 Goals:**
- [ ] Connection Groups (Critical)
- [ ] Snippets Library (Critical)

**Week 2 Goals:**
- [ ] Volume Keys Font Size
- [ ] Show Frequently Used
- [ ] Search Connections
- [ ] Click URLs to Open

**Week 3 Goals:**
- [ ] Swipe Between Sessions
- [ ] Android Widget
- [ ] Proxy/Jump Host

**Week 4 Goals:**
- [ ] Custom Gestures
- [ ] Sort Connections
- [ ] Performance Monitor
- [ ] UI/UX polish

---

## Completion Metrics

**Current:** 100% core features, 0% UX enhancements
**Target:** 100% core features, 90% UX enhancements (exclude low priority)

**Critical Features:** 0/2 complete (0%)
**High Priority Features:** 0/7 complete (0%)
**Medium Priority Features:** 0/5 complete (0%)
**Total High-Value Features:** 0/14 complete (0%)

---

## Notes

- All features should follow Material Design 3 guidelines
- Maintain backward compatibility with database v2
- Ensure all features work offline (except cloud sync)
- Test on devices with Android 8.0 minimum
- Keep APK size under 35MB (currently 30MB)
- Maintain 0 compilation errors standard
- All new code must be documented with KDoc
- Follow existing code style and architecture patterns

---

**Last Updated:** 2025-12-19
**Next Review:** After Week 1 completion
