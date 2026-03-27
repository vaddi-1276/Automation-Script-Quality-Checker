export type IssueId =
  | "hard_wait"
  | "hardcoded_test_data"
  | "duplicate_locator"
  | "duplicate_step_block"
  | "poor_assertion"
  | "unused_function"
  | "lengthy_test_script"
  | "locator_index_one";

export interface FindingRow {
  file: string;
  line: number;
  column: number;
  issue: string;
  detail: string;
}

export interface ScanPayload {
  summary: Record<string, number>;
  findings: Partial<Record<IssueId, FindingRow[]>>;
}
