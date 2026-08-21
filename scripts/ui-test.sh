#!/usr/bin/env bash
##@Version 202608210120-git
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
#   --debug             Alias for --verbose
#   --color             Force colour/emoji output even when NO_COLOR is set
#   --list              Print available named tests and exit
#   --help, -h          Print this help and exit
#   --version, -v       Print the script version and exit
#
# Named tests (pass one or more, or "all"):
#   crash-dialog        Crash report dialog shows "Paste / Issue" not "Share"
#   hypervisor-form     HypervisorEditActivity renders without ANR
#   settings-opens      SettingsActivity main screen is navigable
#   logging-navigation  Settings → Logging: all sections and key prefs visible
#   main-tabs           Every main tab (and Infra sub-tab) switches and renders
#   nav-drawer          Drawer lists all entries, closes, and each opens
#   settings-screens    Every Settings category opens its own screen
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

VERSION="202608210120-git"

# ── colour ───────────────────────────────────────────────────────────────────
__colour_on() {
    USE_EMOJI=1
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
    BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
}
__colour_off() {
    USE_EMOJI=0
    RED=''; GREEN=''; YELLOW=''
    BLUE=''; CYAN=''; NC=''
}
if [[ -n "${NO_COLOR:-}" ]]; then
    __colour_off
else
    __colour_on
fi
TEST_FAILS=0

__pass() {
    local emoji=""
    [[ $USE_EMOJI -eq 1 ]] && emoji="✅ "
    echo -e "${GREEN}  ${emoji}$*${NC}"
}
__fail() {
    local emoji=""
    [[ $USE_EMOJI -eq 1 ]] && emoji="❌ "
    echo -e "${RED}  ${emoji}$*${NC}"
    TEST_FAILS=$((TEST_FAILS+1))
}
__info() {
    local emoji=""
    [[ $USE_EMOJI -eq 1 ]] && emoji="▸ "
    echo -e "${BLUE}  ${emoji}$*${NC}"
}
__warn() {
    local emoji=""
    [[ $USE_EMOJI -eq 1 ]] && emoji="⚠ "
    echo -e "${YELLOW}  ${emoji}$*${NC}"
}
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
# Positional list of test names / "run" tokens
TESTS=()

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
        --debug)    VERBOSE=1 ;;
        --color)    __colour_on ;;
        --list)
            echo "Named tests:"
            echo "  crash-dialog        Crash report dialog shows 'Paste / Issue' not 'Share'"
            echo "  hypervisor-form     HypervisorEditActivity renders without ANR"
            echo "  settings-opens      SettingsActivity main screen is navigable"
            echo "  logging-navigation  Settings → Logging: all sections and key prefs visible"
            echo "  main-tabs           Every main tab (and Infra sub-tab) switches and renders"
            echo "  nav-drawer          Drawer lists all entries, closes, and each opens"
            echo "  settings-screens    Every Settings category opens its own screen"
            echo "  all                 All of the above"
            echo ""
            echo "Use 'run STEPS…' for inline tests — see --help for step reference."
            exit 0 ;;
        --help|-h)  __usage 0 ;;
        -v|--version) echo "ui-test.sh $VERSION"; exit 0 ;;
        all)        TESTS+=(crash-dialog hypervisor-form settings-opens logging-navigation
                             main-tabs nav-drawer settings-screens) ;;
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
__info "Device: $SERIAL"

# ── optional root elevation (emulators only) ─────────────────────────────────
# Try `__adb root` so __ui_inject_crash_prefs can push directly to /data/data/.
# Silently ignored on real devices (root not available) and when already root.
"$_ADB_BIN" ${SERIAL:+-s "$SERIAL"} root >/dev/null 2>&1 || true
sleep 1

# ── optional install ─────────────────────────────────────────────────────────
PKG="io.github.tabssh"
# User-visible label (res/values/strings.xml app_name).  ANR dialogs are titled
# with the label, not the package, so __ui_dismiss_anr needs it to tell an ANR
# in the app under test apart from one in the launcher or System UI.
APP_LABEL="TabSSH"

if [[ $INSTALL -eq 1 ]]; then
    if [[ -z "$APK" ]]; then
        # ${0%/*} is $0 unchanged when the script was found on PATH with no
        # slash in it, so fall back to the current directory in that case.
        _self_dir="${0%/*}"
        [[ "$_self_dir" == "$0" ]] && _self_dir="."
        REPO_ROOT="$(cd "$_self_dir/.." && pwd)"
        # Pick the APK that matches the device ABI; fall back to universal.
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
    __info "Installing ${APK##*/}…"
    # -g grants every runtime permission up front. Without it the first launch
    # stops on the notification-permission dialog and every later assertion
    # reads that dialog instead of the app.
    INSTALL_OUT=$(__adb install -r -g "$APK" 2>&1) || true
    # A debug build signed with a different keystore than the one already on the
    # device can only be installed after removing the old copy. Emulators are
    # disposable, so wipe and retry there; on a real device stop and say why,
    # because uninstalling would take the user's app data with it.
    if grep -qF -- "INSTALL_FAILED_UPDATE_INCOMPATIBLE" <<<"$INSTALL_OUT"; then
        if [[ "$SERIAL" == emulator-* ]]; then
            __warn "Signature mismatch with the installed copy — uninstalling and retrying"
            __adb uninstall "$PKG" >/dev/null 2>&1 || true
            INSTALL_OUT=$(__adb install -r -g "$APK" 2>&1) || true
        else
            echo "❌ $PKG is installed with a different signing key." >&2
            echo "   Uninstall it on $SERIAL first (this erases its app data), then re-run." >&2
            exit 2
        fi
    fi
    grep -E -- "Success|Failure|error" <<<"$INSTALL_OUT" || true
    grep -qF -- "Success" <<<"$INSTALL_OUT" || { echo "❌ Install failed — tests would run against a stale build." >&2; exit 2; }
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
    # An empty dump means uiautomator itself failed — most often a previous
    # run was killed mid-dump and left its UiAutomation service registered,
    # after which every dump crashes with "already registered". Every
    # assertion downstream would then fail against a blank screen, so say so
    # rather than let it look like the app lost its UI.
    # Returns success anyway: `set -e` is in force and most call sites invoke
    # this as a bare statement, so a non-zero status here would abort the run
    # instead of letting the individual assertion report its own failure.
    __warn "uiautomator dump returned nothing — assertions below are unreliable (reboot the device to clear a stale UiAutomation service)"
    return 0
}

