 package com.example.vibemate

/**
 * Single place for every online setting the app needs.
 *
 * There is NO server of our own anywhere in the loop anymore, and no PC to keep
 * switched on. The phone talks directly, over HTTPS, to:
 *  - Firebase  (auth + Firestore)        — always needed,
 *  - Gemini    (normal chats)            — on-device key below,
 *  - OpenRouter(the 18+ Private room)     — see OPENROUTER_API_KEY below.
 *
 * SECRET keys (OpenRouter + Gemini) are NOT in this file any more. They live in
 * the git-ignored `secrets.properties` and reach the app through BuildConfig
 * (see app/build.gradle.kts). Copy `secrets.properties.example` and fill it in.
 *
 *  - OPENROUTER_API_KEY -> https://openrouter.ai/keys — required by the 18+
 *                       Private room. Set a monthly spend limit on the key
 *                       (OpenRouter → Keys → Limit) so a leaked key is bounded.
 *  - GEMINI_API_KEY / GEMINI_API_KEY_2 -> https://aistudio.google.com/app/apikey
 *  - FIREBASE_API_KEY -> Firebase → Project settings → General → "Web API key".
 *                       NOTE: this key is PUBLIC by design (it ships inside every
 *                       Firebase app). Its safety comes from the Firestore
 *                       Security Rules + API-key restrictions, NOT from secrecy.
 *  - FIREBASE_PROJECT_ID -> Firebase → Project settings → General → "Project ID".
 */
object AppConfig {

    // ==================== OpenRouter — the 18+ Private room =================
    //
    // The Private (18+) room calls OpenRouter DIRECTLY from the phone, exactly
    // the way normal chat calls Gemini directly. That means:
    //   * no backend server to deploy and pay for,
    //   * no PC to keep switched on (the old "engine is offline" error is gone),
    //   * Private mode works on any network (Wi-Fi, mobile data, anywhere).
    //
    // Get the key at https://openrouter.ai/keys. IMPORTANT: open
    // OpenRouter → Keys → your key → set a monthly "Limit", so that even if
    // someone extracts the key out of the APK they cannot run up a big bill.
    // Injected from the git-ignored `secrets.properties` (see app/build.gradle.kts)
    // — NOT hardcoded in the source any more, so the repo never exposes it.
    val OPENROUTER_API_KEY: String = BuildConfig.OPENROUTER_API_KEY

    // OpenAI-compatible endpoint (POST {BASE}/chat/completions).
    //
    // ========= OpenRouter is REPLACEABLE (any OpenAI-compatible API) ========
    // The app speaks the plain OpenAI `/chat/completions` protocol, so OpenRouter
    // is NOT a lock-in. To run the 18+ room on another provider, change exactly
    // THREE things and rebuild —
    //   1. this base URL,
    //   2. OPENROUTER_API_KEY in `secrets.properties` (that provider's key),
    //   3. the model ids below (OPENROUTER_MODEL_* / FALLBACK / VISION_*).
    // OpenRouter-only extras (the provider-privacy block + the attribution
    // headers) are skipped automatically for any other host (see
    // VibeMateApi.isOpenRouter), so no code change is needed.
    //
    // ACTIVE PROVIDER (chosen after the OpenRouter credits ran out) — Venice AI:
    // (https://venice.ai) direct API:
    //   * the SAME uncensored family the app already uses through OpenRouter
    //     (dolphin-mistral-24b-venice-edition == "Venice Uncensored 1.2"),
    //   * OpenAI-compatible + a ZERO-RETENTION "Private" tier (no prompt logging,
    //     no training) — better privacy than a router, and no per-token markup,
    //   * new accounts get 500 free credits + a free daily allowance, no card.
    //     base URL  -> "https://api.venice.ai/api/v1"
    //     chat model-> "aion-labs-aion-3-5-mini"  ($0.88 / $1.75 per 1M)
    //     fallbacks -> "aion-labs-aion-3-0-mini",
    //                  "venice-uncensored-1-2"     (cheap, zero-retention)
    //     vision    -> "venice-uncensored-1-2"     (native vision, uncensored)
    //     key       -> Venice -> API -> https://venice.ai/settings/api
    // Other drop-in options: Together AI, Fireworks, Requesty, AnonRouter,
    // NanoGPT, Chutes, Featherless (flat monthly), DeepInfra, Novita.
    const val OPENROUTER_BASE_URL: String = "https://api.venice.ai/api/v1"

    // ===== Provider privacy: no-logging / no-training / zero-retention ======
    // OpenRouter is only a ROUTER — every request is actually executed on a
    // third-party provider's GPU. By default some of those providers may keep
    // request logs (which their staff can read) and may train on the data. Both
    // are switched OFF here, so the 18+ private room, the photo reader and the
    // image generator only run on providers that collect and retain nothing:
    //
    //   "provider": { "data_collection": "deny", "zdr": true,
    //                 "allow_fallbacks": true }
    //
    // HONEST TRADE-OFF: a strict policy can leave a model with no matching
    // provider, and OpenRouter then answers HTTP 404 "No endpoints found
    // matching your data policy". That must never break the chat, so the call is
    // retried ONCE without the block (see postOpenRouterWithPrivacy) — a working
    // reply beats a silent failure. Set to false to never send the block at all.
    const val PRIVATE_STRICT_PRIVACY: Boolean = true

