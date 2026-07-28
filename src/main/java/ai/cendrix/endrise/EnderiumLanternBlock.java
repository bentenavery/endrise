package ai.cendrix.endrise;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A lantern whose embers drift upward: the one-tile inversion that says
 * "not the overworld". Sculk souls are already the right teal; no custom
 * particles, no renderers.
 */
public class EnderiumLanternBlock extends LanternBlock {
    public EnderiumLanternBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.SCULK_SOUL,
                    pos.getX() + 0.4 + random.nextDouble() * 0.2,
                    pos.getY() + 0.35,
                    pos.getZ() + 0.4 + random.nextDouble() * 0.2,
                    0.0, 0.02 + random.nextDouble() * 0.02, 0.0);
        }
    }
}
