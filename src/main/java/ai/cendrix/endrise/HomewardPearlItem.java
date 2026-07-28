package ai.cendrix.endrise;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;

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
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.pass(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        // true = a respawn anchor keeps its charge on 1.21.1: the decrement
        // guard here is (!forced && !flag), the inverse of 26.x's
        // consumeSpawnBlock. The pearl itself is the price on both branches.
        DimensionTransition transition =
                serverPlayer.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING);
        ServerLevel from = serverPlayer.serverLevel();
        from.playSound(null, serverPlayer.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 0.9F);
        Entity moved = serverPlayer.changeDimension(transition);
        if (!(moved instanceof ServerPlayer arrivedPlayer)) {
            return InteractionResultHolder.fail(stack);  // travel cancelled: pearl kept
        }
        arrivedPlayer.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        stack.shrink(1);
        ServerLevel arrived = arrivedPlayer.serverLevel();
        arrived.playSound(null, arrivedPlayer.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0F, 1.1F);
        arrived.sendParticles(ParticleTypes.REVERSE_PORTAL,
                arrivedPlayer.getX(), arrivedPlayer.getY() + 1.0, arrivedPlayer.getZ(),
                32, 0.4, 0.6, 0.4, 0.05);
        awardTheWayHome(arrivedPlayer);
        return InteractionResultHolder.consume(stack);
    }

    private static void awardTheWayHome(ServerPlayer player) {
        var holder = player.level().getServer().getAdvancements().get(Endrise.id("the_way_home"));
        if (holder != null) {
            player.getAdvancements().award(holder, "returned");
        }
    }
}
