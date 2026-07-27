package ai.cendrix.endrise;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Headless verification of the Soulbound anvil gate. Off unless the JVM runs with
 * -Dendrise.selftest.anvil=true (e.g. JAVA_TOOL_OPTIONS=-Dendrise.selftest.anvil=true
 * ./gradlew runServer): then it drives a real AnvilMenu through the three interesting
 * combines on server start, logs [ANVIL-SELFTEST] PASS/FAIL per case, and halts the server.
 */
@EventBusSubscriber(modid = Endrise.MODID)
public final class SoulboundAnvilSelfTest {

    private SoulboundAnvilSelfTest() {}

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("endrise.selftest.anvil")) {
            return;
        }
        MinecraftServer server = event.getServer();
        Player player = FakePlayerFactory.getMinecraft(server.overworld());
        Holder<Enchantment> soulbound = server.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Soulbound.KEY);

        ItemStack soulboundPick = new ItemStack(Items.DIAMOND_PICKAXE);
        soulboundPick.enchant(soulbound, 1);

        ItemStack soulboundBook = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(soulbound, 1);
        soulboundBook.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());

        ItemStack infusedPick = new ItemStack(Items.DIAMOND_PICKAXE);
        infusedPick.set(Endrise.ENDERIUM_INFUSED.get(), Unit.INSTANCE);

        ItemStack damagedPick = new ItemStack(Items.DIAMOND_PICKAXE);
        damagedPick.setDamageValue(500);

        boolean ok = true;
        ok &= check(player, damagedPick, soulboundPick, false,
                "plain pick + soulbound pick sacrifice is refused");
        ok &= check(player, infusedPick, soulboundBook, true,
                "infused pick + soulbound book still works");
        ok &= check(player, damagedPick, new ItemStack(Items.DIAMOND_PICKAXE), true,
                "control: plain pick + plain pick still repairs");
        Endrise.LOGGER.info("[ANVIL-SELFTEST] {}", ok ? "ALL PASS" : "FAILURES PRESENT");
        server.halt(false);
    }

    private static boolean check(Player player, ItemStack left, ItemStack right,
            boolean expectResult, String label) {
        AnvilMenu menu = new AnvilMenu(1, player.getInventory());
        menu.getSlot(0).set(left.copy());
        menu.getSlot(1).set(right.copy());
        menu.createResult();
        ItemStack result = menu.getSlot(2).getItem();
        boolean pass = result.isEmpty() != expectResult;
        Endrise.LOGGER.info("[ANVIL-SELFTEST] {}: {} (result: {})",
                pass ? "PASS" : "FAIL", label, result.isEmpty() ? "empty" : result);
        return pass;
    }
}
