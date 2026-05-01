# test-sshd/Dockerfile — purpose-built sshd container for TabSSH beta testing.
#
# Bakes in:
#   - tabssh-test user (password: tabssh-test-pass)
#   - mosh-server (so /tmp/tabssh-android/sshd handoff testing works)
#   - a pre-generated ed25519 client keypair authorized for tabssh-test
#
# At container start the entrypoint copies the client key + writes
# credentials.txt to the bind-mounted /shared dir so the host test runner
# can reach them.

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        openssh-server mosh locales \
    && rm -rf /var/lib/apt/lists/* \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
    && locale-gen \
    && mkdir -p /run/sshd \
    && useradd -m -s /bin/bash tabssh-test \
    && echo 'tabssh-test:tabssh-test-pass' | chpasswd \
    && ssh-keygen -A \
    && echo 'AcceptEnv LANG LC_*' >> /etc/ssh/sshd_config

RUN ssh-keygen -t ed25519 -N '' -C 'tabssh-test@android' -f /root/test_id_ed25519 \
    && mkdir -p /home/tabssh-test/.ssh \
    && cp /root/test_id_ed25519.pub /home/tabssh-test/.ssh/authorized_keys \
    && chmod 700 /home/tabssh-test/.ssh \
    && chmod 600 /home/tabssh-test/.ssh/authorized_keys \
    && chown -R tabssh-test:tabssh-test /home/tabssh-test/.ssh

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

EXPOSE 2222/tcp
EXPOSE 60000-60010/udp

ENTRYPOINT ["/entrypoint.sh"]
