#!/bin/sh
set -eu
MINIO_ROOT_USER=$(cat /run/secrets/minio_root_user)
MINIO_ROOT_PASSWORD=$(cat /run/secrets/minio_root_password)
test -n "$MINIO_ROOT_USER" && test -n "$MINIO_ROOT_PASSWORD"
export MINIO_ROOT_USER MINIO_ROOT_PASSWORD
exec /usr/local/bin/minio "$@"
