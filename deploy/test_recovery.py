"""Archive attack cases and restart barriers; no Docker mutation required."""
import io
import json
import os
import shutil
import subprocess
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import preflight
from recovery_common import REQUIRED, safe_members

class RecoveryTests(unittest.TestCase):
    def test_restore_rejects_insufficient_data_volume_even_with_large_workspace(self):
        from web_restore import require_volume_capacity
        rows='Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/db 9000000 0 8000000 0% /volumes/mysql\n/dev/s3 9000000 0 10 99% /volumes/minio\n/dev/state 9000000 0 8000000 0% /volumes/state\n'
        capacity={'volumeBytesByMount':{'/volumes/mysql':100,'/volumes/minio':100,'/volumes/state':100}}
        with self.assertRaises(SystemExit):require_volume_capacity(rows,capacity,100)
        require_volume_capacity(rows.replace('0 10 99%','0 8000000 0%'),capacity,100)
    def test_restore_preserves_business_configuration_and_isolates_new_server(self):
        from web_restore import recovered_environment
        original='# source checkpoint\nCOMPOSE_PROJECT_NAME=original\nPROXY_IP=172.30.1.2\nCOFFER_DOMAIN=old.test\nCOFFER_WEB_LIMITS_ACCOUNT_BYTES=123\nCOFFER_RELEASE=old-release\n'
        restored=recovered_environment(original,{'COMPOSE_PROJECT_NAME':'isolated','PROXY_IP':'172.30.2.2','COFFER_DOMAIN':'new.test','COFFER_WEB_LIMITS_ACCOUNT_BYTES':'999','COFFER_RELEASE':'unrelated'})
        self.assertIn('COMPOSE_PROJECT_NAME=isolated',restored)
        self.assertIn('PROXY_IP=172.30.2.2',restored)
        self.assertIn('COFFER_DOMAIN=new.test',restored)
        self.assertIn('COFFER_WEB_LIMITS_ACCOUNT_BYTES=123',restored)
        self.assertIn('COFFER_RELEASE=old-release',restored)
        self.assertIn('# source checkpoint',restored)
    def archive(self, extra=None, omit=None):
        stream=io.BytesIO()
        with tarfile.open(fileobj=stream,mode="w") as tar:
            for name in sorted((REQUIRED|{"volumes/mysql","volumes/minio","volumes/state"})-{omit}):
                member=tarfile.TarInfo(name)
                if name.startswith("volumes/"):member.type=tarfile.DIRTYPE
                tar.addfile(member)
            if extra:tar.addfile(extra)
        stream.seek(0);return tarfile.open(fileobj=stream)
    def test_complete_archive(self):
        with self.archive() as archive:self.assertGreater(len(list(safe_members(archive))),3)
    def test_reject_path_escape_duplicate_and_links(self):
        for path in ("/etc/shadow","volumes/../../etc/shadow","volumes\\escape","release/secrets/master_key"):
            with self.archive(tarfile.TarInfo(path)) as archive:
                with self.assertRaises(SystemExit):list(safe_members(archive))
        for kind in (tarfile.SYMTYPE,tarfile.LNKTYPE,tarfile.CHRTYPE):
            member=tarfile.TarInfo("volumes/mysql/host-link");member.type=kind;member.linkname="/etc/shadow"
            with self.archive(member) as archive:
                with self.assertRaises(SystemExit):list(safe_members(archive))
    def test_reject_incomplete_checkpoint(self):
        for name in REQUIRED:
            with self.archive(omit=name) as archive:
                with self.assertRaises(SystemExit):list(safe_members(archive))
    def test_persistent_barrier_blocks_unrelated_restart(self):
        with tempfile.TemporaryDirectory() as directory,patch.object(preflight,"ROOT",Path(directory)),patch.dict(os.environ,{},clear=True):
            (Path(directory)/".maintenance.json").write_text(json.dumps({"token":"operation"}))
            for command in ("up","start","restart","run"):
                with self.assertRaises(SystemExit):preflight.compose(command)
            self.assertIn("ps",preflight.compose("ps"))
            os.environ["COFFER_MAINTENANCE_TOKEN"]="operation"
            self.assertIn("up",preflight.compose("up"))

    @unittest.skipUnless(shutil.which("gpg"),"GPG required")
    def test_authenticated_manifest_wrong_password_and_ciphertext_tamper(self):
        from web_backup import validate_archive,sha256_json
        point={"formatVersion":2,"tables":{},"files":[],"objects":[],"masterKeyId":"test","authenticatedSecrets":0,"decryptedSecretDigest":"test"}
        versions={"formatVersion":2,"pointSha256":sha256_json(point)}
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);key=root/'key';wrong=root/'wrong';key.write_text('correct-test-key'*4);wrong.write_text('wrong-test-key'*4)
            plain=root/'test.tar';encrypted=root/'test.tar.gpg'
            with tarfile.open(plain,mode='w') as tar:
                for name in sorted(REQUIRED|{'volumes/mysql','volumes/minio','volumes/state'}):
                    member=tarfile.TarInfo(name);data=b''
                    if name=='checkpoint/point.json':data=json.dumps(point).encode()
                    elif name=='checkpoint/versions.json':data=json.dumps(versions).encode()
                    if name.startswith('volumes/'):member.type=tarfile.DIRTYPE
                    member.size=len(data);tar.addfile(member,io.BytesIO(data))
            subprocess.run(['gpg','--batch','--pinentry-mode','loopback','--passphrase-file',str(key),'--symmetric','--cipher-algo','AES256','--output',str(encrypted),str(plain)],check=True,stderr=subprocess.DEVNULL)
            self.assertEqual(validate_archive(encrypted,key),(point,versions))
            with self.assertRaises((SystemExit,tarfile.ReadError)):validate_archive(encrypted,wrong)
            tampered=bytearray(encrypted.read_bytes());tampered[-12]^=1;encrypted.write_bytes(tampered)
            with self.assertRaises((SystemExit,tarfile.ReadError)):validate_archive(encrypted,key)

if __name__=="__main__":unittest.main()
