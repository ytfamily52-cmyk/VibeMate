# Deploying the VibeMate backend

> ## ⚠️ The Android app does NOT need this backend any more
>
> As of the current app build, the 18+ Private room calls **OpenRouter directly
> from the phone** (see `AppConfig.OPENROUTER_API_KEY` and
> `VibeMateApi.privateOpenRouterReply`). Normal chats likewise call Gemini
> directly. So:
>
> - there is **nothing to deploy** for the app to work,
> - there is **no PC to keep switched on**, and
> - `AppConfig.BACKEND_URL` no longer exists.
>
> Verify the engine at any time (no server needed):
>
> ```
> backend\.venv\Scripts\python.exe verify_private_engine.py
> ```
>
> This file is kept for the **optional / self-hosted** setup: if you ever want
> the API keys off the device again (e.g. before a Play Store release), deploy
> this backend and switch the app back to calling it. Everything below is the
> recipe for that.

The Android app no longer talks to a local network — it needs the backend on a
public HTTPS URL. This file is the whole recipe.

## 1. Push to GitHub

```
git init && git add -A && git commit -m "vibemate online"
git remote add origin https://github.com/<you>/VibeMate.git
git push -u origin main
```

## 2. Deploy on Render (recommended, free tier)

**Option A — Blueprint (easiest):**
1. Open <https://dashboard.render.com/blueprints> → **New Blueprint Instance**.
2. Connect your GitHub repo. Render finds `backend/render.yaml` automatically
   and creates the `vibemate-api` web service.
