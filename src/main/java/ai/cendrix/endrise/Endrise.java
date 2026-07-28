package ai.cendrix.endrise;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.util.Map;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumables;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Endrise.MODID)
public class Endrise {
    public static final String MODID = "endrise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);

    // Legacy 0.2.x infusion marker. Kept registered so items from old worlds still
    // parse; carries no behavior since the v0.3 first-class gear redesign.
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Unit>> ENDERIUM_INFUSED =
            DATA_COMPONENTS.registerComponentType("enderium_infused",
                    b -> b.persistent(Unit.CODEC).networkSynchronized(Unit.STREAM_CODEC));

    // Enderium: the End's buried metal. Ore sits in end stone, drops raw enderium, smelts to ingots.
    // Where it spawns is data, not code: data/endrise/worldgen/* defines the veins,
    // data/endrise/neoforge/biome_modifier/ injects them into every #minecraft:is_end biome.
    public static final DeferredBlock<Block> ENDERIUM_ORE = BLOCKS.registerSimpleBlock("enderium_ore",
            p -> p.mapColor(MapColor.SAND)
                    .strength(3.0F, 9.0F) // end stone's hardness and blast resistance, so veins feel native
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE));
    public static final DeferredItem<BlockItem> ENDERIUM_ORE_ITEM = ITEMS.registerSimpleBlockItem("enderium_ore", ENDERIUM_ORE);

    // Materials float in lava and shrug off fire, netherite-style (Tide 4)
    public static final DeferredItem<Item> RAW_ENDERIUM = ITEMS.registerSimpleItem("raw_enderium",
            p -> p.fireResistant());
    public static final DeferredItem<Item> ENDERIUM_INGOT = ITEMS.registerSimpleItem("enderium_ingot",
            p -> p.fireResistant());
    public static final DeferredItem<Item> ENDERIUM_UPGRADE_TEMPLATE =
            ITEMS.registerSimpleItem("enderium_upgrade_smithing_template", p -> p.fireResistant());

    // ---- Enderium gear: the tier above netherite (v0.3). Netherite gear + the
    // upgrade template + an enderium ingot transmutes at the smithing table. ----
    public static final TagKey<Item> ENDERIUM_TOOL_MATERIALS =
            TagKey.create(Registries.ITEM, id("enderium_tool_materials"));
    public static final TagKey<Item> REPAIRS_ENDERIUM_ARMOR =
            TagKey.create(Registries.ITEM, id("repairs_enderium_armor"));
    /** The gear the End gives back: exactly the enderium items. */
    public static final TagKey<Item> SOULBOUND_ABLE =
            TagKey.create(Registries.ITEM, id("soulbound_able"));

    public static final ToolMaterial ENDERIUM_TOOL_MATERIAL = new ToolMaterial(
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 2531, 10.0F, 5.0F, 17, ENDERIUM_TOOL_MATERIALS);

    public static final ResourceKey<EquipmentAsset> ENDERIUM_EQUIPMENT_ASSET =
            ResourceKey.create(EquipmentAssets.ROOT_ID, id("enderium"));
    public static final ArmorMaterial ENDERIUM_ARMOR_MATERIAL = new ArmorMaterial(
            41,
            Map.of(ArmorType.BOOTS, 3, ArmorType.LEGGINGS, 6, ArmorType.CHESTPLATE, 8,
                    ArmorType.HELMET, 3, ArmorType.BODY, 19),
            17, SoundEvents.ARMOR_EQUIP_NETHERITE, 3.5F, 0.15F,
            REPAIRS_ENDERIUM_ARMOR, ENDERIUM_EQUIPMENT_ASSET);

    // Baselines copied from vanilla netherite (Items.java); the material carries the tier.
    public static final DeferredItem<Item> ENDERIUM_SWORD = ITEMS.registerSimpleItem("enderium_sword",
            p -> p.sword(ENDERIUM_TOOL_MATERIAL, 3.0F, -2.4F).fireResistant());
    public static final DeferredItem<Item> ENDERIUM_PICKAXE = ITEMS.registerSimpleItem("enderium_pickaxe",
            p -> p.pickaxe(ENDERIUM_TOOL_MATERIAL, 1.0F, -2.8F).fireResistant());
    public static final DeferredItem<AxeItem> ENDERIUM_AXE = ITEMS.registerItem("enderium_axe",
            p -> new AxeItem(ENDERIUM_TOOL_MATERIAL, 5.0F, -3.0F, p.fireResistant()));
    public static final DeferredItem<ShovelItem> ENDERIUM_SHOVEL = ITEMS.registerItem("enderium_shovel",
            p -> new ShovelItem(ENDERIUM_TOOL_MATERIAL, 1.5F, -3.0F, p.fireResistant()));
    public static final DeferredItem<HoeItem> ENDERIUM_HOE = ITEMS.registerItem("enderium_hoe",
            p -> new HoeItem(ENDERIUM_TOOL_MATERIAL, -4.0F, 0.0F, p.fireResistant()));

    public static final DeferredItem<Item> ENDERIUM_HELMET = ITEMS.registerSimpleItem("enderium_helmet",
            p -> p.humanoidArmor(ENDERIUM_ARMOR_MATERIAL, ArmorType.HELMET).fireResistant());
    public static final DeferredItem<Item> ENDERIUM_CHESTPLATE = ITEMS.registerSimpleItem("enderium_chestplate",
            p -> p.humanoidArmor(ENDERIUM_ARMOR_MATERIAL, ArmorType.CHESTPLATE).fireResistant());
    public static final DeferredItem<Item> ENDERIUM_LEGGINGS = ITEMS.registerSimpleItem("enderium_leggings",
            p -> p.humanoidArmor(ENDERIUM_ARMOR_MATERIAL, ArmorType.LEGGINGS).fireResistant());
    public static final DeferredItem<Item> ENDERIUM_BOOTS = ITEMS.registerSimpleItem("enderium_boots",
            p -> p.humanoidArmor(ENDERIUM_ARMOR_MATERIAL, ArmorType.BOOTS).fireResistant());

    // ---- Tide 5: Set in Stone. The End's masonry; the palette the Cenotaphs
    // are built from. Worked stone keeps end stone's stats; metals mirror their
    // vanilla counterparts. ----
    private static BlockBehaviour.Properties stoneProps() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.SAND)
                .strength(3.0F, 9.0F).sound(SoundType.STONE);
    }

    public static final DeferredBlock<Block> POLISHED_END_STONE =
            BLOCKS.registerBlock("polished_end_stone", Block::new, Endrise::stoneProps);
    public static final DeferredBlock<Block> END_STONE_TILES =
            BLOCKS.registerBlock("end_stone_tiles", Block::new, Endrise::stoneProps);
    public static final DeferredBlock<Block> CHISELED_END_STONE_TILES =
            BLOCKS.registerBlock("chiseled_end_stone_tiles", Block::new, Endrise::stoneProps);
    public static final DeferredBlock<StairBlock> POLISHED_END_STONE_STAIRS =
            BLOCKS.registerBlock("polished_end_stone_stairs",
                    p -> new StairBlock(POLISHED_END_STONE.get().defaultBlockState(), p), Endrise::stoneProps);
    public static final DeferredBlock<StairBlock> END_STONE_TILE_STAIRS =
            BLOCKS.registerBlock("end_stone_tile_stairs",
                    p -> new StairBlock(END_STONE_TILES.get().defaultBlockState(), p), Endrise::stoneProps);
    public static final DeferredBlock<SlabBlock> POLISHED_END_STONE_SLAB =
            BLOCKS.registerBlock("polished_end_stone_slab", SlabBlock::new, Endrise::stoneProps);
    public static final DeferredBlock<SlabBlock> END_STONE_TILE_SLAB =
            BLOCKS.registerBlock("end_stone_tile_slab", SlabBlock::new, Endrise::stoneProps);

    public static final DeferredBlock<Block> ENDERIUM_BLOCK =
            BLOCKS.registerBlock("enderium_block", Block::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WARPED_NYLIUM).strength(50.0F, 1200.0F)
                    .requiresCorrectToolForDrops().sound(SoundType.NETHERITE_BLOCK));
    public static final DeferredBlock<Block> RAW_ENDERIUM_BLOCK =
            BLOCKS.registerBlock("raw_enderium_block", Block::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WARPED_NYLIUM).strength(5.0F, 6.0F)
                    .requiresCorrectToolForDrops().sound(SoundType.STONE));
    public static final DeferredBlock<EnderiumLanternBlock> ENDERIUM_LANTERN =
            BLOCKS.registerBlock("enderium_lantern", EnderiumLanternBlock::new,
                    () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).forceSolidOn()
                            .requiresCorrectToolForDrops().strength(3.5F)
                            .sound(SoundType.LANTERN).lightLevel(state -> 15).noOcclusion()
                            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY));

    public static final DeferredItem<BlockItem> POLISHED_END_STONE_ITEM =
            ITEMS.registerSimpleBlockItem("polished_end_stone", POLISHED_END_STONE);
    public static final DeferredItem<BlockItem> END_STONE_TILES_ITEM =
            ITEMS.registerSimpleBlockItem("end_stone_tiles", END_STONE_TILES);
    public static final DeferredItem<BlockItem> CHISELED_END_STONE_TILES_ITEM =
            ITEMS.registerSimpleBlockItem("chiseled_end_stone_tiles", CHISELED_END_STONE_TILES);
    public static final DeferredItem<BlockItem> POLISHED_END_STONE_STAIRS_ITEM =
            ITEMS.registerSimpleBlockItem("polished_end_stone_stairs", POLISHED_END_STONE_STAIRS);
    public static final DeferredItem<BlockItem> END_STONE_TILE_STAIRS_ITEM =
            ITEMS.registerSimpleBlockItem("end_stone_tile_stairs", END_STONE_TILE_STAIRS);
    public static final DeferredItem<BlockItem> POLISHED_END_STONE_SLAB_ITEM =
            ITEMS.registerSimpleBlockItem("polished_end_stone_slab", POLISHED_END_STONE_SLAB);
    public static final DeferredItem<BlockItem> END_STONE_TILE_SLAB_ITEM =
            ITEMS.registerSimpleBlockItem("end_stone_tile_slab", END_STONE_TILE_SLAB);
    public static final DeferredItem<BlockItem> ENDERIUM_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("enderium_block", ENDERIUM_BLOCK);
    public static final DeferredItem<BlockItem> RAW_ENDERIUM_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("raw_enderium_block", RAW_ENDERIUM_BLOCK);
    public static final DeferredItem<BlockItem> ENDERIUM_LANTERN_ITEM =
            ITEMS.registerSimpleBlockItem("enderium_lantern", ENDERIUM_LANTERN);

    // ---- Tide 6: Mourning Blooms + the petal economy ----
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, MODID);
    /** Marker effect: the teleport itself happens in BloomEvents on natural expiry. */
    public static final DeferredHolder<MobEffect, MobEffect> RETURN_EFFECT =
            MOB_EFFECTS.register("return", () -> new MobEffect(MobEffectCategory.BENEFICIAL, 0x2FC39D) {});

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MODID);
    /** Where the draught was drunk; persists across logout with the player. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<GlobalPos>> RETURN_ANCHOR =
            ATTACHMENTS.register("return_anchor", () -> AttachmentType
                    .builder(() -> GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO))
                    .serialize(GlobalPos.MAP_CODEC).build());

    public static final DeferredBlock<MourningBloomBlock> MOURNING_BLOOM =
            BLOCKS.registerBlock("mourning_bloom", MourningBloomBlock::new,
                    () -> BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE)
                            .noCollision().instabreak().sound(SoundType.GRASS)
                            .lightLevel(state -> 7)
                            .offsetType(BlockBehaviour.OffsetType.XZ)
                            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY));
    // 26.x FlowerPotBlock(content, props) self-registers with the vanilla pot
    public static final DeferredBlock<FlowerPotBlock> POTTED_MOURNING_BLOOM =
            BLOCKS.registerBlock("potted_mourning_bloom",
                    p -> new FlowerPotBlock(MOURNING_BLOOM.get(), p),
                    () -> BlockBehaviour.Properties.of().instabreak().noOcclusion()
                            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY));
    public static final DeferredItem<BlockItem> MOURNING_BLOOM_ITEM =
            ITEMS.registerSimpleBlockItem("mourning_bloom", MOURNING_BLOOM);

    public static final DeferredItem<Item> VOID_PETAL = ITEMS.registerSimpleItem("void_petal");
    public static final DeferredItem<Item> DRAUGHT_OF_RETURN = ITEMS.registerSimpleItem("draught_of_return",
            p -> p.stacksTo(16)
                    .craftRemainder(Items.GLASS_BOTTLE)
                    .usingConvertsTo(Items.GLASS_BOTTLE)
                    .food(new FoodProperties.Builder().alwaysEdible().build(),
                            Consumables.defaultDrink()
                                    .onConsume(new ApplyStatusEffectsConsumeEffect(
                                            new MobEffectInstance(RETURN_EFFECT, 1800, 0)))
                                    .build()));

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Everything Endrise adds, in one place (items stay in the vanilla category tabs too)
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ENDRISE_TAB =
            CREATIVE_TABS.register("endrise", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.endrise"))
                    .icon(() -> new ItemStack(ENDERIUM_INGOT.get()))
                    .displayItems((params, output) -> {
                        output.accept(ENDERIUM_ORE_ITEM.get());
                        output.accept(RAW_ENDERIUM.get());
                        output.accept(ENDERIUM_INGOT.get());
                        output.accept(ENDERIUM_UPGRADE_TEMPLATE.get());
                        output.accept(ENDERIUM_SWORD.get());
                        output.accept(ENDERIUM_PICKAXE.get());
                        output.accept(ENDERIUM_AXE.get());
                        output.accept(ENDERIUM_SHOVEL.get());
                        output.accept(ENDERIUM_HOE.get());
                        output.accept(ENDERIUM_HELMET.get());
                        output.accept(ENDERIUM_CHESTPLATE.get());
                        output.accept(ENDERIUM_LEGGINGS.get());
                        output.accept(ENDERIUM_BOOTS.get());
                        output.accept(POLISHED_END_STONE_ITEM.get());
                        output.accept(POLISHED_END_STONE_STAIRS_ITEM.get());
                        output.accept(POLISHED_END_STONE_SLAB_ITEM.get());
                        output.accept(END_STONE_TILES_ITEM.get());
                        output.accept(END_STONE_TILE_STAIRS_ITEM.get());
                        output.accept(END_STONE_TILE_SLAB_ITEM.get());
                        output.accept(CHISELED_END_STONE_TILES_ITEM.get());
                        output.accept(RAW_ENDERIUM_BLOCK_ITEM.get());
                        output.accept(ENDERIUM_BLOCK_ITEM.get());
                        output.accept(ENDERIUM_LANTERN_ITEM.get());
                        output.accept(MOURNING_BLOOM_ITEM.get());
                        output.accept(VOID_PETAL.get());
                        output.accept(DRAUGHT_OF_RETURN.get());
                        output.accept(Soulbound.book(params.holders()));
                    })
                    .build());

    public Endrise(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        MOB_EFFECTS.register(modEventBus);
        ATTACHMENTS.register(modEventBus);

        modEventBus.addListener(this::addCreative);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Endrise: the tide is coming in (Enderium online)");
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(ENDERIUM_ORE_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(RAW_ENDERIUM);
            event.accept(ENDERIUM_INGOT);
            event.accept(ENDERIUM_UPGRADE_TEMPLATE);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ENDERIUM_PICKAXE);
            event.accept(ENDERIUM_AXE);
            event.accept(ENDERIUM_SHOVEL);
            event.accept(ENDERIUM_HOE);
        }
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(POLISHED_END_STONE_ITEM);
            event.accept(POLISHED_END_STONE_STAIRS_ITEM);
            event.accept(POLISHED_END_STONE_SLAB_ITEM);
            event.accept(END_STONE_TILES_ITEM);
            event.accept(END_STONE_TILE_STAIRS_ITEM);
            event.accept(END_STONE_TILE_SLAB_ITEM);
            event.accept(CHISELED_END_STONE_TILES_ITEM);
            event.accept(RAW_ENDERIUM_BLOCK_ITEM);
            event.accept(ENDERIUM_BLOCK_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(MOURNING_BLOOM_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(VOID_PETAL);
        }
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(DRAUGHT_OF_RETURN);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ENDERIUM_LANTERN_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ENDERIUM_SWORD);
            event.accept(ENDERIUM_HELMET);
            event.accept(ENDERIUM_CHESTPLATE);
            event.accept(ENDERIUM_LEGGINGS);
            event.accept(ENDERIUM_BOOTS);
        }
    }
}
