#!/usr/bin/env node
/**
 * VibeMate admin helper — talks to the Google APIs the Admin SDK uses, with the
 * project's service-account key. Works even when `firebase deploy` is blocked by
 * the CLI's serviceusage permission check.
 *
 *   node admin/tools/firebase_admin.js whoami                # SA + project
 *   node admin/tools/firebase_admin.js users                 # list Auth accounts
 *   node admin/tools/firebase_admin.js grant <email|uid>     # make an admin
 *   node admin/tools/firebase_admin.js revoke <email|uid>    # revoke admin
 *   node admin/tools/firebase_admin.js admins                # who is an admin now
 *   node admin/tools/firebase_admin.js stats                 # Firestore doc counts
 *   node admin/tools/firebase_admin.js deploy-rules          # publish firestore.rules
 *   node admin/tools/firebase_admin.js live-rules            # what is live now
 *   node admin/tools/firebase_admin.js rules-diff            # live vs local rules
 *   node admin/tools/firebase_admin.js check <email|uid>     # prove admin access
 *   node admin/tools/firebase_admin.js delete-user <email|uid>  # remove Auth login
 *   node admin/tools/firebase_admin.js android-apps          # app + SHA-1 + OAuth client
 *   node admin/tools/firebase_admin.js add-debug-sha         # debug SHA-1 register
 *   node admin/tools/firebase_admin.js google-signin         # Google login checklist
 *
 * Credential lookup order: SA_PATH env → GOOGLE_APPLICATION_CREDENTIALS → the
 * first `sa.json` / `*-adminsdk-*.json` in the project root.
 */
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..', '..');

function findKeyFile() {
    for (const candidate of [process.env.SA_PATH, process.env.GOOGLE_APPLICATION_CREDENTIALS]) {
        if (candidate && fs.existsSync(candidate)) return candidate;
    }
    const found = fs.readdirSync(ROOT)
        .filter((n) => /^sa\.json$|adminsdk.*\.json$|^service-account.*\.json$/i.test(n))
        .map((n) => path.join(ROOT, n));
    if (found.length === 0) {
        throw new Error(
            'Service-account JSON nahi mila. Console → Project settings → Service accounts → ' +
            'Generate new private key, file ko project root me sa.json naam se rakho ' +
            '(ya SA_PATH env var set karo).'
        );
    }
    return found[0];
}

const keyFile = findKeyFile();
const key = JSON.parse(fs.readFileSync(keyFile, 'utf8'));
if (key.type !== 'service_account' || !key.private_key) {
    throw new Error(`${keyFile} service-account key nahi hai.`);
}
const PROJECT = key.project_id;
const SCOPE = [
    'https://www.googleapis.com/auth/datastore',
    'https://www.googleapis.com/auth/identitytoolkit',
    'https://www.googleapis.com/auth/cloud-platform',
].join(' ');

const b64url = (value) => Buffer.from(value).toString('base64url');

/** Signs a JWT with the service-account key and exchanges it for an access token. */
async function accessToken() {
    const now = Math.floor(Date.now() / 1000);
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const claims = b64url(JSON.stringify({
        iss: key.client_email, scope: SCOPE, aud: key.token_uri, iat: now, exp: now + 3600,
    }));
    const signature = crypto
        .sign('RSA-SHA256', Buffer.from(`${header}.${claims}`), key.private_key)
        .toString('base64url');
    const res = await fetch(key.token_uri, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            assertion: `${header}.${claims}.${signature}`,
        }),
    });
    const body = await res.json();
    if (!body.access_token) throw new Error(`Token exchange fail: ${JSON.stringify(body)}`);
    return body.access_token;
}

