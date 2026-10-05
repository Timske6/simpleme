package io.github.timske6.simpleme.menu;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A terminal menu opened with a wireless terminal. Works at any distance and in every dimension,
 * as long as the player keeps holding the wireless terminal. The network is stored on the item.
 * <p>
 * The menu stays bound to the inventory slot the wireless terminal was in when it was opened:
 * selecting another hotbar slot (which a modified client can do while a menu is open) closes the
 * menu instead of silently switching to whatever is held then.
 */
public final class WirelessTerminalHost implements TerminalHost {
	private final MinecraftServer server;
	private final Player player;
	/** Inventory slot of the wireless terminal (a hotbar slot, or the offhand slot). */
	private final int slot;

	public WirelessTerminalHost(MinecraftServer server, Player player, InteractionHand hand) {
		this.server = server;
		this.player = player;
		this.slot = hand == InteractionHand.OFF_HAND ? Inventory.SLOT_OFFHAND : player.getInventory().getSelectedSlot();
	}

	/** The wireless terminal this menu was opened with, or an empty stack if it is not there any more. */
	private ItemStack terminal() {
		ItemStack stack = this.player.getInventory().getItem(this.slot);
		return stack.is(ModRegistry.WIRELESS_TERMINAL) ? stack : ItemStack.EMPTY;
	}

	@Override
	public @Nullable UUID getNetworkId() {
		StorageLink link = this.terminal().get(ModRegistry.LINK);
		return link != null ? link.id() : null;
	}

	@Override
	public void setNetworkId(@Nullable UUID id) {
		ItemStack held = this.terminal();
		if (held.isEmpty()) {
			return;
		}
		Networks.Network network = Networks.get(this.server).find(id);
		if (network != null) {
			held.set(ModRegistry.LINK, StorageLink.of(network));
		} else {
			held.remove(ModRegistry.LINK);
		}
	}

	@Override
	public int getSortMode() {
		return this.terminal().getOrDefault(ModRegistry.SORT_MODE, 0);
	}

	@Override
	public void setSortMode(int mode) {
		ItemStack held = this.terminal();
		if (held.isEmpty()) {
			return;
		}
		if (mode == 0) {
			held.remove(ModRegistry.SORT_MODE);
		} else {
			held.set(ModRegistry.SORT_MODE, mode);
		}
	}

	@Override
	public boolean stillValid(Player player) {
		boolean selected = this.slot == Inventory.SLOT_OFFHAND || player.getInventory().getSelectedSlot() == this.slot;
		return selected && !this.terminal().isEmpty();
	}

	@Override
	public ItemStack lockedItem(Player player) {
		return this.terminal();
	}
}
