package io.github.timske6.simpleme.menu;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.network.GroupActionPayload;
import io.github.timske6.simpleme.network.GroupListPayload;
import io.github.timske6.simpleme.network.NetworkActions;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.network.StorageSyncPayload;
import io.github.timske6.simpleme.storage.ItemGroups;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StoredItems;
import io.github.timske6.simpleme.storage.TerminalStorage;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import org.jspecify.annotations.Nullable;

/**
 * Menu of the ME terminal (placed or wireless): a virtual item grid (synced via custom packets),
 * a 3x3 crafting grid that refills itself from the storage and the player inventory.
 */
public class TerminalMenu extends AbstractCraftingMenu implements NetworkMenu {
	public static final int RESULT_SLOT = 0;
	public static final int GRID_START = 1;
	public static final int GRID_END = 10;
	public static final int INV_START = 10;
	public static final int HOTBAR_START = 37;
	public static final int INV_END = 46;

	public static final int BUTTON_CLEAR_GRID = 0;
	/** {@code BUTTON_SORT + mode}: the terminal sorts its items like that from now on. */
	public static final int BUTTON_SORT = 16;
	/** Name, count, mod. */
	public static final int SORT_MODES = 3;

	public static final int COLUMNS = 9;
	public static final int MIN_ROWS = 3;
	public static final int MAX_ROWS = 8;
	public static final int DEFAULT_ROWS = 5;

	/** Layout positions (relative to the GUI origin). */
	public static final int GRID_X = 8;
	public static final int GRID_Y = 18;
	public static final int CRAFT_GRID_X = 30;
	public static final int RESULT_X = 124;
	/** GUI height without the item rows. */
	public static final int FIXED_HEIGHT = 176;

	/** How many item rows the client shows; set by the client entrypoint based on the window height. */
	public static IntSupplier clientRows = () -> DEFAULT_ROWS;

	public final int rows;
	public final int craftingY;
	public final int inventoryLabelY;
	public final int inventoryY;
	public final int imageHeight;

	private final Player player;
	private final @Nullable TerminalHost host;

	// server side sync state
	private final Object2LongOpenHashMap<ItemVariant> lastSent = new Object2LongOpenHashMap<>();
	private long lastSentVersion = Long.MIN_VALUE;
	private int lastSyncTick = -1;
	private boolean suppressCraftUpdates;
	/** Crafts allowed during the click that is being handled (shift-click / ctrl+Q would otherwise empty the storage). */
	private int craftLimit = Integer.MAX_VALUE;
	private int craftsThisClick;
	/** The storage the client view belongs to; a different one (network switched or deleted) means a full resync. */
	private @Nullable TerminalStorage syncedStorage;
	private boolean networkSent;
	private @Nullable UUID sentNetworkId;
	private boolean sentNetworkAvailable;
	private int sentGroupsVersion = -1;
	private @Nullable UUID sentGroupsNetwork;

	// client side mirror of the storage
	private final Object2LongLinkedOpenHashMap<ItemVariant> clientItems = new Object2LongLinkedOpenHashMap<>();
	private int clientRevision;
	private int clientFullSyncs;
	private final NetworkClientState networkState = new NetworkClientState();
	private List<GroupListPayload.Entry> clientGroups = List.of();
	private int clientGroupsRevision;
	private int clientSortMode;

	/** Client constructor of the placed terminal (called through the menu type with the block position). */
	public TerminalMenu(int containerId, Inventory inventory, BlockPos pos) {
		this(ModRegistry.TERMINAL_MENU, containerId, inventory, null);
	}

	/**
	 * @param type {@link ModRegistry#TERMINAL_MENU} or {@link ModRegistry#WIRELESS_TERMINAL_MENU}
	 * @param host the storage to work on (server), {@code null} on the client
	 */
	public TerminalMenu(MenuType<?> type, int containerId, Inventory inventory, @Nullable TerminalHost host) {
		super(type, containerId, 3, 3);
		this.player = inventory.player;
		this.host = host;

		this.rows = inventory.player.level().isClientSide() ? Mth.clamp(clientRows.getAsInt(), MIN_ROWS, MAX_ROWS) : DEFAULT_ROWS;
		this.craftingY = GRID_Y + this.rows * 18 + 8;
		this.inventoryLabelY = this.craftingY + 54 + 3;
		this.inventoryY = this.inventoryLabelY + 11;
		this.imageHeight = this.inventoryY + 58 + 18 + 6;

		this.addSlot(new TerminalResultSlot(this.player, RESULT_X, this.craftingY + 18));
		this.addCraftingGridSlots(CRAFT_GRID_X, this.craftingY);
		this.addStandardInventorySlots(inventory, 8, this.inventoryY);
		this.addDataSlot(new DataSlot() {
			@Override
			public int get() {
				return TerminalMenu.this.host != null ? Mth.clamp(TerminalMenu.this.host.getSortMode(), 0, SORT_MODES - 1) : TerminalMenu.this.clientSortMode;
			}

			@Override
			public void set(int value) {
				TerminalMenu.this.clientSortMode = value;
			}
		});
	}

