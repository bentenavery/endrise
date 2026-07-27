package ai.cendrix.endrise;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

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

    /** Tools carry the smithing marker component; armor counts via an enderium trim. */
    public static boolean isInfused(ItemStack stack) {
        if (stack.has(Endrise.ENDERIUM_INFUSED.get())) {
            return true;
        }
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        return trim != null && trim.material().is(Endrise.ENDERIUM_TRIM);
    }

    public static boolean isSoulbound(HolderLookup.Provider registries, ItemStack stack) {
        var enchantment = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(KEY);
        return EnchantmentHelper.getItemEnchantmentLevel(enchantment, stack) > 0;
    }

    /** True when this stack should skip death drops and return to its owner. */
    public static boolean protects(HolderLookup.Provider registries, ItemStack stack) {
        return !stack.isEmpty() && isInfused(stack) && isSoulbound(registries, stack);
    }
}
