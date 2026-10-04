package io.github.timske6.simpleme.test;

import com.mojang.blaze3d.platform.InputConstants;
import static io.github.timske6.simpleme.test.TestItems.check;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.storage.TerminalStorage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Real dedicated server + client over the network: a big storage (thousands of types and
 * 90 books of ~100 KB each) must sync, and breaking/carrying/placing the terminal must not kick anyone.
 */
@SuppressWarnings("UnstableApiUsage")
public class TerminalDedicatedServerTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);
	private static final int BOOKS = 90;
	private static final int PAPERS = 3000;
	private static final int EXPECTED_TYPES = BOOKS + PAPERS + 3;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
			TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			server.runOnServer(s -> {
				TerminalStorage storage = TestItems.connectNewNetwork(s, TERMINAL, "Test");
				for (int i = 0; i < BOOKS; i++) {
					storage.insert(ItemVariant.of(TestItems.bigBook(i)), 1);
				}
				for (int i = 0; i < PAPERS; i++) {
					storage.insert(ItemVariant.of(TestItems.namedPaper(i)), i + 1);
				}
				storage.insert(ItemVariant.of(Items.STONE), 12_345);
				storage.insert(ItemVariant.of(Items.DIAMOND), 64);
				storage.insert(ItemVariant.of(Items.OAK_LOG), 64);
			});

			// --- open: ~9 MB of books + 3000 items must arrive at the client --------------------
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(20);
			context.getInput().lookAt(TERMINAL);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			int ticks = context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() == EXPECTED_TYPES, 600);
			System.out.println("[simpleme-test] dedicated server: full sync of " + EXPECTED_TYPES + " types took " + ticks + " ticks");
			context.takeScreenshot("simpleme-dedicated-01-open");

			// first entry (sorted by name) is "Book 0": take it and put it back
			TestItems.click(context, 16, 26, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> !mc.player.containerMenu.getCarried().isEmpty(), 100);
			ItemStack carried = context.computeOnClient(mc -> mc.player.containerMenu.getCarried().copy());
			check(carried.is(Items.WRITTEN_BOOK), "expected the first book on the cursor, got " + carried);
			TestItems.click(context, 16, 26, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> mc.player.containerMenu.getCarried().isEmpty(), 100);

			// scroll down with the mouse wheel
			TestItems.moveTo(context, 60, 40);
			context.getInput().scroll(-10);
			context.waitTicks(2);
			int scroll = context.computeOnClient(mc -> TestItems.screen(mc).scrollRow());
			check(scroll == 10, "mouse wheel should scroll 10 rows, got " + scroll);
			context.takeScreenshot("simpleme-dedicated-02-scrolled");

			// hover tooltip with the stored amount
			context.getInput().scroll(10);
			TestItems.moveTo(context, 8 + 18 * 3 + 8, 18 + 18 * 3 + 8);
			context.waitTicks(3);
			context.takeScreenshot("simpleme-dedicated-03-tooltip");

			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);

			// --- break it, walk over the drop, carry it (the old format would kick us here) ------------
			server.runCommand("setblock 0 -60 -3 air destroy");
			server.runCommand("tp @a 0.5 -60 -2.5");
			context.waitFor(mc -> mc.player.getInventory().countItem(ModRegistry.TERMINAL_ITEM) == 1, 200);
			context.waitTicks(20);
			boolean connected = context.computeOnClient(mc -> mc.getConnection() != null && !(mc.gui.screen() instanceof DisconnectedScreen));
			check(connected, "client must stay connected after breaking the terminal");
			context.takeScreenshot("simpleme-dedicated-04-carrying-terminal");

			// --- place it again with the client ------------------------------------------------------
			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			context.waitTicks(5);
			int hotbar = context.computeOnClient(mc -> {
				for (int i = 0; i < 9; i++) {
					if (mc.player.getInventory().getItem(i).is(ModRegistry.TERMINAL_ITEM)) {
						return i;
					}
				}
				return -1;
			});
			check(hotbar >= 0, "terminal should be in the hotbar");
			final int hotbarKey = hotbar;
			context.getInput().pressKey(options -> options.keyHotbarSlots[hotbarKey]);
			context.getInput().lookAt(new BlockPos(0, -61, -3));
			context.getInput().pressKey(options -> options.keyUse);
			BlockPos placed = findTerminal(server);
			check(placed != null, "the client should have placed the terminal");
			check(server.computeOnServer(s -> ((TerminalBlockEntity) s.overworld().getBlockEntity(placed)).getNetworkId() == null),
				"a re-placed terminal starts without network");

			context.getInput().lookAt(placed);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).isNetworkPanelOpen(), 100);
			TestItems.connectFirstNetwork(context);
			context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() == EXPECTED_TYPES, 600);
			context.takeScreenshot("simpleme-dedicated-05-replaced");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
		}
	}

	private static BlockPos findTerminal(TestDedicatedServerContext server) {
		BlockPos[] found = new BlockPos[1];
		server.waitFor(s -> {
			for (BlockPos pos : BlockPos.betweenClosed(-3, -61, -6, 3, -58, 3)) {
				if (s.overworld().getBlockState(pos).is(ModRegistry.TERMINAL_BLOCK)) {
					found[0] = pos.immutable();
					return true;
				}
			}
			return false;
		}, 100);
		return found[0];
	}

	private static TerminalBlockEntity terminal(MinecraftServer server) {
		return (TerminalBlockEntity) server.overworld().getBlockEntity(TERMINAL);
	}
}
