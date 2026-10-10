"""Durable maintenance state and safe archive primitives shared by Web lifecycle tools."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import uuid
from preflight import ROOT, compose, checked, require

def utc(): return dt.datetime.now(dt.timezone.utc).isoformat()
def atomic_json(path,value):
    path=Path(path);stage=path.with_name(path.name+"."+uuid.uuid4().hex+".tmp")
    with stage.open("x",encoding="utf-8") as stream:
        os.chmod(stage,0o600);json.dump(value,stream,indent=2);stream.flush();os.fsync(stream.fileno())
    os.replace(stage,path)
    directory=os.open(path.parent,os.O_RDONLY)
    try:os.fsync(directory)
    finally:os.close(directory)
def sha256(path):
    value=hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):value.update(block)
    return value.hexdigest()
def config():return json.loads(checked(*compose("--profile","ops","--profile","redis","config","--format","json")))
def inspect(identity):return json.loads(checked("docker","inspect",identity))[0]
def running_project():
    name=config()["name"]
    ids=checked("docker","ps","-aq","--filter","label=com.docker.compose.project="+name).splitlines()
    return [inspect(identity) for identity in ids]
def mounted_volumes(configuration):return {k:v["name"] for k,v in configuration["volumes"].items() if k in ("mysql_data","minio_data","backend_state")}
def start_services(redis_enabled=False):
    profiles=["--profile","redis"] if redis_enabled else []
    checked(*compose(*profiles,"up","-d","--wait","--wait-timeout","300"))

class Maintenance:
    def __init__(self):self.path=ROOT/".maintenance.json";self.state=None
    def enter(self,purpose):
        require(not self.path.exists() and not self.path.is_symlink(),"Maintenance already active; recover the journal instead of starting a second operation")
        containers=running_project();configuration=config()
        require(all(any(c["Config"]["Labels"].get("com.docker.compose.service")==s and c["State"]["Running"] for c in containers) for s in ("mysql","minio","backend")),"Initialized running stack required")
        self.state={"formatVersion":2,"id":str(uuid.uuid4()),"token":uuid.uuid4().hex,"purpose":purpose,"phase":"ENTERING","enteredAt":utc(),"sourceProject":configuration["name"],"volumes":mounted_volumes(configuration),
                    "containers":[{"id":c["Id"],"service":c["Config"]["Labels"].get("com.docker.compose.service"),"running":c["State"]["Running"],"restart":c["HostConfig"]["RestartPolicy"]} for c in containers]}
        with self.path.open("x") as stream:os.chmod(self.path,0o600);json.dump(self.state,stream);stream.flush();os.fsync(stream.fileno())
        os.environ["COFFER_MAINTENANCE_TOKEN"]=self.state["token"]
        for item in self.state["containers"]:checked("docker","update","--restart=no",item["id"])
        self.persist("POLICIES_DISABLED")
        writers=[c["service"] for c in self.state["containers"] if c["running"] and c["service"] not in ("mysql","minio")]
        if writers:checked(*compose("--profile","redis","stop","-t","120",*writers))
        self.assert_writers_stopped()
        self.state["recoveryPointAt"]=utc();self.persist("QUIESCED")
        return self
    def resume_journal(self):
        require(self.path.is_file() and not self.path.is_symlink(),"Maintenance journal missing")
        self.state=json.loads(self.path.read_text());os.environ["COFFER_MAINTENANCE_TOKEN"]=self.state["token"];return self
    def persist(self,phase,**fields):self.state.update(phase=phase,**fields);atomic_json(self.path,self.state)
    def assert_writers_stopped(self,cold=False):
        own=config()["name"];volumes=set(self.state["volumes"].values())
        for identity in checked("docker","ps","-q").splitlines():
            item=inspect(identity);labels=item["Config"].get("Labels") or {};project=labels.get("com.docker.compose.project");service=labels.get("com.docker.compose.service")
            touches=any(m.get("Name") in volumes for m in item["Mounts"])
            if touches:require(project==own and not cold and service in ("mysql","minio"),"Unexpected active writer mounts checkpoint volumes")
            if project==own:require(not cold and service in ("mysql","minio"),"Project still has an active writer")
    def stop_data(self):
        checked(*compose("stop","-t","120","mysql","minio"));self.assert_writers_stopped(cold=True)
        for item in self.state["containers"]:
            if item["service"] in ("mysql","minio"):require(inspect(item["id"])["State"]["ExitCode"]!=137,"Data service was killed before clean shutdown")
        self.persist("COLD")
    def release(self):
        require(self.state["phase"] in ("BACKUP_VERIFIED","RESTORE_VERIFIED","UPGRADE_VERIFIED","ROLLBACK_VERIFIED"),"Cannot release an unverified maintenance barrier")
        # Recreated services use declared policies; original stopped containers retain their exact policies.
        for item in self.state["containers"]:
            try:
                old=inspect(item["id"])
                policy=item["restart"]["Name"] or "no"
                if policy=="on-failure" and item["restart"].get("MaximumRetryCount"):policy+=":"+str(item["restart"]["MaximumRetryCount"])
                checked("docker","update","--restart="+policy,item["id"])
            except subprocess.CalledProcessError:pass
        self.path.unlink();os.environ.pop("COFFER_MAINTENANCE_TOKEN",None)
    def fail(self,error):self.persist("RECOVERY_REQUIRED",errorType=type(error).__name__)

REQUIRED={"checkpoint/point.json","checkpoint/versions.json","checkpoint/images.tar","release/env","release/images.lock.env","release/secrets/master_key","release/secrets/minio_app_access","release/secrets/minio_app_secret","release/secrets/db_app_password","release/internal-tls/db/client-trust.p12"}
def safe_members(tar):
    names=set();total=0
    for member in tar:
        p=PurePosixPath(member.name)
        require(member.name and not p.is_absolute() and ".." not in p.parts and "\\" not in member.name and p.parts[0] in ("volumes","release","checkpoint"),"Unsafe archive path")
        require(member.name not in names,"Duplicate archive path");names.add(member.name)
        require(member.isdir() or member.isfile(),"Archive links/devices are prohibited")
        require(member.size>=0,"Invalid archive size");total+=member.size
        yield member
    require(REQUIRED<=names and all(any(n=="volumes/"+v or n.startswith("volumes/"+v+"/") for n in names) for v in ("mysql","minio","state")),"Incomplete consistent backup")
