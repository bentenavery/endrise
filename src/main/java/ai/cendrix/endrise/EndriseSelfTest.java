package ai.cendrix.endrise;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Headless verification of the enderium tier: the smithing upgrade path
 * (netherite -> enderium, components carried), the Soulbound anvil gate
 * (enderium gear only), and the creative tab. Off unless the JVM runs with
 * -Dendrise.selftest=true (legacy -Dendrise.selftest.anvil=true also works), e.g.
 * JAVA_TOOL_OPTIONS=-Dendrise.selftest=true ./gradlew runServer: drives real
 * menus on server start, logs [SELFTEST] per case, halts the server.
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

        immediateOk = runSmithingChecks(server, player);
        immediateOk &= runAnvilChecks(server, player);
        immediateOk &= runCreativeTabCheck(server);
        // Void checks need real server ticks: fresh entities only reach the queryable
        // index once the entity manager processes its pending queue, and the scan
        // itself runs on a tick cadence. Armed here, asserted in onTick.
        armedAt = server.overworld().getGameTime();
    }

    private static boolean immediateOk;
    private static long armedAt = -1;
    private static boolean voidOk = true;
    private static ItemEntity tossed;
    private static ItemEntity diamond;
    private static ItemEntity deathDrop;
    private static ItemEntity expiring;

    @SubscribeEvent
    static void onTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (armedAt < 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        long t = level.getGameTime() - armedAt;
        Player player = FakePlayerFactory.getMinecraft(level);

        if (t == 2) {
            var spawn = level.getRespawnData().pos();
            player.setPos(spawn.getX() + 0.5, 80, spawn.getZ() + 0.5);
            tossed = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get(), 2), false);
            diamond = player.drop(new ItemStack(Items.DIAMOND_PICKAXE), false);
            deathDrop = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get()), false);
            deathDrop.getPersistentData().putBoolean(VoidReturn.TAG_DEATH_DROP, true);
            expiring = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get()), false);
        } else if (t == 6) {
            voidOk &= report(tossed != null
                            && !tossed.getPersistentData().getStringOr(VoidReturn.TAG_OWNER, "").isEmpty(),
                    "void: player toss stamps the dropper UUID");
            var indexed = level.getEntities(net.minecraft.world.entity.EntityType.ITEM, e -> true);
            Endrise.LOGGER.info("[SELFTEST-DBG] indexed items after ticks: {} (expect 4)", indexed.size());
            tossed.setPos(tossed.getX(), level.getMinY() - 10, tossed.getZ());
            diamond.setPos(diamond.getX(), level.getMinY() - 10, diamond.getZ());
            deathDrop.setPos(deathDrop.getX(), level.getMinY() - 10, deathDrop.getZ());
            expiring.lifespan = 1;
            expiring.tick();
            expiring.tick();
        } else if (t == 30) {
            armedAt = -1;
            SoulboundStore store = SoulboundStore.get(server);
            long farFuture = level.getGameTime() + 10_000;
            var captured = store.takeReady(player.getUUID(), farFuture);
            Endrise.LOGGER.info("[SELFTEST-DBG] captured={} tossed[rm={} why={}] diamond[rm={} why={}] death[rm={} why={}] expiring[rm={} why={}]",
                    captured.size(),
                    tossed.isRemoved(), tossed.getRemovalReason(),
                    diamond.isRemoved(), diamond.getRemovalReason(),
                    deathDrop.isRemoved(), deathDrop.getRemovalReason(),
                    expiring.isRemoved(), expiring.getRemovalReason());
            // expiring was captured at t=6, tossed by the natural scan; order not guaranteed
            voidOk &= report(captured.size() == 2
                            && captured.stream().allMatch(p -> p.slot() == VoidReturn.SLOT_ANY)
                            && captured.stream().allMatch(p -> p.stack().is(Endrise.ENDERIUM_INGOT.get())),
                    "void: natural scan + expire hook captured exactly the two enderium drops");
            voidOk &= report(tossed.isRemoved() && expiring.isRemoved(),
                    "void: captured entities were discarded");
            // "Not captured" means we never discarded it into the store; on this empty
            // test server uncaptured entities get archived as UNLOADED_TO_CHUNK, which
            // is vanilla bookkeeping, not a capture.
            voidOk &= report(diamond.getRemovalReason() != net.minecraft.world.entity.Entity.RemovalReason.DISCARDED,
                    "void: non-enderium items are not captured");
            voidOk &= report(deathDrop.getRemovalReason() != net.minecraft.world.entity.Entity.RemovalReason.DISCARDED,
                    "void: death-flagged drops are left for Soulbound");

            if (captured.size() == 2) {
                int before = player.getInventory().countItem(Endrise.ENDERIUM_INGOT.get());
                SoulboundEvents.deliver((ServerPlayer) player, captured.get(0));
                SoulboundEvents.deliver((ServerPlayer) player, captured.get(1));
                voidOk &= report(player.getInventory().countItem(Endrise.ENDERIUM_INGOT.get()) == before + 3,
                        "void: delivery hands the rescued stacks back");
            }

            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Endrise.ENDERIUM_BOOTS.get()));
            boolean withBoots = VoidReturn.negatesPearl(player);
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            voidOk &= report(withBoots && !VoidReturn.negatesPearl(player),
                    "pearl: negation requires an enderium armor piece");

            boolean ok = immediateOk && voidOk;
            Endrise.LOGGER.info("[SELFTEST] {}", ok ? "ALL PASS" : "FAILURES PRESENT");
            server.halt(false);
        }
    }

    private static boolean runSmithingChecks(MinecraftServer server, Player player) {
        ItemStack template = new ItemStack(Endrise.ENDERIUM_UPGRADE_TEMPLATE.get());
        ItemStack ingot = new ItemStack(Endrise.ENDERIUM_INGOT.get());
        Holder<Enchantment> efficiency = server.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);

        ItemStack usedPick = new ItemStack(Items.NETHERITE_PICKAXE);
        usedPick.enchant(efficiency, 5);
        usedPick.setDamageValue(100);

        boolean ok = true;
        ItemStack upgraded = smithResult(player, template, usedPick, ingot);
        ok &= report(upgraded.is(Endrise.ENDERIUM_PICKAXE.get()),
                "smithing: netherite pick + template + ingot becomes the enderium pickaxe");
        ok &= report(EnchantmentHelper.getItemEnchantmentLevel(efficiency, upgraded) == 5
                        && upgraded.getDamageValue() == 100,
                "smithing: enchantments and damage carry through the upgrade");
        ok &= report(smithResult(player, template, new ItemStack(Items.NETHERITE_CHESTPLATE), ingot)
                        .is(Endrise.ENDERIUM_CHESTPLATE.get()),
                "smithing: netherite chestplate upgrades to enderium chestplate");
        ok &= report(smithResult(player, template, new ItemStack(Items.DIAMOND_PICKAXE), ingot).isEmpty(),
                "smithing: diamond gear is refused (enderium sits above netherite)");

        SmithingMenu gui = new SmithingMenu(1, player.getInventory());
        ok &= report(gui.getSlot(0).mayPlace(template), "smithing GUI: template accepted");
        ok &= report(gui.getSlot(1).mayPlace(new ItemStack(Items.NETHERITE_PICKAXE)),
                "smithing GUI: netherite base accepted");
        ok &= report(gui.getSlot(2).mayPlace(ingot), "smithing GUI: enderium ingot accepted");
        return ok;
    }

    private static ItemStack smithResult(Player player, ItemStack template, ItemStack base, ItemStack addition) {
        SmithingMenu menu = new SmithingMenu(1, player.getInventory());
        menu.getSlot(0).set(template.copy());
        menu.getSlot(1).set(base.copy());
        menu.getSlot(2).set(addition.copy());
        menu.createResult();
        return menu.getSlot(3).getItem();
    }

    private static boolean runAnvilChecks(MinecraftServer server, Player player) {
        ItemStack soulboundBook = Soulbound.book(server.registryAccess());
        Holder<Enchantment> soulbound = server.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Soulbound.KEY);

        ItemStack soulboundEnderiumPick = new ItemStack(Endrise.ENDERIUM_PICKAXE.get());
        soulboundEnderiumPick.enchant(soulbound, 1);

        ItemStack damagedNetheritePick = new ItemStack(Items.NETHERITE_PICKAXE);
        damagedNetheritePick.setDamageValue(500);

        boolean ok = true;
        ok &= report(anvilResult(player, new ItemStack(Endrise.ENDERIUM_PICKAXE.get()), soulboundBook)
                        .is(Endrise.ENDERIUM_PICKAXE.get()),
                "anvil: soulbound book applies to enderium gear");
        ok &= report(anvilResult(player, damagedNetheritePick, soulboundBook).isEmpty(),
                "anvil: soulbound book refused on non-enderium gear");
        ok &= report(anvilResult(player, damagedNetheritePick, soulboundEnderiumPick).isEmpty(),
                "anvil: soulbound sacrifice refused on non-enderium gear");
        ok &= report(!anvilResult(player, damagedNetheritePick, new ItemStack(Items.NETHERITE_PICKAXE)).isEmpty(),
                "anvil control: ordinary netherite repair unaffected");
        return ok;
    }

    private static ItemStack anvilResult(Player player, ItemStack left, ItemStack right) {
        AnvilMenu menu = new AnvilMenu(1, player.getInventory());
        menu.getSlot(0).set(left.copy());
        menu.getSlot(1).set(right.copy());
        menu.createResult();
        return menu.getSlot(2).getItem();
    }

    private static boolean report(boolean pass, String label) {
        Endrise.LOGGER.info("[SELFTEST] {}: {}", pass ? "PASS" : "FAIL", label);
        return pass;
    }

    /** The tab builds lazily on first open; force it so a broken generator fails here. */
    private static boolean runCreativeTabCheck(MinecraftServer server) {
        CreativeModeTab tab = Endrise.ENDRISE_TAB.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.DEFAULT_FLAGS, false, server.registryAccess()));
        var items = tab.getDisplayItems();
        boolean hasBook = items.stream().anyMatch(s -> s.is(Items.ENCHANTED_BOOK)
                && s.has(DataComponents.STORED_ENCHANTMENTS));
        return report(items.size() == 14 && hasBook,
                "creative tab: 14 entries incl. soulbound book (got " + items.size() + ")");
    }
}
