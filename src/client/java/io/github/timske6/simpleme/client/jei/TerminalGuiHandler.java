package io.github.timske6.simpleme.client.jei;

import io.github.timske6.simpleme.client.TerminalScreen;
import java.util.Optional;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.runtime.IClickableIngredient;

/**
 * The storage grid has no real slots, so JEI cannot see its items by itself. This tells JEI which item
 * is under the mouse, so the recipe (R) and usage (U) keys and clicks work there too.
 */
public final class TerminalGuiHandler implements IGuiContainerHandler<TerminalScreen> {
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
