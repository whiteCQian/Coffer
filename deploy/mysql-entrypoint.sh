#!/bin/bash
set -euo pipefail
# Read file secrets while still root, before the official entrypoint changes to the mysql UID.
export MYSQL_ROOT_PASSWORD="$(cat /run/secrets/db_root_password)"
export COFFER_DB_APP_PASSWORD="$(cat /run/secrets/db_app_password)"
export COFFER_DB_MIGRATE_PASSWORD="$(cat /run/secrets/db_migrate_password)"
exec /usr/local/bin/docker-entrypoint.sh "$@"
