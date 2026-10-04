package io.github.timske6.simpleme.menu;

import io.github.timske6.simpleme.network.NetworkListPayload;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Client side: the networks the player can use and the one the open menu is connected to. */
public final class NetworkClientState {
	private List<NetworkListPayload.Entry> entries = List.of();
	private @Nullable UUID current;
	private String currentName = "";
	private boolean received;
	private int revision;

	public void apply(NetworkListPayload payload) {
		this.entries = payload.entries();
		this.current = payload.current().orElse(null);
		this.currentName = payload.currentName();
		this.received = true;
		this.revision++;
	}

	public List<NetworkListPayload.Entry> entries() {
		return this.entries;
	}

	public @Nullable UUID current() {
		return this.current;
	}

	public String currentName() {
		return this.currentName;
	}

	/** Whether the server has sent the state yet. */
	public boolean received() {
		return this.received;
	}

	/** Changes every time new data arrives. */
	public int revision() {
		return this.revision;
	}
}
