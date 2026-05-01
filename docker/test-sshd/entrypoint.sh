#!/usr/bin/env bash
set -e

if [ -d /shared ]; then
    cp -f /root/test_id_ed25519     /shared/test_id_ed25519
    cp -f /root/test_id_ed25519.pub /shared/test_id_ed25519.pub
    chmod 600 /shared/test_id_ed25519
    cat > /shared/credentials.txt <<EOF
host=127.0.0.1
port=2222
user=tabssh-test
password=tabssh-test-pass
private_key=/shared/test_id_ed25519
public_key=/shared/test_id_ed25519.pub
EOF
fi

exec /usr/sbin/sshd -D -e -p 2222
