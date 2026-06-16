#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# scripts/notify-release.sh — post a release announcement to community channels.
# Sends to any channel whose token/webhook env var is set; skips the rest silently.
#
# Usage:  scripts/notify-release.sh <version>
# Env:    MATRIX_TOKEN, MASTODON_TOKEN, DISCORD_WEBHOOK

set -euo pipefail

VERSION="${1:?Usage: $0 <version>  (e.g. v1.2.0)}"
TMPDIR="${TMPDIR:-/tmp}"
OUTFILE="$TMPDIR/tabssh-android/release-message.txt"
mkdir -p "$(dirname "$OUTFILE")"

read -r -d '' MESSAGE << EOF || true
🎉 TabSSH $VERSION Released!

Tabbed SSH client for Android — free, open source, zero telemetry.

📦 Downloads:  https://github.com/tabssh/android/releases/tag/$VERSION
📋 Changelog:  https://github.com/tabssh/android/blob/main/CHANGELOG.md

#TabSSH #SSH #Android #OpenSource
EOF

echo "📝 Release message:"
echo "$MESSAGE"
echo ""
echo "$MESSAGE" > "$OUTFILE"
echo "   (saved to $OUTFILE)"
echo ""

if [[ -n "${MATRIX_TOKEN:-}" ]]; then
    echo "📱 Sending Matrix notification..."
    # curl -q -LSsf -X POST "https://matrix.org/_matrix/client/r0/rooms/!room:server/send/m.room.message" \
    #   -H "Authorization: Bearer $MATRIX_TOKEN" \
    #   -H "Content-Type: application/json" \
    #   -d "{\"msgtype\":\"m.text\",\"body\":$(printf '%s' "$MESSAGE" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')}"
    echo "  (MATRIX_TOKEN set — uncomment the curl call above to enable)"
fi

if [[ -n "${MASTODON_TOKEN:-}" ]]; then
    echo "🐘 Sending Mastodon notification..."
    # curl -q -LSsf -X POST "https://mastodon.social/api/v1/statuses" \
    #   -H "Authorization: Bearer $MASTODON_TOKEN" \
    #   --data-urlencode "status=$MESSAGE"
    echo "  (MASTODON_TOKEN set — uncomment the curl call above to enable)"
fi

if [[ -n "${DISCORD_WEBHOOK:-}" ]]; then
    echo "💬 Sending Discord notification..."
    PAYLOAD="$(printf '%s' "$MESSAGE" | python3 -c 'import json,sys; print(json.dumps({"content": sys.stdin.read()}))')"
    curl -q -LSsf -X POST "$DISCORD_WEBHOOK" \
        -H "Content-Type: application/json" \
        -d "$PAYLOAD"
    echo "  ✅ Discord notification sent"
fi

echo ""
echo "✅ Done — $VERSION announcement prepared."