# Dismiss Android ANR dialogs ("<app> isn't responding") so a slow emulator
# cannot make every downstream assertion fail against a dialog that has nothing
# to do with the app under test.  Call before each wait/assert.
#
# The dialog title is matched on "responding" alone: Android renders the
# contraction with a typographic apostrophe (U+2019), so an ASCII "isn't"
# pattern silently never matches and the dialog is left sitting on screen.
#
# Button choice depends on whose ANR it is.  For the app under test "Wait" is
# correct — the test wants that process alive.  For anything else (most often
# Pixel Launcher or System UI right after boot) "Close app" clears the dialog
# immediately and lets the system restart the process, whereas "Wait" leaves
# the dialog to reappear a few seconds later.
__ui_dismiss_anr() {
    local i coords screen
    for i in 1 2 3; do
        __ui_dump
        screen=$(__ui_texts)
        grep -q -- "responding" <<<"$screen" || return 0
        local button="Close app"
        grep -q -- "$APP_LABEL" <<<"$screen" && button="Wait"
        coords=$(__ui_find_xy "$button") || true
        if [[ -z "$coords" ]]; then
            # Whichever button this dialog offers, take the other one rather
            # than leaving the dialog up.
            [[ "$button" == "Wait" ]] && button="Close app" || button="Wait"
            coords=$(__ui_find_xy "$button") || true
        fi
        [[ -n "$coords" ]] || return 0
        __debug "ANR dialog detected — tapping $button (attempt $i)"
        __adb shell input tap $coords
        sleep 6
    done
    __ui_dump
    __ui_texts | grep -q -- "responding" &&
        __warn "ANR dialog still on screen after 3 dismissal attempts"
    return 0
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
    [[ -s "$UI_XML" ]] || __ui_dump
    python3 - "$UI_XML" "$1" <<'PY'
import sys, xml.etree.ElementTree as ET

def centre(bounds):
    nums = [int(n) for n in bounds.replace('][',',').strip('[]').split(',')]
    return (nums[0]+nums[2])//2, (nums[1]+nums[3])//2

def find_clickable(node, target, best_clickable=None):
    if node.get('clickable') == 'true':
        best_clickable = node
    if node.get('text') == target:
        # A missing clickable ancestor does not mean the label cannot be
        # tapped — menu rows frequently mark no node clickable at all.
        # The label's own centre lands inside the row either way.
        hit = best_clickable if best_clickable is not None else node
        cx, cy = centre(hit.get('bounds', '[0,0][0,0]'))
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
# Usage: __ui_get_attr "Switch label" "checked"
__ui_get_attr() {
    [[ -s "$UI_XML" ]] || __ui_dump
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
    [[ -s "$UI_XML" ]] || __ui_dump
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
    __info "Tap ($x, $y)"
    __adb shell input tap "$x" "$y"
    sleep 0.5
}

# Long-press at exact screen coordinates.
__ui_long_tap_xy() {
    local x="$1" y="$2" ms="${3:-800}"
    __info "Long-tap ($x, $y) for ${ms}ms"
    __adb shell input swipe "$x" "$y" "$x" "$y" "$ms"
    sleep 0.5
}

# Resolve the display size once per run into UI_SCREEN_W / UI_SCREEN_H. Every
# gesture derives its coordinates from these, so the same test drives a phone
# and a tablet without hardcoding one device's resolution.
UI_SCREEN_W=""
UI_SCREEN_H=""
__ui_screen_size() {
    [[ -n "$UI_SCREEN_W" ]] && return 0
    local size
    size=$(__adb shell wm size 2>/dev/null | tr -d '\r' | grep -oE -- '[0-9]+x[0-9]+' | tail -1)
    UI_SCREEN_W="${size%x*}"
    UI_SCREEN_H="${size#*x}"
    if [[ -z "$UI_SCREEN_W" || -z "$UI_SCREEN_H" ]]; then
        __warn "Could not read display size — assuming 1080×1920"
        UI_SCREEN_W=1080
        UI_SCREEN_H=1920
    fi
    __debug "screen ${UI_SCREEN_W}×${UI_SCREEN_H}"
}

# Swipe in a cardinal direction.
# __ui_swipe up|down|left|right [distance_px=800]
__ui_swipe() {
    local dir="$1" dist="${2:-800}" ms="${3:-400}"
    __ui_screen_size
    local cx=$((UI_SCREEN_W / 2)) cy=$((UI_SCREEN_H / 2))
    local x1=$cx y1=$cy x2=$cx y2=$cy
    case "$dir" in
        up)    y1=$((cy+dist/2)); y2=$((cy-dist/2)) ;;
        down)  y1=$((cy-dist/2)); y2=$((cy+dist/2)) ;;
        left)  x1=$((cx+dist/2)); x2=$((cx-dist/2)) ;;
        right) x1=$((cx-dist/2)); x2=$((cx+dist/2)) ;;
        *) __warn "Unknown swipe direction: $dir"; return ;;
    esac
    __debug "swipe $dir (${x1},${y1})→(${x2},${y2})"
    __adb shell input swipe "$x1" "$y1" "$x2" "$y2" "$ms"
    sleep 0.3
}

