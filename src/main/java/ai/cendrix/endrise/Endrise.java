package ai.cendrix.endrise;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Endrise.MODID)
public class Endrise {
    public static final String MODID = "endrise";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

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

    public Endrise(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        modEventBus.addListener(this::addCreative);
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
        }
    }
}
