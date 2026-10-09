#!/usr/bin/env python3
"""Publish failing JUnit test cases from Gradle XML results as GitHub error annotations.

Usage: summarize-junit-failures.py <results-dir> [<results-dir> ...]

Gradle writes TEST-*.xml files under <module>/build/test-results/<task>/. Report
artifacts are not always downloadable from a failed run, so this puts the failing
test names and the first lines of each failure directly into the check annotations.

The script never fails the job itself: the Gradle step already decides pass/fail.
It only reports. Missing directories are reported and skipped.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 60
MAX_MESSAGE_CHARS = 400


def escape(value: str) -> str:
    # GitHub workflow command escaping for the message part.
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def failure_text(element) -> str:
    message = element.get("message") or ""
    body = (element.text or "").strip().splitlines()
    head = [line.strip() for line in body[:3] if line.strip()]
    text = " | ".join([message] + head) if message else " | ".join(head)
    return text[:MAX_MESSAGE_CHARS]


def main(argv):
    if len(argv) < 2:
        print("usage: summarize-junit-failures.py <results-dir> [...]", file=sys.stderr)
        return 0

    files = []
    for root in argv[1:]:
        if not os.path.isdir(root):
            print(f"No test results directory at {root}; nothing to summarize.")
            continue
        files.extend(sorted(glob.glob(os.path.join(root, "**", "TEST-*.xml"), recursive=True)))

    total = 0
    failed = 0
    annotations = 0
    for path in files:
        try:
            tree = ET.parse(path)
        except ET.ParseError as error:
            print(f"Could not parse {path}: {error}")
            continue
        for case in tree.iter("testcase"):
            total += 1
            failure = case.find("failure")
            if failure is None:
                failure = case.find("error")
            if failure is None:
                continue
            failed += 1
            if annotations >= MAX_ANNOTATIONS:
                continue
            annotations += 1
            name = f"{case.get('classname', '?')} > {case.get('name', '?')}"
            print(f"::error title=Failing test::{escape(name + ': ' + failure_text(failure))}")

    print(f"Parsed {len(files)} result file(s): {total} test case(s), {failed} failing.")
    if failed > MAX_ANNOTATIONS:
        print(f"::error::{failed - MAX_ANNOTATIONS} more failing test(s) not shown; see the test report artifact.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
