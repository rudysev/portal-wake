plugins {
  // AGP 9 bundles Kotlin support and registers the `kotlin` extension itself, so the standalone
  // org.jetbrains.kotlin.android plugin must NOT be applied (it double-registers and fails).
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.portal.wake"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.portal.wake"
    minSdk = 28          // Android 9 — covers all Portal devices (incl. 1st-gen Portal+ "aloha")
    targetSdk = 29       // Android 10 — Portal-era behavior; background-started FG mic still allowed
    versionCode = 5
    versionName = "2.3"

    ndk {
      // Portal hardware is ARM only — every device this app targets ("aloha" and the gen2 units) is
      // arm64-v8a. The x86/x86_64 slices of onnxruntime and vosk-android were ~63 MB of a 247 MB APK that
      // no Portal could ever load.
      //
      // The cost is that the app no longer installs on an x86 emulator. That is deliberate: the wake path
      // cannot be evaluated off-device anyway (it needs the real VOICE_RECOGNITION capture path and the
      // device's own acoustics — see hey-jarvis/PHASE_B.md), so the emulator was never where this was
      // tested. Drop this block if you ever need a host-side install back.
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
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
    // Use the bundled org.json (testImplementation) instead of android.jar's throwing stubs, so the
    // Vosk-JSON parser can be exercised in plain JVM unit tests.
    unitTests.isReturnDefaultValues = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  // Shared utilities (DebugLog/PcmCaptureSession/PcmCaptureFormat); composite-build substituted from ./commons.
  implementation("com.portal:commons")
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation("com.portal:commons-android")
  // On-device wake-word recognition — free, keyless, offline, no Google Mobile Services.
  implementation(libs.vosk.android)

  testImplementation(libs.junit)
  testImplementation(libs.json)
}
