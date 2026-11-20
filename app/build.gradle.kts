import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.wire)
}

// H.I.B release signing: the keystore and its passwords live outside the repo, in a private file
// (default ~/Library/Application Support/H.I.B/signing/keystore.properties, or $HIB_SIGNING_PROPS).
// Without it, release builds are left unsigned.
val hibSigningProps = Properties().apply {
    val f = file(System.getenv("HIB_SIGNING_PROPS")
        ?: "${System.getProperty("user.home")}/Library/Application Support/H.I.B/signing/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "com.sameerasw.airsync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sameerasw.airsync"
        minSdk = 30
        versionCode = 33
        versionName = "4.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("hibRelease") {
            hibSigningProps.getProperty("storeFile")?.let { storeFile = file(it) }
            storePassword = hibSigningProps.getProperty("storePassword")
            keyAlias = hibSigningProps.getProperty("keyAlias")
            keyPassword = hibSigningProps.getProperty("keyPassword")
        }
    }

    buildTypes {
//        optimized dev build
//           debug {
//              isMinifyEnabled = true
//              isShrinkResources = true
//              isDebuggable = false
//              proguardFiles(
//                  getDefaultProguardFile("proguard-android-optimize.txt"),
//                  "proguard-rules.pro"
//              )
//           }
//        end

        release {
            // H.I.B: ship exactly the code that runs on the test phone (debug builds aren't shrunk);
            // R8 can strip classes only reached by reflection, which no device test would catch.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hibSigningProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("hibRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileSdkMinor = 1

    defaultConfig {
        targetSdk = 37
        buildConfigField("String", "MIN_MAC_APP_VERSION", "\"4.0.0\"")
    }
}

dependencies {
    implementation(libs.exceptionreport)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Smartspacer SDK
    implementation("com.kieronquinn.smartspacer:sdk-plugin:1.1")

    // Material Components (XML themes: Theme.Material3.*)
    implementation("com.google.android.material:material:1.14.0")

    // H.I.B: Touch ID on Phone (BiometricPrompt for Mac approvals)
    implementation("androidx.biometric:biometric:1.1.0")

    // Android 12+ SplashScreen API with backward compatibility attributes
    implementation("androidx.core:core-splashscreen:1.2.0")

    implementation("androidx.compose.material3:material3:1.5.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("com.airbnb.android:lottie-compose:6.7.1")

    // DataStore for state persistence
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.datastore:datastore-core:1.1.7")

    // ViewModel and state handling
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.13.0")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // WebSocket support
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // JSON parsing for GitHub API
    implementation("com.google.code.gson:gson:2.15.0")

    // Media session support for Mac media player
    implementation("androidx.media:media:1.8.0")

    implementation(libs.ui.graphics)
    implementation(libs.androidx.foundation)


    // CameraX for QR scanning
    implementation("androidx.camera:camera-core:1.7.0")
    implementation("androidx.camera:camera-camera2:1.7.0")
    implementation("androidx.camera:camera-lifecycle:1.7.0")
    implementation("androidx.camera:camera-view:1.7.0")
    implementation("androidx.camera:camera-mlkit-vision:1.7.0")

    // Room database for call history
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Phone number normalization
    implementation(libs.libphonenumber)

    // Coroutines for async operations
    implementation(libs.kotlinx.coroutines.android)


    // ML Kit barcode scanner (QR code only)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Google Play Review
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)

    // Coil for image and GIF loading
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation(libs.wire.runtime)
    implementation(libs.bouncycastle)

    // Ktor Server for WebDAV
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
}

wire {
    kotlin {
        // Wire defaults to current project's proto directory
    }
}
