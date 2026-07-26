package ai.cendrix.endrise;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Endrise.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Endrise.MODID, value = Dist.CLIENT)
public class EndriseClient {
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        Endrise.LOGGER.info("Endrise client ready");
    }
}
