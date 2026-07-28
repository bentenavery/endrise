package ai.cendrix.endrise;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Tide 8, the covenant's last word: after your gear, your drops, and everyone
 * else's graves, it's your turn. Use anywhere, in any dimension, and arrive at
 * your own respawn point. The resolution is vanilla's, never reimplemented:
 * bed and anchor validity, the world-spawn fallback, and the no-respawn-block
 * toast all come from the same call the death screen uses. Leaving the End
 * this way can never roll credits: that belongs to the portal alone.
 */
public class HomewardPearlItem extends Item {
    private static final int COOLDOWN_TICKS = 100;

    public HomewardPearlItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(stack)) {
            return InteractionResult.PASS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;  // client swings; the server travels
        }
        // consumeSpawnBlock=false: a respawn anchor keeps its charge, the
        // pearl itself is the price (mirrors the End-portal exit path)
        TeleportTransition transition =
                serverPlayer.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
        ServerLevel from = (ServerLevel) serverPlayer.level();
        net.minecraft.core.BlockPos origin = serverPlayer.blockPosition();
        ServerPlayer moved = serverPlayer.teleport(transition);
        if (moved == null) {
            return InteractionResult.FAIL;  // travel cancelled by another mod: pearl kept, no noise
        }
        from.playSound(null, origin, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 0.9F);
        // cooldown keys off the stack's item id: set it before the stack can empty
        moved.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
        moved.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(this));
        stack.consume(1, moved);  // creative keeps its pearl, like every vanilla teleport item
        ServerLevel arrived = (ServerLevel) moved.level();
        arrived.playSound(null, moved.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.1F);
        arrived.sendParticles(ParticleTypes.REVERSE_PORTAL,
                moved.getX(), moved.getY() + 1.0, moved.getZ(), 32, 0.4, 0.6, 0.4, 0.05);
        awardTheWayHome(moved);
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void awardTheWayHome(ServerPlayer player) {
        var holder = player.level().getServer().getAdvancements().get(Endrise.id("the_way_home"));
        if (holder != null) {
            player.getAdvancements().award(holder, "returned");
        }
    }
}
