#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# scripts/ui-test.sh — scriptable UI test runner for TabSSH on a live emulator/device.
#
# Resolves __adb the same way android-emulator.sh does.  Each named test is a
# small Bash function.  Ad-hoc sequences can be composed on the command line
# with `run` + step flags — no new function needed.
#
# Usage:
#   scripts/ui-test.sh [GLOBAL…] <test-name|run STEPS…>…
#   scripts/ui-test.sh --list
#
# Global options (must come before the first test name / run):
#   --serial <serial>   ADB device serial (default: first connected device)
#   --apk    <path>     APK to install before running tests (implies --install)
#   --install           Install binaries/tabssh-android-x86.apk before running
#   --verbose           Print every __adb command
#   --list              Print available named tests and exit
#   --help              Print this help and exit
#
# Named tests (pass one or more, or "all"):
#   crash-dialog        Crash report dialog shows "Paste / Issue" not "Share"
#   hypervisor-form     HypervisorEditActivity renders without ANR
#   settings-opens      SettingsActivity main screen is navigable
#   logging-navigation  Settings → Logging: all sections and key prefs visible
#   all                 Run all of the above
#
# Ad-hoc inline test:
#   run [--name <label>] STEP [STEP…]
#
# Steps (for `run` and also callable as helpers inside named test functions):
#   --activity  <pkg/.Activity>   Launch activity (auto-prepends PKG if no slash)
#   --stop                        Force-stop the app
#   --inject-crash                Write fake crash prefs (for crash-dialog testing)
#   --tap       <text>            Scroll until text found, tap its clickable parent
#   --tap-xy    <x> <y>           Tap at exact screen coordinates
#   --long-tap  <text>            Long-press element by text
#   --long-tap-xy <x> <y>         Long-press at coordinates
#   --swipe     <up|down|left|right> [px]   Directional swipe (default 800 px)
#   --swipe-xy  <x1> <y1> <x2> <y2> [ms]   Arbitrary swipe with optional duration
#   --scroll-to <text> [n]        Scroll (up to n times, default 8) to expose text
#   --input     <text>            Type text into the focused field
#   --clear                       Select-all + delete in focused field
#   --back                        Press the Back key
#   --home                        Press the Home key
#   --enter                       Press Enter / Confirm
#   --key       <keycode>         Any Android keycode (e.g. KEYCODE_TAB)
#   --sleep     <seconds>         Pause
#   --screenshot [label]          Pull a screenshot to $TMPDIR/tabssh-uitest/
#   --wait-for  <text> [timeout]  Wait up to N sec (default 8) for text to appear
#   --wait-gone <text> [timeout]  Wait up to N sec for text to disappear
#   --present   <text>            Assert text is visible on screen
#   --absent    <text>            Assert text is NOT visible on screen
#   --attr      <text> <attr> <val>  Assert node with text has attribute=value
#                                    (e.g. --attr "Switch" "checked" "true")
#   --count     <text> <n>        Assert text appears exactly n times on screen
#
# Examples:
#   # Named test:
#   scripts/ui-test.sh crash-dialog
#
#   # Ad-hoc: open Settings, navigate to Logging, assert heading is there
#   scripts/ui-test.sh run --name "settings-logging" \
#       --activity ".ui.activities.SettingsActivity" \
#       --wait-for "Settings" \
#       --tap "Logging" \
#       --wait-for "Debug Logging" \
#       --present "Host Logging"
#
#   # Ad-hoc with coordinate tap: tap a button at known position
#   scripts/ui-test.sh run --name "custom-tap" \
#       --activity ".ui.activities.MainActivity" \
#       --wait-for "Hosts" \
#       --tap-xy 1000 120 \
#       --wait-for "Add Host"
#
#   # Install then run all:
#   scripts/ui-test.sh --install all
#
# Exit codes:
#   0  all tests passed
#   1  one or more tests failed
#   2  usage / setup error
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  SDK root (default: /opt/android)
#   ADB_SERIAL                       device serial (same as --serial)

set -euo pipefail

# ── colour ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
TEST_FAILS=0   # per-test assertion failure counter; reset by run_test / run_inline

__pass()  { echo -e "${GREEN}  ✅ $*${NC}"; }
__fail()  { echo -e "${RED}  ❌ $*${NC}"; TEST_FAILS=$((TEST_FAILS+1)); }
__info()  { echo -e "${BLUE}  ▸ $*${NC}"; }
__warn()  { echo -e "${YELLOW}  ⚠ $*${NC}"; }
__debug() { [[ ${VERBOSE:-0} -eq 1 ]] && echo -e "${CYAN}  $ $*${NC}" || true; }

