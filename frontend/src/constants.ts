import type { IssueId } from "./types";

export const ISSUE_CONFIG: {
  id: IssueId;
  label: string;
  summaryKey: string;
}[] = [
  { id: "hard_wait", label: "Hard Wait", summaryKey: "hard_wait_found" },
  { id: "hardcoded_test_data", label: "Hardcoded Data", summaryKey: "hardcoded_test_data" },
  { id: "duplicate_locator", label: "Duplicate Locator", summaryKey: "duplicate_locators" },
  { id: "duplicate_step_block", label: "Consecutive duplicate step", summaryKey: "duplicate_step_blocks" },
  { id: "poor_assertion", label: "Poor Assertion", summaryKey: "poor_assertions" },
  { id: "unused_function", label: "Unused Function", summaryKey: "unused_functions" },
  { id: "lengthy_test_script", label: "Long test (100+ lines)", summaryKey: "lengthy_test_scripts" },
  { id: "locator_index_one", label: "Positional index", summaryKey: "locator_index_one_hits" },
];

export const ISSUE_IDS: IssueId[] = ISSUE_CONFIG.map((x) => x.id);

export type LangKey = "java" | "javascript_playwright" | "javascript_cypress" | "python";

export const DETECTION_PATTERNS: Record<
  LangKey,
  {
    label: string;
    hardWait: string[];
    hardcodedData: string[];
    locator: string[];
    weakAssertion: string[];
    unusedFunction: string[];
    assertion: string[];
  }
> = {
  java: {
    label: "Java (Selenium)",
    hardWait: ["Thread.sleep("],
    hardcodedData: ["password", "token", "email", "url"],
    locator: ["By.id(", "By.xpath(", "By.cssSelector("],
    weakAssertion: ["assert(", "assertTrue(true)", "assertEquals(..., true)"],
    unusedFunction: ["function ", "def "],
    assertion: ["assert", "assertTrue", "assertEquals", "assertThat"],
  },
  javascript_playwright: {
    label: "Playwright JS/TS",
    hardWait: ["waitForTimeout("],
    hardcodedData: ["password", "token", "email", "url"],
    locator: ["locator("],
    weakAssertion: ["expect().toBeTruthy(", "expect().toBeDefined("],
    unusedFunction: ["function ", "const ... =>"],
    assertion: ["expect", "toBe", "toEqual", "toContain"],
  },
  javascript_cypress: {
    label: "Cypress",
    hardWait: ["cy.wait("],
    hardcodedData: ["password", "token", "email", "url"],
    locator: ["cy.get("],
    weakAssertion: [".should('exist')", ".should('be.visible')"],
    unusedFunction: ["function ", "const ... =>"],
    assertion: ["should", "expect"],
  },
  python: {
    label: "Python (Selenium / Playwright)",
    hardWait: ["time.sleep("],
    hardcodedData: ["password", "token", "email", "url"],
    locator: ["find_element("],
    weakAssertion: ["assert ", "expect().to_be_truthy("],
    unusedFunction: ["def "],
    assertion: ["assert", "expect", "to_be_attached", "to_be_visible"],
  },
};

export const PATTERN_ROWS: { key: keyof (typeof DETECTION_PATTERNS)["java"]; label: string }[] = [
  { key: "hardWait", label: "Hard waits" },
  { key: "hardcodedData", label: "Hardcoded test data" },
  { key: "locator", label: "Duplicate locators" },
  { key: "weakAssertion", label: "Weak assertions" },
  { key: "unusedFunction", label: "Unused helper functions" },
  { key: "assertion", label: "Assertion keywords" },
];

export type AutomationLang = "all" | LangKey;

export const AUTOMATION_LANGUAGE_CONFIG: Record<
  AutomationLang,
  { extensions: string; hardWaitPreset: string; enabledIssues: IssueId[] }
> = {
  all: { extensions: ".java,.js,.ts,.jsx,.tsx,.py", hardWaitPreset: "javascript", enabledIssues: ISSUE_IDS },
  java: { extensions: ".java", hardWaitPreset: "selenium", enabledIssues: ISSUE_IDS },
  javascript_playwright: { extensions: ".js,.ts,.jsx,.tsx", hardWaitPreset: "javascript", enabledIssues: ISSUE_IDS },
  javascript_cypress: { extensions: ".js,.ts,.jsx,.tsx", hardWaitPreset: "javascript", enabledIssues: ISSUE_IDS },
  python: { extensions: ".py", hardWaitPreset: "python", enabledIssues: ISSUE_IDS },
};
