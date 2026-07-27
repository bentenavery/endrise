package ai.cendrix.endrise;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Soulbound: enchantment for enderium-infused gear. On death the gear vanishes with its
 * owner and teleports back into their inventory (original slots) after a short delay.
 * The enchantment itself is data-defined (data/endrise/enchantment/soulbound.json);
 * this class holds the key and the qualification rules.
 */
public final class Soulbound {
    public static final ResourceKey<Enchantment> KEY =
            ResourceKey.create(Registries.ENCHANTMENT, Endrise.id("soulbound"));

    /** How long gear spends "in the void" before returning (Avery spec: 5-10s). */
    public static final int RETURN_DELAY_TICKS = 160; // 8 seconds

    private Soulbound() {}

    /** Enderium gear (the #endrise:soulbound_able items) is the only gear the End gives back. */
    public static boolean isEnderiumGear(ItemStack stack) {
        return stack.is(Endrise.SOULBOUND_ABLE);
    }

    public static boolean isSoulbound(HolderLookup.Provider registries, ItemStack stack) {
        var enchantment = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(KEY);
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack) > 0;
    }

    /** True when this stack should skip death drops and return to its owner. */
    public static boolean protects(HolderLookup.Provider registries, ItemStack stack) {
        return !stack.isEmpty() && isEnderiumGear(stack) && isSoulbound(registries, stack);
    }

    /** The craftable Soulbound book (as it appears in the creative tab). */
    public static ItemStack book(HolderLookup.Provider registries) {
        var soulbound = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(KEY);
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(soulbound, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        return book;
    }
}