# Arbitrary swipe between two coordinates.
# __ui_swipe_xy x1 y1 x2 y2 [duration_ms=400]
__ui_swipe_xy() {
    local x1="$1" y1="$2" x2="$3" y2="$4" ms="${5:-400}"
    __info "Swipe ($x1,$y1)→($x2,$y2)"
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
    # Escape device-shell metacharacters, then encode spaces as %s (input-text convention).
    # Without this, ';', '|', '$', quotes etc. are interpreted by the device-side sh.
    local esc
    esc=$(printf '%s' "$text" | sed -e 's/[][(){}<>|;&*\\~"'"'"'`$!#?=]/\\&/g' -e 's/ /%s/g')
    __adb shell input text "$esc"
    sleep 0.3
}

# Select-all then delete — clears the focused field.
__ui_clear_text() {
    # Ctrl+A (CTRL_LEFT=113, A=29) selects all; longpress KEYCODE_A types a literal 'a' on API 30+
    __adb shell input keycombination 113 29
    sleep 0.2
    __adb shell input keyevent KEYCODE_DEL
    sleep 0.2
}

# ── primitives: screenshot ────────────────────────────────────────────────────

# Pull a screenshot from the device to $UITEST_TMP/.
# __ui_screenshot [label]
__ui_screenshot() {
    SCREENSHOT_N=$((SCREENSHOT_N+1))
    local label="${1:-screen}"
    local fname="${UITEST_TMP}/${SCREENSHOT_N}_${label}.png"
    __adb shell screencap -p /sdcard/ui_test_cap.png >/dev/null 2>&1
    __adb pull /sdcard/ui_test_cap.png "$fname" >/dev/null 2>&1
    __info "Screenshot → $fname"
}

# ── primitives: navigation ────────────────────────────────────────────────────

# Launch an activity and wait for it to settle.
# Component: full (pkg/class) or short (.ClassName — PKG is prepended).
__ui_launch() {
    local component="$1"
    # Prepend PKG if caller passed a short form like ".ui.activities.Foo"
    [[ "$component" != *"/"* ]] && component="$PKG/$component"
    __info "Launch $component"
    __adb shell am start -n "$component" >/dev/null
    sleep 2
    __ui_dismiss_anr
    __ui_dismiss_permission
    __ui_dump
}

# Clear a runtime-permission dialog if one is showing. Belt-and-braces next to
# `install -g`: a device that already had the app installed keeps its old
# permission state, so the dialog can still appear on a re-run.
__ui_dismiss_permission() {
    local i coords
    for i in 1 2 3; do
        __ui_dump
        __ui_texts | grep -qE -- "Allow .* to|to send you notifications" || return 0
        coords=$(__ui_find_xy "Allow") || true
        [[ -n "$coords" ]] || return 0
        __debug "Permission dialog detected — tapping Allow (attempt $i)"
        __adb shell input tap $coords
        sleep 2
    done
}

# Force-stop the app and wait for the process to fully die.
__ui_stop() {
    __info "Stop $PKG"
    __adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 2
}

# Scroll until text is visible (without tapping).  Returns 0 if found.
# __ui_scroll_to text [max_scrolls=8] [direction=up]
__ui_scroll_to() {
    local text="$1" max="${2:-8}" dir="${3:-up}"
    local i
    # Search downward from the top. Scrolling only ever moves one way, so a
    # list left part-way down by a previous lookup would hide everything above
    # it and report entries that are plainly on screen as missing.
    __ui_dump
    if [[ "$dir" == "up" ]] && ! __ui_texts | grep -qF -- "$text"; then
        __ui_scroll_top
    fi
    for i in $(seq 1 "$max"); do
        __ui_dismiss_anr
        if __ui_texts | grep -qF -- "$text"; then
            __debug "scroll_to: found \"$text\" after $((i-1)) scroll(s)"
            return 0
        fi
        # Brief settle pause before swiping — avoids queuing swipes on a still-
        # recovering UI after ANR dismissal, which would re-trigger another ANR.
        sleep 1
        __ui_swipe "$dir"
    done
    return 1
}

# Fling the current list back to its first item. Stops early once two
# consecutive dumps show the same content, so a short list costs one swipe.
__ui_scroll_top() {
    local i prev="" now
    for i in 1 2 3 4 5 6; do
        __ui_dump
        now=$(__ui_texts | md5sum)
        [[ "$now" == "$prev" ]] && return 0
        prev="$now"
        __ui_swipe down
        sleep 0.4
    done
}

