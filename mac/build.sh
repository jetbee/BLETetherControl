#!/bin/sh
# Builds mac/build/BLETether.app (menu bar app + Control Center extension).
# Needs Xcode 26+ and xcodegen (brew install xcodegen). Set your team in project.yml.
set -eu
cd "$(dirname "$0")"

xcodegen generate --quiet
xcodebuild -project BLETether.xcodeproj -scheme BLETether -configuration Release \
    -derivedDataPath build/DerivedData -allowProvisioningUpdates -quiet build

rm -rf build/BLETether.app
cp -R build/DerivedData/Build/Products/Release/BLETether.app build/
echo "Built build/BLETether.app"
