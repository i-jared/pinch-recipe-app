#!/bin/bash
# Creates the upload key that Google Play uses to check your uploads (with Play App Signing,
# Google keeps the key that signs the app on phones). keytool asks for a password and your
# name; keep upload-key.jks and the password somewhere safe, like a password manager.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -e upload-key.jks ]]; then
  echo "upload-key.jks already exists; not replacing it." >&2
  exit 1
fi
keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
"$keytool" -genkeypair -v -storetype PKCS12 -keystore upload-key.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000

echo
echo "Done. Copy keystore.properties.example to keystore.properties and put the password"
echo "you just chose in both storePassword and keyPassword."
