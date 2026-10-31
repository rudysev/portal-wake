# Tooling: Lint, detekt, hooks, CI

Read before changing lint/detekt/ktlint config, git hooks, CI workflows, or dependencies.

## Android Lint
- Runs in `check` on debug + release, `warningsAsErrors` + `abortOnError`. No `lint-baseline.xml`.
- Disabled (network-dependent, fail on upstream releases): `AndroidGradlePluginVersion`, `GradleDependency`,
  `NewerVersionAvailable`. Also `Aligned16KB` (Portals run Android 9/10 on 4 KB pages; `app/build.gradle.kts`).
- Report: `app/build/reports/lint-results-debug.html`.

## detekt
- 2.0.0-alpha.6 (`dev.detekt`); alpha because detekt 1.x doesn't support AGP 9 (quality-tooling ticket 08 → stable).
- Type-resolved `detektMain` + `detektTest`, every variant, in `check`. Warnings as errors, no baseline.
- Config = defaults + `config/detekt/detekt.yml`: coroutine opt-ins (`GlobalCoroutineUsage`, `InjectDispatcher`,
  `SleepInsteadOfDelay`, `SuspendFunSwallowedCancellation`), TODO/FIXME/STOPSHIP comments forbidden, `@Composable`
  exempt from `FunctionNaming`.
- `detekt.yml` and the detekt block of `gradle.properties` are **identical in portal-commons, portal-wake,
  portal-assistant** — change all three together.
- Reports: `app/build/reports/detekt/`.
- Runs in its own process (`detekt.use.worker.api=true` in `gradle.properties`): in the Gradle daemon its type
  resolution kept reading the rebuilt classes jar as it was on the first run, so `detektDebugUnitTest` analysed tests
  against stale main classes and only warned "N compiler errors found during analysis" while `check` passed. That
  warning on a clean tree means type resolution is broken again: rerun with `detekt { debug = true }` to see why.

## Hooks (`.githooks/`)
- `pre-commit`: `ktlint` + `detekt-cli` (no type resolution, ~1 s) on staged `.kt`/`.kts`; `shellcheck` on staged
  `*.sh`, `*.command`, `.githooks/*`. Rejects on findings, never auto-fixes.
- Version pins (bump together): ktlint CLI must be **1.8.0** = kotlinter 5.7.0's bundled version
  (`gradle/libs.versions.toml`); `detekt-cli` must equal `DETEKT_VERSION` = the Gradle plugin's version.
- Install: `brew install ktlint shellcheck`; detekt-cli (Homebrew has only 1.x): unzip
  `https://github.com/detekt/detekt/releases/download/v2.0.0-alpha.6/detekt-cli-2.0.0-alpha.6.zip`, put `bin/` on `PATH`.
- `pre-push`: `./gradlew check`. `--no-verify` is the deliberate escape hatch.
- `.githooks/pre-commit` and `.shellcheckrc` are **identical copies across the three repos** — change together.
- ktlint config lives only in `.editorconfig`.

## CI (`.github/workflows/ci.yml`)
| Trigger | Jobs |
|---|---|
| PR to `main` | `build` (`./gradlew check assembleDebug`), `shellcheck`, `build-health` |
| push to `main`, `workflow_dispatch`, `portal-commons-green` dispatch | `build` only (`./gradlew assembleDebug test`) |

- Branch protection requires `shellcheck`, `build-health`, `build` — **keep those job names**.
- Checks out `portal-wake` + `portal-commons@main` side by side so `includeBuild("../portal-commons")` resolves.
- `build` and `build-health` first run `./fetch-hybrid-deps.sh --libs`: the sherpa-onnx AAR isn't on Maven Central.
- `shellcheck` job lints every tracked script (`provisioning/` end-user installer, `setup.sh`, `scripts/`, hooks);
  not part of `./gradlew check`. `.shellcheckrc` follows sourced files (`config.env`, `keyprov.sh`) relative to
  each script, so cross-file variables aren't flagged unused.
- `build-health` = `./gradlew buildHealth` (dependency-analysis plugin); fails on any advice (unused,
  used-but-undeclared, misconfigured deps). Not in `check`/hooks (slow). Analyses only `:app`; portal-commons has
  its own job. Run it after any dependency change; report:
  `build/reports/dependency-analysis/build-health-report.txt`.
