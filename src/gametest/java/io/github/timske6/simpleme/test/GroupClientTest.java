package io.github.timske6.simpleme.test;

import static io.github.timske6.simpleme.test.TestItems.check;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.client.GroupPanel;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.GroupListPayload;
import io.github.timske6.simpleme.storage.ItemGroups;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Item groups in the terminal (create, rename, drag items in, hide, share, delete), the sorting kept
 * per terminal, the search box losing the focus on clicks outside, and JEI's keys over items while
 * the search box has the focus.
 */
@SuppressWarnings("UnstableApiUsage")
public class GroupClientTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);
	/** Left edge of the group panel, relative to the terminal. */
	private static final int PANEL_X = 195 + 2;
	private static final int LIST_X = PANEL_X + 7;
	private static final int SORT_BUTTON_X = 173 + 9;
	private static final int SORT_BUTTON_Y = 3 + 7;
	private static final int SEARCH_X = 82 + 20;
	private static final int SEARCH_Y = 4 + 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			UUID[] network = new UUID[1];
			server.runOnServer(s -> {
				var storage = TestItems.connectNewNetwork(s, TERMINAL, "Groups");
				storage.insert(ItemVariant.of(Items.ANDESITE), 10);
				storage.insert(ItemVariant.of(Items.STONE), 500);
				storage.insert(ItemVariant.of(Items.DIRT), 3);
				network[0] = terminal(s).getNetworkId();
			});
			context.waitFor(mc -> TestJeiPlugin.runtime != null);
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(20);
			open(context);

			// --- open the panel and create a group ------------------------------------------------------
			check(context.computeOnClient(mc -> !GroupPanel.isExpanded()), "the group panel starts folded in");
			TestItems.click(context, PANEL_X + 8, 18 + 11, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> GroupPanel.isExpanded()), "clicking the tab opens the group panel");
			TestItems.click(context, PANEL_X + 110 - 36 + 7, 4 + 7, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> groups(mc).size() == 1, 40);
			// the name of the new group is selected: typing replaces it, enter saves it
			context.getInput().typeChars("Erze");
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.waitFor(mc -> groups(mc).getFirst().name().equals("Erze"), 40);
			server.runOnServer(s -> check(ItemGroups.get(s).visible(network[0], player(s)).getFirst().name().equals("Erze"), "the server keeps the name"));

			// --- drag the first item of the grid (andesite, sorted by name) into the group ----------------
			ItemStack first = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).copy());
			check(first.is(Items.ANDESITE), "andesite comes first by name, got " + first);
			TestItems.moveTo(context, TerminalMenu.GRID_X + 8, TerminalMenu.GRID_Y + 8);
			context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(1);
			TestItems.moveTo(context, LIST_X + 30, 60);
			context.waitTicks(1);
			context.takeScreenshot("simpleme-groups-01-dragging");
			context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> groups(mc).getFirst().items().equals(List.of(ItemVariant.of(Items.ANDESITE))), 40);
			check(context.computeOnClient(mc -> mc.player.containerMenu.getCarried().isEmpty()), "dragging into a group does not take items out");
			// with the cursor on the panel, the dragged item shows up in the group with its count
			TestItems.moveTo(context, LIST_X + 9, 20 + 13 + 9);
			context.waitTicks(2);
			ItemStack shown = context.computeOnClient(mc -> {
				TerminalScreen screen = TestItems.screen(mc);
				TerminalScreen.StorageCell cell = screen.storageCellAt(mouseX(mc), mouseY(mc));
				return cell != null ? cell.stack().copy() : ItemStack.EMPTY;
			});
			check(shown.is(Items.ANDESITE), "the group shows its item, got " + shown);

			// --- share and stop editing ---------------------------------------------------------------------
			int height = context.computeOnClient(mc -> TestItems.screen(mc).getMenu().imageHeight);
			TestItems.click(context, LIST_X + 10, height - 41 + 7, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> groups(mc).getFirst().shared(), 40);
			context.takeScreenshot("simpleme-groups-02-editing");
			TestItems.click(context, LIST_X + 40, height - 24 + 7, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> !TestItems.screen(mc).groupPanel().isEditing()), "done ends editing");

			// --- items in a group can be taken out like in the grid -------------------------------------------
			TestItems.click(context, LIST_X + 9, 20 + 13 + 9, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> mc.player.containerMenu.getCarried().is(Items.ANDESITE), 40);
			TestItems.click(context, LIST_X + 9, 20 + 13 + 9, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> mc.player.containerMenu.getCarried().isEmpty(), 40);

			// --- hide and show the group (per player) -------------------------------------------------------
			TestItems.click(context, LIST_X + 30, 20 + 6, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> groups(mc).getFirst().hidden(), 40);
			server.runOnServer(s -> check(ItemGroups.get(s).visible(network[0], player(s)).getFirst().isHiddenFor(player(s)), "hidden on the server"));
			context.takeScreenshot("simpleme-groups-03-hidden");
			TestItems.click(context, LIST_X + 30, 20 + 6, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> !groups(mc).getFirst().hidden(), 40);

			// --- groups survive closing the terminal ----------------------------------------------------------
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			open(context);
			context.waitFor(mc -> groups(mc).size() == 1 && groups(mc).getFirst().items().size() == 1, 40);
			check(context.computeOnClient(mc -> GroupPanel.isExpanded()), "the panel stays open");

			// --- sorting is kept per terminal ------------------------------------------------------------
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).is(Items.STONE)), "sorted by amount, stone first");
			server.runOnServer(s -> check(terminal(s).getSortMode() == 1, "the terminal keeps its sorting, got " + terminal(s).getSortMode()));
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			open(context);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).is(Items.STONE)), "reopened with the sorting of this terminal");

			// --- a click outside the terminal takes the focus from the search box ------------------------------
			TestItems.click(context, SEARCH_X, SEARCH_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> TestItems.screen(mc).isSearchFocused()), "search box focused");
			double[] outside = context.computeOnClient(mc -> {
				double scale = mc.getWindow().getGuiScale();
				return new double[] {mc.getWindow().getGuiScaledWidth() / 2.0 * scale, (mc.getWindow().getGuiScaledHeight() - 10) * scale};
			});
			context.getInput().setCursorPos(outside[0], outside[1]);
			context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> !TestItems.screen(mc).isSearchFocused()), "a click outside the terminal unfocuses the search box");
			context.takeScreenshot("simpleme-groups-04-jei-next-to-panel");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			open(context);

			// --- R over an item opens JEI even while the search box has the focus ------------------------------
			TestItems.click(context, SEARCH_X, SEARCH_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			TestItems.moveTo(context, TerminalMenu.GRID_X + 8, TerminalMenu.GRID_Y + 8);
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_R);
			context.waitFor(GroupClientTest::isJeiRecipeScreen, 40);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitForScreen(TerminalScreen.class);
			// without an item under the mouse, the key is typed
			TestItems.click(context, SEARCH_X, SEARCH_Y, InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_R);
			context.getInput().typeChars("r");
			context.waitTicks(2);
			check(context.computeOnClient(mc -> TestItems.screen(mc).searchText().equals("r")), "R is typed without an item under the mouse");

			// --- delete the group (two clicks) ----------------------------------------------------------------
			TestItems.click(context, LIST_X + 90 - 5, 20 + 6, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> TestItems.screen(mc).groupPanel().isEditing()), "the pencil starts editing");
			int deleteX = LIST_X + 96 - 23;
			TestItems.click(context, deleteX, height - 41 + 7, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> groups(mc).size() == 1), "the first click only asks");
			TestItems.click(context, deleteX, height - 41 + 7, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> groups(mc).isEmpty(), 40);
			server.runOnServer(s -> check(terminal(s).getStorage().getAmount(ItemVariant.of(Items.ANDESITE)) == 10, "the items stay in the network"));
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		}
	}

	private static List<GroupListPayload.Entry> groups(Minecraft mc) {
		return ((TerminalMenu) Objects.requireNonNull(mc.player).containerMenu).getClientGroups();
	}

	private static TerminalBlockEntity terminal(MinecraftServer server) {
		return (TerminalBlockEntity) server.overworld().getBlockEntity(TERMINAL);
	}

	private static net.minecraft.server.level.ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static double mouseX(Minecraft mc) {
		return mc.mouseHandler.getScaledXPos(mc.getWindow());
	}

	private static double mouseY(Minecraft mc) {
		return mc.mouseHandler.getScaledYPos(mc.getWindow());
	}

	private static boolean isJeiRecipeScreen(Minecraft mc) {
		return mc.gui.screen() != null && mc.gui.screen().getClass().getName().startsWith("mezz.jei.");
	}

	private static void open(ClientGameTestContext context) {
		context.getInput().lookAt(TERMINAL);
		context.waitTicks(2);
		context.getInput().lookAt(TERMINAL);
		context.getInput().pressKey(options -> options.keyUse);
		context.waitForScreen(TerminalScreen.class);
		context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() > 0, 100);
		context.waitTicks(2);
	}
}
