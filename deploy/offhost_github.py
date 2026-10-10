#!/usr/bin/env python3
"""Private off-host ciphertext transport. Uses GitHub credentials, never production DB/MinIO keys.
Uploads chunks below asset limits, downloads every byte for SHA-256 verification, then publishes
the release within the private repository. Recovery passwords are never read by this module."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import datetime as dt

def credential():
    token=os.environ.get("GITHUB_TOKEN")
    if token:return token
    environment=dict(os.environ,GIT_TERMINAL_PROMPT="0",GCM_INTERACTIVE="never")
    result=subprocess.run(["git","credential","fill"],input="protocol=https\nhost=github.com\n\n",text=True,capture_output=True,env=environment)
    values=dict(line.split("=",1) for line in result.stdout.splitlines() if "=" in line)
    if not values.get("password"):raise SystemExit("Independent GitHub credential unavailable")
    return values["password"]
class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,message,headers,url):
        host=urllib.parse.urlparse(url).hostname or ""
        if host!="api.github.com" and not host.endswith(".githubusercontent.com"):raise SystemExit("Unexpected backup download redirect")
        result=super().redirect_request(request,fp,code,message,headers,url)
        if host!="api.github.com":result.remove_header("Authorization")
        return result
class Github:
    def __init__(self):self.token=credential();self.http=urllib.request.build_opener(Redirect())
    def request(self,url,method="GET",body=None,accept="application/vnd.github+json",length=None):
        headers={"Authorization":"Bearer "+self.token,"Accept":accept,"User-Agent":"Coffer-independent-encrypted-backup","X-GitHub-Api-Version":"2026-03-10"}
        if isinstance(body,dict):body=json.dumps(body).encode();headers["Content-Type"]="application/json"
        elif body is not None:headers["Content-Type"]="application/octet-stream";headers["Content-Length"]=str(length)
        return self.http.open(urllib.request.Request(url,data=body,headers=headers,method=method),timeout=900)
    def api(self,path,method="GET",body=None):
        with self.request("https://api.github.com"+path,method,body) as response:return json.load(response)
def upload(archive,repo_name="coffer-encrypted-backups"):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}",repo_name):raise SystemExit("Use one repository name in the authenticated account")
    archive=Path(archive);envelope=json.loads(archive.with_suffix(".json").read_text())
    if not envelope.get("verified"):raise SystemExit("Only a verified encrypted backup can be uploaded")
    sha=hashlib.sha256()
    with archive.open("rb") as stream:
        for part in iter(lambda:stream.read(1024*1024),b""):sha.update(part)
    if sha.hexdigest()!=envelope["sha256"]:raise SystemExit("Local ciphertext checksum mismatch")
    client=Github();owner=client.api("/user")["login"]
    path="/repos/"+owner+"/"+repo_name
    try:repo=client.api(path)
    except urllib.error.HTTPError as error:
        if error.code!=404:raise
        repo=client.api("/user/repos","POST",{"name":repo_name,"private":True,"auto_init":True,"description":"Encrypted Coffer recovery artifacts. Recovery passwords remain separate; no plaintext production access tokens are uploaded."})
    if not repo.get("private") or repo["owner"]["login"]!=owner:raise SystemExit("Backup repository must be private and owned by the authenticated user")
    release=client.api(path+"/releases","POST",{"tag_name":"checkpoint-"+envelope["id"],"name":"Coffer encrypted checkpoint "+envelope["id"],"body":"Authenticated static encrypted backup; recovery password is held separately. Ciphertext download was verified before this private release was published.","draft":True})
    assets=[];aggregate=hashlib.sha256()
    with tempfile.TemporaryDirectory(prefix="coffer-ciphertext-") as temporary,archive.open("rb") as source:
        index=0
        while True:
            file=Path(temporary)/(archive.name+".part"+str(index).zfill(4));remaining=1024*1024*1024;size=0;local=hashlib.sha256()
            with file.open("xb") as output:
                while remaining:
                    block=source.read(min(1024*1024,remaining))
                    if not block:break
                    output.write(block);local.update(block);size+=len(block);remaining-=len(block)
            if not size:break
            url=release["upload_url"].split("{")[0]+"?name="+urllib.parse.quote(file.name)
            with file.open("rb") as data,client.request(url,"POST",data,length=size) as response:asset=json.load(response)
            remote=hashlib.sha256();read=0
            with client.request(asset["url"],accept="application/octet-stream") as download:
                for block in iter(lambda:download.read(1024*1024),b""):remote.update(block);aggregate.update(block);read+=len(block)
            if read!=size or remote.hexdigest()!=local.hexdigest():raise SystemExit("Off-host ciphertext readback mismatch; release remains draft")
            assets.append({"name":file.name,"sizeBytes":size,"sha256":local.hexdigest(),"assetId":asset["id"]});index+=1
    if aggregate.hexdigest()!=envelope["sha256"]:raise SystemExit("Reassembled off-host digest mismatch")
    manifest={"formatVersion":2,"backupId":envelope["id"],"sha256":envelope["sha256"],"sizeBytes":envelope["sizeBytes"],"parts":assets}
    data=json.dumps(manifest,indent=2).encode()
    with client.request(release["upload_url"].split("{")[0]+"?name=ciphertext-manifest.json","POST",data,length=len(data)) as response:json.load(response)
    published=client.api(path+"/releases/"+str(release["id"]),"PATCH",{"draft":False})
    receipt={"verifiedAt":dt.datetime.now(dt.timezone.utc).isoformat(),"independentHost":True,"productionCredentialsUsed":False,"repositoryPrivate":True,"repository":repo["full_name"],"releaseUrl":published["html_url"],"backupId":envelope["id"],"sha256":envelope["sha256"],"downloadedAndVerified":True,"parts":assets}
    archive.with_suffix(".offhost.json").write_text(json.dumps(receipt,indent=2))
    envelope["offHostVerified"]=True;envelope["offHostReceipt"]=receipt;archive.with_suffix(".json").write_text(json.dumps(envelope,indent=2))
    return receipt
if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("archive",type=Path);parser.add_argument("--repository",default="coffer-encrypted-backups")
    args=parser.parse_args();print(json.dumps(upload(args.archive,args.repository),indent=2))
