#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# scripts/dev-shell.sh — enter an interactive development container shell.
# Mounts the project source at /workspace with the build image.
# Use this for one-off Gradle commands outside of `make`.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

exec docker run --rm -it \
    -v "$ROOT:/workspace" \
    -w /workspace \
    -e "GRADLE_USER_HOME=/workspace/.gradle-home" \
    ghcr.io/tabssh/android:build \
    bash
