#!/usr/bin/env sh
set -eu

REPO="${PROTOFORM_REPO:-walensaz/ProtoForm}"
REQUESTED_VERSION="${PROTOFORM_VERSION:-latest}"
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: required command '$1' not found." >&2
    exit 1
  fi
}

need_cmd curl

OS_RAW="$(uname -s)"
ARCH_RAW="$(uname -m)"

case "$OS_RAW" in
  Darwin)
    OS_TAG="macos"
    need_cmd ditto
    ;;
  Linux)
    OS_TAG="linux"
    if ! command -v unzip >/dev/null 2>&1 && ! command -v bsdtar >/dev/null 2>&1; then
      echo "Error: required command 'unzip' (or 'bsdtar') not found." >&2
      exit 1
    fi
    ;;
  *)
    echo "Unsupported OS: $OS_RAW" >&2
    exit 1
    ;;
esac

case "$ARCH_RAW" in
  arm64|aarch64) ARCH_TAG="$ARCH_RAW" ;;
  x86_64|amd64)  ARCH_TAG="x86_64" ;;
  *)
    echo "Unsupported architecture: $ARCH_RAW" >&2
    exit 1
    ;;
esac

api_get() {
  if [ -n "$TOKEN" ]; then
    curl -fsSL -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/vnd.github+json" "$1"
  else
    curl -fsSL -H "Accept: application/vnd.github+json" "$1"
  fi
}

if [ "$REQUESTED_VERSION" = "latest" ]; then
  API_URL="https://api.github.com/repos/${REPO}/releases/latest"
  TAG_NAME="$(api_get "$API_URL" 2>/dev/null | sed -n 's/.*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1 || true)"
  if [ -z "$TAG_NAME" ] && command -v gh >/dev/null 2>&1; then
    TAG_NAME="$(gh api "repos/${REPO}/releases/latest" --jq .tag_name 2>/dev/null || true)"
  fi
  if [ -z "$TAG_NAME" ]; then
    echo "Error: could not resolve latest release from $API_URL (HTTP 404 or no published release)." >&2
    echo "Ensure ${REPO} is public and has a published (non-draft) release, or set GITHUB_TOKEN / PROTOFORM_VERSION." >&2
    exit 1
  fi
else
  case "$REQUESTED_VERSION" in
    v*) TAG_NAME="$REQUESTED_VERSION" ;;
    *)  TAG_NAME="v${REQUESTED_VERSION}" ;;
  esac
fi