    // Default model, used when a persona has no override below.
    //
    // ACTIVE PROVIDER: Venice AI direct (see OPENROUTER_BASE_URL above), running
    // "aion-labs-aion-3-5-mini" — the model that won a live head-to-head for
    // Hinglish quality + context memory (see the persona block below).
    //
    // HISTORY (why the earlier picks were dropped, verified live):
    //  * qwen-2.5-72b / llama-3.3-70b REFUSED explicit scenes -> removed,
    //  * mistral-nemo was the cheapest, but its Hinglish came back garbled,
    //  * hermes-3-405b engaged but was the priciest (~3x) -> dropped,
    //  * cydonia-24b-v4.1 was cheap, but was only reachable through OpenRouter,
    //    whose credits ran out,
    //  * venice-uncensored-1-2 (Dolphin-Mistral-24B) engaged but its Hinglish
    //    grammar was broken ("Tumhara chut... karongi") -> now the cheap fallback.
    const val OPENROUTER_MODEL: String = "aion-labs-aion-3-5-mini"

    // NOTE: FRIEND mode runs on the built-in Gemini path in NORMAL mode (like
    // every other normal room — that path has key rotation + model fallback, and
    // Gemini is best at the "long, categorised answer" style a friend uses for
    // real questions). Its 18+ Private room DOES need an adult-capable model, so
    // OPENROUTER_MODEL_FRIEND below is the never-refuses model used there.

    // One model PER PERSONA (the two gay personas share one — same family).
    // Every room rides the SAME model: the persona prompt itself gives each room
    // its own voice/behaviour (and the body/gender lock), so the model does not
    // need to differ per persona.
    //
    // "aion-labs-aion-3-5-mini" — chosen after a LIVE head-to-head (temp 0.7,
    // Venice system prompt ON, 4-turn context, explicit Hinglish prompt):
    //   * aion-3-5-mini  -> "Haan mera pyaara Rahul, sab yaad hai mujhe, tumhari
    //     thakan bhi... Arre main bhi kab se tadap rahi thi tumhare liye, pagal."
    //     = CORRECT Hinglish grammar, remembers name + context, naturally explicit.
    //   * venice-uncensored-1-2 -> "Ha haan yaad hai na, Rahul ji." = flat, and
    //     elsewhere produced broken grammar ("Tumhara chut... karongi").
    //   * venice-uncensored-role-play -> produced pure gibberish
    //     ("tumhare lipid-horny lips se kitabee seetjayenge") -> DROPPED.
    //   * gemma-4-uncensored -> coherent but tame + mixed English -> dropped.
    //   * qwen-3-6-plus / qwen-3-8-27b -> returned EMPTY (reasoning models).
    // 262K context, so long chats stay in memory. NOTE: its privacy tier is
    // "anonymized" (Venice hides your identity, but it is not the zero-retention
    // "private" tier that venice-uncensored-1-2 has) — the quality is worth it.
    const val OPENROUTER_MODEL_GIRLFRIEND: String = "aion-labs-aion-3-5-mini"
    const val OPENROUTER_MODEL_BOYFRIEND: String = "aion-labs-aion-3-5-mini"
    const val OPENROUTER_MODEL_CRUSH: String = "aion-labs-aion-3-5-mini"
    // Gay mode — BOTH gay personas share this one model: boy gay (mate = a MAN,
    // user = a man too) and girl gay (mate = a WOMAN, user = a woman too, i.e. a
    // lesbian couple). The persona prompt (couple behaviour + body lock) keeps
    // the two apart.
    const val OPENROUTER_MODEL_GAY: String = "aion-labs-aion-3-5-mini"
    // Friend mode (18+ Private room): the friend is a male buddy who answers
    // EVERYTHING, so it rides the same model as the romance rooms.
    // Its NORMAL room still runs on Gemini (see the NOTE above).
    const val OPENROUTER_MODEL_FRIEND: String = "aion-labs-aion-3-5-mini"

    // Tried in order when the primary model errors / rate-limits / refuses.
    // aion-3-0-mini is the same family (same voice), and venice-uncensored-1-2 is
    // the cheapest zero-retention uncensored model — so a refusal can never end
    // the conversation.
    const val OPENROUTER_FALLBACK_MODEL: String =
        "aion-labs-aion-3-0-mini,venice-uncensored-1-2"

