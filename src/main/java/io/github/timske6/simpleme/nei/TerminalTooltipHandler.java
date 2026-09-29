package io.github.timske6.simpleme.nei;

import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import codechicken.nei.guihook.IContainerTooltipHandler;
import io.github.timske6.simpleme.client.GuiTerminal;

/** Adds the stored amount to NEI's tooltip for items in the terminal grid. */
public class TerminalTooltipHandler implements IContainerTooltipHandler {

    @Override
    public List<String> handleItemTooltip(GuiContainer gui, ItemStack stack, int mouseX, int mouseY,
        List<String> tooltip) {
        if (gui instanceof GuiTerminal && stack != null && !tooltip.isEmpty()) {
            long stored = ((GuiTerminal) gui).storedAt(mouseX, mouseY);
            if (stored >= 0) {
                tooltip.add(EnumChatFormatting.GRAY + GuiTerminal.storedLine(stored));
            }
        }
        return tooltip;
    }
}
