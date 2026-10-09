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

MAX_BLOCK_CHARS = 3000   # annotation messages are packed to stay well under GitHub's limit
MAX_BLOCKS = 9           # GitHub shows at most 10 error annotations per step; keep one for notices
MAX_MESSAGE_CHARS = 300  # per failing test


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
    lines = []
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
            name = f"{case.get('classname', '?')} > {case.get('name', '?')}"
            lines.append(f"{name}: {failure_text(failure)}")

    print(f"Parsed {len(files)} result file(s): {total} test case(s), {failed} failing.")
    if failed:
        blocks = []
        block = f"{failed} failing test(s) of {total}:\n"
        for line in lines:
            if len(block) + len(line) + 1 > MAX_BLOCK_CHARS and block:
                blocks.append(block)
                block = ""
            block += line + "\n"
        if block:
            blocks.append(block)
        for index, text in enumerate(blocks[:MAX_BLOCKS]):
            print(f"::error title=Failing tests ({index + 1}/{len(blocks)})::{escape(text.rstrip())}")
        if len(blocks) > MAX_BLOCKS:
            print(f"::error::{len(blocks) - MAX_BLOCKS} more block(s) of failing tests omitted; see the test report artifact.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
