plugins {
  // AGP 9 bundles Kotlin support and registers the `kotlin` extension itself, so the standalone
  // org.jetbrains.kotlin.android plugin must NOT be applied (it double-registers and fails).
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlinter)
  alias(libs.plugins.detekt)
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
    // 16 KB page alignment matters from Android 15 on; Portals run Android 9 and 10 (4 KB pages). onnxruntime-android
    // stays on 1.19.2, the version the hybrid's gate was measured on, whose arm64 library is 4 KB aligned.
    disable += "Aligned16KB"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Prebuilt dependency .so files, packaged as they are: stripping needs an NDK, which nothing here installs, and
  // without one AGP warns on every native-lib merge. libvosk ships stripped already; JNA's libjnidispatch keeps its
  // symbols (~130 KB per ABI), as do the hybrid's ONNX Runtime and sherpa-onnx libraries. The x86 libraries are dropped
  // before the merge, which otherwise fails on two x86 libonnxruntime.so files (only sherpa-onnx's x86 build links
  // ONNX Runtime dynamically); every Portal is ARM.
  packaging {
    jniLibs {
      excludes += setOf("lib/x86/**", "lib/x86_64/**")
      keepDebugSymbols +=
        setOf(
          "**/libvosk.so",
          "**/libjnidispatch.so",
          "**/libsherpa-onnx-jni.so",
          "**/libonnxruntime.so",
          "**/libonnxruntime4j_jni.so",
        )
    }
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

// The sherpa-onnx AAR comes from ../fetch-hybrid-deps.sh; fail early and say so when it is missing. (The models are
// optional: without them the hybrid is unavailable at runtime and Vosk alone decides.)
val sherpaAar: File = file("libs/sherpa-onnx-static-link-onnxruntime-2.0.1.aar")
val checkHybridDeps = tasks.register("checkHybridDeps") {
  val aar = sherpaAar
  doFirst { check(aar.isFile) { "Missing $aar: run ./fetch-hybrid-deps.sh first" } }
}
tasks.named("preBuild") { dependsOn(checkHybridDeps) }

dependencies {
  // NotificationCompat/ContextCompat and @RequiresApi; the app uses none of core-ktx's Kotlin extensions.
  implementation(libs.androidx.core)
  implementation(libs.androidx.annotation)
  // Shared utilities (DebugLog/PcmCapture/PcmCaptureFormat); composite-build substituted from ./commons.
  implementation(libs.portal.commons)
  // Shared Android shells (AudioRecordPcmDevice); composite-build substituted from ./commons-android.
  implementation(libs.portal.commons.android)
  // The hybrid wake detector's openWakeWord gate (the ai.onnxruntime API).
  implementation(libs.onnxruntime.android)
  // The hybrid's keyword spotter: sherpa-onnx with ONNX Runtime linked statically into libsherpa-onnx-jni.so (no
  // second libonnxruntime.so to clash with onnxruntime-android's). Not on Maven Central: ../fetch-hybrid-deps.sh.
  implementation(files(sherpaAar))

  testImplementation(libs.junit)
  // Real org.json at test runtime only: the code under test calls it, the tests never reference it.
  testRuntimeOnly(libs.json)
}

// detekt on the defaults plus the shared opt-ins in config/detekt/detekt.yml (identical in all three repos).
detekt {
  buildUponDefaultConfig = true
  config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// `check` runs the type-resolving tasks (detektMain/detektTest: main and test classes, every variant) instead of the
// plugin's default `detekt` task, which analyses the same sources without type resolution. `detekt` is disabled
// rather than unhooked from `check`, so this holds however the plugin wires it.
tasks.named("detekt") { enabled = false }
tasks.check { dependsOn("detektMain", "detektTest") }

// The plugin's variant detekt classpath is a convention mapped from the compile task (kotlinc.map { it.libraries }),
// and that Provider drops the build dependencies of the libraries' artifact transforms, which Gradle deprecates
// ("undeclared_artifact_transform_input"). Restate it as the FileCollection itself (as portal-assistant does).
listOf("Debug", "Release", "DebugUnitTest").forEach { variant ->
  tasks.matching { it.name == "detekt$variant" }.configureEach {
    val kotlinc = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compile${variant}Kotlin")
    (this as dev.detekt.gradle.Detekt).classpath.setFrom(kotlinc.get().libraries)
  }
}