	public boolean isServerSide() {
		return this.host != null;
	}

	/**
	 * The storage of the connected network, or {@code null} if there is none, it was deleted or the
	 * player has no access (any more). Server side only.
	 */
	private @Nullable TerminalStorage storage() {
		if (this.host == null || !(this.player.level() instanceof ServerLevel level)) {
			return null;
		}
		UUID id = this.host.getNetworkId();
		Networks.Network network = Networks.get(level.getServer()).find(id);
		return network != null && network.canAccess(this.player) ? Networks.storage(level.getServer(), id) : null;
	}

	// ------------------------------------------------------------------------------------------
	// Network
	// ------------------------------------------------------------------------------------------

	@Override
	public @Nullable UUID networkId() {
		return this.host != null ? this.host.getNetworkId() : null;
	}

	@Override
	public void connectNetwork(ServerPlayer player, @Nullable UUID id) {
		if (this.host == null || Objects.equals(id, this.host.getNetworkId())) {
			return;
		}
		// the crafting grid goes back to the network it came from
		this.suppressCraftUpdates = true;
		try {
			this.returnGridToStorage(player);
		} finally {
			this.suppressCraftUpdates = false;
		}
		this.host.setNetworkId(id);
		this.refreshCraftingResult();
		this.lastSyncTick = -1;
		this.broadcastChanges();
	}

	@Override
	public NetworkClientState networkState() {
		return this.networkState;
	}

	// ------------------------------------------------------------------------------------------
	// Validity / lifecycle
	// ------------------------------------------------------------------------------------------

	@Override
	public boolean stillValid(Player player) {
		return this.host == null || this.host.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (this.host != null) {
			this.returnGridToStorage(player);
		}
	}

	private void returnGridToStorage(Player player) {
		TerminalStorage storage = this.storage();
		for (int i = 0; i < this.craftSlots.getContainerSize(); i++) {
			ItemStack stack = this.craftSlots.removeItemNoUpdate(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (storage != null) {
				storage.insertStack(stack);
			}
			if (!stack.isEmpty()) {
				if (player.isRemoved() || (player instanceof ServerPlayer serverPlayer && serverPlayer.hasDisconnected())) {
					player.drop(stack, false, Prediction.SERVER_ONLY);
				} else {
					player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
				}
			}
		}
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (buttonId == BUTTON_CLEAR_GRID) {
			if (this.host != null) {
				this.suppressCraftUpdates = true;
				try {
					this.returnGridToStorage(player);
				} finally {
					this.suppressCraftUpdates = false;
				}
				this.refreshCraftingResult();
			}
			return true;
		}
		if (buttonId >= BUTTON_SORT && buttonId < BUTTON_SORT + SORT_MODES) {
			if (this.host != null) {
				this.host.setSortMode(buttonId - BUTTON_SORT);
			}
			return true;
		}
		return false;
	}

	/** Client: how this terminal sorts its items (synced from the server, set right away when changed here). */
	public int getSortMode() {
		return Mth.clamp(this.clientSortMode, 0, SORT_MODES - 1);
	}

	/** Client: the server is told with {@link #BUTTON_SORT}; this shows the new mode before its answer arrives. */
	public void setClientSortMode(int mode) {
		this.clientSortMode = mode;
	}

	// ------------------------------------------------------------------------------------------
	// Storage sync (server → client)
	// ------------------------------------------------------------------------------------------

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		this.syncStorage();
	}

