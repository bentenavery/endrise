package ai.cendrix.endrise;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tide 8: enderium is quietly awake. Ore and block shed slow reverse-portal
 * motes from any face that meets open air, so a vein glimmers into the cave
 * before you can read the texture, plus a rare resonant hum. Vanilla
 * particles and sounds only: no new assets, no renderers.
 */
public class GlimmerBlock extends Block {
    public GlimmerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(10) == 0) {
            Direction dir = Direction.getRandom(random);
            if (level.getBlockState(pos.relative(dir)).isAir()) {
                double jitterA = (random.nextDouble() - 0.5) * 0.8;
                double jitterB = (random.nextDouble() - 0.5) * 0.8;
                level.addParticle(ParticleTypes.REVERSE_PORTAL,
                        pos.getX() + 0.5 + (dir.getStepX() != 0 ? dir.getStepX() * 0.56 : jitterA),
                        pos.getY() + 0.5 + (dir.getStepY() != 0 ? dir.getStepY() * 0.56 : jitterB),
                        pos.getZ() + 0.5 + (dir.getStepZ() != 0 ? dir.getStepZ() * 0.56
                                : (dir.getStepX() != 0 ? jitterA : jitterB)),
                        0.0, 0.02 + random.nextDouble() * 0.02, 0.0);
            }
        }
        if (random.nextInt(600) == 0) {
            level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS,
                    0.25F, 0.5F + random.nextFloat() * 0.3F, false);
        }
    }
}
