#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
IOS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "XcodeGen is required. Install it with: brew install xcodegen" >&2
  exit 1
fi

cd "$IOS_DIR"
xcodegen generate --spec project.yml
echo "Generated $IOS_DIR/PurchaseSales.xcodeproj"