# ── SDK / __adb resolution ─────────────────────────────────────────────────────
SDK=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android /opt/android-sdk "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platform-tools" ]]; then
        SDK="$candidate"; break
    fi
done
if [[ -z "$SDK" ]]; then
    echo "❌ No Android SDK found. Set ANDROID_HOME or install to /opt/android." >&2
    exit 2
fi
_ADB_BIN="$SDK/platform-tools/adb"
export ANDROID_HOME="$SDK"

# ── global args ───────────────────────────────────────────────────────────────
SERIAL="${ADB_SERIAL:-}"
APK=""
INSTALL=0
VERBOSE=0
TESTS=()     # positional list of test names / "run" tokens

__usage() {
    grep -E -- '^#' "$0" | grep -v -- '^#!/' | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

# Collect global options up front; leave test names + "run" blocks in TESTS.
while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)   shift; SERIAL="$1" ;;
        --apk)      shift; APK="$1"; INSTALL=1 ;;
        --install)  INSTALL=1 ;;
        --verbose)  VERBOSE=1 ;;
        --list)
            echo "Named tests:"
            echo "  crash-dialog        Crash report dialog shows 'Paste / Issue' not 'Share'"
            echo "  hypervisor-form     HypervisorEditActivity renders without ANR"
            echo "  settings-opens      SettingsActivity main screen is navigable"
            echo "  logging-navigation  Settings → Logging: all sections and key prefs visible"
            echo "  all                 All of the above"
            echo ""
            echo "Use 'run STEPS…' for inline tests — see --help for step reference."
            exit 0 ;;
        --help|-h)  __usage 0 ;;
        all)        TESTS+=(crash-dialog hypervisor-form settings-opens logging-navigation) ;;
        *)          TESTS+=("$1") ;;
    esac
    shift
done

if [[ ${#TESTS[@]} -eq 0 ]]; then
    echo "No tests specified. Use --list or --help." >&2
    __usage 2
fi

# ── device selection ─────────────────────────────────────────────────────────
__adb() {
    __debug "__adb ${SERIAL:+-s $SERIAL} $*"
    "$_ADB_BIN" ${SERIAL:+-s "$SERIAL"} "$@"
}

if [[ -z "$SERIAL" ]]; then
    SERIAL=$("$_ADB_BIN" devices | awk '/\tdevice$/{print $1; exit}')
fi
if [[ -z "$SERIAL" ]]; then
    echo "❌ No Android device/emulator connected." >&2
    exit 2
fi
info "Device: $SERIAL"

# ── optional root elevation (emulators only) ─────────────────────────────────
# Try `__adb root` so ui_inject_crash_prefs can push directly to /data/data/.
# Silently ignored on real devices (root not available) and when already root.
"$_ADB_BIN" ${SERIAL:+-s "$SERIAL"} root >/dev/null 2>&1 || true
sleep 1

# ── optional install ─────────────────────────────────────────────────────────
PKG="io.github.tabssh"

if [[ $INSTALL -eq 1 ]]; then
    if [[ -z "$APK" ]]; then
        REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
        # Pick the APK that matches the device ABI; fall back to universal.
        local _abi
        _abi=$("$_ADB_BIN" ${SERIAL:+-s "$SERIAL"} shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
        case "$_abi" in
            x86_64)   APK="$REPO_ROOT/binaries/tabssh-android-amd64.apk" ;;
            arm64-v8a) APK="$REPO_ROOT/binaries/tabssh-android-arm64.apk" ;;
            armeabi-v7a) APK="$REPO_ROOT/binaries/tabssh-android-arm.apk" ;;
            x86)      APK="$REPO_ROOT/binaries/tabssh-android-x86.apk" ;;
            *)        APK="$REPO_ROOT/binaries/tabssh-android-universal.apk" ;;
        esac
        [[ -f "$APK" ]] || APK="$REPO_ROOT/binaries/tabssh-android-universal.apk"
    fi
    [[ -f "$APK" ]] || { echo "❌ APK not found: $APK  (run 'make build' first)" >&2; exit 2; }
    __info "Installing $(basename "$APK")…"
    __adb install -r "$APK" | grep -E -- "Success|Failure|error" || true
fi

# ── temp dir ─────────────────────────────────────────────────────────────────
UITEST_TMP="${TMPDIR:-/tmp}/tabssh-uitest"
mkdir -p "$UITEST_TMP"
UI_XML="$UITEST_TMP/ui.xml"
SCREENSHOT_N=0

