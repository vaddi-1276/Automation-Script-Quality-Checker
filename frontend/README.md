# Automation Quality Checker — React UI

This folder is a **Vite + React + TypeScript** front end. The original single-file UI remains at `../AutomationQualityChecker_UI_Design.html`.

## Run (development)

1. Start the Python API (from the repo root):

   ```bash
   python3 ui_live_server.py
   ```

2. Install and run the React app:

   ```bash
   cd frontend
   npm install
   npm run dev
   ```

3. Open the URL Vite prints (usually `http://127.0.0.1:5173`). API calls to `/api/*` are proxied to `http://127.0.0.1:8787`.

## Build (static files)

```bash
npm run build
```

Output is in `frontend/dist/`. You can serve `dist/` with any static server; configure it to proxy `/api` to `ui_live_server.py` or run the API on the same origin.
