#!/usr/bin/env python3
"""Generate target-only secrets once. No credentials are printed or overwritten."""
import argparse
import os
from pathlib import Path
import re
import secrets

ROOT = Path(__file__).resolve().parent

def prepare(domain, bind):
    if os.name != "posix" or os.geteuid() != 0:
        raise SystemExit("Run as root on the Linux target to set numeric container file permissions")
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]+[a-zA-Z0-9]", domain):
        raise SystemExit("Expected a DNS hostname")
    import ipaddress
    ipaddress.ip_address(bind)
    os.umask(0o077)
    for name in ("secrets", "tls", "evidence", "backups"):
        folder = ROOT / name
        if folder.is_symlink():
            raise SystemExit("Refusing linked deployment directories")
        folder.mkdir(exist_ok=True)
        os.chmod(folder, 0o700 if name in ("evidence", "backups") else 0o750)
        if name in ("secrets", "tls"):
            os.chown(folder, 0, 10001)
    names = ("db_root_password", "db_app_password", "db_migrate_password", "minio_root_user",
             "minio_root_password", "minio_parent_user", "minio_parent_password",
             "minio_app_access", "minio_app_secret", "master_key", "admin_setup_token", "db_trust_password")
    for name in names:
        target = ROOT / "secrets" / name
        if target.exists() or target.is_symlink():
            raise SystemExit("Existing secrets found; refusing reinitialization. Use the existing release.")
    if (ROOT / ".env").exists():
        raise SystemExit("Existing .env found; refusing overwrite")
    for name in names:
        target = ROOT / "secrets" / name
        target.write_text(secrets.token_hex(10 if name=="minio_app_access" else 20 if name=="minio_app_secret" else 16 if name.endswith("user") else 32))
        os.chown(target, 0, 10001)
        os.chmod(target, 0o440)
    env = (ROOT / ".env.example").read_text().replace("files.example.com", domain).replace("HTTPS_BIND_IP=0.0.0.0", "HTTPS_BIND_IP=" + bind)
    (ROOT / ".env").write_text(env)
    print("Generated target credentials. Install TLS, AIStor license and support-review.json before preflight.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", required=True)
    parser.add_argument("--bind", default="0.0.0.0")
    args = parser.parse_args()
    prepare(args.domain, args.bind)
