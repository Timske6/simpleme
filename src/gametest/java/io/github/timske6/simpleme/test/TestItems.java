package io.github.timske6.simpleme.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.client.NetworkPanel;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.TerminalStorage;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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

	/** Connects the terminal at {@code pos} to a new public network and returns its storage. */
	static TerminalStorage connectNewNetwork(MinecraftServer server, BlockPos pos, String name) {
		TerminalBlockEntity terminal = (TerminalBlockEntity) server.overworld().getBlockEntity(pos);
		terminal.setNetworkId(Networks.get(server).create(null, name).id());
		return terminal.getStorage();
	}

	/** Clicks at a position given relative to the network panel (centered on the screen). */
	static void clickPanel(ClientGameTestContext context, int panelX, int panelY) {
		double[] pos = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			int left = (mc.getWindow().getGuiScaledWidth() - NetworkPanel.WIDTH) / 2;
			int top = (mc.getWindow().getGuiScaledHeight() - NetworkPanel.HEIGHT) / 2;
			return new double[] {(left + panelX) * scale, (top + panelY) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
	}

	/** In the network panel: select the first network of the list and press "Connect". */
	static void connectFirstNetwork(ClientGameTestContext context) {
		clickPanel(context, 40, 30 + 7);
		clickPanel(context, 8 + 27, 156 + 8);
	}

	static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