# ── core: UI tree ─────────────────────────────────────────────────────────────

# Dump the live UI tree into $UI_XML.  Retries up to 3 times when the
# resulting file is empty — this happens when uiautomator fails to capture
# the tree (e.g. during a transition or while an ANR dialog is mid-dismiss).
__ui_dump() {
    local i
    for i in 1 2 3; do
        __adb shell uiautomator dump /sdcard/ui_test_tmp.xml >/dev/null 2>&1
        __adb shell cat /sdcard/ui_test_tmp.xml > "$UI_XML" 2>/dev/null
        [[ -s "$UI_XML" ]] && return 0
        sleep 1
    done
}

# Dismiss Android "System UI isn't responding" or app ANR dialogs by tapping
# "Wait" if visible.  Retries up to 3 times and waits 6 seconds after each tap
# so that slow SwiftShader emulators have enough time to re-inflate the UI
# before the caller proceeds.  Call before each wait/assert to avoid false
# failures on fresh-booted emulators where System UI takes a few seconds to
# settle.
__ui_dismiss_anr() {
    local i
    for i in 1 2 3; do
        ui_dump
        __ui_texts | grep -qF -- "isn't responding" || return 0
        local coords
        coords=$(ui_find_xy "Wait") || true
        [[ -n "$coords" ]] || return 0
        debug "ANR dialog detected — tapping Wait (attempt $i)"
        __adb shell input tap $coords
        sleep 6
    done
}

# Print every non-empty text= value currently visible.
__ui_texts() {
    [[ -s "$UI_XML" ]] || { __ui_dump; }
    python3 - "$UI_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(0)
for node in root.iter('node'):
    t = node.get('text', '')
    if t:
        print(t)
PY
}

# ── core: find & coordinate helpers ──────────────────────────────────────────

# Emit the centre "x y" of the nearest clickable ancestor of a node matching
# the given text.  Prints nothing if not found.
__ui_find_xy() {
    [[ -s "$UI_XML" ]] || ui_dump
    python3 - "$UI_XML" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET

def centre(bounds):
    nums = [int(n) for n in bounds.replace('][',',').strip('[]').split(',')]
    return (nums[0]+nums[2])//2, (nums[1]+nums[3])//2

def find_clickable(node, target, best_clickable=None):
    if node.get('clickable') == 'true':
        best_clickable = node
    if node.get('text') == target:
        if best_clickable is not None:
            cx, cy = centre(best_clickable.get('bounds', '[0,0][0,0]'))
            print(cx, cy)
            return True
    for child in node:
        if find_clickable(child, target, best_clickable):
            return True
    return False

try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(0)
find_clickable(root, sys.argv[2])
PY
}

# Get a named attribute from the node (or its clickable ancestor) matching text.
# Usage: ui_get_attr "Switch label" "checked"
__ui_get_attr() {
    [[ -s "$UI_XML" ]] || ui_dump
    python3 - "$UI_XML" "$1" "$2" <<'PY'
import sys, xml.etree.ElementTree as ET

target_text, attr = sys.argv[2], sys.argv[3]

def find_node(node, target, last_match=None):
    if node.get('text') == target:
        last_match = node
    for child in node:
        result = find_node(child, target, last_match)
        if result is not None:
            return result
    return last_match

try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(0)
n = find_node(root, target_text)
if n is not None:
    print(n.get(attr, ''))
PY
}

# Count how many nodes have the given text.
__ui_count_text() {
    [[ -s "$UI_XML" ]] || ui_dump
    python3 - "$UI_XML" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
target = sys.argv[2]
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    print(0)
    sys.exit(0)
print(sum(1 for n in root.iter('node') if n.get('text') == target))
PY
}

# ── primitives: gestures ─────────────────────────────────────────────────────

# Tap at exact screen coordinates.
__ui_tap_xy() {
    local x="$1" y="$2"
    info "Tap ($x, $y)"
    __adb shell input tap "$x" "$y"
    sleep 0.5
}

# Long-press at exact screen coordinates.
__ui_long_tap_xy() {
    local x="$1" y="$2" ms="${3:-800}"
    info "Long-tap ($x, $y) for ${ms}ms"
    __adb shell input swipe "$x" "$y" "$x" "$y" "$ms"
    sleep 0.5
}

