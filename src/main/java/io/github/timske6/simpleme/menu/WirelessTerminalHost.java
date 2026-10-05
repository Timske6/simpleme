package io.github.timske6.simpleme.menu;

import io.github.timske6.simpleme.ModRegistry;
import io.github.timske6.simpleme.storage.Networks;
import io.github.timske6.simpleme.storage.StorageLink;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A terminal menu opened with a wireless terminal. Works at any distance and in every dimension,
 * as long as the player keeps holding the wireless terminal. The network is stored on the item.
 */
public final class WirelessTerminalHost implements TerminalHost {
	private final MinecraftServer server;
	private final Player player;
	private final InteractionHand hand;

	public WirelessTerminalHost(MinecraftServer server, Player player, InteractionHand hand) {
		this.server = server;
		this.player = player;
		this.hand = hand;
	}

	@Override
	public @Nullable UUID getNetworkId() {
		StorageLink link = this.player.getItemInHand(this.hand).get(ModRegistry.LINK);
		return link != null ? link.id() : null;
	}

	@Override
	public void setNetworkId(@Nullable UUID id) {
		ItemStack held = this.player.getItemInHand(this.hand);
		Networks.Network network = Networks.get(this.server).find(id);
		if (network != null) {
			held.set(ModRegistry.LINK, StorageLink.of(network));
		} else {
			held.remove(ModRegistry.LINK);
		}
	}

	@Override
	public int getSortMode() {
		return this.player.getItemInHand(this.hand).getOrDefault(ModRegistry.SORT_MODE, 0);
	}

	@Override
	public void setSortMode(int mode) {
		ItemStack held = this.player.getItemInHand(this.hand);
		if (mode == 0) {
			held.remove(ModRegistry.SORT_MODE);
		} else {
			held.set(ModRegistry.SORT_MODE, mode);
		}
	}

	@Override
	public boolean stillValid(Player player) {
		return player.getItemInHand(this.hand).is(ModRegistry.WIRELESS_TERMINAL);
	}

	@Override
	public ItemStack lockedItem(Player player) {
		return player.getItemInHand(this.hand);
	}
}
