# TabSSH Implementation Plan

**Version:** 1.0.0 (pinned)
**Last Updated:** 2026-02-11

---

## Current Focus: Issue #20 - Termux Terminal Integration (BLOCKER)

### Problem Summary

Termux terminal emulator library is in dependencies but NOT integrated:
- `build.gradle` has `com.termux:terminal-emulator:0.118.0`
- Zero imports of `com.termux` anywhere in codebase
- Custom `TerminalEmulator.kt` only handles CR/LF
- Result: SSH output broken (no colors, no cursor movement, vim unusable)

### Research Phase

**Questions to Answer:**

1. How does Termux's TerminalSession work?
2. What are the JNI/native requirements?
3. Can we use TermuxTerminalView directly or need wrapper?
4. How to wire SSH InputStream/OutputStream to Termux?

**Files to Study:**

```
Termux Library (external):
- com.termux.terminal.TerminalSession
- com.termux.terminal.TerminalEmulator
- com.termux.view.TerminalView (if using their view)

Current TabSSH (to replace/modify):
- terminal/emulator/TerminalEmulator.kt (394 lines) - REPLACE
- terminal/emulator/TerminalBuffer.kt - POSSIBLY REMOVE
- terminal/emulator/TerminalRenderer.kt - POSSIBLY REMOVE
- ui/views/TerminalView.kt (807 lines) - MODIFY or REPLACE
- ui/tabs/SSHTab.kt (397 lines) - MODIFY connection wiring
```

### Implementation Options

**Option A: Use Termux TerminalSession + Custom View**
```
SSH InputStream → TermuxTerminalSession → Our TerminalView (modified to render Termux buffer)
```
- Pros: Keep our TerminalView with existing features (gestures, URL detection)
- Cons: Must adapt rendering to Termux buffer format

**Option B: Use Termux TerminalView Directly**
```
SSH InputStream → TermuxTerminalSession → TermuxTerminalView
```
- Pros: Battle-tested rendering, full feature support
- Cons: Must re-implement our custom features (gestures, URL detection)

**Option C: Wrapper Approach**
```
SSH InputStream → TermuxTerminalWrapper (our class) → TerminalListener callbacks → Our TerminalView
```
- Pros: Minimal changes to existing code
- Cons: Extra abstraction layer

**Recommended: Option A** - Use Termux session but keep our view with modifications.

### Implementation Steps

#### Step 1: Research Termux API (1-2h)
- [ ] Read Termux TerminalSession source
- [ ] Understand TerminalBuffer structure
- [ ] Find how to create session without process (we have SSH streams, not local shell)
- [ ] Check if Termux supports external I/O streams

#### Step 2: Create TermuxSessionWrapper (2-3h)
```kotlin
// NEW FILE: terminal/TermuxSessionWrapper.kt
class TermuxSessionWrapper(
    private val inputStream: InputStream,   // From SSH
    private val outputStream: OutputStream  // To SSH
) {
    private lateinit var terminalSession: TerminalSession

    fun start() {
        // Create Termux session
        // Wire our streams to it
        // Start read loop
    }

    fun write(data: ByteArray) {
        outputStream.write(data)
    }

    fun getScreen(): TerminalBuffer {
        return terminalSession.emulator.screen
    }
}
```

#### Step 3: Modify SSHTab.kt (1h)
```kotlin
// MODIFY: ui/tabs/SSHTab.kt
class SSHTab(
    val profile: ConnectionProfile,
    // Change from custom emulator to Termux wrapper
    val termuxSession: TermuxSessionWrapper  // WAS: TerminalEmulator
) {
    suspend fun connect(sshConnection: SSHConnection): Boolean {
        val shellChannel = sshConnection.openShellChannel()
        termuxSession.start(shellChannel.inputStream, shellChannel.outputStream)
        // ...
    }
}
```

#### Step 4: Modify TerminalView.kt (2-3h)
```kotlin
// MODIFY: ui/views/TerminalView.kt
class TerminalView : View {
    // Change to render Termux buffer instead of custom buffer
    private var termuxSession: TermuxSessionWrapper? = null

    fun attachSession(session: TermuxSessionWrapper) {
        this.termuxSession = session
        // Set up rendering from Termux buffer
    }

    override fun onDraw(canvas: Canvas) {
        val screen = termuxSession?.getScreen() ?: return
        // Render Termux screen buffer
        // Handle colors, attributes, cursor
    }
}
```

#### Step 5: Update TabTerminalActivity.kt (1h)
- Update terminal view setup
- Ensure gestures still work
- Ensure URL detection still works

#### Step 6: Testing (2-3h)
- [ ] Basic shell (bash prompt with colors)
- [ ] vim/nano (cursor movement, alternate screen)
- [ ] htop/top (full screen refresh)
- [ ] Tab completion
- [ ] 256-color output
- [ ] Unicode/emoji
- [ ] Scrollback buffer
- [ ] Copy/paste

### Files to Create
- `terminal/TermuxSessionWrapper.kt` - Wrapper around Termux session

### Files to Modify
- `ui/tabs/SSHTab.kt` - Use Termux wrapper
- `ui/views/TerminalView.kt` - Render Termux buffer
- `ui/activities/TabTerminalActivity.kt` - Setup changes
- `ui/adapters/TerminalPagerAdapter.kt` - If using ViewPager

### Files to Deprecate (after Termux works)
- `terminal/emulator/TerminalEmulator.kt`
- `terminal/emulator/TerminalBuffer.kt`
- `terminal/emulator/TerminalCell.kt`
- `terminal/emulator/TerminalRow.kt`
- `terminal/emulator/TerminalRenderer.kt`

### Risk Mitigation

**Risk 1:** Termux may require local process, not external streams
- Mitigation: Check if we can use TerminalEmulator directly without TerminalSession

**Risk 2:** JNI/native code requirements
- Mitigation: Termux is pure Java/Kotlin for terminal emulation, native only for shell

**Risk 3:** Breaking existing features
- Mitigation: Keep old code until new works, feature flags if needed

### Success Criteria

- [ ] SSH connection shows colored bash prompt correctly
- [ ] vim opens and cursor moves properly
- [ ] htop displays and updates correctly
- [ ] Tab completion works
- [ ] Scrollback works
- [ ] No regression in existing features (gestures, URL detection)

---

## Next: Issue #21 - VM Console (After #20 Complete)

### Approach Summary
- Use serial/text console via hypervisor API (not VNC)
- Proxmox: `termproxy` API → WebSocket
- XCP-ng: XenAPI console
- Xen Orchestra: REST + WebSocket
- Wire WebSocket to same Termux terminal (after #20 fixed)

### Dependency
**BLOCKED BY:** Issue #20 - Terminal must work first

---

## Phase 3+ Planning (After #20 and #21)

Will add detailed plans as we reach each phase.

Quick fixes (#8, #11, #17, #2, #3) can be done without detailed planning.

---

*This file tracks implementation details. See TODO.AI.md for full issue list.*
