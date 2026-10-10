#!/usr/bin/env python3
"""Run on a disposable acceptance deployment or a fresh server. Records no request bodies."""
import argparse
import datetime as dt
import hashlib
import http.cookiejar
import json
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from preflight import ROOT, compose, checked, envfile, require, validate_config

class Client:
    def __init__(self, base, ca):
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies), urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca)))
    def call(self, path, method="GET", data=None, headers=None):
        hdr = dict(headers or {})
        if method != "GET":
            self.call("/api/auth/csrf")
            hdr["X-XSRF-TOKEN"] = next(c.value for c in self.cookies if c.name == "XSRF-TOKEN")
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            hdr["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=data, headers=hdr, method=method)
        try:
            with self.http.open(req, timeout=30) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            return error.code, error.read(), error.headers
    def ok(self, path, method="GET", data=None, headers=None):
        status, body, _ = self.call(path, method, data, headers)
        require(status == 200, "HTTP core flow failed at " + path + ": " + str(status))
        result = json.loads(body)
        require(result["code"] == 0, "Business core flow failed at " + path)
        return result["data"]
    def denied(self, path):
        status, body, _ = self.call(path)
        require(status in (401, 403, 404) or status == 200 and json.loads(body).get("code") in (401,403,404), "Cross-owner/public access was allowed: " + path)

def wait_ready(client, redis_state=None, ready="UP"):
    for _ in range(40):
        try:
            status = client.ok("/api/admin/runtime")
            components = {c["name"]: c["status"] for c in status["components"]}
            if status["readiness"] == ready and (redis_state is None or components.get("redis") == redis_state):
                return
        except (Exception, SystemExit):
            pass
        time.sleep(3)
    raise SystemExit("Timed out waiting for expected readiness/degradation")

def verify_private_ports(base):
    from urllib.parse import urlparse
    host = urlparse(base).hostname
    for port in (3306, 6379, 9000, 9001, 8080, 8081, 5173):
        try:
            with socket.create_connection((host, port), timeout=2):
                raise SystemExit("Infrastructure/dev port reachable on target: " + str(port))
        except OSError:
            pass

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--ca", help="Trusted private CA PEM for NAS, otherwise system trust store")
    parser.add_argument("--initialize", action="store_true", help="Initialize admin + two acceptance users only on a fresh server")
    parser.add_argument("--fixture-local", action="store_true", help="Use deterministic test model only with compose.acceptance.yaml on an isolated test server")
    parser.add_argument("--exercise-failures", action="store_true", help="Stop/recreate Redis, stop MinIO/MySQL, and restart stack; requires an acceptance maintenance window")
    parser.add_argument("--security-stress", action="store_true", help="Exercise 15-minute login lockout LAST; affects this client IP and test account")
    parser.add_argument("--limits", action="store_true", help="Use compose.limits.yaml on isolated acceptance stack; small bodies trigger quota")
    args = parser.parse_args()
    require(args.base_url.startswith("https://"), "HTTPS required")
    config = json.loads(checked(*compose("--profile", "redis", "--profile", "ops", "config", "--format", "json")))
    validate_config(config)
    verify_private_ports(args.base_url)
    anonymous = Client(args.base_url, args.ca)
    code, html, response_headers = anonymous.call("/")
    require(code == 200 and b"/assets/" in html and b"/@vite/client" not in html, "Static production frontend required")
    require(response_headers.get("Strict-Transport-Security") and response_headers.get("X-Frame-Options") == "DENY" and "frame-ancestors 'none'" in response_headers.get("Content-Security-Policy", ""), "HTTPS security headers required")
    for path in ("/actuator/health", "/h2-console", "/doc.html", "/api/debug", "/api/test/probe", "/api/files"):
        anonymous.denied(path)
    state_file = ROOT / "evidence/acceptance-state.json"
    if args.initialize:
        require(not state_file.exists(), "Acceptance state already exists; omit --initialize")
        require(anonymous.ok("/api/auth/status")["setupRequired"], "Initialization requires a fresh database")
        stamp = secrets.token_hex(4)
        state = {"admin": "accept-admin-" + stamp, "adminPassword": "Aa9!" + secrets.token_hex(6),
                 "users": [{"username": "accept-" + str(n) + "-" + stamp, "password": "Aa9!" + secrets.token_hex(6)} for n in (1, 2)]}
        anonymous.ok("/api/auth/setup", "POST", {"setupToken": (ROOT / "secrets/admin_setup_token").read_text(), "username": state["admin"], "password": state["adminPassword"]})
        for user in state["users"]:
            anonymous.ok("/api/admin/users", "POST", user)
        state_file.write_text(json.dumps(state))
        state_file.chmod(0o600)
    state = json.loads(state_file.read_text())
    admin = Client(args.base_url, args.ca)
    admin.ok("/api/auth/login", "POST", {"username": state["admin"], "password": state["adminPassword"]})
    require(not admin.ok("/api/auth/status")["setupRequired"], "Setup must close permanently")
    code, _, _ = admin.call("/api/admin/users", "POST", {"username": "weak-"+secrets.token_hex(4), "password": "password1234"})
    require(code == 400, "Weak production password was accepted")
    users = []
    for record in state["users"]:
        client = Client(args.base_url, args.ca)
        client.ok("/api/auth/login", "POST", {"username": record["username"], "password": record["password"]})
        require(any(c.name == "COFFER_SESSION" and c.secure and "HttpOnly" in c._rest for c in client.cookies), "Secure HttpOnly session required")
        client.denied("/api/admin/users")
        users.append(client)
        if args.fixture_local:
            import os
            require(os.environ.get("COFFER_COMPOSE_ACCEPTANCE") == "1", "Fixture requires isolated acceptance Compose override")
            validated = client.ok("/api/settings/runtime/validate", "POST", {"mode": "LOCAL"})
            require(validated["validationSuccess"], "Acceptance model connectivity failed")
            client.ok("/api/settings/runtime/mode", "PUT", {"mode": "LOCAL"})
        require(client.ok("/api/settings/runtime")["currentModeValidated"], "Configure and validate models for acceptance users, then rerun without --initialize")
        version = client.ok("/api/model-execution/target")["configurationVersion"]
        record["version"] = version
        if "fileId" not in record:
            marker = "acceptance-" + str(uuid.uuid4())
            boundary = "coffer" + uuid.uuid4().hex
            multipart = ("--"+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+marker+'.txt"\r\nContent-Type: text/plain\r\n\r\n'+marker+"\r\n--"+boundary+"--\r\n").encode()
            upload = client.ok("/api/files/upload", "POST", multipart, {"Content-Type": "multipart/form-data; boundary="+boundary, "Idempotency-Key": str(uuid.uuid4()), "X-Coffer-Model-Version": version, "X-Coffer-Allow-Sensitive": "true"})
            files = client.ok("/api/files?size=100")["content"]
            row = next((f for f in files if f["fileName"] == marker+".txt"), None)
            require(row is not None, "Uploaded object must be listed")
            record.update(fileId=row["id"], marker=marker, taskId=upload["taskId"])
            state_file.write_text(json.dumps(state))
        if "sessionId" not in record:
            chat = client.ok("/api/chat/send", "POST", {"message": "Reply with OK only."}, {"X-Coffer-Model-Version": version, "X-Coffer-Allow-Sensitive": "true"})
            record["sessionId"] = chat["sessionId"]
            state_file.write_text(json.dumps(state))
    def core_check():
        for index, client in enumerate(users):
            record = state["users"][index]
            status, body, _ = client.call("/api/files/"+str(record["fileId"])+"/content")
            require(status == 200 and hashlib.sha256(body).hexdigest() == hashlib.sha256(record["marker"].encode()).hexdigest(), "Persisted file content mismatch")
            other = state["users"][1-index]
            client.denied("/api/files/"+str(other["fileId"])+"/content")
            client.denied("/api/files/"+str(other["fileId"]))
            client.denied("/api/tasks/"+other["taskId"])
            listed = client.ok("/api/files?size=100")["content"]
            require(all(f["id"] != other["fileId"] for f in listed), "Cross-owner file listed")
            admin.denied("/api/files/"+str(record["fileId"])+"/content")
            admin.denied("/api/tasks/"+record["taskId"])
            headers = {"X-Coffer-Model-Version": record["version"], "X-Coffer-Allow-Sensitive": "true"}
            resumed = client.ok("/api/chat/send", "POST", {"sessionId": record["sessionId"], "message": "Reply with OK only."}, headers)
            require(resumed["sessionId"] == record["sessionId"], "Conversation continuation failed")
            code, body, _ = client.call("/api/chat/send", "POST", {"sessionId": other["sessionId"], "message": "Reply with OK only."}, headers)
            require(code in (403,404) or code == 200 and json.loads(body).get("code") in (403,404), "Cross-owner conversation continuation allowed")
        anonymous.denied("/api/files/"+str(state["users"][0]["fileId"])+"/content")
    core_check()
    limit_evidence="NOT_RUN"
    if args.limits:
        import os
        require(os.environ.get("COFFER_COMPOSE_LIMITS")=="1","Limit exercise requires isolated limits override")
        client=users[0]; record=state["users"][0]
        usage=client.ok("/api/storage/usage")
        require(usage["limitBytes"]==1024,"Quota exercise must use a small isolated limit")
        for _ in range(8):
            boundary="limit"+uuid.uuid4().hex
            body=("--"+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="quota.txt"\r\nContent-Type: text/plain\r\n\r\n'+"q"*900+"\r\n--"+boundary+"--\r\n").encode()
            before=client.ok("/api/storage/usage")
            code, response, _=client.call("/api/files/upload","POST",body,{"Content-Type":"multipart/form-data; boundary="+boundary,"Idempotency-Key":str(uuid.uuid4()),"X-Coffer-Model-Version":record["version"],"X-Coffer-Allow-Sensitive":"true"})
            if code==413:
                require("配额" in json.loads(response)["msg"],"Quota failure must explain the limit")
                after=client.ok("/api/storage/usage")
                require(before["usedBytes"]==after["usedBytes"] and before["objectCount"]==after["objectCount"],"Rejected upload changed allocation")
                limit_evidence="PASS";break
            require(code in (200,429),"Unexpected quota exercise failure")
            time.sleep(3)
        require(limit_evidence=="PASS","Quota did not reject before a large write")
    # Preserve session while checking a mutation without CSRF; the server must reject it.
    try:
        with users[0].http.open(urllib.request.Request(args.base_url+"/api/auth/logout", method="POST", data=b""),timeout=10) as response:
            raise SystemExit("Mutation without CSRF was accepted")
    except urllib.error.HTTPError as error:
        require(error.code==403,"Missing CSRF must return 403")
    result = checked(*compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "minio-init", "/scripts/minio-boundary.sh"))
    require("PASS:" in result, "MinIO boundary test failed")
    # Verify named-volume ownership/mode without mounting object data into the app.
    modes = checked(*compose("--profile", "ops", "run", "--rm", "--no-deps", "volume-tool", 'stat -c "%u:%g %a" /volumes/minio /volumes/mysql /volumes/state; df -P /volumes/minio /volumes/mysql /volumes/state'))
    require(modes.splitlines()[0] == "10001:10001 700" and modes.splitlines()[2] == "10001:10001 700", "Unexpected MinIO/backend volume permissions")
    mysql_mode = int(modes.splitlines()[1].split()[-1], 8)
    require(mysql_mode & 0o007 == 0, "MySQL volume must not be world accessible")
    devices = [line.split()[0] for line in modes.splitlines()[4:]]
    require(any(v["source"] == "minio_data" and v.get("read_only") for v in config["services"]["capacity"]["volumes"]), "Actual MinIO volume probe required")
    checked(*compose("exec", "-T", "capacity", "sh", "-ec", "test ! -r /measure/minio; test ! -x /measure/minio; test -r /capacity/minio-capacity.json"))
    # Runtime account cannot perform DDL or access mysql's system schema.
    dbclient = 'MYSQL_PWD="$(cat /run/secrets/db_app_password)" mysql --protocol=tcp --ssl-mode=VERIFY_IDENTITY --ssl-ca=/run/db-tls/ca.pem -h127.0.0.1 -ucoffer_app coffer'
    checked(*compose("exec", "-T", "mysql", "sh", "-c", dbclient+' -e "SELECT 1"'))
    require(subprocess.run(compose("exec", "-T", "mysql", "sh", "-c", dbclient.replace("--ssl-mode=VERIFY_IDENTITY", "--ssl-mode=DISABLED")+' -e "SELECT 1"'), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode!=0, "Plaintext database connection was allowed")
    dbcheck = dbclient+' -e "CREATE TABLE deployment_privilege_probe(id INT)"'
    require(subprocess.run(compose("exec", "-T", "mysql", "sh", "-c", dbcheck), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0, "Runtime DB user has DDL privilege")
    failure_tests = "NOT_RUN"
    if args.exercise_failures:
        try:
            checked(*compose("--profile", "redis", "up", "-d", "redis")); wait_ready(admin, "UP")
            checked(*compose("--profile", "redis", "stop", "redis")); wait_ready(admin, "DEGRADED"); core_check()
            checked(*compose("--profile", "redis", "rm", "-f", "redis"))
            checked(*compose("--profile", "redis", "up", "-d", "redis")); wait_ready(admin, "UP"); core_check()
            for dependency in ("minio", "mysql"):
                checked(*compose("stop", dependency))
                # MySQL outage also removes JDBC login/session availability; use internal readiness.
                for _ in range(40):
                    status = subprocess.run(compose("exec", "-T", "backend", "java", "-cp", "/app/classes:/app/lib/*", "com.coffer.deployment.HealthcheckMain"), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
                    if status != 0:
                        break
                    time.sleep(3)
                require(status != 0, "Backend stayed ready after required dependency failure")
                checked(*compose("start", dependency)); wait_ready(admin); core_check()
            checked(*compose("restart", "mysql", "minio", "backend", "proxy")); wait_ready(admin); core_check()
            failure_tests = "PASS"
        finally:
            subprocess.run(compose("--profile", "redis", "up", "-d", "--wait", "--wait-timeout", "300"), check=True)
    stress="NOT_RUN"
    if args.security_stress:
        for _ in range(11):
            code, _, headers = anonymous.call("/api/auth/login", "POST", {"username":state["users"][0]["username"],"password":"Wrong123456!"})
            if code==429:
                require(headers.get("Retry-After"),"Rate limiting must explain retry window"); stress="PASS"; break
        require(stress=="PASS","Brute-force login attempts were not limited")
    evidence = {"checkedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "staticHttpsFrontend": "PASS", "securityHeadersCsrfWeakPasswordAdminMatrix": "PASS", "quotaAdmission":limit_evidence, "databaseTls": "PASS", "bruteForce": stress, "twoAccountCoreFlows": "PASS", "privatePorts": "PASS", "bucketCredentialIsolation": "PASS", "volumePermissions": "PASS", "redisFailuresRestartRecovery": failure_tests,
                "target": args.base_url, "images": envfile(ROOT / "images.lock.env"),
                "referenceOnly": __import__("os").environ.get("COFFER_COMPOSE_REFERENCE")=="1",
                "project":config["name"],"actualImages":{name:item["image"] for name,item in config["services"].items()}}
    (ROOT / "evidence/acceptance.json").write_text(json.dumps(evidence, indent=2))
    print("Acceptance results saved to evidence/acceptance.json. NOT_RUN is not a pass.")

if __name__ == "__main__":
    main()
