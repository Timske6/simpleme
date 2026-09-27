package io.github.timske6.simpleme.test;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.storage.TerminalContents;
import io.github.timske6.simpleme.storage.TerminalStorage;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

@SuppressWarnings("UnstableApiUsage")
public class TerminalClientGameTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);
	private static final BlockPos RELOAD_TERMINAL = new BlockPos(5, -60, -3);

	@Override
	public void runTest(ClientGameTestContext context) {
		TestWorldSave save;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			save = singleplayer.getWorldSave();
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();

			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			// hopper feeding the terminal from above
			server.runCommand("setblock 0 -59 -3 hopper[facing=down]");
			server.runCommand("item replace block 0 -59 -3 container.0 with coal 5");

			server.runOnServer(s -> {
				TerminalStorage storage = terminal(s).getStorage();
				storage.insert(ItemVariant.of(Items.STONE), 12_345);
				storage.insert(ItemVariant.of(Items.COBBLESTONE), 120);
				storage.insert(ItemVariant.of(Items.OAK_LOG), 64);
				storage.insert(ItemVariant.of(Items.DIAMOND), 3);
				storage.insert(ItemVariant.of(Items.IRON_INGOT), 1_500_000);
				storage.insert(ItemVariant.of(Items.MILK_BUCKET), 6);
				storage.insert(ItemVariant.of(Items.SUGAR), 10);
				storage.insert(ItemVariant.of(Items.EGG), 5);
				storage.insert(ItemVariant.of(Items.WHEAT), 10);
				ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
				sword.setDamageValue(100);
				storage.insert(ItemVariant.of(sword), 1);
			});

			context.waitTicks(40);
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.COAL))) == 5,
				"hopper should have pushed 5 coal into the terminal");

			// --- open the terminal like a player would (1080p, automatic GUI scale) ----------------
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(2);
			context.getInput().lookAt(TERMINAL);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(5);
			int visible = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).visibleEntryCount());
			check(visible == 11, "expected 11 item types in the grid, got " + visible);
			context.takeScreenshot("simpleme-01-terminal-open");

			// --- click the first grid entry: takes a full stack onto the cursor ------------------
			clickGui(context, 8 + 8, 18 + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			// sorted by name the first entry is coal (5 pieces)
			ItemStack carried = context.computeOnClient(mc -> mc.player.containerMenu.getCarried().copy());
			check(carried.is(Items.COAL) && carried.getCount() == 5, "expected 5 coal on the cursor, got " + carried);
			context.takeScreenshot("simpleme-02-carrying");

			// --- click again on the grid: puts it back ---------------------------------------
			clickGui(context, 8 + 18 * 4 + 8, 18 + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.player.containerMenu.getCarried().isEmpty()), "cursor should be empty after inserting");

			// --- typing into the search box filters the grid ---------------------------------
			clickGui(context, 82 + 20, 4 + 6, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			context.getInput().typeChars("iron");
			context.waitTicks(3);
			visible = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).visibleEntryCount());
			check(visible == 1, "search 'iron' should show 1 entry, got " + visible);
			context.takeScreenshot("simpleme-03-search");
			context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			clickGui(context, 82 + 20, 4 + 6, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
			context.waitTicks(3);
			visible = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).visibleEntryCount());
			check(visible == 11, "right-clicking the search box should clear it, got " + visible);

			// --- recipe book placement from storage + shift-click crafting ---------------------
			server.runOnServer(s -> {
				ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
				TerminalMenu menu = (TerminalMenu) player.containerMenu;
				menu.handlePlacement(false, false, recipe(s, "oak_planks"), player.level(), player.getInventory());
				menu.broadcastChanges();
			});
			context.waitTicks(5);
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.OAK_LOG))) == 63,
				"recipe placement should take 1 log from the storage");
			context.takeScreenshot("simpleme-04-recipe-placed");

			// shift-click the result through the real client -> server path
			context.runOnClient(mc -> mc.gameMode.handleContainerInput(
				mc.player.containerMenu.containerId, TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, mc.player));
			context.waitTicks(5);
			int planks = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getInventory().countItem(Items.OAK_PLANKS));
			long logs = server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.OAK_LOG)));
			check(planks == 64, "shift-click should craft one stack (64) of planks, got " + planks);
			check(logs == 47, "16 logs used + 1 refilled into the grid -> 47 left, got " + logs);
			context.takeScreenshot("simpleme-05-crafted-planks");

			// --- crafting remainders (cake: milk buckets -> empty buckets go into storage) --------
			server.runOnServer(s -> {
				ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
				TerminalMenu menu = (TerminalMenu) player.containerMenu;
				menu.handlePlacement(false, false, recipe(s, "cake"), player.level(), player.getInventory());
				menu.broadcastChanges();
			});
			context.waitTicks(5);
			// the grid now holds the cake recipe (the planks grid went back into storage)
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.OAK_LOG))) == 48,
				"placing another recipe should return the old grid to the storage");
			context.runOnClient(mc -> mc.gameMode.handleContainerInput(
				mc.player.containerMenu.containerId, TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, mc.player));
			context.waitTicks(5);
			ItemStack cake = context.computeOnClient(mc -> mc.player.containerMenu.getCarried().copy());
			check(cake.is(Items.CAKE), "expected a cake on the cursor, got " + cake);
			long buckets = server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.BUCKET)));
			long milk = server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.MILK_BUCKET)));
			check(buckets == 3, "3 empty buckets should be back in the storage, got " + buckets);
			check(milk == 0, "grid should have been refilled with the remaining 3 milk buckets, storage has " + milk);
			context.takeScreenshot("simpleme-06-cake");

			// put the cake into the storage, then clear the crafting grid via the × button
			clickGui(context, 8 + 8, 18 + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
			context.waitTicks(3);
			context.runOnClient(mc -> mc.gameMode.handleInventoryButtonClick(mc.player.containerMenu.containerId, TerminalMenu.BUTTON_CLEAR_GRID));
			context.waitTicks(5);
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.MILK_BUCKET))) == 3,
				"clearing the grid should return 3 milk buckets");
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.CAKE))) == 1,
				"the cake should have been stored");

			// shift-click from the player inventory into the storage
			context.runOnClient(mc -> mc.gameMode.handleContainerInput(
				mc.player.containerMenu.containerId, TerminalMenu.INV_END - 1, 0, ContainerInput.QUICK_MOVE, mc.player));
			context.waitTicks(5);
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.OAK_PLANKS))) == 64,
				"shift-clicking the planks in the hotbar should store them");
			context.takeScreenshot("simpleme-07-final");

			// --- recipe book open --------------------------------------------------------------
			context.runOnClient(mc -> mc.player.getRecipeBook().setOpen(net.minecraft.world.inventory.RecipeBookType.CRAFTING, true));
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitTicks(3);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("simpleme-08-recipe-book");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.waitTicks(3);

			// --- GUI scale 3 gives room for more rows -------------------------------------------
			context.runOnClient(mc -> {
				mc.player.getRecipeBook().setOpen(net.minecraft.world.inventory.RecipeBookType.CRAFTING, false);
				mc.options.guiScale().set(3);
				mc.resizeGui();
			});
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(5);
			int rows = context.computeOnClient(mc -> ((TerminalScreen) mc.gui.screen()).getMenu().rows);
			check(rows == 8, "GUI scale 3 at 1080p should show 8 rows, got " + rows);
			context.takeScreenshot("simpleme-09-gui-scale-3");
			context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
			context.runOnClient(mc -> {
				mc.options.guiScale().set(0);
				mc.resizeGui();
			});
			context.waitTicks(3);

			// --- breaking keeps the items in the dropped block ------------------------------------
			long typesBefore = server.computeOnServer(s -> (long) terminal(s).getStorage().typeCount());
			server.runCommand("setblock 0 -59 -3 air");
			server.runCommand("setblock 0 -60 -3 air destroy");
			context.waitTicks(5);
			TerminalContents dropped = server.computeOnServer(s -> {
				List<ItemEntity> items = s.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(TERMINAL).inflate(3),
					entity -> entity.getItem().is(ModRegistry.TERMINAL_ITEM));
				return items.isEmpty() ? null : items.getFirst().getItem().get(ModRegistry.STORAGE);
			});
			check(dropped != null && dropped.types() == typesBefore,
				"dropped terminal should contain " + typesBefore + " item types, got " + dropped);

			// give it to the player and show the tooltip in the inventory
			server.runOnServer(s -> {
				ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
				ItemStack stack = new ItemStack(ModRegistry.TERMINAL_ITEM);
				stack.set(ModRegistry.STORAGE, dropped);
				player.getInventory().setItem(0, stack);
			});
			context.waitTicks(3);
			context.takeScreenshot("simpleme-10-dropped-item");

			// --- persistence: storage survives a world reload --------------------------------------
			server.runCommand("setblock 5 -60 -3 simpleme:terminal");
			server.runOnServer(s -> {
				TerminalBlockEntity be = (TerminalBlockEntity) s.overworld().getBlockEntity(RELOAD_TERMINAL);
				be.getStorage().insert(ItemVariant.of(Items.GOLD_INGOT), 777);
				ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
				book.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Named"));
				be.getStorage().insert(ItemVariant.of(book), 2);
			});
		}

		try (TestSingleplayerContext singleplayer = save.open()) {
			singleplayer.getConnection().waitForChunksRender();
			long gold = singleplayer.getServer().computeOnServer(s -> {
				TerminalBlockEntity be = (TerminalBlockEntity) s.overworld().getBlockEntity(RELOAD_TERMINAL);
				return be == null ? -1L : be.getStorage().getAmount(ItemVariant.of(Items.GOLD_INGOT));
			});
			check(gold == 777, "storage should survive a reload, gold = " + gold);
			int types = singleplayer.getServer().computeOnServer(s -> ((TerminalBlockEntity) s.overworld().getBlockEntity(RELOAD_TERMINAL)).getStorage().typeCount());
			check(types == 2, "both item types should survive a reload, got " + types);
		}
	}

	private static TerminalBlockEntity terminal(MinecraftServer server) {
		return (TerminalBlockEntity) server.overworld().getBlockEntity(TERMINAL);
	}

	private static RecipeHolder<?> recipe(MinecraftServer server, String name) {
		ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(name));
		return server.getRecipeManager().byKey(key).orElseThrow();
	}

	/** Clicks at a position given in GUI coordinates relative to the terminal window. */
	private static void clickGui(ClientGameTestContext context, int guiX, int guiY, int button) {
		double[] pos = context.computeOnClient(mc -> {
			TerminalScreen screen = (TerminalScreen) mc.gui.screen();
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(screen.guiLeft() + guiX) * scale, (screen.guiTop() + guiY) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.getInput().pressMouse(button);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
