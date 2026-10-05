package io.github.timske6.simpleme.test;

import static io.github.timske6.simpleme.test.TestItems.check;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.client.GroupPanel;
import io.github.timske6.simpleme.client.TerminalScreen;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.GroupActionPayload;
import io.github.timske6.simpleme.network.GroupListPayload;
import io.github.timske6.simpleme.network.NetworkActionPayload;
import io.github.timske6.simpleme.network.NetworkActions;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.storage.ItemGroups;
import io.github.timske6.simpleme.storage.Networks;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.Items;

/**
 * A second player on the server changes things while the real client has the terminal open: the
 * client must follow every change live (groups, counts, sorting, network name, access).
 */
@SuppressWarnings("UnstableApiUsage")
public class MultiplayerClientTest implements FabricClientGameTest {
	private static final BlockPos TERMINAL = new BlockPos(0, -60, -3);

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a 0.5 -60 0.5 180 20");
			server.runCommand("setblock 0 -60 -3 simpleme:terminal[facing=south]");
			ServerPlayer[] bob = new ServerPlayer[1];
			UUID[] network = new UUID[1];
			server.runOnServer(s -> {
				bob[0] = joinFakePlayer(s, "Bob");
				Networks networks = Networks.get(s);
				Networks.Network bobs = networks.create(bob[0], "Bobs network");
				networks.setPublic(bobs, true);
				network[0] = bobs.id();
				TerminalBlockEntity terminal = terminal(s);
				terminal.setNetworkId(bobs.id());
				terminal.getStorage().insert(ItemVariant.of(Items.COAL), 40);
				terminal.getStorage().insert(ItemVariant.of(Items.APPLE), 5);
				terminal.getStorage().insert(ItemVariant.of(Items.BONE), 900);
				bob[0].openMenu(terminal);
			});
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(10);
			context.getInput().lookAt(TERMINAL);
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(TerminalScreen.class);
			// the search is remembered while the game runs: clear it (right click) and leave the box
			TestItems.click(context, 82 + 20, 4 + 6, InputConstants.MOUSE_BUTTON_RIGHT);
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.waitFor(mc -> TestItems.screen(mc) != null && TestItems.screen(mc).visibleEntryCount() == 3, 100);
			String order = context.computeOnClient(mc -> {
				TerminalScreen screen = TestItems.screen(mc);
				return screen.visibleEntry(0).getItem() + "," + screen.visibleEntry(1).getItem() + "," + screen.visibleEntry(2).getItem()
					+ " sort=" + menu(mc).getSortMode() + " full=" + menu(mc).getClientFullSyncs() + " rev=" + menu(mc).getClientRevision();
			});
			check(order.startsWith("minecraft:apple,minecraft:bone,minecraft:coal"),
				"sorted by name when opened, no matter whether the items arrive before or after the screen is set up: " + order);
			int craftingY = context.computeOnClient(mc -> TestItems.screen(mc).getMenu().craftingY);
			TestItems.click(context, 152 + 10, craftingY - 3 + 9, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			check(context.computeOnClient(mc -> GroupPanel.isExpanded()), "group panel open");

			// --- Bob makes a group and shares it: it shows up here right away, read only -----------------
			UUID bobsGroup = UUID.randomUUID();
			server.runOnServer(s -> {
				groupAction(bob[0], GroupActionPayload.CREATE, bobsGroup, "Fuel", null);
				groupAction(bob[0], GroupActionPayload.ADD_ITEM, bobsGroup, "", ItemVariant.of(Items.COAL));
				bob[0].containerMenu.broadcastChanges();
			});
			context.waitTicks(5);
			check(context.computeOnClient(mc -> groups(mc).isEmpty()), "a private group of another player is not sent");
			server.runOnServer(s -> {
				groupAction(bob[0], GroupActionPayload.TOGGLE_SHARED, bobsGroup, "", null);
				bob[0].containerMenu.broadcastChanges();
			});
			context.waitFor(mc -> groups(mc).size() == 1, 40);
			GroupListPayload.Entry shared = context.computeOnClient(mc -> groups(mc).getFirst());
			check(shared.name().equals("Fuel") && !shared.canManage() && shared.ownerName().equals("Bob")
				&& shared.items().equals(List.of(ItemVariant.of(Items.COAL))), "Bob's shared group, read only here: " + shared);

			// --- Bob takes all the coal: the group keeps showing it, with 0 ------------------------------
			server.runOnServer(s -> {
				TerminalMenu menu = (TerminalMenu) bob[0].containerMenu;
				menu.handleStorageClick(bob[0], ItemVariant.of(Items.COAL), StorageClickPayload.QUICK_MOVE);
			});
			context.waitFor(mc -> menu(mc).getClientItems().getLong(ItemVariant.of(Items.COAL)) == 0, 40);
			context.waitFor(mc -> TestItems.screen(mc).visibleCount(2) == 0, 20);
			check(context.computeOnClient(mc -> groups(mc).getFirst().items().contains(ItemVariant.of(Items.COAL))), "still in the group");
			context.takeScreenshot("simpleme-multiplayer-01-shared-group-empty-item");

			// --- Bob changes the sorting of this terminal: it changes here too -----------------------------
			server.runOnServer(s -> {
				bob[0].containerMenu.clickMenuButton(bob[0], TerminalMenu.BUTTON_SORT + 1);
				bob[0].containerMenu.broadcastChanges();
			});
			context.waitFor(mc -> TestItems.screen(mc).visibleEntry(0).is(Items.BONE), 40);

			// --- Bob renames the network: the name on the network button follows ----------------------------
			server.runOnServer(s -> NetworkActions.handle(bob[0],
				new NetworkActionPayload(bob[0].containerMenu.containerId, NetworkActionPayload.RENAME, Optional.of(network[0]), "Renamed")));
			context.waitFor(mc -> menu(mc).networkState().currentName().equals("Renamed"), 40);

			// --- my group is deleted elsewhere while I edit it: edit mode ends ----------------------------------
			TestItems.click(context, -(GroupPanel.WIDTH + 2) + GroupPanel.WIDTH - 36 + 7, 4 + 7, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> groups(mc).size() == 2, 40);
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			check(context.computeOnClient(mc -> TestItems.screen(mc).groupPanel().isEditing()), "editing my new group");
			server.runOnServer(s -> {
				ItemGroups groups = ItemGroups.get(s);
				groups.all(network[0]).stream().filter(group -> !group.id().equals(bobsGroup)).toList().forEach(groups::delete);
			});
			context.waitFor(mc -> groups(mc).size() == 1 && !TestItems.screen(mc).groupPanel().isEditing(), 60);

			// --- Bob makes his group private again: gone here --------------------------------------------------
			server.runOnServer(s -> {
				groupAction(bob[0], GroupActionPayload.TOGGLE_SHARED, bobsGroup, "", null);
				bob[0].containerMenu.broadcastChanges();
			});
			context.waitFor(mc -> groups(mc).isEmpty(), 40);

			// --- Bob makes the network private: no more items, groups or network for me -------------------------
			server.runOnServer(s -> Networks.get(s).setPublic(Networks.get(s).find(network[0]), false));
			context.waitFor(mc -> menu(mc).getClientItems().isEmpty() && menu(mc).networkState().current() == null, 60);
			server.runOnServer(s -> {
				ServerPlayer me = s.getPlayerList().getPlayers().stream().filter(player -> player != bob[0]).findFirst().orElseThrow();
				TerminalMenu menu = (TerminalMenu) me.containerMenu;
				menu.handleStorageClick(me, ItemVariant.of(Items.BONE), StorageClickPayload.TAKE_STACK);
				check(menu.getCarried().isEmpty(), "no taking from a network that became private");
			});
			context.takeScreenshot("simpleme-multiplayer-02-network-became-private");

			// fold the panel in again for later tests and leave
			TestItems.click(context, 152 + 10, craftingY - 3 + 9, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(2);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			server.runOnServer(s -> {
				bob[0].closeContainer();
				s.getPlayerList().remove(bob[0]);
			});
		}
	}

	private static ServerPlayer joinFakePlayer(MinecraftServer server, String name) {
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
		ServerPlayer player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		player.setPos(0.5, -60, 2.5);
		return player;
	}

	private static void groupAction(ServerPlayer player, int action, UUID group, String text, ItemVariant item) {
		((TerminalMenu) player.containerMenu).handleGroupAction(player,
			new GroupActionPayload(player.containerMenu.containerId, action, group, text, Optional.ofNullable(item)));
	}

	private static TerminalBlockEntity terminal(MinecraftServer server) {
		return (TerminalBlockEntity) server.overworld().getBlockEntity(TERMINAL);
	}

	private static TerminalMenu menu(Minecraft mc) {
		return (TerminalMenu) Objects.requireNonNull(mc.player).containerMenu;
	}

	private static List<GroupListPayload.Entry> groups(Minecraft mc) {
		return menu(mc).getClientGroups();
	}
}
