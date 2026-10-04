package io.github.timske6.simpleme.item;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageData;
import io.github.timske6.simpleme.storage.StorageLink;
import io.github.timske6.simpleme.storage.TerminalStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Pick block (middle click) in survival: if the item is not in the inventory, up to one stack is
 * taken from the ME network of a wireless terminal the player carries.
 */
public final class StoragePickBlock {
	private StoragePickBlock() {
	}

	/** Returns whether the pick was handled here (otherwise vanilla handles it). */
	public static boolean tryPick(ServerPlayer player, ItemStack wanted) {
		if (player.hasInfiniteMaterials() || player.isSpectator() || !wanted.isItemEnabled(player.level().enabledFeatures())) {
			return false;
		}
		Inventory inventory = player.getInventory();
		if (inventory.findSlotMatchingItem(wanted) != -1) {
			// already in the inventory: vanilla moves it to the hand
			return false;
		}
		ItemVariant variant = ItemVariant.of(wanted);
		TerminalStorage storage = findStorageWith(player, variant);
		if (storage == null) {
			return false;
		}
		if (inventory.getFreeSlot() == -1) {
			player.sendOverlayMessage(Component.translatable("message.simpleme.pick_no_space").withStyle(ChatFormatting.RED));
			return true;
		}
		ItemStack picked = storage.extractStack(variant, wanted.getMaxStackSize());
		if (picked.isEmpty()) {
			return false;
		}
		// Puts the stack into the hotbar; an item that has to make room there goes to the free slot checked above.
		inventory.addAndPickItem(picked);
		player.connection.send(new ClientboundSetHeldSlotPacket(inventory.getSelectedSlot()));
		player.inventoryMenu.broadcastChanges();
		return true;
	}

	/** The first network (of a wireless terminal in the inventory, that the player may use) that holds the item. */
	private static @Nullable TerminalStorage findStorageWith(ServerPlayer player, ItemVariant variant) {
		MinecraftServer server = player.level().getServer();
		Networks networks = Networks.get(server);
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			StorageLink link = stack.is(ModRegistry.WIRELESS_TERMINAL) ? stack.get(ModRegistry.LINK) : null;
			Networks.Network network = link != null ? networks.find(link.id()) : null;
			if (network != null && network.canAccess(player)) {
				TerminalStorage storage = StorageData.get(server, link.id()).storage();
				if (storage.getAmount(variant) > 0) {
					return storage;
				}
			}
		}
		return null;
	}
}
