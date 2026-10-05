package io.github.timske6.simpleme.client.jei;

import io.github.timske6.simpleme.client.TerminalScreen;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.renderer.Rect2i;

/**
 * The storage grid and the group panel have no real slots, so JEI cannot see their items by itself. This
 * tells JEI which item is under the mouse, so the recipe (R) and usage (U) keys and clicks work there too.
 */
public final class TerminalGuiHandler implements IGuiContainerHandler<TerminalScreen> {
	/** JEI keeps its item list away from the group panel next to the terminal. */
	@Override
	public List<Rect2i> getGuiExtraAreas(TerminalScreen screen) {
		return screen.extraAreas();
	}

	@Override
	public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(
		IClickableIngredientFactory builder, TerminalScreen screen, double mouseX, double mouseY
	) {
		TerminalScreen.StorageCell cell = screen.storageCellAt(mouseX, mouseY);
		if (cell == null) {
			return Optional.empty();
		}
		return builder.createBuilder(cell.stack()).buildWithArea(cell.x(), cell.y(), 16, 16);
	}
}
