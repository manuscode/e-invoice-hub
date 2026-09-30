#!/usr/bin/env bash
# Loads the invoices of samples/ into the running stack: samples/rest via REST, samples/mail as mail to the
# mailbox of the hub and samples/sftp into the SFTP inbox. Start the stack first:
#   docker compose up --build --detach --wait
# The script can run more than once, the hub recognizes documents it received before.
set -euo pipefail

cd "$(dirname "$0")/.."

readonly hub_url=${HUB_URL:-http://localhost:8080}
readonly token_url=${TOKEN_URL:-http://localhost:8180/realms/invoice-hub/protocol/openid-connect/token}
readonly smtp_url=${SMTP_URL:-smtp://localhost:3025}
readonly mailbox=invoices@invoice-hub.local
readonly supplier=buchhaltung@lieferant.example

require() {
    if ! command -v "$1" > /dev/null; then
        echo "$1 is required, please install it first" >&2
        exit 1
    fi
}

access_token() {
    local response
    # Keycloak has no health check in docker-compose.yml and may still import the realm after "up --wait".
    if ! response=$(curl -fs --retry 30 --retry-delay 2 --retry-all-errors "$token_url" \
        -d grant_type=client_credentials -d client_id=demo-uploader -d client_secret=demo-uploader-secret); then
        echo "No access token from $token_url, is the stack running?" >&2
        exit 1
    fi
    jq -r .access_token <<< "$response"
}

content_type() {
    case "$1" in
        *.pdf) echo application/pdf ;;
        *) echo application/xml ;;
    esac
}

upload() {
    local file=$1
    local token=$2
    local response http_status body
    # The HTTP status is appended as last line, so status and body come from one call.
    response=$(curl -sS -w '\n%{http_code}' "$hub_url/api/invoices" \
        -H "Authorization: Bearer $token" \
        -F "file=@$file;type=$(content_type "$file")")
    http_status=${response##*$'\n'}
    body=${response%$'\n'*}
    if [[ "$http_status" != 200 && "$http_status" != 201 ]]; then
        echo "Upload of $file failed with $http_status: $body" >&2
        exit 1
    fi
    printf '%-48s %s  %s\n' "$(basename "$file")" "$http_status" \
        "$(jq -r '[.status, .rejectionReason // empty, (.duplicateOf // empty | "of " + .), .id] | join("  ")' <<< "$body")"
}

send_mail() {
    local file=$1
    curl -fsS "$smtp_url" --mail-from "$supplier" --mail-rcpt "$mailbox" \
        -H "From: $supplier" -H "To: $mailbox" -H "Subject: Rechnung $(basename "$file")" \
        -F "=Anbei unsere Rechnung.;type=text/plain" \
        -F "=@$file;type=$(content_type "$file");encoder=base64"
    echo "$(basename "$file") sent to $mailbox"
}

# Copied into the container, because ssh refuses the private key in docker/sftp/keys: it is readable for everyone, so
# that the invoice-hub container can read it.
put_into_sftp_inbox() {
    local file=$1
    docker compose cp "$file" sftp:/home/invoices/inbox/
}

require curl
require jq
require docker

echo "REST"
token=$(access_token)
for file in samples/rest/*; do
    upload "$file" "$token"
done
echo "Same document again"
upload samples/rest/01-xrechnung-ubl-valid.xml "$token"

echo
echo "Mail"
for file in samples/mail/*; do
    send_mail "$file"
done

echo
echo "SFTP"
for file in samples/sftp/*; do
    put_into_sftp_inbox "$file"
done

cat <<EOF

Mail and SFTP are polled every 30 seconds, delivery to the ERP runs asynchronously with retries.
  Grafana dashboard: http://localhost:3000/d/invoice-hub
  ERP simulator:     http://localhost:8081/erp/invoices
EOF
