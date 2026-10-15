 /**
 * Verifies `firestore.rules` (the admin override) against the Firebase EMULATOR
 * suite — throw-away accounts, no real project, no real data, no cost.
 *
 *   firebase emulators:exec --only firestore,auth --project demo-vibemate \
 *     "node admin/tools/verify_admin_rules.js"
 *
 * It signs two accounts up, grants admin to one of them exactly the way the
 * README describes (an `admins/<uid>` document) and then proves that
 *   * a normal user still cannot see or touch anybody else's data,
 *   * a normal user can FLAG his own chat ("delete" / "clear") but can never
 *     really DELETE a chat or a message — only an admin can (soft delete),
 *   * an admin CAN list / read / delete other users' documents,
 *   * an admin can NEVER write someone else's document,
 *   * creating or deleting that `admins/<uid>` document grants / revokes access
 *     instantly (no rules redeploy).
 */
const PROJECT = 'demo-vibemate';
const FS = `http://127.0.0.1:8080/v1/projects/${PROJECT}/databases/(default)/documents`;
const AUTH = 'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1';

/** Emulator-only token: full access, bypasses rules (used to set the fixture up). */
const BYPASS = 'owner';

/**
 * OPTIONAL: the REAL admin e-mail that `isAdmin()` lists (EDIT 1 in
 * firestore.rules). Set it to also prove that the e-mail rule allows access:
 *
 *   PowerShell:  $env:VERIFY_ADMIN_EMAIL="you@example.com"
 *   bash:        VERIFY_ADMIN_EMAIL=you@example.com
 *
 * The Auth emulator accepts any e-mail, so this checks the production rule for
 * that address without touching the real project.
 */
const ADMIN_EMAIL = process.env.VERIFY_ADMIN_EMAIL || '';

let failures = 0;

const str = (value) => ({ stringValue: value });
const bool = (value) => ({ booleanValue: value });

async function signUp(email) {
    const res = await fetch(`${AUTH}/accounts:signUp?key=fake-key`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password: 'password123', returnSecureToken: true }),
    });
    const body = await res.json();
    if (!body.idToken) throw new Error(`sign-up failed for ${email}: ${JSON.stringify(body)}`);
    return { email, uid: body.localId, idToken: body.idToken };
}

/** Sends one Firestore REST call and returns its HTTP status. */
async function request(url, { token, method = 'GET', fields } = {}) {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (fields) headers['Content-Type'] = 'application/json';
    const res = await fetch(url, {
        method,
        headers,
        body: fields ? JSON.stringify({ fields }) : undefined,
    });
    await res.text();
    return res.status;
}

async function check(what, expected, url, options) {
    const actual = await request(url, options);
    const ok = actual === expected;
    if (!ok) failures++;
    console.log(
        `${ok ? 'PASS' : 'FAIL'}  ${what}${ok ? '' : `   <- expected ${expected}, got ${actual}`}`
    );
}

function section(title) {
    console.log(`\n--- ${title} ---`);
}

