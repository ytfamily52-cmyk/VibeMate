# VibeMate

AI chat app for Android (Kotlin), with a normal chat engine (Google Gemini) and
an 18+ private room that talks to an OpenAI-compatible provider directly from
the phone over HTTPS.

## Project structure

| Path | Purpose |
|---|---|
| `app/` | Android app (Kotlin, Gradle KTS) |
| `backend/` | FastAPI (Python) backend — auth, chat, vision, storage |
| `admin/` | Admin console (Android/Kotlin module) |
| `tools/` | Helper / verification scripts |
| `firebase.json`, `firestore.rules`, `database.rules.json` | Firebase config |

## Secrets

Real keys live **only** in local, git-ignored files and are never committed:

- `secrets.properties` (Android) — copy from `secrets.properties.example`
- `backend/.env` — copy from `backend/.env.example`

The 18+ private engine setup is documented in `PRIVATE_ENGINE_SETUP.md`.

## Build

- **Android:** open the project in Android Studio and build the `app` module.
- **Backend:** FastAPI app — see `backend/requirements.txt` and run with uvicorn.
