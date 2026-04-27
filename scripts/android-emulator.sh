#!/usr/bin/env bash
# scripts/android-emulator.sh — manage TabSSH test emulators.
#
# Usage:
#   scripts/android-emulator.sh                       # start phone (default)
#   scripts/android-emulator.sh phone                 # start phone (pixel_6)
#   scripts/android-emulator.sh phone small           # start small phone (pixel_5)
#   scripts/android-emulator.sh tablet                # start tablet (pixel_tablet)
#   scripts/android-emulator.sh tablet small          # 7" tablet (pixel_c)
#   scripts/android-emulator.sh fold                  # start foldable (pixel_fold)
#   scripts/android-emulator.sh tv                    # start Android TV
#   scripts/android-emulator.sh start <type> [size]   # explicit "start"
#   scripts/android-emulator.sh stop  [type]          # stop running (or one named)
#   scripts/android-emulator.sh delete <type>         # stop + delete AVD
#   scripts/android-emulator.sh clean                 # stop all + delete every TabSSH_* AVD
#   scripts/android-emulator.sh list                  # list TabSSH AVDs
#
# Idempotent: one AVD per (type, size). If `TabSSH_<type>[_<size>]` already
# exists it is reused — we never spam the user's AVD list.
#
# Env overrides:
#   ANDROID_HOME / ANDROID_SDK_ROOT — SDK root (else picks /opt/android, etc.)
#   ANDROID_AVD_HOME                — AVD home (default $HOME/.config/.android/avd)
#   API_LEVEL                       — Android API to install (default 34)
#   FORCE_RECREATE=1                — wipe & recreate the AVD on next start
#
# Permissions: the script chmods itself + the AVD directory to be user-owned
# (700 for AVD, 755 for the script). KVM (/dev/kvm) is NOT chowned — that's
# a host-level decision; the script just warns if it isn't usable.

set -euo pipefail

API_LEVEL="${API_LEVEL:-34}"
SDK=""
SDKMGR=""
AVDMGR=""

# ── locate SDK ────────────────────────────────────────────────────────────
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android /opt/android-sdk "$HOME/Android/Sdk"; do
  if [[ -n "$candidate" && -d "$candidate" ]]; then
    SDK="$candidate"; break
  fi
done

if [[ -z "${SDK:-}" ]]; then
  echo "❌ No Android SDK found. Set ANDROID_HOME or install to /opt/android."
  exit 1
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
mkdir -p "$ANDROID_AVD_HOME"
chmod 700 "$ANDROID_AVD_HOME" 2>/dev/null || true

PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK/tools/bin/sdkmanager" "$SDK/cmdline-tools/bin/sdkmanager"; do
  [[ -x "$c" ]] && SDKMGR="$c" && break
done
for c in "$SDK/cmdline-tools/latest/bin/avdmanager" "$SDK/tools/bin/avdmanager" "$SDK/cmdline-tools/bin/avdmanager"; do
  [[ -x "$c" ]] && AVDMGR="$c" && break
done

ADB="$SDK/platform-tools/adb"
EMU="$SDK/emulator/emulator"

# ── type → device mapping ─────────────────────────────────────────────────
# Hardware profile names are what `avdmanager list device -c` outputs. We
# pick stable, ships-with-SDK options.
device_for() {
  local type="$1" size="${2:-}"
  case "$type" in
    phone)
      case "$size" in
        small)  echo "pixel_5" ;;
        large|"") echo "pixel_6" ;;
        *) echo "pixel_6" ;;
      esac
      ;;
    tablet)
      case "$size" in
        small)  echo "pixel_c" ;;        # 10.2" — closest "small" tablet in stock SDK
        large|"") echo "pixel_tablet" ;; # 11"
        *) echo "pixel_tablet" ;;
      esac
      ;;
    fold|foldable) echo "pixel_fold" ;;
    tv)            echo "tv_1080p" ;;
    *) echo "$type" ;;  # raw passthrough — let avdmanager validate
  esac
}

