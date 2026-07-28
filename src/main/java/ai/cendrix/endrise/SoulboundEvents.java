package ai.cendrix.endrise;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Endrise.MODID)
public final class SoulboundEvents {

    /** Equipment we capture beyond the main inventory (mainhand lives in slots 0-8). */
    private static final List<EquipmentSlot> EQUIPMENT = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND);

    private SoulboundEvents() {}

    private static int encodeEquipment(EquipmentSlot slot) {
        return -(1 + slot.ordinal());
    }

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return; // vanilla already keeps everything
        }
        var registries = level.registryAccess();
        MinecraftServer server = level.getServer();
        SoulboundStore store = SoulboundStore.get(server);
        long readyAt = server.overworld().getGameTime() + Soulbound.RETURN_DELAY_TICKS;

        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (Soulbound.protects(registries, stack)) {
                store.add(player.getUUID(), new SoulboundStore.Pending(slot, stack.copy(), readyAt));
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        for (EquipmentSlot slot : EQUIPMENT) {
            ItemStack stack = player.getItemBySlot(slot);
            if (Soulbound.protects(registries, stack)) {
                store.add(player.getUUID(), new SoulboundStore.Pending(encodeEquipment(slot), stack.copy(), readyAt));
                player.setItemSlot(slot, ItemStack.EMPTY);
            }
        }

        // Teach the covenant at the exact moment of confusion: dying in the void
        // with unbound enderium is the one loss the void path refuses by design.
        if (event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD) && carriesUnboundEnderium(registries, player)) {
            player.sendSystemMessage(Component.translatable("chat.endrise.void_kept")
                    .withStyle(style -> style.withColor(0x2FC39D).withItalic(true)));
        }
    }

    /** Any void-protected item on this player that Soulbound does not cover. */
    static boolean carriesUnboundEnderium(HolderLookup.Provider registries, ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(VoidReturn.VOID_RETURNING) && !Soulbound.isSoulbound(registries, stack)) {
                return true;
            }
        }
        for (EquipmentSlot slot : EQUIPMENT) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.is(VoidReturn.VOID_RETURNING) && !Soulbound.isSoulbound(registries, stack)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        if (now % 20L != 0L) {
            return;
        }
        SoulboundStore store = SoulboundStore.get(server);
        boolean delivered = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            delivered |= deliverPendingFor(store, player, now);
        }
        if (delivered) {
            // Persist the drained entries now: player .dat files save on logout but
            // the store only saves with the level, and that skew is a crash-dupe.
            server.overworld().getDataStorage().save();
        }
    }

    /** Package-private for the self-test. Returns true when anything was delivered. */
    static boolean deliverPendingFor(SoulboundStore store, ServerPlayer player, long now) {
        // A corpse on the death screen still sits in the player list; anything
        // delivered to it is wiped by respawn. Entries wait for the living.
        if (!player.isAlive()) {
            return false;
        }
        List<SoulboundStore.Pending> ready = store.takeReady(player.getUUID(), now);
        if (ready.isEmpty()) {
            return false;
        }
        for (SoulboundStore.Pending entry : ready) {
            deliver(player, entry);
        }
        ServerLevel level = (ServerLevel) player.level();
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.PORTAL, player.getX(), player.getY() + 1.0, player.getZ(), 32, 0.4, 0.6, 0.4, 0.3);
        return true;
    }

    static void deliver(ServerPlayer player, SoulboundStore.Pending entry) {
        if (entry.slot() == VoidReturn.SLOT_ANY) {
            // Void/despawn rescue: no home slot to restore
            giveOrDrop(player, entry.stack());
            VoidReturn.awardNothingIsLost(player);
            return;
        }
        if (entry.slot() >= 0) {
            if (player.getInventory().getItem(entry.slot()).isEmpty()) {
                player.getInventory().setItem(entry.slot(), entry.stack());
                return;
            }
        } else {
            EquipmentSlot slot = EquipmentSlot.values()[-entry.slot() - 1];
            if (player.getItemBySlot(slot).isEmpty()) {
                player.setItemSlot(slot, entry.stack());
                return;
            }
        }
        // Original spot is occupied: any free slot beats dropping it on the floor
        giveOrDrop(player, entry.stack());
    }

    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    /** Soulbound only lands on enderium gear, whether it arrives from a book
     *  (stored enchantments) or a sacrificed item (live enchantments). */
    /** Tide 8's advancement tab: taking a soulbound result off the anvil is Bound. */
    @SubscribeEvent
    static void onAnvilRepair(net.neoforged.neoforge.event.entity.player.AnvilRepairEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && Soulbound.isSoulbound(player.level().registryAccess(), event.getOutput())) {
            var holder = player.level().getServer().getAdvancements().get(Endrise.id("bound"));
            if (holder != null) {
                player.getAdvancements().award(holder, "bound");
            }
        }
    }

    @SubscribeEvent
    static void onAnvilUpdate(AnvilUpdateEvent event) {
        if (Soulbound.isEnderiumGear(event.getLeft())) {
            return;
        }
        ItemStack right = event.getRight();
        if (carriesSoulbound(right.get(DataComponents.STORED_ENCHANTMENTS))
                || carriesSoulbound(right.get(DataComponents.ENCHANTMENTS))) {
            event.setCanceled(true);
        }
    }

    private static boolean carriesSoulbound(ItemEnchantments enchantments) {
        return enchantments != null && enchantments.keySet().stream().anyMatch(h -> h.is(Soulbound.KEY));
    }
}
