#!/bin/bash
set -euo pipefail

ARCHIVE_PATH="${1:?Usage: signing_identity_report.sh /path/to/PurchaseSales.xcarchive [output.txt]}"
OUTPUT_PATH="${2:-signing-identity-report.txt}"
APP_PATH="$ARCHIVE_PATH/Products/Applications/PurchaseSales.app"
INFO_PLIST="$APP_PATH/Info.plist"

if [[ ! -d "$APP_PATH" ]]; then
  echo "App not found in archive: $APP_PATH" >&2
  exit 1
fi

BUNDLE_ID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$INFO_PLIST")
VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$INFO_PLIST")
BUILD=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$INFO_PLIST")
SIGNING=$(/usr/bin/codesign -dvvv "$APP_PATH" 2>&1)
TEAM_ID=$(printf '%s\n' "$SIGNING" | sed -n 's/^TeamIdentifier=//p')
AUTHORITY=$(printf '%s\n' "$SIGNING" | sed -n 's/^Authority=//p' | head -n 1)
CERT_SHA256=""
if [[ -n "$AUTHORITY" ]]; then
  CERT_SHA256=$(/usr/bin/security find-certificate -c "$AUTHORITY" -p | /usr/bin/openssl x509 -noout -fingerprint -sha256 2>/dev/null | sed 's/^sha256 Fingerprint=//')
fi

{
  echo "Purchase & Sales iOS Signing Identity Report"
  echo "Generated UTC: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "Bundle identifier: $BUNDLE_ID"
  echo "Apple Team ID: $TEAM_ID"
  echo "App version: $VERSION"
  echo "Build number: $BUILD"
  echo "Signing authority: $AUTHORITY"
  echo "Certificate SHA-256: $CERT_SHA256"
  echo "Archive: $(basename "$ARCHIVE_PATH")"
} > "$OUTPUT_PATH"

if [[ "$BUNDLE_ID" != "com.apingu.purchasesales" ]]; then
  echo "Unexpected bundle identifier: $BUNDLE_ID" >&2
  exit 2
fi
if [[ -z "$TEAM_ID" || "$TEAM_ID" == "not set" ]]; then
  echo "The archive is not signed with an Apple Team ID" >&2
  exit 3
fi

cat "$OUTPUT_PATH"