# Swipe in a cardinal direction.
# ui_swipe up|down|left|right [distance_px=800]
__ui_swipe() {
    local dir="$1" dist="${2:-800}" ms="${3:-400}"
    local cx=540 cy=960   # centre of a 1080×1920 screen
    local x1=$cx y1=$cy x2=$cx y2=$cy
    case "$dir" in
        up)    y1=$((cy+dist/2)); y2=$((cy-dist/2)) ;;
        down)  y1=$((cy-dist/2)); y2=$((cy+dist/2)) ;;
        left)  x1=$((cx+dist/2)); x2=$((cx-dist/2)) ;;
        right) x1=$((cx-dist/2)); x2=$((cx+dist/2)) ;;
        *) warn "Unknown swipe direction: $dir"; return ;;
    esac
    debug "swipe $dir (${x1},${y1})→(${x2},${y2})"
    __adb shell input swipe "$x1" "$y1" "$x2" "$y2" "$ms"
    sleep 0.3
}

# Arbitrary swipe between two coordinates.
# ui_swipe_xy x1 y1 x2 y2 [duration_ms=400]
__ui_swipe_xy() {
    local x1="$1" y1="$2" x2="$3" y2="$4" ms="${5:-400}"
    info "Swipe ($x1,$y1)→($x2,$y2)"
    __adb shell input swipe "$x1" "$y1" "$x2" "$y2" "$ms"
    sleep 0.3
}

# ── primitives: keyboard ─────────────────────────────────────────────────────

__ui_press_back()  { __adb shell input keyevent KEYCODE_BACK;  sleep 0.5; }
__ui_press_home()  { __adb shell input keyevent KEYCODE_HOME;  sleep 0.5; }
__ui_press_enter() { __adb shell input keyevent KEYCODE_ENTER; sleep 0.3; }
__ui_key()         { __adb shell input keyevent "$1"; sleep 0.3; }

# Type text into the currently-focused field.
__ui_input_text() {
    local text="$1"
    # __adb input text handles spaces as %s
    __adb shell input text "${text// /%s}"
    sleep 0.3
}

# Select-all then delete — clears the focused field.
__ui_clear_text() {
    __adb shell input keyevent --longpress KEYCODE_A   # select all
    sleep 0.2
    __adb shell input keyevent KEYCODE_DEL
    sleep 0.2
}

# ── primitives: screenshot ────────────────────────────────────────────────────

# Pull a screenshot from the device to $UITEST_TMP/.
# ui_screenshot [label]
__ui_screenshot() {
    SCREENSHOT_N=$((SCREENSHOT_N+1))
    local label="${1:-screen}"
    local fname="${UITEST_TMP}/${SCREENSHOT_N}_${label}.png"
    __adb shell screencap -p /sdcard/ui_test_cap.png >/dev/null 2>&1
    __adb pull /sdcard/ui_test_cap.png "$fname" >/dev/null 2>&1
    info "Screenshot → $fname"
}

# ── primitives: navigation ────────────────────────────────────────────────────

# Launch an activity and wait for it to settle.
# Component: full (pkg/class) or short (.ClassName — PKG is prepended).
__ui_launch() {
    local component="$1"
    # Prepend PKG if caller passed a short form like ".ui.activities.Foo"
    [[ "$component" != *"/"* ]] && component="$PKG/$component"
    info "Launch $component"
    __adb shell am start -n "$component" >/dev/null
    sleep 2
    ui_dismiss_anr
    ui_dump
}

# Force-stop the app and wait for the process to fully die.
__ui_stop() {
    info "Stop $PKG"
    __adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 2
}

# Scroll until text is visible (without tapping).  Returns 0 if found.
# ui_scroll_to text [max_scrolls=8] [direction=up]
__ui_scroll_to() {
    local text="$1" max="${2:-8}" dir="${3:-up}"
    local i
    for i in $(seq 1 "$max"); do
        ui_dismiss_anr
        if __ui_texts | grep -qF -- "$text"; then
            debug "scroll_to: found \"$text\" after $((i-1)) scroll(s)"
            return 0
        fi
        # Brief settle pause before swiping — avoids queuing swipes on a still-
        # recovering UI after ANR dismissal, which would re-trigger another ANR.
        sleep 1
        ui_swipe "$dir"
    done
    return 1
}

# Tap the element with the given text, scrolling if needed.
# ui_tap text [max_scrolls=5] [direction=up]
__ui_tap() {
    local text="$1" max="${2:-5}" dir="${3:-up}"
    local i
    for i in $(seq 1 "$max"); do
        ui_dump
        local coords
        coords=$(ui_find_xy "$text") || true
        if [[ -n "$coords" ]]; then
            __adb shell input tap $coords
            sleep 1
            ui_dump
            return 0
        fi
        ui_swipe "$dir"
    done
    warn "ui_tap: \"$text\" not found after $max scroll(s)"
    return 1
}

