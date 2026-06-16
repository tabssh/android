#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# scripts/pre-commit-check.sh — run before committing.
# Delegates to `make check` which is the single source of truth for
# build validation. See Makefile for what it runs.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "🚦 TabSSH pre-commit check"
echo ""

make check
