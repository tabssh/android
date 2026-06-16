#!/usr/bin/env bash
##@Version YYYYMMDDHHMM-git
# Idempotent launcher for the TabSSH beta-test sshd container.
#
# Builds (or rebuilds, if the Dockerfile changed) the test-sshd image and
# (re)starts a single container bound to 127.0.0.1:2222. Drops a fresh
# credentials.txt + ed25519 keypair into /tmp/tabssh-android/sshd/ so the
# AVD test runner can authenticate via password OR pubkey.
#
# Replaces the older `tabssh-mosh-test` container if it's still around —
# port 2222 conflict is resolved by stopping it.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SHARED="/tmp/tabssh-android/sshd"
NAME="tabssh-test-sshd"
IMAGE="tabssh/test-sshd:latest"

mkdir -p "$SHARED"

echo "==> Building $IMAGE"
docker build -t "$IMAGE" "$ROOT/docker/test-sshd"

for stale in tabssh-mosh-test "$NAME"; do
    if docker ps -a --format '{{.Names}}' | grep -qx -- "$stale"; then
        echo "==> Removing existing container: $stale"
        docker rm -f "$stale" >/dev/null
    fi
done

echo "==> Starting $NAME"
docker run -d --name "$NAME" \
    -p 127.0.0.1:2222:2222 \
    -p 127.0.0.1:60000-60010:60000-60010/udp \
    -v "$SHARED:/shared" \
    "$IMAGE" >/dev/null

for _ in $(seq 1 20); do
    [ -f "$SHARED/credentials.txt" ] && break
    sleep 0.2
done

if [ ! -f "$SHARED/credentials.txt" ]; then
    echo "ERROR: credentials.txt was not written. Container logs:" >&2
    docker logs "$NAME" >&2
    exit 1
fi

echo "==> Test sshd ready on 127.0.0.1:2222"
echo
cat "$SHARED/credentials.txt"