	private void syncStorage() {
		if (this.host == null || !(this.player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		TerminalStorage storage = this.storage();
		UUID networkId = this.host.getNetworkId();
		if (!this.networkSent || !Objects.equals(networkId, this.sentNetworkId) || (storage != null) != this.sentNetworkAvailable) {
			this.networkSent = true;
			this.sentNetworkId = networkId;
			this.sentNetworkAvailable = storage != null;
			NetworkActions.sendList(serverPlayer, this.containerId, networkId);
		}
		this.syncGroups(serverPlayer, storage != null ? networkId : null);
		if (storage != this.syncedStorage) {
			this.syncedStorage = storage;
			this.lastSentVersion = Long.MIN_VALUE;
			if (storage == null) {
				this.lastSent.clear();
				ServerPlayNetworking.send(serverPlayer, new StorageSyncPayload(this.containerId, true, true, List.of()));
				return;
			}
		}
		if (storage == null || storage.getVersion() == this.lastSentVersion) {
			return;
		}
		// At most once per tick, no matter how many clicks arrive; the rest is picked up next tick.
		int tick = serverPlayer.level().getServer().getTickCount();
		if (tick == this.lastSyncTick) {
			return;
		}
		this.lastSyncTick = tick;
		boolean reset = this.lastSentVersion == Long.MIN_VALUE;
		this.lastSentVersion = storage.getVersion();

		List<StoredItems.Entry> changes = new ArrayList<>();
		Object2LongMap<ItemVariant> current = storage.view();
		if (reset) {
			this.lastSent.clear();
		}
		for (Object2LongMap.Entry<ItemVariant> entry : current.object2LongEntrySet()) {
			ItemVariant variant = entry.getKey();
			long count = entry.getLongValue();
			if (reset || !this.lastSent.containsKey(variant) || this.lastSent.getLong(variant) != count) {
				changes.add(new StoredItems.Entry(variant, count));
				this.lastSent.put(variant, count);
			}
		}
		if (!reset) {
			ObjectIterator<Object2LongMap.Entry<ItemVariant>> iterator = this.lastSent.object2LongEntrySet().fastIterator();
			while (iterator.hasNext()) {
				ItemVariant variant = iterator.next().getKey();
				if (!current.containsKey(variant)) {
					changes.add(new StoredItems.Entry(variant, 0));
					iterator.remove();
				}
			}
		}
		if (reset && changes.isEmpty()) {
			ServerPlayNetworking.send(serverPlayer, new StorageSyncPayload(this.containerId, true, true, List.of()));
		}
		// Split big updates so a single packet never gets huge (e.g. a storage full of written books).
		for (int start = 0; start < changes.size(); start += StorageSyncPayload.MAX_ENTRIES) {
			List<StoredItems.Entry> batch = changes.subList(start, Math.min(changes.size(), start + StorageSyncPayload.MAX_ENTRIES));
			ServerPlayNetworking.send(serverPlayer, new StorageSyncPayload(this.containerId, reset && start == 0, reset, List.copyOf(batch)));
		}
	}

	private void syncGroups(ServerPlayer player, @Nullable UUID network) {
		ItemGroups groups = ItemGroups.get(player.level().getServer());
		if (groups.version() == this.sentGroupsVersion && Objects.equals(network, this.sentGroupsNetwork)) {
			return;
		}
		this.sentGroupsVersion = groups.version();
		this.sentGroupsNetwork = network;
		List<GroupListPayload.Entry> entries = new ArrayList<>();
		if (network != null) {
			for (ItemGroups.Group group : groups.visible(network, player)) {
				entries.add(new GroupListPayload.Entry(group.id(), group.name(), group.ownerName(), group.isShared(),
					group.canManage(player), group.isHiddenFor(player), List.copyOf(group.items())));
			}
		}
		ServerPlayNetworking.send(player, new GroupListPayload(this.containerId, entries));
	}

	/** Client: apply a sync packet to the local mirror. */
	public void applySync(StorageSyncPayload payload) {
		if (payload.reset()) {
			this.clientItems.clear();
		}
		if (payload.full()) {
			this.clientFullSyncs++;
		}
		for (StoredItems.Entry entry : payload.entries()) {
			if (entry.count() <= 0) {
				this.clientItems.removeLong(entry.variant());
			} else {
				this.clientItems.put(entry.variant(), entry.count());
			}
		}
		this.clientRevision++;
	}

	/** Client: the synced storage contents. */
	public Object2LongMap<ItemVariant> getClientItems() {
		return Object2LongMaps.unmodifiable(this.clientItems);
	}

	/** Client: changes every time the synced contents change. */
	public int getClientRevision() {
		return this.clientRevision;
	}

	/** Client: the item groups of the connected network. */
	public void applyGroups(GroupListPayload payload) {
		this.clientGroups = payload.groups();
		this.clientGroupsRevision++;
	}

	public List<GroupListPayload.Entry> getClientGroups() {
		return this.clientGroups;
	}

	/** Client: changes every time the groups are received. */
	public int getClientGroupsRevision() {
		return this.clientGroupsRevision;
	}

	/** Client: changes with every packet of a complete sync (menu opened, network switched). */
	public int getClientFullSyncs() {
		return this.clientFullSyncs;
	}

	// ------------------------------------------------------------------------------------------
	// Clicks on the virtual item grid (client → server)
	// ------------------------------------------------------------------------------------------

	public void handleStorageClick(ServerPlayer player, ItemVariant variant, int action) {
		TerminalStorage storage = this.storage();
		if (storage == null || player.isSpectator() || !this.stillValid(player)) {
			return;
		}
		ItemStack carried = this.getCarried();

		switch (action) {
			case StorageClickPayload.TAKE_STACK, StorageClickPayload.TAKE_HALF, StorageClickPayload.TAKE_ONE -> {
				if (!carried.isEmpty() || variant.isBlank()) {
					return;
				}
				int available = (int) Math.min(storage.getAmount(variant), maxStackSize(variant));
				if (available <= 0) {
					return;
				}
				int amount = switch (action) {
					case StorageClickPayload.TAKE_HALF -> (available + 1) / 2;
					case StorageClickPayload.TAKE_ONE -> 1;
					default -> available;
				};
				this.setCarried(storage.extractStack(variant, amount));
			}
			case StorageClickPayload.QUICK_MOVE -> {
				if (variant.isBlank()) {
					return;
				}
				ItemStack stack = storage.extractStack(variant, maxStackSize(variant));
				if (stack.isEmpty()) {
					return;
				}
				this.moveItemStackTo(stack, INV_START, INV_END, true);
				storage.insertStack(stack);
				if (!stack.isEmpty()) {
					player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
				}
			}
			case StorageClickPayload.INSERT_ALL -> {
				if (carried.isEmpty()) {
					return;
				}
				storage.insertStack(carried);
				this.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
			}
			case StorageClickPayload.INSERT_ONE -> {
				if (carried.isEmpty()) {
					return;
				}
				ItemStack single = carried.copyWithCount(1);
				storage.insertStack(single);
				if (single.isEmpty()) {
					carried.shrink(1);
				}
				this.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
			}
			case StorageClickPayload.CLONE -> {
				if (!player.hasInfiniteMaterials() || !carried.isEmpty() || storage.getAmount(variant) <= 0) {
					return;
				}
				this.setCarried(variant.toStack(maxStackSize(variant)));
			}
			default -> {
				return;
			}
		}
		this.broadcastChanges();
	}

	// ------------------------------------------------------------------------------------------
	// Item groups (client → server)
	// ------------------------------------------------------------------------------------------

	public void handleGroupAction(ServerPlayer player, GroupActionPayload payload) {
		UUID network = this.host != null ? this.host.getNetworkId() : null;
		if (network == null || this.storage() == null || player.isSpectator() || !this.stillValid(player)) {
			return;
		}
		ItemGroups groups = ItemGroups.get(player.level().getServer());
		if (payload.action() == GroupActionPayload.CREATE) {
			if (groups.ownedCount(network, player) >= ItemGroups.MAX_GROUPS) {
				player.sendOverlayMessage(Component.translatable("message.simpleme.group.too_many", ItemGroups.MAX_GROUPS).withStyle(ChatFormatting.RED));
			} else {
				groups.create(payload.group(), network, player, payload.text());
			}
			return;
		}
		ItemGroups.Group group = groups.find(payload.group());
		if (group == null || !group.network().equals(network) || !group.canSee(player)) {
			return;
		}
		switch (payload.action()) {
			case GroupActionPayload.SHOW -> groups.setHidden(group, player, false);
			case GroupActionPayload.HIDE -> groups.setHidden(group, player, true);
			default -> {
				if (!group.canManage(player)) {
					return;
				}
				switch (payload.action()) {
					case GroupActionPayload.RENAME -> groups.rename(group, payload.text());
					case GroupActionPayload.TOGGLE_SHARED -> groups.setShared(group, !group.isShared());
					case GroupActionPayload.DELETE -> groups.delete(group);
					case GroupActionPayload.ADD_ITEM -> payload.item().ifPresent(item -> {
						if (!groups.addItem(group, item) && group.items().size() >= ItemGroups.MAX_ITEMS) {
							player.sendOverlayMessage(Component.translatable("message.simpleme.group.full", ItemGroups.MAX_ITEMS).withStyle(ChatFormatting.RED));
						}
					});
					case GroupActionPayload.REMOVE_ITEM -> payload.item().ifPresent(item -> groups.removeItem(group, item));
					default -> {
					}
				}
			}
		}
	}

	private static int maxStackSize(ItemVariant variant) {
		return variant.toStack().getMaxStackSize();
	}

	// ------------------------------------------------------------------------------------------
	// Shift-clicking
	// ------------------------------------------------------------------------------------------

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
		if (this.isLockedClick(slotIndex, buttonNum, containerInput, player)) {
			// e.g. shift-clicking the wireless terminal in use would store it and close the menu
			return;
		}
		if (slotIndex == RESULT_SLOT && (containerInput == ContainerInput.QUICK_MOVE || containerInput == ContainerInput.THROW)) {
			// Shift-click and ctrl+Q repeat crafting in a loop. Because the grid refills itself from the storage,
			// limit them to a full stack of output (or as much as the grid holds, if that is more).
			ItemStack result = this.getSlot(RESULT_SLOT).getItem();
			int perCraft = Math.max(1, result.getCount());
			int byStack = Math.max(1, result.getMaxStackSize() / perCraft);
			int byGrid = 0;
			for (int i = 0; i < this.craftSlots.getContainerSize(); i++) {
				ItemStack stack = this.craftSlots.getItem(i);
				if (!stack.isEmpty()) {
					byGrid = byGrid == 0 ? stack.getCount() : Math.min(byGrid, stack.getCount());
				}
			}
			this.craftLimit = Math.max(byStack, byGrid);
		}
		this.craftsThisClick = 0;
		try {
			super.clicked(slotIndex, buttonNum, containerInput, player);
		} finally {
			this.craftLimit = Integer.MAX_VALUE;
			this.craftsThisClick = 0;
		}
	}

	private boolean isLockedClick(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
		ItemStack locked = this.host != null ? this.host.lockedItem(player) : ItemStack.EMPTY;
		if (locked.isEmpty()) {
			return false;
		}
		if (slotIndex >= 0 && slotIndex < this.slots.size() && this.slots.get(slotIndex).getItem() == locked) {
			return true;
		}
		return containerInput == ContainerInput.SWAP && player.getInventory().getItem(buttonNum) == locked;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (slot == null || !slot.hasItem() || this.host == null) {
			// The client does not predict shift-clicks; the server answers with the real result.
			return ItemStack.EMPTY;
		}

		if (slotIndex == RESULT_SLOT) {
			return this.quickMoveResult(player, slot);
		}

		ItemStack stack = slot.getItem();
		int before = stack.getCount();
		TerminalStorage storage = this.storage();
		if (storage != null) {
			storage.insertStack(stack);
		}
		if (stack.getCount() == before) {
			// Not storable (e.g. a filled terminal): fall back to vanilla inventory <-> hotbar moves.
			boolean moved;
			if (slotIndex < GRID_END) {
				moved = this.moveItemStackTo(stack, INV_START, INV_END, false);
			} else if (slotIndex < HOTBAR_START) {
				moved = this.moveItemStackTo(stack, HOTBAR_START, INV_END, false);
			} else {
				moved = this.moveItemStackTo(stack, INV_START, HOTBAR_START, false);
			}
			if (!moved) {
				return ItemStack.EMPTY;
			}
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return ItemStack.EMPTY;
	}

	private ItemStack quickMoveResult(Player player, Slot slot) {
		if (!slot.mayPickup(player)) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		stack.getItem().onCraftedBy(stack, player);
		if (!this.moveItemStackTo(stack, INV_START, INV_END, true)) {
			return ItemStack.EMPTY;
		}
		slot.onQuickCraft(stack, original);
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == original.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(player, stack);
		if (!stack.isEmpty()) {
			// Did not fully fit into the inventory: keep the rest in the storage instead of dropping it.
			TerminalStorage storage = this.storage();
			if (storage != null) {
				storage.insertStack(stack);
			}
			if (!stack.isEmpty()) {
				player.drop(stack, false, Prediction.SERVER_ONLY);
			}
		}
		return original;
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		return target.container != this.resultSlots && super.canTakeItemForPickAll(carried, target);
	}

	// ------------------------------------------------------------------------------------------
	// Crafting
	// ------------------------------------------------------------------------------------------

	@Override
	public void slotsChanged(Container container) {
		if (!this.suppressCraftUpdates) {
			this.refreshCraftingResult();
		}
	}

	@Override
	protected void beginPlacingRecipe() {
		this.suppressCraftUpdates = true;
	}

	@Override
	protected void finishPlacingRecipe(ServerLevel level, RecipeHolder<CraftingRecipe> recipe) {
		this.suppressCraftUpdates = false;
		this.updateCraftingResult(level, recipe);
	}

	private void refreshCraftingResult() {
		if (this.host != null && this.player.level() instanceof ServerLevel serverLevel) {
			this.updateCraftingResult(serverLevel, null);
		}
	}

	private void updateCraftingResult(ServerLevel level, @Nullable RecipeHolder<CraftingRecipe> hint) {
		if (!(this.player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		CraftingInput input = this.craftSlots.asCraftInput();
		ItemStack result = ItemStack.EMPTY;
		Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level, hint);
		if (recipe.isPresent() && this.resultSlots.setRecipeUsed(serverPlayer, recipe.get())) {
			ItemStack assembled = recipe.get().value().assemble(input);
			if (assembled.isItemEnabled(level.enabledFeatures())) {
				result = assembled;
			}
		}
		this.resultSlots.setItem(0, result);
		this.setRemoteSlot(RESULT_SLOT, result);
		serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(this.containerId, this.incrementStateId(), RESULT_SLOT, result));
	}

	private ItemStack[] snapshotGrid() {
		ItemStack[] snapshot = new ItemStack[this.craftSlots.getContainerSize()];
		for (int i = 0; i < snapshot.length; i++) {
			snapshot[i] = this.craftSlots.getItem(i).copy();
		}
		return snapshot;
	}

	/** After crafting: top the grid back up to what it was, pulling from the storage. */
	private void refillGrid(ItemStack[] before, Player player) {
		TerminalStorage storage = this.storage();
		if (storage == null) {
			return;
		}
		for (int i = 0; i < before.length; i++) {
			ItemStack wanted = before[i];
			if (wanted.isEmpty()) {
				continue;
			}
			ItemStack current = this.craftSlots.getItem(i);
			if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, wanted)) {
				// A crafting remainder (e.g. an empty bucket) took the slot: store it and refill.
				ItemStack remainder = this.craftSlots.removeItemNoUpdate(i);
				storage.insertStack(remainder);
				if (!remainder.isEmpty()) {
					player.getInventory().placeItemBackInInventory(remainder, Prediction.SERVER_ONLY);
				}
				current = ItemStack.EMPTY;
			}
			int missing = wanted.getCount() - current.getCount();
			if (missing <= 0) {
				continue;
			}
			ItemStack refill = storage.extractStack(ItemVariant.of(wanted), missing);
			if (refill.isEmpty()) {
				continue;
			}
			if (current.isEmpty()) {
				this.craftSlots.setItem(i, refill);
			} else {
				current.grow(refill.getCount());
			}
		}
	}