    // Vision reader: private-room photos are actually SEEN by this model, whose
    // description is handed to the persona model above so it reacts to what is
    // really visible instead of guessing from the caption.
    //
    // DEEP SCAN (verified live, test_deep_readers.py): the reader is now asked
    // to scan the WHOLE image item-by-item (scene / body parts / pose / look /
    // clothing / lighting) the way OCR scans every character — so it stops
    // giving a 2-line summary that "kabhi kabhi" missed what a photo showed.
    //
    // WHY qwen3-vl-235b-a22b IS THE PRIMARY: it is a 235B model built
    // SPECIFICALLY for vision (vs the 24B general models), so it gives the
    // itemised DEEP-SCAN read instead of a vague 2-line summary — and it was
    // verified live earlier to name explicit content plainly ("…human penis and
    // scrotum… glans… shaft…") instead of renaming it into scenery.
    //
    // WHY THE OLD READERS WERE DROPPED (all verified live earlier):
    //   * google/gemini-2.5-pro — Google's model: looked great on a *drawing*
    //     proxy, but Google's real-photo safety filter RENAMES a real explicit
    //     photo into "wall/door frame" (that was the "still not identifying"
    //     bug) — a stylised drawing slips through, a real photo does not,
    //   * mistral-small-3.2 — good but sometimes mislabels ("dildo"),
    //   * llama-4-maverick — vague ("abstract figure"),
    //   * qwen-2.5-vl-72b — self-censored ("no body parts present").
    const val OPENROUTER_VISION_MODEL: String = "qwen3-vl-235b-a22b"

    // Tried IN ORDER when the primary reader refuses / moderates / errors. All
    // three are Venice models WITH native vision (verified in the live model
    // list); venice-uncensored-1-2 is the ZERO-REFUSAL safety net, so an
    // explicit photo can never end up with no read at all.
    const val OPENROUTER_VISION_FALLBACK_MODELS: String =
        "venice-uncensored-1-2,venice-uncensored-role-play"

    // Private (18+) replies are SHORT chat bubbles, so this budget is
    // deliberately small (was 2048): a huge cap let the model ramble a long
    // paragraph. A "chat" turn now asks for 2-3 tiny WhatsApp-style messages
    // (see PRIVATE_BUBBLE_SEPARATOR), so 256 tokens is still plenty — and it
    // also lowers cost + latency.
    const val OPENROUTER_MAX_OUTPUT_TOKENS: Int = 256

    // ...but an INFO turn (the user asked a real question — "Taj Mahal kahan
    // hai", "photosynthesis explain karo") must be answered COMPLETELY. The
    // short chat budget used to cut those answers mid-sentence, which looked
    // like a broken/half reply. Info turns therefore get a much larger budget
    // and are shown as ONE full bubble instead of being chopped up.
    // 1000 tokens is deliberately generous: Hinglish/Hindi answers are long and
    // Devanagari costs many tokens per word, so a smaller cap cut them off.
    const val OPENROUTER_INFO_MAX_OUTPUT_TOKENS: Int = 1000

    // A normal (non-info) Private reply arrives as 2-3 tiny messages separated
    // by this marker, and the app posts each one as its own bubble with a short
    // "typing…" pause between them — exactly how a real partner fires off quick
    // WhatsApp texts instead of one long paragraph.
    const val PRIVATE_BUBBLE_SEPARATOR: String = "|||"
    const val MAX_PRIVATE_BUBBLES: Int = 4
    // TARGET length of one chat bubble (a fast WhatsApp line). This is NOT a cut
    // point any more: a piece longer than this is broken at SENTENCE / COMMA
    // boundaries instead of being chopped mid-sentence, which is what used to
    // leave a dangling "…" at the end of a half reply.
    const val MAX_PRIVATE_BUBBLE_WORDS: Int = 10
    const val MAX_PRIVATE_BUBBLE_CHARS: Int = 90
    // Anti-flood guard for ONE bubble — a runaway paragraph, not a chat line.
    // Far above any real WhatsApp text, so a genuine reply is never touched by it.
    const val MAX_PRIVATE_BUBBLE_HARD_WORDS: Int = 120
    const val MAX_PRIVATE_BUBBLE_HARD_CHARS: Int = 1200
    // Pause between two consecutive bubbles so the reply feels typed, not pasted.
    // Lowered from 650ms: the old gap made a 2-3 bubble Private reply feel laggy.
    const val PRIVATE_BUBBLE_TYPING_DELAY_MS: Long = 350L

    // When a provider stops a reply at the token limit (OpenRouter
    // finish_reason = "length", Gemini finishReason = "MAX_TOKENS") the text is
    // cut mid-sentence — the "adhura reply" bug. Instead of showing that half
    // text, the SAME request is retried once with this much bigger budget.
    const val OPENROUTER_TRUNCATION_RETRY_TOKENS: Int = 1200
    const val GEMINI_TRUNCATION_RETRY_TOKENS: Int = 600

    // Sanity ceiling for an INFO answer — a flood-guard ONLY, never a length
    // target. Measured live (14/09/2026): real answers came back at 99-163 words
    // (Taj Mahal 99, chai banane ka tarika 126, photosynthesis 163), so this sits
    // far above any normal answer and a genuine reply is never cut mid-way; it
    // only stops a runaway model from flooding the chat with an essay.
    const val MAX_PRIVATE_INFO_WORDS: Int = 450
    const val MAX_PRIVATE_INFO_CHARS: Int = 2800

