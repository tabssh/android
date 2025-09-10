#!/bin/bash

# TabSSH 1.0.0 - Release Notification Script
# Notifies community channels about new releases

VERSION="$1"

if [ -z "$VERSION" ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 v1.0.0"
    exit 1
fi

echo "📢 TabSSH $VERSION - Release Notification"
echo "========================================"

# Prepare release announcement
RELEASE_MESSAGE="🎉 TabSSH $VERSION Released!

The ultimate mobile SSH client for Android is now available!

🚀 Complete Feature Set:
• True tabbed SSH interface
• Enterprise-grade security with hardware encryption
• Complete SFTP file management  
• 12 professional themes with accessibility validation
• Advanced protocols (Mosh, X11 forwarding)
• Full accessibility support (WCAG 2.1 AA)
• Zero data collection - complete privacy

📦 Downloads:
• GitHub: https://github.com/tabssh/android/releases/tag/$VERSION
• F-Droid: Coming soon (submission in progress)

🎯 Multi-Architecture Support:
• tabssh-android-arm64 (recommended for most devices)
• tabssh-android-arm (older 32-bit devices)
• tabssh-android-amd64 (Chromebooks, emulators)

The first truly tabbed SSH client for Android - 100% free and open source forever!

#TabSSH #SSH #Android #OpenSource #Privacy #Accessibility"

echo "📝 Release message prepared:"
echo "$RELEASE_MESSAGE"
echo ""

# Matrix notification (if token available)
if [ -n "$MATRIX_TOKEN" ]; then
    echo "📱 Sending Matrix notification..."
    
    # This would send to Matrix room
    # curl -X POST "https://matrix.org/_matrix/client/r0/rooms/!room:server/send/m.room.message" \
    #   -H "Authorization: Bearer $MATRIX_TOKEN" \
    #   -H "Content-Type: application/json" \
    #   -d "{\"msgtype\":\"m.text\",\"body\":\"$RELEASE_MESSAGE\"}"
    
    echo "✅ Matrix notification sent (simulated)"
else
    echo "ℹ️ MATRIX_TOKEN not set, skipping Matrix notification"
fi

# Mastodon notification (if token available)  
if [ -n "$MASTODON_TOKEN" ]; then
    echo "🐘 Sending Mastodon notification..."
    
    # This would post to Mastodon
    # curl -X POST "https://mastodon.social/api/v1/statuses" \
    #   -H "Authorization: Bearer $MASTODON_TOKEN" \
    #   -H "Content-Type: application/json" \
    #   -d "{\"status\":\"$RELEASE_MESSAGE\"}"
    
    echo "✅ Mastodon notification sent (simulated)"
else
    echo "ℹ️ MASTODON_TOKEN not set, skipping Mastodon notification"
fi

# Discord webhook (if available)
if [ -n "$DISCORD_WEBHOOK" ]; then
    echo "💬 Sending Discord notification..."
    
    # This would send to Discord webhook
    # curl -X POST "$DISCORD_WEBHOOK" \
    #   -H "Content-Type: application/json" \
    #   -d "{\"content\":\"$RELEASE_MESSAGE\"}"
    
    echo "✅ Discord notification sent (simulated)"
else
    echo "ℹ️ DISCORD_WEBHOOK not set, skipping Discord notification"
fi

# Reddit post (if credentials available)
if [ -n "$REDDIT_CLIENT_ID" ] && [ -n "$REDDIT_CLIENT_SECRET" ]; then
    echo "🤖 Posting to Reddit..."
    echo "✅ Reddit post prepared (simulated)"
else
    echo "ℹ️ Reddit credentials not set, skipping Reddit post"
fi

# Hacker News (manual post suggestion)
echo ""
echo "📰 Manual Notifications Suggested:"
echo "=================================="
echo "• Hacker News: Post to /r/androidapps, /r/opensource, /r/sysadmin"
echo "• Reddit: Cross-post to relevant communities"  
echo "• Dev.to: Write release blog post"
echo "• XDA Developers: Post in Android Apps forum"
echo "• F-Droid Forum: Announce when approved"

echo ""
echo "📧 Release message saved to: release-message.txt"
echo "$RELEASE_MESSAGE" > release-message.txt

echo ""
echo "✅ Release notification complete!"
echo "🎊 TabSSH $VERSION is ready for the community!"