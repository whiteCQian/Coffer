import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import uuid
from preflight import ROOT,compose,checked,require,envfile
from recovery_common import Maintenance,atomic_json,config,mounted_volumes,safe_members,sha256,utc,start_services
from web_backup import decrypt,passphrase_file,validate_archive,checkpoint_probe

def recovered_environment(original,target_binding):
    binding_keys={"COMPOSE_PROJECT_NAME","COFFER_DOMAIN","HTTPS_BIND_IP","PROXY_SUBNET","PROXY_DYNAMIC_RANGE","PROXY_IP"}
    lines=[line.partition("=")[0]+"="+target_binding[line.partition("=")[0]] if line.partition("=")[0] in binding_keys and line.partition("=")[0] in target_binding else line for line in original.splitlines()]
    return "\n".join(lines)+"\n"

def require_volume_capacity(rows,capacity,fallback):
    sizes=capacity.get("volumeBytesByMount",{})
    by_mount={line.split()[-1]:int(line.split()[3])*1024 for line in rows.splitlines()[1:]}
    for mount in ("/volumes/mysql","/volumes/minio","/volumes/state"):
        need=int(sizes.get(mount,capacity.get("volumeBytes",fallback))*1.1)+1024**3
        require(by_mount.get(mount,0)>=need,"Insufficient target volume capacity at "+mount+"; preserve checkpoint and increase capacity")

def extract_release(archive,key,destination):
    process=decrypt(archive,key)
    try:
        with tarfile.open(fileobj=process.stdout,mode="r|") as tar:
            for member in safe_members(tar):
                if not member.name.startswith(("release/","checkpoint/")):continue
                target=destination/member.name
                if member.isdir():
                    target.mkdir(parents=True,exist_ok=True);os.chmod(target,member.mode & 0o777);os.chown(target,member.uid,member.gid)
                else:
                    target.parent.mkdir(parents=True,exist_ok=True)
                    with target.open("xb") as output:shutil.copyfileobj(tar.extractfile(member),output,1024*1024)
                    os.chmod(target,member.mode & 0o777);os.chown(target,member.uid,member.gid)
        while process.stdout.read(1024*1024):pass
        require(process.wait()==0,"Release extraction integrity failed")
    finally:
        process.stdout.close()
        if process.poll() is None:process.kill();process.wait()
def bind_images(versions,suffix):
    return {"services":{k:{"image":v["id"]} for k,v in versions["images"].items()},
            "volumes":{k:{"name":config()["name"]+"_"+suffix+"_"+k} for k in ("mysql_data","minio_data","backend_state","capacity_data")}}