# Tap the element with the given text, scrolling if needed.
# __ui_tap text [max_scrolls=5] [direction=up]
__ui_tap() {
    local text="$1" max="${2:-5}" dir="${3:-up}"
    local i
    for i in $(seq 1 "$max"); do
        __ui_dump
        local coords
        coords=$(__ui_find_xy "$text") || true
        if [[ -n "$coords" ]]; then
            __adb shell input tap $coords
            sleep 1
            __ui_dump
            return 0
        fi
        __ui_swipe "$dir"
    done
    __warn "__ui_tap: \"$text\" not found after $max scroll(s)"
    return 1
}

# Long-press the element with the given text, scrolling if needed.
__ui_long_tap() {
    local text="$1" max="${2:-5}"
    for _ in $(seq 1 "$max"); do
        __ui_dump
        local coords
        coords=$(__ui_find_xy "$text") || true
        if [[ -n "$coords" ]]; then
            local x y
            read -r x y <<< "$coords"
            __ui_long_tap_xy "$x" "$y"
            __ui_dump
            return 0
        fi
        __ui_swipe up
    done
    __warn "__ui_long_tap: \"$text\" not found after $max scroll(s)"
    return 1
}

# Tap an element by content-description rather than text. The navigation
# hamburger is an icon with no text, so text-based lookup can never find it.
# The toolbar's navigation icon only gets its description once the drawer
# toggle has synced, a beat after the activity first draws, so the lookup is
# retried rather than failed on the first dump.
# __ui_tap_desc description [attempts=6]
__ui_tap_desc() {
    local desc="$1" attempts="${2:-6}" try
    local coords=""
    for ((try = 0; try < attempts; try++)); do
        [[ $try -gt 0 ]] && sleep 1
        __ui_dump
        coords=$(python3 - "$UI_XML" "$desc" <<'PY'
import sys, xml.etree.ElementTree as ET

def centre(bounds):
    nums = [int(n) for n in bounds.replace('][', ',').strip('[]').split(',')]
    return (nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2

try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(0)
for node in root.iter('node'):
    if node.get('content-desc') == sys.argv[2]:
        cx, cy = centre(node.get('bounds', '[0,0][0,0]'))
        print(cx, cy)
        break
PY
        ) || true
        [[ -n "$coords" ]] && break
    done
    if [[ -z "$coords" ]]; then
        __warn "__ui_tap_desc: \"$desc\" not found"
        return 1
    fi
    __adb shell input tap $coords
    sleep 1
    __ui_dump
}

# Bring MainActivity to the front if something else is on top. Back from a
# destination usually lands there on its own, but a destination that finishes
# its own task (or an extra Back consumed by a dialog) leaves the launcher
# showing, and every later drawer lookup would then fail for the wrong reason.
__ui_ensure_main() {
    local top
    top=$(__adb shell dumpsys activity activities 2>/dev/null | grep -m1 -- "topResumedActivity" || true)
    grep -qF -- ".ui.activities.MainActivity" <<<"$top" && return 0
    __info "MainActivity not on top — relaunching"
    __adb shell am start -n "$PKG/.ui.activities.MainActivity" >/dev/null 2>&1 || true
    __ui_wait_for_quiet "Hosts" 15
}

# Wait for text without emitting a pass/fail line — used by recovery paths.
__ui_wait_for_quiet() {
    local text="$1" timeout="${2:-8}" i
    for ((i = 0; i < timeout; i++)); do
        __ui_dump
        __ui_texts | grep -qxF -- "$text" && return 0
        sleep 1
    done
    return 1
}

# True when the navigation drawer is on screen. A closed DrawerLayout child is
# still in the dumped hierarchy — it is translated off the left edge — so the
# test is on its bounds, not on its presence.
__ui_drawer_is_open() {
    __ui_dump
    python3 - "$UI_XML" <<'PY'
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(1)
for node in root.iter('node'):
    if node.get('resource-id', '').endswith(':id/tabssh_nav_view'):
        nums = [int(n) for n in node.get('bounds', '[0,0][0,0]')
                .replace('][', ',').strip('[]').split(',')]
        sys.exit(0 if nums[2] > 0 else 1)
sys.exit(1)
PY
}

# Open the navigation drawer from whatever screen is showing, always scrolled
# back to its first entry. The drawer keeps its scroll position between opens,
# so without the reset the top items are off-screen and a tap for one of them
# looks like a missing entry.
__ui_open_drawer() {
    __ui_screen_size
    local x=$((UI_SCREEN_W / 6))
    local y1=$((UI_SCREEN_H / 4))
    local y2=$((UI_SCREEN_H * 4 / 5))
    local i
    # Tapping the hamburger while the drawer is already open lands on a menu
    # row instead — the toolbar sits underneath the open drawer — which
    # navigates somewhere unexpected and loses the rest of the test.
    if ! __ui_drawer_is_open; then
        __ui_tap_desc "Open navigation drawer" || return 1
        # Wait for the slide-in to finish. Swipes sent mid-animation are
        # swallowed by the DrawerLayout, so the menu would stay wherever the
        # previous open left it.
        for i in 1 2 3 4 5 6 7 8; do
            __ui_drawer_is_open && break
            sleep 0.5
        done
        # Fall back to the edge-swipe gesture — a tap can be dropped while the
        # activity is still settling, and the swipe does not depend on the
        # toolbar icon being wired up yet.
        if ! __ui_drawer_is_open; then
            __adb shell input swipe 5 $((UI_SCREEN_H / 2)) $((UI_SCREEN_W / 2)) $((UI_SCREEN_H / 2)) 300
            sleep 1
        fi
        __ui_drawer_is_open || return 1
    fi
    for i in 1 2 3; do
        __adb shell input swipe "$x" "$y1" "$x" "$y2" 250
        sleep 0.6
    done
    __ui_dump
}

# Scroll the open drawer until `text` is visible. The generic scroll helpers
# swipe at the middle of the display, which on a tablet lands in the content
# area beside the drawer and moves the wrong list.
# __ui_drawer_scroll_to text [max_swipes=8]
__ui_drawer_scroll_to() {
    local text="$1" max="${2:-8}" i
    __ui_screen_size
    local x=$((UI_SCREEN_W / 6))
    local y1=$((UI_SCREEN_H * 4 / 5))
    local y2=$((UI_SCREEN_H / 4))
    for ((i = 0; i <= max; i++)); do
        __ui_dump
        __ui_texts | grep -qxF -- "$text" && return 0
        __adb shell input swipe "$x" "$y1" "$x" "$y2" 250
        sleep 0.4
    done
    return 1
}

# Assert a drawer entry exists, scrolling the drawer to reach it.
__ui_drawer_assert() {
    local text="$1"
    if __ui_drawer_scroll_to "$text"; then
        __pass "Drawer entry: \"$text\""
    else
        __fail "Drawer entry missing: \"$text\""
    fi
}

# Open the drawer, tap one destination, assert the screen it opens, then come
# back to the caller's screen.
# __ui_drawer_visit item expected_text
__ui_drawer_visit() {
    local item="$1" expect="$2" attempt arrived=0
    __info "Drawer → $item"
    # Three attempts, each verifying the destination rather than just the tap.
    # A drawer list that is still settling when the coordinates are read sends
    # the tap to a neighbouring row, which lands on the wrong screen — an
    # emulator timing property, not an app defect. Re-checking the destination
    # inside the loop self-heals that, while a genuinely broken entry still
    # fails loudly because no attempt ever reaches "$expect".
    for attempt in 1 2 3; do
        __ui_ensure_main
        __ui_open_drawer || { sleep 2; continue; }
        # Tap from the same dump the scroll ended on. Handing the label to
        # __ui_tap instead would let it "search" by swiping the content area
        # beside the drawer, which moves the wrong list and loses the entry.
        __ui_drawer_scroll_to "$item" || { sleep 2; continue; }
        local coords
        coords=$(__ui_find_xy "$item") || true
        [[ -n "$coords" ]] || { sleep 2; continue; }
        __adb shell input tap $coords
        sleep 1
        if __ui_wait_contains "$expect" 15; then
            arrived=1
            break
        fi
        __debug "Drawer → $item: landed somewhere else, retrying"
        __ui_press_back
        sleep 2
    done
    if [[ $arrived -eq 1 ]]; then
        __pass "Found: \"$expect\""
    else
        __fail "Drawer entry did not open \"$expect\": \"$item\""
        __info "Screen:"; __ui_texts | sed 's/^/    /'
    fi
    __ui_screenshot "drawer-${item// /-}"
    __ui_press_back
    sleep 1
}

# Open one Settings sub-screen, assert a heading that belongs only to it, then
# return to the Settings root.
# __ui_settings_screen category expected_text
__ui_settings_screen() {
    local category="$1" expect="$2"
    __info "Settings → $category"
    __ui_tap "$category" 8
    sleep 2
    __ui_dismiss_anr
    __ui_wait_for "$expect" 20
    __ui_screenshot "settings-${category// /-}"
    __ui_press_back
    sleep 1
    __ui_dismiss_anr
}

# ── assertions ────────────────────────────────────────────────────────────────

# Wait up to N seconds for a substring to appear, reporting only through the
# exit status. Callers that retry need to probe for a screen without each
# unsuccessful probe printing a failure line of its own.
__ui_wait_contains() {
    local text="$1" timeout="${2:-8}" waited=0
    while [[ $waited -lt $timeout ]]; do
        __ui_dismiss_anr
        __ui_texts | grep -qF -- "$text" && return 0
        sleep 1
        waited=$((waited+1))
    done
    return 1
}

# Wait up to N seconds for text to appear; assert it arrives.
__ui_wait_for() {
    local text="$1" timeout="${2:-8}" waited=0
    while [[ $waited -lt $timeout ]]; do
        __ui_dismiss_anr
        if __ui_texts | grep -qF -- "$text"; then
            __pass "Found: \"$text\""
            return 0
        fi
        sleep 1
        waited=$((waited+1))
    done
    __fail "Timed out after ${timeout}s waiting for \"$text\""
    __info "Screen:"; __ui_texts | sed 's/^/    /'
}

# Wait up to N seconds for text to disappear.
__ui_wait_gone() {
    local text="$1" timeout="${2:-8}" waited=0
    while [[ $waited -lt $timeout ]]; do
        __ui_dump
        if ! __ui_texts | grep -qF -- "$text"; then
            __pass "Gone: \"$text\""
            return 0
        fi
        sleep 1
        waited=$((waited+1))
    done
    __fail "Timed out after ${timeout}s — \"$text\" is still visible"
}

# Assert text is visible on the current screen (single dump).
__ui_assert_present() {
    local text="$1"
    __ui_dismiss_anr
    if __ui_texts | grep -qF -- "$text"; then
        __pass "Present: \"$text\""
    else
        __fail "Expected \"$text\" — not on screen"
        __info "Screen:"; __ui_texts | sed 's/^/    /'
    fi
}

# Scroll until text is visible, then assert it.  Use when the item may be
# below the current viewport — safer than __ui_assert_present for long screens.
__ui_assert_scroll() {
    local text="$1" max="${2:-6}"
    __ui_dismiss_anr
    if __ui_scroll_to "$text" "$max"; then
        __pass "Found (scrolled): \"$text\""
    else
        __fail "Not found after scrolling: \"$text\""
        __info "Screen:"; __ui_texts | sed 's/^/    /'
    fi
}

# Assert text is NOT visible on the current screen.
__ui_assert_absent() {
    local text="$1"
    __ui_dump
    if __ui_texts | grep -qF -- "$text"; then
        __fail "Unexpected \"$text\" — is on screen"
    else
        __pass "Absent: \"$text\""
    fi
}

# Assert a node matching text has attribute=value.
# e.g. __ui_assert_attr "Enable Debug Logging" "checked" "true"
__ui_assert_attr() {
    local text="$1" attr="$2" expected="$3"
    __ui_dump
    local actual
    actual=$(__ui_get_attr "$text" "$attr")
    if [[ "$actual" == "$expected" ]]; then
        __pass "\"$text\": $attr=$actual"
    else
        __fail "\"$text\": expected $attr=$expected, got $attr=${actual:-<not found>}"
    fi
}

# Assert text appears exactly N times on the current screen.
__ui_assert_count() {
    local text="$1" expected="$2"
    __ui_dump
    local actual
    actual=$(__ui_count_text "$text")
    if [[ "$actual" == "$expected" ]]; then
        __pass "Count of \"$text\": $actual (expected $expected)"
    else
        __fail "Count of \"$text\": got $actual, expected $expected"
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
        __info "Crash prefs injected"
    else
        __fail "Could not inject crash prefs (__adb push failed — emulator may not be rooted)"
    fi
    rm -f "$local_tmp"
}

# ── inline `run` executor ─────────────────────────────────────────────────────
# Parses the STEPS list and executes each step sequentially, with lookahead for
# the optional numeric/label arguments some steps accept.
# Called by the top-level argument loop when it sees "run".

# Steps whose value argument is mandatory. A missing value used to fall through
# to `"$1"` under `set -u` and abort the whole run with "$1: unbound variable".
__UI_VALUE_STEPS=" --activity --tap --long-tap --input --key --sleep --scroll-to --wait-for --wait-gone --present --absent --scroll-assert --swipe "

# Number of mandatory values the given step consumes.
__ui_step_arity() {
    case "$1" in
        --swipe-xy) echo 4 ;;
        --attr) echo 3 ;;
        --tap-xy|--long-tap-xy|--count) echo 2 ;;
        *) if [[ "$__UI_VALUE_STEPS" == *" $1 "* ]]; then echo 1; else echo 0; fi ;;
    esac
}