    // --- FRIEND mode (normal, non-romantic) -----------------------------------
    // Friend mode answers a real question like an expert friend: a long,
    // CATEGORISED answer (sections / numbered points / examples) instead of a
    // one-liner. It therefore needs its own, much bigger token budget than a
    // normal chat turn (GEMINI_MAX_OUTPUT_TOKENS = 160 would cut it in half).
    // 900 is measured headroom for a ~250-word Hinglish answer with sections;
    // Devanagari costs many tokens per word, so the budget is deliberately not
    // tiny. The retry budget is the bigger one used if Gemini stops at
    // MAX_TOKENS (finishReason) so a long answer always arrives COMPLETE.
    const val GEMINI_FRIEND_INFO_TOKENS: Int = 900
    const val GEMINI_FRIEND_INFO_RETRY_TOKENS: Int = 1600
    // Sanity ceiling (flood-guard ONLY, never a length target) for a friend's
    // long answer. Same numbers as the private INFO ceiling: far above any real
    // answer, so a genuine reply is never clipped mid-way.
    const val MAX_FRIEND_INFO_WORDS: Int = 450
    const val MAX_FRIEND_INFO_CHARS: Int = 2800

    // 0.7 — lowered from 0.9 after a live A/B: at 0.9 the 24B models drifted into
    // broken grammar, while 0.7 kept the persona but stayed coherent. Measured on
    // a 4-turn Hinglish context probe (see OPENROUTER_MODEL above).
    const val OPENROUTER_TEMPERATURE: Float = 0.7f

    // Optional attribution headers shown on the OpenRouter dashboard.
    const val OPENROUTER_HTTP_REFERER: String = "https://vibemate.example.com"
    const val OPENROUTER_HTTP_TITLE: String = "VibeMate"

    // =================== AI Image generation — "/pic <kaisa photo>" ==========
    // ####################################################################
    // MASTER SWITCH — IMAGE GENERATION IS OFF IN VIBEMATE.
    // While this is false the app NEVER calls OpenRouter's Image API:
    //   * "/pic <kuch bhi>" and natural-language photo requests ("photo bhejo",
    //     "selfie dikhao", "apni pic send karo") are NOT turned into pictures.
    //     The text simply goes through the normal chat, and the persona is told
    //     it cannot send pics right now (see PersonaPrompts.photoRule), so it
    //     answers honestly instead of promising an image that never arrives.
    //   * VibeMateApi.generateImage() refuses outright (second safety net, so a
    //     future call site cannot silently re-enable paid generation).
    // This does NOT affect photos the USER sends: they are still read and
    // answered (see PersonaPrompts.photoTurnRule).
    // Set it back to true to re-enable AI picture sending.
    //
    // PROVIDER NOTE: the image code below is OpenRouter-specific (it posts to
    // OpenRouter's dedicated Image API). The private room now runs on Venice,
    // whose image API is a different endpoint — so VibeMateApi.generateImage()
    // ALSO refuses unless OPENROUTER_BASE_URL points back at OpenRouter. Turn
    // this switch on only together with an OpenRouter base URL.
    // ####################################################################
    const val IMAGE_GEN_ENABLED: Boolean = false

