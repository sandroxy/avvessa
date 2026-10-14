#!/usr/bin/env python3
"""Check the public native dependency contract without running a device or SDK."""
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
metadata = json.loads((root / "plugin.json").read_text())
ios = metadata["native"]["ios"]
package = (root / "Package.swift").read_text()
for name in ("jpush", "jcore"):
    expression = rf'\.package\(url: "https://github\.com/jpush/{name}-sdk\.git", exact: "{re.escape(ios[name])}"\)'
    if not re.search(expression, package):
        sys.exit(f"{name}: Swift Package version differs from plugin.json")
pins = {item["identity"]: item["state"]["version"] for item in json.loads((root / "Package.resolved").read_text())["pins"]}
for name in ("jpush", "jcore"):
    if pins[name + "-sdk"] != ios[name]:
        sys.exit(f"{name}: resolved package version differs")
for filename in ("README.md", "README-EN.md", "DEVELOPMENT.md", "RELEASING.md", "CHANGELOG.md", "doc/contract.md",
                 "native/android/README.md", "native/ios/README.md", "THIRD_PARTY_NOTICES.md", "adapters/uniapp/README.md", "adapters/react-native/README.md"):
    text = (root / filename).read_text()
    for target in re.findall(r"\]\(([^)]+)\)", text):
        if "://" in target or target.startswith("#"):
            continue
        path = root / Path(filename).parent / target.split("#")[0]
        if not path.exists():
            sys.exit(f"{filename}: missing local link {target}")
uni = json.loads((root / metadata['adapters']['uniAppUts']['sourceRoot'] / 'package.json').read_text())
if uni['id'] != metadata['adapters']['uniAppUts']['id'] or uni['version'] != metadata['version']:
    sys.exit('UNI package identity or version differs from plugin.json')
legacy = json.loads((root / 'adapters/uniapp/packaging/package.template.json').read_text().replace('@VERSION@', metadata['version']))
if legacy['id'] != metadata['adapters']['uniApp']['id'] or legacy['version'] != metadata['version']:
    sys.exit('Legacy UNI package identity or version differs from plugin.json')
result = subprocess.run(["git", "check-ignore", "--quiet", "doc/dev/"], cwd=root, check=False)
if result.returncode != 0:
    sys.exit("Local development notes must remain ignored")
tracked = subprocess.check_output(["git", "ls-files", "doc/dev"], cwd=root, text=True)
if tracked:
    sys.exit("Development notes must not be tracked")
rn = json.loads((root / 'adapters/react-native/package.json').read_text())
if rn['name'] != metadata['adapters']['reactNative']['package'] or rn['version'] != metadata['version'] or rn.get('private'):
    sys.exit('RN package identity, version or publication status differs from plugin.json')
if rn['peerDependencies']['react-native'] != '>=' + metadata['adapters']['reactNative']['minimumReactNative']:
    sys.exit('RN peer requirement differs from plugin.json')
print("Native/UNI/RN dependency metadata, public links and development-note exclusion are consistent.")

policy = json.loads((root / 'release-policy.json').read_text())
if policy['plugin'] != metadata['id'] or policy['sourceRepository'] != metadata['repository']:
    sys.exit('Release policy identity differs')
if '## '+metadata['version'] not in (root/'CHANGELOG.md').read_text().splitlines():
    sys.exit('Missing release changelog')