__run_inline() {
    local name="${1:-inline}"; shift
    echo ""
    echo -e "${BLUE}━━━ Run: $name ━━━${NC}"
    TEST_FAILS=0

    while [[ $# -gt 0 ]]; do
        local _arity
        _arity="$(__ui_step_arity "$1")"
        if [[ $_arity -gt 0 && $(($# - 1)) -lt $_arity ]]; then
            __fail "Step $1 requires $_arity value(s) — none given"
            shift
            continue
        fi
        case "$1" in
            --activity)
                shift; __ui_launch "$1" ;;
            --stop)
                __ui_stop ;;
            --inject-crash)
                __ui_inject_crash_prefs ;;
            --tap)
                shift
                local _tap_text="$1"
                local _tap_max=5
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _tap_max="$1"; fi
                __ui_tap "$_tap_text" "$_tap_max" || __fail "__ui_tap: \"$_tap_text\" not found" ;;
            --tap-xy)
                shift; local _tx="$1"; shift; local _ty="$1"
                __ui_tap_xy "$_tx" "$_ty" ;;
            --long-tap)
                shift; __ui_long_tap "$1" || __fail "__ui_long_tap: \"$1\" not found" ;;
            --long-tap-xy)
                shift; local _lx="$1"; shift; local _ly="$1"
                __ui_long_tap_xy "$_lx" "$_ly" ;;
            --swipe)
                shift; local _sdir="$1"
                local _sdist=800
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _sdist="$1"; fi
                __ui_swipe "$_sdir" "$_sdist" ;;
            --swipe-xy)
                shift; local _sx1="$1"; shift; local _sy1="$1"
                shift; local _sx2="$1"; shift; local _sy2="$1"
                local _sms=400
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _sms="$1"; fi
                __ui_swipe_xy "$_sx1" "$_sy1" "$_sx2" "$_sy2" "$_sms" ;;
            --scroll-to)
                shift; local _st_text="$1" _st_max=8
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _st_max="$1"; fi
                __ui_scroll_to "$_st_text" "$_st_max" || __fail "__ui_scroll_to: \"$_st_text\" not found" ;;
            --input)
                shift; __ui_input_text "$1" ;;
            --clear)
                __ui_clear_text ;;
            --back)
                __ui_press_back ;;
            --home)
                __ui_press_home ;;
            --enter)
                __ui_press_enter ;;
            --key)
                shift; __ui_key "$1" ;;
            --sleep)
                shift; sleep "$1" ;;
            --screenshot)
                local _label="screen"
                if [[ $# -gt 1 && "${2:-}" != --* ]]; then shift; _label="$1"; fi
                __ui_screenshot "$_label" ;;
            --wait-for)
                shift; local _wtext="$1"
                local _wt=8
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _wt="$1"; fi
                __ui_wait_for "$_wtext" "$_wt" ;;
            --wait-gone)
                shift; local _wgtext="$1"
                local _wgt=8
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _wgt="$1"; fi
                __ui_wait_gone "$_wgtext" "$_wgt" ;;
            --present)
                shift; __ui_assert_present "$1" ;;
            --absent)
                shift; __ui_assert_absent "$1" ;;
            --scroll-assert)
                shift; local _satext="$1"
                local _samax=6
                if [[ $# -gt 1 && "${2:-}" =~ ^[0-9]+$ ]]; then shift; _samax="$1"; fi
                __ui_assert_scroll "$_satext" "$_samax" ;;
            --attr)
                shift; local _atext="$1"; shift; local _aattr="$1"; shift; local _aval="$1"
                __ui_assert_attr "$_atext" "$_aattr" "$_aval" ;;
            --count)
                shift; local _ctext="$1"; shift; local _cn="$1"
                __ui_assert_count "$_ctext" "$_cn" ;;
            *)
                __warn "Unknown step: $1" ;;
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
    local fn="__test_${name//-/_}"
    if declare -F -- "$fn" >/dev/null; then
        "$fn" || true
    else
        __fail "test function $fn is not defined"
    fi
    if [[ $TEST_FAILS -eq 0 ]]; then
        PASS_COUNT=$((PASS_COUNT+1))
        echo -e "${GREEN}  PASS${NC}"
    else
        FAIL_COUNT=$((FAIL_COUNT+1))
        echo -e "${RED}  FAIL ($TEST_FAILS assertion(s) failed)${NC}"
    fi
}