def restore_backup(archive,key,new_volumes=True,sanitize=True):
    require(os.name=="posix" and os.geteuid()==0,"Run on Linux as root")
    key=passphrase_file(key);archive=Path(archive);point,versions=validate_archive(archive,key)
    require(versions.get("referenceOnly")== (os.environ.get("COFFER_COMPOSE_REFERENCE")=="1"),"Reference-only checkpoints require the explicit isolated reference mode")
    capacity=versions.get("capacity",{})
    required=int((capacity.get("imageBytesUpperBound",archive.stat().st_size*4)+capacity.get("volumeBytes",archive.stat().st_size*4))*1.1)+1024**3
    require(shutil.disk_usage(ROOT).free>=required,"Insufficient restore workspace/data space; preserve the checkpoint and increase capacity")
    require(not checked(*compose("--profile","redis","ps","-q")),"Stop all target services before restoration")
    barrier=Maintenance()
    if barrier.path.exists():barrier.resume_journal()
    else:
        barrier.state={"formatVersion":2,"id":str(uuid.uuid4()),"token":uuid.uuid4().hex,"purpose":"restore","phase":"RESTORING","enteredAt":utc(),"sourceProject":config()["name"],"volumes":mounted_volumes(config()),"containers":[]}
        atomic_json(barrier.path,barrier.state);os.environ["COFFER_MAINTENANCE_TOKEN"]=barrier.state["token"]
    os.umask(0o077);(ROOT/"evidence").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="restore-",dir=ROOT/"evidence") as temporary:
        stage=Path(temporary);extract_release(archive,key,stage)
        checked("docker","image","load","-i",str(stage/"checkpoint/images.tar"))
        # Original bytes are authenticated inside the ciphertext; external JSON is never restore authority.
        for name,expected in versions["deploymentFiles"].items():require(sha256(stage/"checkpoint/deployment"/name)==expected,"Deployment artifact checksum mismatch")
        target_binding=envfile(ROOT/".env")
        (ROOT/".env").write_text(recovered_environment((stage/"release/env").read_text(),target_binding));os.chmod(ROOT/".env",0o600)
        # Use the checkpoint's network/service definitions and lock file. The new server's
        # .env supplies project/DNS/address bindings; all source settings remain in release/env.
        for file in (stage/"checkpoint/deployment").iterdir():
            if file.suffix in (".yaml",".cjs") or file.name in ("Caddyfile","images.lock.env"):
                shutil.copyfile(file,ROOT/file.name);os.chmod(ROOT/file.name,0o644)
        shutil.copyfile(stage/"release/support-review.json",ROOT/"evidence/support-review.json")
        # Preserve target project/network binding; credentials and PKI must be restored together.
        for name in ("secrets","tls","internal-tls"):
            target=ROOT/name
            if target.exists():
                require(not target.is_symlink(),"Linked credential directory prohibited")
                for old in target.rglob("*"):
                    if old.is_file():require(not old.is_symlink(),"Linked credential prohibited")
                # Only an empty credential directory or identical known release is eligible.
                for old in target.rglob("*"):
                    if old.is_file():
                        source=stage/"release"/name/old.relative_to(target)
                        require(source.is_file() and sha256(source)==sha256(old),"Target credentials differ; use a new isolated release directory")
            shutil.copytree(stage/"release"/name,target,dirs_exist_ok=True)
            for path in (stage/"release"/name).rglob("*"):
                current=target/path.relative_to(stage/"release"/name);metadata=path.stat();os.chmod(current,metadata.st_mode & 0o777);os.chown(current,metadata.st_uid,metadata.st_gid)
            stat=(stage/"release"/name).stat();os.chmod(target,stat.st_mode & 0o777);os.chown(target,stat.st_uid,stat.st_gid)
        for key_name in ("master_key","minio_app_access","minio_app_secret","db_app_password"):
            require(sha256(ROOT/"secrets"/key_name)==sha256(stage/"release/secrets"/key_name),"Credential restore mismatch")
        if new_volumes:atomic_json(ROOT/".active-compose.json",bind_images(versions,"restore_"+uuid.uuid4().hex[:12]))
        target_volumes=mounted_volumes(config())
        for name in target_volumes.values():
            # Refuse any active container sharing even one target volume, regardless of project.
            for container in checked("docker","ps","-q","--filter","volume="+name).splitlines():raise SystemExit("Target volume is mounted by a running container")
        os.environ["COFFER_CHECKPOINT_DIR"]=str(stage/"checkpoint")
        check='for d in /volumes/mysql /volumes/minio /volumes/state; do test -z "$(ls -A "$d")" || exit 1; done'
        checked(*compose("--profile","ops","run","--rm","--no-deps","volume-tool",check))
        space=checked(*compose("--profile","ops","run","--rm","--no-deps","volume-tool","df -Pk /volumes/mysql /volumes/minio /volumes/state"))
        require_volume_capacity(space,capacity,archive.stat().st_size*4)
        process=decrypt(archive,key)
        restore=subprocess.Popen(compose("--profile","ops","run","--rm","--no-deps","-T","volume-tool","tar --numeric-owner -xpf - -C / volumes/mysql volumes/minio volumes/state"),stdin=process.stdout)
        process.stdout.close();require(restore.wait()==0 and process.wait()==0,"Partial restoration; keep barrier and retry on another empty volume set")
        # Start only original infrastructure; no Flyway/backend/init/schedulers can modify restored state.
        checked(*compose("up","-d","--no-deps","mysql","minio"))
        import time
        for _ in range(60):
            ids=checked(*compose("ps","-q","mysql","minio")).splitlines()
            if len(ids)==2 and all(json.loads(checked("docker","inspect",i))[0]["State"].get("Health",{}).get("Status")=="healthy" for i in ids):break
            time.sleep(2)
        else:raise SystemExit("Original data services failed restore health")
        checkpoint_probe("verify")
        if sanitize:
            checked(*compose("--profile","ops","run","--rm","--no-deps","recovery-probe","sanitize"))
            # Recreate only this stack's optional Redis. No restored login/legacy chat/vector cache is trusted.
            checked(*compose("--profile","redis","rm","-s","-f","redis"))
        evidence={"verifiedAt":utc(),"backupId":versions["id"],"recoveryPointAt":versions["recoveryPointAt"],"tablesOwnershipLedgers":"PASS","objectVersionsSha256":"PASS","modelMasterAndCredentials":"PASS","sourceProject":versions["sourceProject"],"targetProject":config()["name"],"newVolumes":target_volumes,"sessionsRevoked":sanitize,"vectorsInvalidated":sanitize,"redisEnabled":versions.get("redisEnabled",False),"referenceOnly":versions["referenceOnly"]}
        atomic_json(ROOT/"evidence/restore-proof.json",evidence)
        barrier.persist("RESTORE_VERIFIED",proofPath=str(ROOT/"evidence/restore-proof.json"))
    os.environ.pop("COFFER_CHECKPOINT_DIR",None)
    return evidence
def main():
    parser=argparse.ArgumentParser();parser.add_argument("archive",type=Path);parser.add_argument("--passphrase-file",type=Path,required=True)
    args=parser.parse_args();evidence=restore_backup(args.archive,args.passphrase_file)
    start_services(evidence["redisEnabled"]);Maintenance().resume_journal().release();print(json.dumps(evidence))
if __name__=="__main__":main()