	private final class TerminalResultSlot extends ResultSlot {
		private TerminalResultSlot(Player player, int x, int y) {
			super(player, TerminalMenu.this.craftSlots, TerminalMenu.this.resultSlots, 0, x, y);
		}

		@Override
		public boolean mayPickup(Player player) {
			return TerminalMenu.this.craftsThisClick < TerminalMenu.this.craftLimit && super.mayPickup(player);
		}

		@Override
		public void onTake(Player player, ItemStack carried) {
			TerminalMenu.this.craftsThisClick++;
			if (TerminalMenu.this.host == null) {
				super.onTake(player, carried);
				return;
			}
			ItemStack[] before = TerminalMenu.this.snapshotGrid();
			TerminalMenu.this.suppressCraftUpdates = true;
			try {
				super.onTake(player, carried);
				TerminalMenu.this.refillGrid(before, player);
			} finally {
				TerminalMenu.this.suppressCraftUpdates = false;
			}
			TerminalMenu.this.refreshCraftingResult();
		}
	}

	// ------------------------------------------------------------------------------------------
	// Recipe book: recipes are filled from the storage (and the player inventory)
	// ------------------------------------------------------------------------------------------

	@Override
	public void fillCraftSlotsStackedContents(StackedItemContents contents) {
		super.fillCraftSlotsStackedContents(contents);
		TerminalStorage storage = this.storage();
		Object2LongMap<ItemVariant> source = this.host == null ? this.clientItems : storage != null ? storage.view() : Object2LongMaps.emptyMap();
		for (Object2LongMap.Entry<ItemVariant> entry : source.object2LongEntrySet()) {
			int count = (int) Math.min(entry.getLongValue(), 64L * 9);
			ItemStack stack = entry.getKey().toStack(count);
			if (Inventory.isUsableForCrafting(stack)) {
				contents.accountStack(stack, count);
			}
		}
	}

