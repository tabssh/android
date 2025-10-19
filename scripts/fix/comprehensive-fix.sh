#!/bin/bash

# Comprehensive error fix script
echo "Applying comprehensive fixes..."

# Fix 1: TabSSHDatabase - deleteOldInactiveSessions (line 104)
echo "Fixing TabSSHDatabase..."
sed -i '104s/deleteOldInactiveSessions()/deleteInactiveSessions(System.currentTimeMillis() - 24 * 60 * 60 * 1000)/' \
    app/src/main/java/com/tabssh/storage/database/TabSSHDatabase.kt

# Fix 2: SSHConnection suspension function errors
echo "Fixing SSHConnection..."
# Add suspend keyword to methods that call suspend functions

# Fix 3: PortForwardingManager type mismatch (line 173)
echo "Fixing PortForwardingManager..."
sed -i '173s/localPort/localPort.toString()/' \
    app/src/main/java/com/tabssh/ssh/forwarding/PortForwardingManager.kt

echo "Fixes applied. Run compilation to check progress."
