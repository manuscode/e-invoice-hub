#!/bin/sh
# Runs in the sftp container before sshd starts (see /etc/sftp.d in atmoz/sftp) and installs the keys from
# generate-keys.sh. Copied instead of mounted, because sshd needs them with owner and mode it accepts.
set -eu

install -m 600 /keys/ssh_host_rsa_key /etc/ssh/ssh_host_rsa_key
install -m 644 /keys/ssh_host_rsa_key.pub /etc/ssh/ssh_host_rsa_key.pub
install -d -o invoices -m 700 /home/invoices/.ssh
install -o invoices -m 600 /keys/invoice-hub_ecdsa.pub /home/invoices/.ssh/authorized_keys
