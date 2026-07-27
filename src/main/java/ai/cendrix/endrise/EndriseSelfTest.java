package ai.cendrix.endrise;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Headless verification of the Soulbound anvil gate and the two infusion paths at the
 * smithing table. Off unless the JVM runs with -Dendrise.selftest=true (the original
 * -Dendrise.selftest.anvil=true still works), e.g.
 * JAVA_TOOL_OPTIONS=-Dendrise.selftest=true ./gradlew runServer: it then drives real
 * AnvilMenu/SmithingMenu instances on server start, logs [SELFTEST] per case, and halts.
 */
@EventBusSubscriber(modid = Endrise.MODID)
public final class EndriseSelfTest {

    private EndriseSelfTest() {}

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("endrise.selftest") && !Boolean.getBoolean("endrise.selftest.anvil")) {
            return;
        }
        MinecraftServer server = event.getServer();
        Player player = FakePlayerFactory.getMinecraft(server.overworld());

        boolean ok = runAnvilChecks(server, player);
        ok &= runSmithingChecks(player);
        Endrise.LOGGER.info("[SELFTEST] {}", ok ? "ALL PASS" : "FAILURES PRESENT");
        server.halt(false);
    }

    private static boolean runAnvilChecks(MinecraftServer server, Player player) {
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
        ok &= anvil(player, damagedPick, soulboundPick, false,
                "anvil: plain pick + soulbound pick sacrifice is refused");
        ok &= anvil(player, infusedPick, soulboundBook, true,
                "anvil: infused pick + soulbound book still works");
        ok &= anvil(player, damagedPick, new ItemStack(Items.DIAMOND_PICKAXE), true,
                "anvil control: plain pick + plain pick still repairs");
        return ok;
    }

    private static boolean anvil(Player player, ItemStack left, ItemStack right,
            boolean expectResult, String label) {
        AnvilMenu menu = new AnvilMenu(1, player.getInventory());
        menu.getSlot(0).set(left.copy());
        menu.getSlot(1).set(right.copy());
        menu.createResult();
        ItemStack result = menu.getSlot(2).getItem();
        boolean pass = result.isEmpty() != expectResult;
        Endrise.LOGGER.info("[SELFTEST] {}: {} (result: {})",
                pass ? "PASS" : "FAIL", label, result.isEmpty() ? "empty" : result);
        return pass;
    }

    private static boolean runSmithingChecks(Player player) {
        ItemStack trimTemplate = new ItemStack(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE);
        ItemStack upgradeTemplate = new ItemStack(Endrise.ENDERIUM_UPGRADE_TEMPLATE.get());
        ItemStack chestplate = new ItemStack(Items.NETHERITE_CHESTPLATE);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack ingot = new ItemStack(Endrise.ENDERIUM_INGOT.get());

        boolean ok = true;
        ok &= smith(player, trimTemplate, chestplate, ingot, true,
                "smithing: trim pattern + armor + enderium ingot trims (armor path)");
        ok &= smith(player, upgradeTemplate, pick, ingot, true,
                "smithing: upgrade template + tool + ingot infuses (tool path)");
        ok &= smith(player, upgradeTemplate, chestplate, ingot, false,
                "smithing: upgrade template + armor has no recipe (tools only, by design)");

        // GUI slot gates: the real screen consults mayPlace before a recipe ever runs.
        SmithingMenu gui = new SmithingMenu(1, player.getInventory());
        ok &= report(gui.getSlot(0).mayPlace(trimTemplate),
                "smithing GUI: trim template accepted by template slot");
        ok &= report(gui.getSlot(0).mayPlace(upgradeTemplate),
                "smithing GUI: enderium upgrade template accepted by template slot");
        ok &= report(gui.getSlot(1).mayPlace(chestplate),
                "smithing GUI: armor accepted by base slot");
        ok &= report(gui.getSlot(2).mayPlace(ingot),
                "smithing GUI: enderium ingot accepted by addition slot");

        // Vanilla rejects re-applying the IDENTICAL trim; a different pattern overwrites.
        // (This is why testing on the already-trimmed photo-studio set errors.)
        SmithingMenu prep = new SmithingMenu(1, player.getInventory());
        prep.getSlot(0).set(trimTemplate.copy());
        prep.getSlot(1).set(new ItemStack(Items.NETHERITE_CHESTPLATE));
        prep.getSlot(2).set(ingot.copy());
        prep.createResult();
        ItemStack onceTrimmed = prep.getSlot(3).getItem();
        ok &= smith(player, trimTemplate, onceTrimmed, ingot, false,
                "smithing: identical trim re-applied is refused (vanilla rule)");
        ok &= smith(player, new ItemStack(Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE), onceTrimmed, ingot, true,
                "smithing: different pattern overwrites an existing trim");
        return ok;
    }

    private static boolean report(boolean pass, String label) {
        Endrise.LOGGER.info("[SELFTEST] {}: {}", pass ? "PASS" : "FAIL", label);
        return pass;
    }

    private static boolean smith(Player player, ItemStack template, ItemStack base,
            ItemStack addition, boolean expectInfusedResult, String label) {
        SmithingMenu menu = new SmithingMenu(1, player.getInventory());
        menu.getSlot(0).set(template.copy());
        menu.getSlot(1).set(base.copy());
        menu.getSlot(2).set(addition.copy());
        menu.createResult();
        ItemStack result = menu.getSlot(3).getItem();
        boolean pass = expectInfusedResult
                ? !result.isEmpty() && Soulbound.isInfused(result)
                : result.isEmpty();
        Endrise.LOGGER.info("[SELFTEST] {}: {} (result: {}{})",
                pass ? "PASS" : "FAIL", label,
                result.isEmpty() ? "empty" : result,
                result.isEmpty() ? "" : (Soulbound.isInfused(result) ? ", counts as infused" : ", NOT infused"));
        return pass;
    }
}
