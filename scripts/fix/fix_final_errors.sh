#!/bin/bash

echo "Fixing final compilation errors for TabSSH 1.0.0..."

# Fix AccessibilityManager Bundle import
sed -i '1a import android.os.Bundle' app/src/main/java/com/tabssh/accessibility/AccessibilityManager.kt

# Fix KeyboardNavigationHelper
sed -i 's/accessibilityLabel/contentDescription/g' app/src/main/java/com/tabssh/accessibility/navigation/KeyboardNavigationHelper.kt

# Fix SessionPersistenceManager
sed -i 's/.size/.length()/g' app/src/main/java/com/tabssh/background/SessionPersistenceManager.kt

# Fix BackupExporter Flow issues
sed -i 's/connections.toList()/connections/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt
sed -i 's/keys.toList()/keys/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt  
sed -i 's/themes.toList()/themes/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt
sed -i 's/certificates.toList()/certificates/g' app/src/main/java/com/tabssh/backup/export/BackupExporter.kt

# Fix BackupImporter dateAdded field
sed -i 's/dateAdded =/addedAt =/g' app/src/main/java/com/tabssh/backup/import/BackupImporter.kt

# Fix KeyStorage Flow issues
sed -i 's/keyDao.getAllKeys()/keyDao.getAllKeysLiveData().value ?: emptyList()/g' app/src/main/java/com/tabssh/crypto/keys/KeyStorage.kt

# Fix SecurePasswordManager imports
sed -i '1a import kotlinx.coroutines.CoroutineScope\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch' app/src/main/java/com/tabssh/crypto/storage/SecurePasswordManager.kt

echo "Fixes applied!"
