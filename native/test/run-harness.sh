#!/bin/sh
# Runs GlassHarness with the JetBrains Runtime shipped inside the local Rider.
set -eu

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RIDER="${RIDER_APP:-$HOME/Applications/Rider.app}"
JAVA="$RIDER/Contents/jbr/Contents/Home/bin/java"
OUT="$ROOT/build/native-test"

"$ROOT/native/build.sh" "$OUT"

exec "$JAVA" \
  --add-opens=java.desktop/java.awt=ALL-UNNAMED \
  --add-opens=java.desktop/javax.swing=ALL-UNNAMED \
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED \
  --add-opens=java.desktop/sun.lwawt=ALL-UNNAMED \
  --add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
  --add-exports=java.desktop/java.awt.peer=ALL-UNNAMED \
  -Dsun.java2d.metal=true -Dswing.bufferPerWindow=true ${RLG_HARNESS_JAVA_OPTS:-} \
  --enable-native-access=ALL-UNNAMED \
  -Drlg.library="$OUT/libliquidglass.dylib" \
  "$ROOT/native/test/GlassHarness.java" "$@"
