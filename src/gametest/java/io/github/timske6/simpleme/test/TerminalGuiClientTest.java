package io.github.timske6.simpleme.test;

import com.mojang.blaze3d.platform.InputConstants;
import static io.github.timske6.simpleme.test.TestItems.check;

import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.StorageClickPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import io.github.timske6.simpleme.storage.TerminalStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** GUI details: sorting, search, resizing, narrow window, creative clone, shift-click, German language. */
@SuppressWarnings("UnstableApiUsage")
public class TerminalGuiClientTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);
	private static final int SORT_BUTTON_X = 173 + 9;
	private static final int SORT_BUTTON_Y = 3 + 7;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			int[] types = new int[1];
			server.runOnServer(s -> {
				TerminalStorage storage = TestItems.connectNewNetwork(s, TERMINAL, "Test");
				int i = 0;
				for (Item item : BuiltInRegistries.ITEM) {
					if (item == Items.AIR || i >= 300) {
						continue;
					}
					storage.insert(ItemVariant.of(item), (long) (i * 37 % 5000) + 1);
					i++;
				}
				storage.insert(ItemVariant.of(Items.DIAMOND), 99_999);
				types[0] = storage.typeCount();
			});

			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(20);
			open(context);
			check(context.computeOnClient(mc -> TestItems.screen(mc).visibleEntryCount()) == types[0], "all types visible");

			// --- sorting ----------------------------------------------------------------------------
			ItemStack firstByName = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).copy());
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			ItemStack firstByCount = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).copy());
			check(firstByCount.is(Items.DIAMOND), "sorting by amount puts the 99,999 diamonds first, got " + firstByCount);
			context.takeScreenshot("simpleme-gui-01-sort-by-amount");
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			context.takeScreenshot("simpleme-gui-02-sort-by-mod");
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			ItemStack again = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).copy());
			check(ItemStack.isSameItemSameComponents(again, firstByName), "third click returns to name sorting");

			// --- taking items does not shuffle the grid; an emptied type shows 0 until the terminal is reopened ---
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			List<ItemStack> orderBefore = context.computeOnClient(mc -> {
				List<ItemStack> list = new ArrayList<>();
				for (int i = 0; i < TestItems.screen(mc).visibleEntryCount(); i++) {
					list.add(TestItems.screen(mc).visibleEntry(i).copy());
				}
				return list;
			});
			int last = orderBefore.size() - 1;
			long lastCount = context.computeOnClient(mc -> TestItems.screen(mc).visibleCount(last));
			check(lastCount > 0 && lastCount <= 64, "the last entry (sorted by amount) fits into one stack, got " + lastCount);
			// shift + left click (all of the smallest type) and a stack of the biggest type into the inventory
			context.runOnClient(mc -> {
				int id = mc.player.containerMenu.containerId;
				ClientPlayNetworking.send(new StorageClickPayload(id, ItemVariant.of(orderBefore.get(last)), StorageClickPayload.QUICK_MOVE));
				ClientPlayNetworking.send(new StorageClickPayload(id, ItemVariant.of(orderBefore.get(1)), StorageClickPayload.QUICK_MOVE));
			});
			context.waitTicks(5);
			List<ItemStack> orderAfter = context.computeOnClient(mc -> {
				List<ItemStack> list = new ArrayList<>();
				for (int i = 0; i < TestItems.screen(mc).visibleEntryCount(); i++) {
					list.add(TestItems.screen(mc).visibleEntry(i).copy());
				}
				return list;
			});
			check(orderAfter.size() == orderBefore.size(), "no entry disappears while the terminal is open");
			for (int i = 0; i < orderBefore.size(); i++) {
				check(ItemStack.isSameItemSameComponents(orderBefore.get(i), orderAfter.get(i)), "order unchanged at " + i);
			}
			check(context.computeOnClient(mc -> TestItems.screen(mc).visibleCount(last)) == 0, "the emptied type shows 0");
			context.takeScreenshot("simpleme-gui-01b-emptied-type-shows-0");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);
			open(context);
			int reopened = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntryCount());
			check(reopened == orderBefore.size() - 1, "after reopening the emptied type is gone, got " + reopened + " of " + orderBefore.size());
			// back to name sorting for the rest of the test
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			TestItems.click(context, SORT_BUTTON_X, SORT_BUTTON_Y, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);

			// --- search is kept when the window is resized ---------------------------------------------
			TestItems.click(context, 82 + 20, 4 + 6, InputConstants.MOUSE_BUTTON_LEFT);
			context.getInput().typeChars("@minecraft oak");
			context.waitTicks(2);
			int filtered = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntryCount());
			check(filtered > 0 && filtered < types[0], "mod + name filter narrows the list, got " + filtered);
			context.getInput().resizeWindow(1600, 900);
			context.waitTicks(5);
			String text = context.computeOnClient(mc -> TestItems.screen(mc).searchText());
			int afterResize = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntryCount());
			check(text.equals("@minecraft oak") && afterResize == filtered, "search survives a window resize: '" + text + "' " + afterResize);
			context.takeScreenshot("simpleme-gui-03-search-after-resize");
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			TestItems.click(context, 82 + 20, 4 + 6, InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitTicks(2);

			// --- keys over the grid do nothing harmful ------------------------------------------------
			TestItems.moveTo(context, 16, 26);
			context.getInput().pressKey(InputConstants.KEY_1);
			context.getInput().pressKey(InputConstants.KEY_Q);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof TerminalScreen), "screen still open after 1/Q over the grid");

			// --- shift-click moves a stack into the inventory (the test input cannot hold shift during a
			// click, so the click packet is sent the way the screen sends it for shift + left click) -----
			context.runOnClient(mc -> {
				ItemStack first = TestItems.screen(mc).visibleEntry(0);
				ClientPlayNetworking.send(new StorageClickPayload(mc.player.containerMenu.containerId, ItemVariant.of(first), StorageClickPayload.QUICK_MOVE));
			});
			context.waitTicks(5);
			int inInventory = context.computeOnClient(mc -> {
				int count = 0;
				for (int i = 0; i < 36; i++) {
					count += mc.player.getInventory().getItem(i).getCount();
				}
				return count;
			});
			check(inInventory > 0, "shift-click should move items into the inventory");

			// --- creative: middle click copies a stack without taking it out ---------------------------
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			server.runCommand("gamemode creative @a");
			server.runCommand("clear @a");
			context.waitTicks(3);
			open(context);
			ItemStack target = context.computeOnClient(mc -> TestItems.screen(mc).visibleEntry(0).copy());
			long before = server.computeOnServer(s -> ((TerminalBlockEntity) s.overworld().getBlockEntity(TERMINAL)).getStorage().getAmount(ItemVariant.of(target)));
			TestItems.click(context, 16, 26, InputConstants.MOUSE_BUTTON_MIDDLE);
			context.waitTicks(3);
			ItemStack cloned = context.computeOnClient(mc -> mc.player.containerMenu.getCarried().copy());
			long after = server.computeOnServer(s -> ((TerminalBlockEntity) s.overworld().getBlockEntity(TERMINAL)).getStorage().getAmount(ItemVariant.of(target)));
			check(cloned.getCount() == cloned.getMaxStackSize() && ItemStack.isSameItemSameComponents(cloned, target), "middle click clones a full stack, got " + cloned);
			check(before == after, "cloning must not extract");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			server.runCommand("clear @a");
			server.runCommand("gamemode survival @a");
			context.waitTicks(3);

			// --- narrow window: recipe book covers the terminal, grid clicks are blocked ------------------
			context.runOnClient(mc -> mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, true));
			context.getInput().resizeWindow(700, 600);
			context.waitTicks(3);
			open(context);
			context.takeScreenshot("simpleme-gui-04-narrow-recipe-book");
			TestItems.click(context, 16, 26, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			check(context.computeOnClient(mc -> mc.player.containerMenu.getCarried().isEmpty()), "grid must not react while the recipe book covers it");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.runOnClient(mc -> mc.player.getRecipeBook().setOpen(RecipeBookType.CRAFTING, false));
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(3);

			// the menu type and rows are right after all of this
			open(context);
			int rows = context.computeOnClient(mc -> TestItems.screen(mc).getMenu().rows);
			check(rows >= TerminalMenu.MIN_ROWS && rows <= TerminalMenu.MAX_ROWS, "rows in range: " + rows);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);

			// --- German translation (last: in the test environment a resource reload can leave the game paused) ---
			setLanguage(context, "de_de");
			open(context);
			TestItems.moveTo(context, 8 + 18 * 2 + 8, 18 + 8);
			context.waitTicks(3);
			context.takeScreenshot("simpleme-gui-05-german");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			setLanguage(context, "en_us");
		}
	}

	private static void open(ClientGameTestContext context) {
		// Mouse moves made while a screen was open can still turn the camera once it closes.
		context.getInput().lookAt(TERMINAL);
		context.waitTicks(2);
		context.getInput().lookAt(TERMINAL);
		context.getInput().pressKey(options -> options.keyUse);
		try {
			context.waitForScreen(TerminalScreen.class);
		} catch (AssertionError e) {
			String state = context.computeOnClient(mc -> "screen=" + mc.gui.screen() + " hit=" + mc.hitResult
				+ " yaw=" + mc.player.getYRot() + " pitch=" + mc.player.getXRot() + " pos=" + mc.player.position());
			System.out.println("[simpleme-test] open failed: " + state);
			context.takeScreenshot("simpleme-debug-open-failed");
			throw e;
		}
		context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() > 0, 100);
		context.waitTicks(2);
	}

	private static void setLanguage(ClientGameTestContext context, String code) {
		CompletableFuture<?>[] reload = new CompletableFuture<?>[1];
		context.runOnClient(mc -> {
			mc.options.languageCode = code;
			mc.getLanguageManager().setSelected(code);
			reload[0] = mc.reloadResourcePacks();
		});
		context.waitFor(mc -> reload[0].isDone(), 1200);
		// the loading overlay pauses singleplayer until it has faded out
		context.waitFor(mc -> !mc.isPaused(), 600);
		context.waitTicks(5);
	}
}
