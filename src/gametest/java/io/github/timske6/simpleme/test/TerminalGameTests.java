package io.github.timske6.simpleme.test;

import com.mojang.authlib.GameProfile;
import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.block.TransferChestBlock;
import io.github.timske6.simpleme.block.TransferChestBlockEntity;
import io.github.timske6.simpleme.item.WirelessTerminalItem;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.menu.TransferChestMenu;
import io.github.timske6.simpleme.network.NetworkActionPayload;
import io.github.timske6.simpleme.network.NetworkActions;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageData;
import io.github.timske6.simpleme.storage.StorageLink;
import io.github.timske6.simpleme.storage.TerminalContents;
import io.github.timske6.simpleme.storage.TerminalStorage;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Server-side logic tests: ./gradlew runGameTest */
public class TerminalGameTests {
	private static final BlockPos TERMINAL = new BlockPos(2, 2, 2);

	// ------------------------------------------------------------------------------------------
	// Storage
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void storageInsertExtract(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant stone = ItemVariant.of(Items.STONE);

		helper.assertTrue(storage.insert(stone, 100) == 100, "insert 100");
		helper.assertTrue(storage.getAmount(stone) == 100, "amount 100");
		helper.assertTrue(storage.extract(stone, 30) == 30, "extract 30");
		helper.assertTrue(storage.extract(stone, 500) == 70, "extract more than stored returns only what is there");
		helper.assertTrue(storage.getAmount(stone) == 0 && storage.isEmpty(), "empty entries are removed");
		helper.assertTrue(storage.insert(ItemVariant.blank(), 10) == 0, "blank variant is rejected");
		helper.assertTrue(storage.insert(stone, 0) == 0 && storage.insert(stone, -5) == 0, "non-positive amounts are ignored");
		helper.assertTrue(storage.extract(stone, -5) == 0, "negative extract is ignored");

		helper.assertTrue(storage.insert(stone, Long.MAX_VALUE) == TerminalStorage.MAX_PER_TYPE, "insert is capped per type");
		helper.assertTrue(TerminalStorage.MAX_PER_TYPE == Long.MAX_VALUE, "up to Long.MAX_VALUE items per type");
		helper.assertTrue(storage.insert(stone, 1) == 0, "no overflow past the cap");
		helper.assertTrue(storage.getAmount(stone) == Long.MAX_VALUE, "count stays at the maximum");
		try (Transaction tx = Transaction.openOuter()) {
			helper.assertTrue(storage.insertionStorage().insert(stone, 64, tx) == 0, "hoppers cannot overflow a full type either");
			tx.commit();
		}
		storage.insert(ItemVariant.of(Items.DIRT), Long.MAX_VALUE);
		helper.assertTrue(storage.totalCount() == Long.MAX_VALUE, "total count saturates instead of overflowing");
		helper.assertTrue(storage.extract(stone, 10) == 10 && storage.getAmount(stone) == Long.MAX_VALUE - 10, "extract from a full type");
		try (Transaction tx = Transaction.openOuter()) {
			helper.assertTrue(storage.insertionStorage().insert(stone, 64, tx) == 10, "pending inserts respect the cap");
			helper.assertTrue(storage.insertionStorage().insert(stone, 64, tx) == 0, "...including earlier inserts of the same transaction");
			tx.commit();
		}
		helper.assertTrue(storage.getAmount(stone) == Long.MAX_VALUE, "full again");
		storage.extract(stone, Long.MAX_VALUE);
		storage.extract(ItemVariant.of(Items.DIRT), Long.MAX_VALUE);

		// items with different data are different entries
		ItemStack named = new ItemStack(Items.DIAMOND);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny"));
		storage.insert(ItemVariant.of(Items.DIAMOND), 5);
		storage.insert(ItemVariant.of(named), 2);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.DIAMOND)) == 5, "plain diamonds");
		helper.assertTrue(storage.getAmount(ItemVariant.of(named)) == 2, "named diamonds kept separate");
		helper.succeed();
	}

	@GameTest
	public void oldFilledTerminalItemsCannotBeStored(GameTestHelper helper) {
		// terminal items of version 1.0 still carry their storage: storing one would hide its items
		ItemStack old = oldTerminalItem(helper, Items.DIRT, 10);
		TerminalStorage storage = placeTerminal(helper).getStorage();
		helper.assertTrue(storage.insert(ItemVariant.of(old), 1) == 0, "an old filled terminal must not be storable");
		helper.assertTrue(storage.insert(ItemVariant.of(ModRegistry.TERMINAL_ITEM), 1) == 1, "a plain terminal is fine");
		helper.succeed();
	}

	@GameTest
	public void brokenTerminalDropsPlainItemNetworkKeepsItems(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		UUID network = terminal.getNetworkId();
		terminal.getStorage().insert(ItemVariant.of(Items.DIRT), 10);
		terminal.getStorage().insert(ItemVariant.of(Items.STONE), 5);
		ItemStack dropped = breakAndCollect(helper, TERMINAL);
		helper.assertTrue(ItemStack.isSameItemSameComponents(dropped, new ItemStack(ModRegistry.TERMINAL_ITEM)), "a broken terminal drops a plain, stackable terminal: " + dropped);
		TerminalStorage storage = Networks.storage(helper.getLevel().getServer(), network);
		helper.assertTrue(storage != null && storage.getAmount(ItemVariant.of(Items.DIRT)) == 10 && storage.getAmount(ItemVariant.of(Items.STONE)) == 5,
			"the items stay in the network");

		TerminalBlockEntity placed = placeFromItem(helper, new BlockPos(4, 2, 4), dropped);
		helper.assertTrue(placed.getNetworkId() == null, "a new terminal starts without network");
		placed.setNetworkId(network);
		helper.assertTrue(placed.getStorage() == storage, "connected again: same items");
		helper.succeed();
	}

	@GameTest
	public void terminalsOfOneNetworkShareItems(GameTestHelper helper) {
		TerminalBlockEntity first = placeTerminal(helper);
		TerminalBlockEntity second = placeBareTerminal(helper, new BlockPos(5, 2, 2));
		second.setNetworkId(first.getNetworkId());
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		first.getStorage().insert(diamond, 64);
		helper.assertTrue(second.getStorage().getAmount(diamond) == 64, "the second terminal shows the same items");
		helper.assertTrue(second.getStorage().extract(diamond, 64) == 64 && first.getStorage().getAmount(diamond) == 0, "and nothing is duplicated");
		helper.succeed();
	}

	@GameTest
	public void oldDataBecomesNetworks(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		ItemVariant gold = ItemVariant.of(Items.GOLD_INGOT);

		// terminal item of version 1.0 (storage id + summary on the item)
		ItemStack oldItem = oldTerminalItem(helper, Items.GOLD_INGOT, 42);
		UUID oldId = oldItem.get(ModRegistry.STORAGE).id();
		TerminalBlockEntity placed = placeFromItem(helper, TERMINAL, oldItem);
		helper.assertTrue(oldId.equals(placed.getNetworkId()), "the old storage is the terminal's network");
		Networks.Network network = Networks.get(server).find(oldId);
		helper.assertTrue(network != null && network.owner() == null && network.isPublic(), "taken over as a public network without owner");
		helper.assertTrue(placed.getStorage().getAmount(gold) == 42, "with all items");

		// placed terminal of version 1.0 (storage id in the block), broken before anybody opened it again
		UUID blockId = UUID.randomUUID();
		StorageData.get(server, blockId).storage().insert(gold, 7);
		BlockPos pos = new BlockPos(5, 2, 5);
		helper.setBlock(pos, ModRegistry.TERMINAL_BLOCK);
		var registries = helper.getLevel().registryAccess();
		var tag = new net.minecraft.nbt.CompoundTag();
		tag.putString("id", "simpleme:terminal");
		tag.store("Storage", net.minecraft.core.UUIDUtil.CODEC, blockId);
		helper.getBlockEntity(pos, TerminalBlockEntity.class).loadWithComponents(
			net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, registries, tag));
		helper.getLevel().destroyBlock(helper.absolutePos(pos), false);
		helper.assertTrue(Networks.get(server).find(blockId) != null, "breaking an old terminal still keeps its items reachable as a network");

		// pre-release formats (items inside the item / block)
		var legacy = new io.github.timske6.simpleme.storage.StoredItems(List.of(new io.github.timske6.simpleme.storage.StoredItems.Entry(gold, 42)));
		ItemStack preRelease = new ItemStack(ModRegistry.TERMINAL_ITEM);
		preRelease.set(ModRegistry.LEGACY_STORED_ITEMS, legacy);
		TerminalBlockEntity fromPreRelease = placeFromItem(helper, new BlockPos(1, 2, 5), preRelease);
		helper.assertTrue(fromPreRelease.getStorage() != null && fromPreRelease.getStorage().getAmount(gold) == 42, "pre-release item contents migrated");

		var itemsTag = new net.minecraft.nbt.CompoundTag();
		itemsTag.putString("id", "simpleme:terminal");
		itemsTag.put("Items", io.github.timske6.simpleme.storage.StoredItems.Entry.CODEC.listOf()
			.encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), legacy.entries()).getOrThrow());
		BlockPos itemsPos = new BlockPos(3, 2, 5);
		helper.setBlock(itemsPos, ModRegistry.TERMINAL_BLOCK);
		var loaded = (TerminalBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
			helper.absolutePos(itemsPos), helper.getLevel().getBlockState(helper.absolutePos(itemsPos)), itemsTag, registries);
		loaded.setLevel(helper.getLevel());
		helper.assertTrue(loaded.getStorage() != null && loaded.getStorage().getAmount(gold) == 42, "pre-release block entity contents migrated");
		helper.succeed();
	}

	@GameTest
	public void transferApiTransactions(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), helper.absolutePos(TERMINAL), Direction.UP);
		helper.assertTrue(storage != null, "terminal exposes an item storage");
		ItemVariant coal = ItemVariant.of(Items.COAL);

		try (Transaction tx = Transaction.openOuter()) {
			helper.assertTrue(storage.insert(coal, 10, tx) == 10, "insert simulated");
			tx.abort();
		}
		helper.assertTrue(terminal.getStorage().getAmount(coal) == 0, "aborted insert must not change anything");

		try (Transaction outer = Transaction.openOuter()) {
			try (Transaction inner = outer.openNested()) {
				storage.insert(coal, 5, inner);
				inner.commit();
			}
			try (Transaction inner = outer.openNested()) {
				storage.insert(coal, 7, inner);
				inner.abort();
			}
			outer.commit();
		}
		helper.assertTrue(terminal.getStorage().getAmount(coal) == 5, "only the committed nested insert counts, got " + terminal.getStorage().getAmount(coal));

		try (Transaction tx = Transaction.openOuter()) {
			helper.assertTrue(storage.extract(coal, 5, tx) == 0, "extraction through pipes/hoppers is not allowed");
			tx.commit();
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 200)
	public void hopperInsertsButCannotExtract(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.STONE), 64);

		helper.setBlock(TERMINAL.above(), Blocks.HOPPER.defaultBlockState());
		helper.setBlock(TERMINAL.below(), Blocks.HOPPER.defaultBlockState());
		var above = helper.getBlockEntity(TERMINAL.above(), net.minecraft.world.level.block.entity.HopperBlockEntity.class);
		above.setItem(0, new ItemStack(Items.COAL, 5));

		helper.runAfterDelay(60, () -> {
			var below = helper.getBlockEntity(TERMINAL.below(), net.minecraft.world.level.block.entity.HopperBlockEntity.class);
			helper.assertTrue(terminal.getStorage().getAmount(ItemVariant.of(Items.COAL)) == 5, "hopper above should insert 5 coal");
			helper.assertTrue(below.isEmpty(), "hopper below must not pull items out");
			helper.assertTrue(terminal.getStorage().getAmount(ItemVariant.of(Items.STONE)) == 64, "stone untouched");
			helper.succeed();
		});
	}

	// ------------------------------------------------------------------------------------------
	// Breaking, placing, saving
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void networkKeepsSpecialItemsAndHugeCounts(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		UUID network = terminal.getNetworkId();
		List<ItemStack> special = specialItems(helper.getLevel());
		terminal.getStorage().insert(ItemVariant.of(Items.COBBLESTONE), 1_000_000);
		terminal.getStorage().insert(ItemVariant.of(Items.SAND), Long.MAX_VALUE);
		for (ItemStack stack : special) {
			terminal.getStorage().insert(ItemVariant.of(stack), 3);
		}
		int types = terminal.getStorage().typeCount();

		breakAndCollect(helper, TERMINAL);
		TerminalBlockEntity placed = placeBareTerminal(helper, new BlockPos(4, 2, 4));
		placed.setNetworkId(network);
		helper.assertTrue(placed.getStorage().typeCount() == types, "all item types still there");
		helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(Items.COBBLESTONE)) == 1_000_000, "counts kept");
		helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(Items.SAND)) == Long.MAX_VALUE, "Long.MAX_VALUE count kept");
		for (ItemStack stack : special) {
			helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(stack)) == 3, "special item kept: " + stack);
		}
		helper.succeed();
	}

	@GameTest
	public void explosionKeepsEverything(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		UUID network = terminal.getNetworkId();
		terminal.getStorage().insert(ItemVariant.of(Items.GOLD_INGOT), 500);
		BlockPos abs = helper.absolutePos(TERMINAL);
		helper.getLevel().explode(null, abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, 4.0F, Level.ExplosionInteraction.TNT);
		helper.assertBlockNotPresent(ModRegistry.TERMINAL_BLOCK, TERMINAL);
		helper.assertTrue(!collectTerminalItem(helper, TERMINAL).isEmpty(), "terminal must drop when exploded");
		helper.assertTrue(Networks.storage(helper.getLevel().getServer(), network).getAmount(ItemVariant.of(Items.GOLD_INGOT)) == 500,
			"the network does not care about the explosion");
		helper.succeed();
	}

	@GameTest
	public void unreadableItemsDoNotWipeTheStorage(GameTestHelper helper) {
		// e.g. after removing another mod: its items are skipped, everything else loads
		var ops = helper.getLevel().registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
		var list = new net.minecraft.nbt.ListTag();
		list.add(io.github.timske6.simpleme.storage.StoredItems.Entry.CODEC.encodeStart(ops,
			new io.github.timske6.simpleme.storage.StoredItems.Entry(ItemVariant.of(Items.EMERALD), 7)).getOrThrow());
		var ghost = new net.minecraft.nbt.CompoundTag();
		var ghostVariant = new net.minecraft.nbt.CompoundTag();
		ghostVariant.putString("item", "removedmod:ghost_item");
		ghost.put("variant", ghostVariant);
		ghost.putLong("count", 3);
		list.add(ghost);
		var entries = io.github.timske6.simpleme.storage.StorageData.LENIENT_ENTRIES.parse(ops, list).getOrThrow();
		helper.assertTrue(entries.size() == 1 && entries.getFirst().count() == 7, "valid entries survive unreadable ones: " + entries);
		helper.succeed();
	}

	@GameTest
	public void emptyStoragesAreNotWrittenButEmptiedOnesAre(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		var data = io.github.timske6.simpleme.storage.StorageData.get(server, UUID.randomUUID());
		helper.assertFalse(data.isDirty(), "a never-used empty storage needs no file");
		data.storage().insert(ItemVariant.of(Items.DIRT), 5);
		helper.assertTrue(data.isDirty(), "a storage with items must be saved");
		server.getDataStorage().saveAndJoin();
		helper.assertFalse(data.isDirty(), "saved");
		data.storage().extract(ItemVariant.of(Items.DIRT), 5);
		helper.assertTrue(data.isDirty(), "an emptied storage must be saved too, otherwise the items would come back (dupe)");
		server.getDataStorage().saveAndJoin();
		helper.succeed();
	}

	@GameTest
	public void saveAndLoadBlockEntity(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.IRON_INGOT), 123_456_789L);
		var registries = helper.getLevel().registryAccess();
		var tag = terminal.saveWithFullMetadata(registries);
		var loaded = (TerminalBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
			terminal.getBlockPos(), terminal.getBlockState(), tag, registries);
		helper.assertTrue(loaded != null, "block entity loads");
		loaded.setLevel(helper.getLevel());
		helper.assertTrue(terminal.getNetworkId().equals(loaded.getNetworkId()), "reloaded terminal shows the same network");
		helper.assertTrue(loaded.getStorage() == terminal.getStorage(), "and the same storage");
		helper.assertTrue(loaded.getStorage().getAmount(ItemVariant.of(Items.IRON_INGOT)) == 123_456_789L, "long counts survive");
		helper.succeed();
	}

	// ------------------------------------------------------------------------------------------
	// Menu: clicks on the storage grid
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void gridClicks(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		TerminalStorage storage = terminal.getStorage();
		ItemVariant stone = ItemVariant.of(Items.STONE);
		storage.insert(stone, 100);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);

		menu.handleStorageClick(player, stone, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().getCount() == 64 && storage.getAmount(stone) == 36, "left click takes a stack");
		menu.handleStorageClick(player, stone, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().getCount() == 64 && storage.getAmount(stone) == 36, "cannot take while carrying");

		menu.handleStorageClick(player, stone, StorageClickPayload.INSERT_ONE);
		helper.assertTrue(menu.getCarried().getCount() == 63 && storage.getAmount(stone) == 37, "right click with cursor inserts one");
		menu.handleStorageClick(player, ItemVariant.of(Items.DIRT), StorageClickPayload.INSERT_ALL);
		helper.assertTrue(menu.getCarried().isEmpty() && storage.getAmount(stone) == 100, "left click with cursor inserts all (server decides what)");

		menu.handleStorageClick(player, stone, StorageClickPayload.TAKE_HALF);
		helper.assertTrue(menu.getCarried().getCount() == 32 && storage.getAmount(stone) == 68, "right click takes half a stack");
		menu.handleStorageClick(player, stone, StorageClickPayload.INSERT_ALL);
		menu.handleStorageClick(player, stone, StorageClickPayload.TAKE_ONE);
		helper.assertTrue(menu.getCarried().getCount() == 1, "shift right click takes one");
		menu.handleStorageClick(player, stone, StorageClickPayload.INSERT_ALL);

		menu.handleStorageClick(player, stone, StorageClickPayload.QUICK_MOVE);
		helper.assertTrue(player.getInventory().countItem(Items.STONE) == 64 && storage.getAmount(stone) == 36, "shift click moves a stack to the inventory");

		// items that are not stored cannot be taken, no matter what the client claims
		menu.handleStorageClick(player, ItemVariant.of(Items.DIAMOND_BLOCK), StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().isEmpty(), "cannot take items that are not stored");
		menu.handleStorageClick(player, ItemVariant.of(Items.STONE), StorageClickPayload.CLONE);
		helper.assertTrue(menu.getCarried().isEmpty(), "survival players cannot clone");
		menu.handleStorageClick(player, stone, 99);
		helper.assertTrue(menu.getCarried().isEmpty(), "unknown actions are ignored");

		finish(helper, player);
	}

	@GameTest
	public void creativeCloneDoesNotExtract(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant stone = ItemVariant.of(Items.STONE);
		storage.insert(stone, 5);
		ServerPlayer player = mockPlayer(helper, GameType.CREATIVE);
		TerminalMenu menu = openTerminal(helper, player);
		menu.handleStorageClick(player, stone, StorageClickPayload.CLONE);
		helper.assertTrue(menu.getCarried().getCount() == 64, "creative middle click gives a full stack");
		helper.assertTrue(storage.getAmount(stone) == 5, "cloning does not extract");
		finish(helper, player);
	}

	@GameTest
	public void quickMoveWithFullInventoryLosesNothing(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant stone = ItemVariant.of(Items.STONE);
		storage.insert(stone, 100);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		for (int i = 0; i < 36; i++) {
			player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
		}
		TerminalMenu menu = openTerminal(helper, player);
		menu.handleStorageClick(player, stone, StorageClickPayload.QUICK_MOVE);
		helper.assertTrue(storage.getAmount(stone) == 100, "nothing may vanish when the inventory is full, got " + storage.getAmount(stone));
		helper.assertTrue(player.getInventory().countItem(Items.STONE) == 0, "nothing moved");
		finish(helper, player);
	}

	@GameTest
	public void shiftClickInventoryIntoStorage(GameTestHelper helper) {
		ItemStack filled = oldTerminalItem(helper, Items.DIRT, 1);
		TerminalStorage storage = placeTerminal(helper).getStorage();

		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		player.getInventory().setItem(9, new ItemStack(Items.OAK_LOG, 40));
		player.getInventory().setItem(10, filled);
		TerminalMenu menu = openTerminal(helper, player);
		int logSlot = TerminalMenu.INV_START;          // inventory slot 9
		int terminalSlot = TerminalMenu.INV_START + 1; // inventory slot 10
		menu.clicked(logSlot, 0, ContainerInput.QUICK_MOVE, player);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 40 && player.getInventory().countItem(Items.OAK_LOG) == 0,
			"shift click stores the stack");
		menu.clicked(terminalSlot, 0, ContainerInput.QUICK_MOVE, player);
		helper.assertTrue(player.getInventory().countItem(ModRegistry.TERMINAL_ITEM) == 1, "filled terminal stays with the player");
		helper.assertTrue(!player.getInventory().getItem(10).is(ModRegistry.TERMINAL_ITEM), "...but is moved to the hotbar like vanilla");
		finish(helper, player);
	}

	@GameTest
	public void twoPlayersCannotDuplicate(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		storage.insert(diamond, 100);
		ServerPlayer a = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer b = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menuA = openTerminal(helper, a);
		TerminalMenu menuB = openTerminal(helper, b);
		menuA.handleStorageClick(a, diamond, StorageClickPayload.TAKE_STACK);
		menuB.handleStorageClick(b, diamond, StorageClickPayload.TAKE_STACK);
		menuA.handleStorageClick(a, diamond, StorageClickPayload.QUICK_MOVE);
		long total = menuA.getCarried().getCount() + menuB.getCarried().getCount()
			+ a.getInventory().countItem(Items.DIAMOND) + b.getInventory().countItem(Items.DIAMOND) + storage.getAmount(diamond);
		helper.assertTrue(total == 100, "items must be conserved with two players, total = " + total);
		finish(helper, a, b);
	}

	@GameTest
	public void brokenTerminalRejectsClicks(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		TerminalStorage storage = terminal.getStorage();
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		storage.insert(diamond, 64);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		breakAndCollect(helper, TERMINAL);
		helper.assertFalse(menu.stillValid(player), "menu must become invalid when the block is gone");
		menu.handleStorageClick(player, diamond, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().isEmpty() && storage.getAmount(diamond) == 64, "no taking items through a broken terminal");
		finish(helper, player);
	}

	// ------------------------------------------------------------------------------------------
	// Crafting
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void craftingRefillAndShiftClick(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.OAK_LOG), 64);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);

		placeRecipe(helper, player, menu, "oak_planks", false);
		helper.assertTrue(menu.getSlot(TerminalMenu.GRID_START).getItem().is(Items.OAK_LOG), "log placed in the grid");
		helper.assertTrue(menu.getSlot(TerminalMenu.RESULT_SLOT).getItem().getCount() == 4, "result shows 4 planks");

		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
		helper.assertTrue(menu.getCarried().getCount() == 4, "took 4 planks");
		helper.assertTrue(menu.getSlot(TerminalMenu.GRID_START).getItem().getCount() == 1, "grid refilled");
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 62, "one log crafted, one in the grid");
		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
		helper.assertTrue(menu.getCarried().getCount() == 8, "clicking again keeps crafting");

		menu.handleStorageClick(player, ItemVariant.of(Items.OAK_PLANKS), StorageClickPayload.INSERT_ALL);
		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
		helper.assertTrue(player.getInventory().countItem(Items.OAK_PLANKS) == 64, "shift click crafts one stack, got " + player.getInventory().countItem(Items.OAK_PLANKS));
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 45, "16 more logs used, got " + storage.getAmount(ItemVariant.of(Items.OAK_LOG)));
		finish(helper, player);
	}

	@GameTest
	public void shiftClickCraftsWholeGrid(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.OAK_LOG), 200);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "oak_planks", true);
		helper.assertTrue(menu.getSlot(TerminalMenu.GRID_START).getItem().getCount() == 64, "max placement puts 64 logs");
		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
		int planks = player.getInventory().countItem(Items.OAK_PLANKS);
		helper.assertTrue(planks == 256, "a full grid is crafted completely (64 crafts), got " + planks);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 72, "200 - 64 crafted - 64 in grid, got " + storage.getAmount(ItemVariant.of(Items.OAK_LOG)));
		finish(helper, player);
	}

	@GameTest
	public void shiftClickResultWithFullInventory(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.OAK_LOG), 10);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		for (int i = 0; i < 36; i++) {
			player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
		}
		player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 62));
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "oak_planks", false);
		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.QUICK_MOVE, player);
		// 4 planks crafted, 2 fit into the inventory, 2 go into the storage, then crafting stops
		helper.assertTrue(player.getInventory().countItem(Items.OAK_PLANKS) == 64, "inventory filled up");
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_PLANKS)) == 2, "rest stored instead of dropped, got " + storage.getAmount(ItemVariant.of(Items.OAK_PLANKS)));
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 8, "exactly one craft, got logs " + storage.getAmount(ItemVariant.of(Items.OAK_LOG)));
		finish(helper, player);
	}

	@GameTest
	public void ctrlQOnResultIsBounded(GameTestHelper helper) {
		// Ctrl+Q on the result throws in a loop; with auto refill that must not empty the whole storage.
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.IRON_INGOT), 9 * 150);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "iron_block", false);
		menu.clicked(TerminalMenu.RESULT_SLOT, 1, ContainerInput.THROW, player);
		int thrown = 0;
		for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8))) {
			if (entity.getItem().is(Items.IRON_BLOCK)) {
				thrown += entity.getItem().getCount();
			}
		}
		helper.assertTrue(thrown >= 1 && thrown <= 64, "ctrl+Q should throw at most one stack, threw " + thrown);
		finish(helper, player);
	}

	@GameTest
	public void craftingRemaindersGoToStorage(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.MILK_BUCKET), 6);
		storage.insert(ItemVariant.of(Items.SUGAR), 10);
		storage.insert(ItemVariant.of(Items.EGG), 5);
		storage.insert(ItemVariant.of(Items.WHEAT), 10);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "cake", false);
		menu.clicked(TerminalMenu.RESULT_SLOT, 0, ContainerInput.PICKUP, player);
		helper.assertTrue(menu.getCarried().is(Items.CAKE), "crafted a cake");
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.BUCKET)) == 3, "empty buckets stored");
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.MILK_BUCKET)) == 0, "grid refilled with milk");
		for (int i = 0; i < 3; i++) {
			helper.assertTrue(menu.getSlot(TerminalMenu.GRID_START + i).getItem().is(Items.MILK_BUCKET), "milk bucket in grid slot " + i);
		}
		finish(helper, player);
	}

	@GameTest
	public void recipeBookUsesTagsAndMaxItems(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.BIRCH_PLANKS), 500);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);

		placeRecipe(helper, player, menu, "stick", false);
		helper.assertTrue(menu.getSlot(TerminalMenu.RESULT_SLOT).getItem().is(Items.STICK), "stick recipe placed from birch planks (tag ingredient)");
		placeRecipe(helper, player, menu, "stick", false);
		int perSlot = maxGridCount(menu);
		helper.assertTrue(perSlot == 2, "clicking the recipe again adds one more per slot, got " + perSlot);
		placeRecipe(helper, player, menu, "stick", true);
		perSlot = maxGridCount(menu);
		helper.assertTrue(perSlot == 64, "shift click on a recipe fills a full stack per slot, got " + perSlot);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.BIRCH_PLANKS)) == 500 - 128, "planks taken from storage");

		menu.clickMenuButton(player, TerminalMenu.BUTTON_CLEAR_GRID);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.BIRCH_PLANKS)) == 500, "clear button returns the grid");
		finish(helper, player);
	}

	@GameTest
	public void recipeBookSkipsNamedAndDamagedItems(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemStack named = new ItemStack(Items.IRON_INGOT);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep me"));
		storage.insert(ItemVariant.of(named), 5);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "iron_nugget", false);
		helper.assertTrue(menu.getSlot(TerminalMenu.RESULT_SLOT).getItem().isEmpty(), "named ingots must not be used automatically");
		helper.assertTrue(storage.getAmount(ItemVariant.of(named)) == 5, "named ingots untouched");

		// ...but plain ones from the player inventory are
		player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 2));
		placeRecipe(helper, player, menu, "iron_nugget", false);
		helper.assertTrue(menu.getSlot(TerminalMenu.RESULT_SLOT).getItem().is(Items.IRON_NUGGET), "ingredient taken from the player inventory");
		finish(helper, player);
	}

	@GameTest
	public void closingReturnsGridToStorage(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		storage.insert(ItemVariant.of(Items.OAK_LOG), 10);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "oak_planks", true);
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 0, "all logs in the grid");
		player.closeContainer();
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.OAK_LOG)) == 10, "closing returns the grid to storage");
		helper.assertTrue(player.getInventory().countItem(Items.OAK_LOG) == 0, "nothing went to the inventory");
		finish(helper, player);
	}

	@GameTest(maxTicks = 400)
	public void largeStoragePerformance(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		long start = System.nanoTime();
		for (int i = 0; i < 20_000; i++) {
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("Paper #" + i));
			storage.insert(ItemVariant.of(stack), i + 1);
		}
		long insertMs = (System.nanoTime() - start) / 1_000_000;
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		start = System.nanoTime();
		TerminalMenu menu = openTerminal(helper, player);
		menu.broadcastChanges();
		long openMs = (System.nanoTime() - start) / 1_000_000;
		start = System.nanoTime();
		for (int i = 0; i < 1000; i++) {
			// click spam: sync work must stay bounded (at most one sync per tick)
			menu.handleStorageClick(player, ItemVariant.of(Items.PAPER), StorageClickPayload.TAKE_STACK);
			storage.insert(ItemVariant.of(Items.STONE), 1);
		}
		long spamMs = (System.nanoTime() - start) / 1_000_000;
		start = System.nanoTime();
		var contents = storage.toComponent();
		long snapshotMs = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[simpleme-test] 20k types: insert %d ms, open+sync %d ms, 1000 clicks %d ms, snapshot %d ms (%d entries)%n",
			insertMs, openMs, spamMs, snapshotMs, contents.entries().size());
		helper.assertTrue(openMs < 2000, "opening a 20k-type terminal took " + openMs + " ms");
		helper.assertTrue(spamMs < 1000, "1000 clicks took " + spamMs + " ms");
		finish(helper, player);
	}

	// ------------------------------------------------------------------------------------------
	// Networks: create, choose, share, rename, delete
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void newTerminalCreatesPrivateNetwork(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeBareTerminal(helper, TERMINAL);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, owner);
		menu.handleStorageClick(owner, ItemVariant.of(Items.STONE), StorageClickPayload.TAKE_STACK);
		owner.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 10));
		menu.clicked(TerminalMenu.HOTBAR_START, 0, ContainerInput.QUICK_MOVE, owner);
		helper.assertTrue(owner.getInventory().countItem(Items.OAK_LOG) == 10, "without a network nothing can be stored");

		networkAction(owner, NetworkActionPayload.CREATE, null, "  Lager  ");
		UUID id = terminal.getNetworkId();
		Networks.Network network = Networks.get(helper.getLevel().getServer()).find(id);
		helper.assertTrue(network != null && network.name().equals("Lager"), "network created (name trimmed) and connected: " + id);
		helper.assertTrue(owner.getUUID().equals(network.owner()) && !network.isPublic(), "new networks are private");
		int logSlot = -1;
		for (int i = TerminalMenu.INV_START; i < TerminalMenu.INV_END; i++) {
			if (menu.getSlot(i).getItem().is(Items.OAK_LOG)) {
				logSlot = i;
			}
		}
		menu.clicked(logSlot, 0, ContainerInput.QUICK_MOVE, owner);
		helper.assertTrue(terminal.getStorage().getAmount(ItemVariant.of(Items.OAK_LOG)) == 10, "now items can be stored");
		finish(helper, owner);
	}

	@GameTest
	public void privateNetworksOnlyForTheirOwner(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeBareTerminal(helper, TERMINAL);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		ServerPlayer other = mockPlayer(helper, GameType.SURVIVAL);
		openTerminal(helper, owner);
		networkAction(owner, NetworkActionPayload.CREATE, null, "Private");
		UUID id = terminal.getNetworkId();
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		terminal.getStorage().insert(diamond, 64);
		owner.closeContainer();

		TerminalMenu otherMenu = openTerminal(helper, other);
		otherMenu.handleStorageClick(other, diamond, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(otherMenu.getCarried().isEmpty(), "others cannot take from a private network, even at its terminal");
		helper.assertTrue(Networks.get(server).accessible(other).stream().noneMatch(network -> network.id().equals(id)), "private networks are not listed for others");
		networkAction(other, NetworkActionPayload.RENAME, id, "Mine now");
		networkAction(other, NetworkActionPayload.TOGGLE_PUBLIC, id, "");
		networkAction(other, NetworkActionPayload.DELETE, id, "");
		Networks.Network network = Networks.get(server).find(id);
		helper.assertTrue(network != null && network.name().equals("Private") && !network.isPublic(), "others cannot rename, share or delete it");
		other.closeContainer();

		// shared by the owner: now usable by everybody, but still managed by the owner only
		openTerminal(helper, owner);
		networkAction(owner, NetworkActionPayload.TOGGLE_PUBLIC, id, "");
		networkAction(owner, NetworkActionPayload.RENAME, id, "Shared");
		owner.closeContainer();
		helper.assertTrue(network.isPublic() && network.name().equals("Shared"), "owner shared and renamed it");
		otherMenu = openTerminal(helper, other);
		otherMenu.handleStorageClick(other, diamond, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(otherMenu.getCarried().getCount() == 64, "public network: others can use it");
		networkAction(other, NetworkActionPayload.RENAME, id, "Hijacked");
		helper.assertTrue(network.name().equals("Shared"), "but not rename it");
		finish(helper, owner, other);
	}

	@GameTest
	public void onlyEmptyNetworksCanBeDeleted(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeBareTerminal(helper, TERMINAL);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		openTerminal(helper, owner);
		networkAction(owner, NetworkActionPayload.CREATE, null, "Temp");
		UUID id = terminal.getNetworkId();
		terminal.getStorage().insert(ItemVariant.of(Items.DIRT), 1);
		networkAction(owner, NetworkActionPayload.DELETE, id, "");
		helper.assertTrue(Networks.get(server).find(id) != null, "a network with items is not deleted");
		terminal.getStorage().extract(ItemVariant.of(Items.DIRT), 1);
		networkAction(owner, NetworkActionPayload.DELETE, id, "");
		helper.assertTrue(Networks.get(server).find(id) == null, "an empty network can be deleted");
		helper.assertTrue(terminal.getNetworkId() == null, "its terminals have no network any more");
		finish(helper, owner);
	}

	@GameTest
	public void switchingNetworkReturnsCraftingGrid(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		TerminalBlockEntity terminal = placeTerminal(helper);
		TerminalStorage first = terminal.getStorage();
		first.insert(ItemVariant.of(Items.OAK_LOG), 10);
		Networks.Network second = Networks.get(server).create(null, "Second");
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		placeRecipe(helper, player, menu, "oak_planks", true);
		helper.assertTrue(first.getAmount(ItemVariant.of(Items.OAK_LOG)) == 0, "all logs in the grid");

		networkAction(player, NetworkActionPayload.CONNECT, second.id(), "");
		helper.assertTrue(second.id().equals(terminal.getNetworkId()), "switched to the second network");
		helper.assertTrue(first.getAmount(ItemVariant.of(Items.OAK_LOG)) == 10, "the grid went back to the first network");
		helper.assertTrue(Networks.storage(server, second.id()).isEmpty(), "nothing leaked into the second network");
		helper.assertTrue(menu.getSlot(TerminalMenu.GRID_START).getItem().isEmpty(), "grid is empty");
		finish(helper, player);
	}

	// ------------------------------------------------------------------------------------------
	// Wireless terminal
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void wirelessTerminalWorksWithoutAnyTerminal(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		storage.insert(diamond, 100);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack remote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, remote);

		linkWithItemInHand(helper, player, TERMINAL);
		StorageLink link = remote.get(ModRegistry.LINK);
		helper.assertTrue(link != null && link.name().equals("Test"), "sneak + right click on a terminal takes its network, got " + link);
		helper.assertTrue(player.getMainHandItem() == remote, "linking does not consume the wireless terminal");

		// no terminal needed at all, far away in an unloaded chunk
		breakAndCollect(helper, TERMINAL);
		player.setPos(player.getX() + 100_000, player.getY(), player.getZ() + 100_000);
		WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(player.containerMenu instanceof TerminalMenu, "wireless terminal opens the network");
		TerminalMenu menu = (TerminalMenu) player.containerMenu;
		helper.assertTrue(menu.stillValid(player), "no range limit");
		menu.handleStorageClick(player, diamond, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().getCount() == 64 && storage.getAmount(diamond) == 36, "can take items through the wireless terminal");
		menu.handleStorageClick(player, diamond, StorageClickPayload.INSERT_ALL);
		helper.assertTrue(storage.getAmount(diamond) == 100, "can store items through the wireless terminal");

		placeRecipe(helper, player, menu, "diamond_block", false);
		helper.assertTrue(menu.getSlot(TerminalMenu.RESULT_SLOT).getItem().is(Items.DIAMOND_BLOCK), "crafting works in the wireless terminal");

		int remoteSlot = TerminalMenu.HOTBAR_START + player.getInventory().getSelectedSlot();
		menu.clicked(remoteSlot, 0, ContainerInput.QUICK_MOVE, player);
		menu.clicked(remoteSlot, 0, ContainerInput.PICKUP, player);
		menu.clicked(TerminalMenu.INV_START, player.getInventory().getSelectedSlot(), ContainerInput.SWAP, player);
		helper.assertTrue(player.getMainHandItem() == remote && menu.getCarried().isEmpty(), "the wireless terminal in use cannot be moved or stored");

		player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		helper.assertFalse(menu.stillValid(player), "menu closes when the wireless terminal leaves the hand");
		player.setItemInHand(InteractionHand.MAIN_HAND, remote);
		player.closeContainer();
		helper.assertTrue(storage.getAmount(diamond) == 100, "closing returns the crafting grid to the network");
		finish(helper, player);
	}

	@GameTest
	public void wirelessTerminalChoosesNetworkInItsGui(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		Networks.Network network = Networks.get(server).create(player, "Remote");
		Networks.storage(server, network.id()).insert(ItemVariant.of(Items.EMERALD), 5);
		ItemStack remote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, remote);

		WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(player.containerMenu instanceof TerminalMenu opened && opened.networkId() == null, "without network it opens to choose one");
		networkAction(player, NetworkActionPayload.CONNECT, network.id(), "");
		helper.assertTrue(network.id().equals(remote.get(ModRegistry.LINK).id()) && remote.get(ModRegistry.LINK).name().equals("Remote"),
			"the choice is stored on the item");
		TerminalMenu menu = (TerminalMenu) player.containerMenu;
		menu.handleStorageClick(player, ItemVariant.of(Items.EMERALD), StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().getCount() == 5, "and used right away");
		menu.handleStorageClick(player, ItemVariant.of(Items.EMERALD), StorageClickPayload.INSERT_ALL);
		player.closeContainer();

		// the network is renamed: the tooltip name follows the next time it is opened
		Networks.get(server).rename(network, "Renamed");
		WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(remote.get(ModRegistry.LINK).name().equals("Renamed"), "name refreshed");
		player.closeContainer();

		// deleted: the item forgets it
		Networks.storage(server, network.id()).extract(ItemVariant.of(Items.EMERALD), 5);
		Networks.get(server).delete(network);
		WirelessTerminalItem.open(player, InteractionHand.MAIN_HAND);
		helper.assertTrue(!remote.has(ModRegistry.LINK) && ((TerminalMenu) player.containerMenu).networkId() == null, "deleted network is forgotten");
		finish(helper, player);
	}

	@GameTest
	public void wirelessTerminalLinksTransferChest(GameTestHelper helper) {
		placeTerminal(helper);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistry.WIRELESS_TERMINAL));
		linkWithItemInHand(helper, player, TERMINAL);
		BlockPos chestPos = new BlockPos(4, 2, 2);
		helper.setBlock(chestPos, ModRegistry.TRANSFER_CHEST_BLOCK);
		linkWithItemInHand(helper, player, chestPos);
		TransferChestBlockEntity chest = helper.getBlockEntity(chestPos, TransferChestBlockEntity.class);
		helper.assertTrue(player.getMainHandItem().get(ModRegistry.LINK).equals(chest.getLink()), "sneak + right click on a transfer chest copies the network");
		finish(helper, player);
	}

	// ------------------------------------------------------------------------------------------
	// Transfer chest
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void transferChestSendsItemsToNetwork(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack chestItem = new ItemStack(ModRegistry.TRANSFER_CHEST_ITEM);
		player.setItemInHand(InteractionHand.MAIN_HAND, chestItem);
		linkWithItemInHand(helper, player, TERMINAL);
		helper.assertTrue(chestItem.has(ModRegistry.LINK), "sneak + right click on a terminal connects the chest item instead of placing it");
		helper.assertTrue(chestItem.getCount() == 1, "linking does not place the chest");

		BlockPos chestPos = new BlockPos(4, 2, 2);
		TransferChestBlockEntity chest = placeChest(helper, chestPos, chestItem);
		helper.assertTrue(chest.getLink() != null, "placed chest keeps the network");

		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		chest.setItem(5, new ItemStack(Items.TORCH, 10));
		helper.assertTrue(chest.getContainerSize() == 54, "6 rows of 9 slots");
		chest.setItem(53, new ItemStack(Items.TORCH, 5));
		helper.assertTrue(chest.transferToStorage(), "items moved");
		helper.assertTrue(chest.isEmpty(), "chest is empty afterwards");
		helper.assertTrue(storage.getAmount(ItemVariant.of(Items.COBBLESTONE)) == 64 && storage.getAmount(ItemVariant.of(Items.TORCH)) == 15,
			"items arrived in the network");

		// the terminal is not needed
		breakAndCollect(helper, TERMINAL);
		chest.setItem(0, oldTerminalItem(helper, Items.DIRT, 1));
		chest.setItem(1, new ItemStack(Items.STONE, 3));
		chest.transferToStorage();
		helper.assertTrue(chest.getItem(0).is(ModRegistry.TERMINAL_ITEM) && chest.getItem(1).isEmpty(), "old filled terminal stays, stone is sent without any terminal");
		finish(helper, player);
	}

	@GameTest
	public void transferChestOffOrWithoutNetworkKeepsItems(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		BlockPos chestPos = new BlockPos(4, 2, 2);
		helper.setBlock(chestPos, ModRegistry.TRANSFER_CHEST_BLOCK);
		TransferChestBlockEntity chest = helper.getBlockEntity(chestPos, TransferChestBlockEntity.class);
		ItemVariant iron = ItemVariant.of(Items.IRON_INGOT);
		chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
		helper.assertFalse(chest.transferToStorage(), "a chest without network is a normal chest");

		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		player.openMenu(chest);
		helper.assertTrue(player.containerMenu instanceof TransferChestMenu, "chest menu opened");
		TransferChestMenu menu = (TransferChestMenu) player.containerMenu;
		helper.assertTrue(menu.status() == TransferChestMenu.STATUS_UNLINKED, "status: no network");

		// choose the network in the chest GUI, but switched off first
		menu.clickMenuButton(player, TransferChestMenu.BUTTON_TOGGLE);
		helper.assertFalse(helper.getBlockState(chestPos).getValue(TransferChestBlock.ENABLED), "button switches the chest off");
		networkAction(player, NetworkActionPayload.CREATE, null, "Chest network");
		UUID id = menu.networkId();
		helper.assertTrue(id != null && menu.status() == TransferChestMenu.STATUS_ONLINE, "network created and chosen in the chest GUI");
		helper.assertFalse(chest.transferToStorage(), "a switched off chest keeps its items");
		menu.clickMenuButton(player, TransferChestMenu.BUTTON_TOGGLE);
		helper.assertTrue(chest.transferToStorage() && Networks.storage(server, id).getAmount(iron) == 5, "switched on: sent");

		// deleted network: items stay in the chest
		Networks.storage(server, id).extract(iron, 5);
		networkAction(player, NetworkActionPayload.DELETE, id, "");
		helper.assertTrue(menu.status() == TransferChestMenu.STATUS_OFFLINE, "status shows the deleted network");
		chest.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
		helper.assertFalse(chest.transferToStorage(), "nothing is sent to a deleted network");
		helper.assertTrue(chest.getItem(0).getCount() == 5, "items still there");
		finish(helper, player);
	}

	@GameTest(maxTicks = 200)
	public void hopperIntoTransferChestReachesNetwork(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		BlockPos chestPos = new BlockPos(4, 2, 2);
		helper.setBlock(chestPos, ModRegistry.TRANSFER_CHEST_BLOCK);
		TransferChestBlockEntity chest = helper.getBlockEntity(chestPos, TransferChestBlockEntity.class);
		chest.setLink(linkTo(helper, terminal));
		helper.setBlock(chestPos.above(), Blocks.HOPPER.defaultBlockState());
		helper.getBlockEntity(chestPos.above(), net.minecraft.world.level.block.entity.HopperBlockEntity.class).setItem(0, new ItemStack(Items.COAL, 8));
		helper.runAfterDelay(100, () -> {
			long coal = terminal.getStorage().getAmount(ItemVariant.of(Items.COAL));
			helper.assertTrue(coal == 8, "coal went hopper -> chest -> network, got " + coal);
			helper.assertTrue(chest.isEmpty(), "chest empty");
			helper.succeed();
		});
	}

	@GameTest
	public void brokenTransferChestKeepsNetworkAndDropsItems(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		BlockPos chestPos = new BlockPos(4, 2, 2);
		helper.setBlock(chestPos, ModRegistry.TRANSFER_CHEST_BLOCK.defaultBlockState().setValue(TransferChestBlock.ENABLED, false));
		TransferChestBlockEntity chest = helper.getBlockEntity(chestPos, TransferChestBlockEntity.class);
		chest.setLink(new StorageLink(terminal.getNetworkId(), "old name"));
		chest.setItem(3, new ItemStack(Items.EMERALD, 7));
		helper.getLevel().destroyBlock(helper.absolutePos(chestPos), true);
		ItemStack chestItem = ItemStack.EMPTY;
		int emeralds = 0;
		for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(chestPos)).inflate(3))) {
			if (entity.getItem().is(ModRegistry.TRANSFER_CHEST_ITEM)) {
				chestItem = entity.getItem().copy();
			} else if (entity.getItem().is(Items.EMERALD)) {
				emeralds += entity.getItem().getCount();
			}
			entity.discard();
		}
		helper.assertTrue(linkTo(helper, terminal).equals(chestItem.get(ModRegistry.LINK)), "dropped chest keeps its network (with the current name), got " + chestItem);
		helper.assertTrue(emeralds == 7, "contents are dropped like a normal chest, got " + emeralds);
		helper.succeed();
	}

	// ------------------------------------------------------------------------------------------
	// Pick block from the ME network
	// ------------------------------------------------------------------------------------------

	@GameTest
	public void pickBlockTakesFromNetwork(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant stone = ItemVariant.of(Items.STONE);
		storage.insert(stone, 100);
		BlockPos target = new BlockPos(2, 2, 4);
		helper.setBlock(target, Blocks.STONE);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);

		pickBlock(helper, player, target);
		helper.assertTrue(player.getInventory().countItem(Items.STONE) == 0 && storage.getAmount(stone) == 100,
			"without a wireless terminal nothing comes from the network");

		ItemStack remote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, remote);
		linkWithItemInHand(helper, player, TERMINAL);
		player.getInventory().setItem(0, ItemStack.EMPTY);
		player.getInventory().setItem(20, remote); // anywhere in the inventory is enough

		pickBlock(helper, player, target);
		helper.assertTrue(player.getMainHandItem().is(Items.STONE) && player.getMainHandItem().getCount() == 64, "one stack picked into the hand, got " + player.getMainHandItem());
		helper.assertTrue(storage.getAmount(stone) == 36, "taken out of the network");

		player.getInventory().setSelectedSlot(4);
		pickBlock(helper, player, target);
		helper.assertTrue(player.getInventory().getSelectedSlot() == 0 && storage.getAmount(stone) == 36,
			"already in the inventory: vanilla selects it, nothing taken");

		// the last few: less than a stack
		player.getInventory().setItem(0, ItemStack.EMPTY);
		storage.extract(stone, 30);
		pickBlock(helper, player, target);
		helper.assertTrue(player.getMainHandItem().getCount() == 6 && storage.getAmount(stone) == 0, "takes what is left");

		// not stored at all: nothing happens
		helper.setBlock(target, Blocks.GOLD_BLOCK);
		pickBlock(helper, player, target);
		helper.assertTrue(player.getInventory().countItem(Items.GOLD_BLOCK) == 0, "items that are not stored are not created");
		finish(helper, player);
	}

	@GameTest
	public void pickBlockNeedsInventorySpace(GameTestHelper helper) {
		TerminalStorage storage = placeTerminal(helper).getStorage();
		ItemVariant stone = ItemVariant.of(Items.STONE);
		storage.insert(stone, 100);
		BlockPos target = new BlockPos(2, 2, 4);
		helper.setBlock(target, Blocks.STONE);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack remote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, remote);
		linkWithItemInHand(helper, player, TERMINAL);

		// hotbar full, one free slot in the inventory: the hand item makes room, nothing is lost
		for (int i = 1; i < 36; i++) {
			player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
		}
		player.getInventory().setItem(30, ItemStack.EMPTY);
		pickBlock(helper, player, target);
		helper.assertTrue(player.getMainHandItem().is(Items.STONE) && storage.getAmount(stone) == 36, "picked although the hotbar was full");
		helper.assertTrue(player.getInventory().countItem(ModRegistry.WIRELESS_TERMINAL) == 1, "the item that was in the hand moved to the free slot");
		helper.assertTrue(player.getInventory().countItem(Items.DIRT) == 34 * 64, "nothing lost");

		// inventory completely full: nothing is taken out
		player.getInventory().setItem(player.getInventory().getSelectedSlot(), new ItemStack(Items.DIRT, 64));
		pickBlock(helper, player, target);
		helper.assertTrue(storage.getAmount(stone) == 36, "no room: nothing taken, got " + storage.getAmount(stone));
		helper.assertTrue(player.getInventory().countItem(ModRegistry.WIRELESS_TERMINAL) == 1, "wireless terminal still there");
		finish(helper, player);
	}

	@GameTest
	public void pickBlockNeedsAccessAndSurvival(GameTestHelper helper) {
		var server = helper.getLevel().getServer();
		BlockPos target = new BlockPos(2, 2, 4);
		helper.setBlock(target, Blocks.STONE);
		ItemVariant stone = ItemVariant.of(Items.STONE);
		ServerPlayer owner = mockPlayer(helper, GameType.SURVIVAL);
		Networks.Network network = Networks.get(server).create(owner, "Private");
		TerminalStorage storage = Networks.storage(server, network.id());
		storage.insert(stone, 100);

		ServerPlayer creative = mockPlayer(helper, GameType.CREATIVE);
		ItemStack creativeRemote = new ItemStack(ModRegistry.WIRELESS_TERMINAL);
		creativeRemote.set(ModRegistry.LINK, StorageLink.of(network));
		creative.setItemInHand(InteractionHand.MAIN_HAND, creativeRemote);
		creative.getInventory().setSelectedSlot(1);
		pickBlock(helper, creative, target);
		helper.assertTrue(storage.getAmount(stone) == 100, "creative players get the vanilla copy, the network is untouched");

		// a wireless terminal of a private network of somebody else does not work
		ServerPlayer other = mockPlayer(helper, GameType.SURVIVAL);
		ItemStack otherRemote = creativeRemote.copy();
		other.setItemInHand(InteractionHand.MAIN_HAND, otherRemote);
		other.getInventory().setSelectedSlot(1);
		pickBlock(helper, other, target);
		helper.assertTrue(other.getInventory().countItem(Items.STONE) == 0 && storage.getAmount(stone) == 100, "no pick from somebody else's private network");

		Networks.get(server).setPublic(network, true);
		pickBlock(helper, other, target);
		helper.assertTrue(other.getInventory().countItem(Items.STONE) == 64, "works once the network is shared");
		finish(helper, owner, creative, other);
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	private static TerminalBlockEntity placeTerminal(GameTestHelper helper) {
		return placeTerminal(helper, TERMINAL);
	}

	/** A terminal connected to a new public network. */
	private static TerminalBlockEntity placeTerminal(GameTestHelper helper, BlockPos pos) {
		TerminalBlockEntity terminal = placeBareTerminal(helper, pos);
		terminal.setNetworkId(Networks.get(helper.getLevel().getServer()).create(null, "Test").id());
		return terminal;
	}

	private static TerminalBlockEntity placeBareTerminal(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, ModRegistry.TERMINAL_BLOCK);
		return helper.getBlockEntity(pos, TerminalBlockEntity.class);
	}

	/** A terminal item of version 1.0: the storage id and a summary are on the item. */
	private static ItemStack oldTerminalItem(GameTestHelper helper, net.minecraft.world.item.Item item, long count) {
		UUID id = UUID.randomUUID();
		StorageData.get(helper.getLevel().getServer(), id).storage().insert(ItemVariant.of(item), count);
		ItemStack stack = new ItemStack(ModRegistry.TERMINAL_ITEM);
		stack.set(ModRegistry.STORAGE, new TerminalContents(id, 1, count));
		return stack;
	}

	/** Breaks the block like a player/explosion would (loot table drops) and picks up the terminal item. */
	private static ItemStack breakAndCollect(GameTestHelper helper, BlockPos pos) {
		helper.getLevel().destroyBlock(helper.absolutePos(pos), true);
		return collectTerminalItem(helper, pos);
	}

	private static ItemStack collectTerminalItem(GameTestHelper helper, BlockPos pos) {
		List<ItemEntity> items = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(helper.absolutePos(pos)).inflate(6),
			entity -> entity.getItem().is(ModRegistry.TERMINAL_ITEM));
		if (items.isEmpty()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = items.getFirst().getItem().copy();
		for (ItemEntity entity : items) {
			entity.discard();
		}
		return stack;
	}

	private static TerminalBlockEntity placeFromItem(GameTestHelper helper, BlockPos pos, ItemStack stack) {
		BlockPos abs = helper.absolutePos(pos);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
		BlockPlaceContext context = new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, stack.copy(), hit);
		((BlockItem) stack.getItem()).place(context);
		return helper.getBlockEntity(pos, TerminalBlockEntity.class);
	}

	/** Sneak + right click on the block with the item in the main hand. */
	private static void linkWithItemInHand(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
		BlockPos abs = helper.absolutePos(pos);
		ItemStack stack = player.getMainHandItem();
		player.setShiftKeyDown(true);
		var result = stack.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false)));
		player.setShiftKeyDown(false);
		helper.assertTrue(result.consumesAction(), "linking " + stack + " with " + pos + " should succeed");
	}

	/** Middle click on a block, sent through the network handler like a real client. */
	private static void pickBlock(GameTestHelper helper, ServerPlayer player, BlockPos pos) {
		player.connection.handlePickItemFromBlock(new net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket(helper.absolutePos(pos), false));
	}

	private static StorageLink linkTo(GameTestHelper helper, TerminalBlockEntity terminal) {
		return StorageLink.of(Networks.get(helper.getLevel().getServer()).find(terminal.getNetworkId()));
	}

	/** What the network panel sends for the menu the player has open. */
	private static void networkAction(ServerPlayer player, int action, @Nullable UUID network, String text) {
		NetworkActions.handle(player, new NetworkActionPayload(player.containerMenu.containerId, action, Optional.ofNullable(network), text));
	}

	private static TransferChestBlockEntity placeChest(GameTestHelper helper, BlockPos pos, ItemStack stack) {
		BlockPos abs = helper.absolutePos(pos);
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false);
		((BlockItem) stack.getItem()).place(new BlockPlaceContext(helper.getLevel(), null, InteractionHand.MAIN_HAND, stack.copy(), hit));
		return helper.getBlockEntity(pos, TransferChestBlockEntity.class);
	}

	private static ServerPlayer mockPlayer(GameTestHelper helper, GameType gameType) {
		var server = helper.getLevel().getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-player"), false);
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
		BlockPos abs = helper.absolutePos(TERMINAL);
		player.setPos(abs.getX() + 0.5, abs.getY() + 1, abs.getZ() + 1.5);
		return player;
	}

	private static TerminalMenu openTerminal(GameTestHelper helper, ServerPlayer player) {
		player.openMenu(helper.getBlockEntity(TERMINAL, TerminalBlockEntity.class));
		helper.assertTrue(player.containerMenu instanceof TerminalMenu, "terminal menu opened");
		return (TerminalMenu) player.containerMenu;
	}

	private static void placeRecipe(GameTestHelper helper, ServerPlayer player, TerminalMenu menu, String name, boolean max) {
		ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, Identifier.withDefaultNamespace(name));
		RecipeHolder<?> recipe = helper.getLevel().getServer().getRecipeManager().byKey(key).orElseThrow();
		menu.handlePlacement(max, false, recipe, (ServerLevel) player.level(), player.getInventory());
	}

	private static int maxGridCount(TerminalMenu menu) {
		int max = 0;
		for (int i = TerminalMenu.GRID_START; i < TerminalMenu.GRID_END; i++) {
			max = Math.max(max, menu.getSlot(i).getItem().getCount());
		}
		return max;
	}

	private static void finish(GameTestHelper helper, ServerPlayer... players) {
		for (ServerPlayer player : players) {
			player.closeContainer();
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		helper.succeed();
	}

	private static List<ItemStack> specialItems(ServerLevel level) {
		List<ItemStack> list = new ArrayList<>();
		ItemStack damaged = new ItemStack(Items.DIAMOND_PICKAXE);
		damaged.setDamageValue(321);
		list.add(damaged);
		ItemStack named = new ItemStack(Items.NETHERITE_SWORD);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
		list.add(named);
		var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
		book.enchant(enchantments.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING), 1);
		list.add(book);
		list.add(net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.STRONG_HEALING));
		ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
		shulker.set(DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(
			List.of(new ItemStack(Items.DIAMOND, 64), new ItemStack(Items.EMERALD, 32))));
		list.add(shulker);
		list.add(bigBook(-1));
		return list;
	}

	private static ItemStack bigBook(int id) {
		List<Filterable<Component>> pages = new ArrayList<>();
		String text = ("Page of book " + id + " ").repeat(200).substring(0, 1024);
		for (int i = 0; i < 100; i++) {
			pages.add(Filterable.passThrough(Component.literal(text)));
		}
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Book " + id), "tester", 0, pages, true));
		return book;
	}

	private static int encodedSize(ServerLevel level, ItemStack stack) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
		ItemStack.STREAM_CODEC.encode(buf, stack);
		return buf.readableBytes();
	}
}
