import argparse
import json
import os
from pathlib import Path
import subprocess
import shutil
import tarfile
import tempfile
import uuid
from preflight import ROOT,compose,checked,envfile,require
from recovery_common import Maintenance,atomic_json,config,sha256,safe_members,utc,start_services

digest=sha256
def passphrase_file(path):
    path=Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_uid==0 and path.stat().st_mode & 0o077==0 and path.stat().st_size>=32,"Root-only independent backup passphrase required")
    require(not path.resolve().is_relative_to(ROOT),"Do not put backup password in the release/payload")
    return path.resolve()
def decrypt(archive,key):
    return subprocess.Popen(["gpg","--batch","--pinentry-mode","loopback","--passphrase-file",str(key),"--decrypt",str(archive)],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
def validate_archive(archive,key):
    process=decrypt(archive,key);point=None;versions=None
    try:
        with tarfile.open(fileobj=process.stdout,mode="r|") as tar:
            for member in safe_members(tar):
                if not member.isfile():continue
                stream=tar.extractfile(member)
                if member.name in ("checkpoint/point.json","checkpoint/versions.json"):
                    require(member.size<=128*1024*1024,"Oversized recovery manifest")
                    value=json.load(stream)
                    if member.name.endswith("point.json"):point=value
                    else:versions=value
                else:
                    while stream.read(1024*1024):pass
        while process.stdout.read(1024*1024):pass
        require(process.wait()==0,"Backup decryption/integrity failed")
    finally:
        process.stdout.close()
        if process.poll() is None:process.kill();process.wait()
    require(point and versions and point.get("formatVersion")==2 and versions.get("formatVersion")==2,"Recovery manifest missing")
    require(versions["pointSha256"]==sha256_json(point),"Recovery point manifest mismatch")
    return point,versions
def sha256_json(value):
    import hashlib
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False).encode()).hexdigest()
def image_manifest(configuration):
    values={}
    for service,item in configuration["services"].items():
        try:identity=checked("docker","image","inspect",item["image"],"--format","{{.Id}}")
        except subprocess.CalledProcessError:
            if service=="redis":continue
            raise
        info=json.loads(checked("docker","image","inspect",identity))[0]
        values[service]={"reference":item["image"],"id":identity,"architecture":info["Architecture"],"os":info["Os"]}
    return values

def capacity_plan(images):
    image_bytes=sum(json.loads(checked("docker","image","inspect",identity))[0]["Size"] for identity in {item["id"] for item in images.values()})
    # Overestimate shared image layers and encryption overhead. Check before creating images.tar.
    physical=checked(*compose("--profile","ops","run","--rm","--no-deps","volume-tool","du -sk /volumes/mysql /volumes/minio /volumes/state"))
    apparent=checked(*compose("--profile","ops","run","--rm","--no-deps","volume-tool","du -sk --apparent-size /volumes/mysql /volumes/minio /volumes/state"))
    by_mount={}
    for line in (physical+"\n"+apparent).splitlines():
        size,path=line.split();by_mount[path]=max(by_mount.get(path,0),int(size)*1024)
    volume_bytes=sum(by_mount.values())
    required=int((image_bytes*2+volume_bytes)*1.1)+1024**3
    require(shutil.disk_usage(ROOT).free>=required,"Insufficient backup workspace; provide space for exact images, ciphertext and 1 GiB reserve")
    return {"imageBytesUpperBound":image_bytes,"volumeBytes":volume_bytes,"volumeBytesByMount":by_mount,"workspaceRequiredBytes":required}
def checkpoint_probe(mode):
    checked(*compose("--profile","ops","run","--rm","--no-deps","recovery-probe",mode,"/checkpoint/point.json"))
