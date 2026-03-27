# Automation Quality Checker


Static analysis for automation tests: scan JavaScript, TypeScript, Python, and Java test files for common quality problems, then export **JSON**, **TXT**, or **Markdown** for CI and dashboards. A small **web UI** can drive the same Java engine locally.

[![CI](https://github.com/YOUR_USERNAME/Tool_Creation/actions/workflows/ci.yml/badge.svg)](https://github.com/YOUR_USERNAME/Tool_Creation/actions/workflows/ci.yml)

Replace `YOUR_USERNAME` in the badge URL with your GitHub username if you use this repo on GitHub.

![Java](https://img.shields.io/badge/Java-11+-orange)
![Python](https://img.shields.io/badge/Python-3.8+-blue)
![License](https://img.shields.io/badge/License-MIT-green)

---

## Contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Repository layout](#repository-layout)
- [CLI: compile and run](#cli-compile-and-run)
- [CLI options (full list)](#cli-options-full-list)
- [Console output](#console-output)
- [JSON report](#json-report)
- [Web UI](#web-ui)
- [Locator Health Monitor](#locator-health-monitor-separate-tool)
- [Limitations](#limitations)
- [Exit codes](#exit-codes)

---

## What it does

`AutomationQualityChecker` walks your chosen files or folders and reports findings in **five categories**:


| Issue ID | What it means |
|----------|----------------|
| `hard_wait` | Fixed sleeps / timed waits (`Thread.sleep`, `waitForTimeout`, `cy.wait`, `time.sleep`, etc.) |
| `hardcoded_test_data` | Inline credentials, tokens, emails, URLs in test-like code |
| `duplicate_locator` | The same locator string appears more than once (duplicate tracking) |
| `poor_assertion` | Weak checks (e.g. truthy-only assertions), tuned by `--automation-language` |
| `unused_function` | Declared helpers that are never referenced |

Optional extras in reports:

- **Suggested fixes** — many findings include a `suggested_fix` string (e.g. prefer explicit waits over hard waits).
- **Flaky risk (per file)** — `flaky_test_analysis` scores files using hard-wait counts and retry-style patterns (not a substitute for real flake detection).

Detection is **regex-based** (no test runner required). Tune behavior with `--automation-language` and `--enable-issues`.

---

## Requirements

| Tool | When you need it |
|------|------------------|
| **Java 11+** | Always — to compile and run `AutomationQualityChecker` |
| **Python 3.8+** | Only if you use `ui_live_server.py` for the web UI |

---

## Quick start

```bash
# 1) Compile
javac AutomationQualityChecker.java

# 2) Scan current directory, write JSON, show line details in the terminal
java AutomationQualityChecker --show-lines --json report.json .

# 3) Optional: web UI (separate terminal)
python3 ui_live_server.py
# Open http://127.0.0.1:8787/AutomationQualityChecker_UI_Design.html
```

---

## Repository layout

| File | Role |
|------|------|
| `AutomationQualityChecker.java` | Main CLI: scanning, detectors, summaries, JSON/TXT/MD output |
| `AutomationQualityChecker_UI_Design.html` | Browser UI: configure targets, issue types, view reports |
| `ui_live_server.py` | Local HTTP server: compiles/runs Java, streams logs (SSE), returns `report.json` to the UI |
| `LocatorHealthMonitor.java` | **Separate** tool for locator brittleness / duplication (see [below](#locator-health-monitor-separate-tool)) |
| `.github/workflows/ci.yml` | CI: compile and run a self-scan |

---

## CLI: compile and run

```bash
javac AutomationQualityChecker.java
java AutomationQualityChecker [options] <targets...>
```

- **`<targets...>`** — One or more files and/or directories. Directories are scanned recursively (filtered by `--extensions`).
- **Outputs** — Only written when you pass `--json`, `--txt`, and/or `--md`.

---

## CLI options (full list)

| Option | Purpose |
|--------|---------|
| `--extensions <.js,.ts,...>` | Comma-separated file extensions (normalized to lowercase with a leading dot). Default includes `.js`, `.ts`, `.jsx`, `.tsx`, `.py`. |
| `--json <file>` | Write machine-readable report (summary, findings, duplicate groups, refactor hints, impacted tests, flaky analysis). |
| `--txt <file>` | Write plain-text report. |
| `--md <file>` | Write Markdown report. |
| `--show-lines` | Print each finding as `file:line:column \| detail` in the console. |
| `--max-lines-per-issue <n>` | Cap how many lines per issue type are printed with `--show-lines`. |
| `--enable-issues <csv>` | **Restrict** which issue IDs run (e.g. `hard_wait,poor_assertion`). If omitted, **all** five types run. |
| `--automation-language <name>` | Pattern set for assertions/locators: `all`, `java`, `javascript_playwright`, `javascript_cypress`, `python` (or empty for defaults). |
| `--hard-wait-preset <name>` | e.g. `selenium` — changes how hard waits are interpreted. |
| `--selenium-assertion-as-hard-wait` | With selenium hard-wait preset, optionally treat certain assertions as sync points (see tool behavior). |
| `--changed-function <name>` | Repeatable — function names to search for in tests (impact / reference listing). |
| `--changed-file <path>` | Optional file context for changed-function analysis. |
| `--max-impacted-per-function <n>` | Limit lines printed per changed function for impacted tests. |

**Examples**

```bash
# Only hard waits and poor assertions
java AutomationQualityChecker --enable-issues hard_wait,poor_assertion --json report.json ./tests

# Playwright-oriented assertion patterns
java AutomationQualityChecker --automation-language javascript_playwright --json report.json .

# All report formats
java AutomationQualityChecker --json report.json --txt report.txt --md report.md --show-lines .
```

---

## Console output

Every run prints a **short summary** (counts per category + files scanned). With `--show-lines`, findings print in a **fixed order** by issue type for stable diffs in CI.

---

## JSON report

Top-level keys:

| Key | Meaning |
|-----|---------|
| `summary` | Counts: `hard_wait_found`, `hardcoded_test_data`, `duplicate_locators`, `poor_assertions`, `unused_functions`, `total_files_scanned` |
| `findings` | Object keyed by issue id; each value is an array of finding objects |
| `duplicate_locator_groups` | Groups of duplicate locator occurrences |
| `duplicate_refactor_intelligence` | Ranked suggestions to consolidate duplicate locators |
| `impacted_tests` | Populated when you use `--changed-function` |
| `flaky_test_analysis` | Array of per-file objects: `file`, `flaky_test_risk` (`LOW` / `MEDIUM` / `HIGH`), `hard_wait_count`, `retry_usage_detected` |

Each finding object includes:

- `file`, `line`, `column`, `issue`, `detail`
- `suggested_fix` (optional string) when the tool can suggest a remediation

---


## Web UI

1. Start the server: `python3 ui_live_server.py`
2. Open: `http://127.0.0.1:8787/AutomationQualityChecker_UI_Design.html`

The UI lets you set scan folders, extensions, automation language, and **which issue types to include** (mapped to `--enable-issues`). It loads the same `report.json` the CLI would write. **Run Live Scan** triggers compile + run on the server; **Run Demo Data** loads sample JSON without scanning.

**API (used by the UI)**

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | Server alive |
| POST | `/api/run` | Body: JSON config → compile and run checker |
| GET | `/api/logs/{runId}` | Server-Sent Events log stream |
| GET | `/api/result/{runId}` | When done: parsed report payload |
| POST | `/api/stop/{runId}` | Stop a run |

---


## Locator Health Monitor (separate tool)

`LocatorHealthMonitor.java` is **not** the main quality checker. It focuses on **selector health**: brittle XPaths, duplication, outdated patterns. Build and run it on its own:

```bash
javac LocatorHealthMonitor.java
java LocatorHealthMonitor .
java LocatorHealthMonitor --json locator_report.json .
```

---

## Limitations

- Rules are **heuristic**; expect some false positives and false negatives.
- Ordering of findings is **deterministic** for repeatable CI output.
- The flaky-risk section is a **static signal**, not execution-based flake detection.

---

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `1` | Failed to write an output file |
| `2` | Invalid CLI arguments |
