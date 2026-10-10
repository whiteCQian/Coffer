#!/usr/bin/env python3
"""Validate a fresh full-TCP Nmap XML report produced on an untrusted network host."""
import argparse
import datetime as dt
import hashlib
import ipaddress
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from preflight import ROOT, require

def validate(report, expected_ip, now):
    require(len(report) <= 16 * 1024 * 1024, "Oversized scan report")
    root = ET.fromstring(report)
    require(root.tag == "nmaprun" and root.get("scanner") == "nmap", "Expected Nmap XML")
    started = dt.datetime.fromtimestamp(int(root.get("start", "0")), dt.timezone.utc)
    require(dt.timedelta(0) <= now - started < dt.timedelta(hours=2), "Scan must be fresh")
    require(any(n.get("protocol") == "tcp" and n.get("numservices") == "65535" for n in root.findall("scaninfo")), "Scan all TCP ports, not just default services")
    hosts = [h for h in root.findall("host") if any(a.get("addr") == expected_ip for a in h.findall("address"))]
    require(len(hosts) == 1 and hosts[0].find("status").get("state") == "up", "Target missing/unreachable")
    ports = {int(p.get("portid")) for p in hosts[0].findall("ports/port") if p.get("protocol") == "tcp" and p.find("state").get("state") == "open"}
    require(ports == {443}, "Unexpected public TCP ports: " + str(sorted(ports)))
    require(root.find("runstats/finished").get("exit") == "success", "Scan did not finish successfully")
    return {"checkedAt": now.isoformat(), "scanStartedAt": started.isoformat(), "targetIp": expected_ip, "fullTcpScan": "PASS", "publicPorts": [443], "scanSha256": hashlib.sha256(report).hexdigest()}

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--expected-ip", required=True)
    parser.add_argument("--outside-trusted-network", action="store_true", required=True, help="Attest that the scan was run from the actual untrusted network")
    args = parser.parse_args()
    ip = str(ipaddress.ip_address(args.expected_ip))
    evidence = validate(args.report.read_bytes(), ip, dt.datetime.now(dt.timezone.utc))
    evidence["outsideTrustedNetworkAttested"] = True
    (ROOT / "evidence/external-boundary.json").write_text(json.dumps(evidence, indent=2))
    print("Fresh full-port report passed; evidence/external-boundary.json saved.")