    // ---- Everything below is kept for when IMAGE_GEN_ENABLED is true again ---
    // OpenRouter's dedicated Image API (POST {BASE}/images) reuses the SAME
    // OPENROUTER_API_KEY as the Private room — nothing new to deploy. Every
    // available image model: https://openrouter.ai/models?output_modalities=image
    //
    // COST-FIRST ORDER — prices LIVE from OpenRouter (GET /images/models/{id}/
    // endpoints) and behaviour verified live (13/09/2026, adult prompt -> image
    // -> vision verdict):
    //
    //   TIER 1  black-forest-labs/flux.2-klein-4b   $0.014/megapixel (~$0.014)
    //       <- CHEAPEST model available. Handles every normal/safe prompt fast and
    //          cheap. It REFUSES adult prompts (HTTP 400 "flagged for sexual or
    //          adult content") — which is fine: the refusal is free, so an 18+
    //          request simply falls through to Tier 2 below.
    //   TIER 2  recraft/recraft-v4.1                $0.035   <- ADULT model
    //   TIER 2  recraft/recraft-v4                  $0.04    <- ADULT last resort
    //          VERIFIED live (14/09/2026, image -> 4 blunt vision models ->
    //          consensus verdict):
    //          * MALE requests CAN render real genitals: the wording
    //            "big erect cock and balls clearly visible" is accepted by both
    //            recraft-v4 and recraft-v4.1 and gives GENITALS VISIBLE 4/4.
    //            Format matters: the accepted string has NO leading "A " and NO
    //            comma after the subject ("Photorealistic explicit nude photo of
    //            a handsome adult man standing completely naked, ..."); those two
    //            variants were 400-blocked 0/2. With the final wording the app
    //            prompt itself was accepted 4/4 and genitals confirmed 4/4
    //            (A/B probe of the app's own prompt, 14/09/2026). Recraft's
    //            moderation is also PROBABILISTIC — an identical prompt sometimes
    //            blocks — which is why the fallback chain exists.
    //          * FEMALE requests canNOT render genitals: every explicit female
    //            wording ("vulva"/"labia"/"pussy"/...) is moderation-blocked by
    //            ALL 9 image providers (recraft v4/v4.1/utility, gemini-3.1,
    //            seedream, riverflow, krea, qwen-image, flux), and the
    //            euphemistic wording recraft DOES accept (e.g. "legs spread wide
    //            apart ... nothing hidden") still self-censors the area -> 0/4
    //            models see any vulva. Best achievable = a full tasteful nude.
    //          So normalizeImagePrompt() sends the explicit cock wording for
    //          male requests, and an honest full-nude (breasts/body) for female.
    //
    // RESULT: a normal image costs ~$0.014 instead of $0.04 (-65%), and an adult
    // image costs $0.035 — the cheapest model that will actually render nudity.
    // REFUSED / BLOCKED for adult (tested, do NOT put in the chain): every cheaper
    // model — sourceful/riverflow-v2-fast (HTTP 422), microsoft/mai-image-2.6-flash
    // ("MultiSeverity_SexualScore"), google/gemini-3.1-flash-lite-image ("content
    // moderation"), bytedance-seed/seedream-5-0-lite, x-ai/grok-imagine, qwen-image,
    // krea (provider moderation), and recraft-v3 (returns a CLOTHED image).
    const val IMAGE_GEN_MODEL: String = "black-forest-labs/flux.2-klein-4b"

    // Tried in order when the primary errors / rate-limits / refuses. These are
    // the adult-capable recraft models (and the only ones that accept the strong
    // explicit wording), so an 18+ request always returns a picture.
    const val IMAGE_GEN_FALLBACK_MODEL: String =
        "recraft/recraft-v4.1,recraft/recraft-v4"

    // Adult prompts skip Tier 1 entirely: the cheap model always refuses them, so
    // trying it only adds latency. When normalizeImagePrompt() rewrites a request
    // into adult wording, generation STARTS here instead.
    const val IMAGE_GEN_ADULT_MODEL: String = "recraft/recraft-v4.1"
    const val IMAGE_GEN_ASPECT_RATIO: String = "1:1"
    // Output resolution: "1K" (~1024px) / "2K" (~2048px) / "4K" (~4096px).
    // "1K" = the cheapest tier (riverflow-v2-fast is $0.02 at 1K vs $0.04 at 2K).
    // The chat bubble caps the image at ~280dp anyway, so 1K is plenty on screen.
    const val IMAGE_GEN_RESOLUTION: String = "1K"
    const val IMAGE_GEN_MAX_PROMPT_CHARS: Int = 400

    // Local re-encode after generation. Kept LOW on purpose: the image is only
    // ever shown in a ~280dp chat bubble, and a smaller JPEG costs less Firestore
    // bandwidth + storage. 1024px @ q65 is ~5-10x smaller than 1536px @ q85 and
    // visually identical at bubble size.
    const val IMAGE_GEN_LOCAL_MAX_SIDE: Int = 1024
    const val IMAGE_GEN_LOCAL_JPEG_QUALITY: Int = 65

    /** Fallback image-model ids — trimmed, without blanks or duplicates. */
    fun imageGenFallbacks(): List<String> {
        val out = ArrayList<String>()
        for (part in IMAGE_GEN_FALLBACK_MODEL.split(",")) {
            val m = part.trim()
            if (m.isNotEmpty() && m !in out) out.add(m)
        }
        return out
    }

    /**
     * True once a real private-engine key has been pasted in.
     *
     * The app talks to ANY OpenAI-compatible provider (see OPENROUTER_BASE_URL),
     * so this only rejects an EMPTY or placeholder value. It must NOT hard-code
     * one vendor's key prefix: the old `startsWith("sk-or-")` check silently
     * rejected a perfectly valid Venice key, and the 18+ room then wrongly
     * reported "engine is not configured" while the key was right there in
     * BuildConfig.
     */
    fun openRouterConfigured(): Boolean = isUsablePrivateKey(OPENROUTER_API_KEY)

    /**
     * True when [key] looks like a real, pasted provider key — not empty and not
     * a placeholder copied from `secrets.properties.example`.
     *
     * Pure function on purpose (it does not read BuildConfig) so the rule is
     * unit-testable; see PrivateEngineKeyTest. A bug here is exactly what made
     * the 18+ room claim the engine was "not configured" while a valid key sat
     * in BuildConfig.
     */
    fun isUsablePrivateKey(key: String): Boolean {
        val k = key.trim()
        return k.isNotEmpty() && !k.contains("PASTE", ignoreCase = true)
    }

