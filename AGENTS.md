# Repository guide

CLHS Pocket is a Traditional Chinese Android school app (`com.clhs.score`).
The product lives in one Kotlin / Compose app module; `:benchmark` measures it.
Read the affected source and callers before editing. Keep existing state owners.

## Repository map

Paths below are relative to the repository root.

| Work | Start here |
| --- | --- |
| App startup, locks, external intents | `android/app/src/main/java/com/clhs/score/MainActivity.kt` |
| Shell, routes, back stacks | `android/app/src/main/java/com/clhs/score/ui/ScoreApp.kt`, `android/app/src/main/java/com/clhs/score/ui/AuthenticatedApp.kt`, `android/app/src/main/java/com/clhs/score/ui/navigation/` |
| Feature screens and state | `android/app/src/main/java/com/clhs/score/ui/`, `android/app/src/main/java/com/clhs/score/viewmodel/` |
| Settings JSON transfer | `android/app/src/main/java/com/clhs/score/data/SettingsTransfer.kt`, `android/app/src/main/java/com/clhs/score/viewmodel/SettingsTransferViewModel.kt` |
| HTTP, cache, session, models | `android/app/src/main/java/com/clhs/score/data/` |
| Overview aggregation | `android/app/src/main/java/com/clhs/score/domain/overview/` |
| Background work and Widget | `android/app/src/main/java/com/clhs/score/reminders/`, `android/app/src/main/java/com/clhs/score/widget/` |
| Unit / framework tests | `android/app/src/test/`, `android/app/src/androidTest/` |
| Preview data | `android/app/src/main/java/com/clhs/score/data/FakeData.kt`, `android/app/src/main/java/com/clhs/score/ui/ScorePreviews.kt` |
| Build / release automation | `android/`, `.github/workflows/` |

## Read only the relevant guide

- Architecture and ownership: [overview](docs/architecture/overview.md), [modules](docs/architecture/modules.md), [data flow](docs/architecture/data-flow.md).
- Routes, Guest access, onboarding: [navigation](docs/architecture/navigation.md).
- Credentials, WebView, public clients: [school system](docs/architecture/school-system.md).
- Feature-specific invariants and regression-test entry points: [maintenance contracts](docs/architecture/maintenance-contracts.md). Read the affected section before changing persistence, reminders, schedule, Widget or integrations.
- UI work: [DESIGN.md](DESIGN.md), then the referenced theme and component source.
- Adding Overview dashboard widgets: use [clhs-overview-widget](.agents/skills/clhs-overview-widget/SKILL.md) for integration points and reusable UI/test templates. This is separate from the Android home-screen Glance Widget.
- Floating navigation, root reselect and overlay clearance: [navigation](docs/architecture/navigation.md), `ui/navigation/PocketFloatingNavigation.kt`, `NavigationOverlay.kt` and `PocketScaffold.kt`. Add clearance inside scroll content, not around its viewport; keep common child route state scoped to its top-level stack.
- Local tools: [setup](docs/development/setup.md), [building](docs/development/building.md), [testing](docs/development/testing.md), [fake data](docs/development/fake-data.md).
- Publishing: [release](docs/development/release.md) and `CHANGELOG.md`.

Source and tests establish current behavior. Reconcile stale documentation with them;
do not reintroduce an old implementation just to match a document.

## Boundaries to preserve

- Keep one Guest-first App shell and Navigation 3 state. `ScoreViewModel.authState` is the auth authority; use `AuthGate` for protected content.
- UI renders state. Overview consumes `OverviewState`; repositories and persistence stay outside Composables. Subject trends remain owned by `SubjectTrendStateOwner`.
- School requests use `prepareSession(session)` and the synchronized cookie jar; preserve the shared dispatcher limit of four. Public clients never borrow school credentials.
- Widget reads its dedicated snapshot and authority metadata, never credentials or school APIs. WebView receives only the unlocked in-memory session.
- Preserve revocation, cleanup retry and account-generation guards. Privacy deletion failures must propagate; never turn them into successful cleanup.
- Notification entry points retain one-use capabilities and the biometric gate. Keep secrets and personal data out of logs, examples and anonymous analytics.
- Use existing Material 3 Expressive components, font subsets and fake data. App copy stays `zh-TW`; the Overview destination is named「首頁」in user-facing copy. Prose documentation defaults to Traditional Chinese, agent instructions to English.

## Testing Strategy & Test Quality

