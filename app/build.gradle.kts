plugins {
  // AGP 9 bundles Kotlin support and registers the `kotlin` extension itself, so the standalone
  // org.jetbrains.kotlin.android plugin must NOT be applied (it double-registers and fails).
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.dependency.analysis) // `buildHealth` advice; see the root build
}

android {
  namespace = "com.portal.wake"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.wake"
    minSdk = 28 // Android 9 — covers all Portal devices (incl. 1st-gen Portal+ "aloha")
    // Sideloaded onto Portal (no Play Store), so Play's target-API floor doesn't apply; 29 is deliberate.
    //noinspection ExpiredTargetSdkVersion
    targetSdk = 29 // Android 10 — Portal-era behavior; background-started FG mic still allowed
    versionCode = 5
    versionName = "2.3"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  testOptions {
    // Use the bundled org.json (testRuntimeOnly) instead of android.jar's throwing stubs, so the
    // Vosk-JSON parser can be exercised in plain JVM unit tests.
    unitTests.isReturnDefaultValues = true
  }
}

kotlin {
  // Keep the build warning-free: any compiler warning fails compilation.
  compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
  // NotificationCompat/ContextCompat and @RequiresApi; the app uses none of core-ktx's Kotlin extensions.
  implementation(libs.androidx.core)
  implementation(libs.androidx.annotation)
  // Shared utilities (DebugLog/PcmCaptureSession/PcmCaptureFormat); composite-build substituted from ./commons.
  implementation("com.portal:commons")
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation("com.portal:commons-android")
  // On-device wake-word recognition — free, keyless, offline, no Google Mobile Services. Runtime-only: the app
  // never calls Vosk itself (commons-android does), it only ships the native library.
  runtimeOnly(libs.vosk.android)

  testImplementation(libs.junit)
  // Real org.json at test runtime only: the code under test calls it, the tests never reference it.
  testRuntimeOnly(libs.json)
}
