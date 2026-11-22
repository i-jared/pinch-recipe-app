#!/bin/bash
# Archives Pinch for the App Store and exports a signed .ipa to ios/build/export.
#   ./scripts/archive-for-app-store.sh            archive and export
#   ./scripts/archive-for-app-store.sh --upload   archive and upload to App Store Connect
# Every upload needs a new build number; it defaults to the current date and time.
# Set BUILD_NUMBER=... to choose one. Uses the team and bundle ID in Config/Local.xcconfig
# and the Apple account signed in to Xcode.
set -euo pipefail
cd "$(dirname "$0")/.."

destination=export
[[ "${1:-}" == "--upload" ]] && destination=upload
build_number="${BUILD_NUMBER:-$(date +%Y%m%d%H%M)}"
team=$(sed -n 's/^PINCH_DEVELOPMENT_TEAM *= *//p' Config/Local.xcconfig 2>/dev/null | tr -d '[:space:]')
if [[ -z "$team" ]]; then
  echo "Set PINCH_DEVELOPMENT_TEAM (and PINCH_BUNDLE_ID) in Config/Local.xcconfig first." >&2
  exit 1
fi

xcodegen generate
rm -rf build/Pinch.xcarchive build/export
xcodebuild -project Pinch.xcodeproj -scheme Pinch -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/Pinch.xcarchive \
  -allowProvisioningUpdates CURRENT_PROJECT_VERSION="$build_number" archive

sed -e "s/TEAM_ID/$team/" -e "s/<string>export<\/string>/<string>$destination<\/string>/" \
  scripts/ExportOptions.plist > build/ExportOptions.plist
xcodebuild -exportArchive -archivePath build/Pinch.xcarchive -exportPath build/export \
  -exportOptionsPlist build/ExportOptions.plist -allowProvisioningUpdates

echo "Build $build_number: $([[ $destination == upload ]] && echo 'uploaded to App Store Connect' || echo 'exported to build/export')"