(async () => {
    const alice = await signUp('alice@example.com');
    const bob = await signUp('bob@example.com');

    section('fixture: two users writing their OWN data');
    await check('alice creates her own profile', 200, `${FS}/users?documentId=${alice.uid}`, {
        token: alice.idToken, method: 'POST', fields: { display_name: str('Alice') },
    });
    await check('alice creates her own chat', 200, `${FS}/chat_sessions?documentId=s-alice`, {
        token: alice.idToken, method: 'POST',
        fields: {
            owner_id: str(alice.uid), title: str('Hi'), persona: str('girlfriend'),
            mate_name: str('Aisha'), private_mode: bool(true),
        },
    });
    for (const id of ['m1', 'm2']) {
        await check(`alice writes message ${id}`, 200,
            `${FS}/chat_sessions/s-alice/messages?documentId=${id}`, {
                token: alice.idToken, method: 'POST',
                fields: { role: str('user'), type: str('text'), content: str('hello') },
            });
    }
    await check('alice writes a call record', 200, `${FS}/call_records?documentId=c-alice`, {
        token: alice.idToken, method: 'POST',
        fields: { user_id: str(alice.uid), session_id: str('s-alice'), status: str('ended') },
    });
    await check('bob creates his own chat', 200, `${FS}/chat_sessions?documentId=s-bob`, {
        token: bob.idToken, method: 'POST',
        fields: { owner_id: str(bob.uid), title: str('Yo'), persona: str('friend') },
    });

    section("the README's grant: ONE admins/{uid} document (console way)");
    // PATCH without an updateMask is the REST "create or replace" (like set()).
    await check('owner creates admins/{bob.uid}', 200, `${FS}/admins/${bob.uid}`, {
        token: BYPASS, method: 'PATCH', fields: { admin: bool(true) },
    });

    section("a NORMAL user stays locked out of everybody else's data");
    await check('alice cannot list all users', 403, `${FS}/users?pageSize=20`, { token: alice.idToken });
    await check("alice cannot read bob's profile", 403, `${FS}/users/${bob.uid}`, { token: alice.idToken });
    await check("alice cannot read bob's chat", 403, `${FS}/chat_sessions/s-bob`, { token: alice.idToken });
    await check('alice cannot list all chats', 403, `${FS}/chat_sessions?pageSize=50`, { token: alice.idToken });
    await check('alice cannot list all call records', 403, `${FS}/call_records?pageSize=20`, { token: alice.idToken });
    await check("alice cannot delete bob's chat", 403, `${FS}/chat_sessions/s-bob`, {
        token: alice.idToken, method: 'DELETE',
    });

    section('the OWNER path: read + flag a chat, but never a real delete');
    await check('alice reads her own profile', 200, `${FS}/users/${alice.uid}`, { token: alice.idToken });
    await check('alice reads her own chat', 200, `${FS}/chat_sessions/s-alice`, { token: alice.idToken });
    await check('alice lists her own messages', 200, `${FS}/chat_sessions/s-alice/messages?pageSize=50`, {
        token: alice.idToken,
    });
    // "Delete chat" in the app: a masked PATCH of the two soft-delete fields.
    await check('alice "deletes" her own chat (soft flag)', 200,
        `${FS}/chat_sessions/s-alice?updateMask.fieldPaths=deleted_by_owner` +
        '&updateMask.fieldPaths=deleted_at', {
            token: alice.idToken, method: 'PATCH',
            fields: {
                deleted_by_owner: bool(true),
                deleted_at: str('2026-02-01T09:30:00Z'),
            },
        });
    await check('alice can no longer DELETE her own chat', 403, `${FS}/chat_sessions/s-alice`, {
        token: alice.idToken, method: 'DELETE',
    });
    await check('alice can no longer DELETE her own message', 403,
        `${FS}/chat_sessions/s-alice/messages/m1`, {
            token: alice.idToken, method: 'DELETE',
        });
    await check('alice cannot un-delete her chat', 403, `${FS}/chat_sessions/s-alice`, {
        token: alice.idToken, method: 'PATCH', fields: { deleted_by_owner: bool(false) },
    });
    // "Clear chat": only the cleared_at marker, the messages all stay stored.
    await check('alice can still "clear" her chat (soft flag)', 200,
        `${FS}/chat_sessions/s-alice?updateMask.fieldPaths=cleared_at`, {
            token: alice.idToken, method: 'PATCH',
            fields: { cleared_at: str('2026-02-02T11:00:00Z') },
        });
    await check('alice can still write a new message', 200,
        `${FS}/chat_sessions/s-alice/messages?documentId=m3`, {
            token: alice.idToken, method: 'POST',
            fields: { role: str('user'), type: str('text'), content: str('after clear') },
        });

    section("an ADMIN can list, read and delete other users' data");
    await check('bob lists every user', 200, `${FS}/users?pageSize=20`, { token: bob.idToken });
    await check("bob reads alice's profile", 200, `${FS}/users/${alice.uid}`, { token: bob.idToken });
    await check('bob lists every chat', 200, `${FS}/chat_sessions?pageSize=50`, { token: bob.idToken });
    await check("bob reads alice's chat", 200, `${FS}/chat_sessions/s-alice`, { token: bob.idToken });
    await check("bob lists alice's messages", 200, `${FS}/chat_sessions/s-alice/messages?pageSize=50`, {
        token: bob.idToken,
    });
    await check("bob reads alice's message m2", 200, `${FS}/chat_sessions/s-alice/messages/m2`, {
        token: bob.idToken,
    });
    await check('bob lists every call record', 200, `${FS}/call_records?pageSize=20`, { token: bob.idToken });

    section("an ADMIN can never WRITE somebody else's data");
    await check("bob cannot update alice's profile", 403, `${FS}/users/${alice.uid}`, {
        token: bob.idToken, method: 'PATCH', fields: { display_name: str('hacked') },
    });
    await check("bob cannot create another user's profile", 403, `${FS}/users?documentId=ghost`, {
        token: bob.idToken, method: 'POST', fields: { display_name: str('ghost') },
    });
    await check('bob cannot forge a chat owned by alice', 403, `${FS}/chat_sessions?documentId=s-forged`, {
        token: bob.idToken, method: 'POST', fields: { owner_id: str(alice.uid) },
    });
    await check("bob cannot plant a message in alice's chat", 403,
        `${FS}/chat_sessions/s-alice/messages?documentId=m-planted`, {
            token: bob.idToken, method: 'POST',
            fields: { role: str('mate'), type: str('text'), content: str('x') },
        });
    await check('bob cannot rewrite an admins document', 403, `${FS}/admins/${bob.uid}`, {
        token: bob.idToken, method: 'PATCH', fields: { admin: bool(false) },
    });
    await check('alice cannot promote herself', 403, `${FS}/admins/${alice.uid}`, {
        token: alice.idToken, method: 'PATCH', fields: { admin: bool(true) },
    });

    section('the exact deletes the admin panel performs');
    await check("bob deletes alice's message m2", 200, `${FS}/chat_sessions/s-alice/messages/m2`, {
        token: bob.idToken, method: 'DELETE',
    });
    await check("bob deletes alice's chat", 200, `${FS}/chat_sessions/s-alice`, {
        token: bob.idToken, method: 'DELETE',
    });
    await check("bob deletes alice's call record", 200, `${FS}/call_records/c-alice`, {
        token: bob.idToken, method: 'DELETE',
    });
    await check("bob deletes alice's profile", 200, `${FS}/users/${alice.uid}`, {
        token: bob.idToken, method: 'DELETE',
    });
    await check('bob still sees his own chat', 200, `${FS}/chat_sessions/s-bob`, { token: bob.idToken });

    section('grant / revoke is instant (no rules redeploy needed)');
    await check('alice is not an admin yet', 403, `${FS}/users?pageSize=20`, { token: alice.idToken });
    await check('owner creates admins/{alice.uid}', 200, `${FS}/admins/${alice.uid}`, {
        token: BYPASS, method: 'PATCH', fields: { admin: bool(true) },
    });
    await check('alice can read her own admins document', 200, `${FS}/admins/${alice.uid}`, {
        token: alice.idToken,
    });
    await check('now alice CAN list every user', 200, `${FS}/users?pageSize=20`, { token: alice.idToken });
    await check('owner deletes admins/{alice.uid}', 200, `${FS}/admins/${alice.uid}`, {
        token: BYPASS, method: 'DELETE',
    });
    await check('and alice is locked out again', 403, `${FS}/users?pageSize=20`, { token: alice.idToken });

    if (ADMIN_EMAIL) {
        section(`rule 1 (e-mail list): ${ADMIN_EMAIL} is an admin`);
        const owner = await signUp(ADMIN_EMAIL);
        await check(`${ADMIN_EMAIL} CAN list every user`, 200, `${FS}/users?pageSize=20`, {
            token: owner.idToken,
        });
        await check(`${ADMIN_EMAIL} CAN list every chat`, 200, `${FS}/chat_sessions?pageSize=50`, {
            token: owner.idToken,
        });
    }

    console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`);
    process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
    console.error(`\nverification crashed: ${e.message}`);
    process.exit(1);
});