def create_backup(key,keep_frozen=False):
    require(os.name=="posix" and os.geteuid()==0,"Run on Linux as root")
    key=passphrase_file(key);os.umask(0o077);(ROOT/"backups").mkdir(exist_ok=True)
    maintenance=Maintenance().enter("backup")
    archive=ROOT/"backups"/("coffer-"+maintenance.state["id"]+".tar.gpg")
    try:
        with tempfile.TemporaryDirectory(prefix="checkpoint-",dir=ROOT/"evidence") as temporary:
            stage=Path(temporary);os.environ["COFFER_CHECKPOINT_DIR"]=str(stage)
            configuration=config();images=image_manifest(configuration)
            capacity=capacity_plan(images)
            checkpoint_probe("capture")
            point=json.loads((stage/"point.json").read_text())
            deployment=stage/"deployment";deployment.mkdir()
            for file in ROOT.iterdir():
                if file.is_file() and not file.is_symlink() and not file.name.startswith(".") and (file.suffix in (".py",".sh",".yaml",".json",".env",".md",".cjs") or file.name.startswith("Dockerfile")):
                    shutil.copyfile(file,deployment/file.name)
            shutil.copyfile(ROOT/"Caddyfile",deployment/"Caddyfile")
            versions={"formatVersion":2,"id":maintenance.state["id"],"recoveryPointAt":maintenance.state["recoveryPointAt"],"pointSha256":sha256_json(point),"images":images,"sourceProject":configuration["name"],"infrastructureLocks":envfile(ROOT/"images.lock.env"),
                      "deploymentFiles":{p.name:sha256(p) for p in deployment.iterdir()},"capacity":capacity,"redisEnabled":any(item["service"]=="redis" and item["running"] for item in maintenance.state["containers"]),"referenceOnly":os.environ.get("COFFER_COMPOSE_REFERENCE")=="1",
                      "overrides":{k:os.environ.get(k,"") for k in ("COFFER_COMPOSE_REFERENCE","COFFER_COMPOSE_ACCEPTANCE","COFFER_COMPOSE_LIMITS")}}
            atomic_json(stage/"versions.json",versions)
            # Save exact image IDs, including init/client/probe images, so old tags are never an implicit rollback.
            checked("docker","image","save","-o",str(stage/"images.tar"),*sorted({v["id"] for v in images.values()}))
            maintenance.persist("CAPTURED",backupPath=str(archive))
            maintenance.stop_data()
            source=subprocess.Popen(compose("--profile","ops","run","--rm","--no-deps","-T","volume-tool","tar --numeric-owner --exclude='volumes/mysql/*.sock' --exclude='volumes/mysql/*.pid' -cpf - -C / volumes release checkpoint"),stdout=subprocess.PIPE)
            with archive.open("xb") as output:
                encrypt=subprocess.Popen(["gpg","--batch","--pinentry-mode","loopback","--passphrase-file",str(key),"--symmetric","--cipher-algo","AES256","--output","-"],stdin=source.stdout,stdout=output)
                source.stdout.close()
                require(encrypt.wait()==0 and source.wait()==0,"Backup tar/encryption failed")
            validate_archive(archive,key)
            envelope={"formatVersion":2,"id":versions["id"],"recoveryPointAt":versions["recoveryPointAt"],"completedAt":utc(),"verified":True,"restoreTested":False,"offHostVerified":False,"referenceOnly":versions["referenceOnly"],"sha256":sha256(archive),"sizeBytes":archive.stat().st_size}
            atomic_json(archive.with_suffix(".json"),envelope)
            receipt=json.dumps({k:envelope[k] for k in ("completedAt","verified","sha256","sizeBytes")})
            checked(*compose("--profile","ops","run","--rm","--no-deps","volume-tool","printf '%s' '"+receipt+"' > /volumes/state/backup-receipt.json; chown 10001:10001 /volumes/state/backup-receipt.json; chmod 0600 /volumes/state/backup-receipt.json"))
            maintenance.persist("BACKUP_VERIFIED",backupSha256=envelope["sha256"])
        if not keep_frozen:
            start_services(versions["redisEnabled"]);maintenance.release()
        return archive,envelope,maintenance
    except BaseException as error:
        maintenance.fail(error);raise
    finally:os.environ.pop("COFFER_CHECKPOINT_DIR",None)
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--passphrase-file",type=Path,required=True);parser.add_argument("--keep-frozen",action="store_true")
    args=parser.parse_args();archive,envelope,_=create_backup(args.passphrase_file,args.keep_frozen)
    print(json.dumps({"archive":str(archive),"sha256":envelope["sha256"],"offHostVerified":False}))
if __name__=="__main__":main()
