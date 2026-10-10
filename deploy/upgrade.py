#!/usr/bin/env python3
"""Freeze, checkpoint, migrate candidate, then restore WHOLE checkpoint on failure.
Failed volumes are retained; rollback binds a new empty set. No down-migration/old-JAR-only fallback."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import time
from preflight import ROOT,compose,checked,require
from recovery_common import Maintenance,atomic_json,utc,start_services
from web_backup import create_backup
from web_restore import restore_backup

def perform_upgrade(key,candidate,fail_after_ddl=False,candidate_web=None):
    if fail_after_ddl:require(os.environ.get("COFFER_COMPOSE_REFERENCE")=="1","Fault injection is isolated-reference only")
    candidate_id=checked("docker","image","inspect",candidate,"--format","{{.Id}}")
    candidate_web_id=checked("docker","image","inspect",candidate_web,"--format","{{.Id}}") if candidate_web else None
    started=time.monotonic();archive,envelope,barrier=create_backup(key,keep_frozen=True)
    redis_enabled=any(item["service"]=="redis" and item["running"] for item in barrier.state["containers"])
    baseline=json.loads((ROOT/".active-compose.json").read_text()) if (ROOT/".active-compose.json").exists() else None
    barrier.persist("UPGRADE_MIGRATING",candidateImage=candidate_id,preUpgradeBackup=str(archive))
    phase="MIGRATION";failure_at=None
    try:
        checked(*compose("up","-d","--no-deps","mysql","minio"))
        # The isolated failed candidate contains V44: committed DDL then a missing-table failure.
        if fail_after_ddl:
            require(os.environ.get("COFFER_COMPOSE_REFERENCE")=="1","Fault injection is isolated-reference only")
        changes=dict(baseline or {});services=dict(changes.get("services",{}));services.update({"migrate":{"image":candidate_id},"backend":{"image":candidate_id}});changes["services"]=services
        if candidate_web_id:services["proxy"]={"image":candidate_web_id}
        atomic_json(ROOT/".active-compose.json",changes)
        checked(*compose("up","-d","--force-recreate","migrate"))
        start_services(redis_enabled)
        require(not fail_after_ddl,"Expected Flyway candidate failure did not occur")
        barrier.persist("UPGRADE_VERIFIED");barrier.release()
        report={"completedAt":utc(),"outcome":"UPGRADE_VERIFIED","backupId":envelope["id"],"elapsedSeconds":round(time.monotonic()-started,3)}
    except BaseException as failure:
        failure_at=dt.datetime.now(dt.timezone.utc);rollback_start=time.monotonic()
        barrier.persist("ROLLBACK_REQUIRED",failurePhase=phase,errorType=type(failure).__name__)
        if fail_after_ddl:
            phase="FLYWAY_PARTIAL_DDL"
            checked(*compose("exec","-T","mysql","sh","-ec",'MYSQL_PWD="$(cat /run/secrets/db_root_password)" mysql --protocol=socket -uroot coffer -e "SELECT id FROM upgrade_partial_probe"'))
        checked(*compose("--profile","redis","stop","-t","120"))
        if baseline is not None:atomic_json(ROOT/".active-compose.json",baseline)
        elif (ROOT/".active-compose.json").exists():(ROOT/".active-compose.json").unlink()
        proof=restore_backup(archive,key,new_volumes=True)
        start_services(proof["redisEnabled"])
        barrier=Maintenance().resume_journal();barrier.persist("ROLLBACK_VERIFIED");barrier.release()
        recovery=dt.datetime.fromisoformat(envelope["recoveryPointAt"])
        report={"completedAt":utc(),"outcome":"ROLLED_BACK_WHOLE_CHECKPOINT","failurePhase":phase,"backupId":envelope["id"],"rpoSeconds":0,"rpoBasis":"Writers remain frozen from recovery point until verified rollback; no later writes are accepted","recoveryPointAgeSeconds":round((failure_at-recovery).total_seconds(),3),"rtoSeconds":round(time.monotonic()-rollback_start,3),"restoreProof":proof,"failedVolumesRetained":True}
    atomic_json(ROOT/"evidence/upgrade-report.json",report);return report

if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--passphrase-file",type=Path,required=True);parser.add_argument("--candidate-image",required=True);parser.add_argument("--candidate-web-image");parser.add_argument("--fail-after-ddl",action="store_true")
    args=parser.parse_args();print(json.dumps(perform_upgrade(args.passphrase_file,args.candidate_image,args.fail_after_ddl,args.candidate_web_image)))
