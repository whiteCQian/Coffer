#!/bin/sh
set -eu
umask 077
export MC_CONFIG_DIR=/tmp/mc
mkdir -p "$MC_CONFIG_DIR/certs/CAs"
cp /tmp/ca-source/ca.pem "$MC_CONFIG_DIR/certs/CAs/ca.pem"
mc alias set admin https://minio:9000 "$(cat /run/secrets/minio_root_user)" "$(cat /run/secrets/minio_root_password)" >/dev/null
mc ready admin >/dev/null
if ! mc stat "admin/$MINIO_BUCKET" >/dev/null 2>&1; then
    mc mb "admin/$MINIO_BUCKET" >/dev/null
    mc version enable "admin/$MINIO_BUCKET" >/dev/null
fi
# Existing buckets must already be versioned; do not silently alter legacy data.
mc version info "admin/$MINIO_BUCKET" | grep -qi 'enabled'
mc anonymous set none "admin/$MINIO_BUCKET" >/dev/null
cat >/tmp/policy.json <<JSON
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:GetBucketLocation","s3:GetBucketVersioning","s3:ListBucket","s3:ListBucketVersions","s3:ListBucketMultipartUploads"],"Resource":["arn:aws:s3:::$MINIO_BUCKET"]},
 {"Effect":"Allow","Action":["s3:GetObject","s3:GetObjectVersion","s3:PutObject","s3:DeleteObject","s3:DeleteObjectVersion","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::$MINIO_BUCKET/users/*"]}
]}
JSON
mc admin policy create admin coffer-app /tmp/policy.json >/dev/null
parent=$(cat /run/secrets/minio_parent_user)
mc admin user add admin "$parent" "$(cat /run/secrets/minio_parent_password)" >/dev/null
mc admin policy attach admin coffer-app --user "$parent" >/dev/null
access=$(cat /run/secrets/minio_app_access)
if ! mc admin accesskey info admin "$access" >/dev/null 2>&1; then
    mc admin accesskey create admin "$parent" --access-key "$access" --secret-key "$(cat /run/secrets/minio_app_secret)" --policy /tmp/policy.json >/dev/null
fi
mc alias set app https://minio:9000 "$access" "$(cat /run/secrets/minio_app_secret)" >/dev/null
mc stat "app/$MINIO_BUCKET" >/dev/null
/bin/sh /scripts/minio-boundary.sh
echo 'Bucket versioning, private policy and application access key initialized.'
