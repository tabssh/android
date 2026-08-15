#!/usr/bin/env bash
##@Version 202608150001-git
# scripts/notify-release.sh — post a release announcement to community channels.
# Sends to any channel whose token/webhook env var is set; skips the rest silently.
#
# Usage:  scripts/notify-release.sh <version>
# Env:    MATRIX_TOKEN + MATRIX_ROOM (+ optional MATRIX_HOMESERVER),
#         MASTODON_TOKEN (+ optional MASTODON_HOST), DISCORD_WEBHOOK

set -euo pipefail

VERSION="202608150001-git"
RELEASE_VERSION="${1:?Usage: $0 <version>  (e.g. v1.2.0)}"
# mktemp -d rather than a fixed, predictable path another user could
# pre-create as a symlink.
OUTDIR="$(mktemp -d "${TMPDIR:-/tmp}/tabssh-release-XXXXXX")"
OUTFILE="$OUTDIR/release-message.txt"

read -r -d '' MESSAGE << EOF || true
🎉 TabSSH $RELEASE_VERSION Released!

Tabbed SSH client for Android — free, open source, zero telemetry.

📦 Downloads:  https://github.com/tabssh/android/releases/tag/$RELEASE_VERSION
📋 Changelog:  https://github.com/tabssh/android/blob/main/CHANGELOG.md

#TabSSH #SSH #Android #OpenSource
EOF

echo "📝 Release message:"
echo "$MESSAGE"
echo ""
echo "$MESSAGE" > "$OUTFILE"
echo "   (saved to $OUTFILE)"
echo ""

# Matrix needs a room in addition to the token; without MATRIX_ROOM there is
# nowhere to post, so say that instead of silently doing nothing.
if [[ -n "${MATRIX_TOKEN:-}" ]]; then
    echo "📱 Sending Matrix notification..."
    if [[ -z "${MATRIX_ROOM:-}" ]]; then
        echo "  ⚠️  MATRIX_ROOM not set — skipping Matrix notification"
    else
        MATRIX_HOMESERVER="${MATRIX_HOMESERVER:-https://matrix.org}"
        MATRIX_PAYLOAD="$(printf '%s' "$MESSAGE" |
            python3 -c 'import json,sys; print(json.dumps({"msgtype": "m.text", "body": sys.stdin.read()}))')"
        curl -LSsf -X POST \
            "${MATRIX_HOMESERVER}/_matrix/client/v3/rooms/${MATRIX_ROOM}/send/m.room.message" \
            -H "Authorization: Bearer $MATRIX_TOKEN" \
            -H "Content-Type: application/json" \
            -d "$MATRIX_PAYLOAD"
        echo "  ✅ Matrix notification sent"
    fi
fi

if [[ -n "${MASTODON_TOKEN:-}" ]]; then
    echo "🐘 Sending Mastodon notification..."
    MASTODON_HOST="${MASTODON_HOST:-https://mastodon.social}"
    curl -LSsf -X POST "${MASTODON_HOST}/api/v1/statuses" \
        -H "Authorization: Bearer $MASTODON_TOKEN" \
        --data-urlencode "status=$MESSAGE"
    echo "  ✅ Mastodon notification sent"
fi

if [[ -n "${DISCORD_WEBHOOK:-}" ]]; then
    echo "💬 Sending Discord notification..."
    PAYLOAD="$(printf '%s' "$MESSAGE" | python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read()}))')"
    curl -LSsf -X POST "$DISCORD_WEBHOOK" \
        -H "Content-Type: application/json" \
        -d "$PAYLOAD"
    echo "  ✅ Discord notification sent"
fi

echo ""
echo "✅ Done — $RELEASE_VERSION announcement prepared."
