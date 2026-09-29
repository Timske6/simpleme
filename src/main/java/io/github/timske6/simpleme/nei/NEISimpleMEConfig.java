package io.github.timske6.simpleme.nei;

import codechicken.nei.api.API;
import codechicken.nei.api.IConfigureNEI;
import codechicken.nei.guihook.GuiContainerManager;
import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.Tags;
import io.github.timske6.simpleme.client.GuiTerminal;

/**
 * NEI plugin (found by NEI through the class name "NEI...Config"; only loaded when NEI is installed).
 * <ul>
 * <li>recipe transfer ("+" button / shift-click in the recipe view) fills the terminal grid from the storage</li>
 * <li>the "?" overlay shows the recipe on the terminal grid</li>
 * <li>R/U and the item tooltip work on the items in the terminal grid</li>
 * </ul>
 */
public class NEISimpleMEConfig implements IConfigureNEI {

    @Override
    public void loadConfig() {
        GuiTerminal.neiActive = true;
        API.registerGuiOverlay(GuiTerminal.class, "crafting", new TerminalPositioner());
        API.registerGuiOverlayHandler(GuiTerminal.class, new TerminalOverlayHandler(), "crafting");
        GuiContainerManager.addObjectHandler(new TerminalObjectHandler());
        GuiContainerManager.addTooltipHandler(new TerminalTooltipHandler());
        SimpleME.LOG.info("NEI integration loaded");
    }

    @Override
    public String getName() {
        return "Simple ME Terminal";
    }

    @Override
    public String getVersion() {
        return Tags.VERSION;
    }
}