- **Value over quantity:** protect meaningful regressions, not test counts, file ratios or arbitrary coverage targets. Coverage can reveal gaps; it cannot justify a test by itself.
- **Decide before adding:** identify the concrete failure and user impact, check existing protection, then weigh added confidence against execution/maintenance cost and a simpler verification. Add no test when existing checks are sufficient; a changed file does not require a matching new test file.
- **Match risk and layer:** use JVM tests for calculations, parsers, state transitions, cancellation, account isolation and storage policies. Use real temporary DataStore/files and MockWebServer for persistence/HTTP contracts; in-memory DAO tests do not prove Room transactions. Keep Android Keystore, Room rollback, Compose restoration, gestures and accessibility checks at the device layer. Simple copy/style changes usually need no new unit test.
- **Test observable contracts:** assert results, durable state, privacy boundaries and important side effects. Avoid private structure, incidental call counts and exact source spelling. Retain source guards where they protect otherwise untested Android wiring, Manifest/backup/R8 or forbidden dependencies; replace them only when equivalent protection exists.
- **Avoid redundancy:** inspect related tests first. Different layers may protect different failures. Existing tests may be merged, replaced or deleted, but identify the surviving protection for every important assertion before removal; never weaken assertions or exclude tests to obtain a pass.
- **Keep infrastructure minimal:** reuse current JUnit, coroutine-test, MockWebServer, TemporaryFolder and existing fakes. Keep helpers only when they remove repeated work or improve diagnostics; remove unused fixtures and dependencies. Do not introduce a framework for a simple case.
- **Make failures useful:** name the behavior and failure scenario. Use a shared test scheduler for owned coroutine timers and injected dates/clocks; assert both sides of important time boundaries. Real HTTP/thread integration needs explicit synchronization and bounded waits, not arbitrary sleeps. Always close servers, cancel/join owned jobs and restore global dispatchers/time zones.
- **Verify proportionally:** run focused owner tests during iteration, then the existing final combined warning gate below for Android code delivery. Broaden checks for integration/security/packaging risk; do not skip required verification to save time. Compare baseline/final results and distinguish actual execution from UP-TO-DATE or NO-SOURCE. Report blocked checks and product defects honestly.
- **Device work:** JVM test authoring does not require device exploration. For new or changed device tests, first explore the relevant interaction on the selected device or emulator with available tooling, then use semantic locators and explicit waits. Device operations still require the authorization and target rules below.
- **Maintain continuously:** review affected tests with feature changes for obsolete assumptions, duplication and flakiness; a whole-suite audit is not required for every edit. Preserve distinct migration, revocation, failed cleanup, stale-account, retry and lifecycle cases even when they look similar.

## Verification

Run commands from `android/` in PowerShell (`./gradlew` on Unix).

```powershell
# Android code delivery: one final combined warning gate
# Full Demo suite; --tests applies only to the Production task before it
.\gradlew.bat :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --tests 'com.clhs.score.FlavorConfigurationTest' :app:lintDemoDebug :app:assembleDemoDebug :app:assembleProductionDebug --warning-mode all

# Focused security / ownership check when those boundaries change
.\gradlew.bat :app:testDemoDebugUnitTest --tests 'com.clhs.score.ArchitectureBoundaryTest'

# R8, runtime dependency or packaging changes
.\gradlew.bat :app:assembleProductionRelease --warning-mode all
```

- Run relevant owner tests while iterating; before delivery inspect Kotlin/compiler and Lint reports together, fix warnings in changed code, then rerun affected checks.
- For subject trends include `SubjectTrendStateOwnerTest` and `ScoreViewModelTest`.
- Documentation-only changes: check referenced paths, Markdown links and `git diff --check`; no Android build is needed.
- Do not run device/emulator tests, install APKs, capture Widget previews or generate Baseline Profiles unless the user explicitly requests device work. A build is not visual verification.
- Before `connected*AndroidTest`, pin the emulator with `ANDROID_SERIAL` and verify the reported target. Runner arguments such as `android.testInstrumentationRunnerArguments.serial` do not select a device. Gradle device tests can uninstall the app and reset its data; do not run them on a user's phone for visual review. Use an in-place APK update for visual review.
- When device work is explicitly requested, use available device/emulator tooling for interaction and evidence collection. Follow `docs/development/testing.md`; keep ADB/device selection explicit and use fake data unless the user authorizes real account data.
- The Android/Kotlin toolchain requires JDK 25. If Java is missing, locate a real JDK 25 installation and verify `lib/jvm.cfg`; do not hardcode a machine-specific installation suffix. Use workspace-local `GRADLE_USER_HOME` / `ANDROID_USER_HOME` when required by the environment.

## Release and documentation

- Before a formal production release, finish the matching changelog section and obtain the user's review/confirmation. Compare with the previous release using Features, Bug Fixes and Performance Improvements. Reviewed dev merges may automatically distribute internal productionRelease builds without a new formal release approval each time.
- `android-release.yml` owns dev Firebase distribution and main stable-tag GitHub Releases. Preserve its exact shared `GITHUB_RUN_NUMBER`, one non-cancelling queue, both complete history checks and CI-before-signing order. Follow [release](docs/development/release.md) for Firebase credentials, branch protection and same-package upgrade limits.
- Release builds consume committed Baseline and Startup Profiles in `android/app/src/productionRelease/generated/baselineProfiles/`. Keep automatic generation disabled and validate both sources before decoding signing material. The separate manual `android-baseline-profiles.yml` generates the pair for human review and commit; it does not publish or merge.
- Documentation, artwork or workflow-only changes do not get an Android version bump.
- `demoDebug` is the daily fake-data build, except YouBike uses the public live API in both flavors; Compose previews and isolated YouBike tests retain fake data. `productionDebug` uses real data; only `productionRelease` is published. Demo never uses production Firebase or release signing. Keep signing material outside version control; `android/app/google-services.json` is the public production Firebase app configuration, not a service-account key.
- Update the relevant linked guide when a durable contract changes. Keep this file a map, not a task log or feature specification.
- Markdown is ignored by default. Verify new documentation is included with `git check-ignore`; `AGENTS.md`, `DESIGN.md` and `docs/**` are explicitly allowed.
