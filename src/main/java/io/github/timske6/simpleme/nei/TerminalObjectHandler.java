package io.github.timske6.simpleme.nei;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import codechicken.nei.guihook.IContainerObjectHandler;
import io.github.timske6.simpleme.client.GuiTerminal;

/** Tells NEI which item is under the mouse in the terminal grid, so R/U, bookmarks and tooltips work there. */
public class TerminalObjectHandler implements IContainerObjectHandler {

    @Override
    public void guiTick(GuiContainer gui) {}

    @Override
    public void refresh(GuiContainer gui) {}

    @Override
    public void load(GuiContainer gui) {}

    @Override
    public ItemStack getStackUnderMouse(GuiContainer gui, int mouseX, int mouseY) {
        return gui instanceof GuiTerminal ? ((GuiTerminal) gui).stackAt(mouseX, mouseY) : null;
    }

    @Override
    public boolean objectUnderMouse(GuiContainer gui, int mouseX, int mouseY) {
        return gui instanceof GuiTerminal && ((GuiTerminal) gui).isOverGrid(mouseX, mouseY);
    }

    @Override
    public boolean shouldShowTooltip(GuiContainer gui) {
        return true;
    }
}
