#!/bin/sh
set -eu
action="${1:-}"
case "$action" in
  reset|seed|reset-and-seed) ;;
  *)
    echo "usage: ACCEPTANCE_TEST_PASSWORD='...' ./scripts/acceptance-data.sh reset|seed|reset-and-seed" >&2
    exit 1
    ;;
esac
if [ -z "${ACCEPTANCE_TEST_PASSWORD:-}" ]; then
  echo "ACCEPTANCE_TEST_PASSWORD is required" >&2
  exit 1
fi
cd "$(dirname "$0")/.."
export ACCEPTANCE_DATA_ENABLED=true
export ACCEPTANCE_DATA_ACTION="$action"
export BOOTSTRAP_USER_ENABLED=false
exec ./mvnw -q spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dserver.port=-1 -Dspring.main.banner-mode=off -Dlogging.level.root=WARN -Dspring.profiles.active=local"
