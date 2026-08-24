#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Runs the Maven reactor's tests locally with the same goal as CI's build-test job.
#
# Usage: scripts/test.sh                       # full reactor
#        scripts/test.sh -pl user-service -am  # single module

COLIMA_SOCKET="${HOME}/.colima/default/docker.sock"

if [ -S "$COLIMA_SOCKET" ]; then
  # Colima's daemon runs inside its own Linux VM, so the host-side socket path
  # (DOCKER_HOST) and the path the daemon sees for its own socket (what Ryuk
  # bind-mounts from) are two different paths. Docker Desktop doesn't have this
  # split: its default context already points at a socket the daemon agrees on,
  # so no override is needed there.
  export DOCKER_HOST="unix://${COLIMA_SOCKET}"
  export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  echo "Colima detected. DOCKER_HOST=$DOCKER_HOST"
else
  echo "No Colima socket found, using the current Docker context (e.g. Docker Desktop)."
fi

docker info >/dev/null || {
  echo "Docker not reachable - is Docker Desktop or 'colima start' running?" >&2
  exit 1
}

mvn -ntp verify "$@"
