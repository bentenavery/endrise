package ai.cendrix.endrise;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Natural-placement audit for the cenotaphs, off unless the JVM runs with
 * -Dendrise.cenotaphprobe=true. Finds 15 distinct natural sites via the same
 * search /locate uses, force-generates each, then checks the ruin physically
 * exists (enderium core + chest) and how much ground actually holds it up.
 * The End's heightmap is honest about void gaps only at generation time, so
 * this needs a FRESH world: chunks generated before Tide 7 can never hold a
 * cenotaph and would read as false MISSING results.
 *
 * Verdict per site: OK / SPARSE (mostly overhanging void) / MISSING.
 * The tide's bar: more than 1 in 5 broken means placement needs rework.
 */
@EventBusSubscriber(modid = Endrise.MODID)
public final class CenotaphPlacementProbe {
    private static final int TARGET_SITES = 15;

    private CenotaphPlacementProbe() {}

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("endrise.cenotaphprobe")) {
            return;
        }
        ServerLevel end = event.getServer().getLevel(Level.END);
        var structure = event.getServer().registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, Endrise.id("cenotaph")));

        Map<ChunkPos, BlockPos> sites = new LinkedHashMap<>();
        for (int attempt = 0; attempt < 48 && sites.size() < TARGET_SITES; attempt++) {
            double angle = attempt * 2.399963;  // golden angle: spread origins evenly
            int dist = 1600 + attempt * 700;
            var origin = new BlockPos((int) (Math.cos(angle) * dist), 64,
                    (int) (Math.sin(angle) * dist));
            var found = end.getChunkSource().getGenerator().findNearestMapStructure(
                    end, HolderSet.direct(structure), origin, 48, false);
            if (found != null) {
                sites.putIfAbsent(ChunkPos.containing(found.getFirst()), found.getFirst());
            }
        }
        Endrise.LOGGER.info("[CENOPROBE] distinct natural sites found: {}", sites.size());

        int okCount = 0, sparse = 0, missing = 0;
        for (BlockPos site : sites.values()) {
            ChunkPos cp = ChunkPos.containing(site);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    end.getChunk(cp.x() + dx, cp.z() + dz);  // force full generation
                }
            }
            BlockPos core = null;
            boolean chest = false;
            for (BlockPos p : BlockPos.betweenClosed(
                    site.getX() - 24, 5, site.getZ() - 24,
                    site.getX() + 24, 130, site.getZ() + 24)) {
                var state = end.getBlockState(p);
                if (state.is(Endrise.ENDERIUM_BLOCK.get())) {
                    core = p.immutable();
                } else if (state.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    chest = true;
                }
            }
            if (core == null || !chest) {
                missing++;
                Endrise.LOGGER.info("[CENOPROBE] MISSING at {} (core={}, chest={})", site, core, chest);
                var chunk = end.getChunk(cp.x(), cp.z());
                var start = end.structureManager().getStartForStructure(
                        net.minecraft.core.SectionPos.bottomOf(chunk), structure.value(), chunk);
                var biome = end.getBiome(new BlockPos(site.getX(), 64, site.getZ()))
                        .unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                Endrise.LOGGER.info("[CENOPROBE] MISSING-DIAG start={} biome={} surfaceY={}",
                        start == null ? "ABSENT" : start.getBoundingBox(), biome,
                        end.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                                site.getX() + 8, site.getZ() + 8));
                continue;
            }
            // Ground audit: a 5x5 sample grid around the core; a column counts as
            // held if anything solid sits within 8 blocks under foundation level.
            int held = 0;
            for (int dx = -4; dx <= 4; dx += 2) {
                for (int dz = -4; dz <= 4; dz += 2) {
                    for (int dy = 2; dy <= 9; dy++) {
                        if (!end.getBlockState(core.offset(dx, -dy, dz)).isAir()) {
                            held++;
                            break;
                        }
                    }
                }
            }
            String verdict = held >= 8 ? "OK" : "SPARSE";
            if (held >= 8) {
                okCount++;
            } else {
                sparse++;
            }
            Endrise.LOGGER.info("[CENOPROBE] {} at {} coreY={} ground={}/25 chest={}",
                    verdict, site, core.getY(), held, chest);
        }
        Endrise.LOGGER.info("[CENOPROBE] SUMMARY sites={} ok={} sparse={} missing={}",
                sites.size(), okCount, sparse, missing);
        event.getServer().halt(false);
    }
}