	@Override
	public PostPlaceAction handlePlacement(
		boolean useMaxItems, boolean allowDroppingItemsToClear, RecipeHolder<?> recipe, ServerLevel level, Inventory inventory
	) {
		if (this.storage() == null) {
			return super.handlePlacement(useMaxItems, allowDroppingItemsToClear, recipe, level, inventory);
		}
		@SuppressWarnings("unchecked")
		RecipeHolder<CraftingRecipe> craftingRecipe = (RecipeHolder<CraftingRecipe>) recipe;
		this.beginPlacingRecipe();
		try {
			return this.placeRecipeFromStorage(craftingRecipe, useMaxItems, level, inventory);
		} finally {
			this.finishPlacingRecipe(level, craftingRecipe);
		}
	}

	private PostPlaceAction placeRecipeFromStorage(RecipeHolder<CraftingRecipe> recipe, boolean useMaxItems, ServerLevel level, Inventory inventory) {
		boolean alreadyPlaced = recipe.value().matches(this.craftSlots.asCraftInput(), level);
		int smallestPlaced = Integer.MAX_VALUE;
		if (alreadyPlaced) {
			for (ItemStack stack : this.craftSlots.getItems()) {
				if (!stack.isEmpty()) {
					smallestPlaced = Math.min(smallestPlaced, stack.getCount());
				}
			}
		}

		this.returnGridToStorage(this.player);

		StackedItemContents available = new StackedItemContents();
		this.fillCraftSlotsStackedContents(available);
		inventory.fillStackedContents(available);

		if (!available.canCraft(recipe.value(), null)) {
			return PostPlaceAction.PLACE_GHOST_RECIPE;
		}

		int biggest = available.getBiggestCraftableStack(recipe.value(), null);
		int amount;
		if (useMaxItems) {
			amount = biggest;
		} else if (smallestPlaced != Integer.MAX_VALUE) {
			amount = Math.min(biggest, smallestPlaced + 1);
		} else {
			amount = 1;
		}

		List<Holder<Item>> itemsUsed = new ArrayList<>();
		if (!available.canCraft(recipe.value(), amount, itemsUsed::add)) {
			return PostPlaceAction.NOTHING;
		}
		int clamped = amount;
		for (Holder<Item> item : itemsUsed) {
			clamped = Math.min(clamped, item.components().getOrDefault(DataComponents.MAX_STACK_SIZE, 1));
		}
		if (clamped != amount) {
			amount = clamped;
			itemsUsed.clear();
			if (!available.canCraft(recipe.value(), amount, itemsUsed::add)) {
				return PostPlaceAction.NOTHING;
			}
		}

		int perSlot = amount;
		PlaceRecipeHelper.placeRecipe(
			this.getGridWidth(),
			this.getGridHeight(),
			recipe.value(),
			recipe.value().placementInfo().slotsToIngredientIndex(),
			(ingredientIndex, gridIndex, gridX, gridY) -> {
				if (ingredientIndex == -1) {
					return;
				}
				ItemStack taken = this.takeIngredient(itemsUsed.get(ingredientIndex), perSlot, inventory);
				if (!taken.isEmpty()) {
					this.craftSlots.setItem(gridIndex, taken);
				}
			}
		);
		inventory.setChanged();
		return PostPlaceAction.NOTHING;
	}

