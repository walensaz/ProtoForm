#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.0.0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if [ "$SKIP_BUILD" != "1" ]; then
  echo "Running tests and building ProtoForm fat JAR..."
  sbt test assembly
fi

JAR_PATH="$REPO_ROOT/target/scala-3.3.3/protoform-${VERSION}.jar"
if [ ! -f "$JAR_PATH" ]; then
  JAR_PATH="$(ls "$REPO_ROOT"/target/scala-3.3.3/protoform-*.jar | head -n 1)"
fi
JAR_NAME="$(basename "$JAR_PATH")"

STAGE_DIR="$REPO_ROOT/packaging/build/input"
DIST_DIR="$REPO_ROOT/dist"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR" "$DIST_DIR"
cp "$JAR_PATH" "$STAGE_DIR/$JAR_NAME"

REDUCED_MODULES="java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported"
JLINK_OPTS="--strip-native-commands --strip-debug --no-header-files --no-man-pages"

OS="$(uname -s)"
ARCH="$(uname -m)"

if [ "$OS" = "Darwin" ]; then
  ICONSET_DIR="$REPO_ROOT/packaging/build/protoform.iconset"
  ICNS_PATH="$REPO_ROOT/packaging/build/protoform.icns"
  SRC_PNG="$REPO_ROOT/packaging/icons/protoform.png"
  rm -rf "$ICONSET_DIR"
  mkdir -p "$ICONSET_DIR"

  for sz in 16 32 128 256 512; do
    sips -z "$sz" "$sz" "$SRC_PNG" --out "$ICONSET_DIR/icon_${sz}x${sz}.png" >/dev/null
  done
  for sz in 16 32 128 256; do
    dbl=$((sz * 2))
    sips -z "$dbl" "$dbl" "$SRC_PNG" --out "$ICONSET_DIR/icon_${sz}x${sz}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET_DIR" -o "$ICNS_PATH"

  rm -rf "$DIST_DIR/ProtoForm.app"
  jpackage \
    --type app-image \
    --name ProtoForm \
    --app-version "$VERSION" \
    --vendor "ProtoForm" \
    --description "Cross-platform dynamic gRPC desktop client" \
    --input "$STAGE_DIR" \
    --main-jar "$JAR_NAME" \
    --main-class net.protoform.Launcher \
    --icon "$ICNS_PATH" \
    --add-modules "$REDUCED_MODULES" \
    --jlink-options "$JLINK_OPTS" \
    --java-options "-Xmx1024m" \
    --dest "$DIST_DIR"

  ZIP_PATH="$DIST_DIR/ProtoForm-${VERSION}-macos-${ARCH}-portable.zip"
  rm -f "$ZIP_PATH"
  (cd "$DIST_DIR" && ditto -c -k --sequesterRsrc --keepParent "ProtoForm.app" "$ZIP_PATH")

  rm -f "$DIST_DIR/ProtoForm-${VERSION}.dmg" "$DIST_DIR/ProtoForm-${VERSION}-macos-${ARCH}.dmg"
  jpackage \
    --type dmg \
    --name ProtoForm \
    --app-version "$VERSION" \
    --vendor "ProtoForm" \
    --description "Cross-platform dynamic gRPC desktop client" \
    --app-image "$DIST_DIR/ProtoForm.app" \
    --dest "$DIST_DIR"

  mv "$DIST_DIR/ProtoForm-${VERSION}.dmg" "$DIST_DIR/ProtoForm-${VERSION}-macos-${ARCH}.dmg"
elif [ "$OS" = "Linux" ]; then
  if ! command -v zip >/dev/null 2>&1; then
    echo "Error: 'zip' command is required to build portable Linux zip archives." >&2
    exit 1
  fi

  PNG_ICON="$REPO_ROOT/packaging/icons/protoform.png"
  rm -rf "$DIST_DIR/ProtoForm"

  jpackage \
    --type app-image \
    --name ProtoForm \
    --app-version "$VERSION" \
    --vendor "ProtoForm" \
    --description "Cross-platform dynamic gRPC desktop client" \
    --input "$STAGE_DIR" \
    --main-jar "$JAR_NAME" \
    --main-class net.protoform.Launcher \
    --icon "$PNG_ICON" \
    --add-modules "$REDUCED_MODULES" \
    --jlink-options "$JLINK_OPTS" \
    --java-options "-Xmx1024m" \
    --dest "$DIST_DIR"

  ZIP_PATH="$DIST_DIR/ProtoForm-${VERSION}-linux-${ARCH}-portable.zip"
  rm -f "$ZIP_PATH"
  (cd "$DIST_DIR" && zip -qry "$ZIP_PATH" "ProtoForm")

  if command -v dpkg-deb >/dev/null 2>&1; then
    rm -f "$DIST_DIR"/protoform_*.deb "$DIST_DIR"/protoform-*.deb
    jpackage \
      --type deb \
      --name protoform \
      --app-version "$VERSION" \
      --vendor "ProtoForm" \
      --description "Cross-platform dynamic gRPC desktop client" \
      --app-image "$DIST_DIR/ProtoForm" \
      --linux-shortcut \
      --linux-menu-group "Development" \
      --dest "$DIST_DIR"
  fi
else
  echo "Unsupported OS for package-unix.sh: $OS"
  exit 1
fi

echo "Unix packaging complete. Outputs in: $DIST_DIR"