# AVD name encodes the type + size so we can keep multiple coexisting.
avd_name_for() {
  local type="$1" size="${2:-}"
  if [[ -n "$size" ]]; then
    echo "TabSSH_${type}_${size}"
  else
    echo "TabSSH_${type}"
  fi
}

# ── helpers ───────────────────────────────────────────────────────────────
list_tabssh_avds() {
  "$AVDMGR" list avd 2>/dev/null | awk '/^    Name:/{print $2}' | grep -E "^TabSSH_" || true
}

ensure_sdk() {
  local missing=0
  [[ -x "$ADB" ]] || missing=1
  [[ -x "$EMU" ]] || missing=1
  [[ -d "$SDK/platforms/android-${API_LEVEL}" ]] || missing=1
  [[ -d "$SDK/system-images/android-${API_LEVEL}/google_apis/x86_64" ]] || missing=1
  if [[ $missing -eq 0 ]]; then return; fi
  if [[ -z "$SDKMGR" ]]; then
    echo "❌ sdkmanager not found in $SDK; install cmdline-tools first."
    exit 1
  fi
  echo "📦 Installing missing SDK components (this can take a while)…"
  yes | "$SDKMGR" --licenses >/dev/null 2>&1 || true
  "$SDKMGR" \
    "platform-tools" \
    "emulator" \
    "platforms;android-${API_LEVEL}" \
    "system-images;android-${API_LEVEL};google_apis;x86_64"
}

avd_exists() {
  local name="$1"
  "$AVDMGR" list avd 2>/dev/null | grep -qE "^    Name: ${name}\$"
}

create_avd() {
  local name="$1" device_id="$2"
  echo "🛠  Creating AVD $name (device=$device_id, api=$API_LEVEL)"
  echo no | "$AVDMGR" create avd \
    -n "$name" \
    -k "system-images;android-${API_LEVEL};google_apis;x86_64" \
    -d "$device_id" \
    --force >/dev/null
}

# Returns the ADB serial of a running emulator booted from `name`, or empty.
running_serial_for() {
  local target_name="$1"
  for serial in $("$ADB" devices 2>/dev/null | awk '/^emulator-/ {print $1}'); do
    local avd
    avd=$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    if [[ "$avd" == "$target_name" ]]; then
      echo "$serial"
      return
    fi
  done
}

start_avd() {
  local type="$1" size="${2:-}"
  ensure_sdk

  local device_id
  device_id="$(device_for "$type" "$size")"
  local name
  name="$(avd_name_for "$type" "$size")"

  if [[ "${FORCE_RECREATE:-0}" = "1" ]] && avd_exists "$name"; then
    "$AVDMGR" delete avd -n "$name" 2>/dev/null || true
  fi

  if ! avd_exists "$name"; then
    create_avd "$name" "$device_id"
  fi

  # Already running for this AVD? Done.
  local running
  running="$(running_serial_for "$name")"
  if [[ -n "$running" ]]; then
    echo "✅ Emulator '$name' already running on $running"
    "$ADB" devices
    return
  fi

  # A DIFFERENT TabSSH emulator already running? Stop it first — only one
  # at a time, per the "we don't want a whole bunch" rule.
  for serial in $("$ADB" devices 2>/dev/null | awk '/^emulator-/ {print $1}'); do
    local other
    other=$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    if [[ -n "$other" && "$other" != "$name" ]]; then
      echo "🛑 Stopping currently-running '$other' ($serial) before starting '$name'…"
      "$ADB" -s "$serial" emu kill 2>/dev/null || true
      sleep 2
    fi
  done

  # KVM hint — soft, the emulator will fall back to TCG if kvm is absent.
  if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
    echo "⚠  /dev/kvm not accessible — emulator will run in software mode (slower)."
  fi

  # Pick a free port pair to bind a known serial we can wait on. Without
  # `-port`, the emulator picks 5554 and adb auto-detects it; if there's a
  # zombie 5554 we'd attach to the wrong one. Forcing the port + waiting
  # against that specific serial avoids that.
  local emu_port=5554
  while ss -tnl 2>/dev/null | awk '{print $4}' | grep -q ":$emu_port\$"; do
    emu_port=$((emu_port + 2))
  done
  local serial="emulator-$emu_port"

  echo "🚀 Booting $name on $serial …"
  nohup "$EMU" \
    -avd "$name" \
    -port "$emu_port" \
    -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect \
    -accel auto \
    >"/tmp/${name}.log" 2>&1 &
  echo "  pid=$! log=/tmp/${name}.log"

  echo -n "⏳ Waiting for $serial "
  for _ in $(seq 1 60); do
    if "$ADB" -s "$serial" get-state 2>/dev/null | grep -q device; then break; fi
    echo -n "."; sleep 5
  done
  echo

  echo -n "⏳ Waiting for boot complete "
  for _ in $(seq 1 90); do
    if [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]]; then break; fi
    echo -n "."; sleep 3
  done
  echo

  if [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    echo "⚠  Emulator did not finish booting. See /tmp/${name}.log"
    return 1
  fi

  echo "✅ Emulator '$name' ready on $serial"
  "$ADB" devices
  echo
  echo "Install the latest debug APK with:"
  echo "  $ADB -s $serial install -r ${PWD}/binaries/tabssh-x86_64.apk"
}

