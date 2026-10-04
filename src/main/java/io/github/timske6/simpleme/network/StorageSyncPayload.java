package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.StoredItems;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: changes of the terminal contents. With {@code reset} the client replaces its
 * whole view, otherwise each entry sets the new count of one item type (0 = removed).
 * {@code full} marks all packets of a complete sync (menu opened, network switched): only then
 * the client sorts the list again.
 */
public record StorageSyncPayload(int containerId, boolean reset, boolean full, List<StoredItems.Entry> entries) implements CustomPacketPayload {
	public static final int MAX_SIZE = 32 * 1024 * 1024;
	/** Entries per packet; larger updates are split into several packets. */
	public static final int MAX_ENTRIES = 256;
	public static final Type<StorageSyncPayload> TYPE = new Type<>(SimpleME.id("storage_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, StorageSyncPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, StorageSyncPayload::containerId,
		ByteBufCodecs.BOOL, StorageSyncPayload::reset,
		ByteBufCodecs.BOOL, StorageSyncPayload::full,
		StoredItems.Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), StorageSyncPayload::entries,
		StorageSyncPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
