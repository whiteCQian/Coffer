#!/bin/sh
set -eu
umask 077
export MC_CONFIG_DIR=/tmp/mc-check
mkdir -p "$MC_CONFIG_DIR/certs/CAs"
cp /tmp/ca-source/ca.pem "$MC_CONFIG_DIR/certs/CAs/ca.pem"
mc alias set admin https://minio:9000 "$(cat /run/secrets/minio_root_user)" "$(cat /run/secrets/minio_root_password)" >/dev/null
mc alias set app https://minio:9000 "$(cat /run/secrets/minio_app_access)" "$(cat /run/secrets/minio_app_secret)" >/dev/null
other="coffer-check-$(date +%s)-$$"
key="users/deployment-check/$$"
cleanup() {
    mc rm --versions --force "admin/$MINIO_BUCKET/$key" >/dev/null 2>&1 || true
    mc rb --force "admin/$other" >/dev/null 2>&1 || true
}
trap cleanup EXIT
mc mb "admin/$other" >/dev/null
echo boundary-check >/tmp/body
mc cp /tmp/body "app/$MINIO_BUCKET/$key" >/dev/null
mc stat "app/$MINIO_BUCKET/$key" >/dev/null
if mc cp /tmp/body "app/$MINIO_BUCKET/outside-prefix" >/dev/null 2>&1; then
    mc rm --versions --force "admin/$MINIO_BUCKET/outside-prefix" >/dev/null
    echo 'FAIL: application wrote outside users prefix' >&2; exit 1
fi
if mc ls "app/$other" >/dev/null 2>&1; then echo 'FAIL: application accessed another bucket' >&2; exit 1; fi
if mc admin info app >/dev/null 2>&1; then echo 'FAIL: application has administrator permissions' >&2; exit 1; fi
mc version info "app/$MINIO_BUCKET" | grep -qi enabled
mc anonymous get "admin/$MINIO_BUCKET" | grep -qi private
echo 'PASS: app bucket/prefix isolation, no admin access, versioning, no anonymous bucket policy'
