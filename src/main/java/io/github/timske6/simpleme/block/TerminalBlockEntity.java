package io.github.timske6.simpleme.block;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.storage.StorageData;
import io.github.timske6.simpleme.storage.StoredItems;
import io.github.timske6.simpleme.storage.TerminalContents;
import io.github.timske6.simpleme.storage.TerminalStorage;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class TerminalBlockEntity extends BlockEntity implements ExtendedMenuProvider<BlockPos> {
	private static final String STORAGE_TAG = "Storage";
	/** Pre-release format that kept the items inside the block entity; migrated on first use. */
	private static final String LEGACY_ITEMS_TAG = "Items";

	private @Nullable UUID storageId;
	private @Nullable TerminalStorage storage;
	private List<StoredItems.Entry> legacyItems = List.of();

	public TerminalBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.TERMINAL_BLOCK_ENTITY, pos, state);
	}

	/** The storage of this terminal. Server side only. */
	public TerminalStorage getStorage() {
		if (this.storage == null) {
			if (!(this.level instanceof ServerLevel serverLevel)) {
				throw new IllegalStateException("Terminal storage is only available on the server");
			}
			if (this.storageId == null) {
				this.storageId = UUID.randomUUID();
				this.setChanged();
			}
			this.storage = StorageData.get(serverLevel.getServer(), this.storageId).storage();
			if (!this.legacyItems.isEmpty()) {
				for (StoredItems.Entry entry : this.legacyItems) {
					this.storage.insert(entry.variant(), entry.count());
				}
				this.legacyItems = List.of();
				this.setChanged();
			}
		}
		return this.storage;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.storageId = input.read(STORAGE_TAG, UUIDUtil.CODEC).orElse(null);
		this.storage = null;
		this.legacyItems = input.listOrEmpty(LEGACY_ITEMS_TAG, StoredItems.Entry.CODEC).stream().toList();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.storageId != null) {
			output.store(STORAGE_TAG, UUIDUtil.CODEC, this.storageId);
		}
		if (!this.legacyItems.isEmpty()) {
			ValueOutput.TypedOutputList<StoredItems.Entry> list = output.list(LEGACY_ITEMS_TAG, StoredItems.Entry.CODEC);
			this.legacyItems.forEach(list::add);
		}
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		TerminalContents contents = components.get(ModRegistry.STORAGE);
		if (contents != null) {
			this.storageId = contents.id();
			this.storage = null;
		}
		StoredItems legacy = components.get(ModRegistry.LEGACY_STORED_ITEMS);
		if (legacy != null) {
			this.legacyItems = legacy.entries();
		}
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		if (this.level instanceof ServerLevel && (this.storageId != null || !this.legacyItems.isEmpty())) {
			TerminalStorage storage = this.getStorage();
			if (!storage.isEmpty()) {
				components.set(ModRegistry.STORAGE, new TerminalContents(this.storageId, storage.typeCount(), storage.totalCount()));
			}
		}
	}

	@Override
	@SuppressWarnings("deprecation")
	public void removeComponentsFromTag(ValueOutput output) {
		super.removeComponentsFromTag(output);
		output.discard(STORAGE_TAG);
		output.discard(LEGACY_ITEMS_TAG);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.simpleme.terminal");
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new TerminalMenu(containerId, inventory, this);
	}

	@Override
	public BlockPos getScreenOpeningData(ServerPlayer player) {
		return this.worldPosition;
	}
}
