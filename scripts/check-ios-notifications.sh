#!/usr/bin/env bash
set -euo pipefail
AVVESSA_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AVVESSA_OUTPUT="$AVVESSA_ROOT/.build/notification-checks"
mkdir -p "$AVVESSA_OUTPUT"
for AVVESSA_CHECK in NotificationPolicy NotificationDelegate; do
  xcrun --sdk macosx clang -fobjc-arc -fmodules -Wall -Wextra -Wno-unused-parameter \
    -mmacosx-version-min=11.0 -framework Foundation -framework UserNotifications \
    "$AVVESSA_ROOT/native/ios/Sources/Avvessa/AVPushTypes.m" \
    "$AVVESSA_ROOT/native/ios/Sources/Avvessa/AVNotificationPolicy.m" \
    "$AVVESSA_ROOT/native/ios/Sources/Avvessa/AVNotificationDelegate.m" \
    "$AVVESSA_ROOT/native/ios/Tests/${AVVESSA_CHECK}Checks.m" \
    -o "$AVVESSA_OUTPUT/$AVVESSA_CHECK"
  "$AVVESSA_OUTPUT/$AVVESSA_CHECK" "$AVVESSA_ROOT/tests/notification-policy.json"
done
