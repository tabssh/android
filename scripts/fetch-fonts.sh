#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# scripts/fetch-fonts.sh — pull the 9 Nerd Fonts the dropdown advertises
# (see app/src/main/res/values/arrays.xml `terminal_font_*`) and place
# them as `app/src/main/assets/fonts/<NerdFontName>-Regular.ttf`.
#
# Same idiom as `fetch-mosh-binaries.sh`:
#   * skip-if-present (re-run with `--force` to refresh)
#   * fail-soft on no network — APK still builds, FontManager falls back
#     to system monospace
#   * triggered by `make build` before the APK packaging step
#
# Why this lives outside git: the 9 .ttf files total ~24 MB. TTFs compress
# poorly under the git pack format, so committing them bloats the repo
# without much win. This script fetches once into a workspace location
# that's gitignored.

set -euo pipefail

ASSETS="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/fonts"

# Pairs are: <upstream zip basename>:<filename inside the zip we want>
# The "filename in the zip" sometimes differs from the FontManager-mapped
# output name (Cascadia → Caskaydia, SourceCodePro → SauceCodePro), so we
# also keep the desired output filename in column 3.
PAIRS=(
    "JetBrainsMono:JetBrainsMonoNerdFont-Regular.ttf:JetBrainsMonoNerdFont-Regular.ttf"
    "FiraCode:FiraCodeNerdFont-Regular.ttf:FiraCodeNerdFont-Regular.ttf"
    "Hack:HackNerdFont-Regular.ttf:HackNerdFont-Regular.ttf"
    "CascadiaCode:CaskaydiaCoveNerdFont-Regular.ttf:CascadiaCodeNerdFont-Regular.ttf"
    "SourceCodePro:SauceCodeProNerdFont-Regular.ttf:SourceCodeProNerdFont-Regular.ttf"
    "Meslo:MesloLGSNerdFont-Regular.ttf:MesloLGSNerdFont-Regular.ttf"
    "RobotoMono:RobotoMonoNerdFont-Regular.ttf:RobotoMonoNerdFont-Regular.ttf"
    "UbuntuMono:UbuntuMonoNerdFont-Regular.ttf:UbuntuMonoNerdFont-Regular.ttf"
    "DejaVuSansMono:DejaVuSansMNerdFont-Regular.ttf:DejaVuSansMNerdFont-Regular.ttf"
)

FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

mkdir -p "$ASSETS"

# Skip if all output files already exist (unless --force).
if ! $FORCE; then
    all_present=true
    for pair in "${PAIRS[@]}"; do
        out_name="${pair##*:}"
        if [[ ! -s "$ASSETS/$out_name" ]]; then
            all_present=false
            break
        fi
    done
    if $all_present; then
        echo "✅ Nerd Fonts already present in assets/fonts/ — skipping fetch (use --force to refresh)"
        exit 0
    fi
fi

# unzip is required — fail loudly if missing rather than silently producing
# zero-byte .ttfs.
if ! command -v unzip >/dev/null 2>&1; then
    echo "✗ unzip is required to extract Nerd Font ZIPs — please install it"
    exit 1
fi

UPSTREAM="https://github.com/ryanoasis/nerd-fonts/releases/latest/download"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

ok=0
fail=0
for pair in "${PAIRS[@]}"; do
    zip_base="${pair%%:*}"
    rest="${pair#*:}"
    in_zip_name="${rest%%:*}"
    out_name="${rest##*:}"

    out_path="$ASSETS/$out_name"
    if [[ -s "$out_path" ]] && ! $FORCE; then
        continue
    fi

    zip_path="$TMP/${zip_base}.zip"
    echo "  ↓ ${zip_base}.zip"
    if ! curl -fsSL "$UPSTREAM/${zip_base}.zip" -o "$zip_path"; then
        echo "  ⚠ ${zip_base}: download failed (offline?). Skipping."
        fail=$((fail + 1))
        continue
    fi

    if ! unzip -p "$zip_path" "$in_zip_name" > "$out_path" 2>/dev/null \
        || [[ ! -s "$out_path" ]]; then
        echo "  ⚠ ${zip_base}: ${in_zip_name} not found in zip. Skipping."
        rm -f "$out_path"
        fail=$((fail + 1))
        continue
    fi

    ok=$((ok + 1))
done

if (( ok > 0 )); then
    echo "✅ Fetched $ok Nerd Font(s) into assets/fonts/"
fi
if (( fail > 0 )); then
    echo "⚠ $fail font(s) failed — APK will fall back to system monospace for those entries"
fi

# Always exit 0 so a transient network glitch doesn't fail the build.
exit 0
