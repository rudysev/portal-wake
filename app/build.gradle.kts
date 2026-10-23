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
  // Any lint warning fails the build. No baseline: suppress inline only, with a reason.
  lint {
    warningsAsErrors = true
    abortOnError = true
    checkReleaseBuilds = true
    // Dependency-freshness checks query Maven/Google at lint time, so the result changes whenever upstream ships a
    // release, not when this code changes. Dependency updates are deliberate (Renovate/Dependabot are out of scope).
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
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

// `check` lints only the default (debug) variant. Lint release too, so release-variant problems fail pre-push and CI
// rather than waiting for lintVital, which only runs on assembleRelease (checkReleaseBuilds) and nothing builds that.
tasks.check { dependsOn("lintRelease") }

dependencies {
  // NotificationCompat/ContextCompat and @RequiresApi; the app uses none of core-ktx's Kotlin extensions.
  implementation(libs.androidx.core)
  implementation(libs.androidx.annotation)
  // Shared utilities (DebugLog/PcmCaptureSession/PcmCaptureFormat); composite-build substituted from ./commons.
  implementation(libs.portal.commons)
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation(libs.portal.commons.android)

  testImplementation(libs.junit)
  // Real org.json at test runtime only: the code under test calls it, the tests never reference it.
  testRuntimeOnly(libs.json)
}
