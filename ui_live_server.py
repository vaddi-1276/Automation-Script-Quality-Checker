#!/usr/bin/env python3
import json
import queue
import subprocess
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parent
HTML_FILE = ROOT / "AutomationQualityChecker_UI_Design.html"
MAX_JAVA_INT = 2_147_483_647

RUNS = {}
RUNS_LOCK = threading.Lock()


class RunState:
    def __init__(self, run_id: str):
        self.run_id = run_id
        self.log_queue = queue.Queue()
        self.done = False
        self.process = None
        self.result_payload = None

    def log(self, line: str):
        self.log_queue.put(line.rstrip("\n"))


def read_output(pipe, run_state: RunState, prefix: str):
    try:
        for line in iter(pipe.readline, ""):
            if not line:
                break
            run_state.log(f"{prefix} {line.rstrip()}")
    finally:
        pipe.close()


def build_command(body):
    def parse_bounded_int(raw_value, minimum=0, maximum=MAX_JAVA_INT):
        try:
            value = int(raw_value)
        except (TypeError, ValueError):
            return minimum
        if value < minimum:
            return minimum
        if value > maximum:
            return maximum
        return value

    cmd = ["java", "AutomationQualityChecker"]
    extensions = body.get("extensions", "").strip()
    if extensions:
        cmd += ["--extensions", extensions]

    if body.get("outputJson", True):
        cmd += ["--json", "report.json"]
    if body.get("outputTxt", True):
        cmd += ["--txt", "report.txt"]
    if body.get("outputMd", True):
        cmd += ["--md", "report.md"]

    if body.get("showLines", True):
        cmd.append("--show-lines")

    hard_wait_preset = str(body.get("hardWaitPreset", "")).strip()
    if hard_wait_preset:
        cmd += ["--hard-wait-preset", hard_wait_preset]

    automation_language = str(body.get("automationLanguage", "")).strip()
    if automation_language:
        cmd += ["--automation-language", automation_language]

    if body.get("seleniumAssertionAsHardWait", False):
        cmd.append("--selenium-assertion-as-hard-wait")

    enabled_issues = body.get("enabledIssues", [])
    if isinstance(enabled_issues, list) and enabled_issues:
        cmd += ["--enable-issues", ",".join(str(x) for x in enabled_issues)]

    max_lines = parse_bounded_int(body.get("maxLinesPerIssue", 0))
    if max_lines > 0:
        cmd += ["--max-lines-per-issue", str(max_lines)]

    changed_functions = body.get("changedFunctions", [])
    for fn in changed_functions:
        if str(fn).strip():
            cmd += ["--changed-function", str(fn).strip()]

    changed_file = str(body.get("changedFile", "")).strip()
    if changed_file:
        cmd += ["--changed-file", changed_file]

    max_impacted = parse_bounded_int(body.get("maxImpactedPerFunction", 0))
    if max_impacted > 0:
        cmd += ["--max-impacted-per-function", str(max_impacted)]

    targets = body.get("targets", [])
    if not targets:
        folder = str(body.get("folder", ".")).strip() or "."
        targets = [folder]
    cmd += [str(t).strip() for t in targets if str(t).strip()]
    return cmd


