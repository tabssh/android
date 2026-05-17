#!/usr/bin/env bash
# scripts/ui-test.sh — scriptable UI test runner for TabSSH on a live emulator/device.
#
# Resolves adb the same way android-emulator.sh does.  Each test is a small
# declarative block: launch an activity, navigate a chain of taps, then assert
# text that must (or must not) be visible on the resulting screen.
#
# Usage:
#   scripts/ui-test.sh [--serial <serial>] [--apk <path>] [--install] <test-name>…
#   scripts/ui-test.sh --list
#
# Options:
#   --serial <serial>  ADB device serial (default: first connected device)
#   --apk <path>       APK to install before running (implies --install)
#   --install          Install binaries/tabssh-android-x86.apk before running
#   --list             Print available tests and exit
#   --help             Print this help and exit
#
# Test names (pass one or more, or "all"):
#   crash-dialog       Navigate Settings → Logging → Test crash, relaunch, verify
#                      crash report screen shows "Paste / Issue" not "Share"
#   hypervisor-form    Open HypervisorEditActivity, verify form renders without ANR
#   settings-opens     Launch SettingsActivity, verify main settings screen
#
# Exit codes:
#   0  all requested tests passed
#   1  one or more tests failed
#   2  usage / setup error
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  SDK root (default: /opt/android)
#   ADB_SERIAL                       device serial (same as --serial)

set -euo pipefail

# ── colour ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; NC='\033[0m'
TEST_FAILS=0  # per-test failure counter, reset by run_test

pass() { echo -e "${GREEN}  ✅ $*${NC}"; }
fail() { echo -e "${RED}  ❌ $*${NC}"; TEST_FAILS=$((TEST_FAILS+1)); }
info() { echo -e "${BLUE}  ▸ $*${NC}"; }
warn() { echo -e "${YELLOW}  ⚠ $*${NC}"; }

# ── SDK / adb resolution ─────────────────────────────────────────────────────
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
ADB="$SDK/platform-tools/adb"
export ANDROID_HOME="$SDK"

# ── args ─────────────────────────────────────────────────────────────────────
SERIAL="${ADB_SERIAL:-}"
APK=""
INSTALL=0
TESTS=()

usage() {
    grep '^#' "$0" | grep -v '^#!/' | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)  shift; SERIAL="$1" ;;
        --apk)     shift; APK="$1"; INSTALL=1 ;;
        --install) INSTALL=1 ;;
        --list)
            echo "Available tests:"
            echo "  crash-dialog      Crash report dialog shows 'Paste / Issue' button"
            echo "  hypervisor-form   HypervisorEditActivity renders without ANR"
            echo "  settings-opens    SettingsActivity main screen is navigable"
            echo "  all               Run all of the above"
            exit 0 ;;
        --help|-h) usage 0 ;;
        all)       TESTS+=(crash-dialog hypervisor-form settings-opens) ;;
        -*)        echo "Unknown option: $1" >&2; usage 2 ;;
        *)         TESTS+=("$1") ;;
    esac
    shift
done

