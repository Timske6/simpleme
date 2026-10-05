package io.github.timske6.simpleme.test;

import com.mojang.authlib.GameProfile;
import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.item.WirelessTerminalItem;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.GroupActionPayload;
import io.github.timske6.simpleme.network.GroupListPayload;
import io.github.timske6.simpleme.network.NetworkActionPayload;
import io.github.timske6.simpleme.network.NetworkActions;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.storage.ItemGroups;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import io.github.timske6.simpleme.storage.TerminalStorage;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Server-side tests with several players: item groups and who may do what with them, the sorting
 * kept per terminal, wireless terminals with modified clients, access that changes while a menu is
 * open, and a long random run that checks after every step that no item appears or vanishes.
 */
public class MultiplayerGameTests {
	private static final BlockPos TERMINAL = new BlockPos(2, 2, 2);
	private static final BlockPos SECOND_TERMINAL = new BlockPos(4, 2, 2);

	// ------------------------------------------------------------------------------------------
	// Groups: who sees and changes what
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void groupsArePrivateUntilShared(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper, TERMINAL);
		UUID network = terminal.getNetworkId();
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer other = mockPlayer(helper, GameType.SURVIVAL);
		ItemGroups groups = ItemGroups.get(helper.getLevel().getServer());
		TerminalMenu ownerMenu = openTerminal(helper, owner, TERMINAL);
		UUID id = createGroup(owner, "Ores");

