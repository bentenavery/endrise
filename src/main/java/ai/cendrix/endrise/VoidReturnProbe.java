package ai.cendrix.endrise;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tide 4 groundwork: proves the void-capture ordering the whole tide depends on.
 * A fake player drops an enderium ingot below the kill plane (the exact production
 * path, so the thrower reference is set the way real drops set it); we then assert
 * that EntityLeaveLevelEvent fires for the void kill with the removal reason, the
 * stack, and the owner all still readable.
 *
 * Off unless the JVM runs with -Dendrise.voidprobe=true, e.g.
 * JAVA_TOOL_OPTIONS=-Dendrise.voidprobe=true ./gradlew runServer:
 * logs [VOIDPROBE] lines, then halts the server.
 */
@EventBusSubscriber(modid = Endrise.MODID)
public final class VoidReturnProbe {
    private static final boolean ENABLED = Boolean.getBoolean("endrise.voidprobe");

    private static int tick = -1; // -1 = idle
    private static ItemEntity dropped;
    private static boolean captured;

    private VoidReturnProbe() {}

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        if (ENABLED) {
            tick = 0;
            Endrise.LOGGER.info("[VOIDPROBE] armed");
        }
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (!ENABLED || tick < 0) {
            return;
        }
        tick++;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (tick == 5) {
            var player = FakePlayerFactory.getMinecraft(level);
            var spawn = level.getRespawnData().pos(); // 26.x: shared spawn moved into LevelData.RespawnData
            player.setPos(spawn.getX() + 0.5, level.getMinY() - 60, spawn.getZ() + 0.5);
            dropped = player.drop(new ItemStack(Endrise.ENDERIUM_INGOT.get(), 3), false);
            Endrise.LOGGER.info("[VOIDPROBE] dropped entity id={} at y={} (minY={})",
                    dropped == null ? -1 : dropped.getId(),
                    dropped == null ? 0.0 : dropped.getY(), level.getMinY());
        }
        // Empty-server chunks don't entity-tick, so the probe drives the entity's own
        // vanilla tick: same gravity, same checkBelowWorld, same discard/removal path.
        if (dropped != null && !dropped.isRemoved()) {
            dropped.tick();
        }
        if (tick > 5 && tick % 20 == 0 && dropped != null) {
            Endrise.LOGGER.info("[VOIDPROBE] t={} alive={} removed={} y={} tickCount={}",
                    tick, dropped.isAlive(), dropped.isRemoved(),
                    String.format("%.1f", dropped.getY()), dropped.tickCount);
        }
        if (tick > 5 && (captured || tick > 120)) {
            Endrise.LOGGER.info("[VOIDPROBE] {}", captured
                    ? "PASS: void kill captured with reason, stack, and owner readable"
                    : "FAIL: no qualifying leave-level capture within 115 ticks");
            tick = -1;
            server.halt(false);
        }
    }

    @SubscribeEvent
    static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!ENABLED || !(event.getEntity() instanceof ItemEntity item) || item != dropped) {
            return;
        }
        var reason = item.getRemovalReason();
        var owner = item.getOwner();
        Endrise.LOGGER.info("[VOIDPROBE] leave-level: reason={} destroy={} y={} stack={}x{} owner={}",
                reason, reason != null && reason.shouldDestroy(),
                String.format("%.1f", item.getY()),
                item.getItem().getItem(), item.getItem().getCount(),
                owner == null ? "null" : owner.getName().getString());
        captured = reason != null && reason.shouldDestroy() && !item.getItem().isEmpty() && owner != null;
    }
}