    /** Fallback model ids — trimmed, without blanks or duplicates. */
    fun openRouterFallbacks(): List<String> {
        val out = ArrayList<String>()
        for (part in OPENROUTER_FALLBACK_MODEL.split(",")) {
            val m = part.trim()
            if (m.isNotEmpty() && m !in out) out.add(m)
        }
        return out
    }

    /**
     * Vision-reader models in TRY order: the primary reader first, then every
     * fallback (trimmed, deduped). An explicit 18+ photo is read only when the
     * WHOLE chain refuses / fails — one provider's moderation must not end the
     * read (see VibeMateApi.privateReadPhoto).
     */
    fun openRouterVisionModels(): List<String> {
        val out = ArrayList<String>()
        for (part in (OPENROUTER_VISION_MODEL + "," + OPENROUTER_VISION_FALLBACK_MODELS).split(",")) {
            val m = part.trim()
            if (m.isNotEmpty() && m !in out) out.add(m)
        }
        return out
    }

    // Firebase Authentication Web API key — VERIFIED WORKING on vibemate-3b75b
    // (email/password, anonymous and Google providers are all enabled).
    const val FIREBASE_API_KEY: String = "AIzaSyCj6r7qwZBnUj5G86nUtORYXz0CiCnNONE"

    // Firebase project id (from google-services.json -> project_info.project_id).
    const val FIREBASE_PROJECT_ID: String = "vibemate-3b75b"

    /**
     * OAuth **web** client id of the same project (Firebase Console -> Project
     * settings -> General -> *Web client*). Google sign-in asks Google for an ID
     * token whose audience is this client: an ID token minted for an *Android*
     * client id is rejected by Firebase's accounts:signInWithIdp, so this is the
     * value that has to be the serverClientId of the Credential Manager request.
     */
    const val GOOGLE_WEB_CLIENT_ID: String =
        "371766671790-v03vrqal7gq8drmi8v8uln69mtgjhakv.apps.googleusercontent.com"

    // Google Gemini — the AI companion talks directly to Gemini from the app
    // (no backend server needed). Key verified working on this project.
    // Injected from the git-ignored `secrets.properties` (see app/build.gradle.kts).
    val GEMINI_API_KEY: String = BuildConfig.GEMINI_API_KEY

    // Second Gemini key (quota backup): create it at
    // https://aistudio.google.com/app/apikey and paste it here. When empty the
    // app runs on the first key only; once filled, both keys are used
    // round-robin with automatic failover. IMPORTANT: a key only helps if its
    // project has quota/credits — the key currently below answers the models API
    // fine but fails every generateContent call with "prepayment credits are
    // depleted", so it is rested (not usable) until that project has credits.
    // Injected from the git-ignored `secrets.properties` (see app/build.gradle.kts).
    val GEMINI_API_KEY_2: String = BuildConfig.GEMINI_API_KEY_2

    // Third key slot (optional spare). The free tier allows only 20 requests per
    // model per DAY per key, so a spare key is what actually keeps a long chat
    // alive. Paste one to add it to the rotation; leave it empty and it is simply
    // ignored. NOTE: a key from a project with no credits answers "/v1beta/models"
    // fine but EVERY generateContent call fails with "prepayment credits are
    // depleted" — such a key is rotated past and rested long (see
    // GEMINI_KEY_EXHAUSTED_REST_MS), it cannot be fixed from the app.
    const val GEMINI_API_KEY_3: String = ""

