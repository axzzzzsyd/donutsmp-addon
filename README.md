# DonutSMP Addon (Axz debug)

A Meteor Client addon for base hunting on DonutSMP. Includes four modules under the DonutSMP category.

## Modules

### 1. Chunk Finder
The core base-detection tool. DonutSMP anti-esp hides blocks below your feet while you fly, so this module does not try to scan inside hidden bases. Instead it scores each chunk using only the surface-visible evidence ABOVE the cut-off Y (default -49), since most DonutSMP bases live below Y=-49 and leave entrance traces visible from above:

Surface evidence that flags a hidden base:

- **Unnatural blocks** (planks, glass, bricks, slabs, doors, fences, obsidian, portals, wool, ...)
- **Containers** (chests, trapped chests, barrels, shulker boxes, hoppers, dispensers, droppers)
- **Light sources** (torches, soul torches, lanterns, glowstone, sea lanterns, jack o'lanterns, end rods, frog lights, glow lichen, campfires)
- **Workstations** (crafting tables, furnaces, blast furnaces, smokers, anvils, enchanting tables, brewing stands, smithing tables, looms, stonecutters, grindstones, cartography tables, fletching tables, lecterns, composters, cauldrons)
- **Crops / farms** (farmland, wheat, carrots, potatoes, beetroots, sugar cane, nether wart, melon/pumpkin stems, sweet berry bushes, hay bales, water source)
- **Shaft blocks** near the surface (ladders, scaffolding, vines, water, lava, obsidian, crying obsidian, iron bars, chains, hoppers, honey blocks, slime blocks) — players build these to drop into a hidden base
- **Markers** near the surface entrance (item frames, glow item frames, all wood signs and wall signs, banners, beds)
- **Cleared terrain** — counts (x,z) columns where the air column above the surface is unusually deep with the surface itself below sea level, which often indicates a dug-out shaft

Each indicator is weighted and capped per chunk so a single mega-chunk can't dominate. Chunks scoring above the configurable threshold are highlighted with a color-coded box:

- Yellow = low suspicion (default >= score 3, < mid threshold)
- Orange = medium suspicion (>= mid threshold, < high threshold)
- Red = high suspicion (>= high threshold)

How to use it on DonutSMP:

1. Fly along the surface at the height you want to scan (use-slice is on by default; slice-height controls thickness of the slab).
2. **surface-only** (on by default) clips the scan to the area above the **cutoff-y** (default -49) where the server actually sends block data.
3. The flagged chunks are the ones with surface evidence of a hidden base below — drop into them with a pickaxe orpitcher to investigate.

This is more accurate than the Glazed addon because it uses a multi-factor scoring system tuned for DonutSMP's anti-esp. You can tune every weight and threshold under the Indicators group.

### 2. Cluster Finder
Highlights fully grown amethyst clusters (methyst_cluster block, the final growth stage) in loaded chunks. This is one of the strongest indicators of a base on DonutSMP: many players run automated amethyst farms, leaving rows of clusters attached to budding amethyst. The module can also draw boxes around the source udding_amethyst blocks so you can spot farms at a glance.

Per-cluster highlights plus per-chunk highlight boxes for chunks containing at least a configurable count of fully grown clusters.

Supports all growth stages via a flag for filtering (only the final stage is the real indicator by default).

### 3. Stash Detector
Finds hidden stashes: chunks that contain a few containers but very few obvious building blocks. A normal base has lots of planks, stairs, doors, and torches around its chests. A stash has just chests in a hole in the ground, far from any base markers; this module specifically catches those by requiring >= N containers with <= M building blocks in the same chunk.

Individually outlines the container blocks (chests, arrels, shulker boxes, hoppers, dispensers, droppers, and optionally ender chests) within each flagged chunk so you can see where to dig.

### 4. New Chunk Detector
Persistent chunk log. Saves every chunk the player has ever loaded (per world, per dimension) to a JSON file in <minecraft>/donutsmp-addon/<worldId>.json. Highlights chunks you've never loaded before in a distinct color so you can:

- Quickly focus on unexplored terrain
- Avoid re-searching chunks you've already walked through
- Build a near-complete map of the server over time

This record survives across sessions and is keyed by world registry id + dimension, so different dimensions and different servers don't pollute each other.

## Installation

1. Make sure Meteor Client is installed (Fabric, Minecraft 1.21.11+).
2. Run gradlew build (or gradlew.bat build on Windows) - this downloads Gradle and the Wrapper files on first call.
3. The built JAR will appear in uild/libs/.
4. Drop the JAR into your Minecraft mods folder alongside Meteor Client.

## Development

Open the project in your IDE, run gradlew genSources to download Minecraft sources, then run the Minecraft Client run configuration.

## Notes

- Chunk scanning runs on a configurable tick throttle to keep performance reasonable. Lower escan-ticks to be more responsive, raise it if you hit fps drops.
- Scanning only inspects chunks that are loaded by the client. You'll need to fly/walk near a chunk to detect anything in it. Server-side redstone / sneak-hiding tricks can hide contents from a client-only scanner.
- All modules live under the DonutSMP category in the Meteor modules list.

## License

CC0-1.0 - public domain dedication.


## DonutSMP Anti-ESP Notes

DonutSMP runs an anti-ESP plugin that stops the server from sending block data for blocks far above or below the player while flying with an elytra or trident. This means block-scanner modules (ChunkFinder, ClusterFinder, StashDetector) only see the slice of the world the server actually sends.

To work around this:

- All scanning modules include a `use-slice` setting (on by default) which restricts the scan to a small horizontal slab centered on the player's current Y level. `slice-height` controls the thickness in blocks.
- Fly at the **exact height** you want to scan. The modules only check the slab around your feet.

  - Surface bases: fly around Y=-50 to Y=80
  - Underground / cave bases: descend slowly and fly at the cave ceiling height
  - Amethyst geodes: fly at Y=-30 to Y=-58 (geode spawning band)
- The `render-radius` / `scan-radius` settings control the horizontal radius around you, so you can sweep the server in long horizontal flight lines at each height you want to cover.


## Build

Run gradlew.bat build (Windows) or ./gradlew build (Unix) in the project root. Requires JDK 21 and Gradle 8.14+. The built JAR is in uild/libs/donutsmp-addon-0.1.0.jar.
