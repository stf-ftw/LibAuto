#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASE_ROOT="${LIBAUTO_RELEASE_ROOT:-$ROOT_DIR/release}"
KEYSTORE="${LIBAUTO_KEYSTORE_FILE:-$RELEASE_ROOT/signing/libauto-upload.jks}"
ALIAS="${LIBAUTO_KEY_ALIAS:-libauto-upload}"
STOREPASS="${LIBAUTO_KEYSTORE_PASSWORD:?Set LIBAUTO_KEYSTORE_PASSWORD}"
KEYPASS="${LIBAUTO_KEY_PASSWORD:-$STOREPASS}"

ANDROID_HOME="${ANDROID_HOME:?Set ANDROID_HOME}"
JAVA_HOME="${JAVA_HOME:?Set JAVA_HOME}"
ZIPALIGN="$ANDROID_HOME/build-tools/35.0.0/zipalign"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
JARSIGNER="$JAVA_HOME/bin/jarsigner"
ART_DIR="$RELEASE_ROOT/artifacts"

mkdir -p "$ART_DIR"

for flavor in compatibility play; do
  unsigned="$ROOT_DIR/app/build/outputs/apk/$flavor/release/LibAuto-$flavor-release-unsigned.apk"
  aligned="$ART_DIR/LibAuto-$flavor-release-aligned.apk"
  signed="$ART_DIR/LibAuto-$flavor-release-signed.apk"

  "$ZIPALIGN" -p -f 4 "$unsigned" "$aligned"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$STOREPASS" \
    --key-pass "pass:$KEYPASS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --v4-signing-enabled false \
    --out "$signed" \
    "$aligned"
  "$APKSIGNER" verify --verbose --print-certs "$signed" > "$ART_DIR/LibAuto-$flavor-release-signed.verify.txt"
  rm -f "$aligned"
done

"$JARSIGNER" \
  -keystore "$KEYSTORE" \
  -storepass "$STOREPASS" \
  -keypass "$KEYPASS" \
  -signedjar "$ART_DIR/LibAuto-play-release-signed.aab" \
  "$ROOT_DIR/app/build/outputs/bundle/playRelease/LibAuto-play-release.aab" \
  "$ALIAS"
"$JARSIGNER" -verify -verbose -certs "$ART_DIR/LibAuto-play-release-signed.aab" > "$ART_DIR/LibAuto-play-release-signed.aab.verify.txt"

find "$ART_DIR" -maxdepth 1 -type f -printf "%f %s bytes\n" | sort
