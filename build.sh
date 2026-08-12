#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK="/workspace/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.nigh.aprstx"
ACT="$PKG/.MainActivity"
IMG="${ANDROID_DEV_IMAGE:-xianii/android-dev:latest}"
# ponytail: project-local cache — avoids journal lock fights with host Gradle on ~/.gradle
GRADLE_DIR="$SCRIPT_DIR/.gradle-docker"

mkdir -p "$HOME/.android" "$GRADLE_DIR"

release_host_adb() {
    command -v adb >/dev/null 2>&1 && adb kill-server >/dev/null 2>&1 || true
}

docker_run_build() {
    docker run --rm \
        -v "$SCRIPT_DIR:/workspace" \
        -v "$HOME/.android:/root/.android" \
        -e GRADLE_USER_HOME=/workspace/.gradle-docker \
        -w /workspace \
        "$IMG" "$@"
}

docker_run_adb() {
    release_host_adb
    local plugdev
    plugdev=$(getent group plugdev 2>/dev/null | cut -d: -f3 || true)
    local -a args=(
        docker run --rm
        --device=/dev/bus/usb
        --user "$(id -u):$(id -g)"
        -e HOME=/home/android-dev
        -v "$SCRIPT_DIR:/workspace"
        -v "$HOME/.android:/home/android-dev/.android"
        -w /workspace
    )
    [ -n "$plugdev" ] && args+=(--group-add "$plugdev")
    args+=("$IMG" "$@")
    "${args[@]}"
}

ADB_INIT='
ensure_adb_key() {
    mkdir -p "$HOME/.android"
    if [ ! -s "$HOME/.android/adbkey" ]; then
        adb keygen "$HOME/.android/adbkey"
        chmod 600 "$HOME/.android/adbkey"
        echo "==> New adb key in ~/.android — authorize once on device (Always allow)."
    fi
    adb start-server >/dev/null 2>&1
}

wait_for_device() {
    echo "==> Waiting for device..."
    echo "    If prompted, tap Allow on the device."
    for i in $(seq 1 90); do
        STATE=$(adb get-state 2>/dev/null || echo "")
        if [ "$STATE" = "device" ]; then
            return 0
        fi
        if [ "$STATE" = "unauthorized" ]; then
            echo "    ($i) unauthorized — tap Allow on device..."
        fi
        sleep 1
    done
    echo "ERROR: device not authorized or not connected"
    echo "Hint: host adb may be holding USB — run: adb kill-server"
    exit 1
}
'

build() {
    echo "==> Building debug..."
    docker_run_build ./gradlew assembleDebug
}

release() {
    if [ ! -f "$SCRIPT_DIR/keystore/release.env" ]; then
        echo "ERROR: keystore/release.env not found — run keytool first (see AGENTS.md)." >&2
        exit 1
    fi
    echo "==> Building release (signed)..."
    docker run --rm \
        -v "$SCRIPT_DIR:/workspace" \
        -v "$HOME/.android:/root/.android" \
        --env-file "$SCRIPT_DIR/keystore/release.env" \
        -e GRADLE_USER_HOME=/workspace/.gradle-docker \
        -w /workspace \
        "$IMG" ./gradlew assembleRelease
}

test_unit() {
    echo "==> Unit tests..."
    docker_run_build ./gradlew test
}

install() {
    docker_run_adb bash -c "$ADB_INIT
ensure_adb_key
wait_for_device
echo '==> Device ready.'
if adb shell pm path '$PKG' >/dev/null 2>&1; then
    echo '==> Removing existing install...'
    adb uninstall '$PKG'
fi
echo '==> Installing...'
adb install -r -t '$APK'
echo '==> Launching...'
adb shell am start -n '$ACT'
echo '==> Done.'
"
}

adb_cmd() {
    if [ $# -eq 0 ]; then
        echo "Usage: $0 adb <adb-args...>   e.g. $0 adb devices" >&2
        exit 1
    fi
    # shellcheck disable=SC2046
    docker_run_adb bash -c "$ADB_INIT
ensure_adb_key
exec adb $(printf '%q ' "$@")
"
}

case "${1:-}" in
    build)   build ;;
    release) release ;;
    test)    test_unit ;;
    install) install ;;
    run)     build; install ;;
    adb)     shift; adb_cmd "$@" ;;
    *)
        build
        install
        ;;
esac
