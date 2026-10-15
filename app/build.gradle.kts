import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

// ============================================================================
// SECRETS — read from the git-ignored `secrets.properties` at the project root
// and injected into BuildConfig (read back in AppConfig.kt). This is why no API
// key is hardcoded anywhere in the source any more. A missing file simply
// yields empty values, and the app then shows its clear "key not configured"
// error instead of silently failing.
//
// First run: copy `secrets.properties.example` to `secrets.properties`.
// ============================================================================
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String =
    (secrets.getProperty(name) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.vibemate"
    compileSdk {
        // androidx.core 1.19.x publishes AAR metadata requiring android-37.
        // Only compileSdk is raised; targetSdk (runtime behavior) stays at 36
        // and minSdk (supported devices) stays at 26.
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vibemate"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Secrets injected from the git-ignored secrets.properties (see top).
        // Read back in AppConfig.kt via BuildConfig.*.
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${secret("OPENROUTER_API_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
        buildConfigField("String", "GEMINI_API_KEY_2", "\"${secret("GEMINI_API_KEY_2")}\"")
    }

    // BuildConfig is what carries the injected secrets into the code.
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // HTTP + JSON for talking to the VibeMate backend
    implementation(libs.okhttp)
    implementation(libs.gson)
    // WhatsApp-style chat message list
    implementation(libs.androidx.recyclerview)
    // Firebase BoM - manages all Firebase dependency versions
    implementation(platform(libs.firebase.bom))
    // Google sign-in: Credential Manager shows the phone's own Google accounts
    // (no browser, no password) and googleid turns the chosen account into the
    // ID token that FirebaseAuth trades for a Firebase session
    // (see GoogleSignInClient.kt). credentials-play-services-auth is what talks
    // to Google Play services under Android 14's Credential Manager.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // Add Firebase dependencies here as needed (e.g., FirebaseAuth, Firestore, etc.)
    // Example:
    // implementation("com.google.firebase:firebase-auth")
    // implementation("com.google.firebase:firebase-firestore")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}