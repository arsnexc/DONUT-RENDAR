# RenderUtil

A Fabric client utility mod for Minecraft **1.21.11**. The click GUI, block
catalogue search, local-world scan, HUD, and block markers are wired together
as a complete project; `.github/workflows/build.yml` builds the jars on pushes,
pull requests, and manual dispatch.

## Fair-play boundary

World scanning and world markers are deliberately **single-player/local-world
only**. They do not activate on multiplayer servers. This project does not
include server-fingerprint hiding, translation filters, packet hooks, or other
anti-detection behavior.

## Build

Use Java 21 or newer and run:

```sh
./gradlew build
```

The installable mod jar is written to `build/libs/renderutil-1.0.0.jar`.
The GitHub Actions workflow uploads the generated jars as the `renderutil-1.0.0`
artifact. The workflow uses the Gradle wrapper committed in this repository.

## In-game controls

| Key | Action |
| --- | --- |
| Right Shift | Open the module GUI |
| Z | Open block catalogue search |
| End | Panic: disable modules and clear scan results |

In the module GUI, left-click a module to toggle it, right-click to expand its
settings, and drag a panel header to reposition it. In block search, type to
filter the registry and click a row to add or remove that block from the local
search list.

The scan radius is configurable in `config/renderutil.json` (1–6 chunks). The
first launch creates the configuration with a four-chunk radius. Enabled scan
modules inspect up to two nearby chunks per client tick, starting with the
chunks closest to you. The HUD's `Scan coverage` line reports how many chunks in
the configured square are currently loaded client-side and how many have been
visited for the current player-chunk/radius window. Unloaded chunks are never
force-loaded; they are picked up when the client loads them.

Block-search markers are pruned when the selected range or block selection
changes, and refreshed when their chunk is rescanned. Unloaded chunks are cleared
when the scan queue checks them, and switching local levels clears all results.
The Activity Scan heat map uses structural/utility block tags and places each
hotspot at the observed Y level of its densest vertical section (it is still a
heuristic, not proof of player placement). ESP markers include bright
through-wall outlines so they remain legible behind blocks and when viewed from
inside a marker.
