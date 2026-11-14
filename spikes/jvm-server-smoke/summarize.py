#!/usr/bin/env python3
"""Turn a server log into GitHub Actions annotations and an exit code.

    summarize.py CONFIG LOG

PASS means the log has vanilla's "Done (...s)!" line and no line this script counts as a problem.
That is a smoke result for starting up. It says nothing about whether a mod's behaviour works.
"""
import re
import sys

DONE = re.compile(r"Done \((\d+(?:\.\d+)?)s\)")
LOADING = re.compile(r"Loading (\d+) mods?")
LIST_ITEM = re.compile(r"^\s+- \S")
PROBLEM = re.compile(
    r"\[(?:[^\]]*/)?ERROR\]|Exception|Caused by|InvalidInjection|Mixin apply failed|Could not execute entrypoint"
)
MAX_PROBLEMS = 6
MAX_MODS = 60


def summarize(lines):
    done = None
    loading = None
    mods = []
    capturing = False
    problems = []
    for line in lines:
        line = line.rstrip("\n")
        if done is None:
            m = DONE.search(line)
            if m:
                done = m.group(1)
        if loading is None:
            m = LOADING.search(line)
            if m:
                loading = line.strip()
                capturing = True
                continue
        if capturing:
            if LIST_ITEM.match(line) and len(mods) < MAX_MODS:
                mods.append(line.strip())
                continue
            capturing = False
        if PROBLEM.search(line):
            problems.append(line.strip())
    return {"done_seconds": done, "loading": loading, "mods": mods, "problems": problems,
            "passed": done is not None and not problems}


def escape_data(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text):
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def annotations(config, result):
    body = [f"result: {'PASS' if result['passed'] else 'FAIL'}",
            f"done: {result['done_seconds']}s" if result["done_seconds"] else "done: not reached",
            result["loading"] or "no 'Loading N mods' line found in the log"]
    body += result["mods"]
    out = [f"::notice title={escape_property('smoke ' + config)}::{escape_data(chr(10).join(body))}"]
    for problem in result["problems"][:MAX_PROBLEMS]:
        out.append(f"::error title={escape_property('log problem')}::{escape_data(problem[:500])}")
    if len(result["problems"]) > MAX_PROBLEMS:
        out.append(f"::warning::{len(result['problems']) - MAX_PROBLEMS} more problem lines in the log")
    return out


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[2], encoding="utf-8", errors="replace") as handle:
        result = summarize(handle.readlines())
    print("\n".join(annotations(argv[1], result)))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
