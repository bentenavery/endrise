package ai.cendrix.endrise;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

/**
 * Tide 6: the world keeps score of loss. A player death in the End sprouts a
 * Mourning Bloom on the nearest end stone, and the Draught of Return carries
 * its drinker back to where they drank it, End-only, when the effect runs its
 * course. Milk and death cancel cleanly; the anchor rides a persistent player
 * attachment, so logging out mid-draught changes nothing.
 */
@EventBusSubscriber(modid = Endrise.MODID)
public final class BloomEvents {
    private static final int DEATH_BLOOM_RANGE = 4;
    private static final int RETURN_NUDGE_MAX = 5;

    private BloomEvents() {}

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.level() instanceof ServerLevel level
                && level.dimension().equals(Level.END)) {
            placeDeathBloom(level, player.blockPosition());
        }
    }

    /** Package-private for the self-test. True when a bloom was planted. */
    static boolean placeDeathBloom(ServerLevel level, BlockPos around) {
        var spot = BlockPos.findClosestMatch(around, DEATH_BLOOM_RANGE, DEATH_BLOOM_RANGE,
                p -> level.getBlockState(p).is(Blocks.END_STONE)
                        && level.getBlockState(p.above()).isAir());
        if (spot.isEmpty()) {
            return false;
        }
        level.setBlockAndUpdate(spot.get().above(), Endrise.MOURNING_BLOOM.get().defaultBlockState());
        return true;
    }

    @SubscribeEvent
    static void onEffectAdded(MobEffectEvent.Added event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getEffectInstance() != null
                && event.getEffectInstance().is(Endrise.RETURN_EFFECT)) {
            player.setData(Endrise.RETURN_ANCHOR.get(),
                    GlobalPos.of(player.level().dimension(), player.blockPosition()));
        }
    }

    @SubscribeEvent
    static void onEffectExpired(MobEffectEvent.Expired event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getEffectInstance() != null
                && event.getEffectInstance().is(Endrise.RETURN_EFFECT)) {
            completeReturn(player);
        }
    }

    /** Milk or death: the draught fizzles without a word. */
    @SubscribeEvent
    static void onEffectRemoved(MobEffectEvent.Remove event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getEffectInstance() != null
                && event.getEffectInstance().is(Endrise.RETURN_EFFECT)) {
            player.removeData(Endrise.RETURN_ANCHOR.get());
        }
    }

    enum ReturnResult { RETURNED, FIZZLED }

    /** Package-private for the self-test. The rule: the draught only works where
     *  it was drunk, in the End. */
    static ReturnResult completeReturn(ServerPlayer player) {
        GlobalPos anchor = player.hasData(Endrise.RETURN_ANCHOR.get())
                ? player.getData(Endrise.RETURN_ANCHOR.get()) : null;
        player.removeData(Endrise.RETURN_ANCHOR.get());
        ServerLevel level = (ServerLevel) player.level();
        if (anchor != null && level.dimension().equals(Level.END)
                && anchor.dimension().equals(Level.END)) {
            BlockPos target = anchor.pos();
            for (int up = 0; up <= RETURN_NUDGE_MAX; up++) {
                BlockPos t = target.above(up);
                if (level.getBlockState(t).isAir() && level.getBlockState(t.above()).isAir()) {
                    level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                            SoundSource.PLAYERS, 1.0F, 1.0F);
                    player.teleportTo(t.getX() + 0.5, t.getY(), t.getZ() + 0.5);
                    level.playSound(null, t, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                    level.sendParticles(ParticleTypes.PORTAL,
                            t.getX() + 0.5, t.getY() + 1.0, t.getZ() + 0.5, 32, 0.4, 0.6, 0.4, 0.3);
                    awardThereAndBack(player);
                    return ReturnResult.RETURNED;
                }
            }
        }
        // wrong dimension, no anchor, or fully obstructed: the draught sighs out
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 0.6F, 0.5F);
        return ReturnResult.FIZZLED;
    }

    private static void awardThereAndBack(ServerPlayer player) {
        var holder = player.level().getServer().getAdvancements().get(Endrise.id("there_and_back"));
        if (holder != null) {
            player.getAdvancements().award(holder, "returned");
        }
    }
}
