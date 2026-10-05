package io.github.timske6.simpleme.menu;

import java.util.UUID;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** What a terminal menu is opened from: a placed terminal block or a wireless terminal. Server side only. */
public interface TerminalHost {
	/** The network shown, or {@code null}. */
	@Nullable UUID getNetworkId();

	/** Connects to another network (access was checked), or disconnects. */
	void setNetworkId(@Nullable UUID id);

	/** How the terminal sorts its items (see {@code TerminalMenu.SORT_MODES}); every terminal keeps its own. */
	int getSortMode();

	void setSortMode(int mode);

	/** Whether the menu may stay open. */
	boolean stillValid(Player player);

	/** An item of the player that must not be moved while the menu is open (the wireless terminal in use). */
	default ItemStack lockedItem(Player player) {
		return ItemStack.EMPTY;
	}
}
