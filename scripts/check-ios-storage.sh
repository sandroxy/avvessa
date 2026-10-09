#!/usr/bin/env bash
set -euo pipefail
AVVESSA_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AVVESSA_OUTPUT="$AVVESSA_ROOT/.build/storage-checks"
mkdir -p "$AVVESSA_OUTPUT"
xcrun --sdk macosx clang -fobjc-arc -fmodules -Wall -Wextra -mmacosx-version-min=11.0 \
  -Wno-unused-parameter -framework Foundation -framework UserNotifications \
  "$AVVESSA_ROOT/native/ios/Sources/Avvessa/AVPushTypes.m" \
  "$AVVESSA_ROOT/native/ios/Sources/Avvessa/AVEventStore.m" \
  "$AVVESSA_ROOT/native/ios/Tests/StorageChecks.m" \
  -o "$AVVESSA_OUTPUT/checks"
"$AVVESSA_OUTPUT/checks"
