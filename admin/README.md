# VibeMate Admin (second APK)

An owner-only Android app that lists **every user**, opens a user's **whole chat
history** and deletes data with a **long-press → All → Delete** flow.

```
Sign in  ->  USERS  ->  one user's CHATS  ->  one chat's MESSAGES
             │ tap                        │ tap
             └ long-press = select ───────┴ long-press = select
               All  = select every row      All  = select every row
               Delete = wipe it all         Delete = wipe chats + messages
```

| Screen | What it does |
| --- | --- |
| `AdminLoginActivity` | Email + password sign-in (Firebase REST). Login is remembered. |
| `AdminUsersActivity` | Every user, ordered by their **latest chat (newest activity on top)** — profile name, uid, chat count, 18+ chat count, `last chat` time. Long-press selects. |
| `AdminChatsActivity` | One user's chats (persona, title, last update, `18+` badge), **newest first**. Long-press selects. |
| `AdminMessagesActivity` | Full history of one chat, oldest first — text, photos (inline), voice notes, documents. |

Deleting a user removes **all** of their data: every chat, every message, every
call record and the `users/{uid}` profile document.

## The user can NOT delete anything any more (soft delete)

When a user deletes a chat / clears a chat in the phone app, **nothing is removed
from Firestore**. The app only writes a flag on the `chat_sessions` document
(`VibeMateApi.SessionDeletePolicy`):

| User action in the app | What is written | What the admin panel shows |
| --- | --- | --- |
| Delete chat | `deleted_by_owner = true`, `deleted_at` | The chat stays in the list with a dark **USER DELETED** badge, all messages intact |
| Clear chat | `cleared_at = <now>` | The chat stays, every message stays readable (meta line says `user ne clear kiya`) |

So a chat the user "deleted" on their phone is still fully readable here — the
**only final delete is the one you do in this app** (`Delete` on the chats screen:
messages first, then the session document). `firestore.rules` backs this up: the
owner has no `delete` right on `chat_sessions` / `messages` any more, and the
`deleted_by_owner` flag can only move `false → true` (a user cannot un-delete and
cannot wipe history behind your back). The users list therefore shows
`N user-deleted` next to the chat counts.

---

## One-time setup (3 minutes)

### 1. Create the admin account

Firebase Console → **Authentication** → **Users** → **Add user** → put an email +
password you will only use for the admin app (e.g. `admin@vibemate.app`).

### 2. Make that account an ADMIN (one document — no redeploy)

Pick ONE of these three. The first is the easiest and can be repeated any time.

**A. `admins/{uid}` document (recommended, nothing to deploy)**

Firebase Console → **Firestore Database** → **Start collection** → collection ID
`admins` → **Add document** → *Document ID* = the account's **UID**
(Authentication → Users → copy the UID) → add a field, e.g. `admin` = `true`
(Boolean) → **Save**.

Sign in to the admin app again — it works. **Delete that document to revoke
access instantly**: no code change, no rules deploy, no rebuild.

**B. E-mail list in the rules (needs a deploy)**

`firestore.rules` (project root) → `isAdmin()` → EDIT 1:

```javascript
request.auth.token.email.lower() in [
  'you@example.com'               // <-- your admin account email
]
```

**C. uid list in the rules (needs a deploy)**

Same function, EDIT 2 → `request.auth.uid in ['PASTE_YOUR_ADMIN_FIREBASE_UID']`.

If (and only if) you edited the RULES file, deploy it:

```powershell
firebase deploy --only firestore:rules
# or paste the file into Firebase Console → Firestore Database → Rules → Publish
```

> No client can write to `admins/`, so an admin can never promote themselves.

### 3. (Optional) Reject non-admins before the network call

`admin/src/main/java/com/example/vibemate/admin/AdminConfig.kt` ships with an
EMPTY list on purpose:

```kotlin
val ADMIN_EMAILS: List<String> = emptyList()   // any account may TRY to sign in
```

Empty means any valid Firebase account may sign in and `firestore.rules` decides
who is really an admin. A non-admin then gets a 403 whose message names the
signed-in **e-mail + uid** and the exact `admins/{uid}` document to create — so
access can be granted from the console without touching the APK.

Fill the list only if you want non-admins rejected on the login screen itself
(keep it identical to `isAdmin()`), then rebuild the APK.

### 4. Build / install

```powershell
.\gradlew :admin:installDebug          # install on the connected phone
.\gradlew :admin:assembleDebug         # APK: admin/build/outputs/apk/debug/admin-debug.apk
```

