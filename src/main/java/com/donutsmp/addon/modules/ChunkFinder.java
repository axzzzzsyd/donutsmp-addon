package com.donutsmp.addon.modules;

import com.donutsmp.addon.DonutSMPAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChunkFinder - Scans loaded chunks for base indicators and highlights
 * suspicious chunks. Bases on DonutSMP often leave traces such as
 * unnatural blocks, light sources, containers, and processed terrain.
 * Better than Glazed: multi-factor scoring per chunk, async-friendly,
 * thresholds tunable per indicator.
 */
public class ChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgIndicators = settings.createGroup("Indicators");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Radius in chunks around the player to scan (r = 0 means only the current chunk).")
        .defaultValue(4)
        .range(0, 8)
        .sliderRange(0, 8)
        .build()
    );

    private final Setting<Integer> minScore = sgGeneral.add(new IntSetting.Builder()
        .name("min-score")
        .description("Minimum chunk score to be flagged as a possible base.")
        .defaultValue(3)
        .range(1, 50)
        .sliderRange(1, 50)
        .build()
    );

    private final Setting<Integer> rescanTicks = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-ticks")
        .description("How often (in ticks) to re-scan chunks. Lower = more responsive but heavier.")
        .defaultValue(40)
        .range(10, 200)
        .sliderRange(10, 200)
        .build()
    );

    // Indicator toggles & weights
    private final Setting<Boolean> checkUnnatural = sgIndicators.add(new BoolSetting.Builder()
        .name("unnatural-blocks")
        .description("Flag chunks containing crafted / placed blocks (planks, glass, bricks, etc.) that show on the surface above a hidden base.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> unnaturalWeight = sgIndicators.add(new IntSetting.Builder()
        .name("unnatural-weight")
        .description("Score added per unnatural block found (capped per chunk).")
        .defaultValue(1)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> checkContainers = sgIndicators.add(new BoolSetting.Builder()
        .name("containers")
        .description("Flag chunks containing chests, barrels, shulker boxes, hoppers, etc. near a base entrance.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> containerWeight = sgIndicators.add(new IntSetting.Builder()
        .name("container-weight")
        .description("Score added per container block found.")
        .defaultValue(5)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkLight = sgIndicators.add(new BoolSetting.Builder()
        .name("light-sources")
        .description("Flag chunks containing torches, lanterns, glowstone, etc. (often placed around shaft entrances).")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> lightWeight = sgIndicators.add(new IntSetting.Builder()
        .name("light-weight")
        .description("Score added per light source found.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkTools = sgIndicators.add(new BoolSetting.Builder()
        .name("workstations")
        .description("Flag chunks with crafting tables, furnaces, anvils, enchanting tables, etc.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> toolWeight = sgIndicators.add(new IntSetting.Builder()
        .name("workstation-weight")
        .description("Score added per workstation block found.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkCrops = sgIndicators.add(new BoolSetting.Builder()
        .name("crops-farms")
        .description("Flag chunks containing farmland, crops, sugar cane, or animal-farm blocks.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> cropWeight = sgIndicators.add(new IntSetting.Builder()
        .name("crop-weight")
        .description("Score added per farm-related block found.")
        .defaultValue(2)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    // Surface-access indicators (only these survive the DonutSMP anti-esp cut-off)
    private final Setting<Boolean> checkShaftBlocks = sgIndicators.add(new BoolSetting.Builder()
        .name("shaft-blocks")
        .description("Flag ladders, scaffolding, vines, water, lava, and obsidian placed above a base shaft.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> shaftWeight = sgIndicators.add(new IntSetting.Builder()
        .name("shaft-weight")
        .description("Score added per shaft-style block found.")
        .defaultValue(4)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkMarkers = sgIndicators.add(new BoolSetting.Builder()
        .name("markers")
        .description("Flag item frames, signs, banners, and beds near base entrances.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> markerWeight = sgIndicators.add(new IntSetting.Builder()
        .name("marker-weight")
        .description("Score added per marker block found.")
        .defaultValue(3)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkClearedTerrain = sgIndicators.add(new BoolSetting.Builder()
        .name("cleared-terrain")
        .description("Flag chunks where the surface is missing natural ground (no grass/dirt/stone/sand in the top layer). Counts air-gaps near sea level.")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> clearedWeight = sgIndicators.add(new IntSetting.Builder()
        .name("cleared-weight")
        .description("Score added per surface air-gap block found.")
        .defaultValue(1)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> checkWasLoaded = sgIndicators.add(new BoolSetting.Builder()
        .name("ignore-current")
        .description("Don't flag the chunk the player is currently standing in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useSlice = sgGeneral.add(new BoolSetting.Builder()
        .name("use-slice")
        .description("Only scan a horizontal slab around the player's Y level. Works around DonutSMP anti-esp: the server only sends block data near your current Y, so fly at the height you want to scan.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> sliceHeight = sgGeneral.add(new IntSetting.Builder()
        .name("slice-height")
        .description("Vertical thickness (in blocks) of the scanned slab centered on the player's Y. Smaller = faster, more targeted.")
        .defaultValue(16)
        .range(4, 256)
        .sliderRange(4, 256)
        .build()
    );

    private final Setting<Boolean> surfaceOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("surface-only")
        .description("Only scan above the cutoff Y (since DonutSMP anti-esp hides everything below). The scan still surfaces the holes/entrances that show a base below.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> cutoffY = sgGeneral.add(new IntSetting.Builder()
        .name("cutoff-y")
        .description("Lowest Y level to scan when surface-only is enabled. DonutSMP anti-esp hides everything below this; default -49.")
        .defaultValue(-49)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    // Render
    private final Setting<SettingColor> lowColor = sgRender.add(new ColorSetting.Builder()
        .name("low-score-color")
        .description("Color used for low-suspicion flagged chunks.")
        .defaultValue(new SettingColor(255, 255, 0, 80))
        .build()
    );
    private final Setting<SettingColor> midColor = sgRender.add(new ColorSetting.Builder()
        .name("mid-score-color")
        .description("Color used for medium-suspicion flagged chunks.")
        .defaultValue(new SettingColor(255, 128, 0, 120))
        .build()
    );
    private final Setting<SettingColor> highColor = sgRender.add(new ColorSetting.Builder()
        .name("high-score-color")
        .description("Color used for high-suspicion flagged chunks.")
        .defaultValue(new SettingColor(255, 0, 0, 180))
        .build()
    );
    private final Setting<Integer> midThreshold = sgRender.add(new IntSetting.Builder()
        .name("mid-threshold")
        .description("Score above which a chunk uses the mid color.")
        .defaultValue(8)
        .range(1, 200)
        .sliderRange(1, 200)
        .build()
    );
    private final Setting<Integer> highThreshold = sgRender.add(new IntSetting.Builder()
        .name("high-threshold")
        .description("Score above which a chunk uses the high color.")
        .defaultValue(20)
        .range(2, 500)
        .sliderRange(2, 500)
        .build()
    );
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the chunk boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Set<ChunkScore> flagged = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    public ChunkFinder() {
        super(DonutSMPAddon.CATEGORY, "chunk-finder", "Highlights chunks that may contain a base below ground. DonutSMP anti-esp hides blocks below your feet, so this module scores chunks using only the surface-visible evidence above the cut-off Y (default -49): light sources, container/workstation hints, shaft blocks (ladders, scaffolding, obsidian, water), markers (signs, banners, beds), and cleared-terrain air gaps. Fly around the surface and the flagged chunks are the ones worth dropping into to inspect.");
    }

    @Override
    public void onDeactivate() {
        flagged.clear();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        tickCounter++;
        if (tickCounter >= rescanTicks.get()) {
            tickCounter = 0;
            scan();
        }

        for (ChunkScore cs : flagged) {
            int cx = cs.x;
            int cz = cs.z;
            int minX = cx << 4;
            int minZ = cz << 4;
            int maxX = minX + 16;
            int maxZ = minZ + 16;

            SettingColor color;
            if (cs.score >= highThreshold.get()) color = highColor.get();
            else if (cs.score >= midThreshold.get()) color = midColor.get();
            else color = lowColor.get();

            // Full-height box from world bottom to a reasonable build height
            AABB box = new AABB(minX, mc.level.getMinBuildHeight(), minZ, maxX, mc.level.getMinBuildHeight() + 128, maxZ);
            event.renderer.box(box, color, color, shapeMode.get(), 0);
        }
    }

    private void scan() {
        flagged.clear();
        if (mc.level == null || mc.player == null) return;

        int centerCX = mc.player.blockPosition().getX() >> 4;
        int centerCZ = mc.player.blockPosition().getZ() >> 4;
        int radius = renderDistance.get();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerCX + dx;
                int cz = centerCZ + dz;

                if (checkWasLoaded.get() && dx == 0 && dz == 0) continue;

                if (!mc.level.isChunkLoaded(cx, cz)) continue;

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                int score = scoreChunk(chunk);
                if (score >= minScore.get()) {
                    flagged.add(new ChunkScore(cx, cz, score));
                }
            }
        }
    }

    private int scoreChunk(LevelChunk chunk) {
        int score = 0;
        int unnaturalCount = 0;
        int containerCount = 0;
        int lightCount = 0;
        int toolCount = 0;
        int cropCount = 0;
        int shaftCount = 0;
        int markerCount = 0;
        int clearedCount = 0;
        // cap to avoid runaway values for very dense chunks
        int cap = 64;

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minY, maxY;
        // Y bounds: respect DonutSMP anti-esp cut-off unless the user opted out of slice mode.
        if (useSlice.get() && mc.player != null) {
            int pY = mc.player.blockPosition().getY();
            int half = sliceHeight.get() / 2;
            minY = Math.max(mc.level.getMinBuildHeight(), pY - half);
            maxY = Math.min(mc.level.getMaxY(), pY + half);
        } else {
            minY = mc.level.getMinBuildHeight();
            maxY = Math.min(mc.level.getMaxY(), minY + 256);
        }
        // Surface-only mode: clip the slab so it never dips below the anti-esp cut-off.
        // Bases hide below `cutoffY` (default -49) so we only scan above it for surface evidence.
        if (surfaceOnly.get()) {
            minY = Math.max(minY, cutoffY.get() + 1);
            if (minY > maxY) return 0; // player's slab sits entirely below cut-off; nothing visible
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                // Cleared-terrain check: surface should have grass/dirt/stone/sand/gravel/etc.
                // The surface block at this (x,z) sits one above the highest non-air block in the slab.
                if (checkClearedTerrain.get()) {
                    int topY = -1;
                    for (int y = maxY; y >= minY; y--) {
                        pos.set(x, y, z);
                        BlockState s = chunk.getBlockState(pos);
                        if (!s.isAir()) { topY = y; break; }
                    }
                    if (topY >= 0 && topY < 80) {
                        // An air gap below a low top usually means the surface was dug into.
                        // Specifically: if the top air-visible block is below sea level (63) but the
                        // (topY+1..maxY) range was sky-filled, that's just a hole — often a base shaft.
                        // We count: top of solid surface is well below slab top, with a deep air column above.
                        int aircol = (maxY - topY);
                        if (aircol >= 8 && topY < 60) clearedCount++;
                    }
                }

                // Per-block indicator scan
                boolean bailColumn = false;
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);

                    if (state.isAir()) continue;

                    if (checkContainers.get() && isContainer(state)) {
                        containerCount++;
                    } else if (checkLight.get() && isLightSource(state)) {
                        lightCount++;
                    } else if (checkTools.get() && isWorkstation(state)) {
                        toolCount++;
                    } else if (checkCrops.get() && isFarmBlock(state)) {
                        cropCount++;
                    } else if (checkShaftBlocks.get() && isShaftBlock(state)) {
                        shaftCount++;
                    } else if (checkMarkers.get() && isMarker(state)) {
                        markerCount++;
                    } else if (checkUnnatural.get() && isUnnatural(state)) {
                        unnaturalCount++;
                    }

                    if (containerCount + lightCount + toolCount + cropCount + unnaturalCount + shaftCount + markerCount > cap * 7) { bailColumn = true; break; }
                }
                if (bailColumn) break;
            }
        }

        // weighted, each indicator capped so one mega-chunk doesn't dominate
        score += Math.min(unnaturalCount, cap) * unnaturalWeight.get();
        score += Math.min(containerCount, cap) * containerWeight.get();
        score += Math.min(lightCount, cap) * lightWeight.get();
        score += Math.min(toolCount, cap) * toolWeight.get();
        score += Math.min(cropCount, cap) * cropWeight.get();
        score += Math.min(shaftCount, cap) * shaftWeight.get();
        score += Math.min(markerCount, cap) * markerWeight.get();
        score += Math.min(clearedCount, 64) * clearedWeight.get();
        return score;
    }

    private boolean isShaftBlock(BlockState s) {
        return s.is(Blocks.LADDER) || s.is(Blocks.SCAFFOLDING) || s.is(Blocks.VINE)
            || s.is(Blocks.WATER) || s.is(Blocks.LAVA)
            || s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN)
            || s.is(Blocks.IRON_BARS) || s.is(Blocks.CHAIN)
            || s.is(net.minecraft.world.level.block.Blocks.HOPPER) // funnels feeding shaft drops
            || s.is(Blocks.HONEY_BLOCK) || s.is(Blocks.SLIME_BLOCK);
    }

    private boolean isMarker(BlockState s) {
        return s.is(Blocks.ITEM_FRAME) || s.is(net.minecraft.world.level.block.Blocks.GLOW_ITEM_FRAME)
            || s.is(net.minecraft.world.level.block.Blocks.OAK_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.OAK_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.SPRUCE_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.SPRUCE_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.BIRCH_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.BIRCH_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.JUNGLE_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.JUNGLE_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.ACACIA_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.ACACIA_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.DARK_OAK_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.DARK_OAK_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.MANGROVE_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.MANGROVE_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.CHERRY_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.CHERRY_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.BAMBOO_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.BAMBOO_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.CRIMSON_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.CRIMSON_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.WARPED_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.WARPED_WALL_SIGN)
            || s.is(net.minecraft.world.level.block.Blocks.WHITE_BANNER)
            || s.is(net.minecraft.world.level.block.Blocks.WHITE_WALL_BANNER)
            || s.is(Blocks.RED_BED) || s.is(Blocks.BLUE_BED) || s.is(Blocks.BLACK_BED)
            || s.is(Blocks.WHITE_BED) || s.is(Blocks.GREEN_BED) || s.is(Blocks.BROWN_BED);
    }

    private boolean isContainer(BlockState state) {
        return state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)
            || state.is(Blocks.SHULKER_BOX) || state.is(Blocks.HOPPER)
            || state.is(Blocks.DISPENSER) || state.is(Blocks.DROPPER);
    }

    private boolean isLightSource(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_TORCH)
            || state.is(Blocks.SOUL_WALL_TORCH) || state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN)
            || state.is(Blocks.GLOWSTONE) || state.is(Blocks.SEA_LANTERN) || state.is(Blocks.JACK_O_LANTERN)
            || state.is(Blocks.END_ROD) || state.is(Blocks.OCHRE_FROGLIGHT) || state.is(Blocks.PEARLESCENT_FROGLIGHT)
            || state.is(Blocks.VERDANT_FROGLIGHT) || state.is(Blocks.GLOW_LICHEN)
            || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
    }

    private boolean isWorkstation(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)
            || state.is(Blocks.SMOKER) || state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL)
            || state.is(Blocks.DAMAGED_ANVIL) || state.is(Blocks.ENCHANTING_TABLE) || state.is(Blocks.BREWING_STAND)
            || state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.LOOM) || state.is(Blocks.STONECUTTER)
            || state.is(Blocks.GRINDSTONE) || state.is(Blocks.CARTOGRAPHY_TABLE) || state.is(Blocks.FLETCHING_TABLE)
            || state.is(Blocks.LECTERN) || state.is(Blocks.COMPOSTER) || state.is(Blocks.CAULDRON);
    }

    private boolean isFarmBlock(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.is(Blocks.WHEAT) || state.is(Blocks.CARROTS)
            || state.is(Blocks.POTATOES) || state.is(Blocks.BEETROOTS) || state.is(Blocks.SUGAR_CANE)
            || state.is(Blocks.NETHER_WART) || state.is(Blocks.MELON) || state.is(Blocks.PUMPKIN)
            || state.is(Blocks.MELON_STEM) || state.is(Blocks.PUMPKIN_STEM) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    private boolean isUnnatural(BlockState state) {
        return state.is(Blocks.OAK_PLANKS) || state.is(Blocks.SPRUCE_PLANKS) || state.is(Blocks.BIRCH_PLANKS)
            || state.is(Blocks.JUNGLE_PLANKS) || state.is(Blocks.ACACIA_PLANKS) || state.is(Blocks.DARK_OAK_PLANKS)
            || state.is(Blocks.MANGROVE_PLANKS) || state.is(Blocks.CHERRY_PLANKS) || state.is(Blocks.BAMBOO_PLANKS)
            || state.is(Blocks.CRIMSON_PLANKS) || state.is(Blocks.WARPED_PLANKS)
            || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
            || state.is(Blocks.BRICKS) || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.DEEPSLATE_BRICKS)
            || state.is(Blocks.NETHER_BRICKS) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)
            || state.is(Blocks.QUARTZ_BLOCK) || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.STONE)
            || state.is(Blocks.SMOOTH_STONE_SLAB) || state.is(Blocks.OAK_SLAB) || state.is(Blocks.SPRUCE_SLAB)
            || state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.STONE_BRICK_SLAB)
            || state.is(Blocks.LADDER) || state.is(Blocks.OAK_FENCE) || state.is(Blocks.SPRUCE_FENCE)
            || state.is(Blocks.NETHER_BRICK_FENCE) || state.is(Blocks.OAK_DOOR) || state.is(Blocks.IRON_DOOR)
            || state.is(Blocks.OAK_TRAPDOOR) || state.is(Blocks.IRON_TRAPDOOR) || state.is(Blocks.IRON_BARS)
            || state.is(Blocks.WHITE_WOOL) || state.is(Blocks.WHITE_CARPET) || state.is(Blocks.WHITE_CONCRETE)
            || state.is(Blocks.WHITE_TERRACOTTA) || state.is(Blocks.WHITE_GLAZED_TERRACOTTA)
            || state.is(Blocks.BEDROCK) // portals often nearby
            || state.is(Blocks.OBSIDIAN) || state.is(Blocks.NETHER_PORTAL);
    }

    private static class ChunkScore {
        final int x;
        final int z;
        final int score;

        ChunkScore(int x, int z, int score) {
            this.x = x;
            this.z = z;
            this.score = score;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChunkScore)) return false;
            ChunkScore that = (ChunkScore) o;
            return x == that.x && z == that.z;
        }

        @Override
        public int hashCode() {
            return 31 * x + z;
        }
    }
}
