#!/usr/bin/env bash
##@Version 202608150001-git
# scripts/fetch-spice-libs.sh — pull the cross-compiled SPICE native library
# (libtabssh_native.so) from the latest GitHub release `spice-libs-<version>`
# and place one per ABI under app/src/main/jniLibs/.
#
# Android's APK installer copies every app/src/main/jniLibs/<abi>/*.so into
# the app's nativeLibraryDir on install; SpiceLoader.System.loadLibrary
# then finds "tabssh_native" there. When the release is unreachable (no
# network, or none published yet) the fetch warns but does not fail — the
# APK simply ships without SPICE and the SpiceLoader gate falls back to VNC
# at runtime, exactly like scripts/fetch-mosh-binaries.sh.
#
# Triggered by `make build` before APK packaging.

set -euo pipefail

VERSION="202608150001-git"

REPO="${TABSSH_REPO:-tabssh/android}"
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
JNI_ROOT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs"

FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

# Skip if all libraries already exist (unless --force).
if ! $FORCE; then
    all_present=true
    for abi in "${ABIS[@]}"; do
        if [[ ! -f "$JNI_ROOT/$abi/libtabssh_native.so" ]]; then
            all_present=false
            break
        fi
    done
    if $all_present; then
        echo "✅ SPICE native libs already present in jniLibs/ — skipping fetch (use --force to refresh)"
        exit 0
    fi
fi

# Find the latest `spice-libs-X.Y.Z` release.
echo "🔍 Querying ${REPO} for latest spice-libs-* release..."
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    tag=$(gh release list --repo "$REPO" --limit 50 \
            --json tagName --jq '.[].tagName' \
            | grep -E -- '^spice-libs-[0-9]+\.[0-9]+\.[0-9]+$' \
            | head -n1 || true)
else
    tag=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases?per_page=50" \
            | grep -oE -- '"tag_name":[[:space:]]*"spice-libs-[0-9]+\.[0-9]+\.[0-9]+"' \
            | head -n1 \
            | sed -E 's/.*"(spice-libs-[0-9]+\.[0-9]+\.[0-9]+)".*/\1/' || true)
fi

if [[ -z "$tag" ]]; then
    echo "⚠️  No spice-libs-* release found on ${REPO}. APK will build without SPICE support."
    echo "   Run the 'SPICE Native Libraries' workflow to publish one, then re-run \`make build\`."
    exit 0
fi

echo "📦 Latest release: $tag"

# Download each ABI library.
for abi in "${ABIS[@]}"; do
    asset="libtabssh_native-${abi}.so"
    dest_dir="$JNI_ROOT/$abi"
    dest="$dest_dir/libtabssh_native.so"

    mkdir -p "$dest_dir"

    echo "  ↓ $asset → $dest"
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        gh release download "$tag" --repo "$REPO" --pattern "$asset" \
            --output "$dest" --clobber
    else
        url="https://github.com/${REPO}/releases/download/${tag}/${asset}"
        curl -fsSL "$url" -o "$dest"
    fi
done

echo "✅ Fetched SPICE native libs from $tag for all 4 ABIs"