	/** Takes up to {@code amount} items of the given type: storage first, then the player inventory. */
	private ItemStack takeIngredient(Holder<Item> item, int amount, Inventory inventory) {
		TerminalStorage storage = Objects.requireNonNull(this.storage());
		ItemVariant plain = ItemVariant.of(item.value());
		ItemStack result = storage.extractStack(plain, amount);

		if (result.getCount() < amount) {
			for (ItemVariant variant : new ArrayList<>(storage.view().keySet())) {
				if (result.getCount() >= amount) {
					break;
				}
				if (!variant.isOf(item.value()) || (!result.isEmpty() && !variant.matches(result))) {
					continue;
				}
				if (!Inventory.isUsableForCrafting(variant.toStack())) {
					continue;
				}
				ItemStack extracted = storage.extractStack(variant, amount - result.getCount());
				if (result.isEmpty()) {
					result = extracted;
				} else {
					result.grow(extracted.getCount());
				}
			}
		}

		while (result.getCount() < amount) {
			int slot = inventory.findSlotMatchingCraftingIngredient(item, result);
			if (slot == -1) {
				break;
			}
			ItemStack taken = inventory.removeItem(slot, amount - result.getCount());
			if (result.isEmpty()) {
				result = taken;
			} else {
				result.grow(taken.getCount());
			}
		}
		return result;
	}

