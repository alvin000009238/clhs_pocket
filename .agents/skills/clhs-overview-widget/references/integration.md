# Integration recipes

All paths below are relative to `android/app/src/main/java/com/clhs/score/`. The examples use an opt-in repeatable `sample_metric` with a public `resourceId`; replace these example names with the actual product concept. They are insertion snippets, not an automated patch. Do not register the sample merely to use this skill.

## Registration and configuration

In `domain/overview/OverviewWidgets.kt`, add a variant **inside** `OverviewWidgetConfig`:

```kotlin
@Serializable
@SerialName("sample_metric")
data class SampleMetric(val resourceId: String = "school") : OverviewWidgetConfig
```

Add these branches to the existing matching and validation functions:

```kotlin
// OverviewWidgetInstance.config(): when (type)
"sample_metric" -> config is OverviewWidgetConfig.SampleMetric

// OverviewWidgetConfig.isValid(): when (this)
is OverviewWidgetConfig.SampleMetric -> resourceId.isNotBlank()
```

Use domain validation for real identifiers; nonblank is only sufficient for this example. In `OverviewWidgetDefinitions.all`:

```kotlin
OverviewWidgetDefinition(
    "sample_metric", "校園數值", "analytics",
    listOf(size(2, 1), size(2, 2), size(4, 1), size(4, 2)),
    size(2, 1), multiple = true,
),
```

For a no-settings widget, reuse `None` instead of adding a serializable config. Allow the new type in the `None` matching branch, the picker, and the configuration sheet. Decide `multiple` based on actual repeatability; see its layout coupling in `SKILL.md`.

In `ui/overview/OverviewDashboard.kt`:

1. Add `"sample_metric" -> OverviewWidgetConfig.SampleMetric()` to the picker's default-config `when`.
2. Add the type to the `onChoose` configuration-routing set if the user must choose settings before insertion. Without this, the widget is inserted immediately.
3. Give the picker preview its own sample label; otherwise it inherits the announcement preview fallback. Picker text is illustrative, not a network subscription.

In `ui/overview/OverviewWidgetConfigSheet.kt`, initialize a draft keyed by `widget.id`, add the controls and a `configuration` branch returning `SampleMetric` only when valid. The existing confirm path applies `widget.withConfig(configuration)` and the dashboard wraps insertion with reveal-on-add. Do not save from each input edit.

For settings-free types, the sheet can show a concise no-settings explanation and confirm `None`. Retain the existing config/menu route rather than leaving a known widget in the sheet's unsupported fallback.

Check all exhaustive `when` expressions on `OverviewWidgetConfig` after adding a variant:

```powershell
rg -n 'OverviewWidgetConfig|when \(.*config|when \(this\)' android/app/src/main/java android/app/src/test
```

## Source and rendering

If data already exists in `OverviewState`, derive the UI projection from it. A new repository is unnecessary for a new presentation of existing data.

For a genuinely new source, follow weather/YouBike in `domain/overview/OverviewCoordinator.kt`:

1. Select enabled, valid configs from the **draft-aware** widgets flow; deduplicate by public resource key and apply `distinctUntilChanged()`.
2. Observe only relevant sources while visible, using existing lifecycle ownership. Editing/reordering alone must not re-fetch unchanged resources.
3. Cancel obsolete resource subscriptions; propagate `CancellationException`. Isolate source failures so one widget cannot fail the dashboard. Preserve a last-good result when the source contract supports it.
4. Add a keyed projection to `OverviewState`. Sharing data for one resource must not collapse the separate widget UUIDs.
5. Add manual refresh input/actions to the existing owner only if needed. Initial emission/automatic polling must not be labeled manual. Existing weather uses refresh events with a silent `onStart`; YouBike's version flow has a silent first emission. Do not copy either approach without checking the new source's event contract.
6. Wire dependencies and callbacks through `OverviewViewModel`, `AuthenticatedApp`, `OverviewDashboard`, then `OverviewWidgetContent`. Inspect `OverviewViewModel.Factory` and its actual callers; there is no assumed app container file.
7. Provide deterministic Demo/preview data using the existing fake-data owners. Preserve the existing live-public-YouBike exception; do not extend it to unrelated sources by implication.

Copy `assets/MetricWidget.kt.template` into `ui/overview/`, rename `SampleMetricWidget` / `SampleMetricDisplay`, and call it from the new `OverviewWidgetContent` branch. Its display model is a UI projection: construct it from `OverviewState`, not from HTTP or persistence in the Composable. Pass the existing content `modifier`, `editing`, and an owner callback. For a non-refreshable widget replace/remove the refresh action and manual indicator rather than keeping a dead control.

The template adapts to physical constraints; `widget.size` can additionally choose semantic content. Do not assume `columns == 4` means a physically wide widget on a two-column phone. Avoid `fillMaxSize()` for a natural-height singleton; use `fillMaxWidth()` and content measurement there.

In `OverviewWidgetGrid`'s `hostShape` branch, include the new type if the UI uses `PocketVisual.metricShape`, so clipping, border and ripple share the silhouette. The grid supplies the edit menu, resize handle and actions; the content does not need its own copies.

If the icon is new, update `android/scripts/generate_material_symbol_subset.py` and regenerate the outline/filled subsets through its documented flow. Check actual glyph output; a known Material Symbol name does not guarantee the shipped subset includes it.

## Persistence and navigation

`OverviewLayoutRepository` already stores type, UUID, rank, size, enabled state, config version/string and `ownRow`. Adding a new type/config usually needs no new table, global settings key, layout format change or database migration. Do not reset users' existing layouts to add a picker option. Change `defaultOverviewWidgets` / legacy import only when product behavior explicitly requires it.

For navigation, use an existing `OverviewDestination` when appropriate. A new destination also requires its `AuthenticatedApp` handler and Navigation 3 route/back-stack integration; preserve unsaved-edit exit handling through the dashboard's `leave` wrapper. A content tap is disabled while editing. For an event list, preserve detail identity and existing read-state rules rather than routing every row to a generic list.

## Acceptance cases to select

Use cases relevant to the new contract, not a duplicate suite for every helper:

| Change | Required evidence |
| --- | --- |
| Type/config | Registry lookup, supported/default sizes, valid/invalid config, JSON round-trip; old unknown config stays opaque |
| Repeatable source | Same resource deduplicates, different resources remain independent, reorder does not fetch, removal/invisibility cancels |
| Manual refresh | Active manual status followed by terminal clear; first entry/poll/reentry silent; failure retains valid last value |
| Editor | Add/duplicate identity, cancel leaves committed layout, save/reopen keeps config and size; reveal on add |
| Private data | Guest/restoring isolation, account-generation changes reject old results |
| UI | Small/tall/wide variants, narrow/large-font phone, light/dark, empty/error/unknown/zero, clipping and reserved controls |

The source template is complete Kotlin; the contract-test template requires the actual registered config/type. Do not claim the sample widget is integrated because its standalone UI compiles.