		helper.assertTrue(groups.visible(network, owner).size() == 1, "the owner sees the new group");
		helper.assertTrue(groups.visible(network, other).isEmpty(), "a new group is private");
		TerminalMenu otherMenu = openTerminal(helper, other, TERMINAL);
		terminal.getStorage().insert(ItemVariant.of(Items.IRON_ORE), 5);
		// someone who cannot see the group cannot touch it either
		groupAction(other, GroupActionPayload.RENAME, id, "Mine", null);
		groupAction(other, GroupActionPayload.TOGGLE_SHARED, id, "", null);
		groupAction(other, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.IRON_ORE));
		groupAction(other, GroupActionPayload.HIDE, id, "", null);
		groupAction(other, GroupActionPayload.DELETE, id, "", null);
		ItemGroups.Group group = groups.find(id);
		helper.assertTrue(group != null && group.name().equals("Ores") && !group.isShared() && group.items().isEmpty() && !group.isHiddenFor(other),
			"others cannot change a private group");

		groupAction(owner, GroupActionPayload.TOGGLE_SHARED, id, "", null);
		helper.assertTrue(groups.visible(network, other).size() == 1, "shared: everybody who uses the network sees it");
		// seeing is not managing
		groupAction(other, GroupActionPayload.RENAME, id, "Mine", null);
		groupAction(other, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.IRON_ORE));
		groupAction(other, GroupActionPayload.TOGGLE_SHARED, id, "", null);
		groupAction(other, GroupActionPayload.DELETE, id, "", null);
		helper.assertTrue(groups.find(id) != null && group.name().equals("Ores") && group.isShared() && group.items().isEmpty(),
			"only the owner renames, fills, shares and deletes");

		// hiding is for each player alone
		groupAction(other, GroupActionPayload.HIDE, id, "", null);
		helper.assertTrue(group.isHiddenFor(other) && !group.isHiddenFor(owner), "hidden only for the player who hid it");
		groupAction(owner, GroupActionPayload.HIDE, id, "", null);
		groupAction(other, GroupActionPayload.SHOW, id, "", null);
		helper.assertTrue(!group.isHiddenFor(other) && group.isHiddenFor(owner), "shown again only for that player");
		helper.assertTrue(ownerMenu.networkId().equals(otherMenu.networkId()), "both on the same network");
		finish(helper, owner, other);
	}

	@GameTest
	public void groupItemsMustBeKnownToThePlayer(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper, TERMINAL);
		TerminalStorage storage = terminal.getStorage();
		storage.insert(ItemVariant.of(Items.COAL), 10);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player, TERMINAL);
		UUID id = createGroup(player, "Stuff");
		ItemGroups.Group group = ItemGroups.get(helper.getLevel().getServer()).find(id);

		groupAction(player, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.NETHER_STAR));
		helper.assertTrue(group.items().isEmpty(), "a modified client cannot add items the player cannot see");
		groupAction(player, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.blank());
		helper.assertTrue(group.items().isEmpty(), "blank is rejected");
		groupAction(player, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.COAL));
		groupAction(player, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.COAL));
		helper.assertTrue(group.items().equals(List.of(ItemVariant.of(Items.COAL))), "an item of the network, only once");
		menu.setCarried(new ItemStack(Items.DIAMOND_SWORD));
		groupAction(player, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.DIAMOND_SWORD));
		helper.assertTrue(group.items().size() == 2 && menu.getCarried().is(Items.DIAMOND_SWORD), "the item on the cursor (it stays there)");
		menu.setCarried(ItemStack.EMPTY);
		// the group keeps items that ran out
		storage.extract(ItemVariant.of(Items.COAL), 10);
		helper.assertTrue(group.items().contains(ItemVariant.of(Items.COAL)), "items stay in the group with 0 in the network");
		groupAction(player, GroupActionPayload.REMOVE_ITEM, id, "", ItemVariant.of(Items.COAL));
		groupAction(player, GroupActionPayload.REMOVE_ITEM, id, "", ItemVariant.of(Items.STONE));
		helper.assertTrue(group.items().equals(List.of(ItemVariant.of(Items.DIAMOND_SWORD))), "removed again; removing a missing item does nothing");
		helper.assertTrue(storage.isEmpty() && player.getInventory().isEmpty(), "groups never move items");
		finish(helper, player);
	}

	@GameTest
	public void groupLimitsAndNames(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeTerminal(helper, TERMINAL);
		UUID network = terminal.getNetworkId();
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player, TERMINAL);
		ItemGroups groups = ItemGroups.get(server);

		UUID named = createGroup(player, "§cRed\u0007" + "x".repeat(100));
		String name = groups.find(named).name();
		helper.assertTrue(!name.contains("§") && !name.contains("\u0007") && name.length() == ItemGroups.MAX_NAME_LENGTH,
			"formatting and control characters are removed and the name is cut, got " + name);
		UUID empty = createGroup(player, "   ");
		helper.assertTrue(!groups.find(empty).name().isBlank(), "an empty name gets a default");

		// the client chooses the id: an id that is taken is refused
		UUID taken = createGroup(player, "First");
		groupAction(player, GroupActionPayload.CREATE, taken, "Second", null);
		helper.assertTrue(groups.find(taken).name().equals("First"), "an existing group is not replaced");

		for (int i = groups.ownedCount(network, player); i < ItemGroups.MAX_GROUPS + 3; i++) {
			createGroup(player, "G" + i);
		}
		helper.assertTrue(groups.ownedCount(network, player) == ItemGroups.MAX_GROUPS, "at most " + ItemGroups.MAX_GROUPS + " groups per player and network");

		TerminalStorage storage = terminal.getStorage();
		List<Item> items = new ArrayList<>();
		for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (item != Items.AIR && items.size() < ItemGroups.MAX_ITEMS + 5) {
				items.add(item);
				storage.insert(ItemVariant.of(item), 1);
			}
		}
		for (Item item : items) {
			groupAction(player, GroupActionPayload.ADD_ITEM, taken, "", ItemVariant.of(item));
		}
		helper.assertTrue(groups.find(taken).items().size() == ItemGroups.MAX_ITEMS, "at most " + ItemGroups.MAX_ITEMS + " items per group");
		helper.assertTrue(menu.networkId().equals(network), "still the same network");
		finish(helper, player);
	}

	@GameTest
	public void groupActionsNeedAccessAndTheRightNetwork(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity first = placeTerminal(helper, TERMINAL);
		TerminalBlockEntity second = placeTerminal(helper, SECOND_TERMINAL);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemGroups groups = ItemGroups.get(server);
		openTerminal(helper, player, TERMINAL);
		UUID id = createGroup(player, "First network");
		player.closeContainer();

		// a menu on another network cannot reach the group (e.g. a late packet after switching networks)
		openTerminal(helper, player, SECOND_TERMINAL);
		groupAction(player, GroupActionPayload.RENAME, id, "Wrong", null);
		groupAction(player, GroupActionPayload.DELETE, id, "", null);
		helper.assertTrue(groups.find(id) != null && groups.find(id).name().equals("First network"), "groups of other networks are not touched");
		player.closeContainer();

		// without access to the network nothing works, even for the owner of the group
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		Networks.Network privateNetwork = Networks.get(server).create(owner, "Private");
		first.setNetworkId(privateNetwork.id());
		openTerminal(helper, player, TERMINAL);
		UUID refused = UUID.randomUUID();
		groupAction(player, GroupActionPayload.CREATE, refused, "Sneaky", null);
		helper.assertTrue(groups.find(refused) == null, "no groups in networks without access");
		player.closeContainer();

		// spectators cannot change anything
		ServerPlayer spectator = mockPlayer(helper, GameType.SPECTATOR);
		openTerminal(helper, spectator, SECOND_TERMINAL);
		UUID spectatorGroup = UUID.randomUUID();
		groupAction(spectator, GroupActionPayload.CREATE, spectatorGroup, "Ghost", null);
		helper.assertTrue(groups.find(spectatorGroup) == null, "spectators cannot create groups");
		helper.assertTrue(second.getNetworkId() != null, "second terminal still connected");
		finish(helper, player, owner, spectator);
	}

	@GameTest
	public void deletingANetworkDeletesItsGroups(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeBareTerminal(helper, TERMINAL);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		openTerminal(helper, owner, TERMINAL);
		networkAction(owner, NetworkActionPayload.CREATE, null, "Temporary");
		UUID network = terminal.getNetworkId();
		createGroup(owner, "A");
		createGroup(owner, "B");
		ItemGroups groups = ItemGroups.get(server);
		helper.assertTrue(groups.all(network).size() == 2, "two groups");
		networkAction(owner, NetworkActionPayload.DELETE, network, "");
		helper.assertTrue(Networks.get(server).find(network) == null && groups.all(network).isEmpty(), "the groups went with the network");
		finish(helper, owner);
	}

	@GameTest
	public void groupVersionsArePerNetwork(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		UUID first = placeTerminal(helper, TERMINAL).getNetworkId();
		UUID second = placeTerminal(helper, SECOND_TERMINAL).getNetworkId();
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemGroups groups = ItemGroups.get(server);
		int firstBefore = groups.version(first);
		int secondBefore = groups.version(second);
		openTerminal(helper, player, TERMINAL);
		UUID id = createGroup(player, "Only here");
		helper.assertTrue(groups.version(first) != firstBefore, "the network's version changes");
		helper.assertTrue(groups.version(second) == secondBefore, "other networks do not send their groups again");
		int version = groups.version(first);
		groupAction(player, GroupActionPayload.SHOW, id, "", null);
		helper.assertTrue(groups.version(first) == version, "no change, no new version (nothing is sent)");

		int networks = Networks.get(server).version();
		Networks.get(server).rename(Networks.get(server).find(first), "Renamed");
		helper.assertTrue(Networks.get(server).version() != networks, "renaming a network makes open menus send the network list again");
		finish(helper, player);
	}

	@GameTest
	public void groupsSurviveSavingAndSkipUnreadableEntries(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeTerminal(helper, TERMINAL);
		UUID network = terminal.getNetworkId();
		terminal.getStorage().insert(ItemVariant.of(Items.LAPIS_LAZULI), 3);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		openTerminal(helper, player, TERMINAL);
		UUID id = createGroup(player, "Saved");
		groupAction(player, GroupActionPayload.ADD_ITEM, id, "", ItemVariant.of(Items.LAPIS_LAZULI));
		groupAction(player, GroupActionPayload.TOGGLE_SHARED, id, "", null);
		groupAction(player, GroupActionPayload.HIDE, id, "", null);

		var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
		Tag saved = ItemGroups.CODEC.encodeStart(ops, ItemGroups.get(server)).getOrThrow();
		ListTag list = (ListTag) saved.copy();
		// an item of a removed mod inside the group, and a group that cannot be read at all
		CompoundTag group = (CompoundTag) list.stream()
			.filter(tag -> ((CompoundTag) tag).getString("name").orElse("").equals("Saved")).findFirst().orElseThrow();
		CompoundTag ghost = new CompoundTag();
		ghost.putString("item", "removedmod:ghost");
		((ListTag) group.get("items")).add(ghost);
		CompoundTag broken = new CompoundTag();
		broken.putString("name", "no id, no owner");
		list.add(broken);

		ItemGroups loaded = ItemGroups.CODEC.parse(ops, list).getOrThrow();
		ItemGroups.Group copy = loaded.find(id);
		helper.assertTrue(copy != null && copy.name().equals("Saved") && copy.isShared() && copy.isHiddenFor(player)
			&& copy.network().equals(network) && copy.items().equals(List.of(ItemVariant.of(Items.LAPIS_LAZULI))),
			"everything readable survives; unreadable items and groups are skipped");
		finish(helper, player);
	}

	@GameTest
	public void groupListIsSplitIntoBoundedPackets(GameTestHelper helper) {
		helper.assertTrue(GroupListPayload.split(1, List.of()).equals(List.of(new GroupListPayload(1, true, true, List.of()))),
			"an empty list is one packet that clears the client's list");

		List<GroupListPayload.Entry> groups = new ArrayList<>();
		Random random = new Random(7);
		for (int i = 0; i < 40; i++) {
			int size = i == 3 ? ItemGroups.MAX_ITEMS : random.nextInt(ItemGroups.MAX_ITEMS + 1);
			List<ItemVariant> items = new ArrayList<>();
			for (int j = 0; j < size; j++) {
				items.add(ItemVariant.of(Items.PAPER.getDefaultInstance().copyWithCount(1)));
			}
			groups.add(new GroupListPayload.Entry(UUID.randomUUID(), "G" + i, "owner", false, true, false, items));
		}
		List<GroupListPayload> packets = GroupListPayload.split(5, groups);
		List<GroupListPayload.Entry> joined = new ArrayList<>();
		for (int i = 0; i < packets.size(); i++) {
			GroupListPayload packet = packets.get(i);
			helper.assertTrue(packet.first() == (i == 0) && packet.last() == (i == packets.size() - 1), "first/last flags at " + i);
			int items = packet.groups().stream().mapToInt(group -> group.items().size()).sum();
			helper.assertTrue(items <= GroupListPayload.MAX_ITEMS_PER_PACKET || packet.groups().size() == 1, "packet " + i + " is bounded: " + items);
			joined.addAll(packet.groups());
		}
		helper.assertTrue(packets.size() > 1 && joined.equals(groups), "nothing lost or reordered over " + packets.size() + " packets");

		// a group full of huge written books still fits into one packet
		List<ItemVariant> books = new ArrayList<>();
		for (int i = 0; i < ItemGroups.MAX_ITEMS; i++) {
			books.add(ItemVariant.of(TestItems.bigBook(i)));
		}
		GroupListPayload big = GroupListPayload.split(1, List.of(new GroupListPayload.Entry(UUID.randomUUID(), "Books", "o", true, true, false, books))).getFirst();
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
		GroupListPayload.CODEC.encode(buf, big);
		helper.assertTrue(buf.readableBytes() < io.github.timske6.simpleme.network.StorageSyncPayload.MAX_SIZE, "fits the packet limit: " + buf.readableBytes());
		helper.assertTrue(GroupListPayload.CODEC.decode(buf).groups().getFirst().items().equals(books), "round trip");
		helper.succeed();
	}

	// ------------------------------------------------------------------------------------------
	// Sorting per terminal
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void sortingIsKeptPerTerminal(GameTestHelper helper) {
		TerminalBlockEntity first = placeTerminal(helper, TERMINAL);
		TerminalBlockEntity second = placeTerminal(helper, SECOND_TERMINAL);
		ServerPlayer a = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer b = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menuA = openTerminal(helper, a, TERMINAL);
		TerminalMenu menuB = openTerminal(helper, b, TERMINAL);
		int[] sentToB = watchSortSlot(menuB);
		helper.assertTrue(sentToB[0] == 0, "starts with the default sorting");
		helper.assertTrue(menuA.clickMenuButton(a, TerminalMenu.BUTTON_SORT + 2), "sort button accepted");
		helper.assertTrue(first.getSortMode() == 2 && second.getSortMode() == 0, "only this terminal changes");
		menuB.broadcastChanges();
		helper.assertTrue(sentToB[0] == 2, "another player at the same terminal gets the new sorting, got " + sentToB[0]);
		helper.assertFalse(menuA.clickMenuButton(a, TerminalMenu.BUTTON_SORT + TerminalMenu.SORT_MODES), "unknown sortings are refused");
		helper.assertFalse(menuA.clickMenuButton(a, TerminalMenu.BUTTON_SORT - 1), "unknown buttons are refused");
		helper.assertTrue(first.getSortMode() == 2, "still the same");

		var registries = helper.getLevel().registryAccess();
		TerminalBlockEntity loaded = (TerminalBlockEntity) BlockEntity.loadStatic(first.getBlockPos(), first.getBlockState(), first.saveWithFullMetadata(registries), registries);
		helper.assertTrue(loaded != null && loaded.getSortMode() == 2, "saved with the terminal");
		finish(helper, a, b);
	}

	@GameTest
	public void wirelessTerminalKeepsItsSorting(GameTestHelper helper) {
		placeTerminal(helper, TERMINAL);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack remote = linkedRemote(helper, TERMINAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, remote);
		WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
		TerminalMenu menu = (TerminalMenu) player.containerMenu;
		menu.clickMenuButton(player, TerminalMenu.BUTTON_SORT + 1);
		helper.assertTrue(remote.get(ModRegistry.SORT_MODE) == 1, "stored on the item");
		menu.clickMenuButton(player, TerminalMenu.BUTTON_SORT);
		helper.assertTrue(!remote.has(ModRegistry.SORT_MODE), "the default leaves no data on the item (it stays stackable with fresh ones)");
		finish(helper, player);
	}

	// ------------------------------------------------------------------------------------------
	// Wireless terminal and modified clients
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void wirelessMenuStaysBoundToItsSlot(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalStorage first = placeTerminal(helper, TERMINAL).getStorage();
		TerminalStorage second = placeTerminal(helper, SECOND_TERMINAL).getStorage();
		first.insert(ItemVariant.of(Items.OAK_LOG), 10);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack remoteA = linkedRemote(helper, TERMINAL);
		ItemStack remoteB = linkedRemote(helper, SECOND_TERMINAL);
		player.getInventory().setItem(0, remoteA);
		player.getInventory().setItem(1, remoteB);
		player.getInventory().setSelectedSlot(0);
		WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
		TerminalMenu menu = (TerminalMenu) player.containerMenu;
		placeRecipe(helper, player, menu, "oak_planks", true);
		helper.assertTrue(first.getAmount(ItemVariant.of(Items.OAK_LOG)) == 0, "logs in the grid");

		// a modified client selects the other wireless terminal while the menu is open
		player.connection.handleSetCarriedItem(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1));
		helper.assertFalse(menu.stillValid(player), "the menu is no longer valid");
		helper.assertTrue(menu.networkId().equals(remoteA.get(ModRegistry.LINK).id()), "it does not switch to the other network");
		menu.handleStorageClick(player, ItemVariant.of(Items.OAK_LOG), StorageClickPayload.TAKE_STACK);
		player.closeContainer();
		helper.assertTrue(first.getAmount(ItemVariant.of(Items.OAK_LOG)) == 10, "the grid went back where it came from");
		helper.assertTrue(second.isEmpty() && player.getInventory().countItem(Items.OAK_LOG) == 0, "nothing leaked");
		helper.assertTrue(Networks.get(server).find(remoteB.get(ModRegistry.LINK).id()) != null, "other network untouched");
		finish(helper, player);
	}

	@GameTest
	public void wirelessTerminalInTheOffhand(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper, TERMINAL).getStorage();
		storage.insert(ItemVariant.of(Items.EMERALD), 20);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack remote = linkedRemote(helper, TERMINAL);
		player.setItemInHand(InteractionHand.OFF_HAND, remote);
		WirelessTerminalItem.open(player, InteractionHand.OFF_HAND);
		TerminalMenu menu = (TerminalMenu) player.containerMenu;
		player.getInventory().setSelectedSlot(5);
		helper.assertTrue(menu.stillValid(player), "selecting hotbar slots does not matter for the offhand");
		// F over an inventory slot swaps with the offhand: blocked while it holds the terminal in use
		menu.clicked(TerminalMenu.INV_START, Inventory.SLOT_OFFHAND, ContainerInput.SWAP, player);
		helper.assertTrue(player.getOffhandItem() == remote, "the wireless terminal in use cannot be swapped away");
		menu.handleStorageClick(player, ItemVariant.of(Items.EMERALD), StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().getCount() == 20, "works from the offhand");
		menu.handleStorageClick(player, ItemVariant.of(Items.EMERALD), StorageClickPayload.INSERT_ALL);
		player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
		helper.assertFalse(menu.stillValid(player), "closes without the wireless terminal");
		finish(helper, player);
	}

	// ------------------------------------------------------------------------------------------
	// Access that changes while menus are open
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void accessRevokedWhileTheMenuIsOpen(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeBareTerminal(helper, TERMINAL);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer guest = mockPlayer(helper, GameType.SURVIVAL);
		Networks.Network network = Networks.get(server).create(owner, "Shared for now");
		Networks.get(server).setPublic(network, true);
		terminal.setNetworkId(network.id());
		TerminalStorage storage = terminal.getStorage();
		storage.insert(ItemVariant.of(Items.OAK_LOG), 10);
		storage.insert(ItemVariant.of(Items.DIAMOND), 10);

		TerminalMenu menu = openTerminal(helper, guest, TERMINAL);
		placeRecipe(helper, guest, menu, "oak_planks", true);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 0, "the guest has the logs in the grid");

		Networks.get(server).setPublic(network, false);
		menu.handleStorageClick(guest, ItemVariant.of(Items.DIAMOND), StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().isEmpty() && storage.getAmount(ItemVariant.of(Items.DIAMOND)) == 10, "no more taking");
		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, guest);
		helper.assertTrue(guest.getInventory().countItem(Items.OAK_PLANKS) <= 40, "crafting what is in the grid is fine, but nothing refills");
		guest.closeContainer();
		long logs = guest.getInventory().countItem(Items.OAK_LOG) + storage.getAmount(ItemVariant.of(Items.OAK_LOG))
			+ guest.getInventory().countItem(Items.OAK_PLANKS) / 4;
		helper.assertTrue(logs == 10, "no log appeared or vanished, got " + logs);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.DIAMOND)) == 10, "diamonds untouched");
		finish(helper, owner, guest);
	}

	@GameTest
	public void networkDeletedWhileAnotherPlayerHoldsItsItemsInTheGrid(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeBareTerminal(helper, TERMINAL);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer guest = mockPlayer(helper, GameType.SURVIVAL);
		Networks.Network network = Networks.get(server).create(owner, "Doomed");
		Networks.get(server).setPublic(network, true);
		terminal.setNetworkId(network.id());
		terminal.getStorage().insert(ItemVariant.of(Items.OAK_LOG), 10);
		TerminalMenu guestMenu = openTerminal(helper, guest, TERMINAL);
		placeRecipe(helper, guest, guestMenu, "oak_planks", true);

		// the storage is empty now (everything is in the guest's grid), so the owner may delete it
		TerminalMenu ownerMenu = openTerminal(helper, owner, TERMINAL);
		networkAction(owner, NetworkActionPayload.DELETE, network.id(), "");
		helper.assertTrue(Networks.get(server).find(network.id()) == null, "deleted");
		helper.assertTrue(ownerMenu.networkId() == null || Networks.get(server).find(ownerMenu.networkId()) == null, "owner's menu has no network");
		guest.closeContainer();
		helper.assertTrue(guest.getInventory().countItem(Items.OAK_LOG) == 10, "the guest keeps the logs from the grid instead of losing them");
		helper.assertTrue(Networks.storage(server, network.id()) == null, "the deleted network does not come back");
		finish(helper, owner, guest);
	}

	@GameTest
	public void twoPlayersCraftFromScarceItems(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper, TERMINAL).getStorage();
		storage.insert(ItemVariant.of(Items.OAK_LOG), 3);
		ServerPlayer a = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer b = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menuA = openTerminal(helper, a, TERMINAL);
		TerminalMenu menuB = openTerminal(helper, b, TERMINAL);
		placeRecipe(helper, a, menuA, "oak_planks", true);
		placeRecipe(helper, b, menuB, "oak_planks", true);
		helper.assertTrue(gridCount(menuA, Items.OAK_LOG) == 3 && gridCount(menuB, Items.OAK_LOG) == 0, "the first one gets the logs, the second nothing");
		menuA.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, a);
		menuB.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, b);
		helper.assertTrue(a.getInventory().countItem(Items.OAK_PLANKS) == 12 && b.getInventory().countItem(Items.OAK_PLANKS) == 0,
			"3 logs make exactly 12 planks, got " + a.getInventory().countItem(Items.OAK_PLANKS) + " + " + b.getInventory().countItem(Items.OAK_PLANKS));
		helper.assertTrue(storage.isEmpty(), "no log left anywhere");
		finish(helper, a, b);
	}

	// ------------------------------------------------------------------------------------------
	// Random run: items are conserved no matter what three players do
	// ------------------------------------------------------------------------------------------

	@GameTest(maxTicks = 1200)
	public void randomActionsOfThreePlayersConserveItems(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity first = placeTerminal(helper, TERMINAL);
		TerminalBlockEntity second = placeTerminal(helper, SECOND_TERMINAL);
		UUID firstNetwork = first.getNetworkId();
		UUID secondNetwork = second.getNetworkId();
		ItemStack named = new ItemStack(Items.DIAMOND);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));
		List<ItemVariant> kinds = List.of(ItemVariant.of(Items.OAK_LOG), ItemVariant.of(Items.DIAMOND), ItemVariant.of(named),
			ItemVariant.of(Items.DIRT), ItemVariant.of(Items.STICK));
		first.getStorage().insert(kinds.get(0), 150);
		first.getStorage().insert(kinds.get(1), 130);
		first.getStorage().insert(kinds.get(2), 20);
		second.getStorage().insert(kinds.get(3), 90);
		second.getStorage().insert(kinds.get(4), 70);

		List<ServerPlayer> players = List.of(mockPlayer(helper, GameType.SURVIVAL), mockPlayer(helper, GameType.SURVIVAL), mockPlayer(helper, GameType.SURVIVAL));
		ItemStack remote = linkedRemote(helper, TERMINAL);
		players.get(2).getInventory().setItem(0, remote);
		players.get(2).getInventory().setSelectedSlot(0);
		long[] expected = totals(helper, players, kinds, firstNetwork, secondNetwork);

		Random random = new Random(20261006L);
		List<List<ItemVariant>> planks = List.of(List.of(kinds.get(0)));
		for (int step = 0; step < 3000; step++) {
			ServerPlayer player = players.get(random.nextInt(players.size()));
			if (!(player.containerMenu instanceof TerminalMenu menu) || random.nextInt(40) == 0) {
				player.closeContainer();
				if (player == players.get(2) && random.nextBoolean()) {
					WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
				} else {
					openTerminal(helper, player, random.nextBoolean() ? TERMINAL : SECOND_TERMINAL);
				}
				continue;
			}
			ItemVariant kind = kinds.get(random.nextInt(kinds.size()));
			int op = random.nextInt(14);
			String what = "step " + step + " op " + op + " " + kind;
			switch (op) {
				case 0 -> menu.handleStorageClick(player, kind, StorageClickPayload.TAKE_STACK);
				case 1 -> menu.handleStorageClick(player, kind, StorageClickPayload.TAKE_HALF);
				case 2 -> menu.handleStorageClick(player, kind, StorageClickPayload.TAKE_ONE);
				case 3 -> menu.handleStorageClick(player, kind, StorageClickPayload.QUICK_MOVE);
				case 4 -> menu.handleStorageClick(player, kind, StorageClickPayload.INSERT_ALL);
				case 5 -> menu.handleStorageClick(player, kind, StorageClickPayload.INSERT_ONE);
				case 6 -> menu.clicked(TerminalMenu.INV_START + random.nextInt(36), random.nextInt(2), ContainerInput.PICKUP, player);
				case 7 -> menu.clicked(TerminalMenu.INV_START + random.nextInt(36), 0, ContainerInput.QUICK_MOVE, player);
				case 8 -> menu.clicked(TerminalMenu.GRID_START + random.nextInt(9), random.nextInt(2), ContainerInput.PICKUP, player);
				case 9 -> menu.clicked(TerminalMenu.INV_START + random.nextInt(36), random.nextInt(9), ContainerInput.SWAP, player);
				case 10 -> placeRecipe(helper, player, menu, "oak_planks", random.nextBoolean());
				case 11 -> menu.handleRecipeTransfer(player, planks, random.nextBoolean());
				case 12 -> menu.clickMenuButton(player, TerminalMenu.BUTTON_CLEAR_GRID);
				default -> networkAction(player, NetworkActionPayload.CONNECT, random.nextBoolean() ? firstNetwork : secondNetwork, "");
			}
			long[] now = totals(helper, players, kinds, firstNetwork, secondNetwork);
			for (int i = 0; i < kinds.size(); i++) {
				if (now[i] != expected[i]) {
					throw helper.assertionException(what + ": " + kinds.get(i) + " was " + expected[i] + ", now " + now[i]);
				}
			}
		}
		for (ServerPlayer player : players) {
			player.closeContainer();
		}
		long[] end = totals(helper, players, kinds, firstNetwork, secondNetwork);
		for (int i = 0; i < kinds.size(); i++) {
			helper.assertTrue(end[i] == expected[i], "after closing all menus: " + kinds.get(i) + " " + expected[i] + " -> " + end[i]);
		}
		helper.assertTrue(Networks.storage(server, firstNetwork) != null, "networks still there");
		finish(helper, players.toArray(ServerPlayer[]::new));
	}

	/** Items of each kind in both networks, all inventories, cursors, crafting grids and on the ground. */
	private static long[] totals(GameTestHelper helper, List<ServerPlayer> players, List<ItemVariant> kinds, UUID... networks) {
		var server = helper.getLevel().getServer();
		long[] totals = new long[kinds.size()];
		for (int i = 0; i < kinds.size(); i++) {
			ItemVariant kind = kinds.get(i);
			for (UUID network : networks) {
				totals[i] += Networks.storage(server, network).getAmount(kind);
			}
			for (ServerPlayer player : players) {
				Inventory inventory = player.getInventory();
				for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
					totals[i] += count(inventory.getItem(slot), kind);
				}
				totals[i] += count(player.containerMenu.getCarried(), kind);
				if (player.containerMenu instanceof TerminalMenu menu) {
					for (int slot = TerminalMenu.GRID_START; slot < TerminalMenu.GRID_END; slot++) {
						totals[i] += count(menu.getSlot(slot).getItem(), kind);
					}
				}
			}
			for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(TERMINAL)).inflate(32))) {
				totals[i] += count(entity.getItem(), kind);
			}
		}
		return totals;
	}

	private static long count(ItemStack stack, ItemVariant kind) {
		return !stack.isEmpty() && kind.matches(stack) ? stack.getCount() : 0;
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	/** A terminal connected to a new public network (named "Test"). */
	private static TerminalBlockEntity placeTerminal(GameTestHelper helper, BlockPos pos) {
		TerminalBlockEntity terminal = placeBareTerminal(helper, pos);
		terminal.setNetworkId(Networks.get(helper.getLevel().getServer()).create(null, "Test").id());
		return terminal;
	}

	private static TerminalBlockEntity placeBareTerminal(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, ModRegistry.TERMINAL_BLOCK);
		return helper.getBlockEntity(pos, TerminalBlockEntity.class);
	}

	private static ItemStack linkedRemote(GameTestHelper helper, BlockPos terminal) {
		ItemStack remote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
		UUID id = helper.getBlockEntity(terminal, TerminalBlockEntity.class).getNetworkId();
		remote.set(ModRegistry.LINK, StorageLink.of(Networks.get(helper.getLevel().getServer()).find(id)));
		return remote;
	}

	private static ServerPlayer mockPlayer(GameTestHelper helper, GameType gameType) {
		MinecraftServer server = helper.getLevel().getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "mp-player"), false);
		ServerPlayer player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
			@Override
			public GameType gameMode() {
				return gameType;
			}
		};
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		gameType.updatePlayerAbilities(player.getAbilities());
		player.setGameMode(gameType);
		BlockPos abs = helper.absolutePos(TERMINAL);
		player.setPos(abs.getX() + 0.5, abs.getY() + 1, abs.getZ() + 1.5);
		return player;
	}

	private static TerminalMenu openTerminal(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
		player.openMenu(helper.getBlockEntity(pos, TerminalBlockEntity.class));
		helper.assertTrue(player.containerMenu instanceof TerminalMenu, "terminal menu opened");
		return (TerminalMenu) player.containerMenu;
	}

	/** Records what a menu sends to its client for its (only) data slot, the sorting. */
	private static int[] watchSortSlot(TerminalMenu menu) {
		int[] value = {-1};
		menu.setSynchronizer(new ContainerSynchronizer() {
			@Override
			public void sendInitialData(AbstractContainerMenu container, List<ItemStack> slotItems, ItemStack carried, int[] dataSlots) {
				value[0] = dataSlots[0];
			}

			@Override
			public void sendSlotChange(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
			}

			@Override
			public void sendCarriedChange(AbstractContainerMenu container, ItemStack itemStack) {
			}

			@Override
			public void sendDataChange(AbstractContainerMenu container, int id, int changed) {
				value[0] = changed;
			}

			@Override
			public RemoteSlot createSlot() {
				return RemoteSlot.PLACEHOLDER;
			}
		});
		return value;
	}

	/** What the group panel sends: creates a group with a new id and returns it. */
	private static UUID createGroup(ServerPlayer player, String name) {
		UUID id = UUID.randomUUID();
		groupAction(player, GroupActionPayload.CREATE, id, name, null);
		return id;
	}

	private static void groupAction(ServerPlayer player, int action, UUID group, String text, @Nullable ItemVariant item) {
		((TerminalMenu) player.containerMenu).handleGroupAction(player, new GroupActionPayload(player.containerMenu.containerId, action, group, text, Optional.ofNullable(item)));
	}

	private static void networkAction(ServerPlayer player, int action, @Nullable UUID network, String text) {
		NetworkActions.handle(player, new NetworkActionPayload(player.containerMenu.containerId, action, Optional.ofNullable(network), text));
	}

	private static void placeRecipe(GameTestHelper helper, ServerPlayer player, TerminalMenu menu, String name, boolean max) {
		ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(name));
		RecipeHolder<?> recipe = helper.getLevel().getServer().getRecipeManager().byKey(key).orElseThrow();
		menu.handlePlacement(max, false, recipe, (ServerLevel) player.level(), player.getInventory());
	}

	private static int gridCount(TerminalMenu menu, Item item) {
		int count = 0;
		for (int slot = TerminalMenu.GRID_START; slot < TerminalMenu.GRID_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static void finish(GameTestHelper helper, ServerPlayer... players) {
		for (ServerPlayer player : players) {
			player.closeContainer();
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		helper.succeed();
	}
}
