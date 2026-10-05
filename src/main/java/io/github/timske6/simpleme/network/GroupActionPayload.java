package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.ItemGroups;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: an action in the group panel of the open terminal. */
public record GroupActionPayload(int containerId, int action, UUID group, String text, Optional<ItemVariant> item) implements CustomPacketPayload {
	/** Create a group with the id {@code group} named {@code text} in the connected network. */
	public static final int CREATE = 0;
	public static final int RENAME = 1;
	public static final int TOGGLE_SHARED = 2;
	public static final int DELETE = 3;
	public static final int ADD_ITEM = 4;
	public static final int REMOVE_ITEM = 5;
	/** Show or hide the group for this player. */
	public static final int SHOW = 6;
	public static final int HIDE = 7;

	public static final Type<GroupActionPayload> TYPE = new Type<>(SimpleME.id("group_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GroupActionPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GroupActionPayload::containerId,
		ByteBufCodecs.VAR_INT, GroupActionPayload::action,
		UUIDUtil.STREAM_CODEC, GroupActionPayload::group,
		ByteBufCodecs.stringUtf8(ItemGroups.MAX_NAME_LENGTH * 4), GroupActionPayload::text,
		ByteBufCodecs.optional(ItemVariant.PACKET_CODEC), GroupActionPayload::item,
		GroupActionPayload::new
	);

	public GroupActionPayload(int containerId, int action, UUID group) {
		this(containerId, action, group, "", Optional.empty());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
