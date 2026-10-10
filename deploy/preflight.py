#!/usr/bin/env python3
"""Fail closed on deployment prerequisites and public/persistence boundaries."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent
def compose(*args):
    extra = ["-f", str(ROOT / "compose.acceptance.yaml")] if os.environ.get("COFFER_COMPOSE_ACCEPTANCE") == "1" else []
    if os.environ.get("COFFER_COMPOSE_LIMITS") == "1": extra += ["-f", str(ROOT / "compose.limits.yaml")]
    if os.environ.get("COFFER_COMPOSE_REFERENCE") == "1": extra += ["-f", str(ROOT / "compose.recovery-reference.yaml")]
    if (ROOT/".active-compose.json").exists(): extra += ["-f",str(ROOT/".active-compose.json")]
    journal=ROOT/".maintenance.json"
    if journal.exists() and any(command in args for command in ("up","start","restart","run")):
        state=json.loads(journal.read_text())
        require(os.environ.get("COFFER_MAINTENANCE_TOKEN")==state.get("token"),"Persistent maintenance barrier active; use backup/restore/upgrade recovery CLI")
    return ["docker", "compose", "--env-file", str(ROOT / "images.lock.env"), "--env-file", str(ROOT / ".env"), "-f", str(ROOT / "compose.yaml"), *extra, *args]
def checked(*args):
    return subprocess.check_output(args, text=True).strip()
def require(ok, reason):
    if not ok:
        raise SystemExit(reason)
def envfile(file):
    return dict(line.split("=", 1) for line in file.read_text().splitlines() if line and not line.startswith("#"))
def validate_config(config):
    services = config["services"]
    require(set(services) >= {"proxy", "backend", "mysql", "minio", "migrate", "minio-init", "redis"}, "Missing production services")
    for name, service in services.items():
        for key, value in service.get("environment",{}).items():
            require(not value or key.endswith("_FILE") or not any(term in key for term in ("PASSWORD","SECRET_KEY","ACCESS_KEY","SETUP_TOKEN")), "Plaintext credential in Compose environment: "+key)
        ports = service.get("ports", [])
        require(not ports or name == "proxy" and len(ports) == 1 and ports[0]["published"] == "443" and ports[0]["target"] == 8443, "Only proxy HTTPS may be published: " + name)
        require(service.get("network_mode") != "host" and not service.get("privileged"), "Host network/privileged service prohibited")
        if name in {"mysql", "minio", "minio-init", "redis", "migrate"}:
            require(set(service["networks"]) == {"data"}, "Infrastructure must stay on internal data network")
        if name in {"minio", "redis"} and not (name=="minio" and os.environ.get("COFFER_COMPOSE_REFERENCE")=="1"):
            restored=(ROOT/".active-compose.json").exists() and re.fullmatch(r"sha256:[0-9a-f]{64}",service["image"])
            require(restored or re.search(r"@sha256:[0-9a-f]{64}$", service["image"]), "Unpinned infrastructure image")
    require(config["networks"]["data"]["internal"] and config["networks"]["proxy"]["internal"], "Private networks must be internal")
    for service, target in (("mysql", "/var/lib/mysql"), ("minio", "/data")):
        require(any(v["type"] == "volume" and v["target"] == target for v in services[service]["volumes"]), "Persistent volume required: " + service)
    require(not services["redis"].get("volumes"), "Redis must be rebuildable")
    require("redis" not in services["backend"].get("depends_on", {}), "Redis must not gate backend readiness")
    env = services["backend"]["environment"]
    require(env["SPRING_PROFILES_ACTIVE"] == "prod" and env["MYSQL_USERNAME"] == "coffer_app", "Production profile/runtime DB account required")
    require(env["MINIO_ENDPOINT"] == "https://minio:9000" and "sslMode=VERIFY_IDENTITY" in env["COFFER_DB_URL"] and "localhost" not in env["COFFER_DB_URL"] and "allowPublicKeyRetrieval=true" not in env["COFFER_DB_URL"], "TLS identity verification and service DNS required")
    require(env["COFFER_DATABASE_TLS_ENABLED"] == "true" and env["MINIO_CA_CERTIFICATE"] == "/run/secrets/minio_ca", "Separate scoped database/MinIO trust required")
    require(services["mysql"].get("build", {}).get("dockerfile") == "deploy/Dockerfile.mysql" and "--require-secure-transport=ON" in services["mysql"]["command"], "Hardened MySQL entrypoint and mandatory TLS required")
    require(services["capacity"]["network_mode"] == "none" and any(v.get("source") == "minio_data" and v.get("read_only") for v in services["capacity"]["volumes"]), "Capacity must probe actual MinIO volume, isolated from network")
    require(services["capacity"]["user"] == "10002:10001" and not services["capacity"].get("secrets"), "Capacity probe must have a distinct UID and no credentials")
    require(any(v["source"] == "capacity_data" and v.get("read_only") for v in services["backend"]["volumes"]), "Backend must read capacity receipt from a separate read-only volume")
    require(env["COFFER_COOKIE_SECURE"] == "true" and env["SPRING_FLYWAY_ENABLED"] == "false", "Secure cookies and separate DDL job required")
    backend_secrets = {s["source"] for s in services["backend"]["secrets"]}
    require(not backend_secrets & {"db_root_password", "db_migrate_password", "minio_root_user", "minio_root_password", "minio_parent_password"}, "Privileged credentials reached backend")
    require(all(v["source"] != "minio_data" for v in services["proxy"]["volumes"]), "Bucket cannot be served by web server")

def main():
    require(not any(os.environ.get(k) for k in ("COFFER_COMPOSE_ACCEPTANCE","COFFER_COMPOSE_LIMITS","COFFER_COMPOSE_REFERENCE")), "Acceptance overrides must never be used for production installation")
    require(os.name == "posix" and os.geteuid() == 0, "Run preflight as root on the Linux target")
    require(checked("docker", "info", "--format", "{{.OSType}}") == "linux", "Linux container daemon required")
    version = checked("docker", "compose", "version", "--short").lstrip("v")
    require(tuple(map(int, version.split(".")[:2])) >= (2, 24), "Compose 2.24+ required")
    locks = envfile(ROOT / "images.lock.env")
    require(locks["MYSQL_IMAGE"] in (ROOT/"Dockerfile.mysql").read_text(), "MySQL wrapper must use the exact locked base image")
    require(locks["MC_IMAGE"] in (ROOT/"Dockerfile.mc").read_text(), "MinIO helper must use the exact locked client image")
    env = envfile(ROOT / ".env")
    require(env["COFFER_DOMAIN"] != "files.example.com", "Set target DNS name")
    import ipaddress
    require(ipaddress.ip_address(env["PROXY_IP"]) in ipaddress.ip_network(env["PROXY_SUBNET"]), "Proxy IP must lie in proxy subnet")
    require(ipaddress.ip_network(env["PROXY_DYNAMIC_RANGE"]).subnet_of(ipaddress.ip_network(env["PROXY_SUBNET"])) and ipaddress.ip_address(env["PROXY_IP"]) not in ipaddress.ip_network(env["PROXY_DYNAMIC_RANGE"]), "Reserve the fixed proxy IP outside the dynamic allocation pool")
    for directory in ("secrets", "tls"):
        require(not (ROOT / directory).is_symlink(), "Symlink deployment directory prohibited")
        folder_stat = (ROOT / directory).stat()
        require(folder_stat.st_uid == 0 and folder_stat.st_gid == 10001 and folder_stat.st_mode & 0o777 == 0o750, "Expected credential directory root:10001 mode 0750")
        for file in (ROOT / directory).iterdir():
            require(file.is_file() and not file.is_symlink(), "Only regular credential files permitted")
            stat = file.stat()
            require(stat.st_uid == 0 and stat.st_gid == 10001 and stat.st_mode & 0o777 == 0o440, "Expected root:10001 mode 0440: " + file.name)
    license = ROOT / "secrets/minio.license"
    require(license.is_file() and license.stat().st_size > 0, "AIStor license missing")
    review = json.loads((ROOT / "evidence/support-review.json").read_text())
    now = dt.datetime.now(dt.timezone.utc)
    require(review.get("productionUseAuthorized") is True and review.get("vendorSupportActive") is True, "Target license and active vendor support must be reviewed")
    reviewed = dt.datetime.fromisoformat(review["reviewedAt"].replace("Z", "+00:00"))
    expiry = dt.datetime.fromisoformat(review["validUntil"].replace("Z", "+00:00"))
    require(reviewed <= now < expiry and now - reviewed < dt.timedelta(days=30), "Support/security review is expired or stale")
    for key in ("licenseReference", "supportReference", "securityReviewReference"):
        require(review.get(key) and not review[key].startswith("REPLACE"), "Missing authorization/security evidence reference")
    require(review["approvedMinioImage"] == locks["MINIO_IMAGE"] and review["approvedMcImage"] == locks["MC_IMAGE"], "Review must cover exact MinIO/client digests")
    config = json.loads(checked(*compose("--profile", "redis", "--profile", "ops", "config", "--format", "json")))
    validate_config(config)
    # Verify certificate/key match and expiry; hostname + trust chain are checked by acceptance HTTPS client.
    checked("openssl", "x509", "-in", str(ROOT / "tls/fullchain.pem"), "-checkend", "2592000", "-noout")
    cert_pub = checked("openssl", "x509", "-in", str(ROOT / "tls/fullchain.pem"), "-pubkey", "-noout")
    key_pub = checked("openssl", "pkey", "-in", str(ROOT / "tls/privkey.pem"), "-pubout")
    require(cert_pub == key_pub, "TLS certificate and key mismatch")
    for service, hostname in (("db", "mysql"), ("minio", "minio")):
        folder = ROOT / "internal-tls" / service
        leaf = folder / "server" / ("server.crt" if service == "db" else "public.crt")
        checked("openssl", "verify", "-CAfile", str(folder/"ca.pem"), "-verify_hostname", hostname, str(leaf))
        checked("openssl", "x509", "-in", str(leaf), "-checkend", "2592000", "-noout")
        private = folder / "server" / ("server.key" if service == "db" else "private.key")
        require(not private.is_symlink() and private.stat().st_mode & 0o007 == 0, "Private service key must not be world accessible")
        server_cert_pub = checked("openssl", "x509", "-in", str(leaf), "-pubkey", "-noout")
        server_key_pub = checked("openssl", "pkey", "-in", str(private), "-pubout")
        require(server_cert_pub == server_key_pub, "Service certificate/key mismatch")
    require((ROOT/"internal-tls/db/client-trust.p12").is_file(), "Database scoped truststore missing")
    evidence = {"checkedAt": now.isoformat(), "composeVersion": version, "boundaries": "PASS", "licenseSupportReview": "PASS", "images": locks,
                "composeSha256": hashlib.sha256((ROOT / "compose.yaml").read_bytes()).hexdigest()}
    (ROOT / "evidence/preflight.json").write_text(json.dumps(evidence, indent=2))
    print("Preflight passed; evidence/preflight.json saved. Runtime acceptance is still required.")

if __name__ == "__main__":
    main()
