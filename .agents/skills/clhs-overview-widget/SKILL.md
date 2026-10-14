---
name: clhs-overview-widget
description: Add or extend CLHS Pocket Overview dashboard widgets in the existing Compose grid, including per-instance configuration, data ownership, responsive sizes, and editor integration. Use for new Overview widget types, not Android home-screen Glance Widgets.
---

# Add an Overview widget

Work from the repository root containing `AGENTS.md` and `android/`. Read the current source before applying the examples; this skill documents integration points, not a replacement widget framework.

## Locate the actual path

Read `AGENTS.md`, `DESIGN.md`, and the Overview section in `docs/architecture/maintenance-contracts.md`.

| Responsibility | Source under `android/app/src/main/java/com/clhs/score/` |
| --- | --- |
| Registry, sizes, config validation, default layout, packing | `domain/overview/OverviewWidgets.kt` |
| UI state and destinations | `domain/overview/OverviewModels.kt` |
| Source aggregation and cancellation | `domain/overview/OverviewCoordinator.kt` |
| Committed/draft editor state and refresh actions | `viewmodel/OverviewViewModel.kt` |
| Atomic layout persistence | `data/OverviewLayoutRepository.kt` |
| Picker, configuration routing, reveal-on-add | `ui/overview/OverviewDashboard.kt` |
| Configuration draft and confirmation | `ui/overview/OverviewWidgetConfigSheet.kt` |
| Type-to-content dispatch | `ui/overview/OverviewWidgetContent.kt` |
| Measurement, drag, resize, edit overlay, accessibility actions | `ui/overview/OverviewWidgetGrid.kt` |
| Dependency wiring, lifecycle, destination routing | `ui/AuthenticatedApp.kt` |

Trace `AuthenticatedApp → OverviewDashboard → OverviewWidgetGrid → OverviewWidgetContent`. `OverviewScreen` also contains reused components and a legacy path; adding content there alone does not add a dashboard widget.

## Choose the smallest integration

- Reuse existing `OverviewState` data when possible. Add a source only when the requested content needs it; keep `repository → OverviewCoordinator → OverviewState → UI` ownership.
- Choose a stable persisted type key, zh-TW title, existing Material Symbol ligature, supported sizes, default size, and whether instances can repeat.
- **Current coupling:** `multiple=true` enables duplicate instances, fixed cell measurement, size menu, and the bottom-right resize handle. `multiple=false` means singleton, full-width/natural-height content; listed sizes do not make it resizable. Do not flip this flag only to obtain a resize handle. If the product needs a resizable singleton, explicitly address this coupling with focused changes and tests.
- New widgets appear in the picker automatically from the registry, but rendering/config validation are explicit branches. Keep new types opt-in unless the requested product behavior calls for a new default.

Read [integration.md](references/integration.md) for the exact registration/config/source checklist and snippets. Copy [MetricWidget.kt.template](assets/MetricWidget.kt.template) for a repeatable numeric widget; adapt the name, hierarchy, and display projection. For a singleton/list, reuse `OverviewSection` and the existing segmented list pattern instead of forcing a fixed-height metric card.

## Preserve editor and data contracts

- Stable UUID identity belongs to `OverviewWidgetInstance.id`; resource identity belongs to config. Same-resource instances can share source data without sharing instance IDs.
- Add config type matching in `config()` and validation in `isValid()`. Unknown types, unknown config versions, and opaque configuration must round-trip unchanged. Keep existing type keys/serial names stable; a new JSON config variant alone does not require a Room schema bump.
- Configuration confirms into `putWidget(widget.withConfig(...))`; cancel changes nothing. Only `saveLayout()` commits. Preserve instance ID, size, enabled state and `ownRow` when updating config. `ownRow` is a layout outcome, not a user option.
- Use the dashboard's `putWidget` wrapper when adding or re-enabling so the measured position is revealed. Calling `actions.putWidget` directly there skips reveal-on-add.
- Let the grid own dragging, resizing, press/edit interception, and accessibility actions. Do not put a competing long-press/drag detector or an inner vertical scroller on a widget. Existing event-row long holds must not enter edit mode.
- Configuration contains public preferences/resource IDs, never credentials, API keys, session objects, or fetched private data. Reuse secure settings owners for credentials.
- Public content remains available to Guest. Private content uses the existing auth gate and generation isolation; clearing a UI field is not sufficient isolation.

## Design for live geometry

Render from current `widget.size` **and actual constraints**: the grid can clamp width on small phones, and the drag overlay has continuously changing dimensions. Never cache size with an unkeyed `remember`, wait for pointer-up to swap content, or add a second size animation that lags the pointer.

Keep the main value useful at the smallest size. Add details as space permits; distribute useful content through taller variants without large blank bottoms. Reserve the top-right edit menu/refresh indicator and bottom-right resize handle. Use `MaterialTheme`, `PocketVisual`, `PocketMotion`, and `hapticClick`; if using `PocketVisual.metricShape`, align the host shape branch too. Verify new icon ligatures exist in both generated font subsets.

For refreshable public metrics, manual clicks use `hapticClick` and a top-right official Material 3 `LoadingIndicator`. Entry, visibility changes and automatic polls remain silent. Model request origin in the state owner, not a Composable-local timer; end manual status on success, failure, cancellation or leaving the route. Keep last good values on failure, distinguish unknown from zero, and expose a concise retry hint. Do not add fake delays to make a spinner visible.

Use a local Shimmer skeleton only while initially waiting for data without a usable snapshot. Match the value/list layout and retain known titles; reuse `ui/overview/OverviewLoading.kt` and `rememberLoadingShimmerProgress`. Existing data stays visible during refresh, including its initial source emission; errors, confirmed empty results and missing configuration are terminal UI, not skeletons. Create the shimmer transition only inside the skeleton branch and expose one loading description rather than fake values to accessibility.

## Verify the changed contract

Use [WidgetContractTest.kt.template](assets/WidgetContractTest.kt.template) for registration/config/persistence coverage; adapt it to the actual config. Existing targeted tests live under `android/app/src/test/java/com/clhs/score/`:

- `domain/overview/OverviewWidgetLayoutTest.kt`: sizes, packing, unknown entries and defaults.
- `domain/overview/OverviewWidgetCoordinatorTest.kt`: source deduplication, visibility, refresh isolation.
- `viewmodel/OverviewEditorTest.kt`: drafts, duplicate/singleton handling, cancellation and save.
- `data/OverviewLayoutRepositoryTest.kt`: atomic persistence and restoration.

Run relevant owner tests, then the final Android gate in `AGENTS.md`. Check compiler warnings and Lint together.

Device work requires explicit authorization. When authorized, select the target device or emulator and use available tooling to explore interactions and collect evidence. Check all supported sizes, narrow phone/large text, light/dark, add/reveal, duplicate/config/cancel/save/reopen, live resize/reflow, repeated drag and two-finger scrolling. For refreshable widgets, check manual indicator and silent entry/poll/reentry. Keep the target serial explicit; avoid instrumentation resets on a user's phone. If the user requests shutdown, shut down the selected emulator and verify it is off. Report build and visual evidence separately.
