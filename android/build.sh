#!/bin/sh
# Builds the signed APK: ./build.sh (epita.knhdixh.com) or ./build.sh staging (epita-staging.knhdixh.com).
# Needs JDK 17, the Android SDK in local.properties (sdk.dir=…) and keystore.properties next to this file.
set -e
cd "$(dirname "$0")"
[ -f keystore.properties ] || { echo "keystore.properties is missing (see README.md)"; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
if [ "$1" = staging ]; then HOST=epita-staging.knhdixh.com; OUT=attendance80-staging.apk; else HOST=epita.knhdixh.com; OUT=attendance80.apk; fi
./gradlew -q assembleRelease -PappHost="$HOST"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
echo "Built $OUT for $HOST"
