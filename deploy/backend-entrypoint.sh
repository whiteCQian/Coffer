#!/bin/sh
set -eu
umask 077
export MYSQL_PASSWORD="$(cat /run/secrets/db_app_password)"
export MINIO_ACCESS_KEY="$(cat /run/secrets/minio_app_access)"
export MINIO_SECRET_KEY="$(cat /run/secrets/minio_app_secret)"
export COFFER_SECRET_KEY="$(cat /run/secrets/master_key)"
export COFFER_ADMIN_SETUP_TOKEN="$(cat /run/secrets/admin_setup_token)"
mkdir -p /state/logs
exec java -XX:MaxRAMPercentage=65 -Djava.io.tmpdir=/tmp -cp '/app/classes:/app/lib/*' com.coffer.CofferApplication
