package io.github.timske6.simpleme.network;

import io.github.timske6.simpleme.SimpleME;
import io.github.timske6.simpleme.storage.ItemGroups;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the item groups of the connected network that the player sees. */
public record GroupListPayload(int containerId, List<Entry> groups) implements CustomPacketPayload {
	public static final Type<GroupListPayload> TYPE = new Type<>(SimpleME.id("group_list"));

	public static final StreamCodec<RegistryFriendlyByteBuf, GroupListPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GroupListPayload::containerId,
		Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), GroupListPayload::groups,
		GroupListPayload::new
	);

	/**
	 * @param canManage the player made the group and may rename, share, delete and change it
	 * @param hidden the player chose not to show it
	 */
	public record Entry(UUID id, String name, String ownerName, boolean shared, boolean canManage, boolean hidden, List<ItemVariant> items) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, Entry::id,
			ByteBufCodecs.stringUtf8(ItemGroups.MAX_NAME_LENGTH * 4), Entry::name,
			ByteBufCodecs.PLAYER_NAME, Entry::ownerName,
			ByteBufCodecs.BOOL, Entry::shared,
			ByteBufCodecs.BOOL, Entry::canManage,
			ByteBufCodecs.BOOL, Entry::hidden,
			ItemVariant.PACKET_CODEC.apply(ByteBufCodecs.list(ItemGroups.MAX_ITEMS)), Entry::items,
			Entry::new
		);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
