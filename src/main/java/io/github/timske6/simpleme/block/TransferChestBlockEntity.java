package io.github.timske6.simpleme.block;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.menu.TransferChestMenu;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import io.github.timske6.simpleme.storage.TerminalStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A 54-slot chest (as big as a double chest). While switched on and connected to a network,
 * everything inside is moved into that network. Items that cannot be stored stay inside.
 */
public class TransferChestBlockEntity extends BaseContainerBlockEntity {
	public static final int SIZE = 54;
	/** How often to retry when nothing changed (storage had no room, ...). */
	private static final int RETRY_INTERVAL = 20;
	private static final String LINK_TAG = "Link";

	private NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);
	private @Nullable StorageLink link;
	/** Set whenever the contents change, so new items are sent on the next tick. */
	private boolean dirty = true;

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case TransferChestMenu.DATA_ENABLED -> TransferChestBlockEntity.this.isEnabled() ? 1 : 0;
				case TransferChestMenu.DATA_STATUS -> TransferChestBlockEntity.this.status();
				default -> 0;
			};
		}

		@Override
		public void set(int index, int value) {
			if (index == TransferChestMenu.DATA_ENABLED) {
				TransferChestBlockEntity.this.setEnabled(value != 0);
			}
		}

		@Override
		public int getCount() {
			return TransferChestMenu.DATA_COUNT;
		}
	};

	public TransferChestBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.TRANSFER_CHEST_BLOCK_ENTITY, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, TransferChestBlockEntity chest) {
		if (!chest.dirty && level.getGameTime() % RETRY_INTERVAL != 0) {
			return;
		}
		chest.dirty = false;
		chest.transferToStorage();
	}

	/** Moves everything that fits into the linked storage. Returns whether anything was moved. */
	public boolean transferToStorage() {
		if (!this.isEnabled() || this.link == null || this.isEmpty() || !(this.level instanceof ServerLevel serverLevel)) {
			return false;
		}
		TerminalStorage storage = Networks.storage(serverLevel.getServer(), this.link.id());
		if (storage == null) {
			return false;
		}
		boolean moved = false;
		for (int i = 0; i < this.items.size(); i++) {
			ItemStack stack = this.items.get(i);
			if (stack.isEmpty()) {
				continue;
			}
			int before = stack.getCount();
			storage.insertStack(stack);
			if (stack.getCount() != before) {
				moved = true;
				if (stack.isEmpty()) {
					this.items.set(i, ItemStack.EMPTY);
				}
			}
		}
		if (moved) {
			this.setChanged();
		}
		return moved;
	}

	public boolean isEnabled() {
		return this.getBlockState().getValue(TransferChestBlock.ENABLED);
	}

	public void setEnabled(boolean enabled) {
		if (this.level != null && enabled != this.isEnabled()) {
			this.level.setBlock(this.worldPosition, this.getBlockState().setValue(TransferChestBlock.ENABLED, enabled), 3);
			this.dirty = true;
		}
	}

	public @Nullable StorageLink getLink() {
		return this.link;
	}

	public void setLink(@Nullable StorageLink link) {
		this.link = link;
		this.dirty = true;
		this.setChanged();
	}

	public void setNetwork(Networks.@Nullable Network network) {
		this.setLink(network != null ? StorageLink.of(network) : null);
	}

	/** One of the {@code TransferChestMenu.STATUS_*} values. Server side only. */
	public int status() {
		if (this.link == null) {
			return TransferChestMenu.STATUS_UNLINKED;
		}
		if (!(this.level instanceof ServerLevel serverLevel)) {
			return TransferChestMenu.STATUS_OFFLINE;
		}
		return Networks.get(serverLevel.getServer()).find(this.link.id()) != null ? TransferChestMenu.STATUS_ONLINE : TransferChestMenu.STATUS_OFFLINE;
	}

	@Override
	public void setChanged() {
		super.setChanged();
		this.dirty = true;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, this.items);
		this.link = input.read(LINK_TAG, StorageLink.CODEC).orElse(null);
		this.dirty = true;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, this.items);
		output.storeNullable(LINK_TAG, StorageLink.CODEC, this.link);
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		this.link = components.get(ModRegistry.LINK);
		this.dirty = true;
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		StorageLink link = this.link;
		if (link != null && this.level instanceof ServerLevel serverLevel) {
			// current name for the tooltip; a deleted network is forgotten
			Networks.Network network = Networks.get(serverLevel.getServer()).find(link.id());
			link = network != null ? StorageLink.of(network) : null;
		}
		components.set(ModRegistry.LINK, link);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void removeComponentsFromTag(ValueOutput output) {
		super.removeComponentsFromTag(output);
		output.discard(LINK_TAG);
	}

	@Override
	public int getContainerSize() {
		return SIZE;
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return this.items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	protected Component getDefaultName() {
		return Component.translatable("container.simpleme.transfer_chest");
	}

	@Override
	protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
		return new TransferChestMenu(containerId, inventory, this, this.dataAccess, this);
	}
}
