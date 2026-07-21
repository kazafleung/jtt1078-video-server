#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

SSH_HOST="${SSH_HOST:-152.69.210.81}"
SSH_USER="${SSH_USER:-opc}"
SSH_KEY="${SSH_KEY:-/Users/zensis/taxieco/oracle/ssh-key-152.69.210.81.key}"
REMOTE_PROJECT_DIR="${REMOTE_PROJECT_DIR:-jtt1078-video-server}"
BRANCH="${BRANCH:-master}"
SERVICE_NAME="${SERVICE_NAME:-media}"

if [[ ! -f "$SSH_KEY" ]]; then
  echo "SSH key not found: $SSH_KEY" >&2
  echo "Set SSH_KEY=/path/to/key if your Oracle key is stored somewhere else." >&2
  exit 1
fi

quote() {
  printf '%q' "$1"
}

echo "Deploying $BRANCH to $SSH_USER@$SSH_HOST:$REMOTE_PROJECT_DIR"

ssh -i "$SSH_KEY" "$SSH_USER@$SSH_HOST" \
  "REMOTE_PROJECT_DIR=$(quote "$REMOTE_PROJECT_DIR") BRANCH=$(quote "$BRANCH") SERVICE_NAME=$(quote "$SERVICE_NAME") bash -se" <<'REMOTE_SCRIPT'
set -Eeuo pipefail

cd "$REMOTE_PROJECT_DIR"

echo "Pulling latest changes from origin/$BRANCH"
git pull --ff-only origin "$BRANCH"

echo "Building project"
mvn clean package -DskipTests

echo "Restarting service: $SERVICE_NAME"
sudo systemctl restart "$SERVICE_NAME"

echo "Service status"
sudo systemctl --no-pager --full status "$SERVICE_NAME"
REMOTE_SCRIPT