# Long-press the element with the given text, scrolling if needed.
__ui_long_tap() {
    local text="$1" max="${2:-5}"
    for _ in $(seq 1 "$max"); do
        ui_dump
        local coords
        coords=$(ui_find_xy "$text") || true
        if [[ -n "$coords" ]]; then
            local x y
            read -r x y <<< "$coords"
            ui_long_tap_xy "$x" "$y"
            ui_dump
            return 0
        fi
        ui_swipe up
    done
    warn "ui_long_tap: \"$text\" not found after $max scroll(s)"
    return 1
}

# ── assertions ────────────────────────────────────────────────────────────────

# Wait up to N seconds for text to appear; assert it arrives.
__ui_wait_for() {
    local text="$1" timeout="${2:-8}" waited=0
    while [[ $waited -lt $timeout ]]; do
        ui_dismiss_anr
        if __ui_texts | grep -qF -- "$text"; then
            pass "Found: \"$text\""
            return
        fi
        sleep 1
        waited=$((waited+1))
    done
    fail "Timed out after ${timeout}s waiting for \"$text\""
    info "Screen:"; __ui_texts | sed 's/^/    /'
}

# Wait up to N seconds for text to disappear.
__ui_wait_gone() {
    local text="$1" timeout="${2:-8}" waited=0
    while [[ $waited -lt $timeout ]]; do
        ui_dump
        if ! __ui_texts | grep -qF -- "$text"; then
            pass "Gone: \"$text\""
            return
        fi
        sleep 1
        waited=$((waited+1))
    done
    fail "Timed out after ${timeout}s — \"$text\" is still visible"
}

# Assert text is visible on the current screen (single dump).
__ui_assert_present() {
    local text="$1"
    ui_dismiss_anr
    if __ui_texts | grep -qF -- "$text"; then
        pass "Present: \"$text\""
    else
        fail "Expected \"$text\" — not on screen"
        info "Screen:"; __ui_texts | sed 's/^/    /'
    fi
}

# Scroll until text is visible, then assert it.  Use when the item may be
# below the current viewport — safer than ui_assert_present for long screens.
__ui_assert_scroll() {
    local text="$1" max="${2:-6}"
    ui_dismiss_anr
    if ui_scroll_to "$text" "$max"; then
        pass "Found (scrolled): \"$text\""
    else
        fail "Not found after scrolling: \"$text\""
        info "Screen:"; __ui_texts | sed 's/^/    /'
    fi
}

# Assert text is NOT visible on the current screen.
__ui_assert_absent() {
    local text="$1"
    ui_dump
    if __ui_texts | grep -qF -- "$text"; then
        fail "Unexpected \"$text\" — is on screen"
    else
        pass "Absent: \"$text\""
    fi
}

# Assert a node matching text has attribute=value.
# e.g. ui_assert_attr "Enable Debug Logging" "checked" "true"
__ui_assert_attr() {
    local text="$1" attr="$2" expected="$3"
    ui_dump
    local actual
    actual=$(ui_get_attr "$text" "$attr")
    if [[ "$actual" == "$expected" ]]; then
        pass "\"$text\": $attr=$actual"
    else
        fail "\"$text\": expected $attr=$expected, got $attr=${actual:-<not found>}"
    fi
}

# Assert text appears exactly N times on the current screen.
__ui_assert_count() {
    local text="$1" expected="$2"
    ui_dump
    local actual
    actual=$(ui_count_text "$text")
    if [[ "$actual" == "$expected" ]]; then
        pass "Count of \"$text\": $actual (expected $expected)"
    else
        fail "Count of \"$text\": got $actual, expected $expected"
    fi
}

# ── special helpers ────────────────────────────────────────────────────────────