let TOKEN = '';
async function api(url, { method = 'GET', body } = {}) {
    const res = await fetch(url, {
        method,
        headers: {
            Authorization: `Bearer ${TOKEN}`,
            ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    let parsed = null;
    try {
        parsed = text ? JSON.parse(text) : null;
    } catch (e) {
        parsed = { raw: text };
    }
    return { status: res.status, ok: res.ok, body: parsed };
}

const FS = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents`;

// ---------------------------------------------------------------- auth ------

/** Every Firebase Auth account of the project (e-mail, uid, disabled). */
async function listUsers() {
    const legacy = await api(
        'https://www.googleapis.com/identitytoolkit/v3/relyingparty/downloadAccount',
        { method: 'POST', body: { maxResults: 1000 } }
    );
    if (legacy.ok && Array.isArray(legacy.body.users)) return legacy.body.users;
    const modern = await api(
        `https://identitytoolkit.googleapis.com/v1/projects/${PROJECT}/accounts:batchGet?maxResults=1000`
    );
    if (modern.ok) return modern.body.users || [];
    throw new Error(
        `Auth users nahi padh sake (HTTP ${legacy.status}/${modern.status}): ` +
        `${JSON.stringify(modern.body).slice(0, 300)}`
    );
}

async function resolveUid(who) {
    if (!who.includes('@')) return who;
    const users = await listUsers();
    const wanted = who.trim().toLowerCase();
    const hit = users.find((u) => (u.email || '').toLowerCase() === wanted);
    if (!hit) {
        const known = users.map((u) => `${u.email} (uid ${u.localId})`).join('\n  ');
        throw new Error(`'${who}' naam ka koi Auth account nahi hai. Maujood hain:\n  ${known}`);
    }
    return hit.localId;
}

// ------------------------------------------------------------ firestore -----

const adminDocUrl = (uid) => `${FS}/admins/${uid}`;

/** Creates/updates admins/{uid} — this is what isAdmin() checks. */
async function grant(who) {
    const uid = await resolveUid(who);
    const res = await api(adminDocUrl(uid), {
        // Firestore REST me document create/update ke liye PATCH hota hai
        // (PUT route hi nahi hai — Google HTML 404 deta hai).
        method: 'PATCH',
        body: {
            fields: {
                admin: { booleanValue: true },
                email: { stringValue: who.includes('@') ? who.trim() : '' },
                granted_at: { timestampValue: new Date().toISOString() },
            },
        },
    });
    if (!res.ok) throw new Error(`admins/${uid} banane me fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
    return uid;
}

async function revoke(who) {
    const uid = await resolveUid(who);
    const res = await api(adminDocUrl(uid), { method: 'DELETE' });
    if (!res.ok) throw new Error(`admins/${uid} delete fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
    return uid;
}

async function listAdmins() {
    const res = await api(`${FS}/admins?pageSize=100`);
    if (!res.ok) throw new Error(`admins list fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
    return (res.body.documents || []).map((doc) => ({
        uid: doc.name.split('/').pop(),
        email: doc.fields?.email?.stringValue || '',
    }));
}

/** How many documents a collection holds (first page + whether more exist). */
async function countDocs(collection, pageSize = 300) {
    const res = await api(`${FS}/${collection}?pageSize=${pageSize}`);
    if (!res.ok) {
        throw new Error(
            `${collection} read fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 200)}`
        );
    }
    return {
        docs: (res.body.documents || []).length,
        more: Boolean(res.body.nextPageToken),
    };
}

// ------------------------------------------------------- auth admin ops ------

/** Web API key of the project (same value the app ships in AdminConfig). */
const WEB_API_KEY = process.env.VIBEMATE_WEB_API_KEY ||
    'AIzaSyCj6r7qwZBnUj5G86nUtORYXz0CiCnNONE';

/**
 * Mints a Firebase custom token for [uid] (what the Admin SDK's
 * createCustomToken does: an RS256 JWT signed with the service account).
 */
async function customToken(uid) {
    const now = Math.floor(Date.now() / 1000);
    const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const claims = b64url(JSON.stringify({
        iss: key.client_email,
        sub: key.client_email,
        aud: 'https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit',
        iat: now,
        exp: now + 3600,
        uid,
    }));
    const signature = crypto
        .sign('RSA-SHA256', Buffer.from(`${header}.${claims}`), key.private_key)
        .toString('base64url');
    return `${header}.${claims}.${signature}`;
}

/** Custom token → real Firebase ID token (the same token the admin app gets on login). */
async function idTokenFor(uid) {
    const url = 'https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken' +
        `?key=${WEB_API_KEY}`;
    const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token: await customToken(uid), returnSecureToken: true }),
    });
    const body = await res.json();
    if (!body.idToken) {
        throw new Error(
            `Custom-token exchange fail (HTTP ${res.status}): ${JSON.stringify(body).slice(0, 300)}`
        );
    }
    return body.idToken;
}

/** One Firestore call AS that user (rules apply, exactly like the app). */
async function asUser(idToken, url) {
    const res = await fetch(url, { headers: { Authorization: `Bearer ${idToken}` } });
    await res.text();
    return res.status;
}

/**
 * Proves the grant really works: signs in as that user (via a custom token) and
 * checks the three reads the admin panel performs. 200 = admin, 403 = not yet.
 */
async function verifyAdminAccess(who) {
    const uid = await resolveUid(who);
    const token = await idTokenFor(uid);
    const results = [];
    for (const [what, url] of [
        ['users list', `${FS}/users?pageSize=5`],
        ['chat_sessions list', `${FS}/chat_sessions?pageSize=5`],
        ['call_records list', `${FS}/call_records?pageSize=5`],
    ]) {
        results.push([what, await asUser(token, url)]);
    }
    return { uid, results };
}

/** Deletes a Firebase Authentication login (the app cannot do this over REST). */
async function deleteAuthUser(who) {
    const uid = await resolveUid(who);
    const res = await api(
        `https://identitytoolkit.googleapis.com/v1/projects/${PROJECT}/accounts:delete`,
        { method: 'POST', body: { localId: uid } }
    );
    if (!res.ok) {
        throw new Error(
            `Auth account delete fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`
        );
    }
    return uid;
}

