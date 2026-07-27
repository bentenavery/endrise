package ai.cendrix.endrise;

import org.slf4j.Logger;

import com.google.common.base.Suppliers;
import com.mojang.logging.LogUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
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
                    b -> b.persistent(Unit.CODEC).networkSynchronized(StreamCodec.unit(Unit.INSTANCE)));

    // Enderium: the End's buried metal. Ore sits in end stone, drops raw enderium, smelts to ingots.
    // Where it spawns is data, not code: data/endrise/worldgen/* defines the veins,
    // data/endrise/neoforge/biome_modifier/ injects them into every #minecraft:is_end biome.
    // 1.21.1 API: registerSimpleBlock takes Properties directly (newer versions take an operator lambda)
    public static final DeferredBlock<Block> ENDERIUM_ORE = BLOCKS.registerSimpleBlock("enderium_ore",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SAND)
                    .strength(3.0F, 9.0F) // end stone's hardness and blast resistance, so veins feel native
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE));
    public static final DeferredItem<BlockItem> ENDERIUM_ORE_ITEM = ITEMS.registerSimpleBlockItem("enderium_ore", ENDERIUM_ORE);

    public static final DeferredItem<Item> RAW_ENDERIUM = ITEMS.registerSimpleItem("raw_enderium");
    public static final DeferredItem<Item> ENDERIUM_INGOT = ITEMS.registerSimpleItem("enderium_ingot");
    public static final DeferredItem<Item> ENDERIUM_UPGRADE_TEMPLATE =
            ITEMS.registerSimpleItem("enderium_upgrade_smithing_template");

    // ---- Enderium gear: the tier above netherite (v0.3). Netherite gear + the
    // upgrade template + an enderium ingot transmutes at the smithing table. ----
    public static final TagKey<Item> ENDERIUM_TOOL_MATERIALS =
            TagKey.create(Registries.ITEM, id("enderium_tool_materials"));
    public static final TagKey<Item> REPAIRS_ENDERIUM_ARMOR =
            TagKey.create(Registries.ITEM, id("repairs_enderium_armor"));
    /** The gear the End gives back: exactly the enderium items. */
    public static final TagKey<Item> SOULBOUND_ABLE =
            TagKey.create(Registries.ITEM, id("soulbound_able"));

    // 1.21.1 API: the tool material is a Tier implementation (no registry). Repair is
    // an Ingredient, memoized so the item holder isn't dereferenced at classload.
    public static final Tier ENDERIUM_TIER = new Tier() {
        private final Supplier<Ingredient> repairIngredient =
                Suppliers.memoize(() -> Ingredient.of(ENDERIUM_INGOT.get()));

        @Override
        public int getUses() {
            return 2531;
        }

        @Override
        public float getSpeed() {
            return 10.0F;
        }

        @Override
        public float getAttackDamageBonus() {
            return 5.0F;
        }

        @Override
        public TagKey<Block> getIncorrectBlocksForDrops() {
            return BlockTags.INCORRECT_FOR_NETHERITE_TOOL; // harvests everything netherite does
        }

        @Override
        public int getEnchantmentValue() {
            return 17;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return repairIngredient.get();
        }
    };

    // 1.21.1 API: ArmorMaterial is registry-registered and consumed through a Holder.
    // Defense map mirrors netherite (all 5 keys filled, like vanilla's EnumMap backfill).
    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, MODID);
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> ENDERIUM_ARMOR_MATERIAL =
            ARMOR_MATERIALS.register("enderium", () -> new ArmorMaterial(
                    Map.of(ArmorItem.Type.BOOTS, 3, ArmorItem.Type.LEGGINGS, 6,
                            ArmorItem.Type.CHESTPLATE, 8, ArmorItem.Type.HELMET, 3,
                            ArmorItem.Type.BODY, 11),
                    17, SoundEvents.ARMOR_EQUIP_NETHERITE,
                    Suppliers.memoize(() -> Ingredient.of(ENDERIUM_INGOT.get())),
                    List.of(new ArmorMaterial.Layer(id("enderium"))),
                    3.5F, 0.15F));

    // Baselines copied from vanilla netherite (Items.java); the tier carries the bonus.
    public static final DeferredItem<SwordItem> ENDERIUM_SWORD = ITEMS.register("enderium_sword",
            () -> new SwordItem(ENDERIUM_TIER, new Item.Properties().fireResistant()
                    .attributes(SwordItem.createAttributes(ENDERIUM_TIER, 3, -2.4F))));
    public static final DeferredItem<PickaxeItem> ENDERIUM_PICKAXE = ITEMS.register("enderium_pickaxe",
            () -> new PickaxeItem(ENDERIUM_TIER, new Item.Properties().fireResistant()
                    .attributes(PickaxeItem.createAttributes(ENDERIUM_TIER, 1.0F, -2.8F))));
    public static final DeferredItem<AxeItem> ENDERIUM_AXE = ITEMS.register("enderium_axe",
            () -> new AxeItem(ENDERIUM_TIER, new Item.Properties().fireResistant()
                    .attributes(AxeItem.createAttributes(ENDERIUM_TIER, 5.0F, -3.0F))));
    public static final DeferredItem<ShovelItem> ENDERIUM_SHOVEL = ITEMS.register("enderium_shovel",
            () -> new ShovelItem(ENDERIUM_TIER, new Item.Properties().fireResistant()
                    .attributes(ShovelItem.createAttributes(ENDERIUM_TIER, 1.5F, -3.0F))));
    public static final DeferredItem<HoeItem> ENDERIUM_HOE = ITEMS.register("enderium_hoe",
            () -> new HoeItem(ENDERIUM_TIER, new Item.Properties().fireResistant()
                    .attributes(HoeItem.createAttributes(ENDERIUM_TIER, -4.0F, 0.0F))));

    // 1.21.1 API: durability lives per-item (netherite's multiplier is 37; enderium 41).
    public static final DeferredItem<ArmorItem> ENDERIUM_HELMET = ITEMS.register("enderium_helmet",
            () -> new ArmorItem(ENDERIUM_ARMOR_MATERIAL, ArmorItem.Type.HELMET,
                    new Item.Properties().fireResistant()
                            .durability(ArmorItem.Type.HELMET.getDurability(41))));
    public static final DeferredItem<ArmorItem> ENDERIUM_CHESTPLATE = ITEMS.register("enderium_chestplate",
            () -> new ArmorItem(ENDERIUM_ARMOR_MATERIAL, ArmorItem.Type.CHESTPLATE,
                    new Item.Properties().fireResistant()
                            .durability(ArmorItem.Type.CHESTPLATE.getDurability(41))));
    public static final DeferredItem<ArmorItem> ENDERIUM_LEGGINGS = ITEMS.register("enderium_leggings",
            () -> new ArmorItem(ENDERIUM_ARMOR_MATERIAL, ArmorItem.Type.LEGGINGS,
                    new Item.Properties().fireResistant()
                            .durability(ArmorItem.Type.LEGGINGS.getDurability(41))));
    public static final DeferredItem<ArmorItem> ENDERIUM_BOOTS = ITEMS.register("enderium_boots",
            () -> new ArmorItem(ENDERIUM_ARMOR_MATERIAL, ArmorItem.Type.BOOTS,
                    new Item.Properties().fireResistant()
                            .durability(ArmorItem.Type.BOOTS.getDurability(41))));

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
                        output.accept(Soulbound.book(params.holders()));
                    })
                    .build());

    public Endrise(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        ARMOR_MATERIALS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);

        modEventBus.addListener(this::addCreative);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
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
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(ENDERIUM_SWORD);
            event.accept(ENDERIUM_HELMET);
            event.accept(ENDERIUM_CHESTPLATE);
            event.accept(ENDERIUM_LEGGINGS);
            event.accept(ENDERIUM_BOOTS);
        }
    }
}