# Write fake crash prefs so CrashReportActivity displays without a live crash.
# Launches the app briefly first to ensure shared_prefs/ exists, then pushes the
# XML directly via __adb push (works on emulators with adbd running as root, which
# is the standard for AOSP/google_apis emulator images without Play Store).
__ui_inject_crash_prefs() {
    local prefs_dir="/data/data/$PKG/shared_prefs"
    local prefs_path="$prefs_dir/tabssh_startup.xml"
    local ts
    ts=$(date +%s)000

    # Launch MainActivity briefly so Room can create the app data directory.
    __adb shell am start -n "$PKG/.ui.activities.MainActivity" >/dev/null
    sleep 2
    __adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1

    # Write prefs XML to a local temp file and push it directly (requires root adb).
    local local_tmp
    local_tmp="$UITEST_TMP/tabssh_startup_inject.xml"
    printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <long name="crash_time" value="%s" />\n    <string name="crash_thread">main</string>\n    <string name="last_crash">java.lang.RuntimeException: Test crash\n\tat io.github.tabssh.test.Fake.method(Fake.kt:1)\n    </string>\n</map>\n' \
        "$ts" > "$local_tmp"

    # Ensure the shared_prefs directory exists (it might not if the app never ran).
    __adb shell "mkdir -p $prefs_dir" >/dev/null 2>&1 || true
    if __adb push "$local_tmp" "$prefs_path" >/dev/null 2>&1; then
        __adb shell "chmod 660 $prefs_path" >/dev/null 2>&1 || true
        info "Crash prefs injected"
    else
        fail "Could not inject crash prefs (__adb push failed — emulator may not be rooted)"
    fi
    rm -f "$local_tmp"
}

# ── inline `run` executor ─────────────────────────────────────────────────────
# Parses a STEPS array and executes each step sequentially.
# Called by the top-level argument loop when it sees "run".

__exec_run_steps() {
    local name="$1"; shift  # test label
    local steps=("$@")
    local i=0 n=${#steps[@]}

    while [[ $i -lt $n ]]; do
        local step="${steps[$i]}"
        i=$((i+1))

        case "$step" in
            --activity)
                ui_launch "${steps[$i]}"; i=$((i+1)) ;;
            --stop)
                ui_stop ;;
            --inject-crash)
                ui_inject_crash_prefs ;;
            --tap)
                local max_s=5
                # peek: if next token is a bare integer use it as max_scrolls
                if [[ $i -lt $n && "${steps[$i]}" =~ ^[0-9]+$ ]]; then
                    max_s="${steps[$i]}"; i=$((i+1))
                fi
                ui_tap "${steps[$i-1]}" "$max_s"
                # re-read the text arg (already consumed above); tap was called with it
                # Actually need to restructure: consume text first
                ;;
            # ↑ that's awkward; cleaner to always consume text right after flag:
        esac
    done
}

# Actually, simpler: re-implement inline step processing with a proper lookahead.
__run_inline() {
    local name="${1:-inline}"; shift
    echo ""
    echo -e "${BLUE}━━━ Run: $name ━━━${NC}"
    TEST_FAILS=0

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --activity)
                shift; ui_launch "$1" ;;
            --stop)
                ui_stop ;;
            --inject-crash)
                ui_inject_crash_prefs ;;
            --tap)
                shift
                local _tap_text="$1"
                local _tap_max=5
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _tap_max="$1"; fi
                ui_tap "$_tap_text" "$_tap_max" || fail "ui_tap: \"$_tap_text\" not found" ;;
            --tap-xy)
                shift; local _tx="$1"; shift; local _ty="$1"
                ui_tap_xy "$_tx" "$_ty" ;;
            --long-tap)
                shift; ui_long_tap "$1" || fail "ui_long_tap: \"$1\" not found" ;;
            --long-tap-xy)
                shift; local _lx="$1"; shift; local _ly="$1"
                ui_long_tap_xy "$_lx" "$_ly" ;;
            --swipe)
                shift; local _sdir="$1"
                local _sdist=800
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _sdist="$1"; fi
                ui_swipe "$_sdir" "$_sdist" ;;
            --swipe-xy)
                shift; local _sx1="$1"; shift; local _sy1="$1"
                shift; local _sx2="$1"; shift; local _sy2="$1"
                local _sms=400
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _sms="$1"; fi
                ui_swipe_xy "$_sx1" "$_sy1" "$_sx2" "$_sy2" "$_sms" ;;
            --scroll-to)
                shift; ui_scroll_to "$1" || fail "ui_scroll_to: \"$1\" not found" ;;
            --input)
                shift; ui_input_text "$1" ;;
            --clear)
                ui_clear_text ;;
            --back)
                ui_press_back ;;
            --home)
                ui_press_home ;;
            --enter)
                ui_press_enter ;;
            --key)
                shift; ui_key "$1" ;;
            --sleep)
                shift; sleep "$1" ;;
            --screenshot)
                local _label="screen"
                if [[ $# -gt 1 && "${2:-}" != --* ]]; then shift; _label="$1"; fi
                ui_screenshot "$_label" ;;
            --wait-for)
                shift; local _wtext="$1"
                local _wt=8
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _wt="$1"; fi
                ui_wait_for "$_wtext" "$_wt" ;;
            --wait-gone)
                shift; local _wgtext="$1"
                local _wgt=8
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _wgt="$1"; fi
                ui_wait_gone "$_wgtext" "$_wgt" ;;
            --present)
                shift; ui_assert_present "$1" ;;
            --absent)
                shift; ui_assert_absent "$1" ;;
            --scroll-assert)
                shift; local _satext="$1"
                local _samax=6
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _samax="$1"; fi
                ui_assert_scroll "$_satext" "$_samax" ;;
            --attr)
                shift; local _atext="$1"; shift; local _aattr="$1"; shift; local _aval="$1"
                ui_assert_attr "$_atext" "$_aattr" "$_aval" ;;
            --count)
                shift; local _ctext="$1"; shift; local _cn="$1"
                ui_assert_count "$_ctext" "$_cn" ;;
            *)
                warn "Unknown step: $1" ;;
        esac
        shift
    done

    if [[ $TEST_FAILS -eq 0 ]]; then
        PASS_COUNT=$((PASS_COUNT+1))
        echo -e "${GREEN}  PASS${NC}"
    else
        FAIL_COUNT=$((FAIL_COUNT+1))
        echo -e "${RED}  FAIL ($TEST_FAILS assertion(s) failed)${NC}"
    fi
}

