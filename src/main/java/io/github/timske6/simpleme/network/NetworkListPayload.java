package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.Networks;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client: the networks the player can use, and the one the open menu is connected to
 * ({@code current} is empty if there is none or the player has no access to it).
 */
public record NetworkListPayload(int containerId, Optional<UUID> current, String currentName, List<Entry> entries) implements CustomPacketPayload {
	public static final Type<NetworkListPayload> TYPE = new Type<>(SimpleME.id("network_list"));
	private static final StreamCodec<io.netty.buffer.ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(Networks.MAX_NAME_LENGTH * 4);

	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkListPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, NetworkListPayload::containerId,
		ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), NetworkListPayload::current,
		NAME, NetworkListPayload::currentName,
		Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), NetworkListPayload::entries,
		NetworkListPayload::new
	);

	/**
	 * @param canManage the player may rename / delete it
	 * @param hasOwner networks taken over from old terminals have no owner and are always public
	 */
	public record Entry(UUID id, String name, String ownerName, boolean isPublic, boolean canManage, boolean hasOwner, int types, long total) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, Entry::id,
			NAME, Entry::name,
			ByteBufCodecs.PLAYER_NAME, Entry::ownerName,
			ByteBufCodecs.BOOL, Entry::isPublic,
			ByteBufCodecs.BOOL, Entry::canManage,
			ByteBufCodecs.BOOL, Entry::hasOwner,
			ByteBufCodecs.VAR_INT, Entry::types,
			ByteBufCodecs.VAR_LONG, Entry::total,
			Entry::new
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