// -------------------------------------------------------- rules deploy ------

/** Publishes firestore.rules through the Firebase Rules API and verifies the release. */
async function deployRules(file = path.join(ROOT, 'firestore.rules')) {
    const content = fs.readFileSync(file, 'utf8');
    const base = `https://firebaserules.googleapis.com/v1/projects/${PROJECT}`;

    const created = await api(`${base}/rulesets`, {
        method: 'POST',
        body: { source: { files: [{ name: 'firestore.rules', content }] } },
    });
    if (!created.ok) {
        throw new Error(
            `Ruleset create fail (HTTP ${created.status}): ${JSON.stringify(created.body).slice(0, 400)}`
        );
    }
    const rulesetName = created.body.name;
    console.log(`naya ruleset: ${rulesetName}`);

    // firebaserules v1 ka release-update: kaunsa verb/body shape transcoder
    // maangta hai (wrapper vs flat) API revision ke saath badla hai, isliye
    // dono shapes try karke pehla chalta hua rakha jata hai. Neeche release ko
    // wapas padh kar verify bhi kiya jata hai — galat shape chupke se pass nahi
    // ho sakta.
    const releaseName = `projects/${PROJECT}/releases/cloud.firestore`;
    const shapes = [
        ['PATCH', { release: { name: releaseName, rulesetName } }, 'wrapped'],
        ['PATCH', { name: releaseName, rulesetName }, 'flat'],
        ['PUT', { release: { name: releaseName, rulesetName } }, 'wrapped'],
        ['PUT', { name: releaseName, rulesetName }, 'flat'],
    ];
    const attempts = [];
    let updated = null;
    for (const [method, body, label] of shapes) {
        updated = await api(`${base}/releases/cloud.firestore`, { method, body });
        attempts.push(
            `${method} ${label} -> HTTP ${updated.status}: ${JSON.stringify(updated.body).slice(0, 220)}`
        );
        if (updated.ok) {
            console.log(`release update: ${method} ${label} shape chala`);
            break;
        }
    }
    if (!updated.ok) {
        throw new Error('Release update fail — koi shape chala nahi:\n  ' + attempts.join('\n  '));
    }

    // Read the release (and the ruleset) back: only that proves the LIVE rules moved.
    const live = await api(`${base}/releases/cloud.firestore`);
    if (!live.ok || live.body.rulesetName !== rulesetName) {
        throw new Error(
            `Release ne naya ruleset nahi liya (HTTP ${live.status}): ` +
            `${JSON.stringify(live.body).slice(0, 300)}`
        );
    }
    const fetched = await api(`https://firebaserules.googleapis.com/v1/${rulesetName}`);
    if (!fetched.ok || !JSON.stringify(fetched.body).includes('admins/')) {
        throw new Error('Publish hue ruleset me admins/{uid} route nahi mila — firestore.rules check karo.');
    }

    console.log(`release     : ${releaseName}`);
    console.log(`updateTime  : ${live.body.updateTime || '(unknown)'}`);
    console.log(`check       : isAdmin() me admins/{uid} route maujood hai`);
    return rulesetName;
}

/**
 * Prints the rules that are LIVE right now (the released ruleset), so it is
 * obvious whether the admin override is already deployed.
 */
