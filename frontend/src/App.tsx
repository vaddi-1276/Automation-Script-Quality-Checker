import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { flushSync } from "react-dom";
import {
  AUTOMATION_LANGUAGE_CONFIG,
  DETECTION_PATTERNS,
  ISSUE_CONFIG,
  ISSUE_IDS,
  PATTERN_ROWS,
  type AutomationLang,
} from "./constants";
import type { IssueId, ScanPayload } from "./types";
import { formatLocation, getEmptyPayload, getIssueCount, toLines } from "./utils";

const LANG_OPTIONS: { value: AutomationLang; label: string }[] = [
  { value: "all", label: "All" },
  { value: "java", label: "Java (Selenium)" },
  { value: "javascript_playwright", label: "JavaScript / TypeScript (Playwright)" },
  { value: "javascript_cypress", label: "JavaScript / TypeScript (Cypress)" },
  { value: "python", label: "Python" },
];

function buildIssueEnabledFromLang(lang: AutomationLang): Record<IssueId, boolean> {
  const config = AUTOMATION_LANGUAGE_CONFIG[lang] ?? AUTOMATION_LANGUAGE_CONFIG.all;
  const enabled = new Set(config.enabledIssues);
  return Object.fromEntries(ISSUE_IDS.map((id) => [id, enabled.has(id)])) as Record<IssueId, boolean>;
}

