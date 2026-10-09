#!/usr/bin/env python3
"""Preserve the union of the pinned official SDK privacy declarations as an app resource."""
import argparse
import json
from pathlib import Path
import plistlib

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--jpush-sdk", type=Path, required=True)
parser.add_argument("--jcore-sdk", type=Path, required=True)
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
metadata = json.loads((ROOT / "plugin.json").read_text())["native"]["ios"]
manifests = []
for name, directory in (("jpush", args.jpush_sdk), ("jcore", args.jcore_sdk)):
    framework = directory / f"{name}-ios-{metadata[name]}.xcframework"
    device = plistlib.loads((framework / "ios-arm64/PrivacyInfo.xcprivacy").read_bytes())
    simulator = plistlib.loads((framework / "ios-arm64_x86_64-simulator/PrivacyInfo.xcprivacy").read_bytes())
    if device != simulator:
        raise SystemExit(f"{name}: platform privacy declarations differ; review them before packaging")
    unknown = set(device) - {"NSPrivacyTracking", "NSPrivacyTrackingDomains", "NSPrivacyCollectedDataTypes", "NSPrivacyAccessedAPITypes"}
    if unknown:
        raise SystemExit(f"{name}: unsupported privacy fields: {sorted(unknown)}")
    manifests.append(device)

result = {"NSPrivacyTracking": any(m.get("NSPrivacyTracking", False) for m in manifests),
          "NSPrivacyTrackingDomains": sorted({domain for m in manifests for domain in m.get("NSPrivacyTrackingDomains", [])})}
for key, identity in (("NSPrivacyCollectedDataTypes", "NSPrivacyCollectedDataType"),
                      ("NSPrivacyAccessedAPITypes", "NSPrivacyAccessedAPIType")):
    entries = {}
    for manifest in manifests:
        for entry in manifest.get(key, []):
            existing = entries.setdefault(entry[identity], {})
            for field, value in entry.items():
                if isinstance(value, list):
                    existing[field] = sorted(set(existing.get(field, []) + value))
                elif isinstance(value, bool):
                    existing[field] = existing.get(field, False) or value
                elif field in existing and existing[field] != value:
                    raise SystemExit(f"Conflicting privacy declaration: {field}")
                else:
                    existing[field] = value
    result[key] = [entries[name] for name in sorted(entries)]

destination = ROOT / "native/ios/Sources/Avvessa/Resources/PrivacyInfo.xcprivacy"
encoded = plistlib.dumps(result, sort_keys=True)
if args.check:
    if not destination.is_file() or plistlib.loads(destination.read_bytes()) != result:
        raise SystemExit("Bundled privacy declarations differ from the pinned SDKs")
    print("Bundled privacy declarations match both pinned SDKs.")
else:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(encoded)
    print("Updated the bundled privacy declarations.")
