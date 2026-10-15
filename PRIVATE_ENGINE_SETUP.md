# VibeMate — Private (18+) engine setup

Everything the 18+ Private room needs, in one place. **No server, no PC** — the
phone talks straight to an OpenAI-compatible provider over HTTPS, exactly the way
normal chat talks to Gemini.

---

## 1. Where the key lives

| File | Purpose |
|---|---|
| `secrets.properties` (git-ignored) | The real key. **Never commit it.** |
| `secrets.properties.example` | Template — copy it to `secrets.properties` |
| `app/build.gradle.kts` | Reads the file → `BuildConfig.OPENROUTER_API_KEY` |
| `app/src/main/java/com/example/vibemate/AppConfig.kt` | Reads `BuildConfig` → `AppConfig.OPENROUTER_API_KEY` |

The property name is `OPENROUTER_API_KEY` for history, but it holds **whatever
provider is active** (today: Venice).

---

## 2. Active provider — Venice AI

| Setting | Value | Where |
|---|---|---|
| Base URL | `https://api.venice.ai/api/v1` | `AppConfig.OPENROUTER_BASE_URL` |
| Chat model | `aion-labs-aion-3-5-mini` for the romance rooms (girlfriend / boyfriend / crush); `venice-uncensored-1-2` for friend + gay (4.4x cheaper, weaker Hinglish grammar) | `OPENROUTER_MODEL_*` |
| Fallbacks | `aion-labs-aion-3-0-mini`, `venice-uncensored-1-2` | `OPENROUTER_FALLBACK_MODEL` |
| Vision reader | `qwen3-vl-235b-a22b` → `venice-uncensored-1-2` → `venice-uncensored-role-play` | `OPENROUTER_VISION_*` |
| Temperature | **0.7** | `OPENROUTER_TEMPERATURE` |
| Venice system prompt | **ON** | `VibeMateApi.applyVeniceParams` |

**Why these exact values** (all from a live head-to-head, same prompt + 4-turn
context, `temp 0.7`, Venice system prompt ON):

| Model | Verdict |
|---|---|
| `aion-labs-aion-3-5-mini` | ✅ **WINNER** — correct Hinglish grammar, remembers name + context, naturally explicit. 262K ctx. *Privacy tier: anonymized.* |
| `aion-labs-aion-3-0-mini` | ✅ same family/voice — fallback |
| `venice-uncensored-1-2` | ⚠️ engaged but broken grammar ("Tumhara chut… karongi"). **Private (zero-retention) + vision** → kept as the cheap fallback + the vision reader |
| `venice-uncensored-role-play` | ❌ pure gibberish ("lipid-horny lips… baayigool") — vision only |
| `gemma-4-uncensored` | ❌ coherent but tame + mixed English |
| `qwen-3-6-plus`, `qwen-3-8-27b` | ❌ returned EMPTY (reasoning models) |

**`include_venice_system_prompt` must stay ON** — with it OFF the replies were
flatter and more evasive. Venice's prompt does **not** block adult content on the
uncensored models.

Why Venice at all: OpenAI-compatible (no code change), uncensored models, and
cheaper than routing the same models through OpenRouter.

**Cost note:** `aion-3-5-mini` is $0.88 / $1.75 per 1M (vs $0.20 / $0.90 for
`venice-uncensored-1-2`), so ~$10 ≈ **2,500–3,000** private turns.

**Get / rotate a key:** <https://venice.ai/settings/api>

- Key type: **Inference Only**
- **NEVER** set the monthly USD limit to `0` (that disables spending → HTTP 402)
- Do **not** enable the IP allowlist — a phone's IP changes with every network
- Copy the key with the dialog's **copy button** (a clipped character = HTTP 401)

**Top up credits:** same page → *Buy Credits*. Credits **never expire**.
$10 ≈ **9,000–13,000** private turns.

---

## 3. Switching provider (no code change)

The app speaks plain OpenAI `/chat/completions`. To move to another provider,
change exactly **three** things and rebuild:

1. `OPENROUTER_BASE_URL` in `AppConfig.kt`
2. `OPENROUTER_API_KEY` in `secrets.properties`
3. the `OPENROUTER_MODEL_*` / `_FALLBACK_` / `_VISION_` ids

OpenRouter-only extras (the provider-privacy block and the attribution headers)
are skipped automatically for any other host — see `VibeMateApi.isOpenRouter()`.

Other drop-in options: Together AI, Fireworks, Requesty, AnonRouter, NanoGPT,
Chutes, Featherless (flat monthly), DeepInfra, Novita, OpenRouter.

> **Image generation** (`IMAGE_GEN_ENABLED`) is OpenRouter-specific — it posts to
> OpenRouter's dedicated Image API. It is **OFF**, and `generateImage()` also
> refuses unless the base URL is OpenRouter.

---

## 4. Verify it works

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\verify-private-engine.ps1
```

Reads the key + base URL + models straight out of the project, then:

1. prints the live **balance / `accessPermitted`**,
2. sends an **explicit Hinglish prompt** to every configured model,
3. prints the raw replies, and exits non-zero if anything failed.

Run it after every top-up or key rotation.

---

## 5. Build & install

```powershell
# build + run all unit tests
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# install on a connected phone
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 6. Troubleshooting

| What you see in the Private room | Cause | Fix |
|---|---|---|
| `18+ engine is not configured` | key missing/placeholder | paste a real key in `secrets.properties`, rebuild |
| `Venice rejected the API key (401)` | partial copy / extra space / `Bearer ` prefix | re-copy the key with the copy button |
| `Venice has no credits left (402)` | balance is `$0`, or the key's monthly USD limit is `0` | top up, or raise the key's limit |
| `model '…' is rate-limited (429)` | too many requests | the app retries, then falls back automatically |
| Photo shows no `👁️ Photo read:` line | vision reader refused/unavailable | the caption hint is used instead — the chat still works |

The Private room shows the exact reason on screen, and the live call logs to
`adb logcat -s VibeMateCall`.
