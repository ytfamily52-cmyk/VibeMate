// VibeMate ADMIN app — a SEPARATE APK for the project owner.
//
// It lists every user, opens a user's whole chat history, and supports
// long-press → select → "All" → delete for users and for chats.
//
// Why no Firebase SDK / google-services.json here:
//   the admin app talks to Firebase Auth + Firestore over the same REST APIs
//   the main app already uses (see AdminAuth.kt / AdminStore.kt), so the web API
//   key + project id in AdminConfig.kt are all it needs to build and run.
//
// Cross-user reads are enforced by firestore.rules -> isAdmin(): add the admin
// account's email/uid there and deploy the rules (see admin/README.md).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.vibemate.admin"
    compileSdk {
        // Mirrors :app — androidx.core 1.19.x publishes AAR metadata that
        // requires android-37. targetSdk stays at 36, minSdk at 26.
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.vibemate.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // HTTP + JSON for the Firebase Auth / Firestore REST calls.
    implementation(libs.okhttp)
    implementation(libs.gson)
    // User list / chat list / message list.
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
}