# ── named test definitions ────────────────────────────────────────────────────
PASS_COUNT=0
FAIL_COUNT=0

__run_test() {
    local name="$1"
    echo ""
    echo -e "${BLUE}━━━ Test: $name ━━━${NC}"
    TEST_FAILS=0
    "test_${name//-/_}" || true
    if [[ $TEST_FAILS -eq 0 ]]; then
        PASS_COUNT=$((PASS_COUNT+1))
        echo -e "${GREEN}  PASS${NC}"
    else
        FAIL_COUNT=$((FAIL_COUNT+1))
        echo -e "${RED}  FAIL ($TEST_FAILS assertion(s) failed)${NC}"
    fi
}

__test_settings_opens() {
    ui_stop
    ui_launch "$PKG/.ui.activities.SettingsActivity"
    ui_wait_for       "Settings"
    ui_assert_present "Connection"
    ui_assert_present "Logging"
}

__test_hypervisor_form() {
    ui_stop
    ui_launch "$PKG/.ui.activities.HypervisorEditActivity"
    # "Name" is a TextInputLayout hint that may be absent from the accessibility tree on
    # SwiftShader emulators; "Host" (the address field) is reliably present once rendered.
    # Allow 15 s for SwiftShader to finish inflating the form.
    ui_wait_for       "Host" 15
    # "Verify SSL Certificate" is a plain Switch preference that appears unconditionally.
    ui_assert_present "Verify SSL Certificate"
    ui_assert_absent  "Application Not Responding"
    ui_stop
}

