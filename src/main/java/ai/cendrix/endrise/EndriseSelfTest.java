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
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

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
        immediateOk &= runMasonryChecks(server);
        immediateOk &= runBloomChecks(server);
        immediateOk &= runCenotaphChecks(server);
        immediateOk &= runWayHomeChecks(server);
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
            var spawn = level.getSharedSpawnPos();
            // Pin the drop lane loaded: on an empty server the boot tickets can
            // expire mid-test, and an unloaded chunk archives its entities
            // (UNLOADED_TO_CHUNK) before the scan ever sees them. The race only
            // bites on 26.x (late entity indexing) but both suites pin it.
            for (int cx = (spawn.getX() >> 4) - 1; cx <= ((spawn.getX() + 18) >> 4) + 1; cx++) {
                level.setChunkForced(cx, spawn.getZ() >> 4, true);
            }
            // Drops are spaced out: same-owner enderium stacks merge by design,
            // and merged test subjects made the 1.21.1 suite miscount captures
            player.setPos(spawn.getX() + 0.5, 80, spawn.getZ() + 0.5);
            tossed = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get(), 2), false);
            player.setPos(spawn.getX() + 6.5, 80, spawn.getZ() + 0.5);
            diamond = player.drop(new ItemStack(Items.DIAMOND_PICKAXE), false);
            player.setPos(spawn.getX() + 12.5, 80, spawn.getZ() + 0.5);
            deathDrop = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get()), false);
            net.minecraft.world.item.component.CustomData.update(DataComponents.CUSTOM_DATA,
                    deathDrop.getItem(), tag -> {
                        tag.putBoolean(VoidReturn.TAG_DEATH_DROP, true);
                        tag.remove(VoidReturn.TAG_OWNER);
                    });
            player.setPos(spawn.getX() + 18.5, 80, spawn.getZ() + 0.5);
            expiring = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get()), false);
        } else if (t == 6) {
            var stampData = tossed == null ? null : tossed.getItem().get(DataComponents.CUSTOM_DATA);
            voidOk &= report(stampData != null
                            && !stampData.copyTag().getString(VoidReturn.TAG_OWNER).isEmpty(),
                    "void: player toss stamps the dropper UUID into the stack");
            var indexed = level.getEntities(net.minecraft.world.entity.EntityType.ITEM, e -> true);
            Endrise.LOGGER.info("[SELFTEST-DBG] indexed items after ticks: {} (expect 4)", indexed.size());
            tossed.setPos(tossed.getX(), level.getMinBuildHeight() - 10, tossed.getZ());
            diamond.setPos(diamond.getX(), level.getMinBuildHeight() - 10, diamond.getZ());
            deathDrop.setPos(deathDrop.getX(), level.getMinBuildHeight() - 10, deathDrop.getZ());
            expiring.lifespan = 1;
            expiring.tick();
            expiring.tick();
        } else if (t == 80) {
            // t=80, not 30: the empty-server entity index on 26.x can lag the
            // spawn drops past tick 30 (players online never see this; the scan
            // just needs the index to exist). 80 ticks is 5x the observed lag.
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
            // "Not captured" is asserted against the captured LIST, never entity
            // state: on 1.21.1 spawn chunks tick, so uncaptured items keep
            // falling until vanilla itself void-kills (DISCARDED) them, while on
            // 26.x they hover unticked. The list is the same on both.
            voidOk &= report(captured.stream().noneMatch(p -> p.stack().is(Items.DIAMOND_PICKAXE)),
                    "void: non-enderium items are not captured");
            voidOk &= report(captured.stream().filter(p -> p.stack().getCount() == 1).count() == 1,
                    "void: death-flagged drops are left for Soulbound (only expiring's single)");

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

            // Marked and clean stacks must never merge (marker laundering regression)
            ItemStack marked = new ItemStack(Endrise.ENDERIUM_INGOT.get());
            net.minecraft.world.item.component.CustomData.update(DataComponents.CUSTOM_DATA,
                    marked, tag -> tag.putString(VoidReturn.TAG_OWNER, player.getStringUUID()));
            voidOk &= report(!ItemStack.isSameItemSameComponents(marked, new ItemStack(Endrise.ENDERIUM_INGOT.get())),
                    "void: marked stacks refuse to merge with clean ones");

            // Toss below the kill plane is captured synchronously (panic-drop regression)
            player.setPos(player.getX(), level.getMinBuildHeight() - 70, player.getZ());
            ItemEntity deepToss = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get()), false);
            player.setPos(player.getX(), 80, player.getZ());
            var deepCaptured = store.takeReady(player.getUUID(), farFuture);
            voidOk &= report(deepToss != null && deepToss.getItem().isEmpty() && deepCaptured.size() == 1,
                    "void: toss below the kill plane is captured at toss time");

            // Delivery waits out the death screen (respawn-wipe regression)
            int baseline = player.getInventory().countItem(Endrise.ENDERIUM_INGOT.get());
            store.add(player.getUUID(), new SoulboundStore.Pending(VoidReturn.SLOT_ANY,
                    new ItemStack(Endrise.ENDERIUM_INGOT.get()), level.getGameTime()));
            player.setHealth(0.0F);
            boolean heldWhileDead = !SoulboundEvents.deliverPendingFor(store, (ServerPlayer) player, farFuture);
            player.setHealth(20.0F);
            boolean deliveredAfter = SoulboundEvents.deliverPendingFor(store, (ServerPlayer) player, farFuture);
            voidOk &= report(heldWhileDead && deliveredAfter
                            && player.getInventory().countItem(Endrise.ENDERIUM_INGOT.get()) == baseline + 1,
                    "void: delivery waits out the death screen, lands after revival");

            // The store must round-trip a pending entry through NBT (save-crash regression)
            SoulboundStore probe = new SoulboundStore();
            probe.add(player.getUUID(), new SoulboundStore.Pending(3,
                    new ItemStack(Endrise.ENDERIUM_INGOT.get()), 42L));
            var savedTag = probe.save(new net.minecraft.nbt.CompoundTag(), server.registryAccess());
            SoulboundStore reloaded = SoulboundStore.FACTORY.deserializer()
                    .apply(savedTag, server.registryAccess());
            voidOk &= report(reloaded.takeReady(player.getUUID(), 100L).size() == 1,
                    "store: NBT save/load round-trips a pending entry");

            // Death-hint predicate: flips off once the gear is soulbound
            player.getInventory().clearContent();
            player.getInventory().setItem(2, new ItemStack(Endrise.ENDERIUM_PICKAXE.get()));
            boolean unbound = SoulboundEvents.carriesUnboundEnderium(server.registryAccess(), (ServerPlayer) player);
            player.getInventory().getItem(2).enchant(server.registryAccess()
                    .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Soulbound.KEY), 1);
            boolean bound = !SoulboundEvents.carriesUnboundEnderium(server.registryAccess(), (ServerPlayer) player);
            player.getInventory().clearContent();
            voidOk &= report(unbound && bound, "hint: unbound-enderium detection flips with Soulbound");

            boolean ok = immediateOk && voidOk;
            Endrise.LOGGER.info("[SELFTEST] {}", ok ? "ALL PASS" : "FAILURES PRESENT");
            server.halt(false);
        }
    }

    private static boolean runMasonryChecks(MinecraftServer server) {
        var level = server.overworld();
        var rm = server.getRecipeManager();
        boolean ok = true;

        var nineIngots = java.util.Collections.nCopies(9,
                new ItemStack(Endrise.ENDERIUM_INGOT.get()));
        var compress = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(3, 3, new java.util.ArrayList<>(nineIngots)), level);
        ok &= report(compress.isPresent()
                        && compress.get().value().assemble(
                                net.minecraft.world.item.crafting.CraftingInput.of(3, 3, new java.util.ArrayList<>(nineIngots)),
                                server.registryAccess()).is(Endrise.ENDERIUM_BLOCK_ITEM.get()),
                "masonry: 9 ingots craft the enderium block");
        var expand = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(1, 1,
                        java.util.List.of(new ItemStack(Endrise.ENDERIUM_BLOCK_ITEM.get()))), level);
        ok &= report(expand.isPresent(), "masonry: the block uncrafts back to ingots");

        ok &= report(rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.STONECUTTING,
                        new net.minecraft.world.item.crafting.SingleRecipeInput(
                                new ItemStack(net.minecraft.world.item.Items.END_STONE)), level).isPresent(),
                "masonry: stonecutter accepts end stone");
        boolean allCutPaths = true;
        for (String cut : new String[] {"polished_end_stone_from_end_stone_stonecutting",
                "chiseled_end_stone_tiles_from_end_stone_stonecutting",
                "end_stone_tile_slab_from_polished_end_stone_stonecutting"}) {
            allCutPaths &= rm.byKey(Endrise.id(cut)).isPresent();
        }
        ok &= report(allCutPaths, "masonry: stonecutting family paths are registered");

        ok &= report(Endrise.ENDERIUM_BLOCK.get().defaultBlockState()
                        .is(net.minecraft.tags.BlockTags.BEACON_BASE_BLOCKS),
                "masonry: enderium block is a beacon base");
        ok &= report(Endrise.ENDERIUM_LANTERN.get().defaultBlockState().getLightEmission() == 15,
                "masonry: lantern shines at light 15");
        var fourEndStone = java.util.Collections.nCopies(4,
                new ItemStack(net.minecraft.world.item.Items.END_STONE));
        var bricks = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(2, 2, new java.util.ArrayList<>(fourEndStone)), level);
        ok &= report(bricks.isPresent()
                        && bricks.get().value().assemble(
                                net.minecraft.world.item.crafting.CraftingInput.of(2, 2, new java.util.ArrayList<>(fourEndStone)),
                                server.registryAccess()).is(net.minecraft.world.item.Items.END_STONE_BRICKS),
                "masonry: vanilla end stone bricks recipe is untouched (no 2x2 collision)");
        return ok;
    }

    private static boolean runBloomChecks(MinecraftServer server) {
        boolean ok = true;
        ServerLevel ow = server.overworld();
        var base = new net.minecraft.core.BlockPos(8, 200, 8);
        ow.setBlockAndUpdate(base, net.minecraft.world.level.block.Blocks.END_STONE.defaultBlockState());
        var bloomState = Endrise.MOURNING_BLOOM.get().defaultBlockState();
        ok &= report(bloomState.canSurvive(ow, base.above()), "bloom: plants on end stone");
        ow.setBlockAndUpdate(base, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        ok &= report(!bloomState.canSurvive(ow, base.above()), "bloom: refuses ordinary stone");
        ow.setBlockAndUpdate(base, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

        var rm = server.getRecipeManager();
        var petals = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(1, 1,
                        java.util.List.of(new ItemStack(Endrise.MOURNING_BLOOM_ITEM.get()))), ow);
        ok &= report(petals.isPresent(), "bloom: one bloom crafts into void petals");
        var draught = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(2, 1, java.util.List.of(
                        new ItemStack(Endrise.VOID_PETAL.get()),
                        new ItemStack(Items.GLASS_BOTTLE))), ow);
        ok &= report(draught.isPresent(), "bloom: petal + bottle brews the draught");
        var bottle = new ItemStack(Endrise.DRAUGHT_OF_RETURN.get());
        ok &= report(bottle.has(DataComponents.FOOD)
                        && bottle.get(DataComponents.FOOD).usingConvertsTo().isPresent(),
                "draught: drinkable, bottle returned");

        ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
        ok &= report(end != null, "bloom: the End level is loaded");
        if (end != null) {
            var site = new net.minecraft.core.BlockPos(100, 60, 100);
            end.setBlockAndUpdate(site, net.minecraft.world.level.block.Blocks.END_STONE.defaultBlockState());
            end.setBlockAndUpdate(site.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            boolean placed = BloomEvents.placeDeathBloom(end, site.above());
            ok &= report(placed && end.getBlockState(site.above()).is(Endrise.MOURNING_BLOOM.get()),
                    "bloom: a death in the End plants a bloom");
            end.setBlockAndUpdate(site.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            end.setBlockAndUpdate(site, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

            // A distinct profile: the default factory fake is cached on the overworld
            ServerPlayer endPlayer = FakePlayerFactory.get(end,
                    new com.mojang.authlib.GameProfile(
                            java.util.UUID.nameUUIDFromBytes("endrise-end-tester".getBytes()),
                            "EndriseEndTester"));
            var home = new net.minecraft.core.BlockPos(200, 180, 200); // far above any island: guaranteed air
            endPlayer.setData(Endrise.RETURN_ANCHOR.get(),
                    net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.END, home));
            // RETURNED proves the full decision chain (anchor read, dimension rules,
            // air-spot search, award). The final teleportTo is vanilla's, and fake
            // players' stub connections swallow it, so position is not asserted here.
            ok &= report(BloomEvents.completeReturn(endPlayer) == BloomEvents.ReturnResult.RETURNED,
                    "draught: natural expiry in the End returns to the anchor");
            endPlayer.setData(Endrise.RETURN_ANCHOR.get(),
                    net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, home));
            ok &= report(BloomEvents.completeReturn(endPlayer) == BloomEvents.ReturnResult.FIZZLED,
                    "draught: an overworld anchor fizzles");

            // Corpse-tick regression: expiry on the death screen must not fire the return
            endPlayer.setData(Endrise.RETURN_ANCHOR.get(),
                    net.minecraft.core.GlobalPos.of(net.minecraft.world.level.Level.END, home));
            endPlayer.setHealth(0.0F);
            BloomEvents.onEffectExpired(new MobEffectEvent.Expired(endPlayer,
                    new net.minecraft.world.effect.MobEffectInstance(Endrise.RETURN_EFFECT, 1, 0)));
            boolean heldThroughDeath = endPlayer.hasData(Endrise.RETURN_ANCHOR.get());
            endPlayer.setHealth(20.0F);
            endPlayer.removeData(Endrise.RETURN_ANCHOR.get());
            ok &= report(heldThroughDeath, "draught: expiry on a corpse is ignored");
        }
        return ok;
    }

    private static boolean runWayHomeChecks(MinecraftServer server) {
        boolean ok = true;
        ServerLevel overworld = server.overworld();

        // The pearl recipe: ender pearl center, petals cardinal, ingots corners
        var rm = server.getRecipeManager();
        var grid = java.util.List.of(
                new ItemStack(Endrise.ENDERIUM_INGOT.get()), new ItemStack(Endrise.VOID_PETAL.get()),
                new ItemStack(Endrise.ENDERIUM_INGOT.get()), new ItemStack(Endrise.VOID_PETAL.get()),
                new ItemStack(Items.ENDER_PEARL), new ItemStack(Endrise.VOID_PETAL.get()),
                new ItemStack(Endrise.ENDERIUM_INGOT.get()), new ItemStack(Endrise.VOID_PETAL.get()),
                new ItemStack(Endrise.ENDERIUM_INGOT.get()));
        var pearlRecipe = rm.getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING,
                net.minecraft.world.item.crafting.CraftingInput.of(3, 3, new java.util.ArrayList<>(grid)), overworld);
        ok &= report(pearlRecipe.isPresent()
                        && pearlRecipe.get().value().assemble(
                                net.minecraft.world.item.crafting.CraftingInput.of(3, 3, new java.util.ArrayList<>(grid)),
                                server.registryAccess()).is(Endrise.HOMEWARD_PEARL.get()),
                "way home: pearl + petals + ingots craft the Homeward Pearl");

        // The full 8-node tab, in covenant order
        String[][] chain = {
                {"root", null}, {"deeper_than_diamonds", "root"}, {"bound", "deeper_than_diamonds"},
                {"the_tier_above", "bound"}, {"nothing_is_lost", "the_tier_above"},
                {"there_and_back", "nothing_is_lost"}, {"someone_was_here", "there_and_back"},
                {"the_way_home", "someone_was_here"}};
        boolean chainOk = true;
        for (String[] link : chain) {
            var adv = server.getAdvancements().get(Endrise.id(link[0]));
            chainOk &= adv != null && (link[1] == null
                    ? adv.value().parent().isEmpty()
                    : adv.value().parent().map(par -> par.equals(Endrise.id(link[1]))).orElse(false));
        }
        ok &= report(chainOk, "way home: the eight-node advancement chain is wired in order");

        ok &= report(Endrise.ENDERIUM_ORE.get() instanceof GlimmerBlock
                        && Endrise.ENDERIUM_BLOCK.get() instanceof GlimmerBlock,
                "way home: ore and block glimmer (animateTick subclasses)");

        // Resolution from the End: a spawnless player resolves OUT of the End to
        // the overworld world-spawn fallback, and resolution alone never touches
        // the credits flag. (Cross-dim teleport mechanics are vanilla's; per the
        // fake-player doctrine we assert the decision, not the landing.)
        ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
        ServerPlayer endPlayer = net.neoforged.neoforge.common.util.FakePlayerFactory.get(end,
                new com.mojang.authlib.GameProfile(
                        java.util.UUID.nameUUIDFromBytes("endrise-wayhome-end".getBytes()),
                        "EndriseWayHomeEnd"));
        var resolved = endPlayer.findRespawnPositionAndUseSpawnBlock(true,
                net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING);
        ok &= report(resolved.newLevel() == overworld && !endPlayer.seenCredits,
                "way home: spawnless resolution leaves the End for world spawn, credits untouched");

        // The inverted-polarity regression: the pearl flag must NOT spend a
        // respawn anchor charge, the death flag MUST. This boolean is the most
        // drift-prone value in the tide (26.x consumeSpawnBlock=false keeps the
        // charge; on 1.21.1 the keep-flag is true), and without a charged
        // anchor in play it is dead code to every other test.
        ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        var anchorPos = new net.minecraft.core.BlockPos(48, 100, 48);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                nether.setBlockAndUpdate(anchorPos.offset(dx, -1, dz),
                        net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
                for (int dy = 1; dy <= 2; dy++) {
                    nether.setBlockAndUpdate(anchorPos.offset(dx, dy, dz),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
                if (dx != 0 || dz != 0) {
                    nether.setBlockAndUpdate(anchorPos.offset(dx, 0, dz),
                            net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
        nether.setBlockAndUpdate(anchorPos, net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR
                .defaultBlockState().setValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE, 4));
        ServerPlayer anchored = net.neoforged.neoforge.common.util.FakePlayerFactory.get(nether,
                new com.mojang.authlib.GameProfile(
                        java.util.UUID.nameUUIDFromBytes("endrise-wayhome-anchor".getBytes()),
                        "EndriseWayHomeAnchor"));
        anchored.setRespawnPosition(net.minecraft.world.level.Level.NETHER, anchorPos, 0.0F, false, false);
        var pearlWay = anchored.findRespawnPositionAndUseSpawnBlock(true, net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING);
        int afterPearl = nether.getBlockState(anchorPos)
                .getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE);
        var deathWay = anchored.findRespawnPositionAndUseSpawnBlock(false, net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING);
        int afterDeath = nether.getBlockState(anchorPos)
                .getValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE);
        ok &= report(pearlWay.newLevel() == nether && afterPearl == 4
                        && deathWay.newLevel() == nether && afterDeath == 3,
                "way home: the pearl leaves anchor charges alone, death still spends one");

        var boundAdv = server.getAdvancements().get(Endrise.id("bound"));
        ok &= report(boundAdv != null && boundAdv.value().criteria().containsKey("bound"),
                "way home: Bound's anvil award criterion is wired");

        // The full use() on an overworld fake player (same-dimension travel):
        // consumed, cooldown armed, criterion wired, credits never shown.
        ServerPlayer user = net.neoforged.neoforge.common.util.FakePlayerFactory.get(overworld,
                new com.mojang.authlib.GameProfile(
                        java.util.UUID.nameUUIDFromBytes("endrise-wayhome-ow".getBytes()),
                        "EndriseWayHomeOw"));
        user.getInventory().clearContent();
        user.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(Endrise.HOMEWARD_PEARL.get(), 2));
        var first = Endrise.HOMEWARD_PEARL.get().use(overworld, user, net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack after = user.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
        boolean consumedOnce = first.getResult() == net.minecraft.world.InteractionResult.CONSUME
                && after.getCount() == 1;
        boolean cooling = user.getCooldowns().isOnCooldown(Endrise.HOMEWARD_PEARL.get());
        var second = Endrise.HOMEWARD_PEARL.get().use(overworld, user, net.minecraft.world.InteractionHand.MAIN_HAND);
        boolean blocked = second.getResult() == net.minecraft.world.InteractionResult.PASS
                && user.getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND).getCount() == 1;
        ok &= report(consumedOnce && cooling && blocked,
                "way home: one pearl spent, five-second cooldown blocks the second");
        // Fake players' PlayerAdvancements is a no-op stub, so progress can't be
        // asserted here; the award call ran inside use() (a broken criterion name
        // would still pass silently, hence the explicit criteria-key check).
        var wayHome = server.getAdvancements().get(Endrise.id("the_way_home"));
        ok &= report(wayHome != null && wayHome.value().criteria().containsKey("returned")
                        && !user.seenCredits,
                "way home: the award criterion is wired, the credits are not rolled");
        user.getInventory().clearContent();
        return ok;
    }

    private static boolean runCenotaphChecks(MinecraftServer server) {
        boolean ok = true;
        ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
        if (end == null) {
            return report(false, "cenotaph: the End level is loaded");
        }

        var structures = server.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var structure = structures.get(net.minecraft.resources.ResourceKey.create(
                Registries.STRUCTURE, Endrise.id("cenotaph")));
        var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        ok &= report(structure.isPresent()
                        && structure.get().value().biomes().contains(
                                biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.END_MIDLANDS))
                        && !structure.get().value().biomes().contains(
                                biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.THE_END)),
                "cenotaph: registered for the outer End, never the dragon island");

        var sets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        var set = sets.get(net.minecraft.resources.ResourceKey.create(
                Registries.STRUCTURE_SET, Endrise.id("cenotaphs")));
        boolean spacingOk = set.isPresent() && set.get().value().placement() instanceof
                net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement spread
                && spread.spacing() == 34 && spread.separation() == 12;
        ok &= report(spacingOk, "cenotaph: placement spaced 34 with separation 12");

        var pools = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
        var pool = pools.get(net.minecraft.resources.ResourceKey.create(
                Registries.TEMPLATE_POOL, Endrise.id("cenotaph/start")));
        ok &= report(pool.isPresent() && pool.get().value().size() == 4,
                "cenotaph: start pool holds the four ruins");

        var templates = server.getStructureManager();
        var expected = java.util.Map.of(
                "vigil", new net.minecraft.core.Vec3i(9, 6, 9),
                "waygate", new net.minecraft.core.Vec3i(10, 7, 10),
                "ring", new net.minecraft.core.Vec3i(11, 8, 11),
                "fallen_hall", new net.minecraft.core.Vec3i(12, 7, 12));
        boolean sizesOk = true;
        for (var e : expected.entrySet()) {
            var t = templates.get(Endrise.id("cenotaph/" + e.getKey()));
            sizesOk &= t.isPresent() && t.get().getSize().equals(e.getValue());
        }
        ok &= report(sizesOk, "cenotaph: all four templates load with authored sizes");

        // Real placements of ALL FOUR ruins with neighbor updates ON (flag 3):
        // furniture that would pop after placement pops right here, and every
        // chest must come out of the NBT wearing the loot table.
        boolean furnitureOk = true, lootWiredAll = true;
        int placedCount = 0;
        String[] ruinNames = {"vigil", "waygate", "ring", "fallen_hall"};
        for (int idx = 0; idx < ruinNames.length; idx++) {
            var t = templates.get(Endrise.id("cenotaph/" + ruinNames[idx]));
            if (t.isEmpty()) {
                furnitureOk = false;
                continue;
            }
            var origin = new net.minecraft.core.BlockPos(320 + idx * 48, 180, 320);
            if (t.get().placeInWorld(end, origin, origin,
                    new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
                    end.getRandom(), 3)) {
                placedCount++;
            }
            var size = t.get().getSize();
            int chests = 0, cores = 0, reliefs = 0, lanterns = 0, blooms = 0;
            net.minecraft.core.BlockPos chestPos = null;
            for (var p : net.minecraft.core.BlockPos.betweenClosed(origin,
                    origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
                var state = end.getBlockState(p);
                if (state.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                    chests++;
                    chestPos = p.immutable();
                } else if (state.is(Endrise.ENDERIUM_BLOCK.get())) {
                    cores++;
                } else if (state.is(Endrise.CHISELED_END_STONE_TILES.get())) {
                    reliefs++;
                } else if (state.is(Endrise.ENDERIUM_LANTERN.get())) {
                    lanterns++;
                } else if (state.is(Endrise.MOURNING_BLOOM.get())) {
                    blooms++;
                }
            }
            furnitureOk &= chests == 1 && cores == 1 && reliefs >= 1 && lanterns >= 1 && blooms == 3;
            lootWiredAll &= chestPos != null && end.getBlockEntity(chestPos) instanceof
                    net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity chest
                    && chest.getLootTable() != null
                    && chest.getLootTable().location().equals(Endrise.id("chests/cenotaph"));
        }
        ok &= report(placedCount == 4 && furnitureOk,
                "cenotaph: all four ruins place whole, furniture survives updates");
        ok &= report(lootWiredAll, "cenotaph: every chest wears the cenotaph loot table");

        var table = server.reloadableRegistries().getLootTable(
                net.minecraft.resources.ResourceKey.create(Registries.LOOT_TABLE, Endrise.id("chests/cenotaph")));
        var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(end)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        new net.minecraft.world.phys.Vec3(320.5, 180.5, 320.5))
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
        Holder<Enchantment> soulbound = server.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Soulbound.KEY);
        int gearRolls = 0, soulboundSeen = 0, templateSeen = 0, ferrenSeen = 0;
        boolean petalsOk = true, rawOk = true, wearOk = true;
        for (int i = 0; i < 100; i++) {
            int gear = 0, petals = 0, raw = 0;
            for (ItemStack s : table.getRandomItems(params)) {
                if (s.has(DataComponents.CUSTOM_NAME)) {
                    gear++;
                    if (EnchantmentHelper.getItemEnchantmentLevel(soulbound, s) > 0) {
                        soulboundSeen++;
                    }
                    if (s.is(Endrise.ENDERIUM_PICKAXE.get())) {
                        ferrenSeen++;
                    }
                    int max = s.getMaxDamage();
                    if (max > 0 && (s.getDamageValue() < max * 0.25 || s.getDamageValue() > max * 0.75)) {
                        wearOk = false;
                    }
                } else if (s.is(Endrise.VOID_PETAL.get())) {
                    petals += s.getCount();
                } else if (s.is(Endrise.RAW_ENDERIUM.get())) {
                    raw += s.getCount();
                } else if (s.is(Endrise.ENDERIUM_UPGRADE_TEMPLATE.get())) {
                    templateSeen++;
                }
            }
            if (gear == 1) {
                gearRolls++;
            }
            petalsOk &= petals >= 2 && petals <= 4;
            rawOk &= raw >= 2 && raw <= 5;
        }
        ok &= report(gearRolls == 100, "cenotaph loot: every chest holds exactly one named remembrance");
        ok &= report(soulboundSeen >= 25 && soulboundSeen <= 55,
                "cenotaph loot: soulbound rides ~40% of the gear (got " + soulboundSeen + "/100)");
        ok &= report(petalsOk && rawOk, "cenotaph loot: petals 2-4 and raw enderium 2-5 every time");
        ok &= report(templateSeen >= 4 && templateSeen <= 30,
                "cenotaph loot: upgrade template near 15% (got " + templateSeen + "/100)");
        ok &= report(ferrenSeen >= 1, "cenotaph loot: someone made it that far (enderium gear seen)");
        ok &= report(wearOk, "cenotaph loot: remembrances arrive worn, never broken");

        // Review regression: Soulbound from a chest rides VANILLA gear, and the
        // covenant must honor the mark, not the metal (protects() is enchant-only).
        ItemStack remembrance = new ItemStack(Items.IRON_SWORD);
        boolean unmarkedIgnored = !Soulbound.protects(server.registryAccess(), remembrance);
        remembrance.enchant(soulbound, 1);
        ok &= report(unmarkedIgnored && Soulbound.protects(server.registryAccess(), remembrance),
                "cenotaph loot: a marked remembrance is death-protected, an unmarked one is not");

        var found = end.getChunkSource().getGenerator().findNearestMapStructure(
                end, net.minecraft.core.HolderSet.direct(structure.get()),
                new net.minecraft.core.BlockPos(0, 64, 0), 40, false);
        ok &= report(found != null, "cenotaph: locate finds one within 40 chunks of origin");

        ok &= report(server.getAdvancements().get(Endrise.id("someone_was_here")) != null,
                "cenotaph: Someone Was Here is registered");
        return ok;
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
        return report(items.size() == 28 && hasBook,
                "creative tab: 28 entries incl. soulbound book (got " + items.size() + ")");
    }
}
