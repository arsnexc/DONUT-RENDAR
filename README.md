# RenderUtil

A Fabric client utility mod for Minecraft **1.21.11**. The click GUI, registry
block search, loaded-chunk scanner, HUD, and local-world markers are wired
as a complete project. The supported development setup is Java 21,
Fabric Loader **0.19.5**, Fabric API **0.141.6+1.21.11**, and official Mojang
mappings.

## Fair-play boundary

World scanning and world markers are deliberately **single-player/local-world
only**. They use the existing `hasLocalWorld()` / `hasSingleplayerServer()`
gate and do not activate on multiplayer servers. RenderUtil does not include
packet hooks, mixins, server fingerprinting, translation filters, or other
anti-detection behavior. Activity Scan reports structural signal estimates,
not proof that a player is or was present.

## Build and regression checks

Use Java 21 and run:

```sh
./gradlew build
```

The build includes a dependency-free regression harness for scan ordering,
coverage counting, result pruning, and world changes. It can also be run alone:

```sh
./gradlew regressionTest
```

The installable mod jar is written to `build/libs/renderutil-1.0.0.jar`. The
GitHub Actions **Build** workflow runs the regression checks and build on pushes,
pull requests, and manual dispatch, then uploads the generated jars as the
`renderutil-1.0.0` artifact.

## In-game controls

| Key / input | Action |
| --- | --- |
| Right Shift | Open the module GUI |
| Z | Open Block Search |
| H (in the module GUI) | Open HUD and marker appearance settings |
| End | Panic: disable modules and clear scan results |
| Left-click a module | Toggle the module (only in a local single-player world) |
| Right-click a module | Expand or collapse its settings |
| Left/right-click a setting | Increase/decrease a number; toggle a boolean on left-click |
| Drag a module header | Reposition that GUI panel for the current screen |

### Per-feature scanning and coverage

Expand any module to control its **Scan** switch and **Radius** independently.
Each radius is a square window of 1–6 chunks around the player; the default is
four chunks. The scanner is shared and paced at two chunk scan jobs per client
tick, round-robin across active features, with the nearest chunks first. Each
feature has its own loaded and visited counters. The GUI shows that feature's
coverage under its module; the HUD can show it in stacked or compact layout.

`Loaded` is how many chunks in that feature's window are currently in the client
cache. `Visited` is how many chunks that feature has scanned at least once in
the current player-chunk/radius window. These counts are intentionally distinct:
a chunk can be loaded but not visited yet, and each feature advances its own
coverage. Unloaded chunks are never requested or force-loaded; they can be
scanned only after Minecraft loads them. Turning a feature's scan switch off
clears that feature's results. Changing the player window or radius prunes
results outside the new window. Leaving a local level or changing dimensions
clears all scan data.

Block Search also has a **Range** setting (16–256 blocks). Results must be both
inside that range and inside Block Search's configured chunk window, so the
loaded-chunk window can further limit the results. Block Search reports the
loaded/visited window in its screen; no chunk-loading workaround is used.

### Block Search

Open the screen with **Z**. Search by display name or namespaced block ID. The
**All**, **Selected**, and **Found** filters narrow the catalogue; clicking a
catalogue row adds/removes that block ID. Save a preset by entering a name and
pressing **Save**; use the arrows and **Load** / **Delete** to manage saved
presets. Presets contain the selected registry IDs, not world results.

The nearby-results list is filtered by the search query and sorted nearest
first. Click a coordinate to copy `x y z` to the clipboard. Use **N** to cycle
through nearby coordinates and **C** to copy the current coordinate. The screen
lists up to 512 nearby matches, with a scrollable view. If a block is absent,
check the selected IDs, Block Search's range/radius, and the per-feature coverage:
only currently loaded chunks inside the configured window are searched.

### HUD and markers

From the module GUI, press **H** or click **Appearance**. HUD options include
visibility, stacked/compact layout, one of four screen corners, coverage display,
text color, and accent color. Marker styling is configurable separately for
Container ESP, Spawner ESP, Block Search, and Activity Scan: fill color, fill
opacity, outline color, outline width, and a **through-wall outline** switch.
Through-wall rendering is a local visual preference and applies only to the
marker outline; it does not change scanning or the local-world restriction.

### Activity Scan

Activity Scan scores loaded map cells from configurable storage, workstation,
building, redstone, and home/decor signals. Each signal group has an independent
weight. **Noise floor** requires a minimum number of recognized signals in a
cell; **Score threshold** filters the weighted estimate; **Map cell** selects a
16–64 block horizontal cell width. Results are split into 16-block vertical
slices so the displayed Y coordinate corresponds to the observed cell section.
The HUD and markers label these as activity estimates: structures and utility
blocks are only heuristic signals and do not establish player placement.

## Configuration and migration

The client config is `config/renderutil.json`. Current files use
`schemaVersion: 2` and store:

- per-module scan enabled/radius controls and module setting values;
- selected block registry IDs and named Block Search presets;
- HUD visibility, layout, corner, and colors;
- per-feature marker fill/outline colors, fill opacity, outline width, and
  through-wall-outline preference.

The former unversioned config contained only `scanRadius`. On load, RenderUtil
migrates that value (clamped to 1–6) into each module's scan radius, uses the
new defaults for other preferences, and rewrites the file in version 2 format.
Module enable toggles are deliberately not restored on startup; modules still
have to be enabled in a local single-player world. This keeps scan activation
behind the in-world gate.

## Candidate 2.0 roadmap — implemented scope

- Independent scan switches, radii, and per-feature loaded/visited coverage.
- Versioned settings with migration from the original radius-only config.
- Saved block presets, catalogue/result filters, and coordinate copy/cycling.
- Custom HUD layout/colors and per-feature marker styles.
- Weighted, noise-filtered, height-aware Activity Scan estimates.
- Scan regression checks and the manual checklist below.

## Manual in-game test checklist

These checks are provided for a human in-game verification pass; they are not
claimed as completed by the build workflow.

- [ ] Start a local single-player world. Confirm modules cannot be enabled from
a menu or multiplayer world and that HUD/markers never appear on a remote world.
- [ ] Enable two scan modules with different radii. Confirm each coverage row
uses its own window, `loaded` can exceed `visited`, and visited counts advance
independently while the shared scanner remains paced.
- [ ] Turn one module's Scan switch off. Confirm its results clear while another
feature continues scanning; shrink its radius and confirm outside markers prune.
- [ ] Walk across chunk boundaries and switch dimensions/worlds. Confirm coverage
restarts for the new window and results from the previous level disappear.
- [ ] In Block Search, choose block IDs, use All/Selected/Found filters, save and
reload a named preset, and verify selected IDs persist after restarting.
- [ ] Check Block Search's loaded-only radius/range message. Click a nearby
coordinate, cycle with N, and copy with C; verify the clipboard contains `x y z`.
- [ ] Change HUD layout, corner, visibility, and colors. Confirm settings persist
and the HUD coverage lines match each module's GUI coverage.
- [ ] Change each marker fill/outline color, opacity, width, and through-wall
outline setting. Confirm the toggle affects outlines only and stays local.
- [ ] Change Activity Scan weights, noise floor, threshold, and cell size. Confirm
its map updates and labels remain estimates rather than player-placement claims.
- [ ] Back up an old radius-only `renderutil.json`, launch once, and confirm it
is rewritten with `schemaVersion: 2` and the old radius copied to all modules.