    /** All valid keys — empty/placeholder entries are ignored automatically. */
    fun geminiKeys(): List<String> {
        return listOf(GEMINI_API_KEY, GEMINI_API_KEY_2, GEMINI_API_KEY_3)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("PASTE") }
    }
    const val GEMINI_MODEL: String = "gemini-3.5-flash"
    // Fallback model: used automatically when the primary is rate-limited. Kept
    // as the "lite" model because it has a separate (smaller) quota — if the
    // primary hits its limit the rotation still tries this one.
    const val GEMINI_MODEL_FALLBACK: String = "gemini-3.5-flash-lite"
    // --- Key rotation rests ---------------------------------------------------
    // A per-MINUTE rate limit clears in seconds, so a short rest is right for it.
    const val GEMINI_KEY_COOLDOWN_MS: Long = 60_000L
    // A FREE-TIER DAILY quota ("GenerateRequestsPerDayPerProjectPerModel-FreeTier",
    // limit: 20) or a key whose prepaid credits are depleted does NOT come back
    // within a minute — it stays dead for hours. The old flat 60 s rest made
    // rotation look broken: the app re-picked the dead key on EVERY message,
    // burned a request on it and only then fell through to the working key.
    // Exhausted keys now rest long enough to be skipped reliably.
    const val GEMINI_KEY_EXHAUSTED_REST_MS: Long = 30 * 60 * 1000L
    const val GEMINI_TEMPERATURE: Float = 0.7f
    // Normal-chat budget. Raising this to 225 made the model ramble: it read the
    // token budget as "how much to write" and produced a wall of text ("reply
    // buhut lamba, kam ka"). Hinglish is token-hungry (Roman Hindi words need 2-3
    // tokens each) so 160 still fits a warm 1-2 line partner text with headroom.
    const val GEMINI_MAX_OUTPUT_TOKENS: Int = 160
    // ...but a turn where the user asked a REAL question (or a question about a
    // photo they just sent) must be answered COMPLETELY, so it gets this much
    // bigger budget instead. Same reasoning as OPENROUTER_INFO_MAX_OUTPUT_TOKENS:
    // a small cap chopped Hinglish answers mid-sentence ("adhura reply").
    const val GEMINI_INFO_MAX_OUTPUT_TOKENS: Int = 500

    // How many recent chat messages are sent to Gemini as context.
    // 8 = enough memory while using half the tokens vs 15 (plus dedup fix).
    const val CHAT_HISTORY_WINDOW: Int = 8

    // Target length for a displayed normal-chat reply. This is NOT a cut point
    // any more: cleanReply() stops at a SENTENCE boundary once the reply is this
    // long, so text is never chopped mid-sentence. 60 words filled the whole chat
    // bubble like an essay ("reply buhut lamba text milta hai, kam ka"), so a
    // normal partner turn now stops after ~2 quick lines.
    const val MAX_REPLY_WORDS: Int = 28
    const val MAX_REPLY_CHARS: Int = 220
    // Anti-flood guard for normal mode — a runaway paragraph only, far above any
    // real partner text, so a genuine reply is never touched by it.
    const val MAX_REPLY_HARD_WORDS: Int = 220
    const val MAX_REPLY_HARD_CHARS: Int = 1600

    // An INFO turn (a real question — including a question asked about a photo the
    // user just sent, e.g. "isme kya likha hai?") must be answered COMPLETELY, so
    // the 28-word chat target above is replaced by this much bigger one and the
    // flood guard gets its own ceiling. These are sanity caps only, never a length
    // target: a normal answer is returned exactly as written.
    const val MAX_REPLY_INFO_WORDS: Int = 200
    const val MAX_REPLY_INFO_HARD_WORDS: Int = 450
    const val MAX_REPLY_INFO_HARD_CHARS: Int = 2800

    // WhatsApp-style attachments. Firestore documents are capped at 1 MiB and
    // base64 inflates data 4/3, so raw payloads stay well under these limits.
    // Images are downscaled + JPEG-compressed first; audio/documents bigger
    // than MAX_FILE_BYTES show a "file too large" message instead of failing.
    const val MAX_IMAGE_DIMEN: Int = 1024
    const val IMAGE_JPEG_QUALITY: Int = 70
    const val MAX_FILE_BYTES: Int = 512 * 1024

    // ===================== LIVE VOICE CALL =====================
    //
    // The call button used to open a screen with a timer and NOTHING else: no
    // microphone permission, no speech recogniser, no text-to-speech — so the
    // AI could neither hear the user nor say anything. The real voice loop now
    // lives in LiveCallEngine.kt:
    //
    //   mic -> SpeechRecognizer (STT) -> api.sendMessage (Gemini/OpenRouter,
    //   the SAME persona path the chat uses) -> TextToSpeech (AI bolti hai)
    //   -> mic again …
    //
    // MASTER SWITCH: set to false to go back to the old "screen only" call.
    const val LIVE_CALL_ENABLED: Boolean = true

    // Language the mic listens in. Hindi first: the users speak Hindi/Hinglish,
    // and the hi-IN recogniser returns Hindi words + English loan words (which
    // Gemini/OpenRouter read perfectly). en-IN is the fallback when a phone has
    // no Hindi pack — a missing language pack must never kill the call.
    const val LIVE_CALL_LANGUAGE: String = "hi-IN"
    const val LIVE_CALL_LANGUAGE_FALLBACK: String = "en-IN"

    // ------------------------------ the AI's VOICE ------------------------------
    //
    // The single biggest reason the call did not sound natural ("sound ajeeb lag
    // raha hai") was here: the engine only ever called setLanguage(hi-IN) and
    // NEVER chose a Voice. Android then speaks with the engine's own default for
    // that language — on almost every phone the low-quality LOCAL voice — and the
    // code tried to make it sound like a young woman by pushing the pitch +8% on
    // top of it. That combination IS the squeaky robot people hear.
    //
    // LiveCallVoice.kt now PICKS a real voice (best quality for the language,
    // gender-matched to the persona, neural "network" voices preferred over the
    // local ones) and only touches the pitch when the picked voice cannot give
    // the persona's gender. These constants are the fine tuning.

    // 1.0 = the phone's natural talking speed. Below 1 drags the line out the way
    // an IVR menu does; the call rule already keeps every reply to ~25 words, so
    // there is no need to slow the voice down to make it fit.
    const val LIVE_CALL_TTS_RATE: Float = 1.0f

    // Pitch per gender. Deliberately 1.0: the gender now comes from the VOICE
    // itself, and any push on top of a real female voice is exactly what made it
    // sound synthetic (see LiveCallVoice.pitchFor for the fallback maths used
    // when a phone has no gender-matching voice at all).
    const val LIVE_CALL_TTS_FEMALE_PITCH: Float = 1.0f
    const val LIVE_CALL_TTS_MALE_PITCH: Float = 1.0f

    // Prefer the neural (cloud / "network") voices over the offline local ones.
    // They are what "natural" sounds like. A voice that fails to speak (no
    // internet, engine hiccup) is blacklisted for the rest of the call and the
    // next best voice is picked, so this can never silence a call.
    const val LIVE_CALL_TTS_PREFER_NEURAL_VOICES: Boolean = true

    // How loud the AI talks (1.0 = full). The voice now plays as SPEECH on the
    // MEDIA stream (see LiveCallEngine.applyVoice): the TextToSpeech default is
    // the *accessibility* stream, which several ROMs (ColorOS/realme included)
    // process and scale differently — one more reason the AI's voice could sound
    // thin, far away or plain wrong next to the call screen.
    const val LIVE_CALL_TTS_VOLUME: Float = 1.0f

    // Language the voice uses for a LATIN-script (Hinglish / English) reply. A
    // Devanagari reply always goes to LIVE_CALL_LANGUAGE above. "en-IN" is the
    // Indian-English voice: real Hinglish is spoken by Indians, so Latin words
    // get an Indian-English pronunciation instead of being spelled out by the
    // Hindi voice. Set this to LIVE_CALL_LANGUAGE for a heavier Hindi accent.
    const val LIVE_CALL_TTS_LATIN_TAG: String = LIVE_CALL_LANGUAGE_FALLBACK

    // A line longer than this is spoken in two pieces: the AI starts talking after
    // the first piece instead of making the user wait for the whole line, and the
    // cut is always at a sentence/comma boundary (never mid-word).
    const val LIVE_CALL_SPEECH_CHUNK_CHARS: Int = 90

    // While the model "thinks" a real partner does not go silent — she hums. After
    // this long without a reply the engine says one tiny hesitation (below), and
    // the reply itself then flushes it away. Short enough to stay out of the way
    // of a fast answer, long enough to cover a slow one.
    const val LIVE_CALL_FILLER_DELAY_MS: Long = 1400L
    // Devanagari on purpose: the hum must come from the SAME Hindi voice that then
    // speaks the reply, and the phone reads Devanagari with the right pronunciation.
    val LIVE_CALL_FILLERS: List<String> = listOf("हम्म\u2026", "अच्छा\u2026", "हम्म, अच्छा\u2026")

    // How long the engine waits after TTS finishes before opening the mic
    // again, so the tail of the AI's own voice is never recorded as "the user
    // speaking" (echo). Also used after an error / empty result. Kept short: a
    // long pause after every sentence is what made the call feel like a
    // walkie-talkie instead of a phone call.
    const val LIVE_CALL_REOPEN_MIC_DELAY_MS: Long = 350L

    // Silence detection: end the user's turn quickly (a phone call is a fast
    // back-and-forth, not a dictation exercise).
    //
    // CRITICAL — these MUST be Long (and MUST be put in the intent as Long):
    // the recognition service reads them with Bundle.getLongExtra(), and a
    // Bundle.getLong() on an Integer throws ClassCastException. That exception
    // killed every mic session the instant it opened (ERROR_CLIENT), which is
    // exactly the "click-click sound aata hai, koi response nahi" bug: the
    // engine re-opened the mic over and over and nothing was ever heard.
    const val LIVE_CALL_SILENCE_MS: Long = 1000L
    const val LIVE_CALL_POSSIBLE_SILENCE_MS: Long = 1300L
    /** Ignore taps/noise shorter than this — never treated as a spoken turn. */
    const val LIVE_CALL_MINIMUM_LENGTH_MS: Long = 500L

    // Languages the mic tries IN ORDER. Hindi/Hinglish first (what the users
    // actually speak); if the phone's recogniser keeps failing on hi-IN (no
    // Hindi pack installed) the engine falls back to en-IN and finally to the
    // phone's own default language, so the call can never be stuck "hearing
    // nothing" just because one language pack is missing.
    val LIVE_CALL_LANGUAGE_CHAIN: List<String> = listOf(
        LIVE_CALL_LANGUAGE,
        LIVE_CALL_LANGUAGE_FALLBACK,
        "",
    )

    // A spoken user turn longer than this is ignored — it is a stuck mic, not a
    // sentence. Also the cap for one spoken AI line: anything longer is cut at a
    // sentence boundary before TTS, so the persona never gives a "phone lecture".
    const val LIVE_CALL_MAX_LISTEN_MS: Int = 30_000
    const val LIVE_CALL_MAX_SPOKEN_CHARS: Int = 420
}