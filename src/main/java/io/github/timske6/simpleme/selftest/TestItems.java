package io.github.timske6.simpleme.selftest;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import cpw.mods.fml.common.registry.GameRegistry;

/**
 * Items and recipes that only exist in self-test builds. The hammer behaves like a GregTech tool: it stays in
 * the crafting grid and keeps its damage in NBT.
 */
final class TestItems {

    static Item hammer;
    static Item token;
    /** Output of the bucket test recipe (packs like GTNH remove recipes by output, so no vanilla item). */
    static Item result;

    private TestItems() {}

    static void register() {
        hammer = new TestHammer();
        token = new Item().setUnlocalizedName("simpleme.test_token")
            .setTextureName("minecraft:nether_star");
        GameRegistry.registerItem(hammer, "test_hammer");
        GameRegistry.registerItem(token, "test_token");
        result = new Item().setUnlocalizedName("simpleme.test_result")
            .setTextureName("minecraft:cake");
        GameRegistry.registerItem(result, "test_result");
    }

    static void registerRecipes() {
        // tool recipe: hammer + clay -> brick (the hammer stays in the grid and takes damage)
        GameRegistry.addRecipe(
            new ShapelessOreRecipe(
                new ItemStack(Items.brick),
                new ItemStack(hammer, 1, OreDictionary.WILDCARD_VALUE),
                new ItemStack(Items.clay_ball)));
        // container item recipe: milk bucket + token -> test result (the empty bucket leaves the grid)
        GameRegistry.addRecipe(
            new ShapelessOreRecipe(new ItemStack(result), new ItemStack(Items.milk_bucket), new ItemStack(token)));
    }

    static int uses(ItemStack hammer) {
        return hammer != null && hammer.hasTagCompound() ? hammer.getTagCompound()
            .getInteger("Uses") : 0;
    }

    static final class TestHammer extends Item {

        TestHammer() {
            setMaxStackSize(1);
            setUnlocalizedName("simpleme.test_hammer");
            setTextureName("minecraft:iron_pickaxe");
        }

        @Override
        public boolean hasContainerItem(ItemStack stack) {
            return true;
        }

        @Override
        public ItemStack getContainerItem(ItemStack stack) {
            ItemStack copy = stack.copy();
            NBTTagCompound tag = copy.hasTagCompound() ? copy.getTagCompound() : new NBTTagCompound();
            tag.setInteger("Uses", tag.getInteger("Uses") + 1);
            copy.setTagCompound(tag);
            return copy;
        }

        @Override
        public boolean doesContainerItemLeaveCraftingGrid(ItemStack stack) {
            return false;
        }
    }
}