async function liveRules() {
    const base = `https://firebaserules.googleapis.com/v1/projects/${PROJECT}`;
    const release = await api(`${base}/releases/cloud.firestore`);
    if (!release.ok) {
        throw new Error(
            `Live release read fail (HTTP ${release.status}): ` +
            `${JSON.stringify(release.body).slice(0, 300)}`
        );
    }
    const rulesetName = release.body.rulesetName;
    const ruleset = await api(`https://firebaserules.googleapis.com/v1/${rulesetName}`);
    const content = (ruleset.body.source?.files || []).map((f) => f.content).join('\n');
    console.log(`release    : ${release.body.name}`);
    console.log(`ruleset    : ${rulesetName}`);
    console.log(`updated    : ${release.body.updateTime || '(unknown)'}`);
    console.log(`bytes      : ${content.length}`);
    console.log(`admins doc : ${content.includes('admins/') ? 'YES (admin override live)' : 'NO (purane rules live hain)'}`);
    console.log('--- live firestore.rules -------------------------------------------');
    console.log(content.trim());
}

/**
 * Compares the LIVE released ruleset with a local file, line by line, so it is
 * obvious whether the deployed rules really are the ones in the repo.
 */
async function rulesDiff(file = path.join(ROOT, 'firestore.rules')) {
    const local = fs.readFileSync(file, 'utf8');
    const base = `https://firebaserules.googleapis.com/v1/projects/${PROJECT}`;
    const release = await api(`${base}/releases/cloud.firestore`);
    if (!release.ok) {
        throw new Error(`Live release read fail (HTTP ${release.status}): ${JSON.stringify(release.body).slice(0, 200)}`);
    }
    const ruleset = await api(`https://firebaserules.googleapis.com/v1/${release.body.rulesetName}`);
    const live = (ruleset.body.source?.files || []).map((f) => f.content).join('\n');

    const norm = (text) => text
        .replace(/\r\n/g, '\n')
        .split('\n')
        .map((line) => line.replace(/\s+$/, ''))
        .join('\n')
        .trim();
    const a = norm(live).split('\n');
    const b = norm(local).split('\n');
    const same = norm(live) === norm(local);

    console.log(`release : ${release.body.name}`);
    console.log(`ruleset : ${release.body.rulesetName}`);
    console.log(`local   : ${path.relative(ROOT, file) || file} (${local.length} bytes, ${b.length} lines)`);
    console.log(`live    : ${live.length} bytes, ${a.length} lines`);
    if (same) {
        console.log('OK  live rules aur local firestore.rules ek jaise hain (line-by-line match).');
    } else {
        console.log('MISMATCH  live rules alag hain — deploy-rules chalao.');
        for (let i = 0; i < Math.max(a.length, b.length); i++) {
            if (a[i] !== b[i]) {
                console.log(`  pehla farq line ${i + 1}:`);
                console.log(`    live : ${a[i] === undefined ? '(khaali)' : a[i]}`);
                console.log(`    local: ${b[i] === undefined ? '(khaali)' : b[i]}`);
                break;
            }
        }
    }
    process.exitCode = same ? 0 : 1;
}

// ------------------------------------- Android app config (Google login) -----

/**
 * Google sign-in only works when the phone can find an OAuth client that matches
 * the app's package name + signing certificate. That client is created the moment
 * a SHA-1 fingerprint is registered for the Firebase Android app — which is what
 * these helpers do, without opening the console.
 */

const FIREBASE_MGMT = 'https://firebase.googleapis.com/v1beta1';
const DEFAULT_PACKAGE = 'com.example.vibemate';

