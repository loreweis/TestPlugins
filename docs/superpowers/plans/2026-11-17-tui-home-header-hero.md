# TUI Home Header and Hero Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the existing CloudStream home header and native ViewPager2 hero to match the approved compact TUI design through the TestPlugins hook.

**Architecture:** Keep CloudStream's ViewPager2, data binding, picker, and search intact. Extend `ExamplePlugin` to restyle the header and attached `home_scroll_view` pages at runtime, observe page recycling/layout changes, and restore every changed property/listener on unload or view destruction.

**Tech Stack:** Kotlin, Android Views, CloudStream plugin API, AndroidX ViewPager2/RecyclerView.

**Spec:** `docs/superpowers/specs/2026-11-17-tui-home-header-hero-design.md`

## Global Constraints

- Scope changes to `TestPlugins`; do not edit `/storage/emulated/0/GitSync/clau`.
- Keep the native provider picker, search, hero data source, paging, and slide click behavior.
- Hide the duplicate floating provider FAB while the header selector is active; restore its original visibility on unload.
- Use TuiBg `#0A0A0C`, TuiLine `#26262E`, TuiBright `#F4F4F6`, TuiDim `#8A8A98`, and TuiAccent `#EAB308`.
- Use monospace text; title 16sp medium; labels 11sp medium.
- Use a 14dp horizontal inset, 10dp vertical header padding, 18dp square provider icon, 18dp search glyph, 4dp/12dp hero vertical spacing, 16:8.5 hero ratio, 1dp border, 12dp/10dp metadata insets, 6dp title-to-genre gap, and 0dp corner radius.
- Use TuiAccent `#EAB308` for header focus/pressed feedback; keep resting provider/search colors TuiBright.
- Do not add dependencies, run tests, or build; the user builds manually.
- Preserve and restore all modified views and listeners during plugin unload and lifecycle recreation.

## Review Focus

- **ViewPager2 pages attach after the hero frame is first styled:** install the page hook on existing and newly attached children.
- **ViewPager2 reuses a page for new content:** refresh title/tag text visibility and style after bind/layout without registering duplicate listeners.
- **No logo URL or no tags:** show the text title and allow the genre line to disappear cleanly.
- **Hero width changes on rotation/window resize:** recompute height from measured width and keep the 16:8.5 ratio.
- **Plugin unload while search is expanded or a page is attached:** cancel async icon work and restore original parent order, visibility, dimensions, drawables, text styles, and listeners.

## File Map

- Modify `ExampleProvider/src/main/kotlin/com/example/ExamplePlugin.kt`: header TUI styling, runtime hero sizing, page attach/rebind styling, async style/icon cleanup, and original-state restoration.
- Modify `ExampleProvider/build.gradle.kts`: increment plugin version for distribution.

---

### Task 1: Restyle the Home Header and Hero Frame

**Files:**
- Modify: `ExampleProvider/src/main/kotlin/com/example/ExamplePlugin.kt`
- Modify: `ExampleProvider/build.gradle.kts`

**Interfaces:**
- Consumes: existing `installHomeBrand(host, fragment, headerRoot)` lifecycle hook and `homeHeaderStates` restoration state.
- Produces: an idempotent TUI style application for the active-provider header and `home_preview_viewpager_text` frame; restoration runs from `beforeUnload()`.

- [x] Capture the original `home_header`, `home_padding`, `home_search`, `home_head_profile_padding`, `home_preview_viewpager_text`, and `home_preview_title_holder` view properties in a state object before styling.
- [x] Apply the TUI background and header spacing without changing any feed section below the hero.
- [x] Keep active provider brand on the left and native SearchView on the right; apply 10dp vertical header padding, set provider icon and search glyph to 18dp, and remove rounded corners from icons/fallbacks.
- [x] Use TuiAccent for pressed/focus feedback while resting provider/search content remains TuiBright.
- [x] Apply the TUI hero frame background/border, 14dp horizontal inset, 4dp top and 12dp bottom spacing, and recompute height as `width * 8.5 / 16` when the frame width changes.
- [x] Hide the bookmark/play/info action row so the hero uses the requested artwork and metadata layout; retain the provider selection and search actions.
- [x] Hide the duplicate `home_api_fab` visually while the header brand continues invoking its native picker click; restore its original visibility on unload. Show `None` without an icon when no provider is selected.
- [x] Register lifecycle cleanup for listeners and restore captured values from `beforeUnload()`.
- [x] Increment `ExampleProvider/build.gradle.kts` version from 21 to 22.
- [x] Run `git diff --check` and inspect that only TestPlugins source files changed; do not build or run tests.

### Task 2: Restyle Attached Hero Slides

**Files:**
- Modify: `ExampleProvider/src/main/kotlin/com/example/ExamplePlugin.kt`

**Interfaces:**
- Consumes: the styled ViewPager2 frame from Task 1 and its `home_preview_viewpager` child.
- Produces: idempotent page styling for each attached `home_scroll_view` item, tracked with weak references and cleaned up with the home view lifecycle.

- [x] Find the ViewPager2 internal RecyclerView and register an attach listener; immediately style children already attached.
- [x] Capture and replace each page's top/bottom shadow with the specified vertical gradient: transparent, transparent, black at 0.55 alpha, black at 0.92 alpha.
- [x] Hide the native metadata container and add a square-corner TUI overlay at bottom-start; mirror title/tag source text so asynchronous logo loading cannot replace the text presentation, while leaving native source views intact for unload.
- [x] Style overlay title at 16sp monospace medium, tags at 11sp monospace medium in TuiDim, with 12dp horizontal and 10dp vertical inset and a 6dp vertical gap.
- [x] Keep artwork full bleed with center crop, square corners, no score/rating, and no pager dots.
- [x] Reapply metadata styling when a recycled page's content/layout changes, while ensuring only one listener per page.
- [x] Restore page backgrounds, dimensions, overlay, source metadata visibility, and listeners when the page detaches, home view is destroyed, or plugin unloads.
- [x] Run `git diff --check` and inspect the final diff; do not build or run tests.

## Manual Device Review After User Build

- Confirm home header shows active provider icon/name left and search right; brand click still opens CloudStream's provider picker.
- Confirm hero has 16:8.5 geometry, square border, full-bleed artwork, bottom gradient, left-aligned title and genre, and no action row/rating/dots.
- Confirm the floating provider FAB is hidden while the header selector still opens the native picker; confirm unload restores its prior visibility.
- Swipe through multiple slides and confirm title/tags update as pages recycle.
- Rotate or resize, open and close search, switch themes, and unload/reload the plugin; confirm the native home returns without layout drift or crashes.
