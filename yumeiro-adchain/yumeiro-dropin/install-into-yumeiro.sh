#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 /path/to/yumeiro"
  exit 1
fi

YUMEIRO_DIR="$(cd "$1" && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADCHAIN_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -f "$YUMEIRO_DIR/app/build.gradle.kts" ]; then
  echo "Error: $YUMEIRO_DIR does not look like the Yumeiro repository root."
  exit 1
fi

TARGET_ADCHAIN_DIR="$YUMEIRO_DIR/app/src/main/java/dev/asfakul/yumeiro/adchain"
mkdir -p "$TARGET_ADCHAIN_DIR/android"
mkdir -p "$YUMEIRO_DIR/app/src/main/java/dev/asfakul/yumeiro/ui/screens/online"
mkdir -p "$YUMEIRO_DIR/app/src/main/java/dev/asfakul/yumeiro/stream"

cp "$ADCHAIN_ROOT/src/main/kotlin/dev/asfakul/yumeiro/adchain/"*.kt "$TARGET_ADCHAIN_DIR/"
cp "$ADCHAIN_ROOT/android-integration/AdChainWebViewRunner.kt" "$TARGET_ADCHAIN_DIR/android/"
cp "$SCRIPT_DIR/app/src/main/java/dev/asfakul/yumeiro/ui/screens/online/AdChainGateHost.kt" \
   "$YUMEIRO_DIR/app/src/main/java/dev/asfakul/yumeiro/ui/screens/online/"
cp "$SCRIPT_DIR/app/src/main/java/dev/asfakul/yumeiro/stream/YumeiroAdChainBridge.kt" \
   "$YUMEIRO_DIR/app/src/main/java/dev/asfakul/yumeiro/stream/"

echo "Copied adchain sources into $YUMEIRO_DIR/app/src/main/java/dev/asfakul/yumeiro/."

if git -C "$YUMEIRO_DIR" apply --check "$SCRIPT_DIR/yumeiro-integration.patch" 2>/dev/null; then
  git -C "$YUMEIRO_DIR" apply "$SCRIPT_DIR/yumeiro-integration.patch"
  echo "Applied yumeiro-integration.patch cleanly!"
else
  echo "Patch offset shifted on your branch — apply the 2 tiny hooks manually (see yumeiro-integration.patch)."
fi
