package io.github.timske6.simpleme.nei;

import java.util.ArrayList;

import net.minecraft.inventory.Slot;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.IStackPositioner;
import io.github.timske6.simpleme.client.GuiTerminal;
import io.github.timske6.simpleme.menu.ContainerTerminal;

/**
 * Moves NEI's crafting recipe positions (grid at 25/6 in the recipe view) onto the terminal's crafting grid.
 * The grid position depends on how many item rows the terminal shows, so it is read from the open screen.
 */
public class TerminalPositioner implements IStackPositioner {

    @Override
    public ArrayList<PositionedStack> positionStacks(ArrayList<PositionedStack> stacks) {
        GuiTerminal gui = GuiTerminal.current;
        if (gui == null) {
            return stacks;
        }
        Slot firstGridSlot = gui.getContainer()
            .getSlot(ContainerTerminal.GRID_START);
        int offsetX = firstGridSlot.xDisplayPosition - 25;
        int offsetY = firstGridSlot.yDisplayPosition - 6;
        for (PositionedStack stack : stacks) {
            stack.relx += offsetX;
            stack.rely += offsetY;
        }
        return stacks;
    }
}
