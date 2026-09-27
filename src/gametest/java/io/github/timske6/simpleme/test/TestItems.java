package io.github.timske6.simpleme.test;

import io.github.timske6.simpleme.client.TerminalScreen;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/** Shared helpers for the client game tests. */
final class TestItems {
	private TestItems() {
	}

	/** A written book with 100 full pages (~100 KB of data), like a player can write in survival. */
	static ItemStack bigBook(int id) {
		List<Filterable<Component>> pages = new ArrayList<>();
		String text = ("Page of book " + id + " ").repeat(200).substring(0, 1024);
		for (int i = 0; i < 100; i++) {
			pages.add(Filterable.passThrough(Component.literal(text)));
		}
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Book " + id), "tester", 0, pages, true));
		return book;
	}

	static ItemStack namedPaper(int id) {
		ItemStack stack = new ItemStack(Items.PAPER);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Paper #" + id));
		return stack;
	}

	static TerminalScreen screen(net.minecraft.client.Minecraft mc) {
		return mc.gui.screen() instanceof TerminalScreen screen ? screen : null;
	}

	/** Moves the cursor to a position given in GUI coordinates relative to the terminal window. */
	static void moveTo(ClientGameTestContext context, int guiX, int guiY) {
		double[] pos = context.computeOnClient(mc -> {
			TerminalScreen screen = screen(mc);
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(screen.guiLeft() + guiX) * scale, (screen.guiTop() + guiY) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
	}

	static void click(ClientGameTestContext context, int guiX, int guiY, int button) {
		moveTo(context, guiX, guiY);
		context.getInput().pressMouse(button);
	}

	static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
