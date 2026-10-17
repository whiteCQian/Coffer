"""Compile only the new viewing classes against the exact shipped V44 dependencies."""
import pathlib, subprocess, sys, zipfile, shutil
repo=pathlib.Path(__file__).resolve().parents[2]
output=pathlib.Path(sys.argv[1]).resolve()
if not output.is_relative_to(repo/'.test-tmp') or output.exists(): raise RuntimeError('Use fresh repository-local output')
output.mkdir(parents=True)
jar=repo/'desktop/dist/win-unpacked/resources/coffer/backend/coffer-backend.jar'
extracted=output/'shipped'
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.startswith(('BOOT-INF/classes/','BOOT-INF/lib/')):
            target=extracted/name
            if not target.resolve().is_relative_to(extracted): raise RuntimeError('Invalid archive path')
            z.extract(name,extracted)
classes=output/'classes';classes.mkdir()
classpath=str(extracted/'BOOT-INF/classes')+';'+str(extracted/'BOOT-INF/lib/*')
javac=r'C:\Program Files\Java\jdk-25.0.3\bin\javac.exe'
sources=[repo/'backend/src/main/java/com/coffer/desktop'/name for name in ['FileViewService.java','FileViewController.java']]
subprocess.run([javac,'--release','17','-encoding','UTF-8','-parameters','-cp',classpath,'-d',str(classes),*map(str,sources)],check=True)
target=output/'backend/coffer-backend.jar';target.parent.mkdir();shutil.copyfile(jar,target)
with zipfile.ZipFile(target,'a',compression=zipfile.ZIP_DEFLATED) as z:
    for source in classes.rglob('*.class'):
        name='BOOT-INF/classes/'+source.relative_to(classes).as_posix()
        if name in z.namelist(): raise RuntimeError('Class already exists; use a versioned replacement repair')
        z.write(source,name)
(output/'classpath.txt').write_text(str(classes)+';'+classpath,encoding='utf-8')
print('Compiled new viewing classes against shipped V44; existing JAR entries preserved.')
