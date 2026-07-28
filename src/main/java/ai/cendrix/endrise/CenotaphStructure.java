package ai.cendrix.endrise;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pools.DimensionPadding;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.pools.alias.PoolAliasLookup;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

import java.util.List;
import java.util.Optional;

/**
 * A single-piece jigsaw start that yields to the void. Vanilla jigsaw JSON has
 * no ground condition, and the End's WORLD_SURFACE heightmap happily reports
 * the void floor: a fresh-world probe put 8 of 15 cenotaphs at y=0 hanging in
 * nothing. Worse, jigsaw's own heightmap projection samples the ROTATED piece
 * center, so a start corner on solid ground can still project into a void
 * column off the island edge (seen live: start bbox y -13..18 beside terrain
 * at y 61). So the cenotaph anchors its own Y: the start-corner column is
 * sampled directly, the surrounding ring must mostly hold in every rotation
 * quadrant, and the piece is pinned there with projection disabled. If the
 * ground refuses, the structure start never exists: /locate never points at
 * a grave the void already took.
 */
public final class CenotaphStructure extends Structure {
    public static final MapCodec<CenotaphStructure> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    settingsCodec(instance),
                    StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter(s -> s.startPool))
            .apply(instance, CenotaphStructure::new));

    /** Samples this far under the top of the world's floor still count as void. */
    private static final int GROUND_MARGIN = 10;
    private static final int SAMPLE_REACH = 6;
    private static final int MIN_HELD = 7;

    private final Holder<StructureTemplatePool> startPool;

    public CenotaphStructure(Structure.StructureSettings settings, Holder<StructureTemplatePool> startPool) {
        super(settings);
        this.startPool = startPool;
    }

    @Override
    public Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
        ChunkPos chunkPos = context.chunkPos();
        int cornerX = chunkPos.getMinBlockX();
        int cornerZ = chunkPos.getMinBlockZ();
        int voidLine = context.heightAccessor().getMinBuildHeight() + GROUND_MARGIN;

        // The rotation pivot column: piece height is pinned here, so it MUST hold.
        int corner = surface(context, cornerX, cornerZ);
        if (corner <= voidLine || !groundHolds(context, cornerX, cornerZ, voidLine)) {
            return Optional.empty();
        }
        // groundLevelDelta(1) seats the template's y0 foundation at `corner`,
        // flush with the terrain surface, one step proud of the island.
        BlockPos startPos = new BlockPos(cornerX, corner + 1, cornerZ);
        return JigsawPlacement.addPieces(
                context, this.startPool, Optional.empty(), 1, startPos, false,
                Optional.empty(), 80,
                PoolAliasLookup.create(List.of(), startPos, context.seed()),
                DimensionPadding.ZERO, LiquidSettings.APPLY_WATERLOGGING);
    }

    private static int surface(Structure.GenerationContext context, int x, int z) {
        return context.chunkGenerator().getFirstOccupiedHeight(x, z,
                Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
    }

    /** The rotation is drawn inside jigsaw assembly, so the piece may occupy any
     *  quadrant around the pivot: the ring check is centered on the pivot itself. */
    private static boolean groundHolds(Structure.GenerationContext context, int x, int z, int voidLine) {
        int held = 0;
        for (int dx = -SAMPLE_REACH; dx <= SAMPLE_REACH; dx += SAMPLE_REACH) {
            for (int dz = -SAMPLE_REACH; dz <= SAMPLE_REACH; dz += SAMPLE_REACH) {
                if (surface(context, x + dx, z + dz) > voidLine) {
                    held++;
                }
            }
        }
        return held >= MIN_HELD;
    }

    @Override
    public StructureType<?> type() {
        return Endrise.CENOTAPH_STRUCTURE.get();
    }
}
