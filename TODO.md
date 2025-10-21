# TabSSH Android - Critical Fixes TODO

**Last Updated:** 2025-10-21 08:50
**Status:** 🔴 DEEP INVESTIGATION - Terminal Connection Flow Broken

---

## ROOT CAUSE IDENTIFIED ✅

### Terminal Not Working - ROOT CAUSE FOUND
**Location:** `TerminalEmulator.kt:189-192`

```kotlin
fun connect(inputStream: java.io.InputStream, outputStream: java.io.OutputStream) {
    // Implementation would connect streams to terminal I/O
    Logger.d("TerminalEmulator", "Connected to I/O streams")
}
```

**Problem:** The `connect()` method is a stub - it doesn't actually do anything!
- No coroutine to read from SSH inputStream
- No forwarding of data to terminal buffer
- No writing of user input to outputStream
- Terminal remains disconnected despite SSH connection succeeding

**Impact:** Terminal screen is black because:
1. SSH connection IS working (logs show maintenance tasks running)
2. TerminalView IS initialized
3. But TerminalEmulator never reads SSH output or sends user input
4. Data flows nowhere → black screen

**Fix Required:**
- Implement actual I/O stream connection in TerminalEmulator
- Start coroutine to read from inputStream → process → display
- Connect outputStream for user input
- Notify listeners when data flows
- Handle disconnection properly

---

## 🔴 CRITICAL FIXES IN PROGRESS

### 1. Fix TerminalEmulator.connect() - **IN PROGRESS**
**Files to Modify:**
1. `app/src/main/java/com/tabssh/terminal/emulator/TerminalEmulator.kt`
   - Implement actual stream connection (line 189)
   - Add coroutine for reading input stream
   - Add method to write to output stream
   - Notify listeners of data events

**Implementation Plan:**
```kotlin
private var inputStream: InputStream? = null
private var outputStream: OutputStream? = null
private var readJob: Job? = null

fun connect(inputStream: InputStream, outputStream: OutputStream) {
    this.inputStream = inputStream
    this.outputStream = outputStream

    // Start reading from SSH in background
    readJob = CoroutineScope(Dispatchers.IO).launch {
        try {
            val buffer = ByteArray(4096)
            while (isActive.value && !inputStream.isClosed()) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val data = buffer.copyOf(bytesRead)
                    processInput(data)
                    listeners.forEach { it.onDataReceived(data) }
                }
            }
        } catch (e: Exception) {
            listeners.forEach { it.onTerminalError(e) }
        }
    }

    listeners.forEach { it.onTerminalConnected() }
}

fun sendText(text: String) {
    outputStream?.let { stream ->
        val bytes = text.toByteArray(currentCharset)
        stream.write(bytes)
        stream.flush()
        listeners.forEach { it.onDataSent(bytes) }
    }
}

fun disconnect() {
    readJob?.cancel()
    inputStream?.close()
    outputStream?.close()
    listeners.forEach { it.onTerminalDisconnected() }
}
```

### 2. Fix System Keyboard Not Showing
**Files to Modify:**
- `app/src/main/java/com/tabssh/ui/views/TerminalView.kt` (line 206-213)

**Current Code:**
```kotlin
fun toggleKeyboard() {
    if (inputMethodManager.isActive) {
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    } else {
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
}
```

**Fix:**
```kotlin
fun toggleKeyboard() {
    requestFocus()
    inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_FORCED)
}

// Also add to onSingleTapConfirmed in TerminalGestureListener (line 373)
override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
    requestFocus()
    inputMethodManager.showSoftInput(this@TerminalView, InputMethodManager.SHOW_FORCED)
    return true
}
```

### 3. Fix Section Titles Visibility
**File:** `app/src/main/res/layout/activity_main.xml`

**Lines to Change:**
- Line 72: Section header "Quick Connect" (currently invisible)
- Line 122: Section header "My Servers" (currently invisible)

**Current:**
```xml
<TextView
    android:text="⚡ Quick Connect"
    android:textColor="#212121" />  <!-- Dark gray on dark background = invisible -->
```

**Fix:**
```xml
<TextView
    android:text="⚡ Quick Connect"
    android:textColor="?attr/colorOnSurface" />  <!-- Theme-aware color -->
```

### 4. Connect TerminalView to TerminalEmulator Output
**File:** `app/src/main/java/com/tabssh/ui/views/TerminalView.kt`

**Add Output Stream Connection:**
```kotlin
fun setOutputStream(stream: OutputStream) {
    outputStream = stream
    terminalEmulator?.setOutputStream(stream)  // Connect emulator to SSH output
}
```

**Connect View to Emulator Updates:**
```kotlin
private fun setupTerminalListener() {
    terminalEmulator?.addListener(object : com.tabssh.terminal.emulator.TerminalListener {
        override fun onDataReceived(data: ByteArray) {
            post { invalidate() }  // Redraw on new data
        }
        override fun onDataSent(data: ByteArray) {}
        override fun onTitleChanged(title: String) {}
        override fun onTerminalError(error: Exception) {
            Logger.e("TerminalView", "Terminal error", error)
        }
        override fun onTerminalConnected() {}
        override fun onTerminalDisconnected() {}
    })
}
```

---

## 🎯 EXECUTION PLAN

### Step 1: Fix TerminalEmulator Stream Connection ✅
- Implement connect() method with actual I/O
- Add background coroutine for reading SSH output
- Implement sendText() to write to SSH input
- Implement disconnect() to clean up streams

### Step 2: Fix TerminalView Integration
- Connect TerminalView to TerminalEmulator updates
- Make terminal redraw when data arrives
- Ensure keyboard input flows to SSH

### Step 3: Fix Keyboard
- Change toggleKeyboard() to use SHOW_FORCED
- Add tap listener to show keyboard

### Step 4: Fix Section Titles
- Change hardcoded colors to theme attributes
- Test visibility in dark theme

### Step 5: Test End-to-End
- Build APK
- Install on device
- Connect to SSH server
- Verify terminal shows output
- Verify keyboard works
- Verify text is visible

---

## 📝 FILES TO MODIFY

### Priority 1 - Terminal Connection (Critical)
1. `app/src/main/java/com/tabssh/terminal/emulator/TerminalEmulator.kt`
   - Lines 189-200: Implement connect/disconnect
   - Add: inputStream, outputStream, readJob fields
   - Update: sendText() to write to outputStream

2. `app/src/main/java/com/tabssh/ui/views/TerminalView.kt`
   - Add: setupTerminalListener() to redraw on data
   - Update: setOutputStream() to connect emulator

### Priority 2 - UI Fixes
3. `app/src/main/res/layout/activity_main.xml`
   - Line 72, 122: Change textColor to ?attr/colorOnSurface

4. `app/src/main/java/com/tabssh/ui/views/TerminalView.kt`
   - Line 206-213: Fix toggleKeyboard()

---

## 🧪 TESTING CHECKLIST

After fixes:
- [ ] Build: `./build.sh`
- [ ] Install: `adb install -r binaries/tabssh-universal.apk`
- [ ] Launch app
- [ ] Try Quick Connect to amd64.us with root/password
- [ ] **Verify terminal shows SSH output** ← KEY TEST
- [ ] Tap terminal, verify keyboard appears
- [ ] Type commands, verify they execute
- [ ] Verify section titles are visible
- [ ] Check logs: `adb logcat | grep -E "Terminal|SSH"`

---

**NEXT:** Implement TerminalEmulator.connect() with actual I/O stream handling
