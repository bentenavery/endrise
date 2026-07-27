package ai.cendrix.endrise;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Unit;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
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

    // The enderium armor trim material (data-defined in data/endrise/trim_material/enderium.json)
    public static final ResourceKey<TrimMaterial> ENDERIUM_TRIM =
            ResourceKey.create(Registries.TRIM_MATERIAL, id("enderium"));

    // Marker set by the smithing infusion recipes; presence = "this tool is enderium-infused"
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

    public static final DeferredItem<Item> RAW_ENDERIUM = ITEMS.registerSimpleItem("raw_enderium");
    // The ingot doubles as an armor trim material at the smithing table
    public static final DeferredItem<Item> ENDERIUM_INGOT = ITEMS.registerSimpleItem("enderium_ingot",
            p -> p.trimMaterial(ENDERIUM_TRIM));
    public static final DeferredItem<Item> ENDERIUM_UPGRADE_TEMPLATE =
            ITEMS.registerSimpleItem("enderium_upgrade_smithing_template");

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
                        output.accept(Soulbound.book(params.holders()));
                    })
                    .build());

    public Endrise(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);

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
    }
}