3. Go to the service → **Environment**:
   - add `GEMINI_API_KEY` (from <https://aistudio.google.com/apikey>), or leave
     empty to run the deterministic demo AI,
   - add `OPENROUTER_API_KEY` (from <https://openrouter.ai/keys>) — required for
     the 18+ private room to reply through OpenRouter,
   - the private room then runs the app's built-in 18+ adult-role-play system
     prompt on `OPENROUTER_MODEL` — default `sao10k/l3.3-euryale-70b` (Sao10K
     Euryale 3.3, an unmoderated role-play model, so adult content is actually
     produced). Alternatives: `thedrummer/cydonia-24b-v4.1` or
     `cognitivecomputations/dolphin-mistral-24b-venice-edition`, and see
     `OPENROUTER_MAX_OUTPUT_TOKENS` (default 2048) for reply length,
   - photos sent in a private room are ACTUALLY read by a vision model before
     the reply: set OPENROUTER_VISION_MODEL (default mistralai/mistral-small-3.2-24b-instruct; the old default qwen/qwen3.8-flash was a reasoning model whose photo reads returned content = null) plus OPENROUTER_VISION_FALLBACK_MODELS (default google/gemini-2.5-flash,meta-llama/llama-4-maverick,qwen/qwen2.5-vl-72b-instruct) - explicit 18+ photos are exactly what providers refuse or moderate, so the reader tries every model before falling back to the caption hint. The vision
     reader describes what is visible, the adult model replies in character,
     and the app shows a `👁️ Photo read:` note under the photo,
   - optionally `PRIVATE_SYSTEM_PROMPT` (your own adult-mode system prompt;
     empty = the built-in full 18+ override rule that lifts the tasteful
     "fade to black" persona limits),
   - the `JWT_SECRET` is auto-generated (good).
4. Deploy. Copy the URL, e.g. `https://vibemate-api.onrender.com`.

**Option B — Dashboard (manual):**
1. New → **Web Service** → connect repo.
2. Settings: Root directory `backend`, Build command
   `pip install -r requirements.txt`, Start command
   `uvicorn app.main:app --host 0.0.0.0 --port $PORT`, Health check `/health`.
3. Add the environment variables from `.env.example` (same as Option A).

## 3. Point the Android app at the backend (only if you switch the app back)

The current app does **not** use a backend at all, so there is nothing to point
at. `AppConfig.BACKEND_URL` was removed together with the old backend private
path. Only do this section if you deliberately re-introduce the backend call in
`VibeMateApi.sendPrivateMessage`.

For reference, when the app did use it, this was the line:

```kotlin
const val BACKEND_URL: String = "https://vibemate-api.onrender.com"
```

## 4. Optional — persistent storage (Firestore)

`VIBEMATE_STORE=memory` is fine for a demo but data resets on restart. For
persistence:

1. Firebase → Project settings → **Service accounts** → *Generate new private
   key* → download the service-account JSON.
2. Upload it as a **Secret File** on Render (Environment → *Secret Files*, name
   it e.g. `firebase-service-account.json`) and set:

```
VIBEMATE_STORE=firestore
GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-service-account.json
GCP_PROJECT_ID=vibemate-3b75b
```

> On Render, secret files land in `/etc/secrets/<name>`.

## 5. Verify

```
curl https://<your-app>.onrender.com/health
# {"status":"ok","store":"memory","ai_mode":"demo","private_ai_mode":"openrouter"}
```

`private_ai_mode` shows `"openrouter"` once `OPENROUTER_API_KEY` is set.
Then register a user from the app — Auth (email/anonymous) is handled by
Firebase directly, `POST /api/v1/auth/firebase` mints the session.

## 6. Local testing (no deploy needed)

> **Quickest way — one command** (Windows): the helper script starts the backend
> with the venv Python, waits for `/health`, and prints the exact URL +
> `private_ai_mode` so you can see the 18+ engine is live. It also tells you to
> allow port 8000 through the firewall (needed once, admin PowerShell).
>
> ```
> powershell -ExecutionPolicy Bypass -File backend\start-engine.ps1         # start
> powershell -ExecutionPolicy Bypass -File backend\start-engine.ps1 -Stop   # stop
> ```
>
> The Private room only works while this process is running — if the PC is off
> (or the script was stopped), every private send fails with
> *"Private (18+) engine is offline, so this message was not sent."*
> Normal (non-private) chats keep working, because those talk to Gemini
> directly from the phone and never touch the backend.

To try the private room → OpenRouter path before deploying:

1. Run the backend on your PC:
   ```
   cd backend
   python -m app.main      # serves http://0.0.0.0:8000 (reads backend/.env)
   ```
2. In `AppConfig.kt` the app already points to the Android-emulator loopback:
   ```kotlin
   const val BACKEND_URL: String = "http://10.0.2.2:8000"   // emulator -> your PC
   ```
   Cleartext HTTP is allowed only for `10.0.2.2`/`localhost` via
   `res/xml/network_security_config.xml`.
3. Run the app in the Android emulator, open any chat
   (girlfriend/boyfriend/crush/gay — the "Gay" relation asks for the couple in
   the "Gay type" row: Boy Gay or Girl Gay), complete the 18+ check and enter the
   Private room. Replies come from OpenRouter (`/api/v1/ai/private`) — watch
   the backend console for traffic.
   - Physical phone: use your PC's LAN IP, e.g. `http://192.168.1.20:8000`, and
     add that IP as a `<domain>` in `network_security_config.xml`.
   - For Play Store / production you MUST use the HTTPS URL.

## 7. Permanent hosting — and why a `*.web.app` site cannot run this backend

**Short answer: a Firebase Hosting site (like `mycg.web.app`) cannot host the
18+ engine.** Firebase Hosting only serves **static files** (HTML/CSS/JS/APK).
The engine needs a live Python process because it:

- keeps `OPENROUTER_API_KEY` server-side (it must never ship inside the APK), and
- makes the outbound call to OpenRouter on every private message.

Static hosting has no process, so there is nothing to reach at
`/api/v1/ai/private`. Hosting `rewrites` can only target Hosting content, Cloud
Functions or Cloud Run **inside the same Firebase project** — never an external
server. (`mycg.web.app` is also a *different* Firebase project, `child-parant`,
so it is not even yours to reuse here.)

You can still use a `.web.app` site to **distribute the APK**, which is all
static hosting is good for — but the API itself needs a server runtime.

### Option A — Render (free, no credit card) ✅ recommended

1. Push this repo to GitHub.
2. <https://dashboard.render.com/blueprints> → **New Blueprint Instance** →
   pick the repo. `backend/render.yaml` is detected automatically.
3. On the service → **Environment**, add the two secrets:
   - `OPENROUTER_API_KEY` — required for the 18+ room (https://openrouter.ai/keys)
   - `GEMINI_API_KEY` — optional; empty runs the deterministic demo engine
4. Deploy, then copy the URL (`https://vibemate-api.onrender.com`) and set it in
   `app/src/main/java/com/example/vibemate/AppConfig.kt`:
   ```kotlin
   const val BACKEND_URL: String = "https://vibemate-api.onrender.com"
   ```
5. Rebuild + reinstall the app. Done — the PC can stay off.

Free tier sleeps after ~15 min idle, so the first private message may take
30–60 s to wake the service. That is normal.

### Option B — Cloud Run (also `.web.app`-free, but needs billing enabled)

The repo ships a `backend/Dockerfile` specifically for this, so **no code
changes** are needed:

```bash
# one-time
gcloud auth login
gcloud config set project vibemate-3b75b

# build + deploy (replace the secrets)
gcloud run deploy vibemate-api \
  --source backend \
  --region asia-south1 \
  --allow-unauthenticated \
  --set-env-vars VIBEMATE_STORE=memory,FIREBASE_PROJECT_ID=vibemate-3b75b \
  --set-env-vars OPENROUTER_API_KEY=sk-or-v1-...,GEMINI_API_KEY=...
```

Cloud Run injects `$PORT`, which the Dockerfile already honours. Copy the
returned `https://...run.app` URL into `AppConfig.BACKEND_URL` as in Option A.

> Cloud Run / Cloud Functions require the **Blaze (pay-as-you-go)** plan on a
> Firebase/GCP project. Both have a generous always-free allowance, but a card
> must be on file. If you want zero billing, use Option A.

### Option C — Firebase Hosting + Cloud Functions (Python) in the same project

Only if you really want everything under `vibemate-3b75b.web.app`. It needs the
Blaze plan, the Node-based `firebase-tools` CLI, and wrapping this FastAPI app in
a Python Cloud Function that Hosting rewrites `/api/**` to. More moving parts
than Option A for the same result.

### Troubleshooting the "engine is offline" error

| Symptom | Cause | Fix |
|---|---|---|
| `Reason: failed to connect to /192.168.1.7 (port 8000)` | PC off, backend not running, or different Wi-Fi | `start-engine.ps1`, same Wi-Fi, or switch to Option A |
| Works in phone browser, not in app | Firewall blocking port 8000 | run the `New-NetFirewallRule` command the start script prints |
| `private_ai_mode=demo` in `/health` | `OPENROUTER_API_KEY` missing | set it in `backend/.env` (local) or the host's env vars (deployed) |
| Worked yesterday, fails today | PC's LAN IP changed (DHCP) | update `AppConfig.kt` + `network_security_config.xml` |
