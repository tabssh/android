#!/usr/bin/env bash
# scripts/generate-keystore.sh — create a release signing keystore.
# Prompts for a password rather than hardcoding one.
#
# Usage:  scripts/generate-keystore.sh [output.jks]
# Output: keystore.jks (default) or the path you specify

set -euo pipefail

OUT="${1:-keystore.jks}"

if [[ -f "$OUT" ]]; then
    echo "⚠  $OUT already exists — delete it first if you want to regenerate."
    exit 1
fi

echo "🔑 Generating TabSSH release keystore: $OUT"
echo "   You will be prompted for a keystore password."
echo ""

keytool -genkey -v \
  -keystore "$OUT" \
  -alias tabssh \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=TabSSH, OU=Development, O=TabSSH, L=Unknown, S=Unknown, C=US"

echo ""
echo "✅ Keystore written to $OUT"
echo "   Store the password in a secrets manager — never commit it."
