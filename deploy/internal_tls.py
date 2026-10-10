#!/usr/bin/env python3
"""Generate separate DB/MinIO private CAs and leaf certificates; CA passwords stay off-host release.
Run AFTER building backend/mysql images, before preflight. Never overwrite existing PKI."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
from preflight import ROOT, envfile, checked, require

def command(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def generate(root, name, passphrase, uid):
    directory = root / name
    server = directory / "server"
    server.mkdir(parents=True)
    command("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072", "-aes-256-cbc", "-pass", "file:"+str(passphrase), "-out", str(directory/"ca-key.enc.pem"))
    command("openssl", "req", "-x509", "-new", "-key", str(directory/"ca-key.enc.pem"), "-passin", "file:"+str(passphrase), "-sha256", "-days", "3650", "-subj", "/CN=Coffer-"+name+"-CA", "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-out", str(directory/"ca.pem"))
    command("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:3072", "-out", str(server/"server.key"))
    command("openssl", "req", "-new", "-key", str(server/"server.key"), "-subj", "/CN="+name, "-out", str(directory/"server.csr"))
    config=directory/"leaf.cnf"
    config.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:"+name+",IP:127.0.0.1\n")
    command("openssl", "x509", "-req", "-in", str(directory/"server.csr"), "-CA", str(directory/"ca.pem"), "-CAkey", str(directory/"ca-key.enc.pem"), "-passin", "file:"+str(passphrase), "-CAcreateserial", "-sha256", "-days", "365", "-extfile", str(config), "-out", str(server/"server.crt"))
    shutil.copyfile(directory/"ca.pem",server/"ca.pem")
    if name=="minio":
        (server/"CAs").mkdir()
        shutil.copyfile(directory/"ca.pem",server/"CAs/ca.pem")
        (server/"public.crt").write_bytes((server/"server.crt").read_bytes()+(directory/"ca.pem").read_bytes())
        (server/"server.key").rename(server/"private.key")
    for path in directory.rglob("*"):
        os.chmod(path, 0o750 if path.is_dir() else 0o440)
        os.chown(path,0,uid if path.is_relative_to(server) else 10001)
    os.chmod(directory/"ca-key.enc.pem",0o400)
    os.chmod(directory,0o750); os.chown(directory,0,10001)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--db-ca-passphrase-file",type=Path,required=True)
    parser.add_argument("--minio-ca-passphrase-file",type=Path,required=True)
    args=parser.parse_args()
    require(os.name=="posix" and os.geteuid()==0,"Run as root on Linux target")
    require(args.db_ca_passphrase_file.resolve()!=args.minio_ca_passphrase_file.resolve(),"Database and MinIO CA keys require separate passphrase files")
    require(args.db_ca_passphrase_file.read_bytes()!=args.minio_ca_passphrase_file.read_bytes(),"Database and MinIO CA passphrases must differ")
    for key in (args.db_ca_passphrase_file,args.minio_ca_passphrase_file):
        require(key.is_file() and not key.is_symlink() and key.stat().st_uid==0 and key.stat().st_mode & 0o077==0 and key.stat().st_size>=32 and not key.resolve().is_relative_to(ROOT),"CA passphrases must be root-only and stored outside deployment")
    root=ROOT/"internal-tls"
    require(not root.exists() and not root.is_symlink(),"Existing internal PKI found; no automatic overwrite/rotation")
    os.umask(0o077); root.mkdir(); os.chmod(root,0o750); os.chown(root,0,10001)
    env=envfile(ROOT/".env"); locks=envfile(ROOT/"images.lock.env")
    uid=int(checked("docker","run","--rm","--entrypoint","id",locks["MYSQL_IMAGE"],"-u","mysql"))
    generate(root,"mysql",args.db_ca_passphrase_file,uid)
    # Config convention uses db; the DNS SAN remains mysql.
    (root/"mysql").rename(root/"db")
    generate(root,"minio",args.minio_ca_passphrase_file,10001)
    command("docker","run","--rm","--user","0:0","-v",str(root/"db")+":/work","-v",str(ROOT/"secrets/db_trust_password")+":/run/trust-password:ro","--entrypoint","keytool","coffer/backend:"+env["COFFER_RELEASE"],"-importcert","-noprompt","-alias","coffer-db","-file","/work/ca.pem","-keystore","/work/client-trust.p12","-storetype","PKCS12","-storepass:file","/run/trust-password")
    os.chown(root/"db/client-trust.p12",0,10001); os.chmod(root/"db/client-trust.p12",0o440)
    print("Separate database/MinIO PKI generated; server private keys are mounted only in their own services.")

if __name__=="__main__": main()
