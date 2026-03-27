import { ISSUE_CONFIG } from "./constants";
import type { IssueId, ScanPayload } from "./types";

export function toLines(raw: string): string[] {
  return String(raw || "")
    .split(/\r?\n/)
    .map((x) => x.trim())
    .filter(Boolean);
}

export function formatLocation(file: string, line: number, col: number): string {
  let path = String(file || "unknown file").replace(/\\/g, "/").trim();
  const srcIdx = path.indexOf("src/");
  if (srcIdx >= 0) path = "/" + path.substring(srcIdx);
  else if (path && !path.startsWith("/")) path = "/" + path;
  return `${path}:${line}:${col}`;
}

export function getIssueCount(payload: ScanPayload | null | undefined, issueId: IssueId): number {
  const findings = payload?.findings?.[issueId];
  if (Array.isArray(findings)) return findings.length;
  const summaryKey = ISSUE_CONFIG.find((x) => x.id === issueId)?.summaryKey;
  return Number(payload?.summary?.[summaryKey ?? ""] ?? 0);
}

export function getEmptyPayload(): ScanPayload {
  return {
    summary: {
      total_files_scanned: 0,
      hard_wait_found: 0,
      hardcoded_test_data: 0,
      duplicate_locators: 0,
      duplicate_step_blocks: 0,
      poor_assertions: 0,
      unused_functions: 0,
      lengthy_test_scripts: 0,
      locator_index_one_hits: 0,
    },
    findings: {
      hard_wait: [],
      hardcoded_test_data: [],
      duplicate_locator: [],
      duplicate_step_block: [],
      poor_assertion: [],
      unused_function: [],
      lengthy_test_script: [],
      locator_index_one: [],
    },
  };
}
