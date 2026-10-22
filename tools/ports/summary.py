#!/usr/bin/env python3
"""The nightly check's results as a table for the run's summary: summary.py <results.json> <column>."""

import json
import sys


def table(results, column):
    passed = sum(1 for r in results.values() if r.get("passed"))
    lines = [f"{passed} of {len(results)} passed.", "", f"| Port | {column} | What was seen |", "|---|---|---|"]
    lines += [f"| {k} | {'yes' if v.get('passed') else 'no'} | {str(v.get('detail', '')).replace('|', '/')} |" for k, v in sorted(results.items())]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    with open(sys.argv[1], encoding="utf-8") as f:
        sys.stdout.write(table(json.load(f), sys.argv[2]))
