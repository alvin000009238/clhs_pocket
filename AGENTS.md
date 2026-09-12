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
- Use existing Material 3 Expressive components, font subsets and fake data. App copy stays `zh-TW`; prose documentation defaults to Traditional Chinese, agent instructions to English.

## Verification

Run commands from `android/` in PowerShell (`./gradlew` on Unix).

```powershell
# Android code delivery: one final combined warning gate
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --warning-mode all

# Focused security / ownership check when those boundaries change
.\gradlew.bat :app:testDebugUnitTest --tests 'com.clhs.score.ArchitectureBoundaryTest'

# R8, runtime dependency or packaging changes
.\gradlew.bat :app:assembleRelease --warning-mode all
```

- Run relevant owner tests while iterating; before delivery inspect Kotlin/compiler and Lint reports together, fix warnings in changed code, then rerun affected checks.
- For subject trends include `SubjectTrendStateOwnerTest` and `ScoreViewModelTest`.
- Documentation-only changes: check referenced paths, Markdown links and `git diff --check`; no Android build is needed.
- Do not run device/emulator tests, install APKs, capture Widget previews or generate Baseline Profiles unless the user explicitly requests device work. A build is not visual verification.
- The Android/Kotlin toolchain requires JDK 25. If Java is missing, locate a real JDK 25 installation and verify `lib/jvm.cfg`; do not hardcode a machine-specific installation suffix. Use workspace-local `GRADLE_USER_HOME` / `ANDROID_USER_HOME` when required by the environment.

## Release and documentation

- Before pushing an Android update to GitHub, finish the matching changelog section and obtain the user's review/confirmation. Compare with the previous release using Features, Bug Fixes and Performance Improvements.
- Documentation, artwork or workflow-only changes do not get an Android version bump.
- Keep signing material outside version control; `android/app/google-services.json` is the public Firebase app configuration, not a service-account key.
- Update the relevant linked guide when a durable contract changes. Keep this file a map, not a task log or feature specification.
- Markdown is ignored by default. Verify new documentation is included with `git check-ignore`; `AGENTS.md`, `DESIGN.md` and `docs/**` are explicitly allowed.