	// ------------------------------------------------------------------------------------------
	// Recipe transfer from JEI: the "+" button sends the items each grid slot accepts
	// ------------------------------------------------------------------------------------------

	public void handleRecipeTransfer(ServerPlayer player, List<List<ItemVariant>> slots, boolean maxTransfer) {
		if (this.host == null || player.isSpectator() || !this.stillValid(player)) {
			return;
		}
		// Clicking "+" again on the recipe that is already in the grid adds one more set (like the recipe book).
		int placed = this.setsInGrid(slots);

		this.suppressCraftUpdates = true;
		try {
			this.returnGridToStorage(player);
			TerminalStorage storage = this.storage();
			ItemStack locked = this.host.lockedItem(player);
			RecipeFill.Plan plan = RecipeFill.plan(slots, RecipeFill.available(
				storage != null ? storage.view() : Object2LongMaps.emptyMap(), player.getInventory(), List.of(), locked
			));
			if (plan.sets() > 0) {
				int sets = maxTransfer ? plan.sets() : Math.min(plan.sets(), placed + 1);
				for (int i = 0; i < RecipeFill.GRID_SIZE; i++) {
					ItemVariant variant = plan.variants()[i];
					if (variant != null) {
						this.craftSlots.setItem(i, this.takeForGrid(variant, sets, storage, player.getInventory(), locked));
					}
				}
				player.getInventory().setChanged();
			}
		} finally {
			this.suppressCraftUpdates = false;
		}
		this.refreshCraftingResult();
		this.broadcastChanges();
	}

