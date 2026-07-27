package ai.cendrix.endrise;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tide 4: the void gives back. Enderium items dropped by a player return to them
 * instead of dying to the void or the despawn timer.
 *
 * Capture points (adversarial review shaped all three):
 * - Toss below the world floor: captured synchronously at toss time, because an
 *   item spawned under the kill plane is discarded by its own first tick, before
 *   any scan could see it (the panic-drop-while-falling flagship case).
 * - Below-floor scan every 10 ticks: catches items that FALL into the void.
 *   EntityLeaveLevelEvent is useless here: 26.x wipes the stack before it posts.
 * - ItemExpireEvent: age-despawn rescue, entity intact pre-discard.
 *
 * Ownership and the death flag live in the STACK's custom data, not entity NBT:
 * vanilla merges item entities by comparing stacks only, so entity-level markers
 * could be laundered off death drops (or stripped from protected drops) by a
 * merge. Component markers make differently-marked stacks unmergeable by
 * construction. They are stripped on pickup and on capture, so inventories
 * never see them. Death drops are flagged at LivingDropsEvent time and excluded
 * from every capture path: Soulbound keeps its monopoly on death.
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

    /** Every player-dropped item remembers its dropper in the stack itself. */
    @SubscribeEvent
    static void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemEntity entity = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, entity.getItem(),
                tag -> tag.putString(TAG_OWNER, player.getStringUUID()));
        // Tossed under the world floor: the entity dies on its own first tick,
        // before any scan. Capture right now. isAlive() excludes death drops,
        // which also pass through this event while the player is already dead.
        if (player instanceof ServerPlayer serverPlayer && player.isAlive()
                && entity.getY() < player.level().getMinBuildHeight()) {
            if (captureStack(serverPlayer.level().getServer(), serverPlayer.getUUID(), entity.getItem())) {
                entity.setItem(ItemStack.EMPTY); // empty entities self-discard; nothing to dupe
            }
        }
    }

    /** Death drops are Soulbound's business; flag them out of every capture path. */
    @SubscribeEvent
    static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        for (ItemEntity drop : event.getDrops()) {
            CustomData.update(DataComponents.CUSTOM_DATA, drop.getItem(), tag -> {
                tag.putBoolean(TAG_DEATH_DROP, true);
                tag.remove(TAG_OWNER);
            });
        }
    }

    /** Inventories never see the markers; stripping also keeps stacking clean. */
    @SubscribeEvent
    static void onPickup(ItemEntityPickupEvent.Pre event) {
        stripMarkers(event.getItemEntity().getItem());
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
            int floor = level.getMinBuildHeight();
            List<? extends ItemEntity> below = level.getEntities(EntityType.ITEM,
                    item -> !item.isRemoved() && item.getY() < floor);
            for (ItemEntity item : below) {
                tryCapture(server, item);
            }
        }
    }

    private static void tryCapture(MinecraftServer server, ItemEntity item) {
        if (item.isRemoved() || item.getItem().isEmpty()) {
            return;
        }
        UUID owner = markedOwner(item.getItem());
        if (owner == null) {
            return; // unmarked, death-flagged, or corrupt: vanilla behavior proceeds
        }
        if (captureStack(server, owner, item.getItem())) {
            item.discard();
        }
    }

    private static boolean captureStack(MinecraftServer server, UUID owner, ItemStack stack) {
        if (stack.isEmpty() || !stack.is(VOID_RETURNING)) {
            return false;
        }
        ItemStack rescued = stack.copy();
        stripMarkers(rescued);
        long readyAt = server.overworld().getGameTime() + Soulbound.RETURN_DELAY_TICKS;
        SoulboundStore.get(server).add(owner, new SoulboundStore.Pending(SLOT_ANY, rescued, readyAt));
        return true;
    }

    /** The dropper UUID, or null when absent, death-flagged, or unparseable. */
    private static UUID markedOwner(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        if (tag.getBoolean(TAG_DEATH_DROP)) {
            return null;
        }
        String owner = tag.getString(TAG_OWNER);
        if (owner.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void stripMarkers(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return;
        }
        CompoundTag tag = data.copyTag();
        tag.remove(TAG_OWNER);
        tag.remove(TAG_DEATH_DROP);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    /**
     * Ender pearl impact costs nothing while wearing any enderium armor piece.
     * EntityTeleportEvent.EnderPearl is the version-stable seam: on 1.21.1 pearls
     * deal plain fall-type damage, so a damage-source check cannot port safely.
     */
    @SubscribeEvent
    static void onEnderPearlTeleport(EntityTeleportEvent.EnderPearl event) {
        ServerPlayer player = event.getPlayer();
        if (player != null && negatesPearl(player)) {
            event.setAttackDamage(0.0F);
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
