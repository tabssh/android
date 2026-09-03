#!/usr/bin/env bash
##@Version 202608160001-git
# scripts/fetch-tor-binaries.sh — pull the latest tor binaries from the
# GitHub release `tor-<version>` and place them as native libs.
#
# Each ABI's binary is dropped as `app/src/main/jniLibs/<abi>/libtor.so`
# so Android's APK installer copies it to nativeLibraryDir on install
# (the `lib*.so` filename trick — it's not actually a shared object).
#
# Triggered by `make build` before the APK packaging step. If binaries
# already exist and `--force` is not passed, exits cleanly. If the GH
# release isn't reachable (no network in the build sandbox), warns but
# does not fail the build — the APK then ships without the built-in Tor
# route (TorNativeClient.isAvailable() gates it out at runtime; Orbot and
# external SOCKS routing still work).

set -euo pipefail

VERSION="202608160001-git"

REPO="${TABSSH_REPO:-tabssh/android}"
ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
JNI_ROOT="$(cd "${0%/*}/.." && pwd)/app/src/main/jniLibs"

FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true

# Skip if all binaries already exist (unless --force).
if ! $FORCE; then
    all_present=true
    for abi in "${ABIS[@]}"; do
        if [[ ! -f "$JNI_ROOT/$abi/libtor.so" ]]; then
            all_present=false
            break
        fi
    done
    if $all_present; then
        echo "✅ Tor binaries already present in jniLibs/ — skipping fetch (use --force to refresh)"
        exit 0
    fi
fi

# Find the latest `tor-X.Y.Z.W` release.
echo "🔍 Querying ${REPO} for latest tor-* release..."
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    tag=$(gh release list --repo "$REPO" --limit 50 \
            --json tagName --jq '.[].tagName' \
            | grep -E -- '^tor-[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$' \
            | head -n1 || true)
else
    tag=$(curl -fsSL "https://api.github.com/repos/${REPO}/releases?per_page=50" \
            | grep -oE -- '"tag_name":[[:space:]]*"tor-[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?"' \
            | head -n1 \
            | sed -E 's/.*"(tor-[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?)".*/\1/' || true)
fi

if [[ -z "$tag" ]]; then
    echo "⚠️  No tor-* release found on ${REPO}. APK will build without the built-in Tor route."
    echo "   The monthly workflow should publish one — re-run \`make build\` after that."
    exit 0
fi

echo "📦 Latest release: $tag"

# Download each ABI binary.
for abi in "${ABIS[@]}"; do
    asset="tor-${abi}"
    dest_dir="$JNI_ROOT/$abi"
    dest="$dest_dir/libtor.so"

    mkdir -p "$dest_dir"

    echo "  ↓ $asset → $dest"
    if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
        gh release download "$tag" --repo "$REPO" --pattern "$asset" \
            --output "$dest" --clobber
    else
        url="https://github.com/${REPO}/releases/download/${tag}/${asset}"
        curl -fsSL "$url" -o "$dest"
    fi
    chmod +x "$dest"
done

echo "✅ Fetched tor binaries from $tag for all 4 ABIs"
