package ai.cendrix.endrise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-saved store of soulbound gear waiting to return to its owner.
 * Persisted so a server restart mid-return cannot eat anyone's tools.
 * 1.21.1 branch: NBT-based SavedData (the codec-based SavedDataType is a newer-version API).
 */
public class SoulboundStore extends SavedData {

    /**
     * One captured stack. {@code slot}: 0..35 = main inventory index;
     * negative = -(1 + EquipmentSlot ordinal) for armor/offhand.
     */
    public record Pending(int slot, ItemStack stack, long readyAt) {}

    private final Map<UUID, List<Pending>> pending = new HashMap<>();

    public static final SavedData.Factory<SoulboundStore> FACTORY =
            new SavedData.Factory<>(SoulboundStore::new, SoulboundStore::load);

    public SoulboundStore() {}

    public static SoulboundStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "endrise_soulbound");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag owners = new ListTag();
        this.pending.forEach((owner, entries) -> {
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putUUID("owner", owner);
            ListTag list = new ListTag();
            for (Pending entry : entries) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putInt("slot", entry.slot());
                entryTag.putLong("ready_at", entry.readyAt());
                entryTag.put("stack", entry.stack().save(registries));
                list.add(entryTag);
            }
            ownerTag.put("entries", list);
            owners.add(ownerTag);
        });
        tag.put("pending", owners);
        return tag;
    }

    private static SoulboundStore load(CompoundTag tag, HolderLookup.Provider registries) {
        SoulboundStore store = new SoulboundStore();
        ListTag owners = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < owners.size(); i++) {
            CompoundTag ownerTag = owners.getCompound(i);
            UUID owner = ownerTag.getUUID("owner");
            ListTag list = ownerTag.getList("entries", Tag.TAG_COMPOUND);
            List<Pending> entries = new ArrayList<>();
            for (int j = 0; j < list.size(); j++) {
                CompoundTag entryTag = list.getCompound(j);
                int slot = entryTag.getInt("slot");
                long readyAt = entryTag.getLong("ready_at");
                ItemStack.parse(registries, entryTag.getCompound("stack"))
                        .ifPresent(stack -> entries.add(new Pending(slot, stack, readyAt)));
            }
            if (!entries.isEmpty()) {
                store.pending.put(owner, entries);
            }
        }
        return store;
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