def compile_and_run(run_state: RunState, body):
    try:
        run_state.log("[server] Compiling AutomationQualityChecker.java ...")
        compile_proc = subprocess.run(
            ["javac", "AutomationQualityChecker.java"],
            cwd=ROOT,
            capture_output=True,
            text=True
        )
        if compile_proc.stdout.strip():
            run_state.log(f"[javac] {compile_proc.stdout.strip()}")
        if compile_proc.stderr.strip():
            run_state.log(f"[javac-err] {compile_proc.stderr.strip()}")
        if compile_proc.returncode != 0:
            run_state.log(f"[server] Compile failed (exit={compile_proc.returncode})")
            run_state.done = True
            return

        cmd = build_command(body)
        run_state.log("[server] Starting: " + " ".join(cmd))

        proc = subprocess.Popen(
            cmd,
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1
        )
        run_state.process = proc

        out_thread = threading.Thread(target=read_output, args=(proc.stdout, run_state, "[out]"), daemon=True)
        err_thread = threading.Thread(target=read_output, args=(proc.stderr, run_state, "[err]"), daemon=True)
        out_thread.start()
        err_thread.start()

        code = proc.wait()
        out_thread.join(timeout=2)
        err_thread.join(timeout=2)
        run_state.log(f"[server] Run completed with exit code {code}.")

        report_path = ROOT / "report.json"
        if report_path.exists():
            try:
                run_state.result_payload = json.loads(report_path.read_text(encoding="utf-8"))
                run_state.log("[server] Loaded report.json into dashboard payload.")
            except Exception as ex:
                run_state.log(f"[server] Failed to parse report.json: {ex}")
        else:
            run_state.log("[server] report.json not found after run.")
    except Exception as ex:
        run_state.log(f"[server] Run failure: {ex}")
    finally:
        run_state.done = True


class Handler(BaseHTTPRequestHandler):
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _json(self, data, status=200):
        payload = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ("/", "/AutomationQualityChecker_UI_Design.html"):
            content = HTML_FILE.read_bytes()
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        if path == "/api/health":
            self._json({"ok": True, "message": "ui_live_server running"})
            return

        if path.startswith("/api/logs/"):
            run_id = unquote(path.split("/api/logs/", 1)[1])
            with RUNS_LOCK:
                run_state = RUNS.get(run_id)
            if not run_state:
                self._json({"error": "run not found"}, status=404)
                return

            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()

            while True:
                if run_state.done and run_state.log_queue.empty():
                    done_msg = b"data: __RUN_DONE__\n\n"
                    self.wfile.write(done_msg)
                    self.wfile.flush()
                    break
                try:
                    line = run_state.log_queue.get(timeout=1)
                    msg = f"data: {line}\n\n".encode("utf-8")
                    self.wfile.write(msg)
                    self.wfile.flush()
                except queue.Empty:
                    try:
                        self.wfile.write(b": ping\n\n")
                        self.wfile.flush()
                    except Exception:
                        break
                except Exception:
                    break
            return

        if path.startswith("/api/result/"):
            run_id = unquote(path.split("/api/result/", 1)[1])
            with RUNS_LOCK:
                run_state = RUNS.get(run_id)
            if not run_state:
                self._json({"error": "run not found"}, status=404)
                return
            self._json({"runId": run_id, "done": run_state.done, "payload": run_state.result_payload})
            return

        self.send_error(404)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/run":
            length = int(self.headers.get("Content-Length", "0"))
            body_raw = self.rfile.read(length) if length else b"{}"
            try:
                body = json.loads(body_raw.decode("utf-8") or "{}")
            except Exception:
                self._json({"error": "invalid json body"}, status=400)
                return

            run_id = str(uuid.uuid4())
            run_state = RunState(run_id)
            with RUNS_LOCK:
                RUNS[run_id] = run_state

            thread = threading.Thread(target=compile_and_run, args=(run_state, body), daemon=True)
            thread.start()
            self._json({"ok": True, "runId": run_id})
            return

        if path.startswith("/api/stop/"):
            run_id = unquote(path.split("/api/stop/", 1)[1])
            with RUNS_LOCK:
                run_state = RUNS.get(run_id)
            if not run_state:
                self._json({"error": "run not found"}, status=404)
                return

            proc = run_state.process
            if proc and proc.poll() is None:
                proc.terminate()
                run_state.log("[server] Termination requested by UI.")
            self._json({"ok": True, "runId": run_id})
            return

        self.send_error(404)

    def log_message(self, fmt, *args):
        # Keep server console output clean; logs are shown in UI.
        return


def main():
    server = ThreadingHTTPServer(("127.0.0.1", 8787), Handler)
    print("UI server running at http://127.0.0.1:8787")
    print("Open: http://127.0.0.1:8787/AutomationQualityChecker_UI_Design.html")
    server.serve_forever()


if __name__ == "__main__":
    main()
