#!/bin/bash

echo "=== Starting comprehensive error fix ==="

# Fix duplicate class declarations - remove duplicates from com.tabssh package
echo "Removing duplicate classes from com.tabssh package..."
rm -f app/src/main/java/com/tabssh/storage/database/dao/CertificateDao.kt
rm -f app/src/main/java/com/tabssh/storage/database/dao/TabSessionDao.kt
rm -f app/src/main/java/com/tabssh/storage/database/entities/TabSession.kt
rm -f app/src/main/java/com/tabssh/storage/database/entities/TrustedCertificate.kt
rm -f app/src/main/java/com/tabssh/ui/tabs/TabManager.kt
rm -f app/src/main/java/com/tabssh/ui/views/TerminalView.kt

# Fix SessionPersistenceManager length issues
echo "Fixing SessionPersistenceManager.kt..."
sed -i 's/\.length()/.length/g' app/src/main/java/com/tabssh/background/SessionPersistenceManager.kt

# Fix BackupExporter.kt property names
echo "Fixing BackupExporter.kt..."
sed -i 's/theme\.id/theme.themeId/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt
sed -i 's/theme\.themeData/theme.ansiColors ?: ""/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt
sed -i 's/theme\.isCustom/!theme.isBuiltIn/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt
sed -i 's/hostKey\.addedAt/hostKey.firstSeen/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt

# Fix TerminalEmulator charset issue
echo "Fixing TerminalEmulator.kt..."
sed -i 's/Charsets\.forName/Charset.forName/g' app/src/main/java/com/tabssh/terminal/emulator/TerminalEmulator.kt
sed -i '1a\import java.nio.charset.Charset' app/src/main/java/com/tabssh/terminal/emulator/TerminalEmulator.kt

# Fix KeyboardNavigationHelper property name
echo "Fixing KeyboardNavigationHelper.kt..."
sed -i 's/accessibilityTraversalAfter/importantForAccessibility/g' app/src/main/java/com/tabssh/accessibility/navigation/KeyboardNavigationHelper.kt

echo "=== Fix script completed ==="
echo "Now building the project..."