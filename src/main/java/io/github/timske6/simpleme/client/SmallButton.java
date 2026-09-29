package io.github.timske6.simpleme.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;

/** A small flat button in the style of the terminal panel (vanilla buttons need 20 px height). */
public class SmallButton extends GuiButton {

    public SmallButton(int id, int x, int y, int width, int height, String text) {
        super(id, x, y, width, height, text);
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return mouseX >= xPosition && mouseY >= yPosition && mouseX < xPosition + width && mouseY < yPosition + height;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        boolean hovered = isHovered(mouseX, mouseY);
        int x2 = xPosition + width;
        int y2 = yPosition + height;
        Gui.drawRect(xPosition, yPosition, x2, y2, 0xFF000000);
        Gui.drawRect(xPosition + 1, yPosition + 1, x2 - 1, y2 - 1, hovered ? 0xFF8C9CC8 : 0xFF6F6F6F);
        Gui.drawRect(xPosition + 1, yPosition + 1, x2 - 2, yPosition + 2, hovered ? 0xFFBCC8E8 : 0xFFA8A8A8);
        Gui.drawRect(xPosition + 1, yPosition + 1, xPosition + 2, y2 - 2, hovered ? 0xFFBCC8E8 : 0xFFA8A8A8);
        Gui.drawRect(xPosition + 2, y2 - 2, x2 - 1, y2 - 1, 0xFF3C3C3C);
        Gui.drawRect(x2 - 2, yPosition + 2, x2 - 1, y2 - 1, 0xFF3C3C3C);
        int textWidth = mc.fontRenderer.getStringWidth(displayString);
        mc.fontRenderer.drawStringWithShadow(
            displayString,
            xPosition + (width - textWidth) / 2 + 1,
            yPosition + (height - 8) / 2,
            hovered ? 0xFFFFA0 : 0xFFFFFF);
    }
}
