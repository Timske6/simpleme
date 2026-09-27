package io.github.timske6.simpleme.menu;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TerminalBlockEntity;
import io.github.timske6.simpleme.network.StorageClickPayload;
import io.github.timske6.simpleme.network.StorageSyncPayload;
import io.github.timske6.simpleme.storage.StoredItems;
import io.github.timske6.simpleme.storage.TerminalStorage;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.recipebook.PlaceRecipeHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
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
 * Menu of the ME terminal: a virtual item grid (synced via custom packets), a 3x3 crafting
 * grid that refills itself from the storage and the player inventory.
 */
public class TerminalMenu extends AbstractCraftingMenu {
	public static final int RESULT_SLOT = 0;
	public static final int GRID_START = 1;
	public static final int GRID_END = 10;
	public static final int INV_START = 10;
	public static final int HOTBAR_START = 37;
	public static final int INV_END = 46;

	public static final int BUTTON_CLEAR_GRID = 0;

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
	private final ContainerLevelAccess access;
	private final @Nullable TerminalBlockEntity terminal;

	// server side sync state
	private final Object2LongOpenHashMap<ItemVariant> lastSent = new Object2LongOpenHashMap<>();
	private long lastSentVersion = Long.MIN_VALUE;
	private int lastSyncTick = -1;
	private boolean suppressCraftUpdates;
	/** Crafts allowed during the click that is being handled (shift-click / ctrl+Q would otherwise empty the storage). */
	private int craftLimit = Integer.MAX_VALUE;
	private int craftsThisClick;

	// client side mirror of the storage
	private final Object2LongLinkedOpenHashMap<ItemVariant> clientItems = new Object2LongLinkedOpenHashMap<>();
	private int clientRevision;

	/** Client constructor (called through the menu type with the block position). */
	public TerminalMenu(int containerId, Inventory inventory, BlockPos pos) {
		this(containerId, inventory, (TerminalBlockEntity) null);
	}

	/** Server constructor. */
	public TerminalMenu(int containerId, Inventory inventory, @Nullable TerminalBlockEntity terminal) {
		super(ModRegistry.TERMINAL_MENU, containerId, 3, 3);
		this.player = inventory.player;
		this.terminal = terminal;
		this.access = terminal != null && terminal.getLevel() != null
			? ContainerLevelAccess.create(terminal.getLevel(), terminal.getBlockPos())
			: ContainerLevelAccess.NULL;

		this.rows = inventory.player.level().isClientSide() ? Mth.clamp(clientRows.getAsInt(), MIN_ROWS, MAX_ROWS) : DEFAULT_ROWS;
		this.craftingY = GRID_Y + this.rows * 18 + 8;
		this.inventoryLabelY = this.craftingY + 54 + 3;
		this.inventoryY = this.inventoryLabelY + 11;
		this.imageHeight = this.inventoryY + 58 + 18 + 6;

		this.addSlot(new TerminalResultSlot(this.player, RESULT_X, this.craftingY + 18));
		this.addCraftingGridSlots(CRAFT_GRID_X, this.craftingY);
		this.addStandardInventorySlots(inventory, 8, this.inventoryY);
	}

	public boolean isServerSide() {
		return this.terminal != null;
	}

	// ------------------------------------------------------------------------------------------
	// Validity / lifecycle
	// ------------------------------------------------------------------------------------------

	@Override
	public boolean stillValid(Player player) {
		if (this.terminal != null && this.terminal.isRemoved()) {
			return false;
		}
		return stillValid(this.access, player, ModRegistry.TERMINAL_BLOCK);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (this.terminal != null) {
			this.returnGridToStorage(player);
		}
	}

	private void returnGridToStorage(Player player) {
		boolean storageAvailable = this.terminal != null && !this.terminal.isRemoved();
		for (int i = 0; i < this.craftSlots.getContainerSize(); i++) {
			ItemStack stack = this.craftSlots.removeItemNoUpdate(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (storageAvailable) {
				this.terminal.getStorage().insertStack(stack);
			}
			if (!stack.isEmpty()) {
				if (player.isRemoved() || (player instanceof ServerPlayer serverPlayer && serverPlayer.hasDisconnected())) {
					player.drop(stack, false);
				} else {
					player.getInventory().placeItemBackInInventory(stack);
				}
			}
		}
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (buttonId == BUTTON_CLEAR_GRID) {
			if (this.terminal != null) {
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
		return false;
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
		if (this.terminal == null || !(this.player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		TerminalStorage storage = this.terminal.getStorage();
		if (storage.getVersion() == this.lastSentVersion) {
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
			ServerPlayNetworking.send(serverPlayer, new StorageSyncPayload(this.containerId, true, List.of()));
		}
		// Split big updates so a single packet never gets huge (e.g. a storage full of written books).
		for (int start = 0; start < changes.size(); start += StorageSyncPayload.MAX_ENTRIES) {
			List<StoredItems.Entry> batch = changes.subList(start, Math.min(changes.size(), start + StorageSyncPayload.MAX_ENTRIES));
			ServerPlayNetworking.send(serverPlayer, new StorageSyncPayload(this.containerId, reset && start == 0, List.copyOf(batch)));
		}
	}

	/** Client: apply a sync packet to the local mirror. */
	public void applySync(StorageSyncPayload payload) {
		if (payload.reset()) {
			this.clientItems.clear();
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

	// ------------------------------------------------------------------------------------------
	// Clicks on the virtual item grid (client → server)
	// ------------------------------------------------------------------------------------------

	public void handleStorageClick(ServerPlayer player, ItemVariant variant, int action) {
		if (this.terminal == null || player.isSpectator() || !this.stillValid(player)) {
			return;
		}
		TerminalStorage storage = this.terminal.getStorage();
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
					player.getInventory().placeItemBackInInventory(stack);
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

	private static int maxStackSize(ItemVariant variant) {
		return variant.toStack().getMaxStackSize();
	}

	// ------------------------------------------------------------------------------------------
	// Shift-clicking
	// ------------------------------------------------------------------------------------------

	@Override
	public void clicked(int slotIndex, int buttonNum, ContainerInput containerInput, Player player) {
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

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		Slot slot = this.slots.get(slotIndex);
		if (slot == null || !slot.hasItem() || this.terminal == null) {
			// The client does not predict shift-clicks; the server answers with the real result.
			return ItemStack.EMPTY;
		}

		if (slotIndex == RESULT_SLOT) {
			return this.quickMoveResult(player, slot);
		}

		ItemStack stack = slot.getItem();
		int before = stack.getCount();
		this.terminal.getStorage().insertStack(stack);
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
			this.terminal.getStorage().insertStack(stack);
			if (!stack.isEmpty()) {
				player.drop(stack, false);
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
		this.access.execute((level, pos) -> {
			if (level instanceof ServerLevel serverLevel) {
				this.updateCraftingResult(serverLevel, null);
			}
		});
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
		TerminalStorage storage = this.terminal.getStorage();
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
					player.getInventory().placeItemBackInInventory(remainder);
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
			if (TerminalMenu.this.terminal == null) {
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
		Object2LongMap<ItemVariant> source = this.terminal != null ? this.terminal.getStorage().view() : this.clientItems;
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
		if (this.terminal == null) {
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
		TerminalStorage storage = this.terminal.getStorage();
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
