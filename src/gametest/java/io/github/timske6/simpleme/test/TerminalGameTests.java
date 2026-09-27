package io.github.timske6.simpleme.test;

import com.mojang.authlib.GameProfile;
import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.storage.TerminalStorage;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
		helper.assertTrue(storage.insert(stone, 1) == 0, "no overflow past the cap");

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
	public void filledTerminalCannotBeStored(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.DIRT), 10);
		ItemStack filled = breakAndCollect(helper, TERMINAL);
		helper.assertTrue(!filled.isEmpty(), "breaking should drop the terminal");

		TerminalStorage other = placeTerminal(helper, new BlockPos(5, 2, 2)).getStorage();
		helper.assertTrue(other.insert(ItemVariant.of(filled), 1) == 0, "a filled terminal must not be storable (nesting)");
		helper.assertTrue(other.insert(ItemVariant.of(ModRegistry.TERMINAL_ITEM), 1) == 1, "an empty terminal is fine");
		helper.succeed();
	}

	@GameTest
	public void droppedTerminalItemProperties(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.DIRT), 10);
		terminal.getStorage().insert(ItemVariant.of(Items.STONE), 5);
		ItemStack filled = breakAndCollect(helper, TERMINAL);
		var contents = filled.get(ModRegistry.STORAGE);
		helper.assertTrue(contents != null && contents.types() == 2 && contents.total() == 15, "tooltip summary is stored on the item: " + contents);
		helper.assertTrue(filled.has(DataComponents.DAMAGE_RESISTANT), "filled terminals do not burn in lava/fire");

		// an empty terminal drops as a plain, stackable item
		placeTerminal(helper).getStorage();
		ItemStack empty = breakAndCollect(helper, TERMINAL);
		helper.assertFalse(empty.has(ModRegistry.STORAGE), "empty terminal drops without storage data");
		helper.assertTrue(ItemStack.isSameItemSameComponents(empty, new ItemStack(ModRegistry.TERMINAL_ITEM)), "empty terminals stack with new ones");
		helper.succeed();
	}

	@GameTest
	public void duplicatedTerminalItemDoesNotDuplicateItems(GameTestHelper helper) {
		// If another mod or a glitch duplicates the terminal item, both copies point to the same storage.
		TerminalBlockEntity terminal = placeTerminal(helper);
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		terminal.getStorage().insert(diamond, 64);
		ItemStack item = breakAndCollect(helper, TERMINAL);
		TerminalBlockEntity first = placeFromItem(helper, new BlockPos(1, 2, 5), item.copy());
		TerminalBlockEntity second = placeFromItem(helper, new BlockPos(5, 2, 5), item.copy());
		helper.assertTrue(first.getStorage().extract(diamond, 64) == 64, "first copy has the diamonds");
		helper.assertTrue(second.getStorage().getAmount(diamond) == 0, "second copy shares the storage, so nothing was duplicated");
		helper.succeed();
	}

	@GameTest
	public void legacyDataIsMigrated(GameTestHelper helper) {
		ItemVariant gold = ItemVariant.of(Items.GOLD_INGOT);
		var legacy = new io.github.timske6.simpleme.storage.StoredItems(List.of(new io.github.timske6.simpleme.storage.StoredItems.Entry(gold, 42)));

		// old terminal item (items inside the item)
		ItemStack oldItem = new ItemStack(ModRegistry.TERMINAL_ITEM);
		oldItem.set(ModRegistry.LEGACY_STORED_ITEMS, legacy);
		TerminalBlockEntity placed = placeFromItem(helper, TERMINAL, oldItem);
		helper.assertTrue(placed.getStorage().getAmount(gold) == 42, "legacy item contents migrated");

		// old block entity data (items inside the block)
		var registries = helper.getLevel().registryAccess();
		var tag = new net.minecraft.nbt.CompoundTag();
		tag.putString("id", "simpleme:terminal");
		tag.put("Items", io.github.timske6.simpleme.storage.StoredItems.Entry.CODEC.listOf()
			.encodeStart(registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), legacy.entries()).getOrThrow());
		BlockPos pos = new BlockPos(5, 2, 5);
		helper.setBlock(pos, ModRegistry.TERMINAL_BLOCK);
		var loaded = (TerminalBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
			helper.absolutePos(pos), helper.getLevel().getBlockState(helper.absolutePos(pos)), tag, registries);
		loaded.setLevel(helper.getLevel());
		helper.assertTrue(loaded.getStorage().getAmount(gold) == 42, "legacy block entity contents migrated");
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
	public void breakAndPlaceKeepsEverything(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		List<ItemStack> special = specialItems(helper.getLevel());
		terminal.getStorage().insert(ItemVariant.of(Items.COBBLESTONE), 1_000_000);
		for (ItemStack stack : special) {
			terminal.getStorage().insert(ItemVariant.of(stack), 3);
		}
		int types = terminal.getStorage().typeCount();

		ItemStack dropped = breakAndCollect(helper, TERMINAL);
		helper.assertTrue(dropped.is(ModRegistry.TERMINAL_ITEM), "terminal item dropped");
		helper.assertBlockNotPresent(ModRegistry.TERMINAL_BLOCK, TERMINAL);

		BlockPos target = new BlockPos(4, 2, 4);
		TerminalBlockEntity placed = placeFromItem(helper, target, dropped);
		helper.assertTrue(placed.getStorage().typeCount() == types, "all item types restored");
		helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(Items.COBBLESTONE)) == 1_000_000, "counts restored");
		for (ItemStack stack : special) {
			helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(stack)) == 3, "special item restored: " + stack);
		}
		helper.succeed();
	}

	@GameTest
	public void explosionKeepsEverything(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.GOLD_INGOT), 500);
		BlockPos abs = helper.absolutePos(TERMINAL);
		helper.getLevel().explode(null, abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, 4.0F, Level.ExplosionInteraction.TNT);
		helper.assertBlockNotPresent(ModRegistry.TERMINAL_BLOCK, TERMINAL);
		ItemStack dropped = collectTerminalItem(helper, TERMINAL);
		helper.assertTrue(!dropped.isEmpty(), "terminal must drop when exploded");
		TerminalBlockEntity placed = placeFromItem(helper, new BlockPos(2, 2, 5), dropped);
		helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(Items.GOLD_INGOT)) == 500, "contents survive an explosion");
		helper.succeed();
	}

	@GameTest
	public void droppedTerminalStaysSmall(GameTestHelper helper) {
		// Many large written books: the dropped item must not become a multi-megabyte stack
		// (that would kick nearby players on a server, like the old "book ban" exploit).
		TerminalBlockEntity terminal = placeTerminal(helper);
		for (int i = 0; i < 90; i++) {
			terminal.getStorage().insert(ItemVariant.of(bigBook(i)), 1);
		}
		ItemStack dropped = breakAndCollect(helper, TERMINAL);
		int bytes = encodedSize(helper.getLevel(), dropped);
		helper.assertTrue(bytes < 64 * 1024, "dropped terminal item is " + bytes + " bytes on the network (limit 8 MB)");
		TerminalBlockEntity placed = placeFromItem(helper, new BlockPos(4, 2, 4), dropped);
		helper.assertTrue(placed.getStorage().typeCount() == 90, "all 90 books restored");
		helper.succeed();
	}

	@GameTest
	public void adminCanRestoreTerminalWithGiveCommand(GameTestHelper helper) {
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.EMERALD), 99);
		ItemStack lost = breakAndCollect(helper, TERMINAL);
		UUID id = lost.get(ModRegistry.STORAGE).id();

		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		var server = helper.getLevel().getServer();
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
			"give " + player.getGameProfile().name() + " simpleme:terminal[simpleme:storage={id:\"" + id + "\"}]");
		ItemStack restored = player.getInventory().getItem(0);
		helper.assertTrue(restored.is(ModRegistry.TERMINAL_ITEM) && id.equals(restored.get(ModRegistry.STORAGE).id()),
			"/give with the storage id restores the terminal, got " + restored);
		TerminalBlockEntity placed = placeFromItem(helper, new BlockPos(5, 2, 5), restored);
		helper.assertTrue(placed.getStorage().getAmount(ItemVariant.of(Items.EMERALD)) == 99, "restored terminal has its items");
		finish(helper, player);
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
		for (ItemStack stack : specialItems(helper.getLevel())) {
			terminal.getStorage().insert(ItemVariant.of(stack), 7);
		}
		terminal.getStorage().insert(ItemVariant.of(Items.IRON_INGOT), 123_456_789L);
		var registries = helper.getLevel().registryAccess();
		var tag = terminal.saveWithFullMetadata(registries);
		var loaded = (TerminalBlockEntity) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
			terminal.getBlockPos(), terminal.getBlockState(), tag, registries);
		helper.assertTrue(loaded != null, "block entity loads");
		loaded.setLevel(helper.getLevel());
		helper.assertTrue(loaded.getStorage() == terminal.getStorage(), "reloaded block entity points to the same storage");
		helper.assertTrue(loaded.getStorage().typeCount() == terminal.getStorage().typeCount(), "same number of types after load");
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
		TerminalBlockEntity terminal = placeTerminal(helper);
		terminal.getStorage().insert(ItemVariant.of(Items.DIRT), 1);
		ItemStack filled = breakAndCollect(helper, TERMINAL);
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
		ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
		terminal.getStorage().insert(diamond, 64);
		ServerPlayer player = mockPlayer(helper, GameType.SURVIVAL);
		TerminalMenu menu = openTerminal(helper, player);
		ItemStack dropped = breakAndCollect(helper, TERMINAL);
		helper.assertFalse(menu.stillValid(player), "menu must become invalid when the block is gone");
		menu.handleStorageClick(player, diamond, StorageClickPayload.TAKE_STACK);
		helper.assertTrue(menu.getCarried().isEmpty(), "no taking items out of a broken terminal (dupe)");
		TerminalBlockEntity placed = placeFromItem(helper, new BlockPos(4, 2, 4), dropped);
		helper.assertTrue(placed.getStorage().getAmount(diamond) == 64, "the dropped item has everything");
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
	// Helpers
	// ------------------------------------------------------------------------------------------

	private static TerminalBlockEntity placeTerminal(GameTestHelper helper) {
		return placeTerminal(helper, TERMINAL);
	}

	private static TerminalBlockEntity placeTerminal(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, ModRegistry.TERMINAL_BLOCK);
		return helper.getBlockEntity(pos, TerminalBlockEntity.class);
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
