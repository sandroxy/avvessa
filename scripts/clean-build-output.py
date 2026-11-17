#!/usr/bin/env python3
"""Preview or reclaim completed local compiler output without invoking a toolchain."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SCOPES = ('native/android', 'adapters/android', '.build/ios-device', '.build/ios-simulator',
          '.build/uniapp', '.build/release', '.build/android-resource-tests',
          '.build/uniapp-consumer', '.build/documentation')
OUTPUTS = {'build', '.gradle', '.kotlin', '.cxx', '.externalNativeBuild', 'Build',
           'CompilationCache.noindex', 'Index.noindex', 'ModuleCache.noindex',
           'SDKStatCaches.noindex', 'objects'}
DEPENDENCIES = {'SourcePackages', 'Pods', 'node_modules', '.git', 'package', 'packages'}
REPORTS = {'Logs', 'reports', 'report', 'test-results', 'androidTest-results'}


def safe(path):
    path = Path(path).absolute()
    if ROOT not in path.parents or '..' in path.parts:
        raise ValueError('Output must stay below the product repository')
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Output path traverses a symbolic link: '+str(path))
    return path


def retained(relative):
    p = Path(relative)
    return (p.suffix in ('.log', '.xcactivitylog', '.jks', '.keystore', '.p12', '.p8', '.mobileprovision')
            or p.name in ('google-services.json', 'agconnect-services.json', 'local.properties', '.env')
            or '.local.' in p.name or p.name.startswith('.env.')
            or any(n in REPORTS or n.endswith('.xcresult') for n in p.parts[:-1]))


def walk(path):
    def failed(error):
        raise error
    for directory, names, leaves in os.walk(path, followlinks=False, onerror=failed):
        for name in list(names):
            if (Path(directory)/name).is_symlink():
                names.remove(name)
                leaves.append(name)
        for name in sorted(leaves):
            yield Path(directory)/name


def outputs():
    paths = []
    def failed(error):
        raise error
    for relative in SCOPES:
        scope = safe(ROOT/relative)
        if not scope.exists():
            continue
        if not scope.is_dir():
            raise ValueError('Expected a product build directory: '+str(scope))
        for directory, names, leaves in os.walk(scope, followlinks=False, onerror=failed):
            for name in leaves:
                if name in OUTPUTS and (Path(directory)/name).is_symlink():
                    safe(Path(directory)/name)
            for name in list(names):
                path = Path(directory)/name
                if name in DEPENDENCIES or name in REPORTS or name.endswith('.xcresult'):
                    names.remove(name)
                elif name in OUTPUTS:
                    safe(path)
                    names.remove(name)
                    if any(not retained(p.relative_to(path)) and p.name != '.DS_Store' for p in walk(path)):
                        paths.append(path)
                elif path.is_symlink():
                    names.remove(name)
    return sorted(set(paths))


def sha(path):
    if path.is_symlink():
        return hashlib.sha256(os.readlink(path).encode()).hexdigest()
    with path.open('rb') as stream:
        return stream_sha(stream)


def stream_sha(stream):
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(1024*1024), b''):
        value.update(block)
    return value.hexdigest()


def plan():
    if json.loads((ROOT/'plugin.json').read_text()).get('id') != 'avvessa':
        raise ValueError('Expected the Avvessa product repository')
    paths = outputs()
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    if any(ROOT/name == p or p in (ROOT/name).parents for p in paths for name in tracked if name):
        raise ValueError('Compiler output contains tracked inputs')
    records = []
    for path in paths:
        for p in walk(path):
            info = p.lstat()
            if not (stat.S_ISREG(info.st_mode) or stat.S_ISLNK(info.st_mode)):
                raise ValueError('Unexpected file in compiler output: '+str(p))
            records.append((p.relative_to(ROOT).as_posix(), info.st_mode, info.st_size,
                            info.st_mtime_ns, os.readlink(p) if p.is_symlink() else None))
    record = {
        'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'changes': hashlib.sha256(subprocess.check_output(['git', 'diff', 'HEAD', '--binary'], cwd=ROOT)).hexdigest(),
        'paths': [p.relative_to(ROOT).as_posix() for p in paths], 'files': sorted(records),
    }
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=ROOT).decode().split('\0')
    record['untrackedInputs'] = [(name, sha(safe(ROOT/name))) for name in untracked if name]
    return paths, hashlib.sha256(json.dumps(record, sort_keys=True).encode()).hexdigest()


def require_idle(paths):
    result = subprocess.run(['lsof', '-n', '-P', '-F', 'pn'], capture_output=True, text=True)
    if result.returncode not in (0, 1) or result.stderr.strip():
        raise ValueError('Cannot inspect processes using compiler output')
    for line in result.stdout.splitlines():
        if line.startswith('n/'):
            p = Path(line[1:])
            if any(p == target or target in p.parents for target in paths):
                raise ValueError('Compiler output is in use: '+str(p))


def preserve(paths, digest):
    files = {p.relative_to(ROOT).as_posix(): p for path in paths for p in walk(path)
             if retained(p.relative_to(path))}
    for p in files.values():
        safe(p)
        if not p.is_file():
            raise ValueError('Evidence must be a regular file: '+str(p))
    inventory = {name: (p.stat().st_mode, sha(p)) for name, p in files.items()}
    directory = safe(ROOT/'doc/dev/workspace-retention')
    directory.mkdir(parents=True, exist_ok=True)
    directory.chmod(0o700)
    destination = safe(directory/(digest+'.zip'))
    if not destination.exists():
        temporary = safe(destination.with_suffix('.tmp'))
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
            temporary.chmod(0o600)
            for name, p in sorted(files.items()):
                archive.write(p, name)
        temporary.replace(destination)
    with zipfile.ZipFile(destination) as archive:
        if archive.namelist() != sorted(files):
            raise ValueError('Preserved evidence inventory differs')
        for name, (mode, digest) in inventory.items():
            info = archive.getinfo(name)
            with archive.open(name) as stream:
                saved = stream_sha(stream)
            if info.external_attr >> 16 != mode or saved != digest:
                raise ValueError('Preserved evidence differs: '+name)
            if files[name].stat().st_mode != mode or sha(files[name]) != digest:
                raise ValueError('Evidence changed during preservation: '+name)
    return destination


@contextmanager
def locked():
    lock = safe(ROOT/'.build/cleanup.lock')
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open('a+') as handle:
        lock.chmod(0o600)
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('Product output cleanup is already running') from None
        yield


def clean(completed=None, apply=False):
    paths, digest = plan()
    print('Completed output plan: '+digest, flush=True)
    for p in paths:
        print(('Clear: ' if apply else 'Would clear: ')+str(p), flush=True)
    if completed is not None and completed != digest:
        raise ValueError('Source or compiler output changed since completion review')
    if not apply or not paths:
        return paths
    if completed is None:
        raise ValueError('Apply requires --completed-plan SHA256 from the reviewed preview')
    with locked():
        require_idle(paths)
        if plan()[1] != digest:
            raise ValueError('Source or compiler output changed before cleanup')
        archive_path = preserve(paths, digest)
        try:
            for path in paths:
                for directory, names, _ in os.walk(path, followlinks=False):
                    names[:] = [n for n in names if not (Path(directory)/n).is_symlink()]
                    p = Path(directory)
                    if p.stat().st_uid != os.getuid():
                        raise ValueError('Output belongs to another user: '+str(p))
                    p.chmod(stat.S_IMODE(p.stat().st_mode) | 0o700)
                shutil.rmtree(path)
        finally:
            # Interrupted deletion must not erase original evidence paths.
            with zipfile.ZipFile(archive_path) as archive:
                for info in archive.infolist():
                    p = safe(ROOT/info.filename)
                    p.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(info) as source, p.open('wb') as destination:
                        shutil.copyfileobj(source, destination)
                    p.chmod(stat.S_IMODE(info.external_attr >> 16))
        print('Preserved evidence: '+str(archive_path), flush=True)
    return paths


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--completed-plan', metavar='SHA256')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    try:
        clean(args.completed_plan, args.apply)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit(str(error))