VERSION="${TAG_NAME#v}"
ASSET_NAME="ProtoForm-${VERSION}-${OS_TAG}-${ARCH_TAG}-portable.zip"
BASE_URL="https://github.com/${REPO}/releases/download/${TAG_NAME}"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RELEASE_JSON=""
find_asset_api_url() {
  target_name="$1"
  printf '%s\n' "$RELEASE_JSON" | awk -v target="$target_name" '
    /"url":[[:space:]]*"https:\/\/api\.github\.com\/repos\/.*\/releases\/assets\/[0-9]+"/ {
      url = $0
      sub(/.*"url":[[:space:]]*"/, "", url)
      sub(/".*/, "", url)
    }
    $0 ~ "\"name\":[[:space:]]*\"" target "\"" {
      if (url != "") { print url; exit }
    }
  '
}

download_release_file() {
  file_name="$1"
  dest_path="$2"
  optional="${3:-0}"

  if [ -n "$TOKEN" ]; then
    if [ -z "$RELEASE_JSON" ]; then
      RELEASE_JSON="$(api_get "https://api.github.com/repos/${REPO}/releases/tags/${TAG_NAME}" 2>/dev/null || true)"
    fi
    asset_api_url="$(find_asset_api_url "$file_name" || true)"
    if [ -n "$asset_api_url" ]; then
      curl -fL --progress-bar -H "Authorization: Bearer ${TOKEN}" -H "Accept: application/octet-stream" "$asset_api_url" -o "$dest_path"
      return 0
    fi
  fi

  if curl -fL --progress-bar "${BASE_URL}/${file_name}" -o "$dest_path" 2>/dev/null; then
    return 0
  fi

  if command -v gh >/dev/null 2>&1; then
    if gh release download "$TAG_NAME" --repo "$REPO" --pattern "$file_name" --dir "$TMP_DIR" >/dev/null 2>&1; then
      return 0
    fi
  fi

  if [ "$optional" = "1" ]; then
    return 1
  fi

  echo "Error: failed to download ${file_name} from ${BASE_URL}/${file_name}." >&2
  echo "Ensure ${REPO} is public and release ${TAG_NAME} is published, or set GITHUB_TOKEN." >&2
  exit 1
}

echo "Downloading ProtoForm ${VERSION} (${OS_TAG}/${ARCH_TAG})..."
download_release_file "$ASSET_NAME" "${TMP_DIR}/${ASSET_NAME}" 0

if download_release_file "SHA256SUMS.txt" "${TMP_DIR}/SHA256SUMS.txt" 1; then
  EXPECTED="$(grep " ${ASSET_NAME}\$" "${TMP_DIR}/SHA256SUMS.txt" | awk '{print $1}' | head -n 1 || true)"
  if [ -n "$EXPECTED" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      ACTUAL="$(sha256sum "${TMP_DIR}/${ASSET_NAME}" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
      ACTUAL="$(shasum -a 256 "${TMP_DIR}/${ASSET_NAME}" | awk '{print $1}')"
    else
      ACTUAL=""
    fi
    if [ -n "$ACTUAL" ] && [ "$ACTUAL" != "$EXPECTED" ]; then
      echo "Checksum verification failed for ${ASSET_NAME}!" >&2
      exit 1
    fi
    echo "Verified SHA-256 checksum."
  fi
fi

if [ "$OS_TAG" = "macos" ]; then
  ditto -xk "${TMP_DIR}/${ASSET_NAME}" "${TMP_DIR}"

  if [ -w "/Applications" ]; then
    INSTALL_DIR="/Applications"
  else
    INSTALL_DIR="${HOME}/Applications"
    mkdir -p "$INSTALL_DIR"
  fi
  rm -rf "${INSTALL_DIR}/ProtoForm.app"
  mv "${TMP_DIR}/ProtoForm.app" "${INSTALL_DIR}/ProtoForm.app"
  xattr -dr com.apple.quarantine "${INSTALL_DIR}/ProtoForm.app" 2>/dev/null || true

  BIN_DIR="${HOME}/.local/bin"
  mkdir -p "$BIN_DIR"
  cat > "${BIN_DIR}/protoform" <<EOF
#!/usr/bin/env sh
open -a "${INSTALL_DIR}/ProtoForm.app" "\$@"
EOF
  chmod +x "${BIN_DIR}/protoform"
  echo "Installed ProtoForm ${VERSION} to ${INSTALL_DIR}/ProtoForm.app"
  echo "Launch via Spotlight/Applications or '${BIN_DIR}/protoform'."
else
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "${TMP_DIR}/${ASSET_NAME}" -d "${TMP_DIR}"
  else
    bsdtar -xf "${TMP_DIR}/${ASSET_NAME}" -C "${TMP_DIR}"
  fi

  INSTALL_DIR="${HOME}/.local/share/protoform"
  BIN_DIR="${HOME}/.local/bin"
  rm -rf "$INSTALL_DIR"
  mkdir -p "$INSTALL_DIR" "$BIN_DIR"
  mv "${TMP_DIR}/ProtoForm"/* "$INSTALL_DIR/"
  ln -sf "${INSTALL_DIR}/bin/ProtoForm" "${BIN_DIR}/protoform"
  echo "Installed ProtoForm ${VERSION} to ${INSTALL_DIR}"
  echo "Run 'protoform' (ensure ${BIN_DIR} is on your PATH)."
fi
