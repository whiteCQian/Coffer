#!/usr/bin/env python3
"""Inspect a durable barrier or recover it from an authenticated whole checkpoint."""
import argparse
import json
from pathlib import Path
from preflight import ROOT, compose, checked
from recovery_common import Maintenance,start_services

def main():
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest="command",required=True)
    sub.add_parser("status")
    recover=sub.add_parser("recover");recover.add_argument("archive",type=Path);recover.add_argument("--passphrase-file",type=Path,required=True)
    args=parser.parse_args()
    if args.command=="status":
        if not (ROOT/".maintenance.json").exists():print("NO_MAINTENANCE_BARRIER");return
        state=json.loads((ROOT/".maintenance.json").read_text())
        print(json.dumps({k:state.get(k) for k in ("id","purpose","phase","enteredAt","recoveryPointAt","backupPath","preUpgradeBackup","errorType")}));return
    # Authentication and path validation happen before stopping any target service.
    from web_backup import passphrase_file,validate_archive
    from web_restore import restore_backup
    validate_archive(args.archive,passphrase_file(args.passphrase_file))
    checked(*compose("--profile","redis","stop","-t","120"))
    proof=restore_backup(args.archive,args.passphrase_file)
    start_services(proof["redisEnabled"])
    Maintenance().resume_journal().release();print(json.dumps(proof))

if __name__=="__main__":main()
