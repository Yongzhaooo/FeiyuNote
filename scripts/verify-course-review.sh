#!/usr/bin/env bash
set -euo pipefail

# scripts/verify-course-review.sh
# Verifies Course Review Records implementation on a designated Android test device.

USAGE="Usage: $0 -s <device_serial> [--skip-install] [--classes <class1,class2>]"

SERIAL=""
SKIP_INSTALL=false
CUSTOM_CLASSES=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial)
            if [[ -z "${2:-}" || "$2" == -* ]]; then
                echo "Error: -s requires a device serial argument." >&2
                echo "$USAGE" >&2
                exit 1
            fi
            SERIAL="$2"
            shift 2
            ;;
        --skip-install)
            SKIP_INSTALL=true
            shift
            ;;
        -c|--classes)
            if [[ -z "${2:-}" || "$2" == -* ]]; then
                echo "Error: --classes requires a comma-separated list of test classes." >&2
                echo "$USAGE" >&2
                exit 1
            fi
            CUSTOM_CLASSES="$2"
            shift 2
            ;;
        -h|--help)
            echo "$USAGE"
            echo "Options:"
            echo "  -s, --serial <serial>       Specific Android device/emulator serial (required)"
            echo "  --skip-install              Skip APK installation step"
            echo "  -c, --classes <classes>     Comma-separated list of test classes to run"
            exit 0
            ;;
        *)
            echo "Error: Unknown argument: $1" >&2
            echo "$USAGE" >&2
            exit 1
            ;;
    esac
done

ADB_BIN="${ADB_BIN:-adb}"
TEE_BIN="${TEE_BIN:-tee}"
if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    if [[ -x "${ANDROID_HOME:-}/platform-tools/adb" ]]; then
        ADB_BIN="$ANDROID_HOME/platform-tools/adb"
    elif [[ -x "$HOME/android-sdk/platform-tools/adb" ]]; then
        ADB_BIN="$HOME/android-sdk/platform-tools/adb"
    elif [[ -x "/home/circleci/android-sdk/platform-tools/adb" ]]; then
        ADB_BIN="/home/circleci/android-sdk/platform-tools/adb"
    else
        echo "Error: adb command not found and android-sdk not found in standard paths." >&2
        exit 1
    fi
fi

if [[ -z "$SERIAL" ]]; then
    echo "Error: Missing required -s <device_serial> argument." >&2
    echo "Available devices:" >&2
    "$ADB_BIN" devices -l >&2 || true
    echo "$USAGE" >&2
    exit 1
fi

if [[ ${#SERIAL} -gt 6 ]]; then
    SERIAL_MASKED="${SERIAL:0:3}***${SERIAL: -3}"
    SERIAL_SAFE="${SERIAL:0:3}xxx${SERIAL: -3}"
else
    SERIAL_MASKED="***"
    SERIAL_SAFE="xxx"
fi

echo "=== 1. Checking device connection for serial: $SERIAL_MASKED ==="
DEVICE_LINE=$("$ADB_BIN" devices -l | grep -w "^$SERIAL" || true)
if [[ -z "$DEVICE_LINE" ]]; then
    echo "Error: Device '$SERIAL_MASKED' not found in adb devices." >&2
    "$ADB_BIN" devices -l >&2
    exit 1
fi

if echo "$DEVICE_LINE" | grep -q "unauthorized"; then
    echo "Error: Device '$SERIAL_MASKED' is unauthorized. Please accept USB debugging prompt on the device." >&2
    exit 1
fi

if echo "$DEVICE_LINE" | grep -q "offline"; then
    echo "Error: Device '$SERIAL_MASKED' is offline." >&2
    exit 1
fi

DEVICE_API=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')
DEVICE_MODEL=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')
DEVICE_MANUF=$("$ADB_BIN" -s "$SERIAL" shell getprop ro.product.manufacturer | tr -d '\r')
echo "Target device: $DEVICE_MANUF $DEVICE_MODEL (API $DEVICE_API, serial: $SERIAL_MASKED)"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

DEBUG_APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$PROJECT_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [[ ! -f "$DEBUG_APK" || ! -f "$TEST_APK" ]]; then
    echo "Error: APKs not found. Please build them first using:" >&2
    echo "  bash ./gradlew assembleDebug assembleDebugAndroidTest --console=plain" >&2
    exit 1
fi

if [[ "$SKIP_INSTALL" == false ]]; then
    echo "=== 2. Installing APKs on device ==="
    echo "Installing app-debug.apk..."
    "$ADB_BIN" -s "$SERIAL" install -r -t "$DEBUG_APK"
    echo "Installing app-debug-androidTest.apk..."
    "$ADB_BIN" -s "$SERIAL" install -r -t "$TEST_APK"
else
    echo "=== 2. Skipping APK installation (--skip-install) ==="
fi

echo "=== 3. Running targeted verification tests ==="
if [[ -n "$CUSTOM_CLASSES" ]]; then
    TEST_CLASSES="$CUSTOM_CLASSES"
else
    TEST_CLASSES="com.feiyu.notes.data.NotebookStoreTest,com.feiyu.notes.study.GeneratorTest,com.feiyu.notes.ui.UiFlowTest"
fi

GIT_SHA=$(git -C "$PROJECT_ROOT" rev-parse --short HEAD 2>/dev/null || echo "head")
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
CLASS_TAG=$(echo "$TEST_CLASSES" | tr ',' '_' | sed 's/com.feiyu.notes.//g' | tr '.' '_')
OUTPUT_DIR="$PROJECT_ROOT/build/course-review-validation"
mkdir -p "$OUTPUT_DIR"
OUTPUT_FILE="$OUTPUT_DIR/device-test-${SERIAL_SAFE}-${CLASS_TAG}-${GIT_SHA}-${TIMESTAMP}.log"
DEFAULT_LINK="$OUTPUT_DIR/device-test-${SERIAL_SAFE}.log"

echo "Executing instrumentation tests: $TEST_CLASSES"
set +e
"$ADB_BIN" -s "$SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASSES" \
    com.feiyu.notes.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | "$TEE_BIN" "$OUTPUT_FILE"
ADB_PIPESTATUS=("${PIPESTATUS[@]}")
set -e
ADB_EXIT="${ADB_PIPESTATUS[0]}"
TEE_EXIT="${ADB_PIPESTATUS[1]}"

# Retain symlink/copy to default path for tooling compatibility
cp -f "$OUTPUT_FILE" "$DEFAULT_LINK"

TEST_FAILED=false

if [[ "$ADB_EXIT" -ne 0 ]]; then
    echo "Error: adb command exited with non-zero status: $ADB_EXIT" >&2
    TEST_FAILED=true
fi
if [[ "$TEE_EXIT" -ne 0 ]]; then
    echo "Error: tee command exited with non-zero status: $TEE_EXIT" >&2
    TEST_FAILED=true
fi

if ! python3 "$SCRIPT_DIR/parse-test-runner.py" --log "$OUTPUT_FILE" --classes "$TEST_CLASSES"; then
    echo "Error: Test runner parsing reported failure or incomplete tests." >&2
    TEST_FAILED=true
fi

if [[ "$TEST_FAILED" == true ]]; then
    echo "=== Verification Result: FAILED ===" >&2
    echo "See log for details: $OUTPUT_FILE" >&2
    exit 1
fi

echo "=== Verification Result: SUCCESS ==="
echo "All targeted instrumented tests completed cleanly on device $SERIAL_MASKED."
echo "Log saved to: $OUTPUT_FILE"