The admin app installs **next to** VibeMate (applicationId
`com.example.vibemate.admin`) and changes nothing inside the main app.

### 5. Deploying the rules / granting admins without the Firebase CLI

`firebase deploy` needs the machine's CLI login to really own the project. When
that login cannot use it (`403 ... Caller does not have required permission to
use project ...`), use the service-account helper instead — it calls the same
Google APIs the Admin SDK uses, so it is not affected by that check:

```powershell
node admin/tools/firebase_admin.js whoami          # key + project check
node admin/tools/firebase_admin.js users           # every Auth account (+ uid)
node admin/tools/firebase_admin.js deploy-rules    # publishes firestore.rules
node admin/tools/firebase_admin.js grant you@example.com    # = admins/{uid}
node admin/tools/firebase_admin.js admins          # who is an admin right now
node admin/tools/firebase_admin.js check you@example.com    # prove 200/403
node admin/tools/firebase_admin.js stats           # Firestore document counts
node admin/tools/firebase_admin.js revoke you@example.com   # remove access
node admin/tools/firebase_admin.js android-apps    # app + SHA-1 + OAuth clients
node admin/tools/firebase_admin.js add-debug-sha   # debug keystore ka SHA-1 register
```

`deploy-rules` creates a new ruleset, points the live `cloud.firestore` release
at it **and reads the release back**, so a successful run proves the deployed
rules really contain `admins/{uid}`. `check` mints a real ID token for the
account (Firebase **custom token**, signed with the key — no password needed) and
reads `users` / `chat_sessions` / `call_records`: `HTTP 200` = admin, `403` = not
yet.

Credential: Firebase Console → Project settings → **Service accounts** →
*Generate new private key* → save the JSON in the project root as `sa.json` (any
`*-adminsdk-*.json` also works; both are git-ignored). **Delete the key again**
(Console → Service accounts) when you are done — it grants full access to the
project.

### 6. Google sign-in check (app ka SHA-1)

Google login on device only works when Firebase knows the app's **package name +
signing certificate**. That registration is what makes Google create the Android
OAuth client Google Play services looks for; without it every "Continue with
Google" tap fails before the app even gets a token.

```powershell
node admin/tools/firebase_admin.js google-signin    # full verdict (exit 1 = kuch missing)
node admin/tools/firebase_admin.js providers        # google.com ENABLED / disabled
node admin/tools/firebase_admin.js enable-google    # band ho to on karo
node admin/tools/firebase_admin.js android-apps     # SHA list + OAuth clients
```

`google-signin` prints the whole chain, for example:

```
project       : vibemate-3b75b
provider      : google.com ENABLED
IdP clientId  : 371766671790-v03...apps.googleusercontent.com
web client id : 371766671790-v03...apps.googleusercontent.com
SHA registered: 77846e45b80ab8af118da7524800c6cc871ec512
android oauth : 371766671790-88m...apps.googleusercontent.com
OK  Firebase side poora set hai ...
```

Registering the **debug** key (Run from Android Studio) is one command — it reads
`~/.android/debug.keystore` (alias `androiddebugkey`, password `android`) with
`keytool`, so the JDK must be on `PATH`:

```powershell
node admin/tools/firebase_admin.js add-debug-sha
node admin/tools/firebase_admin.js add-sha <sha1|sha256> [package]   # manual
```

`add-sha` accepts `77:84:6E:...` or bare hex and decides SHA-1/SHA-256 from the
length; re-adding an existing fingerprint is a no-op, so it is safe to repeat.
Play-signed builds need the **Play Console → App integrity → App signing key
certificate** SHA-1 registered the same way (debug/release keys each need their
own). A newly added fingerprint takes ~5–10 minutes (or one app reinstall) before
the phone picks it up.

> API gotcha baked into the tool: `projects.androidApps.sha.create` wants
> `{ "shaHash", "certType": "SHA_1" | "SHA_256" }`. Any other field name (e.g.
> `certificateType`) is answered with a bare `400 INVALID_ARGUMENT` that never
> names the offending field.

---

## Why it works without a server or `google-services.json`

The admin app talks straight to the same Firebase project over REST, exactly the
way the main app does:

* `AdminAuth.kt` — `accounts:signInWithPassword` + the `securetoken` refresh API.
* `AdminStore.kt` — Firestore REST reads (`users`, `chat_sessions`,
  `chat_sessions/{id}/messages`, `call_records`) and deletes.

`AdminConfig.kt` holds the same `FIREBASE_API_KEY` / `FIREBASE_PROJECT_ID` as the
app's `AppConfig.kt` — **if you ever change projects, update both files.**

