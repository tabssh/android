#!/usr/bin/env bash
##@Version 202608150001-git
# scripts/pre-commit-check.sh — run before committing.
# Delegates to `make check` which is the single source of truth for
# build validation. See Makefile for what it runs.

set -euo pipefail

VERSION="202608150001-git"

ROOT="$(cd "${0%/*}/.." && pwd)"
cd "$ROOT"

echo "🚦 TabSSH pre-commit check"
echo ""

make check
