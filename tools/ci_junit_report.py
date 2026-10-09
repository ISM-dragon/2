#!/usr/bin/env python3
"""Turn Gradle JUnit XML results into a compact, machine-readable report.

The reliability CI job has to survive a reviewer who cannot open the raw build log (forked runs, log
retention, proxy restrictions), so the numbers are published in three places:

  * a GitHub Actions annotation per failing test (``::error::`` / ``::notice::`` workflow commands
    written to stdout), capped so the 50-annotation-per-check budget is never exceeded;
  * a Markdown summary (test classes, counts, failures with their first lines) for a PR comment;
  * a one-line machine-readable status on stdout for anything that parses CI output.

Usage:
    python3 tools/ci_junit_report.py --summary out.md --failures out.txt <results-dir> [<dir> ...]

Exit code: 0 when every executed test passed, 1 when any test failed or errored, 2 when no results
were found at all (a suite that silently did not run is a failure, not a success).
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_FAILURE_ANNOTATIONS = 30
MAX_MESSAGE_CHARS = 1_600


def parse_results(dirs: list[str]) -> tuple[list[dict], int]:
    cases: list[dict] = []
    files: list[str] = []
    for root in dirs:
        files.extend(sorted(glob.glob(os.path.join(root, "**", "TEST-*.xml"), recursive=True)))

    for path in files:
        try:
            tree = ET.parse(path)
        except ET.ParseError as error:  # a truncated report is still a signal, not a crash
            cases.append(
                {
                    "class": os.path.basename(path),
                    "name": "<unreadable report>",
                    "status": "error",
                    "detail": f"{type(error).__name__}: {path}",
                    "suite": path,
                }
            )
            continue
        for suite in tree.getroot().iter("testsuite"):
            suite_name = suite.get("name") or os.path.basename(path)
            for case in suite.iter("testcase"):
                failure = case.find("failure")
                error = case.find("error")
                skipped = case.find("skipped")
                problem = failure if failure is not None else error
                if problem is not None:
                    status = "failed" if failure is not None else "error"
                    message = (problem.get("message") or "").strip()
                    body = one_line(problem.text or "")
                    # JUnit repeats the message on the first body line; keep one copy.
                    if body.startswith(message):
                        detail = body
                    else:
                        detail = " ".join(filter(None, [message, body]))
                elif skipped is not None:
                    status = "skipped"
                    detail = skipped.get("message") or ""
                else:
                    status = "passed"
                    detail = ""
                cases.append(
                    {
                        "class": suite_name,
                        "name": case.get("name") or "<unnamed>",
                        "status": status,
                        "detail": one_line(detail),
                        "suite": suite_name,
                    }
                )
    return cases, len(files)


def one_line(text: str) -> str:
    if not text:
        return ""
    kept: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped:
            kept.append(stripped)
        if len(kept) >= 6:
            break
    joined = " | ".join(kept)
    return joined[:MAX_MESSAGE_CHARS]


def group_by_class(cases: list[dict]) -> list[str]:
    rows: dict[str, dict[str, int]] = {}
    for case in cases:
        counters = rows.setdefault(case["class"], {"total": 0, "passed": 0, "failed": 0, "skipped": 0})
        counters["total"] += 1
        key = "failed" if case["status"] in ("failed", "error") else case["status"]
        counters[key] = counters.get(key, 0) + 1
    return [
        "| `%s` | %d | %d | %d | %d |"
        % (name, row["total"], row["passed"], row["failed"], row["skipped"])
        for name, row in sorted(rows.items())
    ]


def escape_annotation(text: str) -> str:
    # GitHub workflow commands are single-line and cannot carry %, CR or LF literally.
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main() -> int:
    parser = argparse.ArgumentParser(description="JUnit XML -> CI report")
    parser.add_argument("dirs", nargs="+", help="directories holding Gradle test-results XML")
    parser.add_argument("--summary", help="write the Markdown summary here")
    parser.add_argument("--failures", help="write the plain-text failure list here")
    parser.add_argument("--no-annotations", action="store_true", help="do not emit workflow commands")
    args = parser.parse_args()

    cases, report_count = parse_results(args.dirs)
    executed = [case for case in cases if case["status"] != "skipped"]
    problems = [case for case in cases if case["status"] in ("failed", "error")]

    total = len(cases)
    passed = sum(1 for case in cases if case["status"] == "passed")
    skipped = sum(1 for case in cases if case["status"] == "skipped")

    lines = [
        "## Test results",
        "",
        "- reports parsed: **%d**" % report_count,
        "- tests: **%d** (passed %d, failed %d, skipped %d)" % (total, passed, len(problems), skipped),
        "",
        "| test class | total | passed | failed | skipped |",
        "| --- | --- | --- | --- | --- |",
    ]
    lines.extend(group_by_class(cases) or ["| _none_ | 0 | 0 | 0 | 0 |"])
    if problems:
        lines.extend(["", "### Failures", ""])
        for case in problems:
            lines.append("- `%s` ▶ **%s**" % (case["class"], case["name"]))
            if case["detail"]:
                lines.append("  - `%s`" % case["detail"][:600])
    if not cases:
        lines.extend(["", "> **No test results were produced.** A suite that did not run is a failure."])

    summary = "\n".join(lines) + "\n"
    if args.summary:
        os.makedirs(os.path.dirname(os.path.abspath(args.summary)), exist_ok=True)
        with open(args.summary, "w", encoding="utf-8") as handle:
            handle.write(summary)
    if args.failures:
        os.makedirs(os.path.dirname(os.path.abspath(args.failures)), exist_ok=True)
        with open(args.failures, "w", encoding="utf-8") as handle:
            for case in problems:
                handle.write("%s :: %s :: %s\n" % (case["class"], case["name"], case["detail"]))

    if not args.no_annotations:
        for case in problems[:MAX_FAILURE_ANNOTATIONS]:
            print("::error title=%s::%s" % (escape_annotation(case["class"][:200]), escape_annotation(case["name"] + " :: " + case["detail"])))
        if len(problems) > MAX_FAILURE_ANNOTATIONS:
            print("::error title=more failures::%d further failing tests are listed in the job summary" % (len(problems) - MAX_FAILURE_ANNOTATIONS))
        print(
            "::notice title=test totals::reports=%d tests=%d passed=%d failed=%d skipped=%d"
            % (report_count, total, passed, len(problems), skipped)
        )
        notice = "; ".join("%s: %s" % (case["class"], case["name"]) for case in problems[MAX_FAILURE_ANNOTATIONS:])
        if notice:
            print("::notice title=failing tests (continued)::%s" % escape_annotation(notice[:4_000]))

    print("JUNIT_REPORT tests=%d passed=%d failed=%d skipped=%d reports=%d" % (total, passed, len(problems), skipped, report_count))

    if not cases:
        return 2
    return 1 if problems or not executed else 0


if __name__ == "__main__":
    sys.exit(main())
