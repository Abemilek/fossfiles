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

# 2) Keep everything the build generates (and the Gradle cache) owned by you,
#    never root:
#      - Podman: --userns=keep-id
#      - Docker: --user <uid>:<gid>
#    If the container runs as root it leaves root-owned folders in the
#    workspace, which then make actions/cache's hashFiles() fail on CI
#    ("Fail to hash files under directory ...").
RUN_ARGS=()
if [ "$ENGINE" = "podman" ]; then
  RUN_ARGS+=(--userns=keep-id)
else
  RUN_ARGS+=(--user "$(id -u):$(id -g)")
fi

# 3) Point Gradle at the SDK inside the container: a temp file is mounted
#    read-only over local.properties (Gradle reads sdk.dir from there and your
#    own local.properties points at a host path that does not exist here).
#    The host copy is saved first and restored on exit: with rootless Podman the
#    bind-mount target gets replaced by an empty file owned by the container's
#    user, which would leave your local.properties empty and break local builds.
mkdir -p "$CACHE_DIR"
TMP_PROPS="$(mktemp)"
LOCAL_PROPS_BAK=""
if [ -e local.properties ]; then
  LOCAL_PROPS_BAK="$(mktemp)"
  cp -p local.properties "$LOCAL_PROPS_BAK"
fi
restore_local_props() {
  rm -f "$TMP_PROPS"
  rm -f local.properties
  if [ -n "$LOCAL_PROPS_BAK" ]; then
    cp -p "$LOCAL_PROPS_BAK" local.properties
    rm -f "$LOCAL_PROPS_BAK"
  fi
}
trap restore_local_props EXIT
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
