#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
OUT_APK="$ROOT_DIR/FlickpayPOS-Android.apk"

cd "$ROOT_DIR"
./gradlew assembleDebug
cp -f "$APP_APK" "$OUT_APK"

echo "Updated: $OUT_APK"