__test_settings_opens() {
    __ui_stop
    __ui_launch "$PKG/.ui.activities.SettingsActivity"
    __ui_wait_for       "Settings"
    __ui_assert_present "Connection"
    __ui_assert_scroll  "Logging" 8
}

# ── test: main-tabs ──────────────────────────────────────────────────────────
# Taps every tab on the home screen and asserts that tab's content actually
# rendered. Regression guard for the tablet drawer bug: a drawer locked open
# swallows every touch in the content area, so the tab strip goes dead while
# the drawer's own items keep working — tapping a tab and asserting the
# resulting content is the only check that catches it.
__test_main_tabs() {
    __ui_stop
    __ui_launch "$PKG/.ui.activities.MainActivity"
    __ui_wait_for "Hosts" 20

    __ui_tap      "Frequent"
    __ui_wait_for "Frequently Used Connections" 10
    __ui_screenshot "tab-frequent"

    __ui_tap      "Hosts"
    __ui_wait_for "Connections" 10
    __ui_assert_present "Search connections..."
    __ui_screenshot "tab-hosts"

    __ui_tap      "Identities"
    __ui_wait_for "Host Identities" 10
    __ui_screenshot "tab-identities"

    __ui_tap      "Stats"
    __ui_wait_for "Performance Monitor" 10
    __ui_screenshot "tab-stats"

    __ui_tap      "Infra"
    __ui_wait_for "Containers" 10
    __ui_screenshot "tab-infra"

    # Infra's own sub-tabs — a second TabLayout nested in the tab content.
    __ui_tap      "Hypervisors"
    __ui_wait_for "Hypervisors" 10
    __ui_tap      "Cloud"
    __ui_wait_for "Cloud" 10
    __ui_tap      "Containers"
    __ui_wait_for "Containers" 10

    # Back to the default tab so a following test starts from a known screen.
    __ui_tap      "Hosts"
    __ui_wait_for "Connections" 10
    __ui_stop
}

