#!/usr/bin/env bash
set -euo pipefail

# scripts/test-verify-script.sh
# Tests scripts/verify-course-review.sh offline behavior using stubbed adb and tee.
# Covers 4 essential error propagation conditions:
#   Case 1: Valid success log, but adb returns non-zero exit status -> fails
#   Case 2: Valid success log, adb returns 0, but tee returns non-zero exit status -> fails
#   Case 3: adb/tee return 0, but test output contains assertion failure -> fails
#   Case 4: adb/tee return 0, and test output is valid success -> succeeds

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VERIFY_SCRIPT="$PROJECT_ROOT/scripts/verify-course-review.sh"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

MOCK_ADB="$TMP_DIR/mock_adb"
MOCK_TEE="$TMP_DIR/mock_tee"
SUCCESS_LOG="$TMP_DIR/success.log"
FAILURE_LOG="$TMP_DIR/failure.log"

cat <<'EOF' > "$SUCCESS_LOG"
INSTRUMENTATION_STATUS: class=com.feiyu.notes.data.NotebookStoreTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test=sampleTest
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.feiyu.notes.data.NotebookStoreTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test=sampleTest
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=
Time: 1.000
OK (1 test)
INSTRUMENTATION_CODE: -1
EOF

cat <<'EOF' > "$FAILURE_LOG"
INSTRUMENTATION_STATUS: class=com.feiyu.notes.data.NotebookStoreTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test=sampleTest
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.feiyu.notes.data.NotebookStoreTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: expected:<1> but was:<2>
INSTRUMENTATION_STATUS: test=sampleTest
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_RESULT: stream=
Time: 1.000
FAILURES!!!
Tests run: 1,  Failures: 1
INSTRUMENTATION_CODE: -1
EOF

cat <<'EOF' > "$MOCK_ADB"
#!/usr/bin/env bash
if [[ "$*" == *"devices"* ]]; then
    echo "List of devices attached"
    echo "mock12345678        device product:MockProd model:MockModel device:MockDev transport_id:1"
    exit 0
elif [[ "$*" == *"getprop"* ]]; then
    echo "31"
    exit 0
elif [[ "$*" == *"am instrument"* ]]; then
    if [[ -n "${MOCK_LOG_FILE:-}" ]]; then
        cat "$MOCK_LOG_FILE"
    fi
    exit "${MOCK_ADB_EXIT:-0}"
fi
exit 0
EOF
chmod +x "$MOCK_ADB"

cat <<'EOF' > "$MOCK_TEE"
#!/usr/bin/env bash
cat > "$1"
exit "${MOCK_TEE_EXIT:-0}"
EOF
chmod +x "$MOCK_TEE"

echo "=== Testing verify-course-review.sh offline error propagation ==="

# Case 1: Valid success log, but adb returns non-zero exit status
echo -n "Case 1 (adb non-zero exit -> failure): "
set +e
OUT=$(ADB_BIN="$MOCK_ADB" TEE_BIN="$MOCK_TEE" MOCK_ADB_EXIT=1 MOCK_TEE_EXIT=0 MOCK_LOG_FILE="$SUCCESS_LOG" \
    bash "$VERIFY_SCRIPT" -s mock12345678 --skip-install -c "com.feiyu.notes.data.NotebookStoreTest" 2>&1)
EXIT_CODE=$?
set -e
if [[ $EXIT_CODE -ne 0 ]] && echo "$OUT" | grep -q "adb command exited with non-zero status" && echo "$OUT" | grep -q "Verification Result: FAILED"; then
    echo "OK (exit code $EXIT_CODE, rejected as expected)"
else
    echo "FAILED: unexpected exit code $EXIT_CODE or output missing expected error string. Output: $OUT"
    exit 1
fi

# Case 2: Valid success log, adb returns 0, but tee returns non-zero exit status
echo -n "Case 2 (tee non-zero exit -> failure): "
set +e
OUT=$(ADB_BIN="$MOCK_ADB" TEE_BIN="$MOCK_TEE" MOCK_ADB_EXIT=0 MOCK_TEE_EXIT=2 MOCK_LOG_FILE="$SUCCESS_LOG" \
    bash "$VERIFY_SCRIPT" -s mock12345678 --skip-install -c "com.feiyu.notes.data.NotebookStoreTest" 2>&1)
EXIT_CODE=$?
set -e
if [[ $EXIT_CODE -ne 0 ]] && echo "$OUT" | grep -q "tee command exited with non-zero status" && echo "$OUT" | grep -q "Verification Result: FAILED"; then
    echo "OK (exit code $EXIT_CODE, rejected as expected)"
else
    echo "FAILED: unexpected exit code $EXIT_CODE or output missing expected error string. Output: $OUT"
    exit 1
fi

# Case 3: adb/tee return 0, but test output contains assertion failure
echo -n "Case 3 (assertion failure in log -> failure): "
set +e
OUT=$(ADB_BIN="$MOCK_ADB" TEE_BIN="$MOCK_TEE" MOCK_ADB_EXIT=0 MOCK_TEE_EXIT=0 MOCK_LOG_FILE="$FAILURE_LOG" \
    bash "$VERIFY_SCRIPT" -s mock12345678 --skip-install -c "com.feiyu.notes.data.NotebookStoreTest" 2>&1)
EXIT_CODE=$?
set -e
if [[ $EXIT_CODE -ne 0 ]] && echo "$OUT" | grep -q "Verification Result: FAILED"; then
    echo "OK (exit code $EXIT_CODE, rejected as expected)"
else
    echo "FAILED: unexpected exit code $EXIT_CODE or output missing expected error string. Output: $OUT"
    exit 1
fi

# Case 4: adb/tee return 0, and test output is valid success
echo -n "Case 4 (all normal -> success): "
set +e
OUT=$(ADB_BIN="$MOCK_ADB" TEE_BIN="$MOCK_TEE" MOCK_ADB_EXIT=0 MOCK_TEE_EXIT=0 MOCK_LOG_FILE="$SUCCESS_LOG" \
    bash "$VERIFY_SCRIPT" -s mock12345678 --skip-install -c "com.feiyu.notes.data.NotebookStoreTest" 2>&1)
EXIT_CODE=$?
set -e
if [[ $EXIT_CODE -eq 0 ]] && echo "$OUT" | grep -q "Verification Result: SUCCESS"; then
    echo "OK (exit code $EXIT_CODE, success reported)"
else
    echo "FAILED: unexpected exit code $EXIT_CODE or output. Output: $OUT"
    exit 1
fi

echo "=== All 4 verify-course-review.sh offline test cases passed! ==="
