#!/usr/bin/env bash
#
# Build the FossFiles APKs using only Podman (or Docker).
# No JDK, Android SDK, Gradle or Kotlin needed on the host.
#
# Usage:
#   ./build-in-container.sh                    # release APKs (default)
#   ./build-in-container.sh assembleDebug      # any Gradle task
#   ./build-in-container.sh testDebugUnitTest  # run the JVM tests
#   ENGINE=docker ./build-in-container.sh      # use Docker instead of Podman
#
set -euo pipefail

cd "$(dirname "$0")"

ENGINE="${ENGINE:-podman}"
IMAGE="${IMAGE:-fossfiles-builder:latest}"
SDK_IN_CONTAINER="/opt/android-sdk"
CACHE_DIR="$PWD/.container/gradle"

GRADLE_TASKS=("${@:-assembleRelease}")

if ! command -v "$ENGINE" >/dev/null 2>&1; then
  echo "error: '$ENGINE' was not found. Install Podman, or run with ENGINE=docker." >&2
  exit 1
fi

# 1) Build the builder image once (first run downloads the base image + Android SDK).
if ! "$ENGINE" image inspect "$IMAGE" >/dev/null 2>&1; then
  echo ">> Building builder image '$IMAGE' (this only happens once)..."
  "$ENGINE" build -f Containerfile -t "$IMAGE" .
fi

# 2) --userns=keep-id is Podman-only and keeps the generated files owned by you.
#    Docker (e.g. on CI, running as root) needs no equivalent.
RUN_ARGS=()
if [ "$ENGINE" = "podman" ]; then
  RUN_ARGS+=(--userns=keep-id)
fi

# 3) Point Gradle at the SDK inside the container without touching your own
#    local.properties: a temp file is mounted read-only over it.
mkdir -p "$CACHE_DIR"
TMP_PROPS="$(mktemp)"
trap 'rm -f "$TMP_PROPS"' EXIT
printf 'sdk.dir=%s\n' "$SDK_IN_CONTAINER" > "$TMP_PROPS"

# 4) Run the build. The Gradle cache persists in .container/gradle between runs.
echo ">> Building: ${GRADLE_TASKS[*]}"
"$ENGINE" run --rm \
  "${RUN_ARGS[@]}" \
  -e HOME=/tmp \
  -e GRADLE_USER_HOME=/gradle \
  -v "$CACHE_DIR":/gradle \
  -v "$PWD":/project \
  -v "$TMP_PROPS":/project/local.properties:ro \
  -w /project \
  "$IMAGE" \
  ./gradlew --console=plain "${GRADLE_TASKS[@]}"

echo
echo ">> Done. APKs (if you ran an assemble task) are in:"
echo "   $PWD/app/build/outputs/apk/release/"