# ── test: nav-drawer ─────────────────────────────────────────────────────────
# Opens the drawer from the home screen, asserts every entry is listed, then
# visits each navigational destination and confirms the screen it opens.
# Diagnostics entries that only copy to the clipboard are asserted present but
# not tapped — tapping them proves nothing on screen.
__test_nav_drawer() {
    __ui_stop
    __ui_launch "$PKG/.ui.activities.MainActivity"
    __ui_wait_for "Hosts" 20

    __ui_open_drawer
    __ui_wait_for "Quick Connect" 10
    __ui_screenshot "drawer-open"
    local item
    for item in "Quick Connect" "VNC Hosts" "Snippets" "Groups" \
                "Routing & Forwarding" "Cluster Commands" \
                "Multi-host Dashboard" "Connection History" "Settings" \
                "Copy App Log (Safe to Share)" "Copy Debug Logs (Developer)" \
                "What's New" "Help" "About"; do
        __ui_drawer_assert "$item"
    done

    # The drawer must close again — if it does not, every content tap below
    # would be swallowed and the failure would look like "nothing works".
    __ui_press_back
    sleep 1
    __ui_assert_absent "Multi-host Dashboard"

    __ui_drawer_visit "VNC Hosts"             "VNC Hosts"
    __ui_drawer_visit "Snippets"              "Snippets"
    __ui_drawer_visit "Groups"                "Groups"
    __ui_drawer_visit "Routing & Forwarding"  "Routing & Forwarding"
    __ui_drawer_visit "Cluster Commands"      "Cluster Commands"
    __ui_drawer_visit "Multi-host Dashboard"  "Dashboard"
    __ui_drawer_visit "Connection History"    "History"
    __ui_drawer_visit "Settings"              "Settings"
    __ui_drawer_visit "What's New"            "What's New"
    __ui_drawer_visit "Help"                  "Help"
    __ui_drawer_visit "About"                 "About"
    __ui_stop
}

