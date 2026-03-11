# AutomationQualityChecker

`AutomationQualityChecker` is a standalone Java CLI tool that scans test automation code and reports common quality issues:

- Hard-coded waits/sleeps
- Duplicate locators
- Poor/weak assertions
- Unused functions
- Tests with missing validations

It supports JavaScript/TypeScript and Python files by default and can optionally export reports as JSON, TXT, and Markdown.

## Features

- Recursive scan of files and directories
- Configurable file extensions
- Deterministic (sorted) output for stable reports
- Console summary with optional detailed lines
- Optional impacted-test lookup for changed functions

## Supported File Extensions

By default:

- `.js`
- `.ts`
- `.jsx`
- `.tsx`
- `.py`

Override with `--extensions`.

## Requirements

- Java 11 or later (uses `Files.readString` and `Files.writeString`)

## Compile

```bash
javac AutomationQualityChecker.java
```

This creates `AutomationQualityChecker.class` and nested class files.

## Usage

```bash
java AutomationQualityChecker [options] <targets...>
```

`<targets...>` can be one or more files/directories.

## CLI Options

- `--extensions <.js,.ts,...>` Comma-separated extensions to scan
- `--json <output.json>` Write detailed JSON report
- `--txt <output.txt>` Write detailed plain-text report
- `--md <output.md>` Write detailed Markdown report
- `--show-lines` Print per-finding file/line details in terminal
- `--max-lines-per-issue <n>` Limit console findings per issue
- `--changed-function <name>` Changed function to map impacted tests (repeatable)
- `--changed-file <path>` Optional file used to validate changed function names
- `--max-impacted-per-function <n>` Limit impacted lines per changed function

## Examples

### Basic scan

```bash
java AutomationQualityChecker .
```

### Scan specific folders with detailed console output

```bash
java AutomationQualityChecker --show-lines --max-lines-per-issue 20 tests src
```

### Custom extensions

```bash
java AutomationQualityChecker --extensions .js,.ts,.py .
```

### Export all report formats

```bash
java AutomationQualityChecker --json report.json --txt report.txt --md report.md .
```

### Impacted tests for changed functions

```bash
java AutomationQualityChecker \
  --changed-function loginUser \
  --changed-function buildRequest \
  --changed-file src/utils/api.ts \
  --max-impacted-per-function 15 \
  .
```

## Console Output (Summary)

The tool always prints a summary:

- Hard Wait Found
- Duplicate Locators
- Poor Assertions
- Unused Functions
- Missing Validations
- Files Scanned

Use `--show-lines` to print individual findings (`file:line | detail`).

## Report Structure

### JSON (`--json`)

Top-level keys:

- `summary`
- `findings`
- `duplicate_locator_groups`
- `impacted_tests`

### TXT (`--txt`)

- Human-readable summary
- Findings grouped by issue type

### Markdown (`--md`)

- Same grouping as TXT in Markdown format

## Detection Notes

The checker uses regex-based heuristics. That means:

- It is fast and dependency-free
- Some false positives/negatives are possible
- Results are best used as quality signals, not strict static-analysis guarantees

## Exit Behavior

- Invalid CLI arguments: exits with code `2`
- Output write failure: exits with code `1`
- Successful scan: normal completion (code `0`)

## Quick Start

```bash
javac AutomationQualityChecker.java
java AutomationQualityChecker --show-lines --json report.json .
```
