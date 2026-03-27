#!/usr/bin/env python3
"""Emit AST|issue|line|col|detail lines for AutomationQualityChecker mergePythonAstFindings."""
from __future__ import annotations

import ast
import sys


def main() -> None:
    if len(sys.argv) < 2:
        return
    path = sys.argv[1]
    try:
        src = open(path, encoding="utf-8").read()
    except OSError:
        return
    try:
        tree = ast.parse(src, filename=path)
    except SyntaxError:
        return

    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef):
            continue
        name = node.name
        if not (name.startswith("test") or name.endswith("_test")):
            continue
        start = node.lineno
        end = getattr(node, "end_lineno", node.lineno) or node.lineno
        nlines = end - start + 1
        if nlines > 100:
            print(
                f"AST|lengthy_test_script|{start}|1|Python test '{name}' is {nlines} lines (more than 100; consider splitting)"
            )


if __name__ == "__main__":
    main()