# ── test: settings-screens ───────────────────────────────────────────────────
# Opens every category on the Settings root and asserts the sub-screen renders
# a preference of its own, then returns to the root.
__test_settings_screens() {
    __ui_stop
    __ui_launch "$PKG/.ui.activities.SettingsActivity"
    __ui_wait_for "Settings" 20

    __ui_settings_screen "General"      "Appearance"
    __ui_settings_screen "Terminal"     "Appearance"
    __ui_settings_screen "App Security" "App lock"
    __ui_settings_screen "Connection"   "Defaults for new connections"
    __ui_settings_screen "Monitoring"   "Status & background activity"
    __ui_settings_screen "Tasker"       "Tasker integration"
    __ui_settings_screen "Logging"      "Application log"
    __ui_settings_screen "Audit Log"    "Audit"
    __ui_stop
}

__test_hypervisor_form() {
    __ui_stop
    __ui_launch "$PKG/.ui.activities.HypervisorEditActivity"
    __ui_wait_for       "Host" 15
    __ui_assert_scroll  "Verify SSL Certificate" 8
    __ui_assert_absent  "Application Not Responding"
    __ui_stop
}

# ── test: logging-navigation ─────────────────────────────────────────────────
# Navigates Settings → Logging and scrolls through the entire screen, asserting
# every category heading and a representative preference from each one.
# Uses __ui_assert_scroll for every item so each one is scrolled into view before
# being checked — a single __ui_scroll_to on the header is not enough because the
# items lower in the section can still be off-screen.
# Also confirms "Test crash dialog" is visible (debug build only behaviour).
__test_logging_navigation() {
    __ui_stop
    __ui_launch "$PKG/.ui.activities.SettingsActivity"
    __ui_wait_for "Settings"
    __ui_tap      "Logging"
    # Logging preferences screen has ~25 items and can trigger an ANR on slow
    # emulators (SwiftShader GPU) while inflating the preference XML.
    # Allow 15 seconds for inflation before attempting any assertions.
    sleep 15
    __ui_dismiss_anr
    __ui_wait_for "Debug logging" 30

    # ── Debug logging (use wait_for, not assert_present, because inflation may
    # still be in progress on SwiftShader after the section header appears) ───
    __ui_wait_for "Enable Debug Logging" 15
    __ui_wait_for "Debug Log Level" 10
    __ui_wait_for "Log raw keystroke bytes (privacy risk)" 10
    # In the Debug Logging section near the top — assert before scrolling down.
    # visible only in devel builds
    __ui_assert_scroll "Test crash dialog"    12

    # ── Application log ───────────────────────────────────────────────────────
    # Use max=12 scrolls throughout — the preferences list is long and
    # SwiftShader renders slowly so each scroll iteration takes extra time.
    __ui_assert_scroll "Application log"      12
    __ui_assert_scroll "Always-on sanitized log" 12

    # ── Host logs ─────────────────────────────────────────────────────────────
    __ui_assert_scroll "Host logs"            12
    __ui_assert_scroll "Enable Host Logs"     12
    __ui_assert_scroll "One log file per connection" 12
    __ui_assert_scroll "Max Size per Host (MB)" 12

    # ── View logs ─────────────────────────────────────────────────────────────
    __ui_assert_scroll "View logs"            12
    __ui_assert_scroll "View Application Log" 12
    __ui_assert_scroll "View Debug Log"       12
    __ui_assert_scroll "View Host Logs"       12
    __ui_assert_scroll "View Audit Log"       12

    # ── Log management ────────────────────────────────────────────────────────
    __ui_assert_scroll "Log management"       12
    __ui_assert_scroll "Export Logs"          12
    __ui_assert_scroll "Clear Logs"           12

    # ── Issue reporting ───────────────────────────────────────────────────────
    __ui_assert_scroll "Issue reporting"      12
    __ui_assert_scroll "Paste Service"        12
    __ui_assert_scroll "MicroBin Server"      12
    __ui_assert_scroll "Lenpaste Server"      12
    __ui_assert_scroll "Stikked Server"       12
    __ui_assert_scroll "Pastebin API Key"     12

    __ui_stop
}

__test_crash_dialog() {
    __ui_stop
    __info "Injecting crash prefs…"
    __ui_inject_crash_prefs
    __ui_launch "$PKG/.ui.activities.CrashReportActivity"
    __ui_wait_for "Paste / Issue" 15
    __ui_assert_absent  "Share"
    __ui_wait_for "Copy"    10
    __ui_wait_for "Restart" 10
    __ui_stop
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
                fn_cand="__test_${next//-/_}"
                if declare -f "$fn_cand" >/dev/null 2>&1 || [[ "$next" == "all" ]]; then
                    break
                fi
            fi
            INLINE_STEPS+=("$next")
            i=$((i+1))
        done
        __run_inline "$INLINE_NAME" "${INLINE_STEPS[@]}"
    else
        fn="__test_${token//-/_}"
        if ! declare -f "$fn" >/dev/null 2>&1; then
            echo "❌ Unknown test: $token  (use --list)" >&2
            FAIL_COUNT=$((FAIL_COUNT+1))
        else
            __run_test "$token"
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
