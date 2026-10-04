package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.Networks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: an action in the network panel of the open menu. */
public record NetworkActionPayload(int containerId, int action, Optional<UUID> network, String text) implements CustomPacketPayload {
	/** Just send the list again. */
	public static final int REFRESH = 0;
	/** Create a network named {@code text} and connect to it. */
	public static final int CREATE = 1;
	public static final int CONNECT = 2;
	public static final int DISCONNECT = 3;
	public static final int RENAME = 4;
	public static final int TOGGLE_PUBLIC = 5;
	/** Only empty networks can be deleted. */
	public static final int DELETE = 6;

	public static final Type<NetworkActionPayload> TYPE = new Type<>(SimpleME.id("network_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, NetworkActionPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, NetworkActionPayload::containerId,
		ByteBufCodecs.VAR_INT, NetworkActionPayload::action,
		ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), NetworkActionPayload::network,
		ByteBufCodecs.stringUtf8(Networks.MAX_NAME_LENGTH * 4), NetworkActionPayload::text,
		NetworkActionPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
