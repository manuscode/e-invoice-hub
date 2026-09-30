#!/bin/sh
# Creates the SSH keys of the local stack in docker/sftp/keys, if they don't exist yet. Runs in the service sftp-keys
# of docker-compose.yml before sftp and invoice-hub. The keys are for local use only and are not checked in.
set -eu

keys=/sftp/keys
mkdir -p "$keys"
cd "$keys"

if [ ! -f invoice-hub_ecdsa ]; then
    ssh-keygen -q -t ecdsa -b 256 -N '' -C 'invoice-hub, local only' -f invoice-hub_ecdsa
fi
# Only RSA, because the SSH client of invoice-hub (Apache MINA SSHD) can't read ed25519 keys without an additional
# provider.
if [ ! -f ssh_host_rsa_key ]; then
    ssh-keygen -q -t rsa -b 3072 -N '' -C 'sftp host key, local only' -f ssh_host_rsa_key
fi

# For clients on the host (spring-boot:run) and inside the compose network.
host_key=$(cut -d ' ' -f 1,2 ssh_host_rsa_key.pub)
printf '[localhost]:2222 %s\nsftp %s\n' "$host_key" "$host_key" > known_hosts

# Owned by the owner of docker/sftp instead of root, so the files can be deleted without sudo. The key of invoice-hub
# must be readable for the user of the invoice-hub container, which is a different one.
chown -R "$(stat -c '%u:%g' /sftp)" "$keys"
chmod 644 invoice-hub_ecdsa