/** Every Android app registered in the Firebase project. */
async function androidApps() {
    const res = await api(`${FIREBASE_MGMT}/projects/${PROJECT}/androidApps`);
    if (!res.ok) {
        throw new Error(`Android apps list fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
    }
    return res.body.apps || [];
}

/** Firebase app id of one package (`com.example.vibemate` by default). */
async function requireAndroidApp(packageName = DEFAULT_PACKAGE) {
    const apps = await androidApps();
    const hit = apps.find((a) => a.packageName === packageName);
    if (!hit) {
        const known = apps.map((a) => a.packageName).join(', ') || '(koi nahi)';
        throw new Error(`'${packageName}' is project me nahi hai. Maujood: ${known}`);
    }
    return hit.appId;
}

/** SHA-1 / SHA-256 fingerprints registered for one Android app. */
async function fingerprints(appId) {
    const res = await api(`${FIREBASE_MGMT}/projects/${PROJECT}/androidApps/${appId}/sha`);
    if (!res.ok) {
        throw new Error(`SHA list fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
    }
    return res.body.certificates || [];
}

/** The app's google-services.json (decoded), or null when it cannot be read. */
async function androidAppConfig(appId) {
    const res = await api(`${FIREBASE_MGMT}/projects/${PROJECT}/androidApps/${appId}/config`);
    if (!res.ok || !res.body.configFileContents) return null;
    try {
        return JSON.parse(Buffer.from(res.body.configFileContents, 'base64').toString('utf8'));
    } catch (e) {
        return null;
    }
}

/**
 * "77846e45..." or "77:84:6E:..." -> `{ shaHash, certType }` in the shape
 * projects.androidApps.sha.create wants. The type field is `certType` (not
 * "certificateType") — sending the wrong name gets a bare 400 INVALID_ARGUMENT.
 */
function normaliseSha(value) {
    const hex = String(value).replace(/[^0-9a-fA-F]/g, '').toUpperCase();
    if (hex.length !== 40 && hex.length !== 64) {
        throw new Error(`SHA-1 (40 hex chars) ya SHA-256 (64 hex chars) do — ye ${hex.length} hex chars hai.`);
    }
    return { shaHash: hex.match(/../g).join(':'), certType: hex.length === 40 ? 'SHA_1' : 'SHA_256' };
}

/** Prints one Android app the way the Google login flow sees it. */
async function showAndroidApp(app) {
    console.log(`${app.packageName}  (${app.appId})  state=${app.state || 'ACTIVE'}`);
    const list = await fingerprints(app.appId);
    if (list.length === 0) {
        console.log('  fingerprints: (koi nahi)   <-- isi liye Google login fail hota hai');
    }
    for (const fp of list) {
        console.log(`  ${(fp.certType || fp.certificateType || 'SHA_1').padEnd(8)} ${fp.shaHash}`);
    }
    const config = await androidAppConfig(app.appId);
    const clients = (config && config.client ? config.client : [])
        .flatMap((c) => c.oauth_client || []);
    for (const client of clients) {
        const type = client.client_type === 1 ? 'android' : client.client_type === 3 ? 'web' : `type ${client.client_type}`;
        console.log(`  oauth client (${type.padEnd(7)}) : ${client.client_id}`);
    }
    return list;
}

// ------------------------------------------------------------------ CLI -----

/**
 * The sign-in methods Firebase Auth has switched on. Google sign-in fails with
 * OPERATION_NOT_ALLOWED while `google.com` is disabled, and the configured
 * clientId must be the project's WEB client id — the audience the app asks
 * Google for ([AppConfig.GOOGLE_WEB_CLIENT_ID]).
 */
async function idpProviders() {
    const res = await api(
        `https://identitytoolkit.googleapis.com/v2/projects/${PROJECT}` +
        '/defaultSupportedIdpConfigs?pageSize=100'
    );
    if (!res.ok) {
        throw new Error(`Providers read fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
    }
    return res.body.defaultSupportedIdpConfigs || [];
}

/** The `google.com` IdP config, or null when it was never created. */
async function googleProvider() {
    const providers = await idpProviders();
    return providers.find((p) => (p.name || '').endsWith('/google.com')) || null;
}

/**
 * The web client id the app asks Google for, read straight out of AppConfig.kt so
 * this tool and the APK can never drift apart.
 */
function appWebClientId() {
    const file = path.join(
        ROOT, 'app', 'src', 'main', 'java', 'com', 'example', 'vibemate', 'AppConfig.kt'
    );
    try {
        const hit = fs.readFileSync(file, 'utf8').match(/GOOGLE_WEB_CLIENT_ID[^"]*"([^"]+)"/);
        return hit ? hit[1] : '';
    } catch (e) {
        return '';
    }
}

/**
 * End-to-end verdict for "Continue with Google": the Firebase half (provider on,
 * web client id, SHA-1 -> Android OAuth client) plus what the app itself will
 * send. Every PROBLEM line is a real reason the phone cannot sign in.
 */
async function googleSignInReport() {
    const appId = await requireAndroidApp(DEFAULT_PACKAGE);
    const provider = await googleProvider();
    const webClientId = appWebClientId();
    const problems = [];

    console.log(`project       : ${PROJECT}`);
    if (!provider) {
        console.log('provider      : google.com MISSING');
        problems.push('Firebase -> Authentication -> Sign-in method -> Google -> Enable (ya: enable-google).');
    } else {
        console.log(`provider      : google.com ${provider.enabled ? 'ENABLED' : 'DISABLED'}`);
        if (!provider.enabled) problems.push('Google provider band hai — enable-google chalao.');
        console.log(`IdP clientId  : ${provider.clientId || '(set nahi hai)'}`);
        if (provider.clientId && webClientId && provider.clientId !== webClientId) {
            problems.push('IdP clientId aur AppConfig.GOOGLE_WEB_CLIENT_ID alag hain ' +
                `(${provider.clientId} vs ${webClientId}).`);
        }
    }
    console.log(`web client id : ${webClientId || '(AppConfig.kt se nahi padh saka)'}`);

    const list = await fingerprints(appId);
    console.log(`SHA registered: ${list.length === 0
        ? 'KOI NAHI'
        : list.map((fp) => fp.shaHash).join(', ')}`);
    if (list.length === 0) {
        problems.push('add-debug-sha chalao — SHA-1 ke bina Google Play services token hi nahi deta.');
    }

    const config = await androidAppConfig(appId);
    const clients = (config && config.client ? config.client : [])
        .flatMap((c) => c.oauth_client || []);
    const androidClients = clients.filter((c) => c.client_type === 1);
    console.log(`android oauth : ${androidClients.length === 0
        ? 'KOI NAHI (SHA-1 register hone par banta hai)'
        : androidClients.map((c) => c.client_id).join(', ')}`);
    if (list.length > 0 && androidClients.length === 0) {
        problems.push('SHA-1 register hai par Android OAuth client abhi bana nahi — kuch minute ruk kar dobara chalao.');
    }

    if (problems.length === 0) {
        console.log('OK  Firebase side poora set hai: phone par Google login chalega ' +
            '(naya SHA-1 device par lagne me 5-10 min lag sakte hain).');
        return;
    }
    console.log('PROBLEM:');
    for (const problem of problems) console.log(`  - ${problem}`);
    process.exitCode = 1;
}

/**
 * Registers one fingerprint (the shape returned by [normaliseSha]). Google then
 * creates the matching Android OAuth client for package + certificate, which is
 * exactly what makes Google sign-in work on device.
 */
async function addFingerprint(appId, fingerprint, packageName) {
    const res = await api(`${FIREBASE_MGMT}/projects/${PROJECT}/androidApps/${appId}/sha`, {
        method: 'POST',
        body: fingerprint,
    });
    if (res.ok) {
        console.log(`OK    ${res.body.shaHash || fingerprint.shaHash} (${res.body.certType || fingerprint.certType}) ` +
            'register ho gaya.');
        return true;
    }
    console.log(`FAIL  ${fingerprint.shaHash} register nahi hua (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 200)}`);
    console.log('      Console se add karo: Firebase -> Project settings -> ' +
        `${packageName} -> Add fingerprint -> ${fingerprint.shaHash}`);
    return false;
}

/** Runs keytool on a keystore and returns { sha1, sha256 }. */
function keystoreFingerprints(keystore, alias, storePass) {
    let out;
    try {
        out = execFileSync(
            'keytool',
            ['-list', '-v', '-keystore', keystore, '-alias', alias, '-storepass', storePass],
            { encoding: 'utf8' }
        );
    } catch (e) {
        throw new Error(`keytool nahi chala (JDK PATH me hona chahiye): ${e.message}`);
    }
    const pick = (label) => {
        const line = out.split(/\r?\n/).find((l) => l.trim().startsWith(`${label}:`));
        return line ? line.trim().slice(label.length + 1).trim() : '';
    };
    return { sha1: pick('SHA1'), sha256: pick('SHA256') };
}

const USAGE = `VibeMate admin helper (service-account ke saath)

  node admin/tools/firebase_admin.js whoami              # key + project check
  node admin/tools/firebase_admin.js users               # list Auth accounts
  node admin/tools/firebase_admin.js grant <email|uid>   # banao admin
  node admin/tools/firebase_admin.js revoke <email|uid>  # admin hatao
  node admin/tools/firebase_admin.js admins              # kaun admin hai
  node admin/tools/firebase_admin.js deploy-rules [file] # firestore.rules publish
  node admin/tools/firebase_admin.js live-rules         # abhi kya deployed hai
  node admin/tools/firebase_admin.js rules-diff         # live rules vs firestore.rules
  node admin/tools/firebase_admin.js check <email|uid>   # admin access verify (200/403)
  node admin/tools/firebase_admin.js delete-user <email|uid>  # Auth login delete
  node admin/tools/firebase_admin.js android-apps        # app + SHA-1 + OAuth clients
  node admin/tools/firebase_admin.js add-sha <sha1|sha256> [package]   # fingerprint register
  node admin/tools/firebase_admin.js add-debug-sha [keystore] [package] # debug key ka SHA-1
  node admin/tools/firebase_admin.js google-signin      # poora Google login check
  node admin/tools/firebase_admin.js providers          # sign-in methods (Google on/off)
  node admin/tools/firebase_admin.js enable-google      # Google sign-in on karo
`;

async function main() {
    const [command, argument] = process.argv.slice(2);
    if (!command || command === 'help' || command === '--help') {
        console.log(USAGE);
        return;
    }

    TOKEN = await accessToken();

    if (command === 'whoami') {
        console.log(`key file    : ${path.relative(ROOT, keyFile) || keyFile}`);
        console.log(`project     : ${PROJECT}`);
        console.log(`service acct: ${key.client_email}`);
        return;
    }

    if (command === 'users') {
        const users = await listUsers();
        console.log(`${users.length} Auth account(s) in ${PROJECT}:`);
        for (const user of users) {
            const when = (value) => (value ? new Date(Number(value)).toISOString().slice(0, 16).replace('T', ' ') : '—');
            // Which sign-in methods this account has. `passwordHash` is only
            // returned by downloadAccount, and only a YES/NO reaches the screen —
            // the hash itself is never printed.
            const providers = (user.providerUserInfo || []).map((p) => p.providerId).join(', ') || '—';
            const hasPassword = Boolean(user.passwordHash || user.salt);
            const methods = providers === 'password' && hasPassword ? 'password' : `${providers}${hasPassword ? ' + password' : ''}`;
            console.log(
                `  ${(user.email || '(anonymous)').padEnd(38)} uid ${user.localId}` +
                `  created ${when(user.createdAt)}  last login ${when(user.lastLoginAt)}` +
                `  providers ${methods}` +
                `${user.disabled ? '  [DISABLED]' : ''}`
            );
        }
        return;
    }

    if (command === 'admins') {
        const admins = await listAdmins();
        if (admins.length === 0) {
            console.log('Koi admins/{uid} document nahi hai — abhi koi console-granted admin nahi.');
            return;
        }
        console.log(`${admins.length} admin(s):`);
        for (const admin of admins) {
            console.log(`  ${(admin.email || '(no email)').padEnd(38)} uid ${admin.uid}`);
        }
        return;
    }

    if (command === 'stats') {
        console.log(`Firestore collections in ${PROJECT} (first page, 300 docs):`);
        for (const collection of ['users', 'chat_sessions', 'call_records', 'admins']) {
            const { docs, more } = await countDocs(collection);
            console.log(`  ${collection.padEnd(16)} ${docs}${more ? '+' : ''} document(s)`);
        }
        return;
    }

    if (command === 'check') {
        if (!argument) throw new Error('check ke liye email ya uid do.');
        const { uid, results } = await verifyAdminAccess(argument);
        let failed = 0;
        for (const [what, status] of results) {
            const ok = status === 200;
            if (!ok) failed++;
            console.log(`${ok ? 'PASS' : 'FAIL'}  ${what} as '${argument}' (uid ${uid}) -> HTTP ${status}`);
        }
        console.log(
            failed === 0
                ? `OK  '${argument}' ab admin hai — poora panel khul jayega.`
                : `'${argument}' ko abhi access nahi hai (403). admins/${uid} doc ya isAdmin() list check karo.`
        );
        process.exitCode = failed === 0 ? 0 : 1;
        return;
    }

    if (command === 'delete-user') {
        if (!argument) throw new Error('delete-user ke liye email ya uid do.');
        const uid = await deleteAuthUser(argument);
        console.log(`OK  Auth account delete ho gaya (uid ${uid}).`);
        return;
    }

    if (command === 'grant') {
        if (!argument) throw new Error('grant ke liye email ya uid do.');
        const uid = await grant(argument);
        console.log(`OK  admins/${uid} likh diya — '${argument}' ab admin hai.`);
        return;
    }

    if (command === 'revoke') {
        if (!argument) throw new Error('revoke ke liye email ya uid do.');
        const uid = await revoke(argument);
        console.log(`OK  admins/${uid} delete ho gaya — '${argument}' ka admin khatam.`);
        return;
    }

    if (command === 'deploy-rules') {
        const ruleset = await deployRules(argument || undefined);
        console.log(`OK  firestore.rules publish ho gaya (${ruleset}).`);
        return;
    }

    if (command === 'live-rules') {
        await liveRules();
        return;
    }

    if (command === 'rules-diff') {
        await rulesDiff(argument || undefined);
        return;
    }

    if (command === 'android-apps') {
        const apps = await androidApps();
        if (apps.length === 0) {
            console.log(`${PROJECT} me koi Android app registered nahi hai.`);
            return;
        }
        console.log(`${apps.length} Android app(s) in ${PROJECT}:`);
        for (const app of apps) {
            await showAndroidApp(app);
        }
        return;
    }

    if (command === 'add-sha') {
        if (!argument) throw new Error('SHA-1 ya SHA-256 do: add-sha 77:84:6E:...');
        const packageName = process.argv[4] || DEFAULT_PACKAGE;
        const appId = await requireAndroidApp(packageName);
        await addFingerprint(appId, normaliseSha(argument), packageName);
        console.log('Note: phone par naya fingerprint turant nahi lagta — 5-10 min ' +
            'ya app reinstall ke baad Google login chalta hai.');
        await showAndroidApp({ packageName, appId });
        return;
    }

    if (command === 'add-debug-sha') {
        const keystore = process.argv[4] || path.join(os.homedir(), '.android', 'debug.keystore');
        if (!fs.existsSync(keystore)) {
            throw new Error(`Debug keystore nahi mila: ${keystore}\n` +
                'Android Studio ka path do: add-debug-sha <keystore> [package]');
        }
        const packageName = process.argv[5] || DEFAULT_PACKAGE;
        const appId = await requireAndroidApp(packageName);
        const { sha1, sha256 } = keystoreFingerprints(keystore, 'androiddebugkey', 'android');
        if (!sha1) {
            throw new Error(`'${keystore}' me androiddebugkey (pass 'android') nahi mila — ` +
                'alias ya password doosra hai, ya key release key hai.');
        }
        console.log(`debug keystore: ${keystore}`);
        console.log(`  SHA1   ${sha1}`);
        if (sha256) console.log(`  SHA256 ${sha256}   (register karne ke liye: add-sha ${sha256})`);
        await addFingerprint(appId, normaliseSha(sha1), packageName);
        console.log('Note: phone par naya fingerprint turant nahi lagta — 5-10 min ' +
            'ya app reinstall ke baad Google login chalta hai.');
        await showAndroidApp({ packageName, appId });
        return;
    }

    if (command === 'providers') {
        const providers = await idpProviders();
        if (providers.length === 0) {
            console.log(`${PROJECT} me koi IdP config nahi mila (list khaali aayi).`);
            return;
        }
        console.log(`Sign-in methods of ${PROJECT}:`);
        for (const provider of providers) {
            const id = (provider.name || '').split('/').pop();
            console.log(`  ${id.padEnd(24)} ${provider.enabled ? 'ENABLED ' : 'disabled'}` +
                `${provider.clientId ? `  client ${provider.clientId}` : '  (clientId set nahi hai)'}`);
        }
        return;
    }

    if (command === 'google-signin') {
        await googleSignInReport();
        return;
    }

    if (command === 'enable-google') {
        const res = await api(
            `https://identitytoolkit.googleapis.com/v2/projects/${PROJECT}` +
            '/defaultSupportedIdpConfigs/google.com?updateMask=enabled',
            { method: 'PATCH', body: { enabled: true } }
        );
        if (!res.ok) {
            throw new Error(`Google enable fail (HTTP ${res.status}): ${JSON.stringify(res.body).slice(0, 300)}`);
        }
        console.log('Google sign-in ENABLED ho gaya.');
        await googleSignInReport();
        return;
    }

    console.log(USAGE);
    throw new Error(`'${command}' naam ka koi command nahi hai.`);
}

main().catch((error) => {
    console.error(`\nFAIL  ${error.message}`);
    process.exit(1);
});