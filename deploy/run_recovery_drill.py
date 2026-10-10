#!/usr/bin/env python3
"""Runs inside the trusted Linux operator container, on isolated Compose projects/volumes.
The reference override is NEVER production support evidence. No host port is published."""
import argparse
import importlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

def run(*args):return subprocess.check_output(args,text=True).strip()
def save_json(path,value):path.write_text(json.dumps(value,indent=2));os.chmod(path,0o600)
def copy_deploy(source,target):
    target.mkdir(parents=True)
    for file in source.iterdir():
        if file.is_file() and not file.name.startswith(".") and (file.suffix in (".py",".sh",".yaml",".json",".env",".md",".cjs") or file.name.startswith("Dockerfile") or file.name=="Caddyfile"):
            shutil.copyfile(file,target/file.name);os.chmod(target/file.name,0o644)
    shutil.copyfile(source/".env.example",target/".env.example")
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--root",type=Path,required=True);parser.add_argument("--source",type=Path,required=True);parser.add_argument("--operator-container",required=True);parser.add_argument("--continue-bootstrap",action="store_true");parser.add_argument("--resume-archive",type=Path)
    args=parser.parse_args();os.umask(0o077);root=args.root;root.mkdir(exist_ok=True)
    source=root/"source/deploy";target=root/"restored/deploy"
    if args.continue_bootstrap:
        os.environ["COFFER_COMPOSE_REFERENCE"]="1";os.environ["COFFER_COMPOSE_ACCEPTANCE"]="1";sys.path.insert(0,str(source))
        workflow(root,source,target,root/"operator-vault",args.operator_container,args.resume_archive);return
    copy_deploy(args.source,source);copy_deploy(args.source,target)
    sys.path.insert(0,str(source));import preflight
    assert preflight.ROOT==source
    os.environ["COFFER_COMPOSE_REFERENCE"]="1";os.environ["COFFER_COMPOSE_ACCEPTANCE"]="1"
    from prepare import prepare
    prepare("coffer-drill.test","127.0.0.1")
    env=(source/".env").read_text().replace("COMPOSE_PROJECT_NAME=coffer","COMPOSE_PROJECT_NAME=coffer_r43_source").replace("172.30.44.","172.30.61.")
    (source/".env").write_text(env)
    save_json(source/".active-compose.json",{"volumes":{k:{"name":"coffer_r43_source_trial_"+uuid.uuid4().hex[:12]+"_"+k} for k in ("mysql_data","minio_data","backend_state","capacity_data")}})
    (source/"secrets/minio.license").write_text("REFERENCE_ONLY_NO_AISTOR_LICENSE");os.chown(source/"secrets/minio.license",0,10001);os.chmod(source/"secrets/minio.license",0o440)
    save_json(source/"evidence/support-review.json",{"referenceOnly":True,"productionApproved":False})
    vault=root/"operator-vault";vault.mkdir();os.chmod(vault,0o700)
    for name in ("backup","db-ca","minio-ca"):
        file=vault/(name+".passphrase");file.write_text(os.urandom(32).hex());os.chmod(file,0o600)
    run("openssl","req","-x509","-newkey","rsa:3072","-nodes","-days","30","-subj","/CN=coffer-drill.test","-addext","subjectAltName=DNS:coffer-drill.test","-keyout",str(source/"tls/privkey.pem"),"-out",str(source/"tls/fullchain.pem"))
    for file in (source/"tls").iterdir():os.chown(file,0,10001);os.chmod(file,0o440)
    from internal_tls import generate,command
    internal=source/"internal-tls";internal.mkdir();os.chown(internal,0,10001);os.chmod(internal,0o750)
    uid=int(run("docker","run","--rm","--entrypoint","id","coffer/mysql:2026.10.10","-u","mysql"))
    generate(internal,"mysql",vault/"db-ca.passphrase",uid);(internal/"mysql").rename(internal/"db");generate(internal,"minio",vault/"minio-ca.passphrase",10001)
    command("docker","run","--rm","--user","0:0","-v",str(internal/"db")+":/work","-v",str(source/"secrets/db_trust_password")+":/run/password:ro","--entrypoint","keytool","coffer/backend:2026.10.10","-importcert","-noprompt","-alias","db","-file","/work/ca.pem","-keystore","/work/client-trust.p12","-storetype","PKCS12","-storepass:file","/run/password")
    os.chown(internal/"db/client-trust.p12",0,10001);os.chmod(internal/"db/client-trust.p12",0o440)
    workflow(root,source,target,vault,args.operator_container)
