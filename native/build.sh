#!/bin/sh
# Builds libliquidglass.dylib as one universal binary (Apple Silicon and Intel).
# Usage: native/build.sh <output-dir>   -> <output-dir>/libliquidglass.dylib
#
# The library is signed ad hoc, which is enough for Rider (it runs with disable-library-validation). For a public
# release set RLG_SIGN_IDENTITY to a "Developer ID Application" identity: the binary is then signed with the
# hardened runtime and a secure timestamp, ready for notarization of the plugin archive.
set -eu

SRC_DIR="$(cd "$(dirname "$0")/src" && pwd)"
OUT_DIR="${1:?output directory required}"
MIN_MACOS="12.0"
IDENTITY="${RLG_SIGN_IDENTITY:--}"

mkdir -p "$OUT_DIR"
# Per-architecture folders of earlier layouts.
rm -rf "$OUT_DIR/mac-arm64" "$OUT_DIR/mac-x64"
xcrun clang -dynamiclib -fobjc-arc -fobjc-arc-exceptions -fmodules -O2 -Wall -Wextra -Werror \
  -arch arm64 -arch x86_64 -mmacosx-version-min="$MIN_MACOS" \
  -framework AppKit -framework QuartzCore \
  -install_name @rpath/libliquidglass.dylib \
  -o "$OUT_DIR/libliquidglass.dylib" "$SRC_DIR/LiquidGlassBridge.m"

if [ "$IDENTITY" = "-" ]; then
  codesign --force --sign - "$OUT_DIR/libliquidglass.dylib" >/dev/null 2>&1
else
  codesign --force --options runtime --timestamp --sign "$IDENTITY" "$OUT_DIR/libliquidglass.dylib" >/dev/null
fi
