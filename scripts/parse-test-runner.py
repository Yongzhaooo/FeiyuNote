#!/usr/bin/env python3
"""
scripts/parse-test-runner.py
Parses AndroidJUnitRunner raw output (-r) and validates that tests ran and terminated cleanly.
"""

import argparse
import os
import sys

def parse_runner_log(log_path, expected_classes=None, allow_all_skipped=False):
    if not os.path.exists(log_path):
        return False, f"Log file does not exist: {log_path}"

    if os.path.getsize(log_path) == 0:
        return False, "Log file is empty"

    with open(log_path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()

    # Check for fatal crash/abort
    crash_patterns = [
        "shortMsg=Process crashed",
        "INSTRUMENTATION_FAILED",
        "INSTRUMENTATION_ABORTED",
    ]
    for pattern in crash_patterns:
        if pattern in content:
            return False, f"Runner crashed or aborted (matched '{pattern}')"

    if "INSTRUMENTATION_CODE: -1" not in content:
        return False, "Runner did not complete successfully (missing 'INSTRUMENTATION_CODE: -1')"

    started = {}
    completed = {}
    classes_executed = set()

    current_class = None
    current_test = None

    for line in content.splitlines():
        line = line.strip()
        if line.startswith("INSTRUMENTATION_STATUS: class="):
            current_class = line.split("class=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS: test="):
            current_test = line.split("test=", 1)[1].strip()
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            code_str = line.split(":", 1)[1].strip()
            try:
                code = int(code_str)
            except ValueError:
                continue

            if not current_class or not current_test:
                continue

            key = (current_class, current_test)
            if code == 1:
                started[key] = started.get(key, 0) + 1
            else:
                completed[key] = code
                classes_executed.add(current_class)

    # Validate test counts
    if not completed:
        return False, "Zero tests were completed in the runner output"

    unpaired = set(started.keys()) - set(completed.keys())
    if unpaired:
        unpaired_desc = ", ".join(f"{cls}#{test}" for cls, test in sorted(unpaired))
        return False, f"Tests started but did not finish (missing terminal status): {unpaired_desc}"

    passed = sum(1 for c in completed.values() if c == 0)
    skipped = sum(1 for c in completed.values() if c == -4)
    ignored = sum(1 for c in completed.values() if c == -3)
    failed = sum(1 for c in completed.values() if c in (-1, -2))

    # Check expected classes
    if expected_classes:
        missing_classes = []
        for cls in expected_classes:
            cls = cls.strip()
            if cls and cls not in classes_executed:
                missing_classes.append(cls)
        if missing_classes:
            return False, f"Expected test classes were not executed: {', '.join(missing_classes)}"

    if failed > 0:
        failed_tests = [f"{cls}#{test} (code {code})" for (cls, test), code in completed.items() if code in (-1, -2)]
        return False, f"Test failures reported ({failed} failed): {', '.join(failed_tests)}"

    if passed == 0 and not allow_all_skipped:
        return False, f"Zero tests passed (all {len(completed)} tests were skipped/ignored)"

    summary = (
        f"Validation passed: {len(completed)} tests executed "
        f"({passed} passed, {skipped} skipped, {ignored} ignored, {failed} failed) "
        f"across {len(classes_executed)} classes."
    )
    return True, summary

def main():
    parser = argparse.ArgumentParser(description="Parse Android test runner output.")
    parser.add_argument("--log", required=True, help="Path to runner output log file")
    parser.add_argument("--classes", default="", help="Comma-separated list of expected classes")
    parser.add_argument("--allow-all-skipped", action="store_true", help="Allow zero passes if all tests skipped")

    args = parser.parse_args()
    expected = [c.strip() for c in args.classes.split(",") if c.strip()] if args.classes else []

    ok, msg = parse_runner_log(args.log, expected, args.allow_all_skipped)
    if ok:
        print(msg)
        sys.exit(0)
    else:
        print(f"Error: {msg}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
