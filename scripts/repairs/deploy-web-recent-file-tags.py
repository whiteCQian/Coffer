"""Install a verified static UI patch on the existing isolated test deployment."""
import fcntl
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request


ROOT = Path('/opt/coffer-test')
DEPLOY = ROOT / 'deploy'
WEB = ROOT / 'build-artifacts/web'
STAGE = Path(__file__).resolve().parent
PLAN = json.loads((STAGE / 'patch.json').read_text())


def run(*args):
    return subprocess.check_output(args, cwd=DEPLOY, text=True).strip()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def get(relative=''):
    with urllib.request.urlopen('http://127.0.0.1:18080/' + relative, timeout=20) as response:
        return response.read(), dict(response.headers)


def compose(*args):
    subprocess.run(['sh', 'coffer-test.sh', *args], cwd=DEPLOY, check=True)


def main():
    assert PLAN['repair'] in ('web-recent-opened-file-tags-2026-10-17', 'web-home-remove-tag-card-2026-10-17', 'web-upload-feedback-2026-10-17', 'web-content-layout-2026-10-18', 'web-reader-controls-2026-10-18', 'web-home-viewport-2026-10-18', 'web-upload-state-2026-10-18', 'web-archive-actions-2026-10-18', 'web-pane-layout-2026-10-18', 'web-eight-recent-tags-2026-10-18', 'web-chat-size-icons-2026-10-18', 'web-chat-drawn-icons-2026-10-18')
    assert WEB.is_dir() and (DEPLOY / 'coffer-test.sh').is_file()
    with Path('/run/lock/coffer-test-web-update.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        html, _ = get()
        assert PLAN['originalJs'] in html.decode() and PLAN['originalCss'] in html.decode(), 'Live version changed'
        for filename, field in [(PLAN['originalJs'], 'originalJsHash'), (PLAN['originalCss'], 'originalCssHash')]:
            content, _ = get('assets/' + filename)
            assert digest(content) == PLAN[field], 'Live resource changed'
            assert digest((WEB / 'assets' / filename).read_bytes()) == PLAN[field], 'Build input changed'
        for entry in PLAN['files']:
            relative = Path(entry['path'])
            assert relative == Path('index.html') or (len(relative.parts) == 2 and relative.parts[0] == 'assets')
            assert '..' not in relative.parts
            assert digest((STAGE / 'build-artifacts/web' / relative).read_bytes()) == entry['sha256']
            assert relative == Path('index.html') or not (WEB / relative).exists(), 'Use a fresh patch version'
        backend_classes = PLAN.get('backendClasses', [])
        backend_root = ROOT / 'build-artifacts/backend/BOOT-INF/classes'
        if backend_classes:
            allowed_backend = {
                'web-upload-feedback-2026-10-17': {'com/coffer/task/application/TaskFileLookupService.class', 'com/coffer/task/api/TaskFileController.class'},
                'web-archive-actions-2026-10-18': {'com/coffer/governance/api/ArchiveSavedFileController.class', 'com/coffer/governance/api/dto/ArchiveSavedFileRequest.class', 'com/coffer/governance/application/ArchiveSavedFileService.class'},
            }
            assert PLAN['repair'] in allowed_backend
            assert {e['path'] for e in backend_classes} == allowed_backend[PLAN['repair']]
            for entry in backend_classes:
                relative = Path(entry['path'])
                assert not (backend_root / relative).exists(), 'Backend class already exists'
                assert digest((STAGE / 'build-artifacts/backend/BOOT-INF/classes' / relative).read_bytes()) == entry['sha256']
            for entry in PLAN['backendFingerprints']:
                assert entry['path'] in ('com/coffer/file/application/FileService.class', 'com/coffer/file/infrastructure/persistence/FileMetadataRepository.class', 'com/coffer/auth/service/OwnerAuthorizationAspect.class', 'com/coffer/governance/application/GovernancePreviewService.class', 'com/coffer/governance/application/ArchiveObjectNameService.class')
                assert digest((backend_root / entry['path']).read_bytes()) == entry['sha256'], 'Backend release changed'
        services = ['mysql', 'minio', 'redis', 'capacity'] + ([] if backend_classes else ['backend'])
        before = {name: run('docker', 'inspect', '--format', '{{.Id}}', 'coffer-test-' + name + '-1') for name in services}
        old_image = run('docker', 'inspect', '--format', '{{.Image}}', 'coffer-test-proxy-1')
        stamp = time.strftime('%Y%m%d-%H%M%S')
        backup = Path('/root/coffer-test-backups') / ('web-ux-' + stamp)
        backup.mkdir(mode=0o700, parents=True)
        shutil.copy2(WEB / 'index.html', backup / 'index.html')
        rollback_tag = 'coffer/web:before-web-ux-' + stamp
        run('docker', 'tag', old_image, rollback_tag)
        backend_rollback = None
        if backend_classes:
            backend_rollback = 'coffer/backend:before-upload-ux-' + stamp
            run('docker', 'tag', run('docker', 'inspect', '--format', '{{.Image}}', 'coffer-test-backend-1'), backend_rollback)
        receipt = {'repair': PLAN['repair'], 'backup': str(backup), 'rollbackImage': rollback_tag,
                   'previousImage': old_image, 'untouchedContainers': before, 'files': PLAN['files'], 'backendRollbackImage': backend_rollback}
        (backup / 'rollback.json').write_text(json.dumps(receipt, indent=2))
        try:
            # Assets first, index last. Older open browser tabs keep their original assets.
            for entry in sorted(PLAN['files'], key=lambda entry: entry['path'] == 'index.html'):
                relative = Path(entry['path'])
                shutil.copy2(STAGE / 'build-artifacts/web' / relative, WEB / relative)
            for entry in backend_classes:
                relative = Path(entry['path']); (backend_root / relative).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(STAGE / 'build-artifacts/backend/BOOT-INF/classes' / relative, backend_root / relative)
            compose('build', *(['backend', 'proxy'] if backend_classes else ['proxy']))
            if backend_classes:
                compose('up', '-d', '--no-deps', '--no-build', '--wait', '--wait-timeout', '180', 'backend')
            compose('up', '-d', '--no-deps', '--no-build', '--wait', '--wait-timeout', '120', 'proxy')
            for entry in PLAN['files']:
                content, headers = get('' if entry['path'] == 'index.html' else entry['path'])
                assert digest(content) == entry['sha256'], 'Served file checksum differs'
                if entry['path'] == 'index.html':
                    assert 'no-cache' in headers.get('Cache-Control', ''), 'HTML must refresh'
                else:
                    assert 'immutable' in headers.get('Cache-Control', ''), 'New assets must retain caching'
            status, _ = get('api/auth/status')
            assert json.loads(status)['code'] == 0
            after = {name: run('docker', 'inspect', '--format', '{{.Id}}', 'coffer-test-' + name + '-1') for name in services}
            assert after == before, 'Non-web service changed'
            receipt.update({'status': 'PASS', 'newImage': run('docker', 'inspect', '--format', '{{.Image}}', 'coffer-test-proxy-1'),
                            'servedFilesVerified': len(PLAN['files']), 'otherContainersUnchanged': True,
                            'backendReadOnlyLookupAdded': bool(backend_classes) and PLAN['repair'] == 'web-upload-feedback-2026-10-17',
                            'savedFileArchiveAdded': bool(backend_classes) and PLAN['repair'] == 'web-archive-actions-2026-10-18'})
        except Exception:
            shutil.copy2(backup / 'index.html', WEB / 'index.html')
            run('docker', 'tag', rollback_tag, 'coffer/web:2026.10.12-test')
            if backend_rollback:
                for entry in backend_classes:
                    (backend_root / entry['path']).unlink(missing_ok=True)
                run('docker', 'tag', backend_rollback, 'coffer/backend:2026.10.12-test')
                compose('up', '-d', '--no-deps', '--no-build', '--wait', '--wait-timeout', '180', 'backend')
            compose('up', '-d', '--no-deps', '--no-build', '--wait', '--wait-timeout', '120', 'proxy')
            raise
        (STAGE / 'verification.json').write_text(json.dumps(receipt, indent=2))
        print(json.dumps(receipt, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
