# TUI Home Header and Hero Design

## Intent

Adapt the TestPlugins home hook to give CloudStream's existing home header and hero carousel the compact TUI appearance requested by the user, without changing the CloudStream app source.

## Goals

- Show the active extension icon and name on the left and search on the right.
- Keep CloudStream's provider picker, search action, carousel data, and paging behavior.
- Hide the duplicate floating provider FAB while the header selector is active; restore its visibility on plugin unload.
- Give the hero a square-corner, thin-line frame and a 16:8.5 artwork area.
- Keep artwork full bleed with a bottom fade and bottom-start title and genre metadata.
- Hide rating and pager dots in the hero.
- Preserve and restore touched view state on plugin unload and lifecycle recreation.

## Non-goals

- Add a Compose `HomePane`; this app checkout has no Compose home pane.
- Modify CloudStream source or replace its home data pipeline.
- Change feed sections below the hero; only the duplicate floating provider FAB is hidden.

## Constraints

- The plugin attaches to an existing XML/ViewPager2 home header at runtime.
- Theme tokens are approximated with Android view colors and monospace type because the host views are not Compose.
- The extension icon URL comes from repository plugin metadata; a local fallback remains necessary when metadata is unavailable.
- Do not run a build; the user builds manually.

## Approaches

1. **Restyle existing native views (recommended):** hook the current header, pager, and metadata views. This keeps the native carousel and picker behavior and avoids adding Compose runtime dependencies to a plugin.
2. **Embed a Compose overlay:** adds Compose dependencies and duplicates part of the host UI while still relying on the native pager for data and paging. It is more fragile and does not provide a real `HomePane` inside this plugin.

## Proposed implementation

- Use `TuiBg #0A0A0C`, `TuiLine #26262E`, `TuiBright #F4F4F6`, `TuiDim #8A8A98`, and `TuiAccent #EAB308` where existing views permit.
- Use monospace typography with 16sp medium titles and 11sp medium labels.
- Use 14dp horizontal insets, 10dp vertical header padding, 18dp square provider/search icons, 4dp top/12dp bottom hero spacing, and 12dp horizontal/10dp vertical metadata insets with a 6dp title-to-genre gap.
- Use TuiAccent for header pressed/focus feedback while resting text and search icon remain TuiBright.
- Keep insets scoped to the header and hero so feed sections below do not shift.
- Render the active provider brand and native search action in the existing top row.
- Route header brand clicks through the native provider picker and hide the separate floating provider FAB to prevent duplicate selector controls.
- Style the existing ViewPager2 content to 16:8.5, with square corners, thin border, image crop, bottom gradient, title, and tags; suppress rating and page dots if present.
- Hide the native metadata container and render a TUI overlay that mirrors its current title and tags, leaving the native logo loader and source views untouched.
- Keep native pager selection and click actions intact.

## Implementation decision

The phone home XML provides a full-width 500dp hero `FrameLayout`, a ViewPager2, and a separate 100dp row of bookmark/play/info actions. The plugin hides that action row to match the specified slide, and hides the duplicate floating provider FAB while exposing its native picker from the header brand. The native title/tag source container is hidden beneath a new TUI overlay so CloudStream's logo loader continues updating source views without overriding the requested text presentation.
