#!/usr/bin/env bash
set -euo pipefail

# scripts/test-runner-parser.sh
# Tests scripts/parse-test-runner.py against fixed synthetic logs.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARSER="$SCRIPT_DIR/parse-test-runner.py"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

echo "=== Testing parse-test-runner.py against synthetic logs ==="

# 1. 46 pass + 2 assumption skip (must pass)
cat << 'EOF' > "$TMP_DIR/log1.txt"
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testPass1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testPass1
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=com.example.TestClass2
INSTRUMENTATION_STATUS: test=testSkip1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.TestClass2
INSTRUMENTATION_STATUS: test=testSkip1
INSTRUMENTATION_STATUS_CODE: -4
INSTRUMENTATION_CODE: -1
EOF

python3 "$PARSER" --log "$TMP_DIR/log1.txt" --classes "com.example.TestClass1,com.example.TestClass2" >/dev/null
echo "Case 1 (Pass with assumption skip): OK"

# 2. Assertion failure (must fail)
cat << 'EOF' > "$TMP_DIR/log2.txt"
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testFail
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testFail
INSTRUMENTATION_STATUS_CODE: -1
INSTRUMENTATION_CODE: -1
EOF

if python3 "$PARSER" --log "$TMP_DIR/log2.txt" >/dev/null 2>&1; then
    echo "Case 2 (Assertion failure): FAILED (expected non-zero exit)" >&2
    exit 1
fi
echo "Case 2 (Assertion failure): OK (rejected as expected)"

# 3. Process crashed (must fail)
cat << 'EOF' > "$TMP_DIR/log3.txt"
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testCrash
INSTRUMENTATION_STATUS_CODE: 1
shortMsg=Process crashed
INSTRUMENTATION_CODE: -1
EOF

if python3 "$PARSER" --log "$TMP_DIR/log3.txt" >/dev/null 2>&1; then
    echo "Case 3 (Process crashed): FAILED (expected non-zero exit)" >&2
    exit 1
fi
echo "Case 3 (Process crashed): OK (rejected as expected)"

# 4. Unpaired start (must fail)
cat << 'EOF' > "$TMP_DIR/log4.txt"
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testHanging
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_CODE: -1
EOF

if python3 "$PARSER" --log "$TMP_DIR/log4.txt" >/dev/null 2>&1; then
    echo "Case 4 (Unpaired start): FAILED (expected non-zero exit)" >&2
    exit 1
fi
echo "Case 4 (Unpaired start): OK (rejected as expected)"

# 5. All skipped (0 pass, 2 skip) (must fail by default)
cat << 'EOF' > "$TMP_DIR/log5.txt"
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testSkip1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testSkip1
INSTRUMENTATION_STATUS_CODE: -4
INSTRUMENTATION_CODE: -1
EOF

if python3 "$PARSER" --log "$TMP_DIR/log5.txt" >/dev/null 2>&1; then
    echo "Case 5 (All skipped): FAILED (expected non-zero exit)" >&2
    exit 1
fi
echo "Case 5 (All skipped): OK (rejected as expected)"

# 6. Zero tests (must fail)
cat << 'EOF' > "$TMP_DIR/log6.txt"
INSTRUMENTATION_CODE: -1
EOF

if python3 "$PARSER" --log "$TMP_DIR/log6.txt" >/dev/null 2>&1; then
    echo "Case 6 (Zero tests): FAILED (expected non-zero exit)" >&2
    exit 1
fi
echo "Case 6 (Zero tests): OK (rejected as expected)"

# 7. Missing expected class (must fail)
cat << 'EOF' > "$TMP_DIR/log7.txt"
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testPass1
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.example.TestClass1
INSTRUMENTATION_STATUS: test=testPass1
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_CODE: -1
EOF

if python3 "$PARSER" --log "$TMP_DIR/log7.txt" --classes "com.example.TestClass1,com.example.MissingClass" >/dev/null 2>&1; then
    echo "Case 7 (Missing expected class): FAILED (expected non-zero exit)" >&2
    exit 1
fi
echo "Case 7 (Missing expected class): OK (rejected as expected)"

echo "=== All 7 parser unit test cases passed! ==="
