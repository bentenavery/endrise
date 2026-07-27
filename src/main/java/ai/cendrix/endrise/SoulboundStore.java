package ai.cendrix.endrise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World-saved store of soulbound gear waiting to return to its owner.
 * Persisted so a server restart mid-return cannot eat anyone's tools.
 */
public class SoulboundStore extends SavedData {

    /**
     * One captured stack. {@code slot}: 0..35 = main inventory index;
     * negative = -(1 + EquipmentSlot ordinal) for armor/offhand.
     */
    public record Pending(int slot, ItemStack stack, long readyAt) {
        public static final Codec<Pending> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("slot").forGetter(Pending::slot),
                ItemStack.CODEC.fieldOf("stack").forGetter(Pending::stack),
                Codec.LONG.fieldOf("ready_at").forGetter(Pending::readyAt)
        ).apply(i, Pending::new));
    }

    private final Map<UUID, List<Pending>> pending = new HashMap<>();

    public static final Codec<SoulboundStore> CODEC =
            Codec.unboundedMap(UUIDUtil.CODEC, Pending.CODEC.listOf())
                    .xmap(SoulboundStore::fromMap, store -> Map.copyOf(store.pending));

    public static final SavedDataType<SoulboundStore> TYPE =
            new SavedDataType<>(Endrise.id("soulbound"), SoulboundStore::new, CODEC);

    public SoulboundStore() {}

    private static SoulboundStore fromMap(Map<UUID, List<Pending>> saved) {
        SoulboundStore store = new SoulboundStore();
        saved.forEach((owner, entries) -> store.pending.put(owner, new ArrayList<>(entries)));
        return store;
    }

    public static SoulboundStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void add(UUID owner, Pending entry) {
        this.pending.computeIfAbsent(owner, k -> new ArrayList<>()).add(entry);
        this.setDirty();
    }

    /** Removes and returns every entry for {@code owner} whose delay has elapsed. */
    public List<Pending> takeReady(UUID owner, long now) {
        List<Pending> entries = this.pending.get(owner);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<Pending> ready = new ArrayList<>();
        entries.removeIf(entry -> {
            if (entry.readyAt() <= now) {
                ready.add(entry);
                return true;
            }
            return false;
        });
        if (!ready.isEmpty()) {
            if (entries.isEmpty()) {
                this.pending.remove(owner);
            }
            this.setDirty();
        }
        return ready;
    }
}
