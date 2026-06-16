#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# scripts/prepare-fdroid-submission.sh — bundle F-Droid submission artefacts.
# Writes output to /tmp/tabssh-android/fdroid-submission/ (never the repo tree).
#
# Usage:  scripts/prepare-fdroid-submission.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMPDIR="${TMPDIR:-/tmp}"
OUT="$TMPDIR/tabssh-android/fdroid-submission"

rm -rf "$OUT"
mkdir -p "$OUT"

echo "📦 Preparing F-Droid submission artefacts → $OUT"

# Required metadata file
cp "$ROOT/metadata/io.github.tabssh.yml" "$OUT/"

# Docs
for f in README.md CHANGELOG.md LICENSE.md; do
    [[ -f "$ROOT/$f" ]] && cp "$ROOT/$f" "$OUT/" || echo "⚠  $f not found — skipping"
done

# Generate RFP stub
VERSION="$(grep -- versionName "$ROOT/app/build.gradle" | head -1 | sed 's/.*"\(.*\)".*/\1/')"

cat > "$OUT/RFP_SUBMISSION.md" << EOF
# F-Droid Request for Packaging (RFP)

**App Name**: TabSSH
**Package Name**: io.github.tabssh
**Version**: $VERSION
**Source Code**: https://github.com/tabssh/android
**License**: MIT
**Categories**: System, Internet, Security

## Description

Tabbed SSH client for Android. Features: multi-session tabs, JSch-based SSH,
SFTP, port forwarding, SSH key management (RSA/ECDSA/Ed25519), 23 built-in
themes, biometric unlock, zero telemetry.

## F-Droid Compliance

- No proprietary dependencies (JSch BSD, AndroidX/Material Apache-2.0)
- No analytics, tracking, or ads
- Reproducible Gradle build
- MIT licensed
- All data stored locally on device

## Submission

1. Open https://gitlab.com/fdroid/rfp/-/issues/new
2. Paste the content of this file
3. Attach metadata/io.github.tabssh.yml
EOF

echo ""
echo "✅ Submission artefacts ready in $OUT"
ls -1 "$OUT/"
echo ""
echo "Next: open https://gitlab.com/fdroid/rfp/-/issues/new and paste RFP_SUBMISSION.md"
