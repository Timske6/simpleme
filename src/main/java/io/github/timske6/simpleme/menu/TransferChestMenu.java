package io.github.timske6.simpleme.menu;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.block.TransferChestBlockEntity;
import io.github.timske6.simpleme.network.NetworkActions;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** Menu of the transfer chest: 54 slots like a double chest, plus the on/off switch and the network. */
public class TransferChestMenu extends AbstractContainerMenu implements NetworkMenu {
	public static final int BUTTON_TOGGLE = 0;

	public static final int DATA_ENABLED = 0;
	public static final int DATA_STATUS = 1;
	public static final int DATA_COUNT = 2;

	public static final int STATUS_UNLINKED = 0;
	public static final int STATUS_OFFLINE = 1;
	public static final int STATUS_ONLINE = 2;

	public static final int ROWS = 6;
	public static final int INVENTORY_Y = 18 + ROWS * 18 + 13;

	private final Container container;
	private final ContainerData data;
	private final @Nullable TransferChestBlockEntity chest;
	private final Player player;
	private final NetworkClientState networkState = new NetworkClientState();
	private boolean networkSent;
	private @Nullable UUID sentNetworkId;

	/** Client constructor. */
	public TransferChestMenu(int containerId, Inventory inventory) {
		this(containerId, inventory, new SimpleContainer(TransferChestBlockEntity.SIZE), new SimpleContainerData(DATA_COUNT), null);
	}

	/** Server constructor. */
	public TransferChestMenu(int containerId, Inventory inventory, Container container, ContainerData data, @Nullable TransferChestBlockEntity chest) {
		super(ModRegistry.TRANSFER_CHEST_MENU, containerId);
		this.chest = chest;
		this.player = inventory.player;
		checkContainerSize(container, TransferChestBlockEntity.SIZE);
		checkContainerDataCount(data, DATA_COUNT);
		this.container = container;
		this.data = data;
		container.startOpen(inventory.player);

		for (int row = 0; row < ROWS; row++) {
			for (int column = 0; column < 9; column++) {
				this.addSlot(new Slot(container, column + row * 9, 8 + column * 18, 18 + row * 18));
			}
		}
		this.addStandardInventorySlots(inventory, 8, INVENTORY_Y);
		this.addDataSlots(data);
	}

	@Override
	public @Nullable UUID networkId() {
		StorageLink link = this.chest != null ? this.chest.getLink() : null;
		return link != null ? link.id() : null;
	}

	@Override
	public void connectNetwork(ServerPlayer player, @Nullable UUID id) {
		if (this.chest != null) {
			this.chest.setNetwork(Networks.get(player.level().getServer()).find(id));
			this.broadcastChanges();
		}
	}

	@Override
	public NetworkClientState networkState() {
		return this.networkState;
	}

	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		UUID networkId = this.networkId();
		if (this.chest != null && this.player instanceof ServerPlayer serverPlayer && (!this.networkSent || !Objects.equals(networkId, this.sentNetworkId))) {
			this.networkSent = true;
			this.sentNetworkId = networkId;
			NetworkActions.sendList(serverPlayer, this.containerId, networkId);
		}
	}

	public boolean isEnabled() {
		return this.data.get(DATA_ENABLED) != 0;
	}

	public int status() {
		return this.data.get(DATA_STATUS);
	}

	@Override
	public boolean clickMenuButton(Player player, int buttonId) {
		if (buttonId == BUTTON_TOGGLE) {
			this.data.set(DATA_ENABLED, this.isEnabled() ? 0 : 1);
			return true;
		}
		return false;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.container.stillValid(player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		ItemStack clicked = ItemStack.EMPTY;
		Slot slot = this.slots.get(slotIndex);
		if (slot != null && slot.hasItem()) {
			ItemStack stack = slot.getItem();
			clicked = stack.copy();
			int size = this.container.getContainerSize();
			if (slotIndex < size) {
				if (!this.moveItemStackTo(stack, size, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else if (!this.moveItemStackTo(stack, 0, size, false)) {
				return ItemStack.EMPTY;
			}
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
		}
		return clicked;
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		this.container.stopOpen(player);
	}
}
