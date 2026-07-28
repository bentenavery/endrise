package ai.cendrix.endrise;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.List;

/**
 * JEI integration. Every Endrise recipe is a vanilla type, so crafting,
 * smelting, stonecutting, and the smithing upgrade all appear in JEI for
 * free; what JEI cannot know is the covenant itself. These are the info
 * pages for the mechanics that are not recipe-shaped: where the ore hides,
 * what the void gives back, how the dead become flowers, and the way home.
 * Loaded by JEI's annotation scan; inert when JEI is absent.
 */
@JeiPlugin
public class EndriseJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return Endrise.id("jei");
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        info(registration, Endrise.ENDERIUM_ORE_ITEM.get(), "enderium_ore");
        info(registration, Endrise.RAW_ENDERIUM.get(), "materials");
        info(registration, Endrise.ENDERIUM_INGOT.get(), "materials");
        info(registration, Endrise.ENDERIUM_UPGRADE_TEMPLATE.get(), "upgrade_template");
        info(registration, Endrise.MOURNING_BLOOM_ITEM.get(), "mourning_bloom");
        info(registration, Endrise.VOID_PETAL.get(), "void_petal");
        info(registration, Endrise.DRAUGHT_OF_RETURN.get(), "draught_of_return");
        info(registration, Endrise.HOMEWARD_PEARL.get(), "homeward_pearl");
        // The covenant's entry point: the info page rides the exact soulbound
        // book stack (JEI's stored-enchantment subtypes make it distinct).
        // registerRecipes runs client-side with a world joined.
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            registration.addIngredientInfo(List.of(Soulbound.book(level.registryAccess())),
                    VanillaTypes.ITEM_STACK,
                    Component.translatable("jei.endrise.soulbound_tome"));
        }
        registration.addIngredientInfo(List.of(
                        new ItemStack(Endrise.ENDERIUM_SWORD.get()),
                        new ItemStack(Endrise.ENDERIUM_PICKAXE.get()),
                        new ItemStack(Endrise.ENDERIUM_AXE.get()),
                        new ItemStack(Endrise.ENDERIUM_SHOVEL.get()),
                        new ItemStack(Endrise.ENDERIUM_HOE.get()),
                        new ItemStack(Endrise.ENDERIUM_HELMET.get()),
                        new ItemStack(Endrise.ENDERIUM_CHESTPLATE.get()),
                        new ItemStack(Endrise.ENDERIUM_LEGGINGS.get()),
                        new ItemStack(Endrise.ENDERIUM_BOOTS.get())),
                VanillaTypes.ITEM_STACK,
                Component.translatable("jei.endrise.gear"));
    }

    private static void info(IRecipeRegistration registration, ItemLike item, String key) {
        registration.addIngredientInfo(item, Component.translatable("jei.endrise." + key));
    }
}
