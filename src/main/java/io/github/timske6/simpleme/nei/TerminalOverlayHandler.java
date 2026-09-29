package io.github.timske6.simpleme.nei;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.IRecipeHandler;
import io.github.timske6.simpleme.client.GuiTerminal;
import io.github.timske6.simpleme.network.Network;
import io.github.timske6.simpleme.network.PacketFillGrid;
import io.github.timske6.simpleme.storage.ItemKey;

/** NEI recipe transfer into the terminal: the server fills the grid from the storage and the inventory. */
public class TerminalOverlayHandler implements IOverlayHandler {

    /** Grid of NEI's crafting recipe handlers: first slot at 25/6, 18 px per slot. */
    private static final int RECIPE_X = 25;
    private static final int RECIPE_Y = 6;

    @Override
    public void overlayRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        if (!(gui instanceof GuiTerminal)) {
            return;
        }
        List<List<ItemStack>> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            slots.add(new ArrayList<>());
        }
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            int slot = gridIndex(ingredient);
            if (slot < 0) {
                continue;
            }
            List<ItemStack> candidates = slots.get(slot);
            for (ItemStack stack : ingredient.items) {
                if (stack != null && candidates.size() < PacketFillGrid.MAX_CANDIDATES) {
                    ItemStack copy = stack.copy();
                    copy.stackSize = 1;
                    candidates.add(copy);
                }
            }
        }
        Network.CHANNEL.sendToServer(new PacketFillGrid(gui.inventorySlots.windowId, slots, maxTransfer));
    }

    private static int gridIndex(PositionedStack ingredient) {
        int column = (ingredient.relx - RECIPE_X) / 18;
        int row = (ingredient.rely - RECIPE_Y) / 18;
        if (ingredient.relx < RECIPE_X || ingredient.rely < RECIPE_Y || column > 2 || row > 2) {
            return -1;
        }
        return row * 3 + column;
    }

    @Override
    public int transferRecipe(GuiContainer gui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        overlayRecipe(gui, recipe, recipeIndex, multiplier > 1);
        return multiplier;
    }

    /** Green/red markers in NEI's recipe view: counts what is in the storage, not only the inventory. */
    @Override
    public List<GuiOverlayButton.ItemOverlayState> presenceOverlay(GuiContainer gui, IRecipeHandler recipe,
        int recipeIndex) {
        List<GuiOverlayButton.ItemOverlayState> states = new ArrayList<>();
        if (!(gui instanceof GuiTerminal)) {
            return IOverlayHandler.super.presenceOverlay(gui, recipe, recipeIndex);
        }
        GuiTerminal terminal = (GuiTerminal) gui;
        Map<ItemKey, Long> used = new HashMap<>();
        for (PositionedStack ingredient : recipe.getIngredientStacks(recipeIndex)) {
            boolean present = false;
            for (ItemStack candidate : ingredient.items) {
                ItemKey key = findAvailable(terminal, gui, candidate, used);
                if (key != null) {
                    used.merge(key, 1L, Long::sum);
                    present = true;
                    break;
                }
            }
            states.add(new GuiOverlayButton.ItemOverlayState(ingredient, present));
        }
        return states;
    }

    private static ItemKey findAvailable(GuiTerminal terminal, GuiContainer gui, ItemStack candidate,
        Map<ItemKey, Long> used) {
        if (candidate == null) {
            return null;
        }
        for (Map.Entry<ItemKey, Long> entry : terminal.getContainer()
            .getClientItems()
            .entrySet()) {
            ItemKey key = entry.getKey();
            if (matches(candidate, key) && entry.getValue() + inventoryCount(gui, key) > used.getOrDefault(key, 0L)) {
                return key;
            }
        }
        for (ItemStack stack : gui.mc.thePlayer.inventory.mainInventory) {
            if (stack != null) {
                ItemKey key = ItemKey.of(stack);
                if (matches(candidate, key)
                    && inventoryCount(gui, key) + terminal.storedAmount(key) > used.getOrDefault(key, 0L)) {
                    return key;
                }
            }
        }
        return null;
    }

    private static boolean matches(ItemStack candidate, ItemKey key) {
        int meta = candidate.getItemDamage();
        return candidate.getItem() == key.item
            && (meta == OreDictionary.WILDCARD_VALUE || meta == key.meta || key.item.isDamageable());
    }

    private static long inventoryCount(GuiContainer gui, ItemKey key) {
        long count = 0;
        for (ItemStack stack : gui.mc.thePlayer.inventory.mainInventory) {
            if (key.matches(stack)) {
                count += stack.stackSize;
            }
        }
        return count;
    }
}
