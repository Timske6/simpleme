package io.github.timske6.simpleme.test;

import com.mojang.blaze3d.platform.InputConstants;
import static io.github.timske6.simpleme.test.TestItems.check;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.block.TransferChestBlock;
import io.github.timske6.simpleme.block.TransferChestBlockEntity;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.client.TransferChestScreen;
import io.github.timske6.simpleme.menu.TransferChestMenu;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import io.github.timske6.simpleme.storage.TerminalStorage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Transfer chest GUI (on/off button) and the wireless terminal far away from its terminal. */
@SuppressWarnings("UnstableApiUsage")
public class LinkedStorageClientTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);
	private static final BlockPos CHEST = new BlockPos(2, -60, -3);

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a 1.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			server.runCommand("setblock 2 -60 -3 simpleme:transfer_chest[facing=south]");
			server.runOnServer(s -> {
				TerminalStorage storage = TestItems.connectNewNetwork(s, TERMINAL, "Lager");
				storage.insert(ItemVariant.of(Items.STONE), 12_345);
				storage.insert(ItemVariant.of(Items.DIAMOND), 42);
				storage.insert(ItemVariant.of(Items.OAK_LOG), 64);
				StorageLink link = StorageLink.of(Networks.get(s).find(terminal(s).getNetworkId()));
				ItemStack remote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
				remote.set(ModRegistry.LINK, link);
				s.getPlayerList().getPlayers().getFirst().setItemInHand(InteractionHand.MAIN_HAND, remote);
				((TransferChestBlockEntity) s.overworld().getBlockEntity(CHEST)).setLink(link);
			});
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(5);

			// --- transfer chest: status and on/off button ---------------------------------------------
			context.getInput().lookAt(CHEST);
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TransferChestScreen.class);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> chestMenu(mc).status()) == TransferChestMenu.STATUS_ONLINE, "chest shows 'connected'");
			check(context.computeOnClient(mc -> chestMenu(mc).isEnabled()), "chest starts switched on");
			context.takeScreenshot("simpleme-link-01-transfer-chest-on");

			// the network button opens the network panel; Escape closes only the panel
			clickChestGui(context, 176 - 7 - 48, 114 + TransferChestMenu.ROWS * 18 - 94 - 3 + 6);
			check(context.computeOnClient(mc -> ((TransferChestScreen) mc.gui.screen()).isNetworkPanelOpen()), "network button opens the panel");
			context.takeScreenshot("simpleme-link-01b-chest-network-panel");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> mc.gui.screen() instanceof TransferChestScreen screen && !screen.isNetworkPanelOpen()),
				"Escape closes the panel, the chest stays open");

			clickToggle(context);
			check(!server.computeOnServer(s -> s.overworld().getBlockState(CHEST).getValue(TransferChestBlock.ENABLED)), "button switches the chest off");
			check(!context.computeOnClient(mc -> chestMenu(mc).isEnabled()), "client sees the chest switched off");
			context.takeScreenshot("simpleme-link-02-transfer-chest-off");
			server.runCommand("item replace block 2 -60 -3 container.0 with gold_ingot 32");
			context.waitTicks(30);
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.GOLD_INGOT))) == 0, "switched off: items stay in the chest");

			clickToggle(context);
			context.waitTicks(5);
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.GOLD_INGOT))) == 32, "switched on: gold sent to the storage");
			check(server.computeOnServer(s -> ((TransferChestBlockEntity) s.overworld().getBlockEntity(CHEST)).isEmpty()), "chest is empty again");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);

			// --- wireless terminal, far away from the terminal ------------------------------------------
			server.runCommand("tp @a 3000.5 -60 3000.5 0 60");
			singleplayer.getConnection().waitForChunksRender();
			context.waitTicks(10);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() == 4, 100);
			context.waitTicks(3);
			context.takeScreenshot("simpleme-link-03-wireless-terminal");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			context.waitTicks(3);

			// --- pick block (middle click): not in the inventory, so it comes from the ME system ----------
			BlockPos stone = new BlockPos(3000, -60, 3002);
			server.runCommand("setblock 3000 -60 3002 stone");
			context.waitTicks(2);
			context.getInput().lookAt(stone);
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyPickItem);
			context.waitTicks(5);
			check(context.computeOnClient(mc -> mc.player.getMainHandItem().is(Items.STONE) && mc.player.getMainHandItem().getCount() == 64),
				"pick block takes one stack of stone from the storage");
			check(server.computeOnServer(s -> terminal(s).getStorage().getAmount(ItemVariant.of(Items.STONE))) == 12_345 - 64, "taken out of the storage");
			context.takeScreenshot("simpleme-link-04-pick-block");
			context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			context.waitTicks(2);

			// --- no terminal block at all: the wireless terminal still opens the network -------------
			server.runOnServer(s -> s.overworld().destroyBlock(TERMINAL, false));
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() == 4, 100);
			context.takeScreenshot("simpleme-link-05-without-terminal");
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		}
	}

	private static TerminalBlockEntity terminal(MinecraftServer server) {
		return (TerminalBlockEntity) server.overworld().getBlockEntity(TERMINAL);
	}

	private static TransferChestMenu chestMenu(net.minecraft.client.Minecraft mc) {
		return ((TransferChestScreen) mc.gui.screen()).getMenu();
	}

	private static void clickChestGui(ClientGameTestContext context, int guiX, int guiY) {
		double[] pos = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			int left = (mc.getWindow().getGuiScaledWidth() - 176) / 2;
			int top = (mc.getWindow().getGuiScaledHeight() - (114 + TransferChestMenu.ROWS * 18)) / 2;
			return new double[] {(left + guiX) * scale, (top + guiY) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
	}

	private static void clickToggle(ClientGameTestContext context) {
		double[] pos = context.computeOnClient(mc -> {
			double scale = mc.getWindow().getGuiScale();
			int left = (mc.getWindow().getGuiScaledWidth() - 176) / 2;
			int top = (mc.getWindow().getGuiScaledHeight() - (114 + TransferChestMenu.ROWS * 18)) / 2;
			return new double[] {(left + 176 - 7 - 15) * scale, (top + 3 + 6) * scale};
		});
		context.getInput().setCursorPos(pos[0], pos[1]);
		context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		context.waitTicks(3);
	}
}