def workflow(root,source,target,vault,operator_container,resume_archive=None):
    from preflight import compose,checked
    env=(source/".env").read_text()
    if not resume_archive:
        checked(*compose("--profile","redis","up","-d","--wait","--wait-timeout","300"))
        run("docker","network","connect","coffer_r43_source_ingress",operator_container)
    from acceptance import Client
    base="https://coffer-drill.test:8443";ca=str(source/"tls/fullchain.pem")
    if not resume_archive:
        arguments=[sys.executable,str(source/"acceptance.py"),"--base-url",base,"--ca",ca,"--fixture-local","--exercise-failures"]
        if not (source/"evidence/acceptance-state.json").exists():arguments.append("--initialize")
        subprocess.run(arguments,check=True)
    # Preserve immutable owner/file samples for restore and rollback checks, without creating new files on recheck.
    credentials=json.loads((source/"evidence/acceptance-state.json").read_text())
    def verify_http():
        clients=[]
        for account in credentials["users"]:
            client=Client(base,ca);client.ok("/api/auth/login","POST",{"username":account["username"],"password":account["password"]});clients.append(client)
        for index,client in enumerate(clients):
            account=credentials["users"][index];status,body,_=client.call("/api/files/"+str(account["fileId"])+"/content")
            assert status==200 and body.decode()==account["marker"]
            client.denied("/api/files/"+str(credentials["users"][1-index]["fileId"])+"/content")
    if resume_archive:
        archive=resume_archive;envelope=json.loads(archive.with_suffix(".json").read_text())
    else:
        verify_http()
        from web_backup import create_backup
        archive,envelope,_=create_backup(vault/"backup.passphrase")
    # Set a new server binding WITHOUT generating replacement DB/MinIO/master credentials.
    target_env=env.replace("coffer_r43_source","coffer_r43_restored").replace("172.30.61.","172.30.62.");(target/".env").write_text(target_env)
    (target/"evidence").mkdir(exist_ok=True);save_json(target/"evidence/support-review.json",{"referenceOnly":True})
    if not resume_archive:run("docker","network","disconnect","coffer_r43_source_ingress",operator_container)
    # Fresh interpreter: ROOT is resolved from the restored server's own release directory.
    restored_run=root/"restore-step.py"
    restored_run.write_text("import sys,os,json,subprocess\nsys.path.insert(0,"+repr(str(target))+ ")\nfrom web_restore import restore_backup\nfrom preflight import compose\nfrom recovery_common import Maintenance\nproof=restore_backup("+repr(str(archive))+","+repr(str(vault/"backup.passphrase"))+ ")\nsubprocess.run(compose('--profile','redis','up','-d','--wait','--wait-timeout','300'),check=True)\nMaintenance().resume_journal().release()\nprint(json.dumps(proof))\n")
    restore_started=time.monotonic();subprocess.run([sys.executable,str(restored_run)],check=True)
    run("docker","network","connect","coffer_r43_restored_ingress",operator_container)
    ca=str(target/"tls/fullchain.pem");verify_http();restore_rto=round(time.monotonic()-restore_started,3)
    initial_proof=json.loads((target/"evidence/restore-proof.json").read_text())
    # A real Flyway candidate commits V44 DDL, fails its next statement, then invokes full snapshot rollback.
    subprocess.run([sys.executable,str(target/"upgrade.py"),"--passphrase-file",str(vault/"backup.passphrase"),"--candidate-image","coffer/failed-upgrade:r43","--fail-after-ddl"],check=True)
    verify_http()
    report={"completedAt":run("date","-u","+%Y-%m-%dT%H:%M:%SZ"),"environment":"isolated Linux Docker projects on one Docker Desktop host","referenceOnly":True,"sourceProject":"coffer_r43_source","restoreProject":"coffer_r43_restored","backupPath":str(archive),"backupSha256":envelope["sha256"],"recoveryPointAt":envelope["recoveryPointAt"],"restoreRtoSeconds":restore_rto,"twoAccountFilesOwnership":"PASS","restoreProof":initial_proof,"failedUpgradeRollback":json.loads((target/"evidence/upgrade-report.json").read_text()),"offHostVerified":False}
    save_json(root/"drill-report.json",report);print("R43_ISOLATED_RESTORE_AND_FLYWAY_ROLLBACK_VERIFIED")
if __name__=="__main__":main()