	/** How many sets of the recipe the grid holds right now (0 if it holds anything else). */
	private int setsInGrid(List<List<ItemVariant>> slots) {
		int sets = Integer.MAX_VALUE;
		for (int i = 0; i < this.craftSlots.getContainerSize(); i++) {
			ItemStack stack = this.craftSlots.getItem(i);
			List<ItemVariant> alternatives = i < slots.size() ? slots.get(i) : List.of();
			if (alternatives.isEmpty() != stack.isEmpty() || (!stack.isEmpty() && !RecipeFill.accepts(alternatives, stack))) {
				return 0;
			}
			if (!stack.isEmpty()) {
				sets = Math.min(sets, stack.getCount());
			}
		}
		return sets == Integer.MAX_VALUE ? 0 : sets;
	}

	/** Takes up to {@code amount} items of exactly this variant: storage first, then the player inventory. */
	private ItemStack takeForGrid(ItemVariant variant, int amount, @Nullable TerminalStorage storage, Inventory inventory, ItemStack locked) {
		ItemStack result = storage != null ? storage.extractStack(variant, amount) : ItemStack.EMPTY;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE && result.getCount() < amount; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || stack == locked || !variant.matches(stack)) {
				continue;
			}
			ItemStack taken = inventory.removeItem(slot, amount - result.getCount());
			if (result.isEmpty()) {
				result = taken;
			} else {
				result.grow(taken.getCount());
			}
		}
		return result;
	}

	@Override
	public Slot getResultSlot() {
		return this.slots.get(RESULT_SLOT);
	}

	@Override
	public List<Slot> getInputGridSlots() {
		return this.slots.subList(GRID_START, GRID_END);
	}

	@Override
	public RecipeBookType getRecipeBookType() {
		return RecipeBookType.CRAFTING;
	}

	@Override
	protected Player owner() {
		return this.player;
	}
}