if [[ ${#TESTS[@]} -eq 0 ]]; then
    echo "No tests specified. Use --list to see available tests." >&2
    usage 2
fi

# ── device selection ─────────────────────────────────────────────────────────
adb() { "$ADB" ${SERIAL:+-s "$SERIAL"} "$@"; }

if [[ -z "$SERIAL" ]]; then
    SERIAL=$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')
fi
if [[ -z "$SERIAL" ]]; then
    echo "❌ No Android device/emulator connected." >&2
    exit 2
fi
info "Device: $SERIAL"

# ── optional install ─────────────────────────────────────────────────────────
PKG="io.github.tabssh"

if [[ $INSTALL -eq 1 ]]; then
    if [[ -z "$APK" ]]; then
        REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
        APK="$REPO_ROOT/binaries/tabssh-android-x86.apk"
        [[ -f "$APK" ]] || APK="$REPO_ROOT/binaries/tabssh-android-universal.apk"
    fi
    if [[ ! -f "$APK" ]]; then
        echo "❌ APK not found: $APK  (run 'make build' first)" >&2
        exit 2
    fi
    info "Installing $(basename "$APK")…"
    adb install -r "$APK" | grep -E "Success|Failure|error" || true
fi

# ── helper library ───────────────────────────────────────────────────────────
TMPDIR_TEST="${TMPDIR:-/tmp}/tabssh-uitest"
mkdir -p "$TMPDIR_TEST"
UI_XML="$TMPDIR_TEST/ui.xml"

# Dump the current UI tree and cache it in $UI_XML.
ui_dump() {
    adb shell uiautomator dump /sdcard/ui_test_tmp.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui_test_tmp.xml > "$UI_XML" 2>/dev/null
}

# Print every non-empty text= value visible on screen.
ui_texts() {
    python3 - "$UI_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter('node'):
    t = node.get('text', '')
    if t:
        print(t)
PY
}

# Return the centre coordinates of the clickable ancestor of a node with the
# given text.  Prints "x y" or nothing if not found.
ui_find() {
    python3 - "$UI_XML" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET

def centre(bounds):
    # bounds = "[x1,y1][x2,y2]"
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

root = ET.parse(sys.argv[1]).getroot()
find_clickable(root, sys.argv[2])
PY
}

# Tap the element with the given text, scrolling if needed.
# Returns 0 on success, 1 if not found after scrolling.
ui_tap() {
    local text="$1"
    local max_scrolls="${2:-5}"
    for _ in $(seq 1 "$max_scrolls"); do
        ui_dump
        local coords
        coords=$(ui_find "$text") || true
        if [[ -n "$coords" ]]; then
            adb shell input tap $coords
            sleep 1
            return 0
        fi
        adb shell input swipe 540 1400 540 600 400
        sleep 0.5
    done
    return 1
}

# Wait up to N seconds for text to appear, then assert it.
ui_wait_for() {
    local text="$1"
    local timeout="${2:-8}"
    local waited=0
    while [[ $waited -lt $timeout ]]; do
        ui_dump
        if ui_texts | grep -qF "$text"; then
            pass "Found: \"$text\""
            return
        fi
        sleep 1
        waited=$((waited+1))
    done
    fail "Timed out after ${timeout}s waiting for \"$text\""
    info "Screen contents:"; ui_texts | sed 's/^/    /'
}

# Assert text is visible on screen (dumps UI first).
ui_assert_present() {
    local text="$1"
    ui_dump
    if ui_texts | grep -qF "$text"; then
        pass "Found: \"$text\""
    else
        fail "Expected \"$text\" — not found on screen"
        info "Screen contents:"; ui_texts | sed 's/^/    /'
    fi
}

# Assert text is NOT visible on screen.
ui_assert_absent() {
    local text="$1"
    ui_dump
    if ui_texts | grep -qF "$text"; then
        fail "Did not expect \"$text\" — but it is visible"
    else
        pass "Absent (correct): \"$text\""
    fi
}

# Launch an activity and wait for it to settle.
ui_launch() {
    local component="$1"   # e.g. io.github.tabssh/.ui.activities.SettingsActivity
    adb shell am start -n "$component" >/dev/null
    sleep 2
}

# Force-stop the app and wait for it to fully die.
ui_stop() {
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 2
}

# Write crash prefs directly so the crash dialog fires on next launch without
# needing a live crash (works around API 34 ANR suppression of Java handler).
ui_inject_crash_prefs() {
    local prefs_path="/data/data/$PKG/shared_prefs/tabssh_startup.xml"
    local ts
    ts=$(date +%s)000

    # Launch the app briefly to ensure shared_prefs/ directory is created,
    # then stop it before we write.
    adb shell am start -n "$PKG/.ui.activities.MainActivity" >/dev/null
    sleep 2
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1

    # Build XML — use printf with explicit escape so base64 payload is clean.
    local xml
    xml=$(printf '<?xml version='"'"'1.0'"'"' encoding='"'"'utf-8'"'"' standalone='"'"'yes'"'"' ?>\n<map>\n    <long name="crash_time" value="%s" />\n    <string name="crash_thread">main</string>\n    <string name="last_crash">java.lang.RuntimeException: Test crash&#10;&#9;at io.github.tabssh.test.Fake.method(Fake.kt:1)&#10;    </string>\n</map>' "$ts")
    local b64
    b64=$(printf '%s' "$xml" | base64 -w0)

    # run-as writes to the app's private data dir; base64 avoids shell quoting issues.
    # The entire command is sent as one string to `adb shell` so the Android shell
    # parses the `>` redirect inside the sh -c, not before run-as.
    if adb shell "run-as '$PKG' sh -c 'echo $b64 | base64 -d > $prefs_path'" 2>/dev/null; then
        info "Crash prefs injected"
    else
        fail "Could not inject crash prefs (run-as failed)"
    fi
}

# ── test definitions ─────────────────────────────────────────────────────────
PASS_COUNT=0
FAIL_COUNT=0

run_test() {
    local name="$1"
    echo ""
    echo -e "${BLUE}━━━ Test: $name ━━━${NC}"
    TEST_FAILS=0
    "test_${name//-/_}" || true   # run-as / adb errors don't short-circuit; fail() tracks them
    if [[ $TEST_FAILS -eq 0 ]]; then
        PASS_COUNT=$((PASS_COUNT+1))
        echo -e "${GREEN}  PASS${NC}"
    else
        FAIL_COUNT=$((FAIL_COUNT+1))
        echo -e "${RED}  FAIL ($TEST_FAILS assertion(s) failed)${NC}"
    fi
}

# ── test: settings-opens ─────────────────────────────────────────────────────
test_settings_opens() {
    ui_stop
    ui_launch "$PKG/.ui.activities.SettingsActivity"
    ui_wait_for       "Settings"
    ui_assert_present "General"
    ui_assert_present "Logging"
}

# ── test: hypervisor-form ─────────────────────────────────────────────────────
test_hypervisor_form() {
    ui_stop
    ui_launch "$PKG/.ui.activities.HypervisorEditActivity"
    ui_wait_for       "Name"
    ui_assert_present "Type"
    ui_assert_absent  "Application Not Responding"
    ui_stop
}

# ── test: crash-dialog ───────────────────────────────────────────────────────
test_crash_dialog() {
    ui_stop
    info "Injecting crash prefs…"
    ui_inject_crash_prefs
    # CrashReportActivity is exported in debug builds (app/src/debug/AndroidManifest.xml)
    # so we can launch it directly without going through MainActivity.
    ui_launch "$PKG/.ui.activities.CrashReportActivity"
    ui_wait_for        "Paste / Issue"   # waits up to 8s for the screen to load
    ui_assert_absent   "Share"
    ui_assert_present  "Copy"
    ui_assert_present  "Restart"
    ui_stop
}

# ── run requested tests ───────────────────────────────────────────────────────
for t in "${TESTS[@]}"; do
    fn="test_${t//-/_}"
    if ! declare -f "$fn" >/dev/null 2>&1; then
        echo "❌ Unknown test: $t  (use --list)" >&2
        FAIL_COUNT=$((FAIL_COUNT+1))
        continue
    fi
    run_test "$t"
done

# ── cleanup ───────────────────────────────────────────────────────────────────
rm -rf "$TMPDIR_TEST"

# ── summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BLUE}━━━ Results ━━━${NC}"
echo -e "  Passed: ${GREEN}$PASS_COUNT${NC}  Failed: ${RED}$FAIL_COUNT${NC}"
[[ $FAIL_COUNT -eq 0 ]] && exit 0 || exit 1
