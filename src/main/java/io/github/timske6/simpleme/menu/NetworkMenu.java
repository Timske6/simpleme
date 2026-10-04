package io.github.timske6.simpleme.menu;

import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** A menu whose network can be chosen in the network panel (terminal, wireless terminal, transfer chest). */
public interface NetworkMenu {
	/** Server: the connected network, or {@code null}. */
	@Nullable UUID networkId();

	/** Server: connects to another network (access was checked), or disconnects with {@code null}. */
	void connectNetwork(ServerPlayer player, @Nullable UUID id);

	/** Client: the network list last received from the server. */
	NetworkClientState networkState();
}
