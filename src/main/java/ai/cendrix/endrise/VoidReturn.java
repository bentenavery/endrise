package ai.cendrix.endrise;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tide 4: the void gives back. Enderium items dropped by a player return to them
 * instead of dying to the void or the despawn timer.
 *
 * Capture is a periodic below-floor scan, NOT EntityLeaveLevelEvent: the probe
 * proved 26.x wipes the item's stack before that event posts. The scan reads
 * items while they are still alive, with 64 blocks of margin above vanilla's
 * kill plane (minY - 64). Despawn rescue hooks ItemExpireEvent, which fires
 * before the age discard with the entity intact.
 *
 * Ownership is self-stamped: ItemTossEvent writes the dropper's UUID into the
 * entity's persistent data (vanilla's thrower field is not reliably readable).
 * Death drops are flagged at spawn time and excluded from every capture path,
 * so Soulbound keeps its monopoly on death.
 */
@EventBusSubscriber(modid = Endrise.MODID)
public final class VoidReturn {
    /** Items the End gives back: enderium gear + enderium materials. */
    public static final TagKey<Item> VOID_RETURNING =
            TagKey.create(Registries.ITEM, Endrise.id("void_returning"));
    public static final TagKey<Item> ENDERIUM_ARMOR =
            TagKey.create(Registries.ITEM, Endrise.id("enderium_armor"));

    /** Pending.slot sentinel: no home slot, deliver to the first free one. */
    public static final int SLOT_ANY = -1000;

    static final String TAG_OWNER = "endrise:dropper";
    static final String TAG_DEATH_DROP = "endrise:death_drop";
    private static final int SCAN_INTERVAL_TICKS = 10;

    private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private VoidReturn() {}

    /** Every player-dropped item remembers who dropped it. */
    @SubscribeEvent
    static void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        if (player != null && !player.level().isClientSide()) {
            event.getEntity().getPersistentData().putString(TAG_OWNER, player.getStringUUID());
        }
    }

    /** Death drops are Soulbound's business; flag them out of every capture path. */
    @SubscribeEvent
    static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        for (ItemEntity drop : event.getDrops()) {
            CompoundTag data = drop.getPersistentData();
            data.putBoolean(TAG_DEATH_DROP, true);
            data.remove(TAG_OWNER);
        }
    }

    /** Despawn rescue: fires before the age discard, entity still intact. */
    @SubscribeEvent
    static void onItemExpire(ItemExpireEvent event) {
        ItemEntity item = event.getEntity();
        if (item.level() instanceof ServerLevel level) {
            tryCapture(level.getServer(), item);
        }
    }

    /** Void rescue: sweep items below the world floor before vanilla's kill plane. */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.overworld().getGameTime() % SCAN_INTERVAL_TICKS != 0L) {
            return;
        }
        scanNow(server);
    }

    /** Package-private so the self-test can drive a deterministic sweep. */
    static void scanNow(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            int floor = level.getMinY();
            List<? extends ItemEntity> below = level.getEntities(EntityType.ITEM,
                    item -> !item.isRemoved() && item.getY() < floor);
            for (ItemEntity item : below) {
                tryCapture(server, item);
            }
        }
    }

    private static void tryCapture(MinecraftServer server, ItemEntity item) {
        if (item.isRemoved() || item.getItem().isEmpty() || !item.getItem().is(VOID_RETURNING)) {
            return;
        }
        CompoundTag data = item.getPersistentData();
        if (data.getBooleanOr(TAG_DEATH_DROP, false)) {
            return;
        }
        String owner = data.getStringOr(TAG_OWNER, "");
        if (owner.isEmpty()) {
            return; // hopper/dispenser drops die vanilla-style
        }
        UUID ownerId;
        try {
            ownerId = UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            return;
        }
        long readyAt = server.overworld().getGameTime() + Soulbound.RETURN_DELAY_TICKS;
        SoulboundStore.get(server).add(ownerId,
                new SoulboundStore.Pending(SLOT_ANY, item.getItem().copy(), readyAt));
        item.discard();
    }

    /** Ender pearl impact costs nothing while wearing any enderium armor piece. */
    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getSource().is(DamageTypes.ENDER_PEARL) && negatesPearl(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    static boolean negatesPearl(LivingEntity entity) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (entity.getItemBySlot(slot).is(ENDERIUM_ARMOR)) {
                return true;
            }
        }
        return false;
    }

    static void awardNothingIsLost(ServerPlayer player) {
        var holder = player.level().getServer().getAdvancements().get(Endrise.id("nothing_is_lost"));
        if (holder != null) {
            player.getAdvancements().award(holder, "returned");
        }
    }
}