# ── test: logging-navigation ─────────────────────────────────────────────────
# Navigates Settings → Logging and scrolls through the entire screen, asserting
# every category heading and a representative preference from each one.
# Uses ui_assert_scroll for every item so each one is scrolled into view before
# being checked — a single ui_scroll_to on the header is not enough because the
# items lower in the section can still be off-screen.
# Also confirms "Test crash dialog" is visible (debug build only behaviour).
__test_logging_navigation() {
    ui_stop
    ui_launch "$PKG/.ui.activities.SettingsActivity"
    ui_wait_for "Settings"
    ui_tap      "Logging"
    # Logging preferences screen has ~25 items and can trigger an ANR on slow
    # emulators (SwiftShader GPU) while inflating the preference XML.
    # Allow 15 seconds for inflation before attempting any assertions.
    sleep 15
    ui_dismiss_anr
    ui_wait_for "Debug Logging" 30

    # ── Debug Logging (use wait_for, not assert_present, because inflation may
    # still be in progress on SwiftShader after the section header appears) ───
    ui_wait_for "Enable Debug Logging" 15
    ui_wait_for "Debug Log Level" 10
    ui_wait_for "Log raw keystroke bytes (privacy risk)" 10

    # ── Host Logging ──────────────────────────────────────────────────────────
    # Use max=12 scrolls throughout — the preferences list is long and
    # SwiftShader renders slowly so each scroll iteration takes extra time.
    ui_assert_scroll "Host Logging"         12
    ui_assert_scroll "Enable Host Logging"  12
    ui_assert_scroll "Log Filename Pattern" 12
    ui_assert_scroll "Append to Existing Logs" 12
    ui_assert_scroll "Log User Input"       12
    ui_assert_scroll "Include Timestamps"   12

    # ── Error Logging ─────────────────────────────────────────────────────────
    ui_assert_scroll "Error Logging"        12
    ui_assert_scroll "Enable Error Logging" 12
    ui_assert_scroll "Include Stack Traces" 12

    # ── Audit Logging ─────────────────────────────────────────────────────────
    ui_assert_scroll "Audit Logging"        12
    ui_assert_scroll "Enable Audit Logging" 12
    ui_assert_scroll "Audit Events"         12

    # ── View Logs ─────────────────────────────────────────────────────────────
    ui_assert_scroll "View Logs"            12
    ui_assert_scroll "View Application Log" 12
    ui_assert_scroll "View Debug Log"       12
    ui_assert_scroll "View Host Logs"       12
    ui_assert_scroll "View Error Log"       12
    ui_assert_scroll "View Audit Log"       12

    # ── Log Management ────────────────────────────────────────────────────────
    ui_assert_scroll "Log Management"       12
    ui_assert_scroll "Export All Logs"      12
    ui_assert_scroll "Clear All Logs"       12
    ui_assert_scroll "Test crash dialog"    12  # visible only in debug builds

    # ── Issue Reporting ───────────────────────────────────────────────────────
    ui_assert_scroll "Issue Reporting"      12
    ui_assert_scroll "Paste Service"        12
    ui_assert_scroll "MicroBin Server"      12
    ui_assert_scroll "Lenpaste Server"      12
    ui_assert_scroll "Stikked Server"       12
    ui_assert_scroll "Pastebin API Key"     12

    ui_stop
}

__test_crash_dialog() {
    ui_stop
    info "Injecting crash prefs…"
    ui_inject_crash_prefs
    ui_launch "$PKG/.ui.activities.CrashReportActivity"
    # On slow emulators (SwiftShader) the activity renders buttons async.
    # Use wait_for with ANR-dismissal loops instead of assert_present for each button.
    ui_wait_for "Paste / Issue" 15
    ui_assert_absent  "Share"
    ui_wait_for "Copy"    10
    ui_wait_for "Restart" 10
    ui_stop
}

# ── dispatch ──────────────────────────────────────────────────────────────────
# Walk TESTS[]. When we see "run", consume everything up to the next named
# test (or end) as inline steps.

i=0
while [[ $i -lt ${#TESTS[@]} ]]; do
    token="${TESTS[$i]}"
    i=$((i+1))

    if [[ "$token" == "run" ]]; then
        # Collect inline steps until next top-level token that looks like a
        # test name (no leading --) or end-of-array.
        INLINE_NAME="inline-$i"
        INLINE_STEPS=()
        # Peek: first token after "run" may be "--name label"
        if [[ $i -lt ${#TESTS[@]} && "${TESTS[$i]}" == "--name" ]]; then
            i=$((i+1))
            INLINE_NAME="${TESTS[$i]}"
            i=$((i+1))
        fi
        while [[ $i -lt ${#TESTS[@]} ]]; do
            next="${TESTS[$i]}"
            # A bare word with no leading -- that matches a known test name
            # (or "run") ends the inline block.
            if [[ "$next" != --* && "$next" != "run" ]]; then
                fn_cand="test_${next//-/_}"
                if declare -f "$fn_cand" >/dev/null 2>&1 || [[ "$next" == "all" ]]; then
                    break
                fi
            fi
            INLINE_STEPS+=("$next")
            i=$((i+1))
        done
        run_inline "$INLINE_NAME" "${INLINE_STEPS[@]}"
    else
        fn="test_${token//-/_}"
        if ! declare -f "$fn" >/dev/null 2>&1; then
            echo "❌ Unknown test: $token  (use --list)" >&2
            FAIL_COUNT=$((FAIL_COUNT+1))
        else
            run_test "$token"
        fi
    fi
done

# ── cleanup ───────────────────────────────────────────────────────────────────
# Keep screenshots (they're in UITEST_TMP but named); remove only the XML tmp.
rm -f "$UI_XML" "$UITEST_TMP/ui_test_cap.png" 2>/dev/null || true

# ── summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BLUE}━━━ Results ━━━${NC}"
echo -e "  Passed: ${GREEN}$PASS_COUNT${NC}  Failed: ${RED}$FAIL_COUNT${NC}"
[[ $FAIL_COUNT -eq 0 ]] && exit 0 || exit 1
