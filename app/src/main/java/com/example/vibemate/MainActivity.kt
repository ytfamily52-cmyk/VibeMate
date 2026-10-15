package com.example.vibemate

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.constraintlayout.widget.ConstraintLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast

/**
 * Single activity for the whole VibeMate flow:
 *  - auth panel: log in / create account
 *  - chat panel: one live chat session with the backend AI
 *
 * Handlers are wired to buttons via android:onClick in activity_main.xml;
 * the framework calls them as methodName(View). Network calls run on
 * background threads and UI updates are marshalled back with runOnUiThread.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val ATTACH_PHOTO = 1004
        private const val ATTACH_AUDIO = 1001
        private const val ATTACH_VIDEO = 1002
        private const val ATTACH_DOCUMENT = 1003
        private const val MENU_PROFILE = 101
        private const val MENU_NEW_CHAT = 102
        private const val MENU_LOGOUT = 103
        private const val MENU_CLEAR_CHAT = 104
        private const val MENU_MY_MATES = 105
        private const val MENU_DELETE_CHAT = 106
        private const val REQUEST_PICK_PHOTO = 2004
        private const val REQUEST_PICK_AUDIO = 2001
        private const val REQUEST_PICK_VIDEO = 2002
        private const val REQUEST_PICK_DOCUMENT = 2003
        private const val REQUEST_CAPTURE_PHOTO = 3001
        /** Runtime mic permission for the live voice call (RECORD_AUDIO). */
        private const val REQUEST_MIC = 4001
        /**
         * Pause before the ONE silent retry of a failed call turn. Short enough
         * that a real blip is covered without the user noticing a gap, long
         * enough for a dropped socket to settle (see onCallSpeechHeard).
         */
        private const val CALL_TURN_RETRY_DELAY_MS = 700L
        // WhatsApp-style attachment size limits (base64-encoded)
        private const val MAX_PHOTO_BYTES = 5 * 1024 * 1024       // 5 MB
        private const val MAX_AUDIO_BYTES = 1024 * 1024           // 1 MB
        private const val MAX_VIDEO_BYTES = 5 * 1024 * 1024       // 5 MB
        private const val MAX_DOCUMENT_BYTES = 10 * 1024 * 1024   // 10 MB

        /**
         * Relation dropdown of the "Add a relationship" panel, in display order:
         * dropdown label -> relation id. The "" relation is the "Select" hint row,
         * so the panel starts with no relationship chosen and Start Chat off.
         *
         * "Gay" is one row, but a same-sex couple can be EITHER kind of couple, so
         * this row only carries the umbrella id [GAY_RELATION] and the exact
         * persona comes from [GAY_TYPE_OPTIONS] in the "Gay type" row below it.
         */
        private val RELATION_OPTIONS: List<Pair<String, String>> = listOf(
            "Select" to "",
            "Girlfriend" to "girlfriend",
            "Boyfriend" to "boyfriend",
            "Crush" to "crush",
            "Friend" to "friend",
            "Gay" to GAY_RELATION,
        )

        /** Umbrella id of the "Gay" row — never sent to the backend as a persona
         *  (see [personaOrGayType], which resolves it to gay_boy / gay_girl). */
        private const val GAY_RELATION = "gay"

        /**
         * The "Gay type" rows, shown ONLY while the relation is "Gay": label ->
         * persona id sent to the backend. A gay relationship is a same-sex couple,
         * so the mate's gender is the user's own gender:
         *   - "Boy Gay"  -> mate = MAN,   user = MAN   (PersonaPrompts.GAY_BOY)
         *   - "Girl Gay" -> mate = WOMAN, user = WOMAN (PersonaPrompts.GAY_GIRL)
         * "Boy Gay" is row 0, so it is already the default the moment "Gay" is
         * picked — one tap on the relation is enough to start a chat.
         */
        private val GAY_TYPE_OPTIONS: List<Pair<String, String>> = listOf(
            "Boy Gay" to PersonaPrompts.GAY_BOY,
            "Girl Gay" to PersonaPrompts.GAY_GIRL,
        )
    }

    private lateinit var authPanel: View
    /** Scrolls the login card: used to bring the status line into view. */
    private lateinit var authScroll: ScrollView
    private lateinit var chatPanel: View
    private lateinit var personaPanel: View
    private lateinit var matesPanel: View
    private lateinit var matesList: RecyclerView
    private lateinit var matesAdapter: MatesAdapter
    private lateinit var matesEmpty: AppCompatTextView
    private lateinit var matesTitle: AppCompatTextView
    private lateinit var matesAddButton: ImageButton
    private lateinit var matesLogoutButton: ImageButton
    private lateinit var matesSelectAllButton: AppCompatButton
    private lateinit var matesDeleteButton: AppCompatButton
    private lateinit var matesSelectButton: AppCompatButton

    private lateinit var emailInput: AppCompatEditText
    private lateinit var passwordInput: AppCompatEditText
    private lateinit var nameInput: AppCompatEditText
    private lateinit var statusText: AppCompatTextView
    private lateinit var loginButton: AppCompatButton
    private lateinit var registerButton: AppCompatButton
    private lateinit var anonymousButton: AppCompatButton
    private lateinit var googleButton: AppCompatButton

    // "Add a relationship" (contact) panel: the ONE place where the relationship
    // is chosen. The Gay type row only shows up for the "Gay" relation.
    private lateinit var personaRelationSpinner: Spinner
    private lateinit var personaGayTypeRow: View
    private lateinit var personaGayTypeSpinner: Spinner
    private lateinit var personaStartButton: AppCompatButton
    private lateinit var mateNameInput: AppCompatEditText

    private lateinit var chatTitle: AppCompatTextView
    private lateinit var chatStatus: AppCompatTextView
    private lateinit var chatAvatar: AppCompatTextView
    private lateinit var privateButton: AppCompatButton
    private lateinit var chatTitleBlock: View
    private lateinit var callButton: View
    private lateinit var chatMenuButton: View
    private lateinit var chatSelectCancel: View
    private lateinit var chatSelectTitle: TextView
    private lateinit var chatSelectDelete: AppCompatButton
    private lateinit var chatList: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var messageInput: AppCompatEditText
    private lateinit var sendButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var cameraButton: ImageButton
    // Pending-photo preview (photo selected but not yet sent).
    private lateinit var photoPreviewBar: View
    private lateinit var photoPreviewThumb: androidx.appcompat.widget.AppCompatImageView
    private lateinit var photoPreviewCancel: ImageButton

    // Call screen views
    private lateinit var callOverlay: ConstraintLayout
    private lateinit var callPartnerName: TextView
    private lateinit var callStatus: TextView
    private lateinit var callDuration: TextView
    private lateinit var callTranscript: TextView
    private lateinit var callMuteButton: ImageButton
    private lateinit var endCallButton: ImageButton
    private lateinit var closeCallButton: ImageButton

    private val api = VibeMateApi()
    private val firebaseAuth = FirebaseAuth()
    // Native Google account sheet (Credential Manager); built in onCreate, where
    // the activity context is fully attached.
    private lateinit var googleSignIn: GoogleSignInClient
    private var currentSessionId: String = ""
    // Double-tap guard: lock as soon as Send is pressed, unlock on reply/error.
    // (Even if Enter + the Send button both fire, Gemini gets only one request.)
    private var isSending: Boolean = false
    // AI persona chosen on the "Add new mate" panel:
    // "girlfriend" | "boyfriend" | "crush" | "friend" | "gay_boy" | "gay_girl".
    // (The legacy "gay" id is folded onto "gay_boy" — see selectPersona.)
    private var selectedPersona: String = ""
    // The user-given custom name for this mate (e.g. "Priya"). When empty the
    // persona default name (Aisha/Arjun/Sana) is used.
    private var selectedMateName: String = ""
    // 18+ "Private" chat room (toggled from the Private button in the header).
    private var isPrivateMode: Boolean = false
    // The normal chat session to return to when leaving the Private room.
    private var privateReturnSessionId: String = ""
    // Display name shown above the user's own... (WhatsApp hides "you", so we
    // keep it for any future use and for VibeMate's header fallback).
    private var myDisplayName: String = ""
    // Direct-camera capture: content URI + temp file for the fresh photo.
    private var capturedPhotoUri: Uri? = null
    private var capturedPhotoFile: File? = null
    // A photo the user selected but has NOT sent yet: it shows in the preview
    // bar so the user can type a caption first (empty = nothing pending).
    private var pendingPhotoData: String = ""
    private var pendingPhotoName: String = ""
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    // Call timer
    private var callStartTime: Long = 0
    private var callTimerHandler: Handler = Handler(Looper.getMainLooper())
    private var callTimerRunnable: Runnable? = null
    private var isInCall: Boolean = false
    private var ringPulse: AnimatorSet? = null
    // LIVE VOICE CALL: the mic + phone-voice half of the call (speech-to-text in,
    // text-to-speech out — see LiveCallEngine.kt). Created in onCreate, stopped
    // by endCall() and shut down in onDestroy.
    private var liveCall: LiveCallEngine? = null
    /** One SPOKEN turn is with the AI right now (guards a double turn). */
    private var callTurnBusy: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // Force DARK status/navigation-bar icons. The app is LIGHT-ONLY (see
        // themes.xml), but enableEdgeToEdge()'s default style follows the SYSTEM
        // dark mode: on a dark-mode phone it paints WHITE icons over this app's
        // WHITE window background, so the status bar looked all-white and the
        // network / battery / clock icons vanished. Dark icons stay readable on
        // every light surface here (login gradient, chat header, white composer).
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // WhatsApp-style behaviour: the whole window resizes above the keyboard
        // (activity has adjustResize), and this listener adds the keyboard (IME)
        // height as bottom padding so the composer bar is never hidden behind it.
        // System/navigation bars keep their own padding on top/left/right.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom),
            )
            insets
        }

        authPanel = findViewById(R.id.authPanel)
        authScroll = findViewById(R.id.authScroll)
        chatPanel = findViewById(R.id.chatPanel)
        personaPanel = findViewById(R.id.personaPanel)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        nameInput = findViewById(R.id.nameInput)
        statusText = findViewById(R.id.statusText)
        loginButton = findViewById(R.id.loginButton)
        registerButton = findViewById(R.id.registerButton)
        anonymousButton = findViewById(R.id.anonymousButton)
        googleButton = findViewById(R.id.googleButton)
        personaRelationSpinner = findViewById(R.id.personaRelationSpinner)
        personaGayTypeRow = findViewById(R.id.personaGayTypeRow)
        personaGayTypeSpinner = findViewById(R.id.personaGayTypeSpinner)
        personaStartButton = findViewById(R.id.personaStartButton)
        mateNameInput = findViewById(R.id.mateNameInput)
        // Gay type is wired FIRST on purpose: the relation dropdown's listener
        // reads it to turn the "Gay" row into the exact persona. The relation
        // spinner is wired after it — its listener enables Start Chat.
        setupGayTypeSpinner(personaGayTypeSpinner)
        setupRelationSpinner(personaRelationSpinner)
        chatTitle = findViewById(R.id.chatTitle)
        chatStatus = findViewById(R.id.chatStatus)
        chatAvatar = findViewById(R.id.chatAvatar)
        privateButton = findViewById(R.id.privateButton)
        chatTitleBlock = findViewById(R.id.chatTitleBlock)
        callButton = findViewById(R.id.callButton)
        chatMenuButton = findViewById(R.id.chatMenuButton)
        chatSelectCancel = findViewById(R.id.chatSelectCancel)
        chatSelectTitle = findViewById(R.id.chatSelectTitle)
        chatSelectDelete = findViewById(R.id.chatSelectDelete)
        chatList = findViewById(R.id.chatList)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        attachButton = findViewById(R.id.attachButton)
        cameraButton = findViewById(R.id.cameraButton)
        photoPreviewBar = findViewById(R.id.photoPreviewBar)
        photoPreviewThumb = findViewById(R.id.photoPreviewThumb)
        photoPreviewCancel = findViewById(R.id.photoPreviewCancel)

        // Call overlay (initially hidden)
        callOverlay = findViewById(R.id.callOverlay)
        callPartnerName = findViewById(R.id.callPartnerName)
        callStatus = findViewById(R.id.callStatus)
        callDuration = findViewById(R.id.callDuration)
        callTranscript = findViewById(R.id.callTranscript)
        callMuteButton = findViewById(R.id.callMuteButton)
        endCallButton = findViewById(R.id.endCallButton)
        closeCallButton = findViewById(R.id.closeCallButton)
        callOverlay.visibility = View.INVISIBLE

        // The live voice engine (mic + phone voice) is built once and reused for
        // every call; release() in onDestroy shuts the speech engines down.
        liveCall = LiveCallEngine(this, liveCallListener)

        chatAdapter = ChatAdapter(
            onActionClicked = { onPrivateJoinPromptClicked() },
            onSelectionChanged = { updateChatSelectionHeader() },
        )
        chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        chatList.adapter = chatAdapter

        // WhatsApp-style mates home screen: a full-screen chat list. Tapping a
        // row opens that mate's chat with its full history.
        matesPanel = findViewById(R.id.matesPanel)
        matesList = findViewById(R.id.matesList)
        matesEmpty = findViewById(R.id.matesEmpty)
        matesTitle = findViewById(R.id.matesTitle)
        matesAddButton = findViewById(R.id.matesAddButton)
        matesLogoutButton = findViewById(R.id.matesLogoutButton)
        matesSelectAllButton = findViewById(R.id.matesSelectAllButton)
        matesDeleteButton = findViewById(R.id.matesDeleteButton)
        matesSelectButton = findViewById(R.id.matesSelectButton)
        matesAdapter = MatesAdapter(
            onMateClicked = { session -> openExistingSession(session) },
            onSelectionChanged = { updateMatesHeader() },
        )
        matesList.layoutManager = LinearLayoutManager(this)
        matesList.adapter = matesAdapter

        // Keep the latest message visible: scroll the chat to the bottom
        // whenever the keyboard opens/closes or the layout changes (rotation,
        // multi-window), because the composer moves with the IME.
        chatList.viewTreeObserver.addOnGlobalLayoutListener {
            if (chatPanel.visibility == View.VISIBLE) scrollChatToBottom()
        }

        // Keyboard "send" action behaves like the Send button. The input is
        // multi-line now: the box grows with the text (up to 6 lines) so every
        // typed word stays visible, and Enter inserts a newline (WhatsApp-like).
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendClicked(sendButton)
                true
            } else {
                false
            }
        }

        // Back button behaves like WhatsApp: inside a chat -> back goes to the
        // mates list instead of leaving the app / hitting the login screen.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (matesPanel.visibility == View.VISIBLE && matesAdapter.isSelectionMode()) {
                    // Selection mode: back first exits the selection (WhatsApp).
                    matesAdapter.clearSelection()
                } else if (chatPanel.visibility == View.VISIBLE && chatAdapter.isSelectionMode()) {
                    // Message selection: back first exits the select bar.
                    chatAdapter.clearSelection()
                } else if (chatPanel.visibility == View.VISIBLE && currentSessionId.isNotEmpty()) {
                    currentSessionId = ""
                    chatAdapter.clear()
                    showMatesDialogOrLogin()
                } else if (personaPanel.visibility == View.VISIBLE) {
                    showMatesDialogOrLogin()
                } else {
                    // Root screen (mates list / auth): one back press leaves the app.
                    finish()
                }
            }
        })

        // Google sign-in sheet (Credential Manager): needs the attached activity
        // context, so it is built here instead of in a field initialiser.
        googleSignIn = GoogleSignInClient(this)

        // Auto-login: if a stored session exists, open the mates list straight
        // away (offline-tolerant). Only a genuinely dead session shows login.
        api.init(this)
        if (api.hasStoredSession()) {
            restoreStoredSession {
                api.clearStoredSession()
                setAuthBusy(false, "Session expired. Please log in again.")
            }
        }
    }

    /**
     * WhatsApp-style auto-login with offline tolerance. Never throws the user
     * to the login screen because of a temporary network problem:
     *  1. A stored ID token younger than ~55 minutes opens the app instantly
     *     (token refresh continues quietly in the background).
     *  2. Otherwise an online token refresh runs; on a connectivity failure
     *     the stored session is KEPT and the mates list still opens (chat
     *     lists load once the network is back).
     *  3. Only a hard Firebase rejection (revoked refresh token) calls
     *     onFailHard, which clears the session and shows the login screen.
     */
    private fun restoreStoredSession(onFailHard: () -> Unit) {
        // Step 1: instant offline entry with a fresh stored token.
        val offline = api.useFreshStoredToken()
        if (offline != null) {
            myDisplayName = offline.user.displayName.ifEmpty { "Guest" }
            setAuthBusy(false, "")
            showMatesPanel()
            // Refresh quietly so the next cold start also has a fresh token.
            Thread {
                try {
                    api.restoreSession()
                } catch (ignored: Exception) {
                }
            }.start()
            return
        }
        // Step 2: online refresh.
        setAuthBusy(true, "Restoring your session…")
        Thread {
            try {
                val result = api.restoreSession()
                runOnUiThread {
                    myDisplayName = result.user.displayName.ifEmpty { "Guest" }
                    setAuthBusy(false, "")
                    showMatesPanel()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (api.isConnectivityError(e)) {
                        // No internet right now — keep the stored session and
                        // let the user in; lists retry when the network returns.
                        myDisplayName = api.storedDisplayName().ifEmpty { "Guest" }
                        setAuthBusy(false, "")
                        showMatesPanel()
                    } else {
                        // Firebase rejected the refresh token (revoked/invalid)
                        // -> clean state, login screen.
                        onFailHard()
                    }
                }
            }
        }.start()
    }

    // ===================== XML-declared click handlers =====================

    /** Email login → Firebase → backend session. */
    fun onLoginClicked(view: View) {
        val email = emailInput.getText().toString().trim()
        val password = passwordInput.getText().toString()
        if (email.isEmpty() || password.isEmpty()) {
            statusText.setText("Please enter your email and password.")
            return
        }
        setAuthBusy(true, "Logging in via Firebase…")
        Thread {
            try {
                val fb = firebaseAuth.signInWithEmail(email, password)
                val displayName = fb.displayName.ifEmpty { fb.email.substringBefore("@") }
                val result = api.loginWithFirebase(fb, displayName)
                runOnUiThread {
                    myDisplayName = result.user.displayName.ifEmpty { displayName }
                    setAuthBusy(false, "")
                    showPersonaPanel()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setAuthBusy(false, "Login failed: " + authErrorMessage(e))
                }
            }
        }.start()
    }

    /** Email sign-up → Firebase account + display name → backend session. */
    fun onRegisterClicked(view: View) {
        val email = emailInput.getText().toString().trim()
        val password = passwordInput.getText().toString()
        val name = nameInput.getText().toString().trim()
        if (email.isEmpty() || password.isEmpty()) {
            statusText.setText("Please enter an email and a password (6+ characters).")
            return
        }
        if (password.length < 6) {
            statusText.setText("Password must be at least 6 characters long.")
            return
        }
        setAuthBusy(true, "Creating account…")
        Thread {
            try {
                val fb = firebaseAuth.signUpWithEmail(email, password)
                val displayName = name.ifEmpty { email.substringBefore("@") }
                if (fb.displayName != displayName) {
                    firebaseAuth.updateProfile(fb.idToken, displayName)
                }
                val result = api.loginWithFirebase(fb, displayName)
                runOnUiThread {
                    myDisplayName = result.user.displayName.ifEmpty { displayName }
                    setAuthBusy(false, "")
                    showPersonaPanel()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setAuthBusy(false, "Registration failed: " + authErrorMessage(e))
                }
            }
        }.start()
    }

    /** Anonymous login: instant guest access, no email/password needed. */
    fun onAnonymousClicked(view: View) {
        setAuthBusy(true, "Signing in as guest…")
        // Reuse any stored session first: creating a fresh anonymous account
        // would point to a new uid and make every previous chat look deleted.
        if (api.hasStoredSession()) {
            restoreStoredSession(
                onFailHard = {
                    // Stored session genuinely dead (revoked) -> wipe it, then
                    // create a brand-new guest account.
                    api.clearStoredSession()
                    Thread { createNewGuest() }.start()
                },
            )
            return
        }
        Thread { createNewGuest() }.start()
    }

    /** Create a brand-new anonymous account (old stored session already gone). */
    private fun createNewGuest() {
        try {
            val fb = firebaseAuth.signInAnonymously()
            val displayName = "Guest"
            if (fb.displayName != displayName) {
                firebaseAuth.updateProfile(fb.idToken, displayName)
            }
            val result = api.loginWithFirebase(fb, displayName)
            runOnUiThread {
                myDisplayName = result.user.displayName.ifEmpty { displayName }
                setAuthBusy(false, "")
                showPersonaPanel()
            }
        } catch (e: Exception) {
            runOnUiThread {
                setAuthBusy(false, "Guest login failed: " + authErrorMessage(e))
            }
        }
    }

    /**
     * Google (Gmail) login: Credential Manager shows the phone's own Google
     * accounts (no browser, no password), and the ID token of the tapped account
     * is exchanged for a Firebase session — the whole sign-in happens on device.
     */
    fun onGoogleClicked(view: View) {
        setAuthBusy(true, "Opening Google sign-in…")
        googleSignIn.signIn(
            onIdToken = { idToken ->
                setAuthBusy(true, "Signing in to VibeMate…")
                Thread {
                    try {
                        val fb = firebaseAuth.signInWithGoogleIdToken(idToken)
                        val displayName = fb.displayName.ifEmpty {
                            fb.email.ifEmpty { "Google User" }.substringBefore("@")
                        }
                        val result = api.loginWithFirebase(fb, displayName)
                        runOnUiThread {
                            myDisplayName = result.user.displayName.ifEmpty { displayName }
                            setAuthBusy(false, "")
                            showPersonaPanel()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            setAuthBusy(false, "Google sign-in failed: " + authErrorMessage(e))
                        }
                    }
                }.start()
            },
            onCancelled = {
                // Sheet dismissed without picking an account: not an error.
                setAuthBusy(false, "")
            },
            onFailed = { reason ->
                setAuthBusy(false, "Google sign-in failed: $reason")
            },
        )
    }

    fun onSendClicked(view: View) {
        // Double-message fix: block sending again until the reply arrives.
        if (isSending) return
        val text = messageInput.getText().toString().trim()
        // IMAGE GENERATION IS OFF (AppConfig.IMAGE_GEN_ENABLED = false), so no AI
        // picture is ever created: "/pic <kuch bhi>" and natural-language photo
        // requests are NOT intercepted any more. They simply go through the normal
        // chat, where the persona answers honestly that it cannot send pics right
        // now (PersonaPrompts.photoRule) — instead of the old behaviour of
        // promising a picture that never arrived. Set the flag back to true to
        // re-enable both branches below.
        // "/pic <kaisa photo>", "/image ..." or "/img ..." used to generate an AI
        // image instead of a normal chat reply (18+ pages behind the age gate too).
        val genPrompt = if (AppConfig.IMAGE_GEN_ENABLED) imageGenPromptIfAny(text) else ""
        if (genPrompt.isNotEmpty()) {
            if (!isAgeVerified()) {
                showAgeVerification()
                return
            }
            messageInput.setText("")
            generateAiImage(genPrompt)
            return
        }
        // A selected (but not yet sent) photo: Send submits the photo together
        // with whatever caption the user typed (even an empty one).
        if (pendingPhotoData.isNotEmpty()) {
            messageInput.setText("")
            sendAttachmentMessage("IMAGE", pendingPhotoData, pendingPhotoName, text)
            return
        }
        // Natural-language photo requests ("photo bhejo", "selfie dikhao",
        // "apni pic send karo") used to generate an image instead of a text reply.
        // OFF with IMAGE_GEN_ENABLED: the text now reaches the model, which says
        // honestly (in character) that it cannot send pics right now, so the AI
        // never just makes excuses or leaves the user waiting for a picture.
        val photoPrompt = if (AppConfig.IMAGE_GEN_ENABLED) photoRequestPromptIfAny(text) else ""
        if (photoPrompt.isNotEmpty()) {
            if (!isAgeVerified()) {
                showAgeVerification()
                return
            }
            messageInput.setText("")
            generateAiImage(photoPrompt)
            return
        }
        if (text.isEmpty() || currentSessionId.isEmpty()) return

        isSending = true
        messageInput.setText("")
        // WhatsApp flow: own message first shows a clock (sending), then a
        // single tick (sent), double grey ticks (delivered) when the backend
        // persists it, and blue double ticks (read) when VibeMate replies.
        // We track the row by its ChatMessage object (not adapter index),
        // because rows added later shift indices around.
        val userRow = ChatMessage(ChatMessage.Sender.USER, myDisplayName, text, nowLabel())
        chatAdapter.add(userRow)
        scrollChatToBottom()
        // WhatsApp-style "typing…" bubble on VibeMate's side while the reply
        // is being generated (Gemini can take several seconds).
        val typingRow = ChatMessage(ChatMessage.Sender.TYPING, chatPartnerName(), "...", nowLabel())
        // Show "typing…" in the WhatsApp header while the AI reply is generated.
        chatAdapter.add(typingRow)
        scrollChatToBottom()
        sendButton.setEnabled(false)
        setChatStatusTyping(true)
        val sessionId = currentSessionId

        Thread {
            try {
                runOnUiThread { chatAdapter.updateStatus(userRow, ChatMessage.Status.SENT) }
                // DELIVERED (double grey ticks) as soon as the backend accepts
                // the message — before the AI starts replying. Do NOT wait for
                // the reply here; the READ state is set separately below.
                runOnUiThread { chatAdapter.updateStatus(userRow, ChatMessage.Status.DELIVERED) }
                val reply = if (isPrivateMode) api.sendPrivateMessage(sessionId, text) else api.sendMessage(sessionId, text)
                userRow.id = reply.userMessage.id
                runOnUiThread {
                    if (isPrivateMode) {
                        // Private room: 2-3 tiny bubbles with a short typing pause
                        // between them (one full bubble when it is an info answer).
                        postVibeMateReplies(replyBubbles(reply), typingRow, userRow)
                    } else {
                        val replyText = reply.assistantMessage.content.trim()
                        setChatStatusTyping(false)
                        chatAdapter.remove(typingRow)
                        val row = ChatMessage(
                            ChatMessage.Sender.VIBEMATE,
                            chatPartnerName(),
                            // Never render an empty bubble: if the backend/AI sent
                            // back no text, say so explicitly instead of showing a
                            // blank white box with just a timestamp.
                            if (replyText.isEmpty()) {
                                "Sorry, I could not generate a reply. Please try again."
                            } else {
                                replyText
                            },
                            nowLabel(),
                        )
                        row.id = reply.assistantMessage.id
                        chatAdapter.add(row)
                        scrollChatToBottom()
                        chatAdapter.updateStatus(userRow, ChatMessage.Status.READ)
                        sendButton.setEnabled(true)
                        isSending = false
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    // Drop the typing bubble, mark the message failed (red "!")
                    // and surface the reason in a system row.
                    setChatStatusTyping(false)
                    chatAdapter.remove(typingRow)
                    // Hide the duplicate-guard error from the user (it was a double tap).
                    val msg = errorMessage(e)
                    if (msg.contains("already being sent")) {
                        chatAdapter.remove(userRow)
                        sendButton.setEnabled(true)
                        isSending = false
                        return@runOnUiThread
                    }
                    // Explicit (18+) content in NORMAL chat: Gemini blocks it with
                    // a SAFETY finish and no text. Instead of dumping the raw error
                    // ("Could not send: Unexpected Gemini response: {...}"), invite
                    // the user into the Private room with a one-tap Join button.
                    if (!isPrivateMode && api.isExplicitContentBlock(msg)) {
                        chatAdapter.updateStatus(userRow, ChatMessage.Status.DELIVERED)
                        addPrivateJoinPrompt()
                        sendButton.setEnabled(true)
                        isSending = false
                        return@runOnUiThread
                    }
                    chatAdapter.updateStatus(userRow, ChatMessage.Status.FAILED)
                    addChat(
                        ChatMessage.Sender.SYSTEM,
                        "",
                        "Could not send: " + msg,
                    )
                    sendButton.setEnabled(true)
                    isSending = false
                }
            }
        }.start()
    }

    // ===================== Attachment Feature (WhatsApp-style) =====================

    /** Attachment button clicked - shows menu to pick audio/video/document */
    fun onAttachClicked(view: View) {
        if (currentSessionId.isEmpty()) {
            showToast("Please start a chat first")
            return
        }

        val menu = PopupMenu(this, view)
        menu.menu.add(0, ATTACH_PHOTO, 0, "Photo")
        menu.menu.add(0, ATTACH_AUDIO, 1, "Audio")
        menu.menu.add(0, ATTACH_VIDEO, 2, "Video")
        menu.menu.add(0, ATTACH_DOCUMENT, 3, "Document")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ATTACH_PHOTO -> { pickPhoto(); true }
                ATTACH_AUDIO -> { pickAudio(); true }
                ATTACH_VIDEO -> { pickVideo(); true }
                ATTACH_DOCUMENT -> { pickDocument(); true }
                else -> false
            }
        }
        menu.show()
    }

    private fun pickPhoto() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQUEST_PICK_PHOTO)
        } catch (e: Exception) {
            showToast("No photo picker available")
        }
    }

    /**
     * Direct camera capture: opens the system camera app. The photo is written
     * to a private cache file (via FileProvider) so the full-resolution image
     * comes back, then it is compressed and sent exactly like a gallery photo.
     */
    fun onCameraButtonClicked(view: View) {
        if (currentSessionId.isEmpty()) {
            showToast("Please start a chat first")
            return
        }
        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (captureIntent.resolveActivity(packageManager) == null) {
            showToast("No camera app found on this device")
            return
        }
        try {
            val dir = File(cacheDir, "captured")
            dir.mkdirs()
            val photoFile = File(dir, "vm_" + System.currentTimeMillis() + ".jpg")
            val photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
            capturedPhotoFile = photoFile
            capturedPhotoUri = photoUri
            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            startActivityForResult(captureIntent, REQUEST_CAPTURE_PHOTO)
        } catch (e: Exception) {
            showToast("Could not open camera: " + errorMessage(e))
        }
    }

    private fun pickAudio() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQUEST_PICK_AUDIO)
        } catch (e: Exception) {
            showToast("No audio picker available")
        }
    }

    private fun pickVideo() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQUEST_PICK_VIDEO)
        } catch (e: Exception) {
            showToast("No video picker available")
        }
    }

    private fun pickDocument() {
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf", "application/msword",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/plain", "application/zip"
                ))
            }, REQUEST_PICK_DOCUMENT)
        } catch (e: Exception) {
            showToast("No document picker available")
        }
    }

    fun onNewChatClicked(view: View) = showPersonaPanel()

    /**
     * WhatsApp-style 3-dot menu: Profile, Add new mate, My mates, Clear chat, Log out.
     */
    fun onMenuButtonClicked(view: View) {
        val menu = PopupMenu(this, view)
        menu.menu.add(0, MENU_PROFILE, 0, "Profile")
        menu.menu.add(0, MENU_NEW_CHAT, 1, "Add new relationship")
        menu.menu.add(0, MENU_MY_MATES, 2, "My chats")
        menu.menu.add(0, MENU_CLEAR_CHAT, 3, "Clear chat")
        menu.menu.add(0, MENU_DELETE_CHAT, 4, "Delete chat")
        menu.menu.add(0, MENU_LOGOUT, 5, "Log out")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PROFILE -> {
                    showProfileDialog()
                    true
                }
                MENU_NEW_CHAT -> {
                    showPersonaPanel()
                    true
                }
                MENU_MY_MATES -> {
                    showMatesPanel()
                    true
                }
                MENU_CLEAR_CHAT -> {
                    showClearChatConfirm()
                    true
                }
                MENU_DELETE_CHAT -> {
                    showDeleteChatConfirm()
                    true
                }
                MENU_LOGOUT -> {
                    onLogoutClicked(view)
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    /**
     * Clear chat: confirm, then remove all bubbles from the screen and show a
     * fresh greeting. Server history is also cleared in the background (if it
     * fails the screen is still clean).
     */
    private fun showClearChatConfirm() {
        if (currentSessionId.isEmpty()) {
            showToast("No chat is open")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear chat?")
            .setMessage("All messages will be removed from the screen. Continue?")
            .setPositiveButton("Clear") { _, _ -> clearCurrentChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * "Delete chat" menu: remove the whole session (chat + full history) and
     * go back to the mates list. Unlike "Clear chat", the chat itself is gone.
     */
    private fun showDeleteChatConfirm() {
        if (currentSessionId.isEmpty()) {
            showToast("No chat is open")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete chat?")
            .setMessage("This chat and its full history will be removed. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteCurrentChat() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrentChat() {
        val sessionId = currentSessionId
        if (sessionId.isEmpty()) return
        sendButton.setEnabled(false)
        setChatStatusTyping(true)
        Thread {
            var error: String? = null
            try {
                api.deleteSession(sessionId)
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            runOnUiThread {
                setChatStatusTyping(false)
                currentSessionId = ""
                chatAdapter.clear()
                if (error != null) {
                    showToast("Could not delete: " + error)
                } else {
                    showToast("Chat deleted")
                }
                chatPanel.setVisibility(View.INVISIBLE)
                showMatesPanel()
            }
        }.start()
    }

    private fun clearCurrentChat() {
        val sessionId = currentSessionId
        val persona = selectedPersona
        chatAdapter.clear()
        setChatStatusTyping(false)
        addChat(
            ChatMessage.Sender.VIBEMATE,
            personaGreeting(persona),
            personaClearGreeting(persona),
        )
        showToast("Chat cleared")
        // Also delete the old server-side messages (in the background).
        Thread {
            try {
                api.clearSessionMessages(sessionId)
            } catch (e: Exception) {
                // The screen is already clean — ignore server errors.
            }
        }.start()
    }

    /** Persona-style greeting after clearing chat (keeps the relationship). */
    private fun personaClearGreeting(persona: String): String {
        return when (PersonaPrompts.normalizePersona(persona)) {
            "girlfriend" -> "Chat cleared, love ❤️ let's start fresh. Tell me, what's on your mind?"
            "boyfriend" -> "Chat cleared 😎 fresh start! So, how are you doing?"
            "crush" -> "Chat cleared 🙈 let's talk in a whole new way. Tell me more?"
            "friend" -> "Chat cleared 😎 fresh start, yaar! So, kya chal raha hai?"
            // Both gay personas are same-sex couples, so the same welcome fits.
            PersonaPrompts.GAY_BOY, PersonaPrompts.GAY_GIRL ->
                "Chat cleared 🏳️‍🌈 fresh start, jaan! So, how are you feeling today?"
            else -> "Chat cleared. Start a new conversation!"
        }
    }

    /**
     * Edit display name dialog. Saves to Firebase Auth profile (REST) and the
     * Firestore user doc, so it sticks across restarts.
     */
    private fun showProfileDialog() {
        val input = AppCompatEditText(this)
        input.setText(myDisplayName)
        input.hint = "Your name"
        input.setSingleLine(true)
        val padding = (20 * resources.displayMetrics.density + 0.5f).toInt()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit profile")
            .setMessage("Display name shown on your messages")
            .setView(input, padding, padding / 2, padding, 0)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    input.error = "Name cannot be empty"
                    return@setOnClickListener
                }
                save.isEnabled = false
                Thread {
                    try {
                        val token = currentFirebaseToken()
                        if (token.isNotEmpty()) {
                            firebaseAuth.updateProfile(token, name)
                        }
                        api.updateDisplayName(name)
                        runOnUiThread {
                            myDisplayName = name
                            dialog.dismiss()
                            showToast("Profile updated")
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            save.isEnabled = true
                            input.error = errorMessage(e)
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun currentFirebaseToken(): String = api.currentToken()

    /**
     * WhatsApp-style chat-list home screen: shows all of the user's chats.
     * Tapping a chat opens that session with its full history.
     */
    private fun showMatesPanel() {
        authPanel.setVisibility(View.INVISIBLE)
        personaPanel.setVisibility(View.INVISIBLE)
        chatPanel.setVisibility(View.INVISIBLE)
        matesPanel.setVisibility(View.VISIBLE)
        // A hidden message box must not keep the caret (see clearInputFocus).
        clearInputFocus()
        matesEmpty.setVisibility(View.GONE)
        Thread {
            var sessions: List<SessionDto> = emptyList()
            var error: String? = null
            try {
                // Private (18+) rooms are NOT contacts: they never show in the
                // mate list and are reachable only via the "Private" button.
                sessions = api.listSessions().filter { !it.privateMode }
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            runOnUiThread {
                try {
                    if (error != null) {
                        matesAdapter.submit(emptyList())
                        matesEmpty.setText("Could not load your chats:\n" + error)
                        matesEmpty.setVisibility(View.VISIBLE)
                        return@runOnUiThread
                    }
                    if (sessions.isEmpty()) {
                        matesAdapter.submit(emptyList())
                        matesEmpty.setText(
                            "No chats yet.\nTap + to start a new relationship!"
                        )
                        matesEmpty.setVisibility(View.VISIBLE)
                        return@runOnUiThread
                    }
                    matesAdapter.submit(sessions)
                } catch (e: Exception) {
                    matesEmpty.setText("Could not show the list: " + errorMessage(e))
                    matesEmpty.setVisibility(View.VISIBLE)
                }
            }
        }.start()
    }

    /** Plus button on the mates home screen: open the "Add new mate" panel. */
    fun onAddMateClicked(view: View) {
        if (!api.isLoggedIn() && !api.hasStoredSession()) return
        showPersonaPanel()
    }

    /** Close (X) on the "Add a relationship" panel: back to the mates list. */
    fun onPersonaCloseClicked(view: View) {
        showMatesPanel()
    }

    /**
     * WhatsApp-style selection header: while rows are selected the title shows
     * "N selected" and the All / Delete actions replace the + / logout icons.
     */
    private fun updateMatesHeader() {
        if (matesAdapter.isSelectionMode()) {
            val n = matesAdapter.selectedCount()
            matesTitle.setText(if (n == 0) "Select chats" else "$n selected")
            matesAddButton.setVisibility(View.GONE)
            matesLogoutButton.setVisibility(View.GONE)
            matesSelectButton.setVisibility(View.GONE)
            matesSelectAllButton.setVisibility(View.VISIBLE)
            matesDeleteButton.setVisibility(View.VISIBLE)
        } else {
            matesTitle.setText("VibeMate")
            matesAddButton.setVisibility(View.VISIBLE)
            matesLogoutButton.setVisibility(View.VISIBLE)
            matesSelectButton.setVisibility(View.VISIBLE)
            matesSelectAllButton.setVisibility(View.GONE)
            matesDeleteButton.setVisibility(View.GONE)
        }
    }

    /** "All" in selection mode: select every chat in the list. */
    fun onSelectAllClicked(view: View) {
        matesAdapter.selectAll()
    }

    /** "Select" in the mates header: enter selection mode without a long-press. */
    fun onSelectChatsClicked(view: View) {
        matesAdapter.enterSelectionMode()
    }

    // ===================== In-chat message selection (select + delete) =========

    /** Switch the chat header to the select bar ("N selected" + Delete + X). */
    private fun updateChatSelectionHeader() {
        val selecting = chatAdapter.isSelectionMode()
        chatAvatar.visibility = if (selecting) View.GONE else View.VISIBLE
        chatTitleBlock.visibility = if (selecting) View.GONE else View.VISIBLE
        privateButton.visibility = if (selecting) View.GONE else View.VISIBLE
        callButton.visibility = if (selecting) View.GONE else View.VISIBLE
        chatMenuButton.visibility = if (selecting) View.GONE else View.VISIBLE
        chatSelectCancel.visibility = if (selecting) View.VISIBLE else View.GONE
        chatSelectTitle.visibility = if (selecting) View.VISIBLE else View.GONE
        chatSelectDelete.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) {
            chatSelectTitle.setText(chatAdapter.selectedCount().toString() + " selected")
        }
    }

    /** X on the chat select bar: leave selection without deleting. */
    fun onChatSelectCancelClicked(view: View) {
        chatAdapter.clearSelection()
    }

    /** Delete on the chat select bar: confirm, then delete the selected bubbles. */
    fun onChatSelectDeleteClicked(view: View) {
        val messages = chatAdapter.selectedMessages()
        if (messages.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(
                if (messages.size == 1) "Delete this message?"
                else "Delete " + messages.size + " messages?"
            )
            .setMessage("This will be removed from your chat.")
            .setPositiveButton("Delete") { _, _ -> deleteSelectedMessages(messages) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Soft-delete the selected messages (server flag) and drop them from the UI. */
    private fun deleteSelectedMessages(messages: List<ChatMessage>) {
        val ids = messages.map { it.id }.filter { it.isNotEmpty() }
        Thread {
            var failed = 0
            for (id in ids) {
                try { api.deleteMessage(currentSessionId, id) } catch (e: Exception) { failed++ }
            }
            runOnUiThread {
                chatAdapter.removeSelected()
                if (failed > 0) showToast("Some messages could not be deleted.")
                else if (ids.isNotEmpty()) showToast("Message(s) deleted")
            }
        }.start()
    }

    /** "Delete" in selection mode: confirm, then remove the selected chats. */
    fun onDeleteSelectedClicked(view: View) {
        val ids = matesAdapter.selectedSessionIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(
                if (ids.size == 1) "Delete 1 chat?" else "Delete " + ids.size + " chats?"
            )
            .setMessage("The chat(s) and their full history will be removed. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val toDelete = ids
                Thread {
                    var failed = 0
                    for (id in toDelete) {
                        try {
                            api.deleteSession(id)
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                    if (toDelete.contains(currentSessionId)) {
                        currentSessionId = ""
                    }
                    runOnUiThread {
                        matesAdapter.clearSelection()
                        if (failed >= toDelete.size) {
                            showToast("Could not delete. Check your internet connection.")
                        } else {
                            showToast(
                                if (failed == 0) "Chat(s) deleted"
                                else (toDelete.size - failed).toString() + " deleted, " + failed + " failed"
                            )
                        }
                        showMatesPanel()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Readable label for a persona id (mate list subtitle). */
    private fun personaTypeLabel(persona: String): String {
        return when (PersonaPrompts.normalizePersona(persona)) {
            "girlfriend" -> "Girlfriend"
            "boyfriend" -> "Boyfriend"
            "crush" -> "Crush"
            "friend" -> "Friend"
            PersonaPrompts.GAY_BOY -> "Boy Gay"
            PersonaPrompts.GAY_GIRL -> "Girl Gay"
            else -> "Mate"
        }
    }

    /**
     * A session was tapped in the mate list: restore its persona + custom name,
     * set up the header and render the full history from the server as bubbles.
     */
    private fun openExistingSession(session: SessionDto, onLoaded: () -> Unit = {}) {
        // Also mirrors the relation into the dropdowns (a legacy "gay" session
        // shows up as Boy Gay), so "Add new relationship" / the login screen show
        // the relation of this chat.
        selectPersona(session.persona)
        selectedMateName = session.mateName
        isPrivateMode = session.privateMode
        updatePrivateButtonUi()
        currentSessionId = session.id
        chatAdapter.clear()
        authPanel.setVisibility(View.INVISIBLE)
        personaPanel.setVisibility(View.INVISIBLE)
        matesPanel.setVisibility(View.INVISIBLE)
        chatPanel.setVisibility(View.VISIBLE)
        focusMessageInput()
        refreshChatHeader(selectedPersona)
        setChatStatusTyping(true)
        sendButton.setEnabled(false)
        Thread {
            var messages: List<MessageDto> = emptyList()
            var error: String? = null
            try {
                messages = api.listMessages(session.id)
            } catch (e: Exception) {
                error = errorMessage(e)
            }
            runOnUiThread {
                setChatStatusTyping(false)
                if (error != null) {
                    addChat(
                        ChatMessage.Sender.SYSTEM,
                        "",
                        "Could not load history: $error",
                    )
                } else {
                    for (m in messages) {
                        val attach =
                            if (m.type == "IMAGE" || m.type == "AUDIO" || m.type == "DOC") m.type else ""
                        if (m.role == "user") {
                            chatAdapter.add(chatMessageFromDto(m, ChatMessage.Sender.USER, myDisplayName, attach))
                        } else if (m.role == "model" || m.role == "assistant") {
                            chatAdapter.add(chatMessageFromDto(m, ChatMessage.Sender.VIBEMATE, chatPartnerName(), attach))
                        }
                    }
                }
                if (messages.isEmpty() && error == null) {
                    addChat(
                        ChatMessage.Sender.VIBEMATE,
                        chatPartnerName(),
                        "Hi! Great to see you again 😊 what would you like to talk about?",
                    )
                }
                scrollChatToBottom()
                sendButton.setEnabled(true)
                onLoaded()
            }
        }.start()
    }

    /** Convert an ISO timestamp into a short chat bubble time label (safe). */
    private fun shortTime(iso: String): String {
        return try {
            val instant = java.time.Instant.parse(iso)
            timeFormat.format(java.util.Date.from(instant))
        } catch (e: Exception) {
            nowLabel()
        }
    }

    /** History row (text or attachment) from a stored MessageDto. */
    private fun chatMessageFromDto(
        m: MessageDto,
        sender: ChatMessage.Sender,
        displayName: String,
        attachType: String,
    ): ChatMessage {
        val row = if (attachType.isNotEmpty() && m.data.isNotEmpty()) {
            ChatMessage(
                sender,
                displayName,
                m.content,
                shortTime(m.createdAt),
                attachType,
                m.data,
                m.fileName,
            )
        } else {
            ChatMessage(sender, displayName, m.content, shortTime(m.createdAt))
        }
        row.id = m.id
        return row
    }

    /**
     * Fills the "Gay type" row (Boy Gay / Girl Gay) of the "Add a relationship"
     * panel. The row is only VISIBLE while the relation is "Gay": switching it
     * re-picks the persona on the fly, and every other relation hides it (see
     * selectPersona).
     */
    private fun setupGayTypeSpinner(spinner: Spinner) {
        val adapter = ArrayAdapter(
            this,
            R.layout.item_relation_spinner,
            GAY_TYPE_OPTIONS.map { it.first },
        )
        adapter.setDropDownViewResource(R.layout.item_relation_spinner_dropdown)
        spinner.adapter = adapter
        spinner.setSelection(0) // "Boy Gay"
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                // Only an ACTIVE gay relation follows this switch; for every other
                // relation the row is hidden anyway. Re-selecting the persona that
                // is already active is a no-op (see selectPersona), so this can
                // never loop with syncGayTypeSpinner().
                if (PersonaPrompts.isGayPersona(selectedPersona)) {
                    selectPersona(gayTypePersona())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Fills the relation dropdown of the "Add a relationship" panel: every
     * relationship the app supports (Girlfriend / Boyfriend / Crush / Friend /
     * Gay) sits in this ONE spinner, with "Select" as the first row acting as the hint.
     * "Gay" is a same-sex couple, so which couple it is comes from the Gay type
     * row — see [personaOrGayType].
     */
    private fun setupRelationSpinner(spinner: Spinner) {
        // Custom row layouts keep the dropdown text centred inside its box and
        // readable (see item_relation_spinner*.xml).
        val adapter = ArrayAdapter(
            this,
            R.layout.item_relation_spinner,
            RELATION_OPTIONS.map { it.first },
        )
        adapter.setDropDownViewResource(R.layout.item_relation_spinner_dropdown)
        spinner.adapter = adapter
        spinner.setSelection(0) // "Select"
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                // "Select" (position 0) clears the relationship again. The "Gay"
                // row only carries the umbrella id, so it is resolved through the
                // Gay type row into the exact persona (gay_boy / gay_girl).
                selectPersona(
                    personaOrGayType(
                        RELATION_OPTIONS.getOrElse(position) { "Select" to "" }.second,
                    ),
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** The persona id of the "Gay type" row that is selected right now
     *  (gay_boy / gay_girl). Falls back to "Boy Gay", the default row. */
    private fun gayTypePersona(): String {
        val position = personaGayTypeSpinner.selectedItemPosition
        return GAY_TYPE_OPTIONS.getOrElse(position) { GAY_TYPE_OPTIONS.first() }.second
    }

    /** Turns the "Gay" umbrella id into the exact gay persona from the Gay type
     *  row; every other relation id is already an exact persona. */
    private fun personaOrGayType(relation: String): String =
        if (relation == GAY_RELATION) gayTypePersona() else relation

    /** Relation row that stands for [persona]: both gay personas sit under the
     *  ONE "Gay" row ("gay_boy" / "gay_girl" are not rows of their own). */
    private fun relationForPersona(persona: String): String =
        if (PersonaPrompts.isGayPersona(persona)) GAY_RELATION else persona

    /** Moves one relation dropdown onto [persona] ("" = back to "Select"). */
    private fun syncRelationSpinner(spinner: Spinner, persona: String) {
        val position = RELATION_OPTIONS.indexOfFirst { it.second == persona }
        if (position != -1 && spinner.selectedItemPosition != position) {
            spinner.setSelection(position)
        }
    }

    /** Moves the Gay type dropdown onto [persona] (gay_boy / gay_girl only). */
    private fun syncGayTypeSpinner(persona: String) {
        val position = GAY_TYPE_OPTIONS.indexOfFirst { it.second == persona }
        if (position != -1 && personaGayTypeSpinner.selectedItemPosition != position) {
            personaGayTypeSpinner.setSelection(position)
        }
    }

    /**
     * Remembers the chosen relationship, mirrors it in the relation dropdown,
     * shows/updates the Gay type row and enables Start Chat.
     *
     * [persona] is either an exact persona id ("girlfriend" | "boyfriend" |
     * "crush" | "friend" | "gay_boy" | "gay_girl") or the legacy "gay" id kept by older
     * sessions, which [PersonaPrompts.normalizePersona] folds onto "gay_boy".
     * Empty = no relationship at all (Start Chat off).
     */
    private fun selectPersona(persona: String) {
        val exact = PersonaPrompts.normalizePersona(persona)
        selectedPersona = exact
        syncRelationSpinner(personaRelationSpinner, relationForPersona(exact))
        syncGayTypeSpinner(exact)
        // The Gay type row only makes sense while a gay relation is selected.
        personaGayTypeRow.visibility =
            if (PersonaPrompts.isGayPersona(exact)) View.VISIBLE else View.GONE
        personaStartButton.setEnabled(exact.isNotEmpty())
    }

    fun onStartChatClicked(view: View) {
        if (selectedPersona.isEmpty()) return
        // The mate's custom name (leave empty to use the persona default name).
        selectedMateName = mateNameInput.text?.toString()?.trim().orEmpty()
        showChatPanel()
    }

    /**
     * "Log out" button (mates header) and menu item. NEVER logs out straight away:
     * it asks for confirmation first, so a stray tap cannot wipe the session.
     */
    fun onLogoutClicked(view: View) {
        showLogoutConfirm()
    }

    /** Confirmation before logging out (the session is cleared for real). */
    private fun showLogoutConfirm() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("You will be signed out and will need to log in again. Continue?")
            .setPositiveButton("Log out") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Clears the session and returns to the auth panel (after confirmation). */
    private fun performLogout() {
        // End call if active
        if (isInCall) {
            endCall()
        }
        api.logout()
        currentSessionId = ""
        myDisplayName = ""
        selectedMateName = ""
        mateNameInput.setText("")
        // Relation dropdown back to its "Select" hint + Start Chat off.
        selectPersona("")
        chatPanel.setVisibility(View.INVISIBLE)
        personaPanel.setVisibility(View.INVISIBLE)
        matesPanel.setVisibility(View.INVISIBLE)
        chatAdapter.clear()
        messageInput.setText("")
        authPanel.setVisibility(View.VISIBLE)
        clearInputFocus()
    }

    // ===================== Call Feature =====================

    // ===================== Private Mode (18+) =====================

    /**
     * Private button (next to the call button in the chat header):
     *  - from a normal chat it redirects into the ONE dedicated Private room
     *    (never copies/recreates the contact),
     *  - from the Private room the same button says "Normal" and returns to the
     *    normal chat you came from.
     * First use asks for a valid birth date >= 18.
     */
    fun onPrivateButtonClicked(view: View) {
        if (currentSessionId.isEmpty()) {
            showToast("Please start a chat first")
            return
        }
        if (isPrivateMode) {
            exitPrivateMode()
            return
        }
        if (!isAgeVerified()) {
            showAgeVerification()
            return
        }
        enterPrivateMode()
    }

    /**
     * Redirects into the user's dedicated Private room for this persona.
     * The room is created exactly once and reused — entering again never makes
     * a duplicate contact.
     */
    private fun enterPrivateMode() {
        val persona = selectedPersona
        val mateName = selectedMateName
        if (currentSessionId.isEmpty()) return
        // Remember where "Normal" should take the user back to.
        privateReturnSessionId = currentSessionId
        sendButton.setEnabled(false)
        Thread {
            try {
                // The Private room is per-contact: reuse it when the SAME contact
                // re-enters (their chat must NOT be wiped), otherwise clear every
                // previous private conversation first and open a fresh room named
                // after this contact.
                val room = api.findPrivateSession(persona, mateName)
                    ?: run {
                        api.deletePrivateSessions()
                        api.createSession(
                            mateName.ifEmpty { PersonaPrompts.defaultName(persona) },
                            persona,
                            mateName,
                            true,
                        )
                    }
                // Pre-flight (local, no network): confirm the in-app 18+ engine
                // is configured before opening the room.
                val health = api.checkPrivateEngine()
                runOnUiThread {
                    isPrivateMode = true
                    updatePrivateButtonUi()
                    // Private-room welcome line. ENGLISH-ONLY on purpose: the user
                    // must never be shown a Hindi/Hinglish system message here. The
                    // old photo tip ("photo bhejo, main dekh kar bataungi") is gone.
                    openExistingSession(room) {
                        addChat(
                            ChatMessage.Sender.SYSTEM,
                            "",
                            "🔒 Private room (18+). Tap “Normal” in the header to go back.\n" +
                            // The header call button works HERE too, and this is
                            // the ADULT call: same 18+ engine as the private chat,
                            // spoken aloud (see onCallSpeechHeard / openLiveVoice).
                            "📞 Tap the Call button to talk on the phone in this 18+ mood.",
                        )
                        // Show the health warning AFTER the history is rendered
                        // (openExistingSession clears the adapter first).
                        val warning = privateEngineWarning(health)
                        if (warning.isNotEmpty()) {
                            addChat(ChatMessage.Sender.SYSTEM, "", warning)
                        }
                    }
                    sendButton.setEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isPrivateMode = false
                    privateReturnSessionId = ""
                    updatePrivateButtonUi()
                    addChat(
                        ChatMessage.Sender.SYSTEM,
                        "",
                        "Could not open the Private room: " + errorMessage(e),
                    )
                    sendButton.setEnabled(true)
                }
            }
        }.start()
    }

    /** Leaves the Private room and redirects back to the normal chat room. */
    private fun exitPrivateMode() {
        val returnTo = privateReturnSessionId
        isPrivateMode = false
        privateReturnSessionId = ""
        updatePrivateButtonUi()
        updatePrivateStatusText()
        if (returnTo.isEmpty()) {
            chatAdapter.clear()
            showMatesDialogOrLogin()
            return
        }
        sendButton.setEnabled(false)
        Thread {
            val target = try {
                api.listSessions().firstOrNull { it.id == returnTo }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (target != null) {
                    openExistingSession(target)
                } else {
                    chatAdapter.clear()
                    showMatesDialogOrLogin()
                    sendButton.setEnabled(true)
                }
            }
        }.start()
    }

    private fun updatePrivateButtonUi() {
        if (isPrivateMode) {
            privateButton.setText("Normal")
            privateButton.setBackgroundResource(R.drawable.bg_private_button_on)
        } else {
            privateButton.setText("Private")
            privateButton.setBackgroundResource(R.drawable.bg_private_button)
        }
    }

    /** Small " • Private" badge appended to the online/typing header label. */
    private fun statusSuffix(): String = if (isPrivateMode) " • Private" else ""

    private fun updatePrivateStatusText() {
        val current = chatStatus.text.toString()
        chatStatus.setText(current.removeSuffix(" • Private") + statusSuffix())
    }

    /**
     * Warning line shown in the Private room when the in-app 18+ engine is not
     * fully configured. The engine runs INSIDE the app now (the provider is
     * called directly, exactly like normal chat calls Gemini), so the only thing
     * that can be wrong is a missing/invalid API key — nothing can be "offline".
     * Returns an empty string when everything is healthy.
     */
    private fun privateEngineWarning(health: PrivateEngineHealth): String {
        // Name the provider the app is ACTUALLY pointed at (see AppConfig.
        // OPENROUTER_BASE_URL) so the hint sends the user to the right console.
        val venice = AppConfig.OPENROUTER_BASE_URL.contains("venice.ai")
        val keyName = if (venice) "the Venice API key" else "OPENROUTER_API_KEY"
        val keyUrl =
            if (venice) "https://venice.ai/settings/api" else "https://openrouter.ai/keys"
        return when {
            !health.reachable -> "⚠️ 18+ engine is not configured (" + health.message + "). " +
                "Adult replies cannot be generated until $keyName holds a valid key from $keyUrl."
            !health.visionReader -> "⚠️ Photo reading is OFF — the same private-engine key " +
                "also powers the vision reader, so photos cannot be seen yet."
            else -> ""
        }
    }

    /** 18+ gate confirmation lives in SharedPreferences (once verified, stays). */
    private fun isAgeVerified(): Boolean {
        return getSharedPreferences("vibemate_age", MODE_PRIVATE).getBoolean("age_verified", false)
    }

    private fun markAgeVerified() {
        getSharedPreferences("vibemate_age", MODE_PRIVATE).edit().putBoolean("age_verified", true).apply()
    }

    /**
     * 18+ confirmation — a single tap ("I am 18 or older") instead of a date-of-
     * birth picker, so entering Private mode is low-friction. This is still an
     * explicit 18+ gate (remembered once given); it is NOT an app-side content
     * limit — the adult model itself decides what it will answer.
     */
    private fun showAgeVerification() {
        AlertDialog.Builder(this)
            .setTitle("18+ Confirmation")
            .setMessage(
                "Private mode contains adult content for 18+ users only.\n" +
                    "Confirm that you are 18 or older to continue."
            )
            .setPositiveButton("I am 18 or older") { _, _ ->
                markAgeVerified()
                showToast("Age confirmed — Private mode unlocked")
                enterPrivateMode()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Call button clicked - starts a voice call with VibeMate AI */
    fun onCallButtonClicked(view: View) {
        if (currentSessionId.isEmpty()) {
            addChat(
                ChatMessage.Sender.SYSTEM,
                "",
                "Please start a chat first before calling."
            )
            return
        }
        if (isInCall) return

        isInCall = true
        callOverlay.visibility = View.VISIBLE
        startRingPulse()

        // Show the partner's name (Aisha / Arjun / Sana) on the call screen.
        callPartnerName.text = chatPartnerName()
        callStatus.text = "Calling..."
        callDuration.text = "00:00"
        callTranscript.text = "Connecting…"
        callMuteButton.alpha = 0.45f

        // Start call timer
        callStartTime = System.currentTimeMillis()
        startCallTimer()

        // THE VOICE LOOP. Before this, the call screen was only a timer: no mic
        // permission, no speech recogniser and no text-to-speech, so the AI
        // neither heard the user nor said a word. Now the mic opens (after the
        // permission is granted) and every spoken turn is answered out loud.
        startLiveVoice()

        // Call backend API to initiate call
        Thread {
            try {
                val callResult = api.startCall(currentSessionId, selectedPersona)
                runOnUiThread {
                    if (!isInCall) return@runOnUiThread
                    callStatus.setTextColor(
                        ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    )
                    addChat(
                        ChatMessage.Sender.SYSTEM,
                        "",
                        "📞 Call connected with " + chatPartnerName() + "!",
                    )
                }
            } catch (e: Exception) {
                // The call LOG is not the call itself: a Firestore hiccup must
                // never kill the voice loop, so this is reported and the call
                // keeps running (before, it hung up the call immediately).
                runOnUiThread {
                    if (!isInCall) return@runOnUiThread
                    callStatus.text = "Connected ✓ (offline log)"
                    callTranscript.text = "Network is weak — still listening…"
                }
            }
        }.start()
    }

    /** End call button clicked */
    fun onEndCallClicked(view: View) {
        endCall()
    }

    /** Close call overlay button clicked */
    fun onCloseCallClicked(view: View) {
        endCall()
    }

    /** End the current call and clean up */
    private fun endCall() {
        isInCall = false
        callTurnBusy = false
        // Close the mic and stop the phone voice FIRST: a released call must
        // never keep listening (that was the "call band karne ke baad bhi mic
        // on" kind of ghost behaviour).
        liveCall?.stop()
        callMuteButton.alpha = 0.45f
        callMuteButton.clearColorFilter()
        callOverlay.visibility = View.INVISIBLE
        stopCallTimer()
        stopRingPulse()

        // Call backend to log call end
        Thread {
            try {
                api.endCall(callStartTime)
            } catch (e: Exception) {
                // Non-critical, just log
            }
        }.start()

        // Show call ended message
        addChat(
            ChatMessage.Sender.SYSTEM,
            "",
            "📞 Call ended."
        )
    }

    /**
     * Opens the live voice loop: asks for the mic permission when needed, then
     * says the partner's greeting out loud and listens. Every spoken turn is
     * then answered turn-by-turn by [LiveCallEngine] -> [onCallSpeechHeard].
     */
    private fun startLiveVoice() {
        if (!AppConfig.LIVE_CALL_ENABLED) {
            callStatus.text = "Connected ✓ (voice off)"
            callTranscript.text = "Voice call is switched off in AppConfig."
            return
        }
        callTurnBusy = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            openLiveVoice()
        } else {
            callStatus.text = "Microphone permission needed…"
            callTranscript.text = "Please allow microphone access so the AI can hear you."
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    /** Starts the engine: it greets the user out loud, then opens the mic. */
    private fun openLiveVoice() {
        if (!isInCall) return
        callStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        // Private (18+) room: the call is the ADULT call — same private backend
        // path as the private chat (see onCallSpeechHeard) with an intimate
        // opener instead of the friendly greeting.
        liveCall?.start(
            LiveCallSpeech.greeting(selectedPersona, selectedMateName, isPrivateMode),
            // The persona also decides the VOICE: a girlfriend must sound like a
            // woman on the phone, a boyfriend like a man (LiveCallVoice). The
            // gender-neutral FRIEND takes its voice from the mate's NAME.
            selectedPersona,
            selectedMateName,
        )
    }

    /** Mic permission result: granted -> start talking, denied -> explain why. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && isInCall) {
            openLiveVoice()
        } else if (isInCall) {
            callStatus.text = "Mic permission denied"
            callStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            callTranscript.text = "I cannot hear you without the microphone. " +
                "Turn it on in Settings → Apps → VibeMate → Permissions → Microphone, " +
                "then start the call again."
        }
    }

    /**
     * The user left the app (Home / another app) while a call was running: the
     * mic must not keep recording in the background, so the call is ended the
     * same way the red button does it (chat log + duration included).
     */
    override fun onStop() {
        if (isInCall) endCall()
        super.onStop()
    }

    /**
     * Leaving the screen must SILENCE the phone: the mic is closed, a pending
     * reply is not spoken any more and the speech engines are shut down.
     */
    override fun onDestroy() {
        liveCall?.release()
        liveCall = null
        isInCall = false
        callTurnBusy = false
        // Belt and braces: if this activity is destroyed WITHOUT onStop (or the
        // call ended just before), no timer tick and no ring animation may keep
        // the views alive after the screen is gone.
        stopCallTimer()
        stopRingPulse()
        super.onDestroy()
    }

    /** Mute / unmute the microphone during a call. */
    fun onMuteCallClicked(view: View) {
        val engine = liveCall ?: return
        if (!isInCall) return
        engine.setMuted(!engine.isMuted)
        callMuteButton.alpha = if (engine.isMuted) 1f else 0.45f
        callMuteButton.setColorFilter(
            if (engine.isMuted) {
                ContextCompat.getColor(this, android.R.color.holo_red_light)
            } else {
                android.graphics.Color.WHITE
            }
        )
        callTranscript.text = if (engine.isMuted) {
            "Mic muted — not listening 🔇"
        } else {
            "Mic on — go ahead 🎤"
        }
    }

    /** Start the call duration timer */
    private fun startCallTimer() {
        callTimerRunnable = object : Runnable {
            override fun run() {
                if (isInCall) {
                    val elapsed = System.currentTimeMillis() - callStartTime
                    val seconds = (elapsed / 1000) % 60
                    val minutes = (elapsed / (1000 * 60)) % 60
                    val hours = (elapsed / (1000 * 60 * 60))
                    callDuration.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                    callTimerHandler.postDelayed(this, 1000)
                }
            }
        }
        callTimerHandler.post(callTimerRunnable!!)
    }

    /** Stop the call timer */
    private fun stopCallTimer() {
        if (callTimerRunnable != null) {
            callTimerHandler.removeCallbacks(callTimerRunnable!!)
            callTimerRunnable = null
        }
    }

    /** Everything the voice engine reports (all callbacks on the main thread). */
    private val liveCallListener = object : LiveCallEngine.Listener {

        override fun onState(state: LiveCallState) {
            if (!isInCall) return
            callStatus.setTextColor(
                ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark)
            )
            // In the 18+ room the badge makes it obvious WHICH call is running
            // (the private adult path answers, not the normal persona).
            val badge = if (isPrivateMode) " • 18+" else ""
            when (state) {
                LiveCallState.CONNECTING -> callStatus.text = "Connecting..."
                LiveCallState.LISTENING -> callStatus.text = "Listening… 🎤$badge"
                LiveCallState.THINKING -> callStatus.text = "Thinking…$badge"
                LiveCallState.SPEAKING -> callStatus.text = "Speaking… 🔊$badge"
                LiveCallState.MUTED -> callStatus.text = "Muted 🔇$badge"
                LiveCallState.ENDED -> callStatus.text = "Call ended"
            }
        }

        /** One spoken user turn: run it through the SAME chat path as typing. */
        override fun onHeard(text: String) {
            onCallSpeechHeard(text)
        }

        /** The AI's line: show it on the call screen (the engine speaks it). */
        override fun onReply(text: String) {
            if (!isInCall) return
            callTranscript.text = "🔊 VibeMate: " + text
        }

        override fun onProblem(message: String) {
            if (!isInCall) return
            callTranscript.text = message
        }

        override fun onFatal(message: String) {
            if (!isInCall) return
            callTranscript.text = message
            addChat(ChatMessage.Sender.SYSTEM, "", "📞 " + message)
            // The screen stays open so the reason can be read, but the call is over.
            liveCall?.stop()
            callStatus.text = "Call unavailable"
            callStatus.setTextColor(
                ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark)
            )
        }
    }

    /**
     * A spoken user turn -> AI reply -> voice.
     *
     * The words the mic heard go through the ordinary chat path
     * ([VibeMateApi.sendMessage] / [VibeMateApi.sendPrivateMessage]), so the
     * call keeps the persona, memory and Firestore history exactly like typing
     * does; the reply text is then handed back to [LiveCallEngine] to be spoken.
     */
    private fun onCallSpeechHeard(text: String) {
        val sessionId = currentSessionId
        if (sessionId.isEmpty() || callTurnBusy) {
            liveCall?.resumeListening()
            return
        }
        callTurnBusy = true
        callTranscript.text = "🎤 You: " + text + "\n…VibeMate is thinking"
        val private = isPrivateMode

        Thread {
            var replyText = ""
            var failure = ""
            try {
                replyText = api.sendCallTurn(sessionId, text, private)
            } catch (e: Exception) {
                failure = errorMessage(e)
                // ONE silent retry for a transient blip — a single network hiccup
                // must not turn a whole spoken turn into "network ki dikkat". A
                // quota / credit wall is NOT retried: it cannot clear in a second.
                if (!api.isCreditsError(failure) && !api.isDailyQuotaError(failure)) {
                    try {
                        Thread.sleep(CALL_TURN_RETRY_DELAY_MS)
                        replyText = api.sendCallTurn(sessionId, text, private)
                        failure = ""
                    } catch (e2: Exception) {
                        failure = errorMessage(e2)
                    }
                }
            }
            runOnUiThread {
                callTurnBusy = false
                if (!isInCall) return@runOnUiThread
                // An empty reply is spoken as an honest line instead of silence, so
                // the call never just goes dead mid-conversation — and the line
                // names the REAL cause (a busy/quota engine is not "network").
                val line = when {
                    replyText.isNotEmpty() -> replyText
                    api.isCreditsError(failure) || api.isDailyQuotaError(failure) ->
                        LiveCallSpeech.busyLine()
                    else -> LiveCallSpeech.troubleLine()
                }
                // DIAGNOSTIC: when a turn fails, show the ACTUAL reason on the call
                // screen (the spoken line stays short) so "network error" can be
                // traced instead of guessed. Also logged — filter with:
                //   adb logcat -s VibeMateCall
                if (replyText.isEmpty()) {
                    val reason = failure.ifEmpty { "empty reply (model returned nothing)" }
                    android.util.Log.w("VibeMateCall", "call turn failed: " + reason)
                    callTranscript.text = "🎤 You: " + text + "\n⚠️ " + reason
                }
                liveCall?.speakReply(line)
            }
        }.start()
    }

    /** Show a pulsing ring behind the avatar while the call is active. */
    private fun startRingPulse() {
        val ring: View = findViewById(R.id.ringAnimation)
        ring.scaleX = 1f
        ring.scaleY = 1f
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.5f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.5f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val set = AnimatorSet()
        set.playTogether(scaleX, scaleY)
        set.start()
        ringPulse = set
    }

    /** Cancel the pulsing ring animation (call finished). */
    private fun stopRingPulse() {
        ringPulse?.cancel()
        ringPulse = null
    }

    // ===================== File Picker Result Handling =====================

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Camera cancelled/failed: drop the empty temp file.
        if (requestCode == REQUEST_CAPTURE_PHOTO && resultCode != RESULT_OK) {
            capturedPhotoFile?.delete()
            capturedPhotoFile = null
            capturedPhotoUri = null
            return
        }
        if (resultCode != RESULT_OK) return
        // Camera capture: the photo lives at capturedPhotoUri (EXTRA_OUTPUT), and
        // some camera apps return a null Intent — so handle it before the data/uri
        // check below.
        if (requestCode == REQUEST_CAPTURE_PHOTO) {
            capturedPhotoUri?.let { handlePhotoAttachment(it) }
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_PICK_PHOTO -> handlePhotoAttachment(uri)
            REQUEST_PICK_AUDIO -> handleAudioAttachment(uri)
            REQUEST_PICK_VIDEO -> handleVideoAttachment(uri)
            REQUEST_PICK_DOCUMENT -> handleDocumentAttachment(uri)
        }
    }

    private fun handleAudioAttachment(uri: Uri) {
        Thread {
            try {
                val fileName = getFileName(uri) ?: "voice_note.mp3"
                val base64Data = readFileAsBase64(uri)
                if (base64Data != null && base64Data.length <= MAX_AUDIO_BYTES) {
                    runOnUiThread { sendAttachmentMessage("AUDIO", base64Data, fileName) }
                } else {
                    runOnUiThread { showToast("Audio too large (max ${MAX_AUDIO_BYTES / 1024} KB)") }
                }
            } catch (e: Exception) {
                runOnUiThread { showToast("Failed to read audio: ${e.message}") }
            }
        }.start()
    }

    private fun handleVideoAttachment(uri: Uri) {
        Thread {
            try {
                val fileName = getFileName(uri) ?: "video.mp4"
                val base64Data = readFileAsBase64(uri)
                if (base64Data != null && base64Data.length <= MAX_VIDEO_BYTES) {
                    runOnUiThread { sendAttachmentMessage("VIDEO", base64Data, fileName) }
                } else {
                    runOnUiThread { showToast("Video too large (max ${MAX_VIDEO_BYTES / 1024 / 1024} MB)") }
                }
            } catch (e: Exception) {
                runOnUiThread { showToast("Failed to read video: ${e.message}") }
            }
        }.start()
    }

    private fun handleDocumentAttachment(uri: Uri) {
        Thread {
            try {
                val fileName = getFileName(uri) ?: "document"
                val base64Data = readFileAsBase64(uri)
                if (base64Data != null && base64Data.length <= MAX_DOCUMENT_BYTES) {
                    runOnUiThread { sendAttachmentMessage("DOC", base64Data, fileName) }
                } else {
                    runOnUiThread { showToast("Document too large (max ${MAX_DOCUMENT_BYTES / 1024 / 1024} MB)") }
                }
            } catch (e: Exception) {
                runOnUiThread { showToast("Failed to read document: ${e.message}") }
            }
        }.start()
    }

    private fun handlePhotoAttachment(uri: Uri) {
        Thread {
            try {
                val fileName = getFileName(uri) ?: "photo.jpg"
                val base64Data = prepareImage(uri)
                if (base64Data != null && base64Data.length <= MAX_PHOTO_BYTES) {
                    // Never send a photo immediately: show the preview bar so the
                    // user can type a caption first, then press Send.
                    runOnUiThread { showPhotoPreview(base64Data, fileName) }
                } else {
                    runOnUiThread { showToast("Photo too large (max ${MAX_PHOTO_BYTES / 1024 / 1024} MB)") }
                }
            } catch (e: Exception) {
                runOnUiThread { showToast("Failed to read photo: ${e.message}") }
            } finally {
                // Direct-camera capture: the temp cache photo is no longer needed.
                capturedPhotoFile?.delete()
                capturedPhotoFile = null
                capturedPhotoUri = null
            }
        }.start()
    }

    /**
     * Decode + downscale an image so real camera photos (often 5-8 MB) fit the
     * 5 MB base64 limit: cap the longest side at ~1280px and re-encode as JPEG
     * quality 80. Returns the base64 payload.
     */
    private fun prepareImage(uri: Uri): String? {
        return try {
            // First pass: read just the bounds to pick a decode sample size.
            val boundsStream = contentResolver.openInputStream(uri) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(boundsStream, null, bounds)
            boundsStream.close()

            val maxSide = 1280
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

            // Second pass: decode at the sample size and compress to JPEG.
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val stream = contentResolver.openInputStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(stream, null, opts)
            stream.close()
            if (bmp == null) return null

            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bmp.recycle()
            java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (e: Exception) {
            null
        }
    }

    // ================== AI image generation ("/pic") ==================

    /**
     * "/pic <kya chahiye>", "/image ..." or "/img ..." -> returns the prompt part
     * ("" when the text is not an image command). A bare "/pic" just shows help.
     */
    private fun imageGenPromptIfAny(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        for (prefix in listOf("/pic ", "/image ", "/img ")) {
            if (lower.startsWith(prefix)) {
                return trimmed.substring(prefix.length).trim()
            }
        }
        if (lower == "/pic" || lower == "/image" || lower == "/img") {
            showToast("To send a pic: /pic <what you want> (e.g. /pic a girl taking a mirror selfie)")
        }
        return ""
    }

    /**
     * Detect a natural-language photo request ("apni pic bhejo na", "selfie
     * dikhao", "photo send karo", "tasveer chahiye") and return a selfie-style
     * image prompt for the current mate, or "" when the text is not a photo
     * request. The detailed "/pic <wish>" command is handled separately above.
     */
    private fun photoRequestPromptIfAny(text: String): String {
        val t = " " + text.lowercase() + " "
        val nouns = listOf(
            "photo", "photos", "pic", "pics", "picture", "pictures",
            "selfie", "selfi", "tasveer", "tasvir", "tasweer", "taswir",
            "foto", "image", "snap"
        )
        if (nouns.none { t.contains(it) }) return ""
        val verbs = listOf(
            "bhej", "send", "dikha", "dikhana", "dikhao", "dikado",
            "chahiye", "do na", "de na", "dijiye", "mangta", "mangti",
            "milegi", "mil jayegi", "kardo", "kar do", "post", "forward"
        )
        val selfieAsk = t.contains("selfie") || t.contains("selfi") ||
            t.contains("tasveer") || t.contains("tasvir")
        if (verbs.none { t.contains(it) } && !selfieAsk) return ""
        return selfieStylePrompt(selectedPersona, selectedMateName)
    }

    /** A realistic front-camera selfie prompt for the current persona/name. */
    private fun selfieStylePrompt(persona: String, mateName: String): String {
        val name = mateName.trim().ifEmpty { PersonaPrompts.defaultName(persona) }
        return when (PersonaPrompts.normalizePersona(persona)) {
            "girlfriend" -> "realistic up-close selfie of a beautiful young Indian woman named $name, front camera, warm soft smile, natural warm lighting, cozy bedroom background"
            "boyfriend" -> "realistic up-close selfie of a handsome young Indian man named $name, front camera, confident warm smile, natural lighting"
            "crush" -> "realistic up-close selfie of a cute young Indian woman named $name, shy sweet smile, front camera, soft natural light"
            "friend" -> "realistic up-close selfie of a friendly young Indian man named $name, front camera, casual smile, natural lighting"
            // Gay mode: boy gay = the mate is a MAN (like boyfriend mode),
            // girl gay = the mate is a WOMAN (like girlfriend mode).
            PersonaPrompts.GAY_BOY -> "realistic up-close selfie of a handsome young Indian man named $name, front camera, confident warm smile, natural lighting"
            PersonaPrompts.GAY_GIRL -> "realistic up-close selfie of a beautiful young Indian woman named $name, front camera, warm soft smile, natural warm lighting"
            else -> "realistic up-close selfie of a friendly young person, front camera, warm smile, natural light"
        }
    }

    /** Generate an AI image for `/pic <prompt>`, save it and show it as a photo. */
    private fun generateAiImage(prompt: String) {
        // Master switch (AppConfig.IMAGE_GEN_ENABLED = false): VibeMate never
        // creates pictures. The `/pic` and photo-request branches in
        // onSendClicked are gated on the same flag, so this only guards a future
        // call site (api.generateImage() refuses too).
        if (!AppConfig.IMAGE_GEN_ENABLED) {
            showToast("Sending photos is turned off right now.")
            return
        }
        if (isSending) return
        if (currentSessionId.isEmpty()) {
            showToast("Start a chat first, then try /pic")
            return
        }
        isSending = true
        messageInput.setText("")
        sendButton.setEnabled(false)
        setChatStatusTyping(true)
        val typingRow = ChatMessage(ChatMessage.Sender.TYPING, chatPartnerName(), "...", nowLabel())
        chatAdapter.add(typingRow)
        scrollChatToBottom()
        val sessionId = currentSessionId

        Thread {
            try {
                // 1) Generate (OpenRouter /images — same key as the Private room).
                val gen = api.generateImage(prompt)
                // 2) Shrink to a Firestore-safe JPEG (docs are capped at 1 MiB).
                //    Deliberately small: it is only shown in a ~280dp bubble.
                val jpeg = resizeBase64Jpeg(
                    gen.data,
                    AppConfig.IMAGE_GEN_LOCAL_MAX_SIDE,
                    AppConfig.IMAGE_GEN_LOCAL_JPEG_QUALITY,
                )
                if (jpeg.isEmpty()) {
                    throw ApiException("The generated pic could not be compressed. Please try again.")
                }
                // 3) Persist as a normal IMAGE message so it survives reopening.
                val dto = MessageDto()
                dto.role = "model"
                dto.content = ""
                dto.type = "IMAGE"
                dto.data = jpeg
                dto.fileName = "vibemate_pic.jpg"
                dto.createdAt = api.nowUtc()
                api.saveMessage(sessionId, dto)

                runOnUiThread {
                    setChatStatusTyping(false)
                    chatAdapter.remove(typingRow)
                    chatAdapter.add(
                        ChatMessage(
                            ChatMessage.Sender.VIBEMATE,
                            chatPartnerName(),
                            "",
                            nowLabel(),
                            "IMAGE",
                            jpeg,
                            "vibemate_pic.jpg",
                        )
                    )
                    scrollChatToBottom()
                    sendButton.setEnabled(true)
                    isSending = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setChatStatusTyping(false)
                    chatAdapter.remove(typingRow)
                    addChat(ChatMessage.Sender.SYSTEM, "", "Pic could not be generated: " + errorMessage(e))
                    sendButton.setEnabled(true)
                    isSending = false
                }
            }
        }.start()
    }

    /**
     * Decode a base64 image, downscale the longest side to maxSide and re-encode
     * as JPEG — same idea as prepareImage() but from generated bytes. Returns ""
     * on failure.
     */
    private fun resizeBase64Jpeg(base64Data: String, maxSide: Int, quality: Int): String {
        return try {
            val raw = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return ""
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            java.util.Base64.getEncoder().encodeToString(out.toByteArray())
        } catch (e: Exception) {
            ""
        }
    }

    // ===================== helpers =====================

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun sendAttachmentMessage(type: String, base64Data: String, fileName: String, caption: String = "") {
        if (isSending) { showToast("Please wait for the previous message"); return }
        if (currentSessionId.isEmpty()) { showToast("No active chat session"); return }

        isSending = true
        sendButton.isEnabled = false
        // The photo is now being sent — dismiss the pending-photo preview.
        hidePhotoPreview()

        val userRow = ChatMessage(ChatMessage.Sender.USER, myDisplayName, caption, nowLabel()).apply {
            this.type = type
            this.data = base64Data
            this.fileName = fileName
        }

        chatAdapter.add(userRow)
        scrollChatToBottom()

        val typingRow = ChatMessage(ChatMessage.Sender.TYPING, chatPartnerName(), "...", nowLabel())
        chatAdapter.add(typingRow)
        scrollChatToBottom()
        setChatStatusTyping(true)

        Thread {
            try {
                runOnUiThread { chatAdapter.updateStatus(userRow, ChatMessage.Status.SENT) }
                runOnUiThread { chatAdapter.updateStatus(userRow, ChatMessage.Status.DELIVERED) }

                val attachment = MessageDto().apply {
                    this.type = type
                    this.data = base64Data
                    this.fileName = fileName
                }

                // Private room: the photo (with its caption) is answered by the
                // in-app adult OpenRouter engine (vision read + persona model);
                // normal chats use the default Gemini path. Both save locally.
                val reply = if (isPrivateMode) api.sendPrivateMessage(currentSessionId, caption, attachment)
                else api.sendMessage(currentSessionId, caption, attachment)
                userRow.id = reply.userMessage.id

                runOnUiThread {
                    if (isPrivateMode) {
                        // The vision reader's one-line note — PROOF the photo was
                        // really read (and WHAT the reader saw). Dead code before:
                        // visionNote came back from the engine but was never shown.
                        val note = reply.visionNote.trim()
                        if (note.isNotEmpty()) {
                            addChat(ChatMessage.Sender.SYSTEM, "", "👁️ Photo read: $note")
                        }
                        // Same Private-room behaviour as text: tiny bubbles with a
                        // short typing pause between them.
                        postVibeMateReplies(replyBubbles(reply), typingRow, userRow)
                    } else {
                        val replyText = reply.assistantMessage.content.trim()
                        setChatStatusTyping(false)
                        chatAdapter.remove(typingRow)
                        val row = ChatMessage(
                            ChatMessage.Sender.VIBEMATE,
                            chatPartnerName(),
                            if (replyText.isEmpty()) "I received your $type! 😊" else replyText,
                            nowLabel(),
                        )
                        row.id = reply.assistantMessage.id
                        chatAdapter.add(row)
                        scrollChatToBottom()
                        chatAdapter.updateStatus(userRow, ChatMessage.Status.READ)
                        sendButton.isEnabled = true
                        isSending = false
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setChatStatusTyping(false)
                    chatAdapter.remove(typingRow)
                    val msg = errorMessage(e)
                    if (msg.contains("already being sent")) {
                        chatAdapter.remove(userRow)
                        sendButton.isEnabled = true
                        isSending = false
                        return@runOnUiThread
                    }
                    chatAdapter.updateStatus(userRow, ChatMessage.Status.FAILED)
                    addChat(ChatMessage.Sender.SYSTEM, "", "Could not send: $msg")
                    sendButton.isEnabled = true
                    isSending = false
                }
            }
        }.start()
    }

    /** Show the pending-photo preview so the user can add a caption first. */
    private fun showPhotoPreview(base64Data: String, fileName: String) {
        pendingPhotoData = base64Data
        pendingPhotoName = fileName
        val bmp = try {
            val raw = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val opts = android.graphics.BitmapFactory.Options()
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        } catch (e: Exception) {
            null
        }
        if (bmp != null) {
            photoPreviewThumb.setImageBitmap(bmp)
            photoPreviewThumb.visibility = View.VISIBLE
        } else {
            photoPreviewThumb.visibility = View.GONE
        }
        photoPreviewBar.visibility = View.VISIBLE
    }

    /** Dismiss the pending-photo preview without sending anything. */
    fun onPhotoPreviewCancelClicked(view: View) {
        hidePhotoPreview()
    }

    /** Reset the pending-photo state and hide the preview bar. */
    private fun hidePhotoPreview() {
        pendingPhotoData = ""
        pendingPhotoName = ""
        photoPreviewBar.visibility = View.GONE
    }

    private fun readFileAsBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val buffer = ByteArray(8192)
            val outputStream = java.io.ByteArrayOutputStream()
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            inputStream.close()
            outputStream.close()
            java.util.Base64.getEncoder().encodeToString(outputStream.toByteArray())
        } catch (e: Exception) { null }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) cursor.getString(nameIndex) else null
                } else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) { uri.lastPathSegment }
    }

    private fun setAuthBusy(busy: Boolean, message: String) {
        loginButton.setEnabled(!busy)
        registerButton.setEnabled(!busy)
        anonymousButton.setEnabled(!busy)
        googleButton.setEnabled(!busy)
        emailInput.setEnabled(!busy)
        passwordInput.setEnabled(!busy)
        nameInput.setEnabled(!busy)
        statusText.setText(message)
        // The status line is the last row of the login card, so on a phone where
        // the card is taller than the visible area it starts below the fold — a
        // failed login then looks like a dead button, because the explanation
        // never reaches the eye. Scroll the card to its bottom whenever there is
        // something to say, and back to the top once the message is cleared.
        authScroll.post {
            val target = if (message.isEmpty()) 0 else authScroll.getChildAt(0)?.height ?: 0
            authScroll.smoothScrollTo(0, target)
        }
    }

    /** Fill the WhatsApp header: partner name, green avatar letter, online status. */
    private fun refreshChatHeader(persona: String) {
        val name = currentMateName(persona)
        chatTitle.setText(name)
        chatStatus.setText("online" + statusSuffix())
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
        chatAvatar.setText(letter)
    }

    /**
     * The mate's name: the user-given name first, otherwise the persona default
     * (Aisha / Arjun / Sana). Every bubble/header/call uses this.
     */
    private fun currentMateName(persona: String): String {
        return selectedMateName.ifEmpty { PersonaPrompts.defaultName(persona) }
    }

    private fun setChatStatusTyping(typing: Boolean) {
        chatStatus.setText((if (typing) "typing…" else "online") + statusSuffix())
    }

    private fun showPersonaPanel() {
        authPanel.setVisibility(View.INVISIBLE)
        chatPanel.setVisibility(View.INVISIBLE)
        matesPanel.setVisibility(View.INVISIBLE)
        personaPanel.setVisibility(View.VISIBLE)
        // A hidden message box must not keep the caret (see clearInputFocus).
        clearInputFocus()
    }

    /**
     * Home-screen helper: if we are logged in, open the WhatsApp-style mates
     * list; otherwise fall back to the login panel. Used by the back button so
     * the user never lands on the login screen while still logged in.
     */
    private fun showMatesDialogOrLogin() {
        if (api.isLoggedIn() || api.hasStoredSession()) {
            showMatesPanel()
        } else {
            showPersonaPanel()
        }
    }

    private fun showChatPanel() {
        currentSessionId = ""
        chatAdapter.clear()
        // A brand-new chat always starts in normal mode (Private is toggled on demand).
        isPrivateMode = false
        updatePrivateButtonUi()
        updatePrivateStatusText()
        // Show the mate's custom name in the header immediately (or the default).
        chatTitle.setText(currentMateName(selectedPersona))
        chatStatus.setText("connecting…")
        chatAvatar.setText(
            currentMateName(selectedPersona).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
        )
        authPanel.setVisibility(View.INVISIBLE)
        personaPanel.setVisibility(View.INVISIBLE)
        matesPanel.setVisibility(View.INVISIBLE)
        chatPanel.setVisibility(View.VISIBLE)
        focusMessageInput()
        startNewChat()
    }

    /**
     * Puts the caret in the message box when the chat screen opens, and drops any
     * stale focus left on a field of a HIDDEN panel.
     *
     * THE BUG THIS FIXES: after typing a name in the "Add a relationship" panel,
     * that hidden "Their name" box kept focus, so anything typed on the chat
     * screen was appended to the CHARACTER'S NAME instead of the message — you
     * had to tap the chat box first to "fix" it.
     */
    private fun focusMessageInput() {
        clearInputFocus()
        // After layout, so a freshly-visible box can actually take the focus.
        messageInput.post { messageInput.requestFocus() }
    }

    /**
     * Drops focus from EVERY text field. Called whenever the screen switches
     * panels, so a box that is now hidden can never keep receiving typing meant
     * for another screen — the "text went into the character's name" bug, and its
     * mirror case (typing landing in a hidden message box).
     */
    private fun clearInputFocus() {
        mateNameInput.clearFocus()
        emailInput.clearFocus()
        passwordInput.clearFocus()
        nameInput.clearFocus()
        messageInput.clearFocus()
    }

    private fun startNewChat() {
        sendButton.setEnabled(false)
        val persona = selectedPersona
        val mateName = selectedMateName
        Thread {
            try {
                val session = api.createSession("New chat", persona, mateName, isPrivateMode)
                runOnUiThread {
                    currentSessionId = session.id
                    refreshChatHeader(persona)
                    addChat(
                        ChatMessage.Sender.VIBEMATE,
                        personaGreeting(persona),
                        "Connected! Say hello to start chatting.",
                    )
                    sendButton.setEnabled(true)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addChat(
                        ChatMessage.Sender.SYSTEM,
                        "",
                        "Could not reach the server: " + errorMessage(e),
                    )
                    sendButton.setEnabled(true)
                }
            }
        }.start()
    }

    /**
     * Adds one bubble to the chat list and scrolls it into view.
     */
    private fun addChat(sender: ChatMessage.Sender, displayName: String, text: String) {
        chatAdapter.add(ChatMessage(sender, displayName, text, nowLabel()))
        scrollChatToBottom()
    }

    /**
     * Invite shown in normal chat when the user tries explicit (18+) content:
     * a friendly message plus a "Join Private Chat" button that opens the 18+
     * Private room (running the age gate when needed). Replaces the old raw
     * "Could not send: Unexpected Gemini response…" error.
     */
    private fun addPrivateJoinPrompt() {
        val row = ChatMessage(
            ChatMessage.Sender.SYSTEM,
            "",
            "🔞 That kind of chat is not allowed here.\n" +
                "Continue in the Private (18+) room.",
            nowLabel(),
        )
        row.actionLabel = "🔒 Join Private Chat"
        chatAdapter.add(row)
        scrollChatToBottom()
    }

    /** Tap on the "Join Private Chat" button inside the chat. */
    private fun onPrivateJoinPromptClicked() {
        if (isPrivateMode || currentSessionId.isEmpty()) return
        if (!isAgeVerified()) {
            // showAgeVerification() auto-enters the Private room once 18+ is confirmed.
            showAgeVerification()
            return
        }
        enterPrivateMode()
    }

    /**
     * VibeMate's reply on screen. A Private-room CHAT turn arrives as 2-3 tiny
     * messages, so they are posted one by one with a short "typing…" pause
     * between them — exactly like a real partner firing off quick WhatsApp texts
     * ("chhote chhote tukde"). An INFO answer arrives as ONE complete bubble.
     *
     * [typingRow] is shown before each bubble and hidden after the last one;
     * [onFinished] runs once the final bubble is on screen.
     */
    private fun postVibeMateReplies(
        bubbles: List<MessageDto>,
        typingRow: ChatMessage,
        userRow: ChatMessage,
        onFinished: () -> Unit = {},
    ) {
        // Pair each visible bubble with its stored id so the in-chat "delete"
        // feature can remove it from Firestore later.
        val queue = bubbles.map { it.content.trim() to it.id }
            .filter { it.first.isNotEmpty() }
            .ifEmpty { listOf("Sorry, I could not generate a reply. Please try again." to "") }
        val handler = Handler(Looper.getMainLooper())

        fun step(i: Int) {
            chatAdapter.remove(typingRow)
            if (i >= queue.size) {
                // All bubbles are on screen: stop the typing header, mark the
                // user's message read and re-enable sending.
                setChatStatusTyping(false)
                chatAdapter.updateStatus(userRow, ChatMessage.Status.READ)
                sendButton.isEnabled = true
                isSending = false
                onFinished()
                return
            }
            val (text, id) = queue[i]
            val row = ChatMessage(ChatMessage.Sender.VIBEMATE, chatPartnerName(), text, nowLabel())
            row.id = id
            chatAdapter.add(row)
            scrollChatToBottom()
            if (i < queue.size - 1) {
                // "typing…" again before the next little text, like a human who
                // is still writing.
                chatAdapter.add(typingRow)
                scrollChatToBottom()
                setChatStatusTyping(true)
                handler.postDelayed({ step(i + 1) }, AppConfig.PRIVATE_BUBBLE_TYPING_DELAY_MS)
            } else {
                step(i + 1)
            }
        }
        step(0)
    }

    /** Every reply bubble of a response (falls back to the single message). */
    private fun replyBubbles(reply: ChatResponseDto): List<MessageDto> {
        val bubbles = reply.assistantMessages
        return bubbles.ifEmpty { listOf(reply.assistantMessage) }
    }

    private fun nowLabel(): String = timeFormat.format(Date())

    /** Header label for the chat title — mate-aware (custom naam pehle). */
    private fun personaLabel(persona: String): String = currentMateName(persona)

    /** Full header label incl. app name, e.g. "VibeMate — Priya". */
    private fun personaFullLabel(persona: String): String {
        return "VibeMate — " + currentMateName(persona)
    }

    /** Short partner name shown in the WhatsApp header — mate-aware. */
    private fun personaShortName(persona: String): String = currentMateName(persona)

    /** Name header above VibeMate's bubbles reflects the chosen mate's naam. */
    private fun personaGreeting(persona: String): String = currentMateName(persona)

    /** Partner name used in chat bubbles / typing row / call screen. */
    private fun chatPartnerName(): String = currentMateName(selectedPersona)

    private fun scrollChatToBottom() {
        chatList.post {
            val count = chatAdapter.itemCount
            if (count > 0) chatList.smoothScrollToPosition(count - 1)
        }
    }

    private fun errorMessage(e: Exception): String {
        val message = e.message
        return if (message.isNullOrEmpty()) e.toString() else message
    }

    /**
     * Firebase answers with terse machine codes ("INVALID_LOGIN_CREDENTIALS"),
     * which read as "the app is broken" to a normal user. This turns them into
     * something actionable, and tells the user which of the (now working) login
     * buttons actually solves their case.
     */
    private fun authErrorMessage(e: Exception): String {
        val raw = errorMessage(e)
        val code = raw.trim().substringBefore(" ").substringBefore(":").uppercase()
        return when {
            code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("EMAIL_NOT_FOUND") ||
                code.startsWith("INVALID_PASSWORD") ->
                "Wrong email or password. New here? Tap \"Create Account\" first."
            code.startsWith("INVALID_EMAIL") || code.startsWith("MISSING_EMAIL") ->
                "That email address looks invalid. Please check it."
            code.startsWith("EMAIL_EXISTS") ->
                "This email already has an account — tap \"Log In\" to sign in."
            code.startsWith("FEDERATED_USER_ID_ALREADY_LINKED") ->
                "This email was registered with Google — use \"Continue with Google\"."
            code.startsWith("WEAK_PASSWORD") ->
                "Password is too short. Firebase needs at least 6 characters."
            code.startsWith("TOO_MANY_ATTEMPTS") ->
                "Too many attempts. Please wait a minute and try again."
            code.startsWith("USER_DISABLED") ->
                "This account has been disabled. Please contact support."
            code.startsWith("OPERATION_NOT_ALLOWED") || code.startsWith("ADMIN_ONLY") ||
                code.startsWith("CONFIGURATION_NOT_FOUND") ->
                "This sign-in method is switched off in Firebase → Authentication → Sign-in method."
            code.startsWith("MISSING_IDP_RESPONSE") || code.startsWith("INVALID_IDP_RESPONSE") ->
                "Google did not finish the sign-in. Please tap \"Continue with Google\" again."
            raw.contains("Could not connect to Firebase", true) ||
                raw.contains("internet", true) || raw.contains("network", true) ->
                "No internet connection. Please check your network and try again."
            else -> raw
        }
    }
}