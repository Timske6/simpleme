package io.github.timske6.simpleme.block;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.menu.TerminalHost;
import io.github.timske6.simpleme.menu.TerminalMenu;
import io.github.timske6.simpleme.storage.Networks;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A terminal shows the items of one ME network. The items belong to the network, not to the
 * terminal: breaking a terminal drops a plain terminal and the items stay in the network.
 */
public class TerminalBlockEntity extends BlockEntity implements ExtendedMenuProvider<BlockPos>, TerminalHost {
	/** The network id (same tag as the storage id of version 1.0, whose storages become networks). */
	private static final String NETWORK_TAG = "Storage";
	/** Pre-release format that kept the items inside the block entity; migrated on first use. */
	private static final String LEGACY_ITEMS_TAG = "Items";

	private @Nullable UUID networkId;
	private List<StoredItems.Entry> legacyItems = List.of();
	/** Whether items stored inside the block or item (pre-release format) have been moved into a network. */
	private boolean migrated;

	public TerminalBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.TERMINAL_BLOCK_ENTITY, pos, state);
	}

	/** The network this terminal shows, or {@code null}. Server side only. */
	@Override
	public @Nullable UUID getNetworkId() {
		this.migrate();
		return this.networkId;
	}

	@Override
	public void setNetworkId(@Nullable UUID id) {
		this.migrate();
		this.networkId = id;
		this.setChanged();
	}

	/** The storage of the connected network (no access check), or {@code null}. Server side only. */
	public @Nullable TerminalStorage getStorage() {
		return this.level instanceof ServerLevel serverLevel ? Networks.storage(serverLevel.getServer(), this.getNetworkId()) : null;
	}

	/**
	 * Turns old data into a network: items inside the block or item (pre-release) once, and the
	 * storage of version 1.0 whenever its network does not exist. A network that is gone (only
	 * empty networks can be deleted) is forgotten.
	 */
	private void migrate() {
		if (!(this.level instanceof ServerLevel serverLevel)) {
			return;
		}
		MinecraftServer server = serverLevel.getServer();
		Networks networks = Networks.get(server);
		if (!this.migrated) {
			this.migrated = true;
			if (!this.legacyItems.isEmpty()) {
				Networks.Network network = networks.find(this.networkId);
				if (network == null) {
					network = networks.adoptLegacy(this.networkId != null ? this.networkId : UUID.randomUUID(), this.legacyName());
				}
				TerminalStorage storage = StorageData.get(server, network.id()).storage();
				for (StoredItems.Entry entry : this.legacyItems) {
					storage.insert(entry.variant(), entry.count());
				}
				this.legacyItems = List.of();
				this.networkId = network.id();
				this.setChanged();
			}
		}
		if (this.networkId != null && networks.find(this.networkId) == null) {
			if (!StorageData.get(server, this.networkId).storage().isEmpty()) {
				// the storage of a terminal from version 1.0
				networks.adoptLegacy(this.networkId, this.legacyName());
			} else {
				this.networkId = null;
				this.setChanged();
			}
		}
	}

	private String legacyName() {
		return "Terminal " + this.worldPosition.getX() + ", " + this.worldPosition.getY() + ", " + this.worldPosition.getZ();
	}

	@Override
	public boolean stillValid(Player player) {
		return !this.isRemoved() && Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		// old data must become a network before the terminal is gone, or its items could not be reached any more
		this.migrate();
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.networkId = input.read(NETWORK_TAG, UUIDUtil.CODEC).orElse(null);
		this.legacyItems = input.listOrEmpty(LEGACY_ITEMS_TAG, StoredItems.Entry.CODEC).stream().toList();
		this.migrated = false;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.networkId != null) {
			output.store(NETWORK_TAG, UUIDUtil.CODEC, this.networkId);
		}
		if (!this.legacyItems.isEmpty()) {
			ValueOutput.TypedOutputList<StoredItems.Entry> list = output.list(LEGACY_ITEMS_TAG, StoredItems.Entry.CODEC);
			this.legacyItems.forEach(list::add);
		}
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		// terminal items of version 1.0 still carry their storage
		TerminalContents contents = components.get(ModRegistry.STORAGE);
		if (contents != null) {
			this.networkId = contents.id();
			this.migrated = false;
		}
		StoredItems legacy = components.get(ModRegistry.LEGACY_STORED_ITEMS);
		if (legacy != null) {
			this.legacyItems = legacy.entries();
			this.migrated = false;
		}
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		// nothing to keep: the items are in the network, so terminals drop as plain, stackable items
		this.migrate();
	}

	@Override
	@SuppressWarnings("deprecation")
	public void removeComponentsFromTag(ValueOutput output) {
		super.removeComponentsFromTag(output);
		output.discard(NETWORK_TAG);
		output.discard(LEGACY_ITEMS_TAG);
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable("container.simpleme.terminal");
	}

	@Override
	public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
		return new TerminalMenu(ModRegistry.TERMINAL_MENU, containerId, inventory, this);
	}

	@Override
	public BlockPos getScreenOpeningData(ServerPlayer player) {
		return this.worldPosition;
	}
}
