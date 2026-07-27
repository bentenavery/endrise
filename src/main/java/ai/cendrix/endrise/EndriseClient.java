package ai.cendrix.endrise;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Endrise.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Endrise.MODID, value = Dist.CLIENT)
public class EndriseClient {
    private static final int ENDERIUM_TEAL = 0x2FC39D;

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        Endrise.LOGGER.info("Endrise client ready");
    }

    @SubscribeEvent
    static void onItemTooltip(ItemTooltipEvent event) {
        if (Soulbound.isInfused(event.getItemStack())) {
            event.getToolTip().add(Component.translatable("tooltip.endrise.enderium_infused")
                    .withStyle(style -> style.withColor(ENDERIUM_TEAL)));
        }
        if (event.getItemStack().is(Endrise.ENDERIUM_UPGRADE_TEMPLATE.get())) {
            event.getToolTip().add(Component.translatable("tooltip.endrise.enderium_upgrade_template")
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
