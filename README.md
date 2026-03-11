# Automation Quality Checker

`AutomationQualityChecker` is a standalone Java-based automation test quality scanner. It analyzes source files and reports common reliability/maintainability issues, then optionally exports structured reports for dashboards and CI use.

This repository includes:

- `AutomationQualityChecker.java` (core CLI scanner)
- `AutomationQualityChecker_UI_Design.html` (interactive dashboard UI)
- `ui_live_server.py` (local backend to run CLI from UI and stream logs)

## What It Detects

The scanner reports six issue categories:

- `hard_wait` - explicit sleeps/waits (for example `waitForTimeout`, `sleep`, `cy.wait`)
- `hardcoded_test_data` - inline credentials/data literals in likely test contexts
- `duplicate_locator` - same locator reused across files/areas
- `poor_assertion` - weak assertions that provide low verification value
- `unused_function` - declared helper/functions not referenced
- `missing_validation` - test action flows with no nearby assertion/validation

## Supported Languages and Extensions

Default extensions:

- `.js`
- `.ts`
- `.jsx`
- `.tsx`
- `.py`

Override with `--extensions` to scan other text-based automation files.

## Requirements

- Java 11+
- Python 3.8+ (only if you use the live UI backend)

## Project Structure

- `AutomationQualityChecker.java` - parser, detectors, summary, and report serialization
- `AutomationQualityChecker_UI_Design.html` - static frontend to configure runs and visualize results
- `ui_live_server.py` - local HTTP API:
  - compiles/runs Java checker
  - streams execution logs via Server-Sent Events (SSE)
  - returns parsed `report.json` payload to UI

## CLI Usage

Compile:

```bash
javac AutomationQualityChecker.java
```

Run:

```bash
java AutomationQualityChecker [options] <targets...>
```

`<targets...>` can be one or more files or directories.

## CLI Options

- `--extensions <.js,.ts,...>`  
  Comma-separated extensions; values are normalized to lowercase and dot-prefixed.

- `--json <output.json>`  
  Write JSON payload with summary, findings, duplicate analysis, and impacted tests.

- `--txt <output.txt>`  
  Write detailed plain-text report grouped by issue.

- `--md <output.md>`  
  Write detailed Markdown report grouped by issue.

- `--show-lines`  
  Print issue lines to terminal in `file:line:column | detail` format.

- `--max-lines-per-issue <n>`  
  Limit number of detailed console lines printed per issue category.

- `--changed-function <name>` (repeatable)  
  Function(s) to map test-impact references.

- `--changed-file <path>`  
  Optional path used to validate changed function names.

- `--max-impacted-per-function <n>`  
  Limit impacted-reference lines shown in console output.

## CLI Examples

Basic recursive scan:

```bash
java AutomationQualityChecker .
```

Scan selected folders with detailed console lines:

```bash
java AutomationQualityChecker --show-lines --max-lines-per-issue 20 tests src
```

Custom extension set:

```bash
java AutomationQualityChecker --extensions .js,.ts,.py .
```

Generate all report types:

```bash
java AutomationQualityChecker --json report.json --txt report.txt --md report.md .
```

Impacted test lookup for changed functions:

```bash
java AutomationQualityChecker \
  --changed-function loginUser \
  --changed-function buildRequest \
  --changed-file src/utils/api.ts \
  --max-impacted-per-function 15 \
  .
```

## Console Output

The CLI always prints summary counters:

- Hard Wait Found
- Test Data Hardcoding
- Duplicate Locators
- Poor Assertions
- Unused Functions
- Missing Validations
- Files Scanned

With `--show-lines`, detailed findings are printed in fixed issue order for stable triage output.

## JSON Report Schema (`--json`)

Top-level keys in generated payload:

- `summary` (object)
- `findings` (object keyed by issue id)
- `duplicate_locator_groups` (object keyed by locator string)
- `duplicate_refactor_intelligence` (array)
- `impacted_tests` (object keyed by changed function name)

`summary` fields:

- `hard_wait_found`
- `hardcoded_test_data`
- `duplicate_locators`
- `poor_assertions`
- `unused_functions`
- `missing_validations`
- `total_files_scanned`

`findings[issue]` entry shape:

- `file` (string)
- `line` (number)
- `column` (number)
- `issue` (string)
- `detail` (string)

`duplicate_refactor_intelligence` entry shape:

- `locator`
- `occurrences`
- `distinct_files`
- `top_module`
- `priority`
- `recommendation`

## Live UI Dashboard

The HTML dashboard maps UI controls to CLI flags and displays scan output with tabs, metrics, and logs.

### Start Live UI

```bash
python3 ui_live_server.py
```

Open:

- `http://127.0.0.1:8787/AutomationQualityChecker_UI_Design.html`

### UI Features

- Configure scan targets/extensions and changed-function inputs
- Trigger real run (`Run Live Scan`) or demo payload (`Run Demo Data`)
- Stream build/run logs in real time
- Issue tabs with counts and detailed entries
- Health check indicator using `/api/health`

### Backend API Endpoints (used by UI)

- `GET /api/health` - server status
- `POST /api/run` - compile and run checker with payload
- `GET /api/logs/{runId}` - SSE log stream
- `GET /api/result/{runId}` - run completion state + report payload
- `POST /api/stop/{runId}` - stop active run

## Behavior and Limitations

- Detection is heuristic and regex-based (fast and dependency-free).
- False positives/negatives are possible; treat results as quality signals.
- Report ordering is deterministic for stable outputs in CI and code reviews.

## Exit Codes

- `0` - successful completion
- `1` - output/report write failure
- `2` - invalid CLI arguments

## Quick Start

```bash
javac AutomationQualityChecker.java
java AutomationQualityChecker --show-lines --json report.json .
python3 ui_live_server.py
```
