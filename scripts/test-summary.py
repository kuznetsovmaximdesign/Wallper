#!/usr/bin/env python3
"""Prints one line per UI test (status, time) plus the first lines of each failure."""
import glob
import sys
import xml.etree.ElementTree as ET

root = sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/androidTest-results"
files = glob.glob(f"{root}/**/*.xml", recursive=True)
if not files:
    print(f"No test results under {root}")
for path in files:
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        continue
    for case in tree.iter("testcase"):
        name = f"{case.get('classname', '').split('.')[-1]}.{case.get('name')}"
        failure = case.find("failure")
        if failure is None:
            failure = case.find("error")
        status = "FAIL" if failure is not None else ("SKIP" if case.find("skipped") is not None else "PASS")
        print(f"{status}  {name}  ({float(case.get('time', 0)):.1f}s)")
        if failure is not None:
            text = (failure.get("message") or failure.text or "").strip().splitlines()
            for line in [l for l in text if not l.strip().startswith("at ")][:4]:
                print(f"      {line.strip()[:300]}")