stop_avd() {
  local type="${1:-}" size="${2:-}"
  if [[ -n "$type" ]]; then
    local name
    name="$(avd_name_for "$type" "$size")"
    local serial
    serial="$(running_serial_for "$name")"
    if [[ -z "$serial" ]]; then
      echo "ℹ  '$name' is not running."
      return
    fi
    "$ADB" -s "$serial" emu kill 2>/dev/null || true
    echo "🛑 Stopped $name ($serial)"
  else
    # Stop all emulators we can see.
    local stopped=0
    for serial in $("$ADB" devices 2>/dev/null | awk '/^emulator-/ {print $1}'); do
      "$ADB" -s "$serial" emu kill 2>/dev/null || true
      stopped=$((stopped+1))
      echo "🛑 Stopped $serial"
    done
    [[ $stopped -eq 0 ]] && echo "ℹ  No emulators running."
  fi
}

delete_avd() {
  local type="${1:?delete needs a type}" size="${2:-}"
  local name
  name="$(avd_name_for "$type" "$size")"
  stop_avd "$type" "$size"
  if avd_exists "$name"; then
    "$AVDMGR" delete avd -n "$name" >/dev/null 2>&1
    echo "🗑  Deleted AVD $name"
  else
    echo "ℹ  AVD $name doesn't exist."
  fi
}

clean_all() {
  stop_avd
  for name in $(list_tabssh_avds); do
    "$AVDMGR" delete avd -n "$name" >/dev/null 2>&1
    echo "🗑  Deleted $name"
  done
  echo "✅ Clean complete."
}

list_avds() {
  echo "TabSSH AVDs:"
  list_tabssh_avds | sed 's/^/  /'
  echo
  echo "Running emulators:"
  "$ADB" devices | awk '/^emulator-/' | sed 's/^/  /'
}

# ── arg dispatch ──────────────────────────────────────────────────────────
COMMAND="${1:-start}"

case "$COMMAND" in
  ""|start)
    # `start [type] [size]` or omitted (defaults to phone)
    shift || true
    start_avd "${1:-phone}" "${2:-}"
    ;;
  stop)
    shift
    stop_avd "${1:-}" "${2:-}"
    ;;
  delete|rm)
    shift
    delete_avd "${1:?type required}" "${2:-}"
    ;;
  clean)
    clean_all
    ;;
  list|ls)
    list_avds
    ;;
  -h|--help|help)
    sed -n '2,30p' "$0" | sed 's/^# *//'
    ;;
  *)
    # Implicit start: first arg is a type (e.g. `tablet`, `phone`, `fold`)
    start_avd "$COMMAND" "${2:-}"
    ;;
esac