export default function App() {
  const [automationLanguage, setAutomationLanguage] = useState<AutomationLang>("all");
  const [scanFolder, setScanFolder] = useState("./src/pages\n./src/components");
  const [extensions, setExtensions] = useState(".java,.js,.ts,.jsx,.tsx,.py");
  const [maxLines, setMaxLines] = useState("100");
  const [maxImpacted, setMaxImpacted] = useState("5");
  const [hardWaitPreset, setHardWaitPreset] = useState("javascript");
  const [outJson, setOutJson] = useState(true);
  const [outTxt, setOutTxt] = useState(true);
  const [outMd, setOutMd] = useState(true);
  const [showLines, setShowLines] = useState(true);
  const [issueEnabled, setIssueEnabled] = useState<Record<IssueId, boolean>>(() => buildIssueEnabledFromLang("all"));
  const [payload, setPayload] = useState<ScanPayload | null>(() => getEmptyPayload());
  const [selectedIssue, setSelectedIssue] = useState<IssueId>(ISSUE_CONFIG[0].id);
  const [selectedFile, setSelectedFile] = useState<string>("__all__");
  const [logText, setLogText] = useState("Live scan logs will appear here...");
  const [running, setRunning] = useState(false);
  const runIdRef = useRef<string | null>(null);
  const esRef = useRef<EventSource | null>(null);
  const [backendStatus, setBackendStatus] = useState("Checking backend…");
  const logBoxRef = useRef<HTMLDivElement>(null);
  const [folderModalOpen, setFolderModalOpen] = useState(false);
  const [folderDraft, setFolderDraft] = useState("");
  const folderModalTextareaRef = useRef<HTMLTextAreaElement>(null);

  const [extensionsModalOpen, setExtensionsModalOpen] = useState(false);
  const [extensionsDraft, setExtensionsDraft] = useState("");
  const extensionsModalTextareaRef = useRef<HTMLTextAreaElement>(null);

  /** When false, Folder Names and Extensions CSV triggers are disabled. Default on so both are clickable. */
  const [scanInputsEditable, setScanInputsEditable] = useState(true);

  /** When multiple paths are listed, `"__all__"` scans every path; otherwise a single path from `toLines(scanFolder)`. */
  const [scanTargetChoice, setScanTargetChoice] = useState<string>("__all__");

  const folderPaths = useMemo(() => toLines(scanFolder), [scanFolder]);

  const openFolderModal = useCallback(() => {
    if (!scanInputsEditable) return;
    setFolderDraft(scanFolder);
    setFolderModalOpen(true);
  }, [scanFolder, scanInputsEditable]);

  const cancelFolderModal = useCallback(() => {
    setFolderModalOpen(false);
  }, []);

  const saveFolderModal = useCallback(() => {
    setScanFolder(folderDraft);
    setFolderModalOpen(false);
  }, [folderDraft]);

  const openExtensionsModal = useCallback(() => {
    if (!scanInputsEditable) return;
    setExtensionsDraft(extensions);
    setExtensionsModalOpen(true);
  }, [extensions, scanInputsEditable]);

  const cancelExtensionsModal = useCallback(() => {
    setExtensionsModalOpen(false);
  }, []);

  const saveExtensionsModal = useCallback(() => {
    setExtensions(extensionsDraft.trim());
    setExtensionsModalOpen(false);
  }, [extensionsDraft]);

  /** Keep wheel / trackpad scroll inside the modal textarea when it overflows. */
  const onModalFolderWheel = useCallback((e: React.WheelEvent<HTMLTextAreaElement>) => {
    const el = e.currentTarget;
    if (el.scrollHeight <= el.clientHeight) return;
    const { scrollTop, scrollHeight, clientHeight } = el;
    const dy = e.deltaY;
    const atTop = scrollTop <= 0;
    const atBottom = scrollTop + clientHeight >= scrollHeight - 1;
    if (dy < 0 && atTop) return;
    if (dy > 0 && atBottom) return;
    e.stopPropagation();
  }, []);

  useEffect(() => {
    if (!folderModalOpen && !extensionsModalOpen) return;
    const onKey = (ev: KeyboardEvent) => {
      if (ev.key !== "Escape") return;
      if (extensionsModalOpen) cancelExtensionsModal();
      else cancelFolderModal();
    };
    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", onKey);
    const id = requestAnimationFrame(() => {
      if (extensionsModalOpen) extensionsModalTextareaRef.current?.focus();
      else folderModalTextareaRef.current?.focus();
    });
    return () => {
      cancelAnimationFrame(id);
      document.body.style.overflow = "";
      window.removeEventListener("keydown", onKey);
    };
  }, [
    folderModalOpen,
    extensionsModalOpen,
    cancelFolderModal,
    cancelExtensionsModal,
  ]);

  useEffect(() => {
    if (!scanInputsEditable) {
      setFolderModalOpen(false);
      setExtensionsModalOpen(false);
    }
  }, [scanInputsEditable]);

  useEffect(() => {
    setScanTargetChoice((prev) => {
      if (folderPaths.length <= 1) return "__all__";
      if (prev === "__all__") return "__all__";
      return folderPaths.includes(prev) ? prev : "__all__";
    });
  }, [folderPaths]);

  useEffect(() => {
    const config = AUTOMATION_LANGUAGE_CONFIG[automationLanguage] ?? AUTOMATION_LANGUAGE_CONFIG.all;
    setExtensions(config.extensions);
    setHardWaitPreset(config.hardWaitPreset);
    setIssueEnabled(buildIssueEnabledFromLang(automationLanguage));
  }, [automationLanguage]);

  const appendLog = useCallback((line: string) => {
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, "0");
    const mm = String(now.getMinutes()).padStart(2, "0");
    const ss = String(now.getSeconds()).padStart(2, "0");
    const prefix = `[${hh}:${mm}:${ss}] `;
    setLogText((prev) => {
      let base = prev;
      if (!prev || prev.includes("will appear here")) {
        base = "";
      } else if (base.length > 0 && !base.endsWith("\n")) {
        base += "\n";
      }
      return base + prefix + line + "\n";
    });
    requestAnimationFrame(() => {
      const el = logBoxRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }, []);

  const checkHealth = useCallback(async () => {
    try {
      const resp = await fetch("/api/health");
      if (!resp.ok) throw new Error(String(resp.status));
      const data = (await resp.json()) as { openaiConfigured?: boolean };
      setBackendStatus(
        data.openaiConfigured
          ? "Backend: connected · OpenAI ready"
          : "Backend: connected · OpenAI: set OPENAI_API_KEY on server"
      );
    } catch {
      setBackendStatus("Backend status: offline (start ui_live_server.py)");
    }
  }, []);

  useEffect(() => {
    checkHealth();
    const t = setInterval(checkHealth, 5000);
    return () => clearInterval(t);
  }, [checkHealth]);

  useEffect(() => {
    return () => {
      esRef.current?.close();
      esRef.current = null;
    };
  }, []);

  const getEnabledIssues = () => ISSUE_IDS.filter((id) => issueEnabled[id]);

  const getRunTargets = () => {
    const lines = folderPaths;
    if (lines.length === 0) return [];
    if (lines.length === 1 || scanTargetChoice === "__all__") return lines;
    if (lines.includes(scanTargetChoice)) return [scanTargetChoice];
    return lines;
  };

  const collectRunPayload = () => ({
    targets: getRunTargets(),
    extensions: extensions.trim(),
    changedFunctions: [] as string[],
    changedFile: "",
    maxLinesPerIssue: Number(maxLines || 0),
    maxImpactedPerFunction: Number(maxImpacted || 0),
    outputJson: outJson,
    outputTxt: outTxt,
    outputMd: outMd,
    showLines,
    hardWaitPreset: hardWaitPreset.trim(),
    enabledIssues: getEnabledIssues(),
    automationLanguage: automationLanguage.trim(),
    pythonAst: true,
    openAiInTool: false,
  });

  const waitForResult = async (runId: string) => {
    for (let i = 0; i < 90; i++) {
      const resp = await fetch(`/api/result/${encodeURIComponent(runId)}`);
      if (resp.ok) {
        const data = (await resp.json()) as { done?: boolean; payload?: ScanPayload | null };
        if (data.done) return data.payload ?? null;
      }
      await new Promise((r) => setTimeout(r, 400));
    }
    return null;
  };

  const runLiveScan = async () => {
    if (running) return;
    setRunning(true);
    runIdRef.current = null;
    esRef.current?.close();
    flushSync(() => setLogText(""));
    appendLog(`Issue types in scan: ${getEnabledIssues().join(", ")}`);
    appendLog("Submitting run request...");
    try {
      const runResp = await fetch("/api/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(collectRunPayload()),
      });
      const runData = (await runResp.json()) as { runId?: string; error?: string };
      if (!runResp.ok || !runData.runId) {
        throw new Error(runData.error || "Unable to start run");
      }
      const runId = runData.runId;
      runIdRef.current = runId;
      appendLog(`Run started (${runId}).`);

      await new Promise<void>((resolve) => {
        const es = new EventSource(`/api/logs/${encodeURIComponent(runId)}`);
        esRef.current = es;
        es.onmessage = (event) => {
          if (event.data === "__RUN_DONE__") {
            es.close();
            esRef.current = null;
            resolve();
            return;
          }
          appendLog(event.data);
        };
        es.onerror = () => {
          appendLog("Log stream interrupted.");
          es.close();
          esRef.current = null;
          resolve();
        };
      });

      const result = await waitForResult(runId);
      if (result) {
        setPayload(result);
        appendLog("Report rendered successfully.");
      } else {
        appendLog("Run completed, but report payload was empty.");
      }
    } catch (e) {
      appendLog(`Run failed: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setRunning(false);
      runIdRef.current = null;
    }
  };

  const stopLiveScan = async () => {
    const id = runIdRef.current;
    if (!id) {
      appendLog("No active run to stop.");
      return;
    }
    try {
      await fetch(`/api/stop/${encodeURIComponent(id)}`, { method: "POST" });
      appendLog("Stop requested.");
    } catch (e) {
      appendLog(`Stop failed: ${e instanceof Error ? e.message : String(e)}`);
    }
  };

  const runDemoData = () => {
    const demo: ScanPayload = {
      summary: {
        hard_wait_found: 4,
        hardcoded_test_data: 3,
        duplicate_locators: 2,
        duplicate_step_blocks: 1,
        poor_assertions: 5,
        unused_functions: 1,
        lengthy_test_scripts: 1,
        locator_index_one_hits: 2,
        total_files_scanned: 19,
      },
      findings: {
        hard_wait: [
          {
            file: "src/pages/login.spec.ts",
            line: 24,
            column: 9,
            issue: "hard_wait",
            detail: "waitForTimeout(3000) used before assertion.",
          },
          {
            file: "src/tests/order.test.js",
            line: 88,
            column: 5,
            issue: "hard_wait",
            detail: "sleep(2000) introduces flaky timing dependency.",
          },
        ],
        hardcoded_test_data: [
          {
            file: "src/tests/auth.spec.ts",
            line: 17,
            column: 15,
            issue: "hardcoded_test_data",
            detail: "Inline email/password string found in test.",
          },
        ],
        duplicate_locator: [
          {
            file: "src/pages/cart.page.ts",
            line: 36,
            column: 12,
            issue: "duplicate_locator",
            detail: "Locator '#submit-order' repeated across modules.",
          },
        ],
        duplicate_step_block: [
          {
            file: "src/tests/navigation.spec.ts",
            line: 31,
            column: 5,
            issue: "duplicate_step_block",
            detail: "await configuration.persistSession()",
          },
        ],
        poor_assertion: [
          {
            file: "src/tests/payment.spec.ts",
            line: 44,
            column: 10,
            issue: "poor_assertion",
            detail: "Generic truthy assertion with low signal.",
          },
        ],
        unused_function: [
          {
            file: "src/helpers/legacy.ts",
            line: 91,
            column: 1,
            issue: "unused_function",
            detail: "Function 'formatOtpLegacy' is never referenced.",
          },
        ],
        lengthy_test_script: [
          {
            file: "src/tests/monolithic.spec.ts",
            line: 1,
            column: 1,
            issue: "lengthy_test_script",
            detail: "Test script has 240 lines (more than 100); consider splitting into smaller tests or steps.",
          },
        ],
        locator_index_one: [
          {
            file: "src/pages/LoginPage.java",
            line: 44,
            column: 38,
            issue: "locator_index_one",
            detail: 'driver.findElement(By.xpath("(//input[@type=\'text\'])[1]"))',
          },
        ],
      },
    };
    setPayload(demo);
    appendLog("Demo payload loaded.");
  };

  const resetDashboard = () => {
    setSelectedIssue(ISSUE_CONFIG[0].id);
    setPayload(getEmptyPayload());
    flushSync(() => setLogText("Live scan logs will appear here..."));
    appendLog("Dashboard reset.");
  };

  const summary = payload?.summary ?? {};
  const totalFindings = ISSUE_CONFIG.reduce((sum, issue) => sum + getIssueCount(payload, issue.id), 0);
  const metricCards = [
    { label: "Files Scanned", value: summary.total_files_scanned ?? 0 },
    { label: "Hard Wait", value: summary.hard_wait_found ?? 0 },
    { label: "Hardcoded Data", value: summary.hardcoded_test_data ?? 0 },
    { label: "Duplicate Locator", value: summary.duplicate_locators ?? 0 },
    { label: "Consecutive duplicate step", value: summary.duplicate_step_blocks ?? 0 },
    { label: "Poor Assertion", value: summary.poor_assertions ?? 0 },
    { label: "Unused Function", value: summary.unused_functions ?? 0 },
    { label: "Long test (100+ lines)", value: summary.lengthy_test_scripts ?? 0 },
    { label: "Positional index", value: summary.locator_index_one_hits ?? 0 },
  ];

  const findingsList = payload?.findings?.[selectedIssue];
  const fileOptions = useMemo(() => {
    const raw = Array.isArray(findingsList) ? findingsList : [];
    const set = new Set<string>();
    for (const item of raw) {
      if (item?.file) set.add(item.file);
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b));
  }, [findingsList]);

  useEffect(() => {
    setSelectedFile("__all__");
  }, [selectedIssue]);

  useEffect(() => {
    if (selectedFile === "__all__") return;
    if (fileOptions.includes(selectedFile)) return;
    setSelectedFile("__all__");
  }, [fileOptions, selectedFile]);

  const filteredFindings = useMemo(() => {
    const raw = Array.isArray(findingsList) ? findingsList : [];
    if (selectedFile === "__all__") return raw;
    return raw.filter((x) => x.file === selectedFile);
  }, [findingsList, selectedFile]);

  const formatFileOptionLabel = (file: string) => {
    const parts = file.split("/").filter(Boolean);
    if (parts.length <= 2) return file;
    return `${parts[parts.length - 2]}/${parts[parts.length - 1]}`;
  };
  const patternLang = automationLanguage === "all" ? null : (automationLanguage as keyof typeof DETECTION_PATTERNS);
  const patternBlock = patternLang ? DETECTION_PATTERNS[patternLang] : null;

  const toggleIssue = (id: IssueId) => {
    setIssueEnabled((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  return (
    <div className="app">
      <aside className="panel sidebar">
        <h1 className="title">Automation Quality Checker</h1>
        <p className="subtitle">
          UI design mapped to CLI flags and report outputs for easier scan configuration and issue triage.
        </p>

        <div className="section-label">Automation Language</div>
        <label htmlFor="automationLanguage">Programming Language</label>
        <select
          id="automationLanguage"
          value={automationLanguage}
          onChange={(e) => setAutomationLanguage(e.target.value as AutomationLang)}
        >
          {LANG_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>

        <div className="section-label">Scan Inputs</div>
        <label className="check scan-inputs-enable">
          <input
            id="enableScanInputs"
            type="checkbox"
            checked={scanInputsEditable}
            onChange={(e) => setScanInputsEditable(e.target.checked)}
          />
          Customize folder names and extensions
        </label>
        <label htmlFor="folderNamesTrigger">Folder Names</label>
        <button
          type="button"
          id="folderNamesTrigger"
          className="folder-names-trigger"
          onClick={openFolderModal}
          disabled={!scanInputsEditable}
          aria-haspopup="dialog"
          aria-expanded={folderModalOpen}
          title={
            scanInputsEditable
              ? undefined
              : "Check the option above to enable editing folder names and extensions"
          }
        >
          <span
            className={
              scanFolder.trim()
                ? "folder-names-trigger__text"
                : "folder-names-trigger__text folder-names-trigger__text--placeholder"
            }
          >
            {scanFolder.trim() ? scanFolder : "Click to enter folder paths (one per line)"}
          </span>
        </button>

        {folderPaths.length > 1 ? (
          <>
            <label htmlFor="scanTargetSelect">Scan target for this run</label>
            <select
              id="scanTargetSelect"
              value={scanTargetChoice}
              onChange={(e) => setScanTargetChoice(e.target.value)}
              aria-describedby="scan-target-hint"
            >
              <option value="__all__">All folders</option>
              {folderPaths.map((p) => (
                <option key={p} value={p}>
                  {p}
                </option>
              ))}
            </select>
            <p id="scan-target-hint" className="scan-target-hint">
              Choose one path to scan only that folder, or All folders to include every line above.
            </p>
          </>
        ) : null}

        {folderModalOpen ? (
          <div
            className="modal-backdrop"
            role="presentation"
            onClick={cancelFolderModal}
          >
            <div
              className="modal-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="folder-modal-title"
              onClick={(e) => e.stopPropagation()}
            >
              <h2 id="folder-modal-title" className="modal-dialog__title">
                Folder Names
              </h2>
              <p className="modal-dialog__hint">One path per line. Save applies to the next scan.</p>
              <textarea
                ref={folderModalTextareaRef}
                id="folderModalTextarea"
                className="modal-folder-textarea"
                value={folderDraft}
                onChange={(e) => setFolderDraft(e.target.value)}
                onWheel={onModalFolderWheel}
                rows={10}
                spellCheck={false}
              />
              <div className="modal-dialog__actions">
                <button type="button" onClick={saveFolderModal}>
                  Save
                </button>
                <button type="button" className="secondary" onClick={cancelFolderModal}>
                  Cancel
                </button>
              </div>
            </div>
          </div>
        ) : null}

        <label htmlFor="extensionsTrigger">Extensions CSV</label>
        <button
          type="button"
          id="extensionsTrigger"
          className="folder-names-trigger folder-names-trigger--compact"
          onClick={openExtensionsModal}
          disabled={!scanInputsEditable}
          aria-haspopup="dialog"
          aria-expanded={extensionsModalOpen}
          title={
            scanInputsEditable
              ? undefined
              : "Check the option above to enable editing folder names and extensions"
          }
        >
          <span
            className={
              extensions.trim()
                ? "folder-names-trigger__text extensions-trigger__text"
                : "folder-names-trigger__text extensions-trigger__text folder-names-trigger__text--placeholder"
            }
          >
            {extensions.trim()
              ? extensions
              : "Click to enter extensions (comma-separated, e.g. .java, .ts)"}
          </span>
        </button>

        {extensionsModalOpen ? (
          <div
            className="modal-backdrop"
            role="presentation"
            onClick={cancelExtensionsModal}
          >
            <div
              className="modal-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="extensions-modal-title"
              onClick={(e) => e.stopPropagation()}
            >
              <h2 id="extensions-modal-title" className="modal-dialog__title">
                Extensions CSV
              </h2>
              <p className="modal-dialog__hint">
                Comma-separated file extensions (include the dot). Save applies to the next scan.
              </p>
              <textarea
                ref={extensionsModalTextareaRef}
                id="extensionsModalTextarea"
                className="modal-folder-textarea modal-extensions-textarea"
                value={extensionsDraft}
                onChange={(e) => setExtensionsDraft(e.target.value)}
                onWheel={onModalFolderWheel}
                rows={6}
                spellCheck={false}
              />
              <div className="modal-dialog__actions">
                <button type="button" onClick={saveExtensionsModal}>
                  Save
                </button>
                <button type="button" className="secondary" onClick={cancelExtensionsModal}>
                  Cancel
                </button>
              </div>
            </div>
          </div>
        ) : null}

        <div className="row">
          <div>
            <label htmlFor="maxLines">Max lines/issue</label>
            <input id="maxLines" type="number" value={maxLines} onChange={(e) => setMaxLines(e.target.value)} />
          </div>
          <div>
            <label htmlFor="maxImpacted">Max impacted/function</label>
            <input id="maxImpacted" type="number" value={maxImpacted} onChange={(e) => setMaxImpacted(e.target.value)} />
          </div>
        </div>


        <div className="buttons">
          <button type="button" id="liveRun" disabled={running} onClick={runLiveScan}>
            Run Live Scan
          </button>
          <button type="button" id="stopRun" className="secondary" disabled={!running} onClick={stopLiveScan}>
            Stop
          </button>
          <button type="button" id="simulateRun" disabled={running} onClick={runDemoData}>
            Run Demo Data
          </button>
          <button type="button" id="resetData" className="secondary" disabled={running} onClick={resetDashboard}>
            Reset
          </button>
        </div>

        <div className="inline-status" id="backendStatus">
          {backendStatus}
        </div>
      </aside>

      <main className="main">
        <section className="panel hero-banner">
          <div>
            <p className="hero-kicker">React Experience</p>
            <h2 className="hero-title">Make Automation Quality Insights Instantly Actionable</h2>
            <p className="hero-copy">
              Run scans, spot flaky patterns faster, and triage issues with a focused dashboard designed for speed.
            </p>
          </div>
          <div className="hero-stats" aria-label="Current scan snapshot">
            <div className="hero-stat">
              <span className="hero-stat-label">Total findings</span>
              <span className="hero-stat-value">{totalFindings}</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-label">Files scanned</span>
              <span className="hero-stat-value">{summary.total_files_scanned ?? 0}</span>
            </div>
            <div className="hero-stat">
              <span className="hero-stat-label">Active issue types</span>
              <span className="hero-stat-value">{getEnabledIssues().length}</span>
            </div>
          </div>
        </section>

        <section className="panel toolbar">
          <div>
            <div style={{ fontSize: "1rem", fontWeight: 700 }}>Quality Dashboard</div>
            <div style={{ color: "var(--muted)", fontSize: ".84rem", marginTop: "3px" }}>
              Monitor flaky patterns, triage findings, and inspect impacted tests.
            </div>
          </div>
          <div className="chips">
            <span className="chip">
              Issue Order: Hard Wait | Hardcoded Data | Duplicate Locator | Poor Assertion | Unused Function |
              Consecutive duplicate step | Long test (100+ lines) | Positional index
            </span>
            <span className="chip">Source: report.json or mock payload</span>
          </div>
        </section>

        <section className="panel dashboard-outputs" aria-label="Report outputs and issue types">
          <div className="section-label">Outputs</div>
          <div className="outputs-checks">
            <label className="check">
              <input type="checkbox" checked={outJson} onChange={(e) => setOutJson(e.target.checked)} /> JSON Report
            </label>
            <label className="check">
              <input type="checkbox" checked={outTxt} onChange={(e) => setOutTxt(e.target.checked)} /> TXT Report
            </label>
            <label className="check">
              <input type="checkbox" checked={outMd} onChange={(e) => setOutMd(e.target.checked)} /> Markdown Report
            </label>
            <label className="check">
              <input type="checkbox" checked={showLines} onChange={(e) => setShowLines(e.target.checked)} /> Show
              Console Lines
            </label>
          </div>

          <div className="section-label">Issue Types to Detect</div>
          <div id="issueTypeChecks" className="issue-checks">
            {ISSUE_CONFIG.map((issue) => (
              <label key={issue.id} className="check">
                <input
                  type="checkbox"
                  checked={issueEnabled[issue.id]}
                  onChange={() => toggleIssue(issue.id)}
                />
                {issue.label}
              </label>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="metrics" id="metrics">
            {metricCards.map((card) => (
              <div key={card.label} className="metric">
                <div className="metric-label">{card.label}</div>
                <div className="metric-value">{card.value}</div>
              </div>
            ))}
          </div>
        </section>

        <section className="panel card" aria-label="Detection patterns by language">
          <h3>Detection Patterns</h3>
          <div className="patterns-subtitle" style={{ color: "var(--muted)", fontSize: ".84rem", marginBottom: "10px" }}>
            Pick one stack to preview its keyword patterns. With <strong>All</strong>, tables stay hidden (the scan still
            applies every stack). This control is linked with <strong>Automation Language</strong> in the sidebar.
          </div>
          <div className="patterns-lang-row">
            <label htmlFor="detectionPatternLanguage">Programming language</label>
            <select
              id="detectionPatternLanguage"
              value={automationLanguage}
              onChange={(e) => setAutomationLanguage(e.target.value as AutomationLang)}
              aria-describedby="patterns-hint"
            >
              {LANG_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <p id="patterns-hint" className="issue-scan-hint" style={{ marginTop: 0, marginBottom: "12px" }}>
            Changing this dropdown or the sidebar language keeps both in sync and updates extensions and presets for the
            next scan. Select Java, Playwright, Cypress, or Python to load that stack’s pattern table below.
          </p>
          <div
            className={
              patternBlock ? "main-patterns-panel" : "main-patterns-panel patterns-idle"
            }
          >
            {!patternBlock ? null : (
              <div className="pattern-block">
                <div className="pattern-label">{patternBlock.label}</div>
                <table className="pattern-table">
                  <tbody>
                    {PATTERN_ROWS.map((row) => {
                      const raw = patternBlock[row.key];
                      const vals = Array.isArray(raw) ? raw : [];
                      if (vals.length === 0) return null;
                      return (
                        <tr key={row.key}>
                          <td className="pattern-key">{row.label}</td>
                          <td>
                            {vals.map((x: string, i: number) => (
                              <code key={`${row.key}-${i}`}>{x} </code>
                            ))}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </section>

        <section className="grid">
          <article className="panel card">
            <h3>Issue Breakdown</h3>
            <div className="tabs" id="issueTabs">
              {ISSUE_CONFIG.map((issue) => {
                const count = getIssueCount(payload, issue.id);
                const active = selectedIssue === issue.id ? "active" : "";
                return (
                  <button
                    key={issue.id}
                    type="button"
                    className={`tab ${active}`}
                    onClick={() => setSelectedIssue(issue.id)}
                  >
                    {issue.label} ({count})
                  </button>
                );
              })}
            </div>
            <div className="issue-filters" aria-label="Issue list filters">
              <div className="issue-filter">
                <label htmlFor="fileFilter">Filter by file</label>
                <select
                  id="fileFilter"
                  value={selectedFile}
                  onChange={(e) => setSelectedFile(e.target.value)}
                  disabled={fileOptions.length === 0}
                >
                  <option value="__all__">All files</option>
                  {fileOptions.map((file) => (
                    <option key={file} value={file} title={file}>
                      {formatFileOptionLabel(file)}
                    </option>
                  ))}
                </select>
                <p className="issue-filter-hint">
                  Showing {filteredFindings.length} of {Array.isArray(findingsList) ? findingsList.length : 0} findings.
                </p>
              </div>
            </div>
            <div className="issue-list" id="issueList">
              {!Array.isArray(findingsList) || findingsList.length === 0 ? (
                <div className="issue-item">
                  <div className="issue-title">No findings in this category</div>
                  <div className="issue-meta">Try another tab or run a new scan.</div>
                </div>
              ) : filteredFindings.length === 0 ? (
                <div className="issue-item">
                  <div className="issue-title">No findings for this file</div>
                  <div className="issue-meta">Pick “All files” or select another file.</div>
                </div>
              ) : (
                filteredFindings.map((item, idx) => (
                  <div key={`${item.file}-${item.line}-${idx}`} className="issue-item">
                    <div className="issue-title">
                      {idx + 1}. {item.detail || item.issue || "Issue"}
                    </div>
                    <div className="issue-meta">{formatLocation(item.file, item.line, item.column)}</div>
                  </div>
                ))
              )}
            </div>
          </article>
        </section>

        <section className="panel card">
          <h3>Live Logs</h3>
          <div id="logConsole" ref={logBoxRef} className="log-console">
            {logText}
          </div>
        </section>
      </main>
    </div>
  );
}