## Security notes (please read)

* Admins can **read and delete**, never **write** someone else's documents — a
  stolen admin login cannot plant or rewrite chat data.
* Every other rule stays owner-only, so normal users still cannot see each other.
* Admin access lives in `admins/{uid}` (preferred) or in `isAdmin()` itself.
  Deleting that document — or removing the e-mail/uid and re-deploying — revokes
  access instantly. Clients can never write `admins/`, so nobody can promote
  themselves.
* Deleting a user here removes their **Firestore data**, not their Firebase
  Authentication login (that needs the Admin SDK) — delete that in
  Firebase Console → Authentication → Users if you want it gone.
* The admin APK contains no user data; it fetches everything live.

## The "(no owner)" row

Old buggy builds wiped `owner_id` on some `chat_sessions`. Those chats are shown
as one `(no owner)` row at the bottom so you can see and delete them (no profile
document is touched, because there is none).

## Tests

```powershell
.\gradlew :admin:testDebugUnitTest        # fast, offline Kotlin tests
```

* `AdminParsingTest` — Firestore REST shapes (collection list, `:runQuery`,
  single document), the users ∪ session-owners merge, pagination and the
  `admins/{uid}`-free allow-list.
* `AdminUserDeletedChatTest` — the soft delete: a chat the user deleted (or
  cleared) in the app is still parsed, still listed and still counted, so only the
  admin's delete is final.
* `AdminGrantHintTest` — the "you are 403, here is how to become an admin" text.
* `AdminPaginationTest` — "sare chats" really means ALL of them: every chat of a
  user is kept even past 300 (the old fixed-`limit` query dropped the rest), other
  owners are excluded, soft-deleted chats still show, and the `:runQuery` pages
  walk their `offset` until a short page arrives.
* `AdminOrderingTest` — "latest chat sabse upar": the timestamp canonicalisation
  that makes ordering exact even when two chats are touched inside the same second
  (`Instant.now()` writes fractions of varying width), the chats list newest-first
  and the users list ordered by each user's latest chat.

### Verifying the rules (no real project, no cost)

`admin/tools/verify_admin_rules.js` runs the whole authorisation matrix against
the **emulator suite**: it creates two throw-away accounts, grants admin to one
of them the exact way step 2A describes, and then asserts that a normal user is
still locked out, an admin can list/read/delete other users' data, an admin can
never *write* it, and that deleting the `admins/{uid}` document revokes access
immediately.

```powershell
firebase emulators:exec --only firestore,auth --project demo-vibemate `
  "node admin/tools/verify_admin_rules.js"
```

Expected last line: `ALL CHECKS PASSED` (41 checks). The Auth + Firestore
emulator ports are declared in `firebase.json`. `firebase-tools` needs **JDK 21
or newer on your PATH** for the emulators (`java -version` must print 21+;
Android Studio's `jbr`, or `~/.gradle/jdks/eclipse_adoptium-21-*`, both work).

Once you have put your real admin e-mail into `isAdmin()` (step 2B), the tool can
also prove that the e-mail route lets that exact address in — 2 extra checks:

```powershell
$env:VERIFY_ADMIN_EMAIL = "you@example.com"
firebase emulators:exec --only firestore,auth --project demo-vibemate `
  "node admin/tools/verify_admin_rules.js"
```

(Those 2 checks are *meant* to fail before you edit the rules — the list is
empty by default, so no e-mail is an admin.)

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Firestore rules ne ye account admin nahi maana (403)` | The rules have not been deployed, or this account is not an admin yet. The message on screen shows your e-mail + uid — do step 2A (or 2B/2C). |
| `Ye email AdminConfig.ADMIN_EMAILS me nahi hai` | Only if you filled that list: add the email there (or empty the list), then rebuild. |
| `Email ya password galat hai` | Wrong credentials (or the account does not exist yet). |
| Users load but "chat count nahi mila" warning | The `chat_sessions` list was denied; usually the same rules problem as the 403. |
| Old login keeps asking again | Sign out in the header, sign in once; the refresh token is stored after a successful sign-in. |
| Emulator says `no longer supports Java version before 21` | Put a JDK 21+ `bin` folder first on PATH (Android Studio's `jbr` works). |
| `firebase deploy` says `403 ... required permission to use project` | That CLI login does not own the project. Either add it in Console → Project settings → Users and permissions, or use the service-account helper (step 5). |
| `sa.json` not found | Step 5: download it from Project settings → Service accounts, or set `SA_PATH` / `GOOGLE_APPLICATION_CREDENTIALS`. |